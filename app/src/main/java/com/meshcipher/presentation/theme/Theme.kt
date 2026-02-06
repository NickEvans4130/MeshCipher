package com.meshcipher.presentation.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val TacticalColorScheme = darkColorScheme(
    primary = SecureGreen,
    onPrimary = OnSecureGreen,
    primaryContainer = SecureGreenDark,
    onPrimaryContainer = TextPrimary,
    secondary = MutedPurple,
    onSecondary = TextPrimary,
    secondaryContainer = TacticalElevated,
    onSecondaryContainer = TextPrimary,
    tertiary = SkyBlue,
    onTertiary = OnSecureGreen,
    tertiaryContainer = TacticalElevated,
    onTertiaryContainer = TextPrimary,
    error = StatusError,
    errorContainer = TacticalElevated,
    onError = TextPrimary,
    onErrorContainer = StatusError,
    background = TacticalBackground,
    onBackground = TextPrimary,
    surface = TacticalSurface,
    onSurface = TextPrimary,
    surfaceVariant = TacticalElevated,
    onSurfaceVariant = TextSecondary,
    outline = DividerMedium,
    outlineVariant = DividerSubtle,
    inverseSurface = TextPrimary,
    inverseOnSurface = TacticalBackground,
    inversePrimary = SecureGreenDark
)

@Composable
fun TacticalMeshCipherTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = TacticalBackground.toArgb()
            window.navigationBarColor = TacticalBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = TacticalColorScheme,
        typography = TacticalTypography,
        content = content
    )
}

// Keep old name as alias for compatibility during migration
@Composable
fun MeshCipherTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    TacticalMeshCipherTheme(content = content)
}
