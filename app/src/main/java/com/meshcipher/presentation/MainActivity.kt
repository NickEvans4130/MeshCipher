package com.meshcipher.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.presentation.guide.GuideScreen
import com.meshcipher.presentation.navigation.MeshCipherNavigation
import com.meshcipher.presentation.onboarding.OnboardingScreen
import com.meshcipher.presentation.theme.TacticalMeshCipherTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var identityManager: IdentityManager

    @Inject
    lateinit var appPreferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TacticalMeshCipherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var hasIdentity by remember { mutableStateOf<Boolean?>(null) }
                    var hasSeenGuide by remember { mutableStateOf(true) }
                    val scope = rememberCoroutineScope()

                    LaunchedEffect(Unit) {
                        hasSeenGuide = appPreferences.hasSeenGuide.first()
                        hasIdentity = identityManager.hasIdentity()
                    }

                    when (hasIdentity) {
                        null -> {
                            // Loading
                        }
                        false -> {
                            OnboardingScreen(
                                onComplete = {
                                    hasIdentity = true
                                    hasSeenGuide = false
                                }
                            )
                        }
                        true -> {
                            if (!hasSeenGuide) {
                                GuideScreen(
                                    onFinish = {
                                        hasSeenGuide = true
                                        scope.launch {
                                            appPreferences.setHasSeenGuide(true)
                                        }
                                    }
                                )
                            } else {
                                MeshCipherNavigation()
                            }
                        }
                    }
                }
            }
        }
    }
}
