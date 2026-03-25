package com.meshcipher.presentation.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshcipher.data.tor.TorBridge
import com.meshcipher.presentation.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: BridgeSettingsViewModel = hiltViewModel()
) {
    val bridges by viewModel.bridges.collectAsState()
    val fetchState by viewModel.fetchState.collectAsState()
    val validationError by viewModel.validationError.collectAsState()
    var bridgeInput by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text("Tor Bridges", color = TextPrimary, fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TacticalBackground)
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
            Text(
                "Bridges",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = SecureGreen
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Configure obfs4 bridges to use Tor in censored environments. " +
                "Without bridges, Tor uses vanilla entry nodes.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(Modifier.height(16.dp))

            // Manual bridge entry
            Text("Add Bridge", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = bridgeInput,
                onValueChange = {
                    bridgeInput = it
                    viewModel.clearValidationError()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("obfs4 <ip:port> [fingerprint] cert=<cert>") },
                isError = validationError != null,
                supportingText = {
                    if (validationError != null) {
                        Text(validationError!!, color = StatusError)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                    viewModel.addBridgeLine(bridgeInput)
                    if (validationError == null) bridgeInput = ""
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SecureGreen,
                    unfocusedBorderColor = DividerSubtle
                )
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.addBridgeLine(bridgeInput)
                        if (validationError == null) bridgeInput = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SecureGreen)
                ) { Text("Add bridge") }
                OutlinedButton(
                    onClick = { viewModel.fetchBridgesFromTorProject() },
                    enabled = fetchState !is BridgeFetchState.Loading,
                    border = BorderStroke(1.dp, SecureGreen),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SecureGreen)
                ) {
                    if (fetchState is BridgeFetchState.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = SecureGreen, strokeWidth = 2.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text("Fetch from torproject.org")
                }
            }

            when (val s = fetchState) {
                is BridgeFetchState.Success -> Text("Added ${s.count} bridge(s).", color = SecureGreen, style = MaterialTheme.typography.bodySmall)
                is BridgeFetchState.Error -> Text(s.message, color = StatusError, style = MaterialTheme.typography.bodySmall)
                else -> {}
            }

            Spacer(Modifier.height(24.dp))

            // Bridge list
            if (bridges.isEmpty()) {
                Text("No bridges configured. Using vanilla Tor.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            } else {
                Text("Configured bridges (${bridges.size})", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                bridges.forEach { bridge ->
                    BridgeItem(bridge = bridge, onDelete = { viewModel.removeBridge(bridge) })
                    Divider(color = DividerSubtle)
                }
            }
        }
    }
}

@Composable
private fun BridgeItem(bridge: TorBridge, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${bridge.type} · ${bridge.address}",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontFamily = RobotoMonoFontFamily
            )
            bridge.fingerprint?.let {
                Text(
                    text = it.take(20) + "…",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontFamily = RobotoMonoFontFamily
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete bridge", tint = StatusError)
        }
    }
}
