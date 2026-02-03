package com.meshcipher.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.presentation.navigation.MeshCipherNavigation
import com.meshcipher.presentation.onboarding.OnboardingScreen
import com.meshcipher.presentation.theme.MeshCipherTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var identityManager: IdentityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MeshCipherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var hasIdentity by remember { mutableStateOf<Boolean?>(null) }

                    LaunchedEffect(Unit) {
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
                                }
                            )
                        }
                        true -> {
                            MeshCipherNavigation()
                        }
                    }
                }
            }
        }
    }
}
