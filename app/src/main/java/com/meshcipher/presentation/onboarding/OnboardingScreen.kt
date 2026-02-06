package com.meshcipher.presentation.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshcipher.presentation.theme.*

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.identityCreated) {
        if (uiState.identityCreated) {
            onComplete()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TacticalBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "MeshCipher",
            style = MaterialTheme.typography.displaySmall,
            color = TextPrimary,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Secure encrypted messaging",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            fontFamily = InterFontFamily
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Create Your Identity",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Your identity is bound to this device's secure hardware. No phone number or email required.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            fontFamily = InterFontFamily
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = uiState.deviceName,
            onValueChange = { viewModel.updateDeviceName(it) },
            label = { Text("Device Name", color = TextSecondary) },
            placeholder = { Text("My Phone", color = TextTertiary) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = SecureGreen,
                unfocusedBorderColor = DividerMedium,
                cursorColor = SecureGreen,
                focusedLabelColor = SecureGreen,
                unfocusedLabelColor = TextSecondary,
                focusedPlaceholderColor = TextTertiary,
                unfocusedPlaceholderColor = TextTertiary
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { viewModel.createIdentity() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isCreating && uiState.deviceName.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = SecureGreen,
                contentColor = OnSecureGreen,
                disabledContainerColor = SecureGreen.copy(alpha = 0.3f),
                disabledContentColor = OnSecureGreen.copy(alpha = 0.5f)
            )
        ) {
            if (uiState.isCreating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = OnSecureGreen
                )
            } else {
                Text(
                    "Create Identity",
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (uiState.error != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = uiState.error!!,
                color = StatusError,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
