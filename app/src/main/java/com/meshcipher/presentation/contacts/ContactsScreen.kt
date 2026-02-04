package com.meshcipher.presentation.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshcipher.domain.model.Contact

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onBackClick: () -> Unit,
    onConversationStart: (String) -> Unit,
    onContactClick: (String) -> Unit,
    onAddContactClick: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel()
) {
    val contacts by viewModel.contacts.collectAsState()
    val unreadCounts by viewModel.unreadCounts.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddContactClick) {
                Icon(Icons.Default.PersonAdd, contentDescription = "Add contact")
            }
        }
    ) { padding ->
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No contacts yet",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap + to add a contact",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(contacts) { contact ->
                    ContactItem(
                        contact = contact,
                        unreadCount = unreadCounts[contact.id] ?: 0,
                        onClick = { onContactClick(contact.id) }
                    )
                    Divider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactItem(
    contact: Contact,
    unreadCount: Int = 0,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = contact.displayName.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (unreadCount > 0) {
                Badge(
                    modifier = Modifier.align(Alignment.TopEnd),
                    containerColor = MaterialTheme.colorScheme.error
                ) {
                    Text(
                        text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Normal
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = contact.signalProtocolAddress.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Icon(
            Icons.Default.ChevronRight,
            contentDescription = "View details",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
