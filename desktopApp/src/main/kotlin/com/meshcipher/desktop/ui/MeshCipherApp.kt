@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.meshcipher.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import com.meshcipher.desktop.data.ContactRepository
import com.meshcipher.desktop.data.DesktopContact
import com.meshcipher.desktop.data.DesktopMessage
import com.meshcipher.shared.util.generateUUID
import kotlinx.coroutines.launch

enum class Screen { CONVERSATIONS, CHAT, LINK_DEVICE }

@Composable
fun MeshCipherApp() {
    MaterialTheme(
        colorScheme = darkColorScheme()
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            var currentScreen by remember { mutableStateOf(Screen.CONVERSATIONS) }
            var selectedContact by remember { mutableStateOf<DesktopContact?>(null) }

            Row(modifier = Modifier.fillMaxSize()) {
                // --- Sidebar ---
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    header = {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "MC",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                ) {
                    Spacer(Modifier.weight(1f))
                    NavigationRailItem(
                        selected = currentScreen == Screen.LINK_DEVICE,
                        onClick = { currentScreen = Screen.LINK_DEVICE },
                        icon = { Icon(Icons.Default.Link, contentDescription = "Link Device") },
                        label = { Text("Link") }
                    )
                }

                VerticalDivider(modifier = Modifier.fillMaxHeight())

                // --- Main content ---
                when (currentScreen) {
                    Screen.CONVERSATIONS -> ConversationsPane(
                        onContactSelected = { contact ->
                            selectedContact = contact
                            currentScreen = Screen.CHAT
                        }
                    )
                    Screen.CHAT -> {
                        val contact = selectedContact
                        if (contact != null) {
                            ChatPane(
                                contact = contact,
                                onBack = { currentScreen = Screen.CONVERSATIONS }
                            )
                        } else {
                            currentScreen = Screen.CONVERSATIONS
                        }
                    }
                    Screen.LINK_DEVICE -> LinkDeviceScreen(
                        onLinked = { currentScreen = Screen.CONVERSATIONS }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationsPane(onContactSelected: (DesktopContact) -> Unit) {
    var contacts by remember { mutableStateOf<List<DesktopContact>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        contacts = ContactRepository.getAllContacts()
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Messages") },
            actions = {
                IconButton(onClick = { /* placeholder: add contact */ }) {
                    Icon(Icons.Default.Add, contentDescription = "Add contact")
                }
            }
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (contacts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No contacts yet", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Link your phone to sync contacts",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(contacts, key = { it.id }) { contact ->
                    ContactRow(contact = contact, onClick = { onContactSelected(contact) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: DesktopContact, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar placeholder
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = contact.displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text = contact.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = contact.contactId.take(16) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ChatPane(contact: DesktopContact, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<DesktopMessage>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(contact.contactId) {
        messages = ContactRepository.getMessagesForContact(contact.contactId)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(contact.displayName) },
            navigationIcon = {
                TextButton(onClick = onBack) { Text("Back") }
            }
        )

        // Messages list
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            reverseLayout = false
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }
        }

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        // Input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                maxLines = 5
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val text = inputText.trim()
                    if (text.isNotEmpty()) {
                        scope.launch {
                            val newMsg = ContactRepository.insertMessage(
                                messageId = generateUUID(),
                                contactId = contact.contactId,
                                content = text,
                                isOutgoing = true
                            )
                            messages = messages + newMsg
                            inputText = ""
                        }
                    }
                },
                enabled = inputText.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: DesktopMessage) {
    val alignment = if (msg.isOutgoing) Alignment.End else Alignment.Start
    val containerColor = if (msg.isOutgoing)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.surfaceVariant

    val textColor = if (msg.isOutgoing)
        MaterialTheme.colorScheme.onPrimary
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = containerColor,
            modifier = Modifier.widthIn(max = 400.dp)
        ) {
            Text(
                text = msg.content,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = textColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
