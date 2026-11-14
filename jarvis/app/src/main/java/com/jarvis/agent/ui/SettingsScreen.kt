package com.jarvis.agent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.brain.Provider
import com.jarvis.agent.core.ListenLanguage
import com.jarvis.agent.core.SecureStore.Secret
import com.jarvis.agent.core.SttEngine
import com.jarvis.agent.core.TtsEngine
import com.jarvis.agent.service.JarvisService
import com.jarvis.agent.ui.components.GlassCard
import com.jarvis.agent.ui.components.SectionTitle
import com.jarvis.agent.ui.setup.PermissionCatalog
import com.jarvis.agent.ui.setup.PermissionList
import com.jarvis.agent.ui.theme.JarvisMuted
import com.jarvis.agent.ui.theme.JarvisOk
import kotlinx.coroutines.launch

private enum class SettingsPage { MAIN, ABILITIES, MEMORY, ADVANCED }

/** Settings in plain sections. Every change saves immediately; there is no Save button to forget. */
@Composable
fun SettingsScreen() {
    var page by remember { mutableStateOf(SettingsPage.MAIN) }
    when (page) {
        SettingsPage.MAIN -> MainSettings { page = it }
        SettingsPage.ABILITIES -> SubPage(tr("Abilities", "Salahiyatein"), onBack = { page = SettingsPage.MAIN }) {
            Text(
                tr("Each ability lets Jarvis do more for you. Turn on only what you need.", "Har salahiyat Jarvis ko mazeed kaam karne deti hai. Sirf zaroorat wali on karein."),
                color = JarvisMuted, style = MaterialTheme.typography.bodySmall,
            )
            SectionTitle(tr("Essential", "Zaroori"))
            PermissionList(PermissionCatalog.items().filter { it.required })
            SectionTitle(tr("Optional", "Ikhtiyari"))
            PermissionList(PermissionCatalog.items().filter { !it.required })
        }
        SettingsPage.MEMORY -> Column(Modifier.fillMaxSize()) {
            BackRow(tr("Memory & routines", "Yaaddasht aur routines")) { page = SettingsPage.MAIN }
            MemoryScreen()
        }
        SettingsPage.ADVANCED -> SubPage(tr("Advanced", "Advanced"), onBack = { page = SettingsPage.MAIN }) { AdvancedSettings() }
    }
}

@Composable
private fun BackRow(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BackRow(title, onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
    }
}

/** Wake word / bubble changes apply when the service starts, so restart it if it is running. */
private fun restartService(context: Context) {
    if (JarvisService.instance != null) {
        JarvisService.stop(context)
        JarvisService.start(context)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainSettings(open: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val graph = JarvisApp.graph
    val prefs = graph.prefs
    val secure = graph.secure
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(prefs.provider) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var wake by remember { mutableStateOf(prefs.wakeWordEnabled) }
    var bubble by remember { mutableStateOf(prefs.overlayEnabled) }
    var boot by remember { mutableStateOf(prefs.startOnBoot) }
    var listen by remember { mutableStateOf(prefs.listenLanguage) }
    var tts by remember { mutableStateOf(prefs.ttsEngine) }
    var refresh by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text(tr("Settings", "Settings"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))

        SectionTitle(tr("Language", "Zabaan"))
        GlassCard {
            Text(tr("App language", "App ki zabaan"), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !UiLang.urdu.value, onClick = { UiLang.set(false) }, label = { Text("English") })
                FilterChip(selected = UiLang.urdu.value, onClick = { UiLang.set(true) }, label = { Text("Roman Urdu") })
            }
            Text(tr("I'll speak", "Main bolunga"), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ListenLanguage.AUTO to tr("Mixed", "Mila jula"),
                    ListenLanguage.URDU to "اردو",
                    ListenLanguage.ENGLISH to "English",
                ).forEach { (l, label) ->
                    FilterChip(selected = listen == l, onClick = { listen = l; prefs.listenLanguage = l }, label = { Text(label) })
                }
            }
        }

        SectionTitle(tr("AI brain", "AI dimaagh"))
        GlassCard {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Provider.entries.forEach { p ->
                    FilterChip(
                        selected = provider == p,
                        onClick = { provider = p; prefs.provider = p; testResult = null },
                        label = { Text(p.label) },
                    )
                }
            }
            Text(provider.keyHelp, color = JarvisMuted, style = MaterialTheme.typography.bodySmall)
            if (provider == Provider.GEMINI) {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(tr("Get a free Gemini key →", "Free Gemini key hasil karein →")) }
            }
            SavedField(
                label = tr("API key", "API key"),
                isSet = secure.has(provider.secret), secret = true, key = "key_${provider.name}_$refresh",
            ) { secure.put(provider.secret, it); refresh++; testResult = null }
            SavedField(label = tr("Model", "Model"), initial = prefs.modelFor(provider), key = "model_${provider.name}") {
                prefs.setModelFor(provider, it)
            }
            Button(
                onClick = {
                    testing = true
                    testResult = "…"
                    scope.launch { testResult = graph.testAi(); testing = false }
                },
                enabled = !testing, modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("Test connection", "Connection test karein")) }
            testResult?.let { Text(it, color = if (it.startsWith("✓")) JarvisOk else MaterialTheme.colorScheme.error) }
        }

        SectionTitle(tr("Voice", "Awaaz"))
        GlassCard {
            Toggle(tr("Wake word \"Hey Jarvis\"", "Wake word \"Hey Jarvis\""), wake) { wake = it; prefs.wakeWordEnabled = it; restartService(context) }
            if (wake) {
                Text(tr("Needs a free Picovoice key (console.picovoice.ai).", "Free Picovoice key chahiye (console.picovoice.ai)."), color = JarvisMuted, style = MaterialTheme.typography.bodySmall)
                SavedField("Picovoice AccessKey", isSet = secure.has(Secret.PICOVOICE_ACCESS_KEY), secret = true, key = "pv_$refresh") {
                    secure.put(Secret.PICOVOICE_ACCESS_KEY, it); refresh++; restartService(context)
                }
            }
            Toggle(tr("Floating mic button", "Floating mic button"), bubble) { bubble = it; prefs.overlayEnabled = it; restartService(context) }
            Toggle(tr("Start when phone turns on", "Phone on hote hi shuru"), boot) { boot = it; prefs.startOnBoot = it }
            Text(tr("Jarvis's voice", "Jarvis ki awaaz"), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tts == TtsEngine.ANDROID, onClick = { tts = TtsEngine.ANDROID; prefs.ttsEngine = tts }, label = { Text(tr("Phone (free)", "Phone (free)")) })
                FilterChip(selected = tts == TtsEngine.ELEVENLABS, onClick = { tts = TtsEngine.ELEVENLABS; prefs.ttsEngine = tts }, label = { Text("ElevenLabs") })
            }
            if (tts == TtsEngine.ELEVENLABS) {
                SavedField("ElevenLabs API key", isSet = secure.has(Secret.ELEVENLABS_API_KEY), secret = true, key = "el_$refresh") { secure.put(Secret.ELEVENLABS_API_KEY, it); refresh++ }
                SavedField("Voice ID", initial = prefs.elevenLabsVoiceId, key = "voice") { prefs.elevenLabsVoiceId = it }
            }
        }

        SectionTitle(tr("More", "Mazeed"))
        NavRow(tr("Abilities (permissions)", "Salahiyatein (permissions)"), tr("What Jarvis is allowed to do", "Jarvis kya kya kar sakta hai")) { open(SettingsPage.ABILITIES) }
        NavRow(tr("Memory & routines", "Yaaddasht aur routines"), tr("What Jarvis remembers, scheduled tasks", "Jarvis ko kya yaad hai, scheduled kaam")) { open(SettingsPage.MEMORY) }
        NavRow(tr("Advanced", "Advanced"), tr("Limits, Claude proxy, speech engine", "Hadood, Claude proxy, speech engine")) { open(SettingsPage.ADVANCED) }

        SectionTitle(tr("Safety", "Hifazat"))
        GlassCard {
            Text(
                tr(
                    "• Sending, calling and posting always ask you first.\n• Money, deleting and security settings need your fingerprint.\n• Jarvis never types passwords, PINs, OTPs or card numbers.\n• Stop anything: say \"stop\", tap the orb, press volume-down 3 times, or use STOP in the notification.",
                    "• Bhejna, call aur post hamesha pehle poochte hain.\n• Paise, delete aur security settings ke liye fingerprint zaroori.\n• Jarvis kabhi password, PIN, OTP ya card number nahi likhta.\n• Sab rokne ke liye: \"ruko\" kahein, orb tap karein, volume-down 3 dafa, ya notification ka STOP.",
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text("Jarvis 0.7", color = JarvisMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 24.dp))
    }
}

@Composable
private fun AdvancedSettings() {
    val prefs = JarvisApp.graph.prefs
    val secure = JarvisApp.graph.secure
    var stt by remember { mutableStateOf(prefs.sttEngine) }
    var effort by remember { mutableStateOf(prefs.effort) }
    var refresh by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassCard {
            Text(tr("Limits", "Hadood"), style = MaterialTheme.typography.titleMedium)
            SavedField(tr("Max steps per task (1–25)", "Har kaam ke max qadam (1–25)"), initial = prefs.maxSteps.toString(), key = "steps") { v -> v.toIntOrNull()?.let { prefs.maxSteps = it } }
            SavedField(tr("AI requests per minute", "AI requests fi minute"), initial = prefs.maxRequestsPerMinute.toString(), key = "rpm") { v -> v.toIntOrNull()?.let { prefs.maxRequestsPerMinute = it } }
            SavedField(tr("Task time limit (seconds)", "Kaam ki waqt had (seconds)"), initial = prefs.taskTimeoutSec.toString(), key = "timeout") { v -> v.toIntOrNull()?.let { prefs.taskTimeoutSec = it } }
        }
        GlassCard {
            Text(tr("Speech recognition", "Awaaz pehchanna"), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = stt == SttEngine.ANDROID, onClick = { stt = SttEngine.ANDROID; prefs.sttEngine = stt }, label = { Text(tr("Phone (free)", "Phone (free)")) })
                FilterChip(selected = stt == SttEngine.WHISPER, onClick = { stt = SttEngine.WHISPER; prefs.sttEngine = stt }, label = { Text("Whisper") })
            }
            if (stt == SttEngine.WHISPER) {
                SavedField("OpenAI key (Whisper)", isSet = secure.has(Secret.OPENAI_API_KEY), secret = true, key = "oa_$refresh") { secure.put(Secret.OPENAI_API_KEY, it); refresh++ }
            }
        }
        GlassCard {
            Text(tr("Claude (when selected)", "Claude (jab chuna ho)"), style = MaterialTheme.typography.titleMedium)
            SavedField(tr("API base URL or your proxy", "API base URL ya apna proxy"), initial = prefs.apiBaseUrl, key = "base") { prefs.apiBaseUrl = it }
            SavedField("Proxy token", isSet = secure.has(Secret.PROXY_TOKEN), secret = true, key = "pt_$refresh") { secure.put(Secret.PROXY_TOKEN, it); refresh++ }
            Text(tr("Effort (lower = faster)", "Effort (kam = tez)"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("low", "medium", "high").forEach { e -> FilterChip(selected = effort == e, onClick = { effort = e; prefs.effort = e }, label = { Text(e) }) }
            }
        }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.padding(bottom = 8.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = JarvisMuted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** A text field with its own Save button. Secret fields never show the stored value; a ✓ says one is saved. */
@Composable
fun SavedField(
    label: String,
    initial: String = "",
    isSet: Boolean = false,
    secret: Boolean = false,
    key: String,
    onSave: (String) -> Unit,
) {
    var value by remember(key) { mutableStateOf(if (secret) "" else initial) }
    var saved by remember(key) { mutableStateOf(false) }
    var base by remember(key) { mutableStateOf(initial) }
    val dirty = if (secret) value.isNotBlank() else value != base
    OutlinedTextField(
        value = value,
        onValueChange = { value = it; saved = false },
        label = { Text(if (secret && isSet) "$label  ✓ ${tr("saved", "saved")}" else label) },
        placeholder = { if (secret && isSet) Text(tr("Paste a new one to replace", "Badalne ke liye nayi paste karein")) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            if (dirty) {
                TextButton(onClick = {
                    onSave(value.trim())
                    saved = true
                    if (secret) value = "" else { value = value.trim(); base = value }
                }) { Text(tr("Save", "Save")) }
            } else if (saved) {
                Text("✓", color = JarvisOk, modifier = Modifier.padding(end = 12.dp))
            }
        },
    )
}
