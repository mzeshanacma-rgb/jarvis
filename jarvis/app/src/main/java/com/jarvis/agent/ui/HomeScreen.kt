package com.jarvis.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.brain.Provider
import com.jarvis.agent.core.AgentPhase
import com.jarvis.agent.core.JarvisRuntime
import com.jarvis.agent.safety.ConfirmationBroker
import com.jarvis.agent.service.JarvisService
import com.jarvis.agent.ui.components.ChatBubble
import com.jarvis.agent.ui.components.GlassCard
import com.jarvis.agent.ui.components.StatusPill
import com.jarvis.agent.ui.components.VoiceOrb
import com.jarvis.agent.ui.components.isBusy
import com.jarvis.agent.ui.components.phaseColor
import com.jarvis.agent.ui.setup.PermissionCatalog
import com.jarvis.agent.ui.theme.JarvisBorder
import com.jarvis.agent.ui.theme.JarvisMuted
import com.jarvis.agent.ui.theme.JarvisWarn
import java.util.Calendar

@Composable
fun HomeScreen(openSettings: () -> Unit) {
    val context = LocalContext.current
    val graph = JarvisApp.graph
    val state by JarvisRuntime.state.collectAsStateWithLifecycle()
    val history by remember { graph.memoryDb.dao().conversationsFlow() }.collectAsState(initial = emptyList())
    var typed by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val busy = state.phase.isBusy()

    fun talk(prefix: String? = null) {
        if (!state.serviceRunning) JarvisService.start(context)
        JarvisService.pushToTalk(context, prefix)
    }

    fun ask(text: String) {
        if (!state.serviceRunning) JarvisService.start(context)
        JarvisService.runText(context, text)
    }

    fun stopNow() {
        graph.tasks.cancelAll()
        ConfirmationBroker.denyAll()
        graph.voice.stopSpeaking()
        JarvisRuntime.update { it.copy(currentAction = "") }
    }

    val chat = history.reversed()
    LaunchedEffect(chat.size, busy) { if (chat.isNotEmpty() || busy) listState.animateScrollToItem(maxOf(0, listState.layoutInfo.totalItemsCount - 1)) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(greeting(), color = JarvisMuted, style = MaterialTheme.typography.bodyMedium)
                Text("Jarvis", style = MaterialTheme.typography.headlineMedium)
            }
            StatusPill(phaseLabel(state.phase), phaseColor(state.phase))
        }

        // Setup problems, in plain words, with a way to fix them.
        val p = graph.prefs.provider
        val proxied = p == Provider.CLAUDE && !graph.prefs.usesDirectApi
        val problems = buildList {
            if (!proxied && !graph.secure.has(p.secret)) add(tr("Add your free AI key to start.", "Shuru karne ke liye apni free AI key daalein."))
            if (!PermissionCatalog.isAccessibilityEnabled(context)) add(tr("Turn on screen control so Jarvis can use apps for you.", "Screen control on karein taake Jarvis apps chala sake."))
        }
        if (problems.isNotEmpty()) {
            GlassCard(onClick = openSettings) {
                problems.forEach { Text("⚠  $it", color = JarvisWarn, style = MaterialTheme.typography.bodyMedium) }
                Text(tr("Tap to fix in Settings", "Settings mein theek karne ke liye tap karein"), color = JarvisMuted, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Conversation
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            if (chat.isEmpty() && !busy) {
                item { EmptyState() }
            }
            items(chat, key = { it.id }) { c ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChatBubble(c.userText, fromUser = true)
                    ChatBubble(c.reply, fromUser = false)
                }
            }
            if (busy && state.lastHeard.isNotBlank()) {
                item(key = "live") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChatBubble(state.lastHeard, fromUser = true)
                        val step = if (state.currentStep > 0) tr("Step ${state.currentStep}", "Qadam ${state.currentStep}") else null
                        ChatBubble(
                            state.currentAction.ifBlank { phaseLabel(state.phase) + "…" },
                            fromUser = false,
                            footnote = step,
                        )
                    }
                }
            }
        }

        // Quick actions
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            item { Quick(Icons.Filled.WbSunny, tr("Briefing", "Briefing")) { ask(QuickPrompts.briefing) } }
            item { Quick(Icons.Filled.NotificationsActive, tr("Needs attention", "Zaroori kya hai")) { ask(QuickPrompts.attention) } }
            item { Quick(Icons.Filled.EditNote, tr("Dictate message", "Message likhwayein")) { talk(QuickPrompts.dictatePrefix) } }
            item { Quick(Icons.Filled.Summarize, tr("Summarize screen", "Screen ka khulasa")) { ask(QuickPrompts.summarize) } }
            item { Quick(Icons.Filled.Alarm, tr("Reminder", "Reminder")) { talk(QuickPrompts.reminderPrefix) } }
        }

        // Input + orb
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text(tr("Type a request…", "Yahan likhein…")) },
                singleLine = true,
                shape = RoundedCornerShape(26.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = JarvisBorder),
                modifier = Modifier.weight(1f),
                trailingIcon = {
                    IconButton(onClick = {
                        val t = typed.trim()
                        if (t.isNotEmpty()) { ask(t); typed = "" }
                    }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = tr("Send", "Bhejein")) }
                },
            )
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                VoiceOrb(state.phase, size = 84.dp) { if (busy) stopNow() else talk() }
                Text(
                    if (busy) tr("Tap to stop", "Rokne ke liye tap karein") else tr("Tap or say \"Hey Jarvis\"", "Tap karein ya \"Hey Jarvis\" kahein"),
                    color = JarvisMuted, style = MaterialTheme.typography.bodySmall,
                )
                if (busy) TextButton(onClick = { com.jarvis.agent.core.KillSwitch.trigger("home emergency stop") }) {
                    Text(tr("Emergency stop (turns Jarvis off)", "Emergency stop (Jarvis band)"), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun Quick(icon: ImageVector, label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.padding(0.dp)) },
        shape = RoundedCornerShape(50),
        colors = AssistChipDefaults.assistChipColors(leadingIconContentColor = MaterialTheme.colorScheme.secondary),
        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = JarvisBorder),
    )
}

@Composable
private fun EmptyState() {
    GlassCard {
        Text(tr("Try saying", "Yeh keh kar dekhein"), fontWeight = FontWeight.SemiBold)
        listOf(
            tr("\"What's on my calendar today?\"", "\"Aaj mera schedule kya hai?\""),
            tr("\"Message Ali I'll be 10 minutes late\"", "\"Ali ko message karo main 10 minute late hoon\""),
            tr("\"Remind me at 5 to call the bank\"", "\"5 baje bank ko call karna yaad dilana\""),
            tr("\"Turn on Do Not Disturb until my meeting ends\"", "\"Meeting tak phone silent kar do\""),
        ).forEach { Text("•  $it", color = JarvisMuted, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..11 -> tr("Good morning", "Subah bakhair")
        in 12..16 -> tr("Good afternoon", "Assalam-o-alaikum")
        else -> tr("Good evening", "Shaam bakhair")
    }
}

@Composable
fun phaseLabel(p: AgentPhase) = when (p) {
    AgentPhase.OFF -> tr("Ready", "Tayyar")
    AgentPhase.WAITING_WAKE_WORD -> tr("Listening for \"Hey Jarvis\"", "\"Hey Jarvis\" ka intezar")
    AgentPhase.LISTENING -> tr("Listening", "Sun raha hoon")
    AgentPhase.THINKING -> tr("Thinking", "Soch raha hoon")
    AgentPhase.ACTING -> tr("Working", "Kaam kar raha hoon")
    AgentPhase.WAITING_CONFIRMATION -> tr("Needs your OK", "Aap ki manzoori chahiye")
    AgentPhase.SPEAKING -> tr("Speaking", "Bol raha hoon")
}
