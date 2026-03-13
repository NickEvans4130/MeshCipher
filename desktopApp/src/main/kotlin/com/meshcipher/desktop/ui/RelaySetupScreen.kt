@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.meshcipher.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshcipher.desktop.network.RelayAuthManager
import com.meshcipher.shared.crypto.KeyManager
import kotlinx.coroutines.launch

private enum class SetupStep { INPUT, CONNECTING, SUCCESS, ERROR }

@Composable
fun RelaySetupScreen(
    keyManager: KeyManager,
    deviceId: String,
    onSetupComplete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var relayUrl by remember { mutableStateOf("https://relay.meshcipher.com") }
    var step by remember { mutableStateOf(SetupStep.INPUT) }
    var errorMessage by remember { mutableStateOf("") }

    MaterialTheme(colorScheme = TacticalColorScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .width(460.dp)
                        .background(Surface, RoundedCornerShape(12.dp))
                        .padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Branding
                    Text(
                        "MESHCIPHER",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Accent,
                        letterSpacing = 3.sp,
                        fontFamily = Monospace
                    )
                    Text(
                        "RELAY SETUP",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextTertiary,
                        letterSpacing = 2.sp,
                        fontFamily = Monospace
                    )

                    HorizontalDivider(color = Divider)

                    when (step) {
                        SetupStep.INPUT, SetupStep.ERROR -> {
                            Text(
                                "Connect to a relay server to enable messaging. " +
                                "Your identity key will be registered automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )

                            OutlinedTextField(
                                value = relayUrl,
                                onValueChange = {
                                    relayUrl = it
                                    if (step == SetupStep.ERROR) step = SetupStep.INPUT
                                },
                                label = { Text("Relay URL", color = TextTertiary) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Divider,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    cursorColor = Accent,
                                    focusedContainerColor = SurfaceElevated,
                                    unfocusedContainerColor = SurfaceElevated
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )

                            if (step == SetupStep.ERROR) {
                                Text(
                                    errorMessage,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ErrorRed,
                                    fontFamily = Monospace,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Button(
                                onClick = {
                                    step = SetupStep.CONNECTING
                                    scope.launch {
                                        RelayAuthManager.authenticate(
                                            relayUrl = relayUrl.trim(),
                                            keyManager = keyManager,
                                            deviceId = deviceId
                                        ).onSuccess {
                                            step = SetupStep.SUCCESS
                                            onSetupComplete()
                                        }.onFailure { e ->
                                            errorMessage = e.message ?: "Connection failed"
                                            step = SetupStep.ERROR
                                        }
                                    }
                                },
                                enabled = relayUrl.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "CONNECT",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp,
                                    fontFamily = Monospace
                                )
                            }
                        }

                        SetupStep.CONNECTING -> {
                            Text(
                                "Registering identity and authenticating...",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                            CircularProgressIndicator(
                                color = Accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        SetupStep.SUCCESS -> {
                            Text(
                                "CONNECTED",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Accent,
                                fontFamily = Monospace,
                                letterSpacing = 2.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
