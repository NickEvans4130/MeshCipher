package com.meshcipher.data.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.meshcipher.domain.model.MeshMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class GattClientManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val activeConnections = ConcurrentHashMap<String, BluetoothGatt>()

    suspend fun sendMessage(
        bluetoothAddress: String,
        message: MeshMessage
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter
            ?: return@withContext Result.failure(Exception("Bluetooth not available"))

        val device = try {
            adapter.getRemoteDevice(bluetoothAddress)
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Invalid Bluetooth address: $bluetoothAddress"))
        }

        try {
            withTimeout(CONNECTION_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val callback = object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(
                            gatt: BluetoothGatt,
                            status: Int,
                            newState: Int
                        ) {
                            Timber.d("Client onConnectionStateChange: address=%s, status=%d, newState=%d",
                                bluetoothAddress, status, newState)
                            when (newState) {
                                BluetoothProfile.STATE_CONNECTED -> {
                                    Timber.d("Connected to %s (status=%d), requesting MTU then discovering services",
                                        bluetoothAddress, status)
                                    try {
                                        // Request larger MTU for mesh messages
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                            gatt.requestMtu(512)
                                        } else {
                                            gatt.discoverServices()
                                        }
                                    } catch (e: SecurityException) {
                                        Timber.e(e, "Security exception requesting MTU/discovering services")
                                        closeGatt(gatt, bluetoothAddress)
                                        if (continuation.isActive) {
                                            continuation.resume(Result.failure(e))
                                        }
                                    }
                                }
                                BluetoothProfile.STATE_DISCONNECTED -> {
                                    Timber.d("Disconnected from %s with status %d", bluetoothAddress, status)
                                    closeGatt(gatt, bluetoothAddress)
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            Result.failure(Exception("Disconnected before write completed: status $status"))
                                        )
                                    }
                                }
                                else -> {
                                    Timber.d("Unknown connection state %d for %s", newState, bluetoothAddress)
                                }
                            }
                        }

                        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                            Timber.d("MTU changed to %d for %s (status=%d)", mtu, bluetoothAddress, status)
                            try {
                                gatt.discoverServices()
                            } catch (e: SecurityException) {
                                Timber.e(e, "Security exception discovering services after MTU change")
                                closeGatt(gatt, bluetoothAddress)
                                if (continuation.isActive) {
                                    continuation.resume(Result.failure(e))
                                }
                            }
                        }

                        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                            Timber.d("Services discovered on %s with status %d", bluetoothAddress, status)
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                closeGatt(gatt, bluetoothAddress)
                                if (continuation.isActive) {
                                    continuation.resume(
                                        Result.failure(Exception("Service discovery failed: $status"))
                                    )
                                }
                                return
                            }

                            val services = gatt.services
                            Timber.d("Found %d services on %s", services.size, bluetoothAddress)
                            services.forEach { svc ->
                                Timber.d("  Service: %s", svc.uuid)
                            }

                            val service = gatt.getService(BluetoothMeshManager.SERVICE_UUID)
                            if (service == null) {
                                Timber.e("MeshCipher service %s not found on %s",
                                    BluetoothMeshManager.SERVICE_UUID, bluetoothAddress)
                                closeGatt(gatt, bluetoothAddress)
                                if (continuation.isActive) {
                                    continuation.resume(
                                        Result.failure(Exception("MeshCipher service not found"))
                                    )
                                }
                                return
                            }

                            val characteristic = service.getCharacteristic(
                                BluetoothMeshManager.CHARACTERISTIC_MESSAGE_UUID
                            )
                            if (characteristic == null) {
                                Timber.e("Message characteristic not found on %s", bluetoothAddress)
                                closeGatt(gatt, bluetoothAddress)
                                if (continuation.isActive) {
                                    continuation.resume(
                                        Result.failure(Exception("Message characteristic not found"))
                                    )
                                }
                                return
                            }
                            Timber.d("Found message characteristic on %s", bluetoothAddress)

                            val messageBytes = message.toBytes()
                            Timber.d("Writing %d bytes to %s", messageBytes.size, bluetoothAddress)

                            try {
                                val writeResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeCharacteristic(
                                        characteristic,
                                        messageBytes,
                                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                    ) == BluetoothGatt.GATT_SUCCESS
                                } else {
                                    @Suppress("DEPRECATION")
                                    characteristic.value = messageBytes
                                    @Suppress("DEPRECATION")
                                    gatt.writeCharacteristic(characteristic)
                                }
                                if (!writeResult) {
                                    closeGatt(gatt, bluetoothAddress)
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            Result.failure(Exception("Write characteristic initiation failed"))
                                        )
                                    }
                                }
                            } catch (e: SecurityException) {
                                Timber.e(e, "Security exception writing to %s", bluetoothAddress)
                                closeGatt(gatt, bluetoothAddress)
                                if (continuation.isActive) {
                                    continuation.resume(Result.failure(e))
                                }
                            }
                        }

                        override fun onCharacteristicWrite(
                            gatt: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic,
                            status: Int
                        ) {
                            closeGatt(gatt, bluetoothAddress)
                            if (continuation.isActive) {
                                if (status == BluetoothGatt.GATT_SUCCESS) {
                                    Timber.d("Message %s written to %s", message.id, bluetoothAddress)
                                    continuation.resume(Result.success(Unit))
                                } else {
                                    continuation.resume(
                                        Result.failure(Exception("Write failed: status $status"))
                                    )
                                }
                            }
                        }
                    }

                    try {
                        Timber.d("Initiating GATT connection to %s (timeout=%dms)", bluetoothAddress, CONNECTION_TIMEOUT_MS)
                        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            device.connectGatt(
                                context,
                                false,
                                callback,
                                BluetoothDevice.TRANSPORT_LE
                            )
                        } else {
                            device.connectGatt(context, false, callback)
                        }
                        if (gatt != null) {
                            Timber.d("GATT connection initiated to %s, waiting for callback...", bluetoothAddress)
                            activeConnections[bluetoothAddress] = gatt
                            continuation.invokeOnCancellation {
                                Timber.d("GATT connection to %s was cancelled", bluetoothAddress)
                                closeGatt(gatt, bluetoothAddress)
                            }
                        } else {
                            Timber.e("Failed to initiate GATT connection to %s - connectGatt returned null", bluetoothAddress)
                            continuation.resume(Result.failure(Exception("Failed to initiate GATT connection")))
                        }
                    } catch (e: SecurityException) {
                        Timber.e(e, "Security exception connecting to %s", bluetoothAddress)
                        continuation.resume(Result.failure(e))
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to send message to %s", bluetoothAddress)
            Result.failure(e)
        }
    }

    fun disconnectAll() {
        activeConnections.forEach { (address, gatt) ->
            closeGatt(gatt, address)
        }
    }

    private fun closeGatt(gatt: BluetoothGatt, address: String) {
        try {
            gatt.disconnect()
            gatt.close()
        } catch (e: SecurityException) {
            Timber.w(e, "Missing permission to close GATT")
        }
        activeConnections.remove(address)
    }

    companion object {
        const val CONNECTION_TIMEOUT_MS = 30_000L
    }
}
