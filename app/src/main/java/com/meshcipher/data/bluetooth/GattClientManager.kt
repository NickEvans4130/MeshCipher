package com.meshcipher.data.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
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
                                    closeGatt(gatt, bluetoothAddress)
                                    if (continuation.isActive) {
                                        if (status != BluetoothGatt.GATT_SUCCESS) {
                                            continuation.resume(
                                                Result.failure(Exception("Connection failed: status $status"))
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                closeGatt(gatt, bluetoothAddress)
                                if (continuation.isActive) {
                                    continuation.resume(
                                        Result.failure(Exception("Service discovery failed: $status"))
                                    )
                                }
                                return
                            }

                            val service = gatt.getService(BluetoothMeshManager.SERVICE_UUID)
                            if (service == null) {
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
                                closeGatt(gatt, bluetoothAddress)
                                if (continuation.isActive) {
                                    continuation.resume(
                                        Result.failure(Exception("Message characteristic not found"))
                                    )
                                }
                                return
                            }

                            val messageBytes = message.toBytes()
                            characteristic.value = messageBytes

                            try {
                                val writeResult = gatt.writeCharacteristic(characteristic)
                                if (!writeResult) {
                                    closeGatt(gatt, bluetoothAddress)
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            Result.failure(Exception("Write characteristic failed"))
                                        )
                                    }
                                }
                            } catch (e: SecurityException) {
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
                        val gatt = device.connectGatt(context, false, callback)
                        if (gatt != null) {
                            activeConnections[bluetoothAddress] = gatt
                            continuation.invokeOnCancellation {
                                closeGatt(gatt, bluetoothAddress)
                            }
                        } else {
                            continuation.resume(Result.failure(Exception("Failed to initiate GATT connection")))
                        }
                    } catch (e: SecurityException) {
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
