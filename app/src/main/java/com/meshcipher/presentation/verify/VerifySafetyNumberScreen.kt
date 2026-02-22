package com.meshcipher.presentation.verify

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.meshcipher.domain.model.Contact
import com.meshcipher.presentation.theme.*
import com.meshcipher.presentation.util.getAvatarColor
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifySafetyNumberScreen(
    onBackClick: () -> Unit,
    viewModel: VerifySafetyNumberViewModel = hiltViewModel()
) {
    val contact by viewModel.contact.collectAsState()
    val formattedSafetyNumber by viewModel.formattedSafetyNumber.collectAsState()
    val ownQrBitmap by viewModel.ownQrBitmap.collectAsState()
    val showCamera by viewModel.showCamera.collectAsState()
    val scanResult by viewModel.scanResult.collectAsState()
    val verified by viewModel.verified.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(scanResult) {
        when (scanResult) {
            is ScanResult.Match -> {
                snackbarHostState.showSnackbar("Safety number verified")
                viewModel.clearScanResult()
            }
            is ScanResult.Mismatch -> {
                snackbarHostState.showSnackbar(
                    message = "Safety numbers do not match — do not trust this connection",
                    duration = SnackbarDuration.Long
                )
                viewModel.clearScanResult()
            }
            else -> {}
        }
    }

    if (showCamera) {
        SafetyNumberScanCamera(
            onQRScanned = { viewModel.handleScannedQR(it) },
            onClose = { viewModel.closeCamera() }
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Verify Safety Number",
                        color = TextPrimary,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TacticalBackground)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            contact?.let { c ->
                ContactHeader(contact = c)

                // Change warning
                AnimatedVisibility(visible = c.safetyNumberChanged() && !verified) {
                    SafetyNumberChangedBanner(contactName = c.displayName)
                }

                // Verified confirmation
                AnimatedVisibility(visible = verified || (c.verifiedSafetyNumber != null && !c.safetyNumberChanged())) {
                    VerifiedBanner(verifiedAt = c.safetyNumberVerifiedAt)
                }

                // Safety number display
                SafetyNumberDisplayCard(
                    formattedNumber = formattedSafetyNumber,
                    onScanClick = { viewModel.openCamera() },
                    onVerifyClick = { viewModel.markAsVerified() },
                    alreadyVerified = verified || (c.verifiedSafetyNumber != null && !c.safetyNumberChanged())
                )

                // Own QR code for contact to scan
                ownQrBitmap?.let { bitmap ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, DividerSubtle, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "Your QR Code",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Your safety number QR code",
                                modifier = Modifier
                                    .size(200.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Text(
                                "Have ${c.displayName} scan this",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }

                // Manual verification instructions
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, DividerSubtle, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Manual Verification",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "1. Meet ${c.displayName} in person or on a trusted call\n" +
                            "2. Both of you open this screen\n" +
                            "3. Read the safety numbers aloud to each other\n" +
                            "4. Confirm they match exactly\n" +
                            "5. Tap \"Mark as Verified\" below",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            lineHeight = 20.sp
                        )
                    }
                }
            } ?: run {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SecureGreen)
                }
            }
        }
    }
}

@Composable
private fun ContactHeader(contact: Contact) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val avatarColor = getAvatarColor(contact.id)
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(12.dp),
            color = avatarColor.copy(alpha = 0.15f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = contact.displayName.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    color = avatarColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Column {
            Text(
                contact.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                "Safety number verification",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun SafetyNumberChangedBanner(contactName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(StatusError.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .border(1.dp, StatusError.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = StatusError, modifier = Modifier.size(20.dp))
        Column {
            Text(
                "Safety number changed",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = StatusError
            )
            Text(
                "$contactName may have reinstalled or restored from backup. " +
                "Verify in person to ensure your connection is still secure.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun VerifiedBanner(verifiedAt: Long?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SecureGreen.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .border(1.dp, SecureGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SecureGreen, modifier = Modifier.size(20.dp))
        Column {
            Text(
                "Verified",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = SecureGreen
            )
            if (verifiedAt != null) {
                Text(
                    "Verified ${formatTimestamp(verifiedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun SafetyNumberDisplayCard(
    formattedNumber: String?,
    onScanClick: () -> Unit,
    onVerifyClick: () -> Unit,
    alreadyVerified: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DividerSubtle, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Safety Number",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            SelectionContainer {
                Text(
                    text = formattedNumber ?: "Computing…",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = TextMono,
                    lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                "Compare this number with your contact in person, or scan their QR code.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onScanClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SecureGreen),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(SecureGreen.copy(alpha = 0.5f))
                    )
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scan QR")
                }

                Button(
                    onClick = onVerifyClick,
                    modifier = Modifier.weight(1f),
                    enabled = !alreadyVerified,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SecureGreen,
                        contentColor = OnSecureGreen,
                        disabledContainerColor = SecureGreen.copy(alpha = 0.3f),
                        disabledContentColor = OnSecureGreen.copy(alpha = 0.5f)
                    )
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (alreadyVerified) "Verified" else "Mark Verified")
                }
            }
        }
    }
}

@Composable
private fun SafetyNumberScanCamera(
    onQRScanned: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    var alreadyScanned by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val scanner = BarcodeScanning.getClient()
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
            if (alreadyScanned) {
                imageProxy.close()
                return@setAnalyzer
            }
            try {
                val inputImage = InputImage.fromMediaImage(
                    imageProxy.image ?: return@setAnalyzer,
                    imageProxy.imageInfo.rotationDegrees
                )
                scanner.process(inputImage)
                    .addOnSuccessListener { barcodes ->
                        barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                            ?.rawValue
                            ?.let { raw ->
                                alreadyScanned = true
                                onQRScanned(raw)
                            }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            } catch (e: Exception) {
                Timber.w(e, "QR scan error")
                imageProxy.close()
            }
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Timber.e(e, "Camera binding failed")
        }

        onDispose { cameraProvider.unbindAll() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Viewfinder overlay
        Box(
            modifier = Modifier
                .size(250.dp)
                .align(Alignment.Center)
                .border(2.dp, SecureGreen, RoundedCornerShape(12.dp))
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close camera", tint = Color.White)
        }

        Text(
            "Scan contact's QR code to verify",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color.White,
            fontFamily = InterFontFamily,
            fontSize = 14.sp
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000} minutes ago"
        diff < 86_400_000L -> "${diff / 3_600_000} hours ago"
        diff < 604_800_000L -> "${diff / 86_400_000} days ago"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}
