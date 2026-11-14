package com.jarvis.agent.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.brain.Provider
import com.jarvis.agent.core.AgentPhase
import com.jarvis.agent.service.JarvisService
import com.jarvis.agent.ui.components.GlassCard
import com.jarvis.agent.ui.components.VoiceOrb
import com.jarvis.agent.ui.setup.PermissionCatalog
import com.jarvis.agent.ui.setup.PermissionList
import com.jarvis.agent.ui.theme.JarvisBorder
import com.jarvis.agent.ui.theme.JarvisMuted
import com.jarvis.agent.ui.theme.JarvisOk
import com.jarvis.agent.ui.theme.JarvisPrimary
import kotlinx.coroutines.launch

/** First run: four short steps — language, AI key (tested), essential permissions, ready. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp, bottom = 20.dp)) {
            repeat(4) { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (i <= step) JarvisPrimary else JarvisBorder))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (step) {
                0 -> WelcomeStep()
                1 -> BrainStep()
                2 -> PermissionsStep()
                else -> ReadyStep()
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { step-- }) { Text(tr("Back", "Wapas")) }
            Spacer(Modifier.weight(1f))
            Button(onClick = { if (step < 3) step++ else onDone() }) {
                Text(if (step < 3) tr("Next", "Aage") else tr("Start Jarvis", "Jarvis shuru karein"))
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { VoiceOrb(AgentPhase.OFF, size = 110.dp) {} }
    Text(tr("Meet Jarvis", "Jarvis se miliye"), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
        tr(
            "Your voice assistant that actually does things: messages, calls, calendar, reminders, apps — in Urdu or English. Risky actions always ask you first.",
            "Aap ka voice assistant jo waqai kaam karta hai: messages, calls, calendar, reminders, apps — Urdu ya English mein. Khatre wale kaam hamesha pehle poochta hai.",
        ),
        color = JarvisMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
    )
    GlassCard {
        Text(tr("App language", "App ki zabaan"), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !UiLang.urdu.value, onClick = { UiLang.set(false) }, label = { Text("English") })
            FilterChip(selected = UiLang.urdu.value, onClick = { UiLang.set(true) }, label = { Text("Roman Urdu") })
        }
    }
}

@Composable
private fun BrainStep() {
    val context = LocalContext.current
    val graph = JarvisApp.graph
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    Text(tr("Connect the AI brain", "AI dimaagh jorein"), style = MaterialTheme.typography.headlineSmall)
    Text(
        tr(
            "Jarvis thinks with Google Gemini, free. It takes 1 minute: open the link, sign in with Google, tap \"Create API key\", copy it, paste it below.",
            "Jarvis Google Gemini se sochta hai, muft. 1 minute lagta hai: link kholein, Google se sign in, \"Create API key\" dabayein, copy kar ke neeche paste karein.",
        ),
        color = JarvisMuted,
    )
    OutlinedButton(onClick = {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }, modifier = Modifier.fillMaxWidth()) { Text(tr("Open Google AI Studio", "Google AI Studio kholein")) }
    SavedField(
        tr("Gemini API key", "Gemini API key"),
        isSet = graph.secure.has(Provider.GEMINI.secret), secret = true, key = "ob_key_$refresh",
    ) {
        graph.secure.put(Provider.GEMINI.secret, it)
        graph.prefs.provider = Provider.GEMINI
        refresh++
        result = "…"
        scope.launch { result = graph.testAi() }
    }
    result?.let {
        Text(
            if (it.startsWith("✓")) tr("✓ Connected! Jarvis can think now.", "✓ Jur gaya! Jarvis ab soch sakta hai.") else it,
            color = if (it.startsWith("✓")) JarvisOk else MaterialTheme.colorScheme.error,
        )
    }
    Text(
        tr("Tip: never share this key with anyone or paste it in chats.", "Mashwara: yeh key kisi ko na dein, na chat mein paste karein."),
        color = JarvisMuted, style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun PermissionsStep() {
    Text(tr("Give Jarvis hands and ears", "Jarvis ko haath aur kaan dein"), style = MaterialTheme.typography.headlineSmall)
    Text(
        tr(
            "These are needed to listen and to use apps for you. You can add more abilities later in Settings.",
            "Yeh sunne aur aap ke liye apps chalane ke liye zaroori hain. Mazeed salahiyatein baad mein Settings se.",
        ),
        color = JarvisMuted,
    )
    PermissionList(PermissionCatalog.items().filter { it.required })
}

@Composable
private fun ReadyStep() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { VoiceOrb(AgentPhase.LISTENING, size = 96.dp) {} }
    Text(tr("You're ready", "Aap tayyar hain"), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    GlassCard {
        Text(tr("Tap the orb or say \"Hey Jarvis\", then try:", "Orb tap karein ya \"Hey Jarvis\" kahein, phir aazmaayein:"), style = MaterialTheme.typography.titleMedium)
        listOf(
            tr("\"Give me my briefing\"", "\"Mujhe briefing do\""),
            tr("\"What needs my attention?\"", "\"Zaroori kya hai?\""),
            tr("\"Remind me in 20 minutes to drink water\"", "\"20 minute baad paani peene ka yaad dilana\""),
        ).forEach { Text("•  $it", color = JarvisMuted) }
    }
    Text(
        tr("\"Hey Jarvis\" needs a free Picovoice key — add it any time in Settings → Voice.", "\"Hey Jarvis\" ke liye free Picovoice key chahiye — Settings → Awaaz mein kabhi bhi daalein."),
        color = JarvisMuted, style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.size(1.dp))
}

/** Marks setup done and starts the always-on service. */
fun finishOnboarding(context: android.content.Context) {
    JarvisApp.graph.prefs.setupDone = true
    runCatching { JarvisService.start(context) }
}
