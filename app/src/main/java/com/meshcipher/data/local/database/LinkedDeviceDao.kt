package com.meshcipher.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.meshcipher.data.local.entity.LinkedDeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LinkedDeviceDao {
    @Query("SELECT * FROM linked_devices ORDER BY linked_at DESC")
    fun observeAll(): Flow<List<LinkedDeviceEntity>>

    @Query("SELECT * FROM linked_devices WHERE approved = 1 ORDER BY linked_at DESC")
    fun observeApproved(): Flow<List<LinkedDeviceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: LinkedDeviceEntity)

    @Query("UPDATE linked_devices SET approved = :approved WHERE device_id = :deviceId")
    suspend fun setApproved(deviceId: String, approved: Boolean)

    @Query("DELETE FROM linked_devices WHERE device_id = :deviceId")
    suspend fun delete(deviceId: String)

    @Query("SELECT * FROM linked_devices WHERE device_id = :deviceId LIMIT 1")
    suspend fun getById(deviceId: String): LinkedDeviceEntity?
}
