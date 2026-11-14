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
import androidx.fragment.app.FragmentActivity
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.ui.theme.JarvisBg
import com.jarvis.agent.ui.theme.JarvisSurface2
import com.jarvis.agent.ui.theme.JarvisTheme

/** FragmentActivity (not ComponentActivity) so BiometricPrompt can be used. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = JarvisApp.graph.prefs
        // Debug builds only: the emulator smoke test opens specific screens directly.
        var startTab = 0
        if (com.jarvis.agent.BuildConfig.DEBUG) {
            if (intent.getBooleanExtra("demo_skip_onboarding", false)) prefs.setupDone = true
            if (intent.hasExtra("demo_urdu")) UiLang.set(intent.getBooleanExtra("demo_urdu", false))
            startTab = intent.getIntExtra("demo_tab", 0)
        }
        setContent {
            JarvisTheme {
                var onboarded by rememberSaveable { mutableStateOf(prefs.setupDone) }
                var tab by rememberSaveable { mutableStateOf(startTab) }
                if (!onboarded) {
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
                            NavigationBar(containerColor = JarvisBg.copy(alpha = 0.96f)) {
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
                            when (tab) {
                                0 -> HomeScreen(openSettings = { tab = 2 })
                                1 -> ActivityScreen()
                                else -> SettingsScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}
