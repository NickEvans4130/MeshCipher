package com.meshcipher.presentation.linking

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshcipher.shared.domain.model.DeviceType
import com.meshcipher.shared.domain.model.LinkedDevice
import com.meshcipher.presentation.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedDevicesScreen(
    onBackClick: () -> Unit,
    onScanQrClick: () -> Unit,
    viewModel: LinkedDevicesViewModel = hiltViewModel()
) {
    val devices by viewModel.devices.collectAsState()
    var deviceToRevoke by remember { mutableStateOf<LinkedDevice?>(null) }

    deviceToRevoke?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToRevoke = null },
            title = { Text("Revoke Device") },
            text = { Text("Remove \"${device.deviceName}\" from linked devices?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.revoke(device.deviceId)
                    deviceToRevoke = null
                }) {
                    Text("Revoke", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRevoke = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Linked Devices") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TacticalSurface,
                    titleContentColor = TextPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onScanQrClick,
                containerColor = SecureGreen
            ) {
                Icon(Icons.Default.Add, contentDescription = "Link new device")
            }
        },
        containerColor = TacticalBackground
    ) { padding ->
        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Computer,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = TextSecondary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No linked devices",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap + to link a desktop or other device",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices, key = { it.deviceId }) { device ->
                    LinkedDeviceCard(
                        device = device,
                        onRevoke = { deviceToRevoke = device }
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkedDeviceCard(
    device: LinkedDevice,
    onRevoke: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (device.deviceType) {
                    DeviceType.DESKTOP -> Icons.Default.Computer
                    DeviceType.ANDROID -> Icons.Default.PhoneAndroid
                    else -> Icons.Default.Computer
                },
                contentDescription = null,
                tint = SecureGreen,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.deviceName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    "Linked ${dateFormat.format(Date(device.linkedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (!device.approved) {
                    Text(
                        "Pending approval",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            IconButton(onClick = onRevoke) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Revoke",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
