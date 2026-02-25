package com.meshcipher.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meshcipher.desktop.data.DeviceLinkManager
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage

@Composable
fun LinkDeviceScreen(onLinked: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var qrImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var deviceId by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        deviceId = DeviceLinkManager.localDeviceId
        try {
            val buffered: BufferedImage = DeviceLinkManager.generateQrImage(400)
            qrImage = buffered.toComposeImageBitmap()
        } catch (e: Exception) {
            // QR generation failed — show device ID as fallback
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Link Your Phone",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Open MeshCipher on your Android phone, go to Settings > Link Desktop, and scan this QR code.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 400.dp)
        )

        Spacer(Modifier.height(32.dp))

        when {
            isLoading -> CircularProgressIndicator()
            qrImage != null -> {
                Card(
                    modifier = Modifier.size(416.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Image(
                        bitmap = qrImage!!,
                        contentDescription = "Device link QR code",
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    )
                }
            }
            else -> {
                // Fallback: show device ID as text
                Text("Could not generate QR code.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Device ID",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = deviceId,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

        OutlinedButton(onClick = {
            scope.launch {
                qrImage = null
                isLoading = true
                try {
                    val buffered = DeviceLinkManager.generateQrImage(400)
                    qrImage = buffered.toComposeImageBitmap()
                } finally {
                    isLoading = false
                }
            }
        }) {
            Text("Refresh QR")
        }
    }
}
