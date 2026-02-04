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
import com.meshcipher.presentation.contacts.ContactsScreen
import com.meshcipher.presentation.conversations.ConversationsScreen
import com.meshcipher.presentation.mesh.MeshNetworkScreen
import com.meshcipher.presentation.scan.ScanContactScreen
import com.meshcipher.presentation.settings.SettingsScreen
import com.meshcipher.presentation.share.ShareContactScreen

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
                onAddContactClick = {
                    navController.navigate(Screen.AddContact.route)
                }
            )
        }

        composable(Screen.AddContact.route) {
            AddContactScreen(
                onBackClick = { navController.popBackStack() },
                onContactAdded = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onMeshNetworkClick = {
                    navController.navigate(Screen.MeshNetwork.route)
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
                    // Contact scanned, pop back
                    navController.popBackStack()
                },
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
