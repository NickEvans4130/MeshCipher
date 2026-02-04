package com.meshcipher.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.meshcipher.data.local.database.ConversationDao
import com.meshcipher.data.local.database.MessageDao
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.domain.model.MessageExpiryMode
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.firstOrNull
import timber.log.Timber

@HiltWorker
class MessageCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val appPreferences: AppPreferences
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("MessageCleanupWorker started")

        return try {
            val globalMode = MessageExpiryMode.fromNameOrDefault(
                appPreferences.messageExpiryMode.firstOrNull()
            )
            val now = System.currentTimeMillis()

            val conversations = conversationDao.getConversationsWithExpiryMode()

            for (conversation in conversations) {
                val mode = MessageExpiryMode.fromName(conversation.messageExpiryMode)
                    ?: continue

                if (mode.durationMs > 0) {
                    val cutoffTimestamp = now - mode.durationMs
                    messageDao.deleteExpiredMessages(conversation.id, cutoffTimestamp)
                    Timber.d("Cleaned up messages older than %s for conversation %s",
                        mode.displayName, conversation.id)
                }
            }

            if (globalMode.durationMs > 0) {
                val allConversations = conversationDao.getAllConversations().firstOrNull() ?: emptyList()
                val conversationsUsingDefault = allConversations.filter { it.messageExpiryMode == null }

                for (conversation in conversationsUsingDefault) {
                    val cutoffTimestamp = now - globalMode.durationMs
                    messageDao.deleteExpiredMessages(conversation.id, cutoffTimestamp)
                }

                if (conversationsUsingDefault.isNotEmpty()) {
                    Timber.d("Cleaned up messages with global expiry %s for %d conversations",
                        globalMode.displayName, conversationsUsingDefault.size)
                }
            }

            Timber.d("MessageCleanupWorker completed successfully")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "MessageCleanupWorker failed")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "message_cleanup"
    }
}
