package com.meshcipher.data.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.domain.model.MeshPeer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothMeshManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityManager: IdentityManager,
    // GAP-01 / R-01: epoch-rotating advertisement pseudonyms
    private val identityProvider: BleAdvertisementIdentityProvider
) {
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    private val peerMap = ConcurrentHashMap<String, MeshPeer>()

    private val _discoveredPeers = MutableStateFlow<List<MeshPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<MeshPeer>> = _discoveredPeers.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    suspend fun startAdvertising(): Result<Unit> {
        if (!isBluetoothEnabled()) {
            return Result.failure(Exception("Bluetooth not enabled"))
        }

        val identity = identityManager.getIdentity()
            ?: return Result.failure(Exception("No identity"))

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            ?: return Result.failure(Exception("BLE advertising not supported"))

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // GAP-01 / R-01: advertise with the current epoch's pseudonym UUID so the device
        // cannot be correlated across epoch boundaries by passive observers.
        val epochUuid = identityProvider.currentEpochUuid()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(epochUuid))
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                _isAdvertising.value = true
                Timber.d("BLE advertising started")
            }

            override fun onStartFailure(errorCode: Int) {
                _isAdvertising.value = false
                Timber.e("BLE advertising failed: %d", errorCode)
            }
        }
        advertiseCallback = callback

        return try {
            advertiser?.startAdvertising(settings, data, callback)
            Result.success(Unit)
        } catch (e: SecurityException) {
            Timber.e(e, "Missing Bluetooth permission for advertising")
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start advertising")
            Result.failure(e)
        }
    }

    fun stopAdvertising() {
        try {
            advertiseCallback?.let { callback ->
                advertiser?.stopAdvertising(callback)
            }
        } catch (e: SecurityException) {
            Timber.w(e, "Missing permission to stop advertising")
        }
        advertiseCallback = null
        _isAdvertising.value = false
        Timber.d("BLE advertising stopped")
    }

    fun startScanning(): Result<Unit> {
        if (!isBluetoothEnabled()) {
            return Result.failure(Exception("Bluetooth not enabled"))
        }

        scanner = bluetoothAdapter?.bluetoothLeScanner
            ?: return Result.failure(Exception("BLE scanning not supported"))

        // GAP-01 / R-01: scan for both the current and previous epoch UUIDs so devices that
        // haven't rotated yet remain discoverable during the transition window.
        val currentFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(identityProvider.currentEpochUuid()))
            .build()
        val previousFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(identityProvider.previousEpochUuid()))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                _isScanning.value = false
                Timber.e("BLE scan failed: %d", errorCode)
            }
        }
        scanCallback = callback

        return try {
            scanner?.startScan(listOf(currentFilter, previousFilter), scanSettings, callback)
            _isScanning.value = true
            Timber.d("BLE scanning started")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Timber.e(e, "Missing Bluetooth permission for scanning")
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start scanning")
            Result.failure(e)
        }
    }

    fun stopScanning() {
        try {
            scanCallback?.let { callback ->
                scanner?.stopScan(callback)
            }
        } catch (e: SecurityException) {
            Timber.w(e, "Missing permission to stop scanning")
        }
        scanCallback = null
        _isScanning.value = false
        Timber.d("BLE scanning stopped")
    }

    /**
     * GAP-01 / R-01: Called at each epoch boundary to restart advertising and scanning with the
     * new pseudonym UUID.  Must be invoked from a coroutine (startAdvertising is suspend).
     */
    suspend fun rotateIdentity() {
        val wasAdvertising = _isAdvertising.value
        val wasScanning = _isScanning.value
        if (wasAdvertising) stopAdvertising()
        if (wasScanning) stopScanning()
        if (wasAdvertising) startAdvertising()
        if (wasScanning) startScanning()
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun isBluetoothSupported(): Boolean = bluetoothAdapter != null

    fun removeStalePeers() {
        val now = System.currentTimeMillis()
        val staleKeys = peerMap.entries
            .filter { now - it.value.lastSeen > MeshPeer.PEER_TIMEOUT_MS }
            .map { it.key }

        staleKeys.forEach { peerMap.remove(it) }
        if (staleKeys.isNotEmpty()) {
            _discoveredPeers.value = peerMap.values.toList()
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val rssi = result.rssi

        val address = try {
            device.address
        } catch (e: SecurityException) {
            Timber.w(e, "Missing permission to read device address")
            return
        }

        Timber.d("Discovered device: %s, RSSI: %d", address, rssi)

        val peer = MeshPeer(
            deviceId = address,
            userId = "unknown",
            displayName = null,
            bluetoothAddress = address,
            rssi = rssi,
            lastSeen = System.currentTimeMillis(),
            isContact = false,
            hopCount = 1
        )

        peerMap[address] = peer
        _discoveredPeers.value = peerMap.values.toList()
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("00001234-0000-1000-8000-00805F9B34FB")
        val CHARACTERISTIC_MESSAGE_UUID: UUID = UUID.fromString("00001235-0000-1000-8000-00805F9B34FB")
        val CHARACTERISTIC_ACK_UUID: UUID = UUID.fromString("00001236-0000-1000-8000-00805F9B34FB")
    }
}
