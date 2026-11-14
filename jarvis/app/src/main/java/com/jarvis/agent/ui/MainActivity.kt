package com.jarvis.agent.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.ui.theme.JarvisSurface2
import com.jarvis.agent.ui.theme.JarvisTheme

/** FragmentActivity (not ComponentActivity) so BiometricPrompt can be used. */
class MainActivity : FragmentActivity() {
    /** App lock: unlocked until the app goes to the background. */
    private val unlocked = mutableStateOf(false)

    override fun onStop() {
        super.onStop()
        unlocked.value = false
    }

    private fun unlock() {
        val auth = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (androidx.biometric.BiometricManager.from(this).canAuthenticate(auth) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            unlocked.value = true // no screen lock on this phone: nothing to check against
            return
        }
        androidx.biometric.BiometricPrompt(
            this, androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    unlocked.value = true
                }
            },
        ).authenticate(
            androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle("Jarvis").setSubtitle("Unlock").setAllowedAuthenticators(auth).build(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light page: dark status/navigation bar icons regardless of the phone's dark mode.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE),
        )
        val prefs = JarvisApp.graph.prefs
        // Debug builds only: the emulator smoke test opens specific screens directly.
        var startTab = 0
        var startTool = ToolPage.NONE
        if (com.jarvis.agent.BuildConfig.DEBUG) {
            if (intent.getBooleanExtra("demo_skip_onboarding", false)) prefs.setupDone = true
            if (intent.hasExtra("demo_urdu")) UiLang.set(intent.getBooleanExtra("demo_urdu", false))
            startTab = intent.getIntExtra("demo_tab", 0)
            startTool = runCatching { ToolPage.valueOf(intent.getStringExtra("demo_tool") ?: "NONE") }.getOrDefault(ToolPage.NONE)
        }
        setContent {
            JarvisTheme {
                var onboarded by rememberSaveable { mutableStateOf(prefs.setupDone) }
                var tab by rememberSaveable { mutableStateOf(startTab) }
                var tool by rememberSaveable { mutableStateOf(startTool) }
                if (prefs.appLock && !unlocked.value) {
                    LockedScreen { unlock() }
                } else if (!onboarded) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        OnboardingScreen(onDone = {
                            finishOnboarding(this@MainActivity)
                            onboarded = true
                        })
                    }
                } else {
                    Scaffold(
                        containerColor = Color.Transparent,
                        bottomBar = {
                            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                                listOf(
                                    Triple(Icons.Filled.Home, tr("Home", "Home"), 0),
                                    Triple(Icons.Filled.Timeline, tr("Activity", "Sargarmi"), 1),
                                    Triple(Icons.Filled.Settings, tr("Settings", "Settings"), 2),
                                ).forEach { (icon, label, i) ->
                                    NavigationBarItem(
                                        selected = tab == i,
                                        onClick = { tab = i },
                                        icon = { Icon(icon, contentDescription = label) },
                                        label = { Text(label) },
                                        colors = NavigationBarItemDefaults.colors(indicatorColor = JarvisSurface2),
                                    )
                                }
                            }
                        },
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) {
                            val back = { tool = ToolPage.NONE }
                            when {
                                tool == ToolPage.MEETING -> MeetingScreen(back)
                                tool == ToolPage.TRANSLATE -> TranslateScreen(back)
                                tool == ToolPage.EXPENSES -> ExpensesScreen(back)
                                tool == ToolPage.DRIVING -> DrivingScreen(back)
                                tab == 0 -> HomeScreen(openSettings = { tab = 2 }, openTool = { tool = it })
                                tab == 1 -> ActivityScreen()
                                else -> SettingsScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun LockedScreen(onUnlock: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().safeDrawingPadding(),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        com.jarvis.agent.ui.components.VoiceOrb(com.jarvis.agent.core.AgentPhase.OFF, size = 96.dp) { onUnlock() }
        Text(tr("Jarvis is locked", "Jarvis lock hai"), style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        androidx.compose.material3.Button(onClick = onUnlock, modifier = Modifier.padding(top = 16.dp)) { Text(tr("Unlock", "Kholein")) }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { onUnlock() }
}
