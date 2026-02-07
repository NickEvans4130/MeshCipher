package com.meshcipher.presentation.navigation

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Conversations : Screen("conversations")
    object Chat : Screen("chat/{conversationId}") {
        fun createRoute(conversationId: String) = "chat/$conversationId"
    }
    object Contacts : Screen("contacts")
    object AddContact : Screen("add_contact?userId={userId}&onionAddress={onionAddress}") {
        fun createRoute(userId: String? = null, onionAddress: String? = null): String {
            val params = mutableListOf<String>()
            if (userId != null) params.add("userId=$userId")
            if (onionAddress != null) params.add("onionAddress=$onionAddress")
            return if (params.isEmpty()) "add_contact" else "add_contact?${params.joinToString("&")}"
        }
    }
    object ContactDetail : Screen("contact/{contactId}") {
        fun createRoute(contactId: String) = "contact/$contactId"
    }
    object Settings : Screen("settings")
    object ShareContact : Screen("share_contact")
    object ScanContact : Screen("scan_contact")
    object MeshNetwork : Screen("mesh_network")
    object WifiDirect : Screen("wifi_direct")
    object P2PTor : Screen("p2p_tor")
    object Guide : Screen("guide")
}
