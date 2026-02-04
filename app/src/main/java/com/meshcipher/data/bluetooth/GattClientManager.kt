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
                            when (newState) {
                                BluetoothProfile.STATE_CONNECTED -> {
                                    Timber.d("Connected to %s, discovering services", bluetoothAddress)
                                    try {
                                        gatt.discoverServices()
                                    } catch (e: SecurityException) {
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
                        Timber.d("Connecting to GATT server at %s", bluetoothAddress)
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
                            activeConnections[bluetoothAddress] = gatt
                            continuation.invokeOnCancellation {
                                closeGatt(gatt, bluetoothAddress)
                            }
                        } else {
                            Timber.e("Failed to initiate GATT connection to %s", bluetoothAddress)
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
