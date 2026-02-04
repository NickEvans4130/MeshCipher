package com.meshcipher.presentation.navigation

import androidx.compose.runtime.Composable
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
import com.meshcipher.presentation.mesh.MeshNetworkScreen
import com.meshcipher.presentation.scan.ScanContactScreen
import com.meshcipher.presentation.settings.SettingsScreen
import com.meshcipher.presentation.share.ShareContactScreen
import com.meshcipher.presentation.wifidirect.WifiDirectScreen

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
                onBackClick = { navController.popBackStack() }
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
                }
            )
        ) {
            AddContactScreen(
                onBackClick = { navController.popBackStack() },
                onContactAdded = {
                    navController.popBackStack(Screen.Settings.route, false)
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
                }
            )
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
                    // Navigate to AddContact with pre-filled userId
                    navController.navigate(Screen.AddContact.createRoute(contactCard.userId)) {
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
    }
}
