@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.meshcipher.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshcipher.desktop.data.ContactRepository
import com.meshcipher.desktop.data.DesktopContact
import com.meshcipher.desktop.data.DesktopMessage
import com.meshcipher.desktop.data.DeviceLinkManager
import com.meshcipher.desktop.data.MessageRepository
import com.meshcipher.desktop.data.MessagingManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class NavItem { CONVERSATIONS, LINK_DEVICE, SETTINGS }

@Composable
fun MeshCipherApp(messagingManager: MessagingManager? = null) {
    MaterialTheme(colorScheme = TacticalColorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background
        ) {
            var selectedNav by remember { mutableStateOf(NavItem.CONVERSATIONS) }
            var selectedContact by remember { mutableStateOf<DesktopContact?>(null) }
            var inChat by remember { mutableStateOf(false) }
            var contactsRefreshKey by remember { mutableStateOf(0) }

            // When contacts are synced via relay, refresh the list
            LaunchedEffect(messagingManager) {
                messagingManager?.contactsUpdated?.collect {
                    contactsRefreshKey++
                    selectedNav = NavItem.CONVERSATIONS
                }
            }

            // When account is unlinked, clear UI state immediately
            LaunchedEffect(Unit) {
                DeviceLinkManager.dataCleared.collect {
                    contactsRefreshKey++
                    selectedContact = null
                    inChat = false
                    selectedNav = NavItem.CONVERSATIONS
                }
            }

            Row(modifier = Modifier.fillMaxSize()) {
                // ── Sidebar ──
                TacticalSidebar(
                    selectedNav = selectedNav,
                    onNavSelect = { nav ->
                        selectedNav = nav
                        if (nav == NavItem.CONVERSATIONS) inChat = false
                    }
                )

                // ── Divider ──
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(Divider)
                )

                // ── Main content ──
                when {
                    selectedNav == NavItem.LINK_DEVICE -> LinkDeviceScreen(
                        onBack = {
                            selectedNav = NavItem.CONVERSATIONS
                            inChat = false
                        },
                        messagingManager = messagingManager
                    )

                    selectedNav == NavItem.SETTINGS -> SettingsScreen(
                        onBack = {
                            selectedNav = NavItem.CONVERSATIONS
                            inChat = false
                        }
                    )

                    inChat && selectedContact != null -> ChatPane(
                        contact = selectedContact!!,
                        messagingManager = messagingManager,
                        onBack = { inChat = false }
                    )

                    else -> ConversationsPane(
                        refreshKey = contactsRefreshKey,
                        onContactSelected = { contact ->
                            selectedContact = contact
                            inChat = true
                        },
                        onRefresh = { contactsRefreshKey++ }
                    )
                }
            }
        }
    }
}

@Composable
private fun TacticalSidebar(
    selectedNav: NavItem,
    onNavSelect: (NavItem) -> Unit
) {
    Column(
        modifier = Modifier
            .width(220.dp)
            .fillMaxHeight()
            .background(Surface)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // App branding
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = "MESHCIPHER",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Accent,
                letterSpacing = 2.sp
            )
            Text(
                text = "SECURE MESSENGER",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                letterSpacing = 1.sp,
                fontFamily = Monospace
            )
        }

        Spacer(Modifier.height(24.dp))

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))

        Spacer(Modifier.height(8.dp))

        // Navigation items
        SidebarNavItem(
            label = "MESSAGES",
            icon = { Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null, modifier = Modifier.size(18.dp)) },
            selected = selectedNav == NavItem.CONVERSATIONS,
            onClick = { onNavSelect(NavItem.CONVERSATIONS) }
        )

        SidebarNavItem(
            label = "LINK DEVICE",
            icon = { Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp)) },
            selected = selectedNav == NavItem.LINK_DEVICE,
            onClick = { onNavSelect(NavItem.LINK_DEVICE) }
        )

        SidebarNavItem(
            label = "SETTINGS",
            icon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp)) },
            selected = selectedNav == NavItem.SETTINGS,
            onClick = { onNavSelect(NavItem.SETTINGS) }
        )

        Spacer(Modifier.weight(1f))

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))

        Spacer(Modifier.height(12.dp))

        // Status footer
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Accent)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "E2E ENCRYPTED",
                style = MaterialTheme.typography.labelSmall,
                color = Accent,
                fontFamily = Monospace,
                letterSpacing = 1.sp
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SidebarNavItem(
    label: String,
    icon: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) AccentDim else Color.Transparent
    val textColor = if (selected) Accent else TextSecondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalContentColor provides textColor) {
            icon()
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            letterSpacing = 1.sp,
            fontFamily = if (selected) Monospace else FontFamily.Default
        )
    }
}

@Composable
private fun ConversationsPane(
    refreshKey: Int,
    onContactSelected: (DesktopContact) -> Unit,
    onRefresh: () -> Unit
) {
    var contacts by remember { mutableStateOf<List<DesktopContact>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(refreshKey) {
        isLoading = true
        contacts = ContactRepository.getAllContacts()
        isLoading = false
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Background)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MESSAGES",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Accent,
                letterSpacing = 2.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextTertiary, modifier = Modifier.size(18.dp))
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
            }

            contacts.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NO CONTACTS", style = MaterialTheme.typography.titleSmall, color = TextTertiary, letterSpacing = 2.sp, fontFamily = Monospace)
                    Spacer(Modifier.height(8.dp))
                    Text("Link your phone to sync contacts", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                }
            }

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(contacts, key = { it.id }) { contact ->
                    ContactRow(contact = contact, onClick = { onContactSelected(contact) })
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: DesktopContact, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .background(Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar circle
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(avatarColor(contact.displayName)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contact.displayName.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = contact.contactId.take(16) + "…",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                fontFamily = Monospace,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ChatPane(
    contact: DesktopContact,
    messagingManager: MessagingManager?,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<DesktopMessage>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LaunchedEffect(contact.contactId) {
        messages = MessageRepository.getForContact(contact.contactId)
    }

    LaunchedEffect(contact.contactId, messagingManager) {
        messagingManager?.newMessages?.collect { msg ->
            if (msg.contactId == contact.contactId) messages = messages + msg
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun sendCurrent() {
        val text = inputText.trim()
        if (text.isEmpty() || isSending) return
        scope.launch {
            isSending = true
            if (messagingManager != null) {
                messagingManager.sendMessage(text, contact.contactId).onSuccess { msg ->
                    messages = messages + msg
                    inputText = ""
                }
            } else {
                val msg = MessageRepository.save(contact.contactId, text, true)
                messages = messages + msg
                inputText = ""
            }
            isSending = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Background)
    ) {
        // Chat header
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
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(avatarColor(contact.displayName)),
                contentAlignment = Alignment.Center
            ) {
                Text(contact.displayName.take(1).uppercase(), fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(contact.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text("END-TO-END ENCRYPTED", style = MaterialTheme.typography.labelSmall, color = Accent, fontFamily = Monospace, letterSpacing = 1.sp)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))

        if (messagingManager == null) {
            Box(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF2A1A1A)).padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Relay not configured — add relayUrl and authToken to ~/.config/meshcipher/relay.conf",
                    style = MaterialTheme.typography.labelSmall,
                    color = ErrorRed,
                    fontFamily = Monospace
                )
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg, timeFormat)
            }
        }

        // Input bar
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && !event.isShiftPressed && event.type == KeyEventType.KeyDown) {
                            sendCurrent()
                            true
                        } else false
                    },
                placeholder = { Text("Message", color = TextTertiary, style = MaterialTheme.typography.bodySmall) },
                maxLines = 5,
                enabled = !isSending,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Divider,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = Accent,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated
                ),
                shape = RoundedCornerShape(8.dp),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { sendCurrent() },
                enabled = inputText.isNotBlank() && !isSending,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (inputText.isNotBlank() && !isSending) Accent else SurfaceElevated)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (inputText.isNotBlank() && !isSending) Color.Black else TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: DesktopMessage, timeFormat: SimpleDateFormat) {
    val isOut = msg.isOutgoing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (isOut) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 420.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 12.dp, topEnd = 12.dp,
                            bottomStart = if (isOut) 12.dp else 2.dp,
                            bottomEnd = if (isOut) 2.dp else 12.dp
                        )
                    )
                    .background(if (isOut) OutgoingBubble else IncomingBubble)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = msg.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isOut) Accent else TextPrimary
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = timeFormat.format(Date(msg.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                fontFamily = Monospace,
                fontSize = 10.sp
            )
        }
    }
}
