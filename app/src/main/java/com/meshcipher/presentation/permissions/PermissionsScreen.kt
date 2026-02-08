package com.meshcipher.presentation.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.meshcipher.presentation.theme.*
import com.meshcipher.util.PermissionUtils

@Composable
fun PermissionsScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current

    var bluetoothGranted by remember {
        mutableStateOf(PermissionUtils.hasBluetoothPermissions(context))
    }
    var wifiDirectGranted by remember {
        mutableStateOf(PermissionUtils.hasWifiDirectPermissions(context))
    }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var notificationsGranted by remember {
        mutableStateOf(PermissionUtils.hasNotificationPermission(context))
    }

    val allGranted = bluetoothGranted && wifiDirectGranted && cameraGranted &&
            micGranted && notificationsGranted

    fun refreshAll() {
        bluetoothGranted = PermissionUtils.hasBluetoothPermissions(context)
        wifiDirectGranted = PermissionUtils.hasWifiDirectPermissions(context)
        cameraGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        micGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        notificationsGranted = PermissionUtils.hasNotificationPermission(context)
    }

    val allPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshAll() }

    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshAll() }

    val wifiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshAll() }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshAll() }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshAll() }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshAll() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TacticalBackground)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Permissions",
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            color = SecureGreen
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "MeshCipher needs these permissions to function across all transport modes.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Grant All button
        Button(
            onClick = {
                allPermissionsLauncher.launch(PermissionUtils.getAllRuntimePermissions())
            },
            enabled = !allGranted,
            colors = ButtonDefaults.buttonColors(
                containerColor = SecureGreen,
                contentColor = OnSecureGreen,
                disabledContainerColor = SecureGreen.copy(alpha = 0.3f),
                disabledContentColor = OnSecureGreen.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (allGranted) "All Permissions Granted" else "Grant All Permissions",
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Or grant individually:",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Bluetooth
        PermissionRow(
            icon = Icons.Default.Bluetooth,
            title = "Bluetooth",
            description = "Bluetooth mesh networking for offline messaging",
            granted = bluetoothGranted,
            onRequest = {
                bluetoothLauncher.launch(PermissionUtils.getRequiredBluetoothPermissions())
            }
        )

        // WiFi Direct
        PermissionRow(
            icon = Icons.Default.Wifi,
            title = "WiFi / Location",
            description = "WiFi Direct P2P and nearby device discovery",
            granted = wifiDirectGranted,
            onRequest = {
                wifiLauncher.launch(PermissionUtils.getWifiDirectPermissions())
            }
        )

        // Camera
        PermissionRow(
            icon = Icons.Default.Camera,
            title = "Camera",
            description = "QR code scanning for contact exchange",
            granted = cameraGranted,
            onRequest = {
                cameraLauncher.launch(PermissionUtils.getCameraPermission())
            }
        )

        // Microphone
        PermissionRow(
            icon = Icons.Default.Mic,
            title = "Microphone",
            description = "Voice message recording",
            granted = micGranted,
            onRequest = {
                micLauncher.launch(PermissionUtils.getAudioPermission())
            }
        )

        // Notifications (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionRow(
                icon = Icons.Default.Notifications,
                title = "Notifications",
                description = "New message alerts",
                granted = notificationsGranted,
                onRequest = {
                    notificationLauncher.launch(PermissionUtils.getNotificationPermission())
                }
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onComplete,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (allGranted) SecureGreen else TacticalElevated,
                contentColor = if (allGranted) OnSecureGreen else TextPrimary
            ),
            border = if (!allGranted) BorderStroke(1.dp, DividerSubtle) else null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (allGranted) "Continue" else "Skip for Now",
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (!allGranted) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "You can grant permissions later in Settings",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        border = BorderStroke(1.dp, if (granted) SecureGreen.copy(alpha = 0.3f) else DividerSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (granted) SecureGreen else TextSecondary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (granted) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Granted",
                    tint = SecureGreen,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                TextButton(
                    onClick = onRequest,
                    colors = ButtonDefaults.textButtonColors(contentColor = SecureGreen)
                ) {
                    Text("Grant", fontFamily = InterFontFamily)
                }
            }
        }
    }
}
