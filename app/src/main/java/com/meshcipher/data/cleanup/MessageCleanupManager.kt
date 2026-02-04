package com.meshcipher.data.cleanup

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.meshcipher.data.local.database.ConversationDao
import com.meshcipher.data.local.database.MessageDao
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.domain.model.MessageExpiryMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageCleanupManager @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val appPreferences: AppPreferences
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        cleanupOnAppClose()
    }

    private fun cleanupOnAppClose() {
        scope.launch {
            try {
                val globalMode = MessageExpiryMode.fromNameOrDefault(
                    appPreferences.messageExpiryMode.firstOrNull()
                )

                val conversationsWithExpiry = conversationDao.getConversationsWithExpiryMode()

                for (conversation in conversationsWithExpiry) {
                    val mode = MessageExpiryMode.fromName(conversation.messageExpiryMode)
                    if (mode == MessageExpiryMode.ON_APP_CLOSE) {
                        messageDao.deleteAllMessagesInConversation(conversation.id)
                        Timber.d("Deleted all messages on app close for conversation %s",
                            conversation.id)
                    }
                }

                if (globalMode == MessageExpiryMode.ON_APP_CLOSE) {
                    val allConversations = conversationDao.getAllConversations().firstOrNull() ?: emptyList()
                    val conversationsUsingDefault = allConversations.filter { it.messageExpiryMode == null }

                    for (conversation in conversationsUsingDefault) {
                        messageDao.deleteAllMessagesInConversation(conversation.id)
                    }

                    if (conversationsUsingDefault.isNotEmpty()) {
                        Timber.d("Deleted all messages on app close for %d conversations using global default",
                            conversationsUsingDefault.size)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to cleanup messages on app close")
            }
        }
    }
}
