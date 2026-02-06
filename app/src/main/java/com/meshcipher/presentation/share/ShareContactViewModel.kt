package com.meshcipher.presentation.share

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.tor.EmbeddedTorManager
import com.meshcipher.domain.model.ContactCard
import com.meshcipher.util.QRCodeGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject

@HiltViewModel
class ShareContactViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val qrCodeGenerator: QRCodeGenerator,
    private val embeddedTorManager: EmbeddedTorManager
) : ViewModel() {

    private val _contactCard = MutableStateFlow<ContactCard?>(null)
    val contactCard = _contactCard.asStateFlow()

    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap = _qrBitmap.asStateFlow()

    init {
        loadContactCard()
    }

    private fun loadContactCard() {
        viewModelScope.launch {
            val identity = identityManager.getIdentity() ?: return@launch

            val verificationCode = generateVerificationCode(identity.hardwarePublicKey)

            val card = ContactCard(
                userId = identity.userId,
                publicKey = identity.hardwarePublicKey,
                deviceId = identity.deviceId,
                deviceName = identity.deviceName,
                verificationCode = verificationCode,
                onionAddress = embeddedTorManager.getOnionAddress()
            )

            _contactCard.value = card
            _qrBitmap.value = qrCodeGenerator.generateQRCode(card.toQRString())
        }
    }

    private fun generateVerificationCode(publicKey: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(publicKey)
        val code = hash.take(6)
            .map { (it.toInt() and 0xFF) % 100 }
            .joinToString("") { it.toString().padStart(2, '0') }
        return "${code.take(3)}-${code.drop(3)}"
    }
}
