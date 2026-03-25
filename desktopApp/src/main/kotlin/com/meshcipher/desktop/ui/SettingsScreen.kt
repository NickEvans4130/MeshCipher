@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.meshcipher.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshcipher.desktop.data.SettingsRepository
import com.meshcipher.desktop.network.TorConnectivityChecker
import com.meshcipher.desktop.network.TorConnectivityChecker.TorStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit = {}) {
    val torEnabled by SettingsRepository.torEnabled.collectAsState()
    var torStatus by remember { mutableStateOf<TorStatus?>(null) }
    var checkingTor by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Auto-check TOR status when the screen opens or toggle changes
    LaunchedEffect(torEnabled) {
        if (torEnabled) {
            checkingTor = true
            torStatus = withContext(Dispatchers.IO) { TorConnectivityChecker.check() }
            checkingTor = false
        } else {
            torStatus = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
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
                text = "SETTINGS",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Accent,
                letterSpacing = 2.sp
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "PRIVACY",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                letterSpacing = 2.sp,
                fontFamily = Monospace
            )

            // TOR toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        text = "Route traffic through TOR",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "SOCKS5 localhost:9050 — requires TOR to be running",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        fontFamily = Monospace
                    )
                }
                Switch(
                    checked = torEnabled,
                    onCheckedChange = { SettingsRepository.setTorEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Background,
                        checkedTrackColor = Accent,
                        uncheckedThumbColor = TextTertiary,
                        uncheckedTrackColor = SurfaceElevated
                    )
                )
            }

            // TOR status + test button (only shown when enabled)
            if (torEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (dotColor, statusText) = when {
                            checkingTor -> Color(0xFFFFAA00) to "CHECKING..."
                            torStatus == TorStatus.RUNNING -> Accent to "RUNNING"
                            torStatus == TorStatus.NOT_RUNNING -> ErrorRed to "NOT RUNNING"
                            torStatus == TorStatus.UNKNOWN -> Color(0xFFFFAA00) to "UNKNOWN"
                            else -> TextTertiary to "—"
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(dotColor)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = dotColor,
                            fontFamily = Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                checkingTor = true
                                torStatus = withContext(Dispatchers.IO) { TorConnectivityChecker.check() }
                                checkingTor = false
                            }
                        },
                        enabled = !checkingTor
                    ) {
                        Text(
                            text = "TEST CONNECTION",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (!checkingTor) Accent else TextTertiary,
                            fontFamily = Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                }

                if (torStatus == TorStatus.NOT_RUNNING) {
                    Text(
                        text = "TOR is not running. Install the TOR Browser or run: sudo systemctl start tor",
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed,
                        fontFamily = Monospace,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}
