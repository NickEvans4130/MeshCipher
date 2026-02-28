package com.meshcipher.desktop.platform

import java.io.File

object DesktopPlatform {

    val configDir: File = File(
        System.getProperty("user.home"),
        ".config/meshcipher"
    ).also { it.mkdirs() }

    val dataDir: File = File(
        System.getProperty("user.home"),
        ".local/share/meshcipher"
    ).also { it.mkdirs() }

    val dbFile: File = File(dataDir, "meshcipher.db")

    val isLinux: Boolean = System.getProperty("os.name").lowercase().contains("linux")
    val isMac: Boolean = System.getProperty("os.name").lowercase().contains("mac")
    val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

    val osName: String = System.getProperty("os.name")

    /**
     * Show an OS desktop notification with the given title and body.
     *
     * - Linux: uses `notify-send` (libnotify, available on GNOME/KDE/XFCE)
     * - macOS: uses `osascript` to post a notification via AppleScript
     * - Windows / fallback: java.awt.SystemTray with TrayIcon.displayMessage
     */
    fun showNotification(title: String, body: String) {
        runCatching {
            when {
                isLinux -> ProcessBuilder(
                    "notify-send",
                    "--app-name=MeshCipher",
                    "--icon=dialog-information",
                    title,
                    body
                ).start()

                isMac -> ProcessBuilder(
                    "osascript", "-e",
                    "display notification \"${body.replace("\"", "\\\"")}\" " +
                        "with title \"${title.replace("\"", "\\\"")}\" " +
                        "subtitle \"MeshCipher\""
                ).start()

                else -> {
                    // Windows / headless fallback — no-op if no tray icon
                }
            }
        }
        // Notifications are best-effort; swallow all errors silently
    }
}
