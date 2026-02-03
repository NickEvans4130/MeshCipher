package com.meshcipher.presentation.navigation

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Conversations : Screen("conversations")
    object Chat : Screen("chat/{conversationId}") {
        fun createRoute(conversationId: String) = "chat/$conversationId"
    }
    object Contacts : Screen("contacts")
    object AddContact : Screen("add_contact")
    object Settings : Screen("settings")
    object ShareContact : Screen("share_contact")
    object ScanContact : Screen("scan_contact")
    object MeshNetwork : Screen("mesh_network")
}
