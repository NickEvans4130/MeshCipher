package com.meshcipher.presentation.mesh

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.filled.SignalCellularAlt2Bar
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshcipher.domain.model.MeshPeer
import com.meshcipher.domain.model.SignalStrength
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mesh Network") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
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
                        fontWeight = FontWeight.Bold
                    )
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
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
                        fontWeight = FontWeight.Bold
                    )
                }

                items(peers, key = { it.bluetoothAddress }) { peer ->
                    PeerListItem(peer = peer)
                }
            } else if (meshEnabled && hasPermissions) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
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
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Scanning for nearby devices...",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Make sure other MeshCipher devices are nearby with Bluetooth enabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mesh Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = if (meshEnabled && bluetoothEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun MeshNetworkVisualization(
    peers: List<MeshPeer>,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = size.minDimension / 3

        // Center node (this device)
        drawCircle(
            color = primaryColor,
            radius = 24f,
            center = Offset(centerX, centerY)
        )
        drawCircle(
            color = primaryColor.copy(alpha = 0.2f),
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
                SignalStrength.EXCELLENT -> Color(0xFF4CAF50)
                SignalStrength.GOOD -> Color(0xFF8BC34A)
                SignalStrength.FAIR -> Color(0xFFFF9800)
                SignalStrength.WEAK -> Color(0xFFF44336)
            }

            drawLine(
                color = lineColor,
                start = Offset(centerX, centerY),
                end = Offset(peerX, peerY),
                strokeWidth = 2f
            )

            // Peer node
            val peerColor = if (peer.isContact) {
                Color(0xFF4CAF50)
            } else {
                surfaceVariantColor
            }

            drawCircle(
                color = peerColor,
                radius = 16f,
                center = Offset(peerX, peerY)
            )

            // Multi-hop indicator (red ring)
            if (peer.hopCount > 1) {
                drawCircle(
                    color = Color(0xFFF44336),
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
    Card(modifier = Modifier.fillMaxWidth()) {
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                SignalStrength.EXCELLENT -> Color(0xFF4CAF50)
                SignalStrength.GOOD -> Color(0xFF8BC34A)
                SignalStrength.FAIR -> Color(0xFFFF9800)
                SignalStrength.WEAK -> Color(0xFFF44336)
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
