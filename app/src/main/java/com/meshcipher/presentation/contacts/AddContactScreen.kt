package com.meshcipher.presentation.contacts

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

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
    val isFromQRScan = viewModel.isFromQRScan

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isFromQRScan) "Add Scanned Contact" else "Add Contact") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
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
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "QR code scanned successfully! Enter a name for this contact.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = name,
                onValueChange = { viewModel.updateName(it) },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Enter contact name") }
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = identifier,
                onValueChange = { if (!isFromQRScan) viewModel.updateIdentifier(it) },
                label = { Text("User ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = isFromQRScan,
                supportingText = {
                    Text(
                        if (isFromQRScan) "ID from scanned QR code"
                        else "Enter their User ID from Settings > Account"
                    )
                }
            )

            if (viewModel.scannedOnionAddress != null) {
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = viewModel.scannedOnionAddress,
                    onValueChange = {},
                    label = { Text("Onion Address") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    readOnly = true,
                    supportingText = {
                        Text("P2P address from scanned QR code")
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { viewModel.addContact(onContactAdded) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && name.isNotBlank() && identifier.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Add Contact")
                }
            }
        }
    }
}
