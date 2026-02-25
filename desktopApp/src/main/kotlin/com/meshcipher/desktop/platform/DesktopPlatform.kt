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
}
