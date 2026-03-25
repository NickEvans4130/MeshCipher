@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.meshcipher.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshcipher.desktop.data.CONTENT_TYPE_DEVICE_UNLINK
import com.meshcipher.desktop.data.DeviceLinkManager
import com.meshcipher.desktop.data.LinkConfirmationRequest
import com.meshcipher.desktop.data.LinkedPhone
import com.meshcipher.desktop.data.MessagingManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LinkDeviceScreen(onBack: () -> Unit = {}, messagingManager: MessagingManager? = null) {
    val scope = rememberCoroutineScope()

    // Pairing QR (phone scans to link)
    var pairingQr by remember { mutableStateOf<ImageBitmap?>(null) }
    var pairingQrLoading by remember { mutableStateOf(true) }
    // GAP-05 / R-06: QR expires after 60 seconds to limit the replay window.
    var qrExpired by remember { mutableStateOf(false) }
    var qrSecondsRemaining by remember { mutableStateOf(60) }

    // Contact QR (contacts scan to add this desktop)
    var contactQr by remember { mutableStateOf<ImageBitmap?>(null) }
    var contactQrLoading by remember { mutableStateOf(true) }

    var displayUserId by remember { mutableStateOf("") }
    var linkedPhone by remember { mutableStateOf<LinkedPhone?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    // GAP-06 / R-06: Pending link confirmation request from Android
    var pendingConfirmation by remember { mutableStateOf<LinkConfirmationRequest?>(null) }

    suspend fun loadPairingQr() {
        pairingQrLoading = true
        pairingQr = null
        qrExpired = false
        qrSecondsRemaining = 60
        runCatching { DeviceLinkManager.generateQrImage(320).toComposeImageBitmap() }
            .onSuccess { pairingQr = it }
        pairingQrLoading = false
    }

    suspend fun loadContactQr(phone: LinkedPhone?) {
        contactQrLoading = true
        contactQr = null
        runCatching { DeviceLinkManager.generateContactQrImage(280, phone).toComposeImageBitmap() }
            .onSuccess { contactQr = it }
        contactQrLoading = false
    }

    LaunchedEffect(refreshKey) {
        val phone = DeviceLinkManager.getApprovedDevices().firstOrNull()
        linkedPhone = phone
        displayUserId = if (phone != null && phone.phoneUserId.isNotBlank()) {
            phone.phoneUserId
        } else {
            DeviceLinkManager.getDesktopUserId()
        }
        loadPairingQr()
        loadContactQr(phone)
    }

    LaunchedEffect(Unit) {
        DeviceLinkManager.deviceLinked.collect { refreshKey++ }
    }

    // GAP-06 / R-06: Collect link confirmation requests from Android
    LaunchedEffect(messagingManager) {
        messagingManager?.linkConfirmationPending?.collect { req ->
            pendingConfirmation = req
        }
    }

    // GAP-06 / R-06: Auto-dismiss + deny if desktop user ignores the dialog for 2 minutes
    val currentPending = pendingConfirmation
    LaunchedEffect(currentPending) {
        if (currentPending == null) return@LaunchedEffect
        delay(2 * 60 * 1000L) // 2 minutes
        if (pendingConfirmation == currentPending) {
            // Still pending after 2 min — auto-deny
            pendingConfirmation = null
            scope.launch {
                val pubKeyBytes = currentPending.publicKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(pubKeyBytes)
                val phoneUserId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash).take(32)
                messagingManager?.sendLinkDenied(phoneUserId, currentPending.deviceId)
            }
        }
    }

    // GAP-05 / R-06: Countdown timer — auto-expire the pairing QR after 60 seconds.
    // The desktop generates a new nonce on each refresh, so the expired QR is permanently invalid.
    LaunchedEffect(refreshKey) {
        if (linkedPhone != null) return@LaunchedEffect
        for (remaining in 59 downTo 0) {
            delay(1_000L)
            qrSecondsRemaining = remaining
        }
        qrExpired = true
        pairingQr = null
    }

    // GAP-06 / R-06: Desktop confirmation dialog — shown when Android approves a link
    // and sends content_type=18. The user must explicitly confirm or deny before the link
    // becomes active on Android.
    val confirmation = pendingConfirmation
    if (confirmation != null) {
        AlertDialog(
            onDismissRequest = { /* require explicit action */ },
            title = {
                Text(
                    "LINK REQUEST",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Accent,
                    fontFamily = Monospace,
                    letterSpacing = 2.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "A device wants to link to this desktop:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Surface)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(confirmation.deviceName.ifBlank { "Unknown device" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary)
                        Text(
                            "Fingerprint: ${confirmation.publicKeyFingerprint}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary,
                            fontFamily = Monospace
                        )
                    }
                    Text(
                        "Tap Confirm to approve, or Deny to block this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingConfirmation = null
                    scope.launch {
                        // Derive phoneUserId from the public key (matches Android algorithm)
                        val pubKeyBytes = confirmation.publicKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(pubKeyBytes)
                        val phoneUserId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash).take(32)
                        messagingManager?.sendLinkConfirmed(phoneUserId, confirmation.deviceId)
                        refreshKey++
                    }
                }) {
                    Text("CONFIRM", color = Accent, fontFamily = Monospace, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingConfirmation = null
                    scope.launch {
                        val pubKeyBytes = confirmation.publicKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(pubKeyBytes)
                        val phoneUserId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash).take(32)
                        messagingManager?.sendLinkDenied(phoneUserId, confirmation.deviceId)
                    }
                }) {
                    Text("DENY", color = ErrorRed, fontFamily = Monospace)
                }
            },
            containerColor = Surface,
            titleContentColor = Accent
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Accent)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "DEVICE",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Accent,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { refreshKey++ },
                enabled = !pairingQrLoading && !contactQrLoading
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        HorizontalDivider(color = Divider, thickness = 1.dp)

        // Two-column layout
        Row(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Left: Linked phone status ─────────────────────────────────
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SectionLabel("LINKED PHONE")
                Spacer(Modifier.height(16.dp))

                if (linkedPhone != null) {
                    LinkedPhoneCard(
                        phone = linkedPhone!!,
                        onUnlink = {
                            scope.launch {
                                DeviceLinkManager.unlinkDevice(
                                    linkedDeviceId = linkedPhone!!.deviceId,
                                    notifyPeer = { phoneUserId ->
                                        messagingManager?.sendUnlinkNotification(phoneUserId)
                                    }
                                )
                                refreshKey++
                            }
                        }
                    )
                } else {
                    // Not linked yet — show pairing QR or expired notice
                    if (qrExpired) {
                        // GAP-05 / R-06: QR expired — instruct user to regenerate
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.widthIn(max = 300.dp)
                        ) {
                            Text(
                                "QR expired — click to regenerate.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { scope.launch { loadPairingQr() } },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Accent),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("REGENERATE QR", style = MaterialTheme.typography.labelMedium, fontFamily = Monospace, letterSpacing = 1.sp)
                            }
                        }
                    } else {
                        Text(
                            "Scan this QR code from your Android phone to link it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 300.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        // Countdown timer
                        if (!pairingQrLoading) {
                            Text(
                                "Expires in ${qrSecondsRemaining}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (qrSecondsRemaining <= 10) ErrorRed else TextTertiary,
                                fontFamily = Monospace
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        QrFrame(bitmap = pairingQr, loading = pairingQrLoading, size = 280.dp)
                    }
                    if (!qrExpired) {
                        Spacer(Modifier.height(16.dp))
                        Column(
                            modifier = Modifier.widthIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StepRow(1, "Open MeshCipher on Android")
                            StepRow(2, "Settings → Linked Devices → +")
                            StepRow(3, "Scan this QR code")
                            StepRow(4, "Approve on your phone")
                        }
                    }
                }
            }

            // Vertical divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Divider)
            )

            // ── Right: Desktop identity + contact QR ─────────────────────
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SectionLabel("YOUR IDENTITY")
                Spacer(Modifier.height(16.dp))

                Text(
                    "Share this QR so contacts can add you, or give them your User ID directly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 300.dp)
                )
                Spacer(Modifier.height(16.dp))

                QrFrame(bitmap = contactQr, loading = contactQrLoading, size = 240.dp)

                Spacer(Modifier.height(16.dp))

                // User ID chip
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Surface)
                        .border(1.dp, Divider, RoundedCornerShape(6.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .widthIn(max = 300.dp)
                ) {
                    Text(
                        "USER ID",
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                        letterSpacing = 2.sp,
                        fontFamily = Monospace
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = displayUserId,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        fontFamily = Monospace,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = Accent,
        letterSpacing = 2.sp,
        fontFamily = Monospace
    )
}

@Composable
private fun QrFrame(
    bitmap: ImageBitmap?,
    loading: Boolean,
    size: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(2.dp, Accent, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> CircularProgressIndicator(
                color = Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(36.dp)
            )
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(10.dp)
            )
            else -> Text("QR error", style = MaterialTheme.typography.bodySmall, color = ErrorRed)
        }
    }
}

@Composable
private fun LinkedPhoneCard(phone: LinkedPhone, onUnlink: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface)
            .border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Connected indicator
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "LINKED",
                style = MaterialTheme.typography.labelMedium,
                color = Accent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontFamily = Monospace
            )
        }

        HorizontalDivider(color = Divider, thickness = 1.dp)

        // Phone avatar + name
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(AccentDim),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PhoneAndroid,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(28.dp)
            )
        }

        Text(
            text = phone.deviceName,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Device ID
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(SurfaceElevated)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                "DEVICE ID",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                letterSpacing = 1.sp,
                fontFamily = Monospace
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = phone.deviceId.take(24) + "…",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontFamily = Monospace
            )
        }

        // Linked date
        Text(
            text = "Linked ${dateFormat.format(Date(phone.linkedAt))}",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )

        // Unlink button
        OutlinedButton(
            onClick = onUnlink,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
            border = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(
                "UNLINK",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                fontFamily = Monospace
            )
        }
    }
}

@Composable
private fun StepRow(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(AccentDim),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = Accent,
                fontWeight = FontWeight.Bold,
                fontFamily = Monospace
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}
