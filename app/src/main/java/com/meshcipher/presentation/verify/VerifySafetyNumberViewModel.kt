package com.meshcipher.presentation.verify

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.usecase.SafetyNumberManager
import com.meshcipher.util.QRCodeGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed class ScanResult {
    object None : ScanResult()
    object Match : ScanResult()
    object Mismatch : ScanResult()
}

@HiltViewModel
class VerifySafetyNumberViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val contactRepository: ContactRepository,
    private val safetyNumberManager: SafetyNumberManager,
    private val qrCodeGenerator: QRCodeGenerator
) : ViewModel() {

    private val contactId: String = savedStateHandle["contactId"]
        ?: throw IllegalArgumentException("contactId required")

    val contact = contactRepository.getContactFlow(contactId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _computedSafetyNumber = MutableStateFlow<String?>(null)
    val computedSafetyNumber = _computedSafetyNumber.asStateFlow()

    private val _formattedSafetyNumber = MutableStateFlow<String?>(null)
    val formattedSafetyNumber = _formattedSafetyNumber.asStateFlow()

    private val _ownQrBitmap = MutableStateFlow<Bitmap?>(null)
    val ownQrBitmap = _ownQrBitmap.asStateFlow()

    private val _showCamera = MutableStateFlow(false)
    val showCamera = _showCamera.asStateFlow()

    private val _scanResult = MutableStateFlow<ScanResult>(ScanResult.None)
    val scanResult = _scanResult.asStateFlow()

    private val _verified = MutableStateFlow(false)
    val verified = _verified.asStateFlow()

    init {
        viewModelScope.launch {
            val c = contactRepository.getContact(contactId) ?: return@launch
            val number = safetyNumberManager.computeSafetyNumber(c)
            if (number != null) {
                _computedSafetyNumber.value = number
                _formattedSafetyNumber.value = safetyNumberManager.formatForDisplay(number)
                try {
                    val qrContent = safetyNumberManager.generateQRContent(c.copy(currentSafetyNumber = number))
                    _ownQrBitmap.value = qrCodeGenerator.generateQRCode(qrContent, 512)
                } catch (e: Exception) {
                    Timber.w(e, "QR code generation failed")
                }
            }
        }
    }

    fun openCamera() {
        _scanResult.value = ScanResult.None
        _showCamera.value = true
    }

    fun closeCamera() {
        _showCamera.value = false
    }

    fun handleScannedQR(qrContent: String) {
        val scanned = safetyNumberManager.parseSafetyNumberFromQR(qrContent) ?: return
        val current = _computedSafetyNumber.value ?: return

        _showCamera.value = false
        if (scanned == current) {
            _scanResult.value = ScanResult.Match
            markAsVerified()
        } else {
            _scanResult.value = ScanResult.Mismatch
        }
    }

    fun markAsVerified() {
        viewModelScope.launch {
            safetyNumberManager.markAsVerified(contactId)
            _verified.value = true
        }
    }

    fun clearScanResult() {
        _scanResult.value = ScanResult.None
    }
}
