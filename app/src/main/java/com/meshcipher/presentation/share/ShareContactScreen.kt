package com.meshcipher.presentation.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshcipher.presentation.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareContactScreen(
    onBackClick: () -> Unit,
    viewModel: ShareContactViewModel = hiltViewModel()
) {
    val contactCard by viewModel.contactCard.collectAsState()
    val qrBitmap by viewModel.qrBitmap.collectAsState()

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Share Contact",
                        color = TextPrimary,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TacticalBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            qrBitmap?.let { bitmap ->
                Box(
                    modifier = Modifier
                        .border(2.dp, TextPrimary, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Contact QR Code",
                        modifier = Modifier.size(280.dp)
                    )
                }
            } ?: CircularProgressIndicator(color = SecureGreen)

            Spacer(modifier = Modifier.height(24.dp))

            contactCard?.let { card ->
                Text(
                    text = "Verification Code:",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = card.verificationCode,
                    style = MaterialTheme.typography.headlineLarge,
                    fontFamily = RobotoMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = SecureGreen
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Have your contact scan this code to add you",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}
