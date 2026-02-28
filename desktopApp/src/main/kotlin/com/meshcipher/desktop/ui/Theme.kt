package com.meshcipher.desktop.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// --- Palette ---
val Background    = Color(0xFF121212)
val Surface       = Color(0xFF1E1E1E)
val SurfaceElevated = Color(0xFF252525)
val SurfaceHighlight = Color(0xFF2E2E2E)
val Accent        = Color(0xFF76D166)
val AccentDark    = Color(0xFF5BAF4D)
val AccentDim     = Color(0xFF2A4D22)
val TextPrimary   = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFB0B0B0)
val TextTertiary  = Color(0xFF6B6B6B)
val Divider       = Color(0xFF2A2A2A)
val ErrorRed      = Color(0xFFEF5350)
val OutgoingBubble = Color(0xFF1C3B1C)
val IncomingBubble = Color(0xFF252525)

// Avatar cycling palette
val AvatarPalette = listOf(
    Color(0xFF9C27B0), Color(0xFF03A9F4), Color(0xFF76D166),
    Color(0xFFFF6F00), Color(0xFF00897B), Color(0xFFD81B60),
    Color(0xFF5E35B1), Color(0xFF1976D2)
)

fun avatarColor(name: String): Color =
    AvatarPalette[Math.abs(name.hashCode()) % AvatarPalette.size]

val TacticalColorScheme = darkColorScheme(
    background       = Background,
    surface          = Surface,
    surfaceVariant   = SurfaceElevated,
    primary          = Accent,
    primaryContainer = AccentDim,
    onPrimary        = Color(0xFF000000),
    onPrimaryContainer = Accent,
    secondary        = Accent,
    secondaryContainer = AccentDim,
    onSecondary      = Color(0xFF000000),
    onBackground     = TextPrimary,
    onSurface        = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline          = Divider,
    outlineVariant   = Color(0xFF1E1E1E),
    error            = ErrorRed,
    surfaceTint      = Color.Transparent
)

val Monospace = FontFamily.Monospace
