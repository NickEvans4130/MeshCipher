package com.meshcipher.presentation.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshcipher.presentation.components.TacticalConversationCard
import com.meshcipher.presentation.components.TacticalEmptyState
import com.meshcipher.presentation.components.TacticalFAB
import com.meshcipher.presentation.components.TacticalHeader
import com.meshcipher.presentation.theme.TacticalBackground

@Composable
fun ConversationsScreen(
    onConversationClick: (String) -> Unit,
    onContactsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel()
) {
    val conversations by viewModel.conversations.collectAsState()
    val connectionMode by viewModel.connectionMode.collectAsState()

    Scaffold(
        floatingActionButton = {
            TacticalFAB(onClick = onContactsClick)
        },
        containerColor = TacticalBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(TacticalBackground)
        ) {
            TacticalHeader(
                connectionMode = connectionMode,
                onSettingsClick = onSettingsClick
            )

            if (conversations.isEmpty()) {
                TacticalEmptyState(
                    icon = Icons.Default.Forum,
                    title = "No Conversations",
                    subtitle = "Tap + to start a secure conversation"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(conversations) { conversation ->
                        TacticalConversationCard(
                            conversation = conversation,
                            connectionMode = connectionMode,
                            onClick = { onConversationClick(conversation.id) }
                        )
                    }
                }
            }
        }
    }
}
