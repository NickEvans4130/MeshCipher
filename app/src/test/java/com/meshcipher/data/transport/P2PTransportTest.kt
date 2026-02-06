package com.meshcipher.data.transport

import android.content.Context
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.media.MediaEncryptor
import com.meshcipher.data.media.MediaFileManager
import com.meshcipher.data.tor.P2PConnectionManager
import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.repository.ConversationRepository
import com.meshcipher.domain.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.signal.libsignal.protocol.SignalProtocolAddress

class P2PTransportTest {

    private lateinit var p2pConnectionManager: P2PConnectionManager
    private lateinit var identityManager: IdentityManager
    private lateinit var contactRepository: ContactRepository
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var mediaEncryptor: MediaEncryptor
    private lateinit var mediaFileManager: MediaFileManager
    private lateinit var context: Context
    private lateinit var p2pTransport: P2PTransport

    @Before
    fun setup() {
        p2pConnectionManager = mockk(relaxed = true)
        identityManager = mockk(relaxed = true)
        contactRepository = mockk(relaxed = true)
        conversationRepository = mockk(relaxed = true)
        messageRepository = mockk(relaxed = true)
        mediaEncryptor = mockk(relaxed = true)
        mediaFileManager = mockk(relaxed = true)
        context = mockk(relaxed = true)

        p2pTransport = P2PTransport(
            p2pConnectionManager = p2pConnectionManager,
            identityManager = identityManager,
            contactRepository = contactRepository,
            conversationRepository = conversationRepository,
            messageRepository = messageRepository,
            mediaEncryptor = mediaEncryptor,
            mediaFileManager = mediaFileManager,
            context = context
        )
    }

    @Test
    fun `isAvailable returns false when P2P not running`() {
        every { p2pConnectionManager.isRunning() } returns false

        assertFalse(p2pTransport.isAvailable())
    }

    @Test
    fun `isAvailable returns true when P2P running`() {
        every { p2pConnectionManager.isRunning() } returns true

        assertTrue(p2pTransport.isAvailable())
    }

    @Test
    fun `isRecipientReachable returns false when not available`() {
        every { p2pConnectionManager.isRunning() } returns false

        assertFalse(p2pTransport.isRecipientReachable("user-123"))
    }

    @Test
    fun `isRecipientReachable returns true when available`() {
        every { p2pConnectionManager.isRunning() } returns true

        assertTrue(p2pTransport.isRecipientReachable("user-123"))
    }

    @Test
    fun `sendMessage fails when not available`() = runTest {
        every { p2pConnectionManager.isRunning() } returns false

        val result = p2pTransport.sendMessage("user-123", ByteArray(10))

        assertTrue(result.isFailure)
        assertEquals("P2P Tor not running", result.exceptionOrNull()?.message)
    }

    @Test
    fun `sendMessage fails when no identity`() = runTest {
        every { p2pConnectionManager.isRunning() } returns true
        coEvery { identityManager.getIdentity() } returns null

        val result = p2pTransport.sendMessage("user-123", ByteArray(10))

        assertTrue(result.isFailure)
        assertEquals("No identity available", result.exceptionOrNull()?.message)
    }

    @Test
    fun `sendMessage fails when recipient not found`() = runTest {
        every { p2pConnectionManager.isRunning() } returns true
        coEvery { identityManager.getIdentity() } returns mockk(relaxed = true)
        coEvery { contactRepository.getAllContacts() } returns flowOf(emptyList())

        val result = p2pTransport.sendMessage("user-123", ByteArray(10))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Recipient contact not found") == true)
    }

    @Test
    fun `sendMessage fails when recipient has no onion address`() = runTest {
        every { p2pConnectionManager.isRunning() } returns true
        coEvery { identityManager.getIdentity() } returns mockk(relaxed = true)

        val contact = Contact(
            id = "user-123",
            displayName = "Test",
            publicKey = ByteArray(32),
            identityKey = ByteArray(32),
            signalProtocolAddress = SignalProtocolAddress("user-123", 1),
            onionAddress = null
        )
        coEvery { contactRepository.getAllContacts() } returns flowOf(listOf(contact))

        val result = p2pTransport.sendMessage("user-123", ByteArray(10))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("no .onion address") == true)
    }

    @Test
    fun `sendMessage succeeds when all conditions met`() = runTest {
        every { p2pConnectionManager.isRunning() } returns true
        coEvery { identityManager.getIdentity() } returns mockk(relaxed = true) {
            every { userId } returns "my-user-id"
        }

        val contact = Contact(
            id = "user-123",
            displayName = "Test",
            publicKey = ByteArray(32),
            identityKey = ByteArray(32),
            signalProtocolAddress = SignalProtocolAddress("user-123", 1),
            onionAddress = "abc123.onion"
        )
        coEvery { contactRepository.getAllContacts() } returns flowOf(listOf(contact))
        coEvery { p2pConnectionManager.sendMessage(any(), any()) } returns Result.success(null)

        val result = p2pTransport.sendMessage("user-123", "Hello".toByteArray())

        assertTrue(result.isSuccess)
    }

    @Test
    fun `sendMessage with contentType 1 sends MEDIA type`() = runTest {
        every { p2pConnectionManager.isRunning() } returns true
        coEvery { identityManager.getIdentity() } returns mockk(relaxed = true) {
            every { userId } returns "my-user-id"
        }

        val contact = Contact(
            id = "user-123",
            displayName = "Test",
            publicKey = ByteArray(32),
            identityKey = ByteArray(32),
            signalProtocolAddress = SignalProtocolAddress("user-123", 1),
            onionAddress = "abc123.onion"
        )
        coEvery { contactRepository.getAllContacts() } returns flowOf(listOf(contact))
        coEvery { p2pConnectionManager.sendMessage(any(), any()) } returns Result.success(null)

        val result = p2pTransport.sendMessage("user-123", "media-data".toByteArray(), contentType = 1)

        assertTrue(result.isSuccess)
    }
}
