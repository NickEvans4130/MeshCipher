package com.meshcipher.presentation.navigation

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Conversations : Screen("conversations")
    object Chat : Screen("chat/{conversationId}") {
        fun createRoute(conversationId: String) = "chat/$conversationId"
    }
    object Contacts : Screen("contacts")
    object AddContact : Screen("add_contact?userId={userId}&onionAddress={onionAddress}&publicKey={publicKey}&deviceId={deviceId}") {
        fun createRoute(
            userId: String? = null,
            onionAddress: String? = null,
            publicKey: String? = null,
            deviceId: String? = null
        ): String {
            val params = mutableListOf<String>()
            if (userId != null) params.add("userId=$userId")
            if (onionAddress != null) params.add("onionAddress=$onionAddress")
            if (publicKey != null) params.add("publicKey=$publicKey")
            if (deviceId != null) params.add("deviceId=$deviceId")
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
    object VerifySafetyNumber : Screen("verify/{contactId}") {
        fun createRoute(contactId: String) = "verify/$contactId"
    }
    object LinkedDevices : Screen("linked_devices")
    object ScanDeviceQr : Screen("scan_device_qr")
    object DeviceLinkApproval : Screen("device_link_approval/{requestJson}") {
        fun createRoute(requestJson: String): String {
            val encoded = android.util.Base64.encodeToString(
                requestJson.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            return "device_link_approval/$encoded"
        }
    }
}
