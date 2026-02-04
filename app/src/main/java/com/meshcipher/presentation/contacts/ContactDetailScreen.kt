package com.meshcipher.presentation.contacts

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Message
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshcipher.domain.model.MessageExpiryMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    onBackClick: () -> Unit,
    onStartConversation: (String) -> Unit,
    onContactDeleted: () -> Unit,
    viewModel: ContactDetailViewModel = hiltViewModel()
) {
    val contact by viewModel.contact.collectAsState()
    val editName by viewModel.editName.collectAsState()
    val editUserId by viewModel.editUserId.collectAsState()
    val isEditing by viewModel.isEditing.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val isDeleting by viewModel.isDeleting.collectAsState()
    val conversationExpiryMode by viewModel.conversationExpiryMode.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExpiryDropdown by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Contact") },
            text = {
                Text("Are you sure you want to delete ${contact?.displayName ?: "this contact"}? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteContact(onContactDeleted)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Contact" else "Contact") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isEditing) {
                            viewModel.cancelEditing()
                        } else {
                            onBackClick()
                        }
                    }) {
                        Icon(
                            if (isEditing) Icons.Default.Close else Icons.Default.ArrowBack,
                            contentDescription = if (isEditing) "Cancel" else "Back"
                        )
                    }
                },
                actions = {
                    if (!isEditing) {
                        IconButton(onClick = { viewModel.startEditing() }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (contact == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                // Avatar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.size(96.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = (contact?.displayName?.firstOrNull()?.uppercase() ?: "?"),
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                if (isEditing) {
                    // Edit mode
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { viewModel.updateName(it) },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = editUserId,
                        onValueChange = { viewModel.updateUserId(it) },
                        label = { Text("User ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text("Their User ID from Settings > Account") }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { viewModel.saveChanges {} },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving && editName.isNotBlank() && editUserId.isNotBlank()
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Save Changes")
                        }
                    }
                } else {
                    // View mode
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Name",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = contact?.displayName ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "User ID",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = contact?.signalProtocolAddress?.name ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Message button
                    Button(
                        onClick = {
                            viewModel.startConversation { conversationId ->
                                onStartConversation(conversationId)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Message,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send Message")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Disappearing Messages Card
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Disappearing Messages",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Override the device default for this contact",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            ExposedDropdownMenuBox(
                                expanded = showExpiryDropdown,
                                onExpandedChange = { showExpiryDropdown = it }
                            ) {
                                val displayValue = if (conversationExpiryMode == null) {
                                    "Use Device Default"
                                } else {
                                    MessageExpiryMode.fromName(conversationExpiryMode)?.displayName ?: "Use Device Default"
                                }

                                OutlinedTextField(
                                    value = displayValue,
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = showExpiryDropdown)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor()
                                )

                                ExposedDropdownMenu(
                                    expanded = showExpiryDropdown,
                                    onDismissRequest = { showExpiryDropdown = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Use Device Default") },
                                        onClick = {
                                            viewModel.setConversationExpiryMode(null)
                                            showExpiryDropdown = false
                                        }
                                    )
                                    MessageExpiryMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.displayName) },
                                            onClick = {
                                                viewModel.setConversationExpiryMode(mode)
                                                showExpiryDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Delete button at bottom
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        enabled = !isDeleting
                    ) {
                        if (isDeleting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delete Contact")
                        }
                    }
                }
            }
        }
    }
}
