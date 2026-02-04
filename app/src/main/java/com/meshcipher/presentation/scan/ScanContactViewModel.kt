package com.meshcipher.presentation.scan

import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.model.ContactCard
import com.meshcipher.domain.repository.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.signal.libsignal.protocol.SignalProtocolAddress
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ScanContactViewModel @Inject constructor(
    private val contactRepository: ContactRepository
) : ViewModel() {

    private val _scannedCard = MutableStateFlow<ContactCard?>(null)
    val scannedCard = _scannedCard.asStateFlow()

    private val _contactAdded = MutableStateFlow(false)
    val contactAdded = _contactAdded.asStateFlow()

    private var isProcessing = false
    private var hasScanned = false

    private val scannerOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()

    private val scanner = BarcodeScanning.getClient(scannerOptions)

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun analyzeImage(imageProxy: ImageProxy, onScanned: (ContactCard) -> Unit) {
        if (isProcessing || hasScanned) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        isProcessing = true

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val rawValue = barcode.rawValue ?: continue

                    val contactCard = ContactCard.fromQRString(rawValue)
                    if (contactCard != null && !hasScanned) {
                        hasScanned = true
                        _scannedCard.value = contactCard
                        addContactFromCard(contactCard)
                        onScanned(contactCard)
                        return@addOnSuccessListener
                    }
                }
                isProcessing = false
            }
            .addOnFailureListener { e ->
                Timber.w(e, "Barcode scanning failed")
                isProcessing = false
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun addContactFromCard(card: ContactCard) {
        viewModelScope.launch {
            try {
                val contact = Contact(
                    id = UUID.randomUUID().toString(),
                    displayName = card.displayName ?: card.deviceName,
                    publicKey = card.publicKey,
                    identityKey = card.publicKey,
                    signalProtocolAddress = SignalProtocolAddress(card.userId, 1),
                    lastSeen = System.currentTimeMillis()
                )

                contactRepository.insertContact(contact)
                _contactAdded.value = true
                Timber.d("Contact added from QR scan: %s", contact.displayName)
            } catch (e: Exception) {
                Timber.e(e, "Failed to add contact from QR scan")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanner.close()
    }
}
