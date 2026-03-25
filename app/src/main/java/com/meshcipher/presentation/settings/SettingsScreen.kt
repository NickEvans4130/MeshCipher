package com.meshcipher.presentation.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.data.tor.TorManager
import com.meshcipher.domain.model.ConnectionMode
import com.meshcipher.domain.model.MessageExpiryMode
import com.meshcipher.presentation.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onMeshNetworkClick: () -> Unit = {},
    onShareContactClick: () -> Unit = {},
    onScanContactClick: () -> Unit = {},
    onWifiDirectClick: () -> Unit = {},
    onP2PTorClick: () -> Unit = {},
    onGuideClick: () -> Unit = {},
    onLinkedDevicesClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val connectionMode by viewModel.connectionMode.collectAsState()
    val torStatus by viewModel.torStatus.collectAsState()
    val meshEnabled by viewModel.meshEnabled.collectAsState()
    val hasBluetoothPermissions by viewModel.hasBluetoothPermissions.collectAsState()
    val userId by viewModel.userId.collectAsState()
    val messageExpiryMode by viewModel.messageExpiryMode.collectAsState()
    val relayServerUrl by viewModel.relayServerUrl.collectAsState()
    val smartModeEnabled by viewModel.smartModeEnabled.collectAsState()
    val preferTor by viewModel.preferTor.collectAsState()
    val ephemeralOnionMode by viewModel.ephemeralOnionMode.collectAsState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var showCopiedMessage by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember { mutableStateOf(viewModel.hasNotificationPermission()) }
    var showExpiryDropdown by remember { mutableStateOf(false) }
    var relayUrlInput by remember(relayServerUrl) { mutableStateOf(relayServerUrl) }

    LaunchedEffect(showCopiedMessage) {
        if (showCopiedMessage) {
            delay(2000)
            showCopiedMessage = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.checkBluetoothPermissions()
        hasNotificationPermission = viewModel.hasNotificationPermission()
    }

    LaunchedEffect(Unit) {
        viewModel.refreshTorStatus()
    }

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TacticalBackground,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
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
            // Account Section
            Text(
                text = "Account",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = SecureGreen
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(1.dp, DividerSubtle)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Your ID",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = userId ?: "Loading...",
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = RobotoMonoFontFamily,
                            color = TextMono,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                userId?.let { id ->
                                    val clipboard = context.getSystemService(
                                        Context.CLIPBOARD_SERVICE
                                    ) as ClipboardManager
                                    val clip = ClipData.newPlainText("User ID", id)
                                    clipboard.setPrimaryClip(clip)
                                    showCopiedMessage = true
                                }
                            },
                            enabled = userId != null
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy ID",
                                tint = if (showCopiedMessage) {
                                    SecureGreen
                                } else {
                                    TextSecondary
                                }
                            )
                        }
                    }
                    if (showCopiedMessage) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Copied to clipboard",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecureGreen
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // QR Code buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onShareContactClick),
                    colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                    border = BorderStroke(1.dp, DividerSubtle)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.QrCode,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = SecureGreen
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "My QR Code",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Share with others",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onScanContactClick),
                    colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                    border = BorderStroke(1.dp, DividerSubtle)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = SecureGreen
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Scan QR Code",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Add a contact",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Connection Mode Section
            Text(
                text = "Connection Mode",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = SecureGreen
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Smart Mode toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (smartModeEnabled) TacticalElevated else TacticalSurface
                ),
                border = BorderStroke(
                    width = if (smartModeEnabled) 2.dp else 1.dp,
                    color = if (smartModeEnabled) SecureGreen else DividerSubtle
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Smart Mode",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Automatically choose the best transport: WiFi Direct → Internet → Bluetooth",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = smartModeEnabled,
                            onCheckedChange = { viewModel.setSmartModeEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = OnSecureGreen,
                                checkedTrackColor = SecureGreen,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = TacticalElevated,
                                uncheckedBorderColor = DividerMedium
                            )
                        )
                    }

                    // Prefer TOR option – only visible when Smart Mode is on
                    AnimatedVisibility(visible = smartModeEnabled) {
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))
                            Divider(color = DividerSubtle)
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Prefer TOR",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Route internet traffic via TOR. Slower but hides your IP.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                                Switch(
                                    checked = preferTor,
                                    onCheckedChange = { viewModel.setPreferTor(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = OnSecureGreen,
                                        checkedTrackColor = SecureGreen,
                                        uncheckedThumbColor = TextSecondary,
                                        uncheckedTrackColor = TacticalElevated,
                                        uncheckedBorderColor = DividerMedium
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // GAP-10 / R-10: Ephemeral .onion mode
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(1.dp, DividerSubtle)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Security,
                                    contentDescription = null,
                                    tint = SecureGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Ephemeral .onion address",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary
                                )
                            }
                            Text(
                                text = "Generate a new Tor address each session. Contacts are notified automatically. Recommended for high-risk use.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = ephemeralOnionMode,
                            onCheckedChange = { viewModel.setEphemeralOnionMode(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = OnSecureGreen,
                                checkedTrackColor = SecureGreen,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = TacticalElevated,
                                uncheckedBorderColor = DividerMedium
                            )
                        )
                    }
                }
            }

            // Manual transport selection – only visible when Smart Mode is off
            AnimatedVisibility(visible = !smartModeEnabled) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Manual Transport",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    ConnectionModeCard(
                        title = "Direct",
                        description = "Fast connection directly to relay server. Best performance.",
                        selected = connectionMode == ConnectionMode.DIRECT,
                        onClick = { viewModel.setConnectionMode(ConnectionMode.DIRECT) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    ConnectionModeCard(
                        title = "TOR Relay",
                        description = "Routes through TOR network for IP privacy. Requires Orbot.",
                        selected = connectionMode == ConnectionMode.TOR_RELAY,
                        onClick = { viewModel.setConnectionMode(ConnectionMode.TOR_RELAY) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    ConnectionModeCard(
                        title = "P2P Only",
                        description = "Bluetooth mesh only. No internet required.",
                        selected = connectionMode == ConnectionMode.P2P_ONLY,
                        enabled = meshEnabled,
                        onClick = { viewModel.setConnectionMode(ConnectionMode.P2P_ONLY) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    ConnectionModeCard(
                        title = "P2P Tor",
                        description = "Direct messaging via .onion addresses. No relay server needed.",
                        selected = connectionMode == ConnectionMode.P2P_TOR,
                        onClick = { viewModel.setConnectionMode(ConnectionMode.P2P_TOR) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // TOR Status Section
            TorStatusSection(
                torStatus = torStatus,
                connectionMode = connectionMode,
                onRefresh = { viewModel.refreshTorStatus() },
                onInstallOrbot = {
                    context.startActivity(viewModel.getOrbotInstallIntent())
                },
                onOpenOrbot = {
                    viewModel.getOrbotLaunchIntent()?.let { intent ->
                        context.startActivity(intent)
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Bluetooth Mesh Section
            Text(
                text = "Bluetooth Mesh",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = SecureGreen
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(1.dp, DividerSubtle)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable Mesh Networking",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Communicate with nearby devices via Bluetooth",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = meshEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && !hasBluetoothPermissions) {
                                    permissionLauncher.launch(viewModel.getRequiredPermissions())
                                } else {
                                    viewModel.setMeshEnabled(enabled)
                                }
                            },
                            enabled = hasBluetoothPermissions || !meshEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = OnSecureGreen,
                                checkedTrackColor = SecureGreen,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = TacticalElevated,
                                uncheckedBorderColor = DividerMedium
                            )
                        )
                    }

                    if (!hasBluetoothPermissions) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = StatusError,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Bluetooth permissions required",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusError
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(
                            onClick = { permissionLauncher.launch(viewModel.getRequiredPermissions()) },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = SecureGreen
                            )
                        ) {
                            Text("Grant Permissions")
                        }
                    }

                    if (!hasNotificationPermission) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = null,
                                tint = StatusWarning,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Enable notifications to be alerted of new messages",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(
                            onClick = { permissionLauncher.launch(viewModel.getRequiredPermissions()) },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = SecureGreen
                            )
                        ) {
                            Text("Enable Notifications")
                        }
                    }

                    if (!viewModel.isBluetoothEnabled() && viewModel.isBluetoothSupported()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Bluetooth is disabled. Enable it in system settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // View Mesh Network button
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onMeshNetworkClick),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(1.dp, DividerSubtle)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "View Mesh Network",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = "See network topology and nearby devices",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // WiFi Direct Section
            Text(
                text = "WiFi Direct",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = SecureGreen
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onWifiDirectClick),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(1.dp, DividerSubtle)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = null,
                            tint = SecureGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "WiFi Direct P2P",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Text(
                                text = "High-speed transfers up to 100m range",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // P2P Tor Section
            Text(
                text = "P2P Tor",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = SecureGreen
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onP2PTorClick),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(1.dp, DividerSubtle)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = SecureGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "P2P Tor Hidden Service",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Direct messaging via .onion addresses",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Relay Server Section
            Text(
                text = "Relay Server",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = SecureGreen
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(1.dp, DividerSubtle)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Server URL",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Both users must use the same relay server for Direct and Tor Relay modes to work.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = relayUrlInput,
                        onValueChange = { relayUrlInput = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = RobotoMonoFontFamily
                        ),
                        placeholder = {
                            Text(
                                text = AppPreferences.DEFAULT_RELAY_URL,
                                fontFamily = RobotoMonoFontFamily,
                                color = TextSecondary.copy(alpha = 0.5f)
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                val trimmed = relayUrlInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    val url = if (!trimmed.endsWith("/")) "$trimmed/" else trimmed
                                    viewModel.setRelayServerUrl(url)
                                    relayUrlInput = url
                                }
                                focusManager.clearFocus()
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextMono,
                            unfocusedTextColor = TextMono,
                            focusedBorderColor = SecureGreen,
                            unfocusedBorderColor = DividerSubtle,
                            cursorColor = SecureGreen
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (relayServerUrl != AppPreferences.DEFAULT_RELAY_URL) {
                            TextButton(
                                onClick = {
                                    viewModel.resetRelayServerUrl()
                                    focusManager.clearFocus()
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = SecureGreen
                                )
                            ) {
                                Text("Reset to Default")
                            }
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }

                        TextButton(
                            onClick = {
                                val trimmed = relayUrlInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    val url = if (!trimmed.endsWith("/")) "$trimmed/" else trimmed
                                    viewModel.setRelayServerUrl(url)
                                    relayUrlInput = url
                                }
                                focusManager.clearFocus()
                            },
                            enabled = relayUrlInput.trim() != relayServerUrl,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = SecureGreen
                            )
                        ) {
                            Text("Save")
                        }
                    }

                    Text(
                        text = "P2P modes (Bluetooth, WiFi Direct, P2P Tor) do not use the relay server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Privacy Section
            Text(
                text = "Privacy",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = SecureGreen
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(1.dp, DividerSubtle)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Disappearing Messages",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Set a default timer for all new messages",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    ExposedDropdownMenuBox(
                        expanded = showExpiryDropdown,
                        onExpandedChange = { showExpiryDropdown = it }
                    ) {
                        OutlinedTextField(
                            value = messageExpiryMode.displayName,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = showExpiryDropdown)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = SecureGreen,
                                unfocusedBorderColor = DividerSubtle,
                                focusedTrailingIconColor = SecureGreen,
                                unfocusedTrailingIconColor = TextSecondary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )

                        ExposedDropdownMenu(
                            expanded = showExpiryDropdown,
                            onDismissRequest = { showExpiryDropdown = false }
                        ) {
                            MessageExpiryMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            mode.displayName,
                                            color = TextPrimary
                                        )
                                    },
                                    onClick = {
                                        viewModel.setMessageExpiryMode(mode)
                                        showExpiryDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = when (messageExpiryMode) {
                            MessageExpiryMode.NEVER -> "Messages will be kept indefinitely"
                            MessageExpiryMode.ON_APP_CLOSE -> "Messages will be deleted when the app is closed"
                            else -> "Messages will automatically delete after ${messageExpiryMode.displayName.lowercase()}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Help Section
            Text(
                text = "Help",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = SecureGreen
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onGuideClick),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(1.dp, DividerSubtle)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = SecureGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "How It Works",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Learn about connection modes and features",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "LINKED DEVICES",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = SecureGreen
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onLinkedDevicesClick),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(1.dp, DividerSubtle)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = SecureGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Linked Devices",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Manage desktop and other linked devices",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionModeCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val borderColor = if (selected) {
        SecureGreen
    } else {
        DividerSubtle
    }

    val containerColor = if (selected) {
        TacticalElevated
    } else {
        TacticalSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = borderColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = if (enabled) onClick else null,
                enabled = enabled,
                colors = RadioButtonDefaults.colors(
                    selectedColor = SecureGreen,
                    unselectedColor = TextSecondary,
                    disabledSelectedColor = SecureGreen.copy(alpha = 0.38f),
                    disabledUnselectedColor = TextSecondary.copy(alpha = 0.38f)
                )
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) {
                        TextPrimary
                    } else {
                        TextPrimary.copy(alpha = 0.38f)
                    }
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) {
                        TextSecondary
                    } else {
                        TextSecondary.copy(alpha = 0.38f)
                    }
                )
            }
        }
    }
}

@Composable
private fun TorStatusSection(
    torStatus: TorManager.TorStatus,
    connectionMode: ConnectionMode,
    onRefresh: () -> Unit,
    onInstallOrbot: () -> Unit,
    onOpenOrbot: () -> Unit
) {
    Text(
        text = "TOR Status",
        style = MaterialTheme.typography.titleMedium,
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        color = SecureGreen
    )

    Spacer(modifier = Modifier.height(8.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = TacticalSurface
        ),
        border = BorderStroke(1.dp, DividerSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            when {
                !torStatus.orbotInstalled -> {
                    Text(
                        text = "Orbot is not installed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Orbot provides TOR connectivity on Android. Install it to use TOR relay mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onInstallOrbot,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SecureGreen,
                            contentColor = OnSecureGreen
                        )
                    ) {
                        Text("Install Orbot")
                    }
                }

                !torStatus.orbotRunning -> {
                    Text(
                        text = "Orbot is installed but not running",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Start Orbot and connect to the TOR network before enabling TOR relay mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onOpenOrbot,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SecureGreen,
                                contentColor = OnSecureGreen
                            )
                        ) {
                            Text("Open Orbot")
                        }
                        OutlinedButton(
                            onClick = onRefresh,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = SecureGreen
                            ),
                            border = BorderStroke(1.dp, SecureGreen)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Refresh")
                        }
                    }
                }

                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Connected",
                            tint = SecureGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TOR Connected",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = SecureGreen
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Traffic is being routed through the TOR network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onRefresh,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = SecureGreen
                        ),
                        border = BorderStroke(1.dp, SecureGreen)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Refresh")
                    }
                }
            }
        }
    }
}
