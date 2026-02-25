package com.meshcipher.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshcipher.desktop.data.DeviceLinkManager
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage

@Composable
fun LinkDeviceScreen(onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var qrImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var deviceId by remember { mutableStateOf("") }

    suspend fun generateQr() {
        isLoading = true
        qrImage = null
        try {
            val buf: BufferedImage = DeviceLinkManager.generateQrImage(360)
            qrImage = buf.toComposeImageBitmap()
        } catch (_: Exception) { }
        isLoading = false
    }

    LaunchedEffect(Unit) {
        deviceId = DeviceLinkManager.localDeviceId
        generateQr()
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Background)
    ) {
        // Header with back button
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
                text = "LINK DEVICE",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Accent,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { scope.launch { generateQr() } },
                enabled = !isLoading
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh QR", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))

        // Content
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "LINK YOUR PHONE",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                letterSpacing = 2.sp
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "On your Android phone: Settings → Linked Devices → tap + → scan this QR code.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 380.dp)
            )

            Spacer(Modifier.height(32.dp))

            // QR code frame
            Box(
                modifier = Modifier
                    .size(380.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(2.dp, Accent, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> CircularProgressIndicator(
                        color = Accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(40.dp)
                    )
                    qrImage != null -> Image(
                        bitmap = qrImage!!,
                        contentDescription = "Device link QR code",
                        modifier = Modifier.fillMaxSize().padding(12.dp)
                    )
                    else -> Text(
                        "QR generation failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Device ID display
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Surface)
                    .border(1.dp, Divider, RoundedCornerShape(6.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "DEVICE ID",
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                    letterSpacing = 2.sp,
                    fontFamily = Monospace
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = deviceId,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    fontFamily = Monospace
                )
            }

            Spacer(Modifier.height(24.dp))

            // Instruction steps
            Column(
                modifier = Modifier.widthIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StepRow(1, "Open MeshCipher on your phone")
                StepRow(2, "Go to Settings → Linked Devices")
                StepRow(3, "Tap + and scan this QR code")
                StepRow(4, "Approve the link on your phone")
                StepRow(5, "Contacts will sync automatically")
            }
        }
    }
}

@Composable
private fun StepRow(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(AccentDim),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
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
