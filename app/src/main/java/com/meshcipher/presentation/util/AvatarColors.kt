package com.meshcipher.presentation.util

import androidx.compose.ui.graphics.Color
import com.meshcipher.presentation.theme.*
import kotlin.math.abs

private val avatarColors = listOf(
    AvatarPurple,
    AvatarBlue,
    AvatarGreen,
    AvatarOrange,
    AvatarTeal,
    AvatarPink,
    AvatarDeepPurple,
    AvatarDeepBlue
)

fun getAvatarColor(userId: String): Color {
    val hash = abs(userId.hashCode())
    return avatarColors[hash % avatarColors.size]
}
