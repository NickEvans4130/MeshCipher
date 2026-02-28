package com.meshcipher.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.rememberTrayState
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * System tray icon and menu for MeshCipher Desktop.
 *
 * Displays an icon in the system tray with a right-click menu to
 * show/restore the main window or quit the application.
 */
@Composable
fun ApplicationScope.MeshCipherTray(
    trayState: TrayState = rememberTrayState(),
    onShow: () -> Unit,
    onQuit: () -> Unit
) {
    val icon = rememberTrayIcon()

    Tray(
        state = trayState,
        icon = icon,
        tooltip = "MeshCipher"
    ) {
        Item("Show MeshCipher", onClick = onShow)
        Separator()
        Item("Quit", onClick = onQuit)
    }
}

@Composable
private fun rememberTrayIcon(): Painter {
    val img = remember { buildTrayIcon() }
    return remember(img) {
        object : Painter() {
            override val intrinsicSize = Size(img.width.toFloat(), img.height.toFloat())

            override fun DrawScope.onDraw() {
                val baos = ByteArrayOutputStream()
                ImageIO.write(img, "png", baos)
                val bitmap = org.jetbrains.skia.Image.makeFromEncoded(baos.toByteArray())
                    .toComposeImageBitmap()
                drawImage(bitmap)
            }
        }
    }
}

private fun buildTrayIcon(): BufferedImage {
    val size = 64
    val bi = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = bi.createGraphics()

    // Dark green circle background
    g.color = Color(0x00, 0xAA, 0x55, 0xFF)
    g.fillOval(0, 0, size, size)

    // Simple lock body (white rectangle with rounded corners)
    g.color = Color.WHITE
    g.fillRoundRect(16, 32, 32, 24, 6, 6)

    // Lock shackle (arc)
    g.color = Color.WHITE
    g.drawArc(20, 16, 24, 24, 0, 180)

    g.dispose()
    return bi
}
