package com.meshcipher.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshcipher.domain.model.ConnectionMode
import com.meshcipher.domain.model.Conversation
import com.meshcipher.presentation.theme.*
import com.meshcipher.presentation.util.getAvatarColor
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TacticalConversationCard(
    conversation: Conversation,
    connectionMode: ConnectionMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val avatarColor = remember(conversation.contactId) {
        getAvatarColor(conversation.contactId)
    }
    val initial = conversation.contactName.firstOrNull()?.uppercase() ?: "?"
    val connectionTag = connectionTagFor(connectionMode)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = TacticalSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(avatarColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = TextPrimary
                )
            }

            // Content
            Column(modifier = Modifier.weight(1f)) {
                // Name + connection tag
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = conversation.contactName,
                        fontFamily = InterFontFamily,
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Text(
                        text = connectionTag,
                        fontFamily = RobotoMonoFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 10.sp,
                        color = TextTertiary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Message preview
                conversation.lastMessage?.let { message ->
                    Text(
                        text = message,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Encryption ghost hint
                val ghostText = remember(conversation.id) {
                    generateGhostText(conversation.lastMessage ?: "encrypted")
                }
                Text(
                    text = ghostText,
                    fontFamily = RobotoMonoFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 10.sp,
                    color = EncryptionGhost,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }

            // Right column: timestamp + badge
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                conversation.lastMessageTime?.let { timestamp ->
                    Text(
                        text = formatConversationTime(timestamp),
                        fontFamily = RobotoMonoFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        color = TextMono
                    )
                }

                if (conversation.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(SecureGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = conversation.unreadCount.toString(),
                            fontFamily = InterFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = OnSecureGreen
                        )
                    }
                }
            }
        }
    }
}

private fun connectionTagFor(mode: ConnectionMode): String {
    return when (mode) {
        ConnectionMode.DIRECT -> "[DIRECT]"
        ConnectionMode.TOR_RELAY -> "[TOR]"
        ConnectionMode.P2P_ONLY -> "[MESH]"
        ConnectionMode.P2P_TOR -> "[P2P]"
    }
}

private fun generateGhostText(seed: String): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz0123456789+/="
    val random = java.util.Random(seed.hashCode().toLong())
    return buildString {
        repeat(seed.length.coerceIn(8, 28)) {
            append(chars[random.nextInt(chars.length)])
        }
    }
}

private fun formatConversationTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "now"
        diff < 3600_000 -> "${diff / 60_000}m"
        diff < 86400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        diff < 604800_000 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
    }
}
