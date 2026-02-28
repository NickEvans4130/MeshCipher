package com.meshcipher.data.repository

import com.meshcipher.data.local.database.LinkedDeviceDao
import com.meshcipher.data.local.entity.LinkedDeviceEntity
import com.meshcipher.shared.domain.model.DeviceType
import com.meshcipher.shared.domain.model.LinkedDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LinkedDevicesRepository @Inject constructor(
    private val dao: LinkedDeviceDao
) {
    fun observeAll(): Flow<List<LinkedDevice>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeApproved(): Flow<List<LinkedDevice>> =
        dao.observeApproved().map { list -> list.map { it.toDomain() } }

    suspend fun upsert(device: LinkedDevice) = dao.upsert(device.toEntity())

    suspend fun approve(deviceId: String) = dao.setApproved(deviceId, true)

    suspend fun revoke(deviceId: String) = dao.delete(deviceId)

    suspend fun getById(deviceId: String): LinkedDevice? =
        dao.getById(deviceId)?.toDomain()

    private fun LinkedDeviceEntity.toDomain() = LinkedDevice(
        deviceId = deviceId,
        deviceName = deviceName,
        deviceType = runCatching { DeviceType.valueOf(deviceType) }.getOrDefault(DeviceType.UNKNOWN),
        publicKeyHex = publicKeyHex,
        linkedAt = linkedAt,
        approved = approved
    )

    private fun LinkedDevice.toEntity() = LinkedDeviceEntity(
        deviceId = deviceId,
        deviceName = deviceName,
        deviceType = deviceType.name,
        publicKeyHex = publicKeyHex,
        linkedAt = linkedAt,
        approved = approved
    )
}
