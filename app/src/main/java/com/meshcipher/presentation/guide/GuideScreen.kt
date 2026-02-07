package com.meshcipher.presentation.guide

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshcipher.presentation.theme.*
import kotlinx.coroutines.launch

data class GuidePage(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val whenToUse: String?,
    val howTo: List<String>? = null
)

private val guidePages = listOf(
    GuidePage(
        icon = Icons.Default.Shield,
        title = "Welcome to MeshCipher",
        description = "Your messages are end-to-end encrypted using the Signal Protocol. No one can read them except you and the recipient.\n\nMeshCipher gives you multiple ways to send messages. Choose the mode that fits your situation.",
        whenToUse = null
    ),
    GuidePage(
        icon = Icons.Default.Cloud,
        title = "Direct Mode",
        description = "Messages route through a relay server for fast, reliable delivery. Works anywhere you have an internet connection.",
        whenToUse = "You want speed and reliability for everyday messaging."
    ),
    GuidePage(
        icon = Icons.Default.Security,
        title = "Tor Relay",
        description = "Messages are routed through the Tor network, hiding your IP address and location from the relay server. Delivery may be slightly slower.",
        whenToUse = "You need extra privacy and don't mind slower delivery.",
        howTo = listOf(
            "Install the Orbot app from the Play Store",
            "Open Orbot and tap Start to connect to Tor",
            "In MeshCipher Settings, select Tor Relay mode"
        )
    ),
    GuidePage(
        icon = Icons.Default.Bluetooth,
        title = "Bluetooth Mesh",
        description = "Send messages to nearby devices over Bluetooth. Messages can hop between multiple devices to reach recipients further away. No internet needed.",
        whenToUse = "You're near the recipient and want to communicate without any internet connection.",
        howTo = listOf(
            "Enable Bluetooth Mesh in Settings",
            "Grant Bluetooth and notification permissions when prompted",
            "Other nearby MeshCipher users will appear automatically"
        )
    ),
    GuidePage(
        icon = Icons.Default.Wifi,
        title = "WiFi Direct",
        description = "High-speed transfers directly between two devices over WiFi. Supports text, images, and files with up to 100m range. No internet or router needed.",
        whenToUse = "You need fast file or image transfers to a nearby device.",
        howTo = listOf(
            "Open Settings and tap WiFi Direct P2P",
            "Both devices must tap Refresh at the same time to discover each other",
            "Tap the discovered device to connect and start messaging"
        )
    ),
    GuidePage(
        icon = Icons.Default.Security,
        title = "P2P Tor",
        description = "Connect directly to other users through Tor hidden services. No relay server is involved \u2014 your device becomes its own anonymous endpoint on the internet.",
        whenToUse = "You want direct communication with maximum anonymity over the internet.",
        howTo = listOf(
            "Open Settings and tap P2P Tor Hidden Service",
            "Start the service to generate your .onion address",
            "Share your .onion address with contacts so they can reach you directly"
        )
    )
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GuideScreen(
    onFinish: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { guidePages.size })
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TacticalBackground)
    ) {
        // Skip button
        TextButton(
            onClick = onFinish,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Text(
                "Skip",
                color = TextSecondary,
                fontFamily = RobotoMonoFontFamily,
                fontSize = 14.sp
            )
        }

        // Page content
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            GuidePageContent(guidePages[page])
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Page indicators
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(guidePages.size) { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == pagerState.currentPage) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) SecureGreen
                                else TextTertiary
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Next / Get Started button
            Button(
                onClick = {
                    if (pagerState.currentPage < guidePages.size - 1) {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    } else {
                        onFinish()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SecureGreen,
                    contentColor = OnSecureGreen
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (pagerState.currentPage < guidePages.size - 1) "Next" else "Get Started",
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
private fun GuidePageContent(page: GuidePage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp)
            .padding(top = 80.dp, bottom = 160.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon
        Surface(
            modifier = Modifier.size(80.dp),
            shape = RoundedCornerShape(20.dp),
            color = SecureGreen.copy(alpha = 0.12f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    page.icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = SecureGreen
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Title
        Text(
            text = page.title,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Description
        Text(
            text = page.description,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        // When to use card
        if (page.whenToUse != null) {
            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "WHEN TO USE",
                        fontFamily = RobotoMonoFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        color = SecureGreen,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = page.whenToUse,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = TextPrimary
                    )
                }
            }
        }

        // How to steps
        if (page.howTo != null) {
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = TacticalElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "HOW TO SET UP",
                        fontFamily = RobotoMonoFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        color = SkyBlue,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    page.howTo.forEachIndexed { index, step ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "${index + 1}.",
                                fontFamily = RobotoMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = SkyBlue,
                                modifier = Modifier.width(24.dp)
                            )
                            Text(
                                text = step,
                                fontFamily = InterFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = TextPrimary
                            )
                        }
                        if (index < page.howTo.size - 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}
