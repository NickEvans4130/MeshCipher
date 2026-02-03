package com.meshcipher.data.security

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MessageSequenceTrackerTest {

    private lateinit var tracker: MessageSequenceTracker
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setup() {
        prefs = mockk()
        editor = mockk(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.putLong(any(), any()) } returns editor

        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        tracker = MessageSequenceTracker(context)
    }

    @Test
    fun `getNextSequence increments from 0`() {
        every { prefs.getLong("sent_seq_contact-1", 0) } returns 0
        val seq = tracker.getNextSequence("contact-1")
        assertEquals(1, seq)
        verify { editor.putLong("sent_seq_contact-1", 1) }
    }

    @Test
    fun `getNextSequence increments from existing`() {
        every { prefs.getLong("sent_seq_contact-1", 0) } returns 5
        val seq = tracker.getNextSequence("contact-1")
        assertEquals(6, seq)
    }

    @Test
    fun `validateIncomingSequence accepts higher sequence`() {
        every { prefs.getLong("recv_seq_contact-1", 0) } returns 5
        assertTrue(tracker.validateIncomingSequence("contact-1", 6))
    }

    @Test
    fun `validateIncomingSequence rejects same sequence`() {
        every { prefs.getLong("recv_seq_contact-1", 0) } returns 5
        assertFalse(tracker.validateIncomingSequence("contact-1", 5))
    }

    @Test
    fun `validateIncomingSequence rejects lower sequence`() {
        every { prefs.getLong("recv_seq_contact-1", 0) } returns 5
        assertFalse(tracker.validateIncomingSequence("contact-1", 3))
    }

    @Test
    fun `validateIncomingSequence rejects zero sequence`() {
        every { prefs.getLong("recv_seq_contact-1", 0) } returns 0
        assertFalse(tracker.validateIncomingSequence("contact-1", 0))
    }
}
