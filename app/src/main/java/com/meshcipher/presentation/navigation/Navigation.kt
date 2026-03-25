package com.meshcipher.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meshcipher.presentation.chat.ChatScreen
import com.meshcipher.presentation.contacts.AddContactScreen
import com.meshcipher.presentation.contacts.ContactDetailScreen
import com.meshcipher.presentation.contacts.ContactsScreen
import com.meshcipher.presentation.conversations.ConversationsScreen
import com.meshcipher.presentation.linking.DeviceLinkApprovalScreen
import com.meshcipher.presentation.linking.LinkedDevicesScreen
import com.meshcipher.presentation.linking.QRScannerScreen
import com.meshcipher.presentation.mesh.MeshNetworkScreen
import com.meshcipher.presentation.scan.ScanContactScreen
import com.meshcipher.presentation.settings.BridgeSettingsScreen
import com.meshcipher.presentation.settings.SettingsScreen
import com.meshcipher.presentation.share.ShareContactScreen
import com.meshcipher.presentation.guide.GuideScreen
import com.meshcipher.presentation.p2ptor.P2PTorScreen
import com.meshcipher.presentation.verify.VerifySafetyNumberScreen
import com.meshcipher.presentation.wifidirect.WifiDirectScreen
import com.google.gson.Gson
import com.meshcipher.shared.domain.model.DeviceLinkRequest

@Composable
fun MeshCipherNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Conversations.route
    ) {
        composable(Screen.Conversations.route) {
            ConversationsScreen(
                onConversationClick = { conversationId ->
                    navController.navigate(Screen.Chat.createRoute(conversationId))
                },
                onContactsClick = {
                    navController.navigate(Screen.Contacts.route)
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType }
            )
        ) {
            ChatScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToVerify = { contactId ->
                    navController.navigate(Screen.VerifySafetyNumber.createRoute(contactId))
                }
            )
        }

        composable(Screen.Contacts.route) {
            ContactsScreen(
                onBackClick = { navController.popBackStack() },
                onConversationStart = { conversationId ->
                    navController.navigate(Screen.Chat.createRoute(conversationId)) {
                        popUpTo(Screen.Conversations.route)
                    }
                },
                onContactClick = { contactId ->
                    navController.navigate(Screen.ContactDetail.createRoute(contactId))
                },
                onAddContactClick = {
                    navController.navigate(Screen.AddContact.route)
                }
            )
        }

        composable(
            route = Screen.ContactDetail.route,
            arguments = listOf(
                navArgument("contactId") { type = NavType.StringType }
            )
        ) {
            ContactDetailScreen(
                onBackClick = { navController.popBackStack() },
                onStartConversation = { conversationId ->
                    navController.navigate(Screen.Chat.createRoute(conversationId)) {
                        popUpTo(Screen.Conversations.route)
                    }
                },
                onContactDeleted = {
                    navController.popBackStack()
                },
                onNavigateToVerify = { contactId ->
                    navController.navigate(Screen.VerifySafetyNumber.createRoute(contactId))
                }
            )
        }

        composable(
            route = Screen.AddContact.route,
            arguments = listOf(
                navArgument("userId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("onionAddress") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("publicKey") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("deviceId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            AddContactScreen(
                onBackClick = { navController.popBackStack() },
                onContactAdded = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onMeshNetworkClick = {
                    navController.navigate(Screen.MeshNetwork.route)
                },
                onShareContactClick = {
                    navController.navigate(Screen.ShareContact.route)
                },
                onScanContactClick = {
                    navController.navigate(Screen.ScanContact.route)
                },
                onWifiDirectClick = {
                    navController.navigate(Screen.WifiDirect.route)
                },
                onP2PTorClick = {
                    navController.navigate(Screen.P2PTor.route)
                },
                onGuideClick = {
                    navController.navigate(Screen.Guide.route)
                },
                onLinkedDevicesClick = {
                    navController.navigate(Screen.LinkedDevices.route)
                },
                onBridgeSettingsClick = {
                    navController.navigate(Screen.BridgeSettings.route)
                }
            )
        }

        composable(Screen.BridgeSettings.route) {
            BridgeSettingsScreen(onBackClick = { navController.popBackStack() })
        }

        composable(Screen.LinkedDevices.route) {
            LinkedDevicesScreen(
                onBackClick = { navController.popBackStack() },
                onScanQrClick = { navController.navigate(Screen.ScanDeviceQr.route) }
            )
        }

        composable(Screen.ScanDeviceQr.route) {
            QRScannerScreen(
                onBackClick = { navController.popBackStack() },
                onRequestScanned = { request ->
                    val requestJson = Gson().toJson(request)
                    navController.navigate(Screen.DeviceLinkApproval.createRoute(requestJson)) {
                        popUpTo(Screen.ScanDeviceQr.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.DeviceLinkApproval.route,
            arguments = listOf(
                navArgument("requestJson") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString("requestJson") ?: ""
            val requestJson = String(android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE))
            val request = runCatching {
                Gson().fromJson(requestJson, DeviceLinkRequest::class.java)
            }.getOrNull()

            if (request != null) {
                DeviceLinkApprovalScreen(
                    request = request,
                    onApproved = {
                        navController.navigate(Screen.LinkedDevices.route) {
                            popUpTo(Screen.Settings.route)
                        }
                    },
                    onDenied = { navController.popBackStack() },
                    onBackClick = { navController.popBackStack() }
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }

        composable(Screen.MeshNetwork.route) {
            MeshNetworkScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Screen.ShareContact.route) {
            ShareContactScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Screen.ScanContact.route) {
            ScanContactScreen(
                onContactScanned = { contactCard ->
                    navController.navigate(
                        Screen.AddContact.createRoute(
                            userId = contactCard.userId,
                            onionAddress = contactCard.onionAddress,
                            publicKey = android.util.Base64.encodeToString(
                                contactCard.publicKey, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
                            ),
                            deviceId = contactCard.deviceId
                        )
                    ) {
                        popUpTo(Screen.ScanContact.route) { inclusive = true }
                    }
                },
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Screen.WifiDirect.route) {
            WifiDirectScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Screen.P2PTor.route) {
            P2PTorScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Screen.Guide.route) {
            GuideScreen(
                onFinish = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.VerifySafetyNumber.route,
            arguments = listOf(
                navArgument("contactId") { type = NavType.StringType }
            )
        ) {
            VerifySafetyNumberScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
