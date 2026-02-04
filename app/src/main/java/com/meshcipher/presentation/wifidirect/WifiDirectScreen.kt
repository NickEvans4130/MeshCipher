package com.meshcipher.presentation.wifidirect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshcipher.domain.model.WifiDirectPeer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiDirectScreen(
    onBackClick: () -> Unit,
    viewModel: WifiDirectViewModel = hiltViewModel()
) {
    val isWifiP2pEnabled by viewModel.isWifiP2pEnabled.collectAsState()
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()
    val groupInfo by viewModel.groupInfo.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val isServerRunning by viewModel.isServerRunning.collectAsState()
    val connectedClients by viewModel.connectedClients.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val hasPermissions by viewModel.hasPermissions.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.checkPermissions()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi Direct") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (hasPermissions && isWifiP2pEnabled) {
                        IconButton(
                            onClick = {
                                if (isDiscovering) viewModel.stopDiscovery()
                                else viewModel.startDiscovery()
                            }
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = if (isDiscovering) "Stop Discovery" else "Start Discovery"
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permissions check
            if (!hasPermissions) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Permissions Required",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "WiFi Direct requires location permission to discover nearby devices.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { permissionLauncher.launch(viewModel.getRequiredPermissions()) }
                            ) {
                                Text("Grant Permissions")
                            }
                        }
                    }
                }
            }

            // Not supported warning
            if (!viewModel.isSupported()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "WiFi Direct Not Supported",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "This device does not support WiFi Direct.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Connection Status Card
            item {
                ConnectionStatusCard(
                    isWifiP2pEnabled = isWifiP2pEnabled,
                    isConnected = viewModel.isConnected(),
                    isGroupOwner = viewModel.isGroupOwner(),
                    groupInfo = groupInfo,
                    isServerRunning = isServerRunning,
                    connectedClients = connectedClients,
                    isConnecting = isConnecting,
                    onCreateGroup = { viewModel.createGroup() },
                    onDisconnect = { viewModel.disconnect() }
                )
            }

            // Discovery Status
            if (hasPermissions && isWifiP2pEnabled) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Peer Discovery",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isDiscovering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isDiscovering) "Searching for nearby devices..."
                                else "Tap refresh to search for devices",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Discovered Peers
            if (discoveredPeers.isNotEmpty()) {
                item {
                    Text(
                        text = "Nearby Devices (${discoveredPeers.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(discoveredPeers) { peer ->
                    PeerCard(
                        peer = peer,
                        isConnecting = isConnecting,
                        onConnect = { viewModel.connectToPeer(peer.deviceAddress) }
                    )
                }
            } else if (hasPermissions && isWifiP2pEnabled && !isDiscovering) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.WifiOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No devices found",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Start discovery to find nearby devices",
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
private fun ConnectionStatusCard(
    isWifiP2pEnabled: Boolean,
    isConnected: Boolean,
    isGroupOwner: Boolean,
    groupInfo: com.meshcipher.domain.model.WifiDirectGroup?,
    isServerRunning: Boolean,
    connectedClients: Set<String>,
    isConnecting: Boolean,
    onCreateGroup: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = if (isConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = when {
                            !isWifiP2pEnabled -> "WiFi P2P Disabled"
                            isConnected && isGroupOwner -> "Group Owner"
                            isConnected -> "Connected"
                            else -> "Not Connected"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (groupInfo != null) {
                        Text(
                            text = "Network: ${groupInfo.networkName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (isConnected && isGroupOwner) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Group,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${connectedClients.size} client(s) connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isServerRunning) {
                    Text(
                        text = "Server running on port 8988",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isConnected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect")
                }
            } else if (isWifiP2pEnabled) {
                Button(
                    onClick = onCreateGroup,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isConnecting
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Create Group")
                }
            }
        }
    }
}

@Composable
private fun PeerCard(
    peer: WifiDirectPeer,
    isConnecting: Boolean,
    onConnect: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.deviceName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = peer.deviceAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusBadge(status = peer.status)
            }

            if (peer.status == WifiDirectPeer.ConnectionStatus.AVAILABLE) {
                Button(
                    onClick = onConnect,
                    enabled = !isConnecting
                ) {
                    Text("Connect")
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: WifiDirectPeer.ConnectionStatus) {
    val (color, text) = when (status) {
        WifiDirectPeer.ConnectionStatus.AVAILABLE -> MaterialTheme.colorScheme.primary to "Available"
        WifiDirectPeer.ConnectionStatus.INVITED -> MaterialTheme.colorScheme.tertiary to "Invited"
        WifiDirectPeer.ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary to "Connected"
        WifiDirectPeer.ConnectionStatus.FAILED -> MaterialTheme.colorScheme.error to "Failed"
        WifiDirectPeer.ConnectionStatus.UNAVAILABLE -> MaterialTheme.colorScheme.outline to "Unavailable"
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.1f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
