package com.meshcipher.data.security

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageSequenceTracker @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "message_sequences", Context.MODE_PRIVATE
    )

    fun getNextSequence(contactId: String): Long {
        val lastSeq = prefs.getLong("sent_seq_$contactId", 0)
        val nextSeq = lastSeq + 1
        prefs.edit().putLong("sent_seq_$contactId", nextSeq).apply()
        return nextSeq
    }

    fun validateIncomingSequence(contactId: String, sequence: Long): Boolean {
        val lastSeq = prefs.getLong("recv_seq_$contactId", 0)
        if (sequence <= lastSeq) {
            return false
        }
        prefs.edit().putLong("recv_seq_$contactId", sequence).apply()
        return true
    }

    fun getLastSentSequence(contactId: String): Long {
        return prefs.getLong("sent_seq_$contactId", 0)
    }

    fun getLastReceivedSequence(contactId: String): Long {
        return prefs.getLong("recv_seq_$contactId", 0)
    }
}
