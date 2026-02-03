package com.meshcipher.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.domain.usecase.ReceiveMessageUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.firstOrNull
import timber.log.Timber

@HiltWorker
class MessageSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val receiveMessageUseCase: ReceiveMessageUseCase,
    private val appPreferences: AppPreferences
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("MessageSyncWorker started")

        val deviceId = appPreferences.userId.firstOrNull()
        if (deviceId.isNullOrBlank()) {
            Timber.w("No device ID configured, skipping sync")
            return Result.success()
        }

        return try {
            val result = receiveMessageUseCase(deviceId)

            if (result.isSuccess) {
                val count = result.getOrDefault(0)
                Timber.d("Sync complete: %d new messages", count)
                Result.success()
            } else {
                Timber.e(result.exceptionOrNull(), "Sync failed")
                Result.retry()
            }
        } catch (e: Exception) {
            Timber.e(e, "Sync worker exception")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "message_sync"
    }
}
