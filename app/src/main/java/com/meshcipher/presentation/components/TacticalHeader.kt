package com.meshcipher.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshcipher.R
import com.meshcipher.domain.model.ConnectionMode
import com.meshcipher.presentation.theme.*

data class ConnectionStatus(
    val isActive: Boolean,
    val label: String,
    val color: androidx.compose.ui.graphics.Color
)

fun connectionStatusFor(mode: ConnectionMode): ConnectionStatus {
    return when (mode) {
        ConnectionMode.DIRECT -> ConnectionStatus(true, "DIRECT", SecureGreen)
        ConnectionMode.TOR_RELAY -> ConnectionStatus(true, "TOR RELAY", MutedPurple)
        ConnectionMode.P2P_ONLY -> ConnectionStatus(true, "P2P MESH", SkyBlue)
        ConnectionMode.P2P_TOR -> ConnectionStatus(true, "P2P TOR", MutedPurple)
    }
}

@Composable
fun TacticalHeader(
    connectionMode: ConnectionMode,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = connectionStatusFor(connectionMode)

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TacticalBackground)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App name
            Text(
                text = "MeshCipher",
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = TextPrimary
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Mesh network icon
                Icon(
                    painter = painterResource(id = R.drawable.ic_mesh_network),
                    contentDescription = "Mesh status",
                    tint = if (status.isActive) SecureGreen else TextTertiary,
                    modifier = Modifier.size(20.dp)
                )

                // Status pill
                StatusPill(status = status)

                // Settings button
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Subtle bottom divider
        Divider(
            color = DividerSubtle,
            thickness = 1.dp
        )
    }
}

@Composable
private fun StatusPill(
    status: ConnectionStatus,
    modifier: Modifier = Modifier
) {
    val pulseScale by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = TacticalSurface,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Pulsing dot
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .scale(if (status.isActive) pulseScale else 1f)
                    .background(status.color, CircleShape)
            )

            Text(
                text = status.label,
                fontFamily = RobotoMonoFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                color = TextMono,
                letterSpacing = 0.5.sp
            )
        }
    }
}
