package com.meshcipher.presentation.mesh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.filled.SignalCellularAlt2Bar
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshcipher.domain.model.MeshPeer
import com.meshcipher.domain.model.SignalStrength
import com.meshcipher.presentation.theme.*
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshNetworkScreen(
    onBackClick: () -> Unit,
    viewModel: MeshNetworkViewModel = hiltViewModel()
) {
    val peers by viewModel.discoveredPeers.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val isAdvertising by viewModel.isAdvertising.collectAsState()
    val meshEnabled by viewModel.meshEnabled.collectAsState()
    val hasPermissions by viewModel.hasPermissions.collectAsState()
    val myUserId by viewModel.myUserId.collectAsState()
    val context = LocalContext.current
    var showCopiedMessage by remember { mutableStateOf(false) }

    LaunchedEffect(showCopiedMessage) {
        if (showCopiedMessage) {
            delay(2000)
            showCopiedMessage = false
        }
    }

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Mesh Network",
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Your User ID card
            item {
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
                            text = "Your User ID",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Share this with contacts so they can message you via mesh",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = myUserId ?: "Loading...",
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = RobotoMonoFontFamily,
                                fontWeight = FontWeight.Medium,
                                color = TextMono,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    myUserId?.let { id ->
                                        val clipboard = context.getSystemService(
                                            Context.CLIPBOARD_SERVICE
                                        ) as ClipboardManager
                                        val clip = ClipData.newPlainText("User ID", id)
                                        clipboard.setPrimaryClip(clip)
                                        showCopiedMessage = true
                                    }
                                },
                                enabled = myUserId != null
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy ID",
                                    tint = if (showCopiedMessage) SecureGreen else TextSecondary
                                )
                            }
                        }
                        if (showCopiedMessage) {
                            Text(
                                text = "Copied to clipboard",
                                style = MaterialTheme.typography.bodySmall,
                                color = SecureGreen
                            )
                        }
                    }
                }
            }

            // Status card
            item {
                MeshStatusCard(
                    peerCount = peers.size,
                    isScanning = isScanning,
                    isAdvertising = isAdvertising,
                    meshEnabled = meshEnabled,
                    hasPermissions = hasPermissions,
                    routeCount = viewModel.getRouteCount(),
                    bluetoothSupported = viewModel.isBluetoothSupported(),
                    bluetoothEnabled = viewModel.isBluetoothEnabled()
                )
            }

            // Visualization
            if (peers.isNotEmpty() || meshEnabled) {
                item {
                    Text(
                        text = "Network Topology",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, DividerSubtle, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(
                            containerColor = TacticalSurface
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        MeshNetworkVisualization(
                            peers = peers,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                                .padding(16.dp)
                        )
                    }
                }
            }

            // Peer list
            if (peers.isNotEmpty()) {
                item {
                    Text(
                        text = "Discovered Peers (${peers.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                items(peers, key = { it.bluetoothAddress }) { peer ->
                    PeerListItem(peer = peer)
                }
            } else if (meshEnabled && hasPermissions) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, DividerSubtle, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(
                            containerColor = TacticalSurface
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.BluetoothSearching,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Scanning for nearby devices...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary
                            )
                            Text(
                                text = "Make sure other MeshCipher devices are nearby with Bluetooth enabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshStatusCard(
    peerCount: Int,
    isScanning: Boolean,
    isAdvertising: Boolean,
    meshEnabled: Boolean,
    hasPermissions: Boolean,
    routeCount: Int,
    bluetoothSupported: Boolean,
    bluetoothEnabled: Boolean
) {
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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mesh Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = if (meshEnabled && bluetoothEnabled) SecureGreen else TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            StatusRow("Bluetooth", when {
                !bluetoothSupported -> "Not supported"
                !bluetoothEnabled -> "Disabled"
                else -> "Enabled"
            })
            StatusRow("Permissions", if (hasPermissions) "Granted" else "Required")
            StatusRow("Advertising", if (isAdvertising) "Active" else "Inactive")
            StatusRow("Scanning", if (isScanning) "Active" else "Inactive")
            StatusRow("Nearby Peers", peerCount.toString())
            StatusRow("Known Routes", routeCount.toString())
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    val valueColor = when (value) {
        "Active", "Enabled", "Granted" -> StatusActive
        "Disabled", "Not supported", "Required" -> StatusWarning
        "Inactive" -> TextTertiary
        else -> TextPrimary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = RobotoMonoFontFamily,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

@Composable
fun MeshNetworkVisualization(
    peers: List<MeshPeer>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = size.minDimension / 3

        // Center node (this device)
        drawCircle(
            color = SecureGreen,
            radius = 24f,
            center = Offset(centerX, centerY)
        )
        drawCircle(
            color = SecureGreen.copy(alpha = 0.2f),
            radius = 36f,
            center = Offset(centerX, centerY)
        )

        if (peers.isEmpty()) return@Canvas

        // Draw peers around center
        peers.forEachIndexed { index, peer ->
            val angle = (2 * Math.PI * index / peers.size).toFloat()
            val peerX = centerX + radius * cos(angle)
            val peerY = centerY + radius * sin(angle)

            // Connection line colored by signal strength
            val lineColor = when (peer.signalStrength) {
                SignalStrength.EXCELLENT -> StatusActive
                SignalStrength.GOOD -> Color(0xFF8BC34A)
                SignalStrength.FAIR -> StatusWarning
                SignalStrength.WEAK -> StatusError
            }

            drawLine(
                color = lineColor,
                start = Offset(centerX, centerY),
                end = Offset(peerX, peerY),
                strokeWidth = 2f
            )

            // Peer node
            val peerColor = if (peer.isContact) {
                SecureGreen
            } else {
                TextSecondary
            }

            drawCircle(
                color = peerColor,
                radius = 16f,
                center = Offset(peerX, peerY)
            )

            // Multi-hop indicator (red ring)
            if (peer.hopCount > 1) {
                drawCircle(
                    color = StatusError,
                    radius = 20f,
                    center = Offset(peerX, peerY),
                    style = Stroke(width = 2f)
                )
            }
        }
    }
}

@Composable
fun PeerListItem(peer: MeshPeer) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DividerSubtle, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = TacticalSurface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName ?: peer.deviceId,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append("${peer.hopCount} hop")
                        if (peer.hopCount != 1) append("s")
                        append(" | RSSI: ${peer.rssi} dBm")
                        if (peer.reachableVia != null) {
                            append(" | via ${peer.reachableVia.take(8)}...")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = RobotoMonoFontFamily,
                    color = TextMono
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            val signalIcon = when (peer.signalStrength) {
                SignalStrength.EXCELLENT -> Icons.Default.SignalCellular4Bar
                SignalStrength.GOOD -> Icons.Default.SignalCellularAlt
                SignalStrength.FAIR -> Icons.Default.SignalCellularAlt2Bar
                SignalStrength.WEAK -> Icons.Default.SignalCellularAlt1Bar
            }

            val signalColor = when (peer.signalStrength) {
                SignalStrength.EXCELLENT -> StatusActive
                SignalStrength.GOOD -> Color(0xFF8BC34A)
                SignalStrength.FAIR -> StatusWarning
                SignalStrength.WEAK -> StatusError
            }

            Icon(
                signalIcon,
                contentDescription = "Signal: ${peer.signalStrength.name}",
                tint = signalColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
