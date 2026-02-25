package com.meshcipher.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.meshcipher.desktop.data.AppDatabase
import com.meshcipher.desktop.ui.MeshCipherApp

fun main() {
    AppDatabase.init()

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "MeshCipher"
        ) {
            MeshCipherApp()
        }
    }
}
