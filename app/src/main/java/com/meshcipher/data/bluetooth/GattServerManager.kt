package com.meshcipher.data.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.meshcipher.domain.model.MeshMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GattServerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var gattServer: BluetoothGattServer? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _receivedMessages = MutableSharedFlow<MeshMessage>(extraBufferCapacity = 64)
    val receivedMessages: SharedFlow<MeshMessage> = _receivedMessages.asSharedFlow()

    private val connectedDevices = CopyOnWriteArraySet<BluetoothDevice>()

    // R-14: per-peer rate limiter — 10 writes per 5 s; 5-minute block on violation.
    private val rateLimiter = GattRateLimiter()

    @Volatile
    private var serviceAdded = false

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            Timber.d("GATT onConnectionStateChange: device=%s, status=%d, newState=%d",
                device.address, status, newState)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Timber.d("GATT device connected: %s", device.address)
                    connectedDevices.add(device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.d("GATT device disconnected: %s", device.address)
                    connectedDevices.remove(device)
                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                serviceAdded = true
                Timber.d("GATT service added successfully: %s", service?.uuid)
            } else {
                Timber.e("Failed to add GATT service: status=%d", status)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Timber.d("GATT onCharacteristicWriteRequest: device=%s, char=%s, bytes=%d, responseNeeded=%s",
                device.address, characteristic.uuid, value.size, responseNeeded)

            when (characteristic.uuid) {
                BluetoothMeshManager.CHARACTERISTIC_MESSAGE_UUID -> {
                    // R-14: drop writes that exceed the per-peer rate limit.
                    if (!rateLimiter.allow(device.address)) {
                        if (responseNeeded) {
                            try {
                                gattServer?.sendResponse(
                                    device, requestId,
                                    BluetoothGatt.GATT_FAILURE, offset, null
                                )
                            } catch (e: SecurityException) {
                                Timber.w(e, "Missing permission to send GATT rate-limit response")
                            }
                        }
                        return
                    }

                    Timber.d("Received message write on MESSAGE characteristic from %s", device.address)
                    handleMessageReceived(device, value)

                    if (responseNeeded) {
                        try {
                            gattServer?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                null
                            )
                        } catch (e: SecurityException) {
                            Timber.w(e, "Missing permission to send GATT response")
                        }
                    }
                }
                else -> {
                    if (responseNeeded) {
                        try {
                            gattServer?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                                offset,
                                null
                            )
                        } catch (e: SecurityException) {
                            Timber.w(e, "Missing permission to send GATT response")
                        }
                    }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == BluetoothMeshManager.CHARACTERISTIC_ACK_UUID) {
                try {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        "ACK".toByteArray()
                    )
                } catch (e: SecurityException) {
                    Timber.w(e, "Missing permission to send GATT response")
                }
            }
        }
    }

    fun startGattServer(): Result<Unit> {
        val manager = bluetoothManager
            ?: return Result.failure(Exception("BluetoothManager not available"))

        Timber.d("Starting GATT server...")

        val service = BluetoothGattService(
            BluetoothMeshManager.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val messageCharacteristic = BluetoothGattCharacteristic(
            BluetoothMeshManager.CHARACTERISTIC_MESSAGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(messageCharacteristic)
        Timber.d("Added MESSAGE characteristic: %s", BluetoothMeshManager.CHARACTERISTIC_MESSAGE_UUID)

        val ackCharacteristic = BluetoothGattCharacteristic(
            BluetoothMeshManager.CHARACTERISTIC_ACK_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(ackCharacteristic)
        Timber.d("Added ACK characteristic: %s", BluetoothMeshManager.CHARACTERISTIC_ACK_UUID)

        return try {
            serviceAdded = false
            gattServer = manager.openGattServer(context, gattCallback)
            if (gattServer == null) {
                Timber.e("Failed to open GATT server - returned null")
                return Result.failure(Exception("Failed to open GATT server"))
            }
            Timber.d("GATT server opened, adding service: %s", BluetoothMeshManager.SERVICE_UUID)
            val added = gattServer?.addService(service)
            Timber.d("addService returned: %s", added)
            Timber.d("GATT server started, waiting for onServiceAdded callback")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Timber.e(e, "Missing Bluetooth permission for GATT server")
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start GATT server")
            Result.failure(e)
        }
    }

    fun isServiceReady(): Boolean = serviceAdded

    fun stopGattServer() {
        try {
            connectedDevices.forEach { device ->
                gattServer?.cancelConnection(device)
            }
            gattServer?.close()
        } catch (e: SecurityException) {
            Timber.w(e, "Missing permission to stop GATT server")
        }
        gattServer = null
        connectedDevices.clear()
        Timber.d("GATT server stopped")
    }

    fun getConnectedDeviceCount(): Int = connectedDevices.size

    /** Evicts expired rate-limiter entries to prevent unbounded memory growth. */
    fun clearStaleRateLimitEntries() = rateLimiter.clearStale()

    private fun handleMessageReceived(device: BluetoothDevice, data: ByteArray) {
        val message = MeshMessage.fromBytes(data)
        if (message != null) {
            scope.launch {
                _receivedMessages.emit(message)
            }
            Timber.d("Received mesh message %s from %s", message.id, device.address)
        } else {
            Timber.w("Failed to parse mesh message from %s (%d bytes)", device.address, data.size)
        }
    }
}
