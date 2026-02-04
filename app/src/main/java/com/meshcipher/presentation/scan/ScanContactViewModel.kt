package com.meshcipher.presentation.scan

import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.meshcipher.domain.model.ContactCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ScanContactViewModel @Inject constructor() : ViewModel() {

    private val _scannedCard = MutableStateFlow<ContactCard?>(null)
    val scannedCard = _scannedCard.asStateFlow()

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
                        Timber.d("QR code scanned: userId=%s", contactCard.userId)
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

    override fun onCleared() {
        super.onCleared()
        scanner.close()
    }
}
