package com.meshcipher.presentation.contacts

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshcipher.presentation.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    onBackClick: () -> Unit,
    onContactAdded: () -> Unit,
    viewModel: AddContactViewModel = hiltViewModel()
) {
    val name by viewModel.name.collectAsState()
    val identifier by viewModel.identifier.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val contactAdded by viewModel.contactAdded.collectAsState()
    val isFromQRScan = viewModel.isFromQRScan

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(contactAdded) {
        if (contactAdded) {
            snackbarHostState.showSnackbar("Contact added successfully")
            onContactAdded()
        }
    }

    Scaffold(
        containerColor = TacticalBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isFromQRScan) "Add Scanned Contact" else "Add Contact",
                        color = TextPrimary,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TacticalBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (isFromQRScan) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, SecureGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(
                        containerColor = SecureGreen.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = SecureGreen
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "QR code scanned successfully! Enter a name for this contact.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = name,
                onValueChange = { viewModel.updateName(it) },
                label = { Text("Name", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text("Enter contact name", color = TextTertiary)
                },
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

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = identifier,
                onValueChange = { if (!isFromQRScan) viewModel.updateIdentifier(it) },
                label = { Text("User ID", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = isFromQRScan,
                supportingText = {
                    Text(
                        if (isFromQRScan) "ID from scanned QR code"
                        else "Enter their User ID from Settings > Account",
                        color = TextTertiary
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = SecureGreen,
                    unfocusedBorderColor = DividerMedium,
                    cursorColor = SecureGreen,
                    focusedLabelColor = SecureGreen,
                    unfocusedLabelColor = TextSecondary
                )
            )

            if (viewModel.scannedOnionAddress != null) {
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = viewModel.scannedOnionAddress,
                    onValueChange = {},
                    label = { Text("Onion Address", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    readOnly = true,
                    supportingText = {
                        Text(
                            "P2P address from scanned QR code",
                            color = TextTertiary
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextMono,
                        unfocusedTextColor = TextMono,
                        focusedBorderColor = SecureGreen,
                        unfocusedBorderColor = DividerMedium,
                        focusedLabelColor = SecureGreen,
                        unfocusedLabelColor = TextSecondary
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { viewModel.addContact() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && name.isNotBlank() && identifier.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SecureGreen,
                    contentColor = OnSecureGreen,
                    disabledContainerColor = SecureGreen.copy(alpha = 0.3f),
                    disabledContentColor = OnSecureGreen.copy(alpha = 0.5f)
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = OnSecureGreen
                    )
                } else {
                    Text("Add Contact")
                }
            }
        }
    }
}
