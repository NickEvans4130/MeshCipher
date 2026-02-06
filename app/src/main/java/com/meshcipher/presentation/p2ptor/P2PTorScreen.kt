package com.meshcipher.presentation.p2ptor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshcipher.data.tor.EmbeddedTorManager
import com.meshcipher.presentation.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P2PTorScreen(
    onBackClick: () -> Unit,
    viewModel: P2PTorViewModel = hiltViewModel()
) {
    val torStatus by viewModel.torStatus.collectAsState()
    val context = LocalContext.current
    var copiedOnion by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "P2P Tor",
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TacticalBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Status Card
            Text(
                text = "Tor Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DividerSubtle, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = TacticalSurface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val stateColor = when (torStatus.state) {
                            EmbeddedTorManager.State.RUNNING -> StatusActive
                            EmbeddedTorManager.State.ERROR -> StatusError
                            EmbeddedTorManager.State.STOPPED -> TextTertiary
                            else -> StatusWarning
                        }
                        val stateIcon = when (torStatus.state) {
                            EmbeddedTorManager.State.RUNNING -> Icons.Default.CheckCircle
                            EmbeddedTorManager.State.ERROR -> Icons.Default.Error
                            else -> Icons.Default.CheckCircle
                        }

                        Icon(
                            stateIcon,
                            contentDescription = null,
                            tint = stateColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (torStatus.state) {
                                EmbeddedTorManager.State.STOPPED -> "Stopped"
                                EmbeddedTorManager.State.STARTING -> "Starting..."
                                EmbeddedTorManager.State.BOOTSTRAPPING -> "Bootstrapping..."
                                EmbeddedTorManager.State.CREATING_HIDDEN_SERVICE -> "Creating hidden service..."
                                EmbeddedTorManager.State.RUNNING -> "Running"
                                EmbeddedTorManager.State.ERROR -> "Error"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = stateColor
                        )
                    }

                    // Bootstrap progress
                    if (torStatus.state == EmbeddedTorManager.State.BOOTSTRAPPING ||
                        torStatus.state == EmbeddedTorManager.State.CREATING_HIDDEN_SERVICE
                    ) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Bootstrap: ${torStatus.bootstrapPercent}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = RobotoMonoFontFamily,
                            color = TextMono
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = torStatus.bootstrapPercent / 100f,
                            modifier = Modifier.fillMaxWidth(),
                            color = SecureGreen,
                            trackColor = TacticalElevated
                        )
                    }

                    // Error message
                    if (torStatus.state == EmbeddedTorManager.State.ERROR && torStatus.errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = torStatus.errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusError
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Onion Address Card
            if (torStatus.onionAddress != null) {
                Text(
                    text = "Your .onion Address",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, SecureGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(
                        containerColor = SecureGreen.copy(alpha = 0.08f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Share this address with contacts for direct P2P messaging:",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = torStatus.onionAddress!!,
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = RobotoMonoFontFamily,
                                color = TextMono,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(
                                        Context.CLIPBOARD_SERVICE
                                    ) as ClipboardManager
                                    val clip = ClipData.newPlainText(
                                        "Onion Address",
                                        torStatus.onionAddress
                                    )
                                    clipboard.setPrimaryClip(clip)
                                    copiedOnion = true
                                }
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy address",
                                    tint = if (copiedOnion) SecureGreen else TextSecondary
                                )
                            }
                        }
                        if (copiedOnion) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Copied to clipboard",
                                style = MaterialTheme.typography.bodySmall,
                                color = SecureGreen
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Start/Stop Control
            Text(
                text = "Control",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            val isRunning = torStatus.state == EmbeddedTorManager.State.RUNNING
            val isStopped = torStatus.state == EmbeddedTorManager.State.STOPPED ||
                torStatus.state == EmbeddedTorManager.State.ERROR

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.startP2PTor() },
                    enabled = isStopped,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SecureGreen,
                        contentColor = OnSecureGreen,
                        disabledContainerColor = SecureGreen.copy(alpha = 0.3f),
                        disabledContentColor = OnSecureGreen.copy(alpha = 0.5f)
                    )
                ) {
                    Text("Start Tor")
                }

                OutlinedButton(
                    onClick = { viewModel.stopP2PTor() },
                    enabled = !isStopped,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = StatusError,
                        disabledContentColor = TextTertiary
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            if (!isStopped) StatusError.copy(alpha = 0.5f) else DividerMedium
                        )
                    )
                ) {
                    Text("Stop Tor")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Info Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DividerSubtle, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = TacticalSurface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "About P2P Tor",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "P2P Tor creates a hidden service on your device, allowing contacts to " +
                            "message you directly via .onion addresses without any relay server. " +
                            "Messages are end-to-end encrypted and routed through the Tor network " +
                            "for maximum privacy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}
