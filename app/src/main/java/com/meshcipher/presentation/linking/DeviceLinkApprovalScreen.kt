package com.meshcipher.presentation.linking

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshcipher.shared.domain.model.DeviceLinkRequest
import com.meshcipher.presentation.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceLinkApprovalScreen(
    request: DeviceLinkRequest,
    onApproved: () -> Unit,
    onDenied: () -> Unit,
    onBackClick: () -> Unit,
    viewModel: DeviceLinkApprovalViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state) {
        when (state) {
            is ApprovalState.Approved -> onApproved()
            is ApprovalState.Denied -> onDenied()
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Link Device") },
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
        containerColor = TacticalBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Computer,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = SecureGreen
            )

            Spacer(Modifier.height(24.dp))

            Text(
                "Link this device?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(Modifier.height(12.dp))

            Text(
                request.deviceName,
                style = MaterialTheme.typography.titleMedium,
                color = SecureGreen,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "This device will be able to send and receive your messages. " +
                    "You can revoke access at any time in Settings → Linked Devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            // Show key fingerprint for verification
            val keyPreview = request.publicKeyHex.take(16) + "..." + request.publicKeyHex.takeLast(8)
            Text(
                "Key: $keyPreview",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )

            Spacer(Modifier.height(40.dp))

            if (state is ApprovalState.Loading) {
                CircularProgressIndicator(color = SecureGreen)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.deny(request) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Deny", color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = { viewModel.approve(request) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = SecureGreen)
                    ) {
                        Text("Approve")
                    }
                }
            }

            if (state is ApprovalState.Error) {
                Spacer(Modifier.height(16.dp))
                Text(
                    (state as ApprovalState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
