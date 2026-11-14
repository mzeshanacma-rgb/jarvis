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

        SectionTitle(tr("Calls & modes", "Calls aur modes"))
        CallsAndModes()

        SectionTitle(tr("Privacy", "Raazdari"))
        PrivacySettings()

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
        Text("Jarvis 0.8", color = JarvisMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 24.dp))
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
        SmartHomeSettings()
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

@Composable
private fun CallsAndModes() {
    val prefs = JarvisApp.graph.prefs
    var announce by remember { mutableStateOf(prefs.announceCalls) }
    var busy by remember { mutableStateOf(prefs.busyMode) }
    var busySms by remember { mutableStateOf(prefs.busyAutoSms) }
    var unknown by remember { mutableStateOf(prefs.silenceUnknownCallers) }
    GlassCard {
        Toggle(tr("Say who is calling", "Batayein kaun call kar raha hai"), announce) { announce = it; prefs.announceCalls = it }
        Toggle(tr("Busy mode: silence calls (meetings)", "Busy mode: calls khamosh (meetings)"), busy) { busy = it; prefs.busyMode = it }
        Toggle(tr("…and text known callers \"will call back\"", "…aur contacts ko \"baad mein call karunga\" SMS"), busySms) { busySms = it; prefs.busyAutoSms = it }
        Toggle(tr("Silence unknown numbers", "Unknown numbers khamosh"), unknown) { unknown = it; prefs.silenceUnknownCallers = it }
        Text(
            tr("Needs the \"Call assistant\" ability (Android 10+). Android does not let apps listen to or answer the call itself.",
                "\"Call assistant\" salahiyat chahiye (Android 10+). Android apps ko call ki awaaz sunne ya uthane nahi deta."),
            color = JarvisMuted, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrivacySettings() {
    val context = LocalContext.current
    val g = JarvisApp.graph
    val prefs = g.prefs
    val scope = rememberCoroutineScope()
    var incognito by remember { mutableStateOf(prefs.incognito) }
    var lock by remember { mutableStateOf(prefs.appLock) }
    var privateApps by remember { mutableStateOf(prefs.privateApps) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    GlassCard {
        Toggle(tr("Incognito (don't keep history or memories)", "Incognito (history aur yaaddasht mehfooz na ho)"), incognito) { incognito = it; prefs.incognito = it }
        Toggle(tr("Lock Jarvis with fingerprint", "Jarvis ko fingerprint se lock karein"), lock) { lock = it; prefs.appLock = it }
        Text(tr("Private apps — Jarvis can never read or use them", "Private apps — Jarvis inhein kabhi parh ya chala nahi sakta"), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            privateApps.sorted().forEach { pkg ->
                FilterChip(selected = true, onClick = {
                    privateApps = privateApps - pkg; prefs.privateApps = privateApps
                }, label = { Text(com.jarvis.agent.tools.impl.Apps.label(context, pkg) + "  ✕") })
            }
        }
        SavedField(tr("Add app by name", "App ka naam likh kar add karein"), key = "private_add_${privateApps.size}") { name ->
            com.jarvis.agent.tools.impl.Apps.find(context, name)?.let { privateApps = privateApps + it.packageName; prefs.privateApps = privateApps }
        }
        TextButton(onClick = {
            val installed = com.jarvis.agent.tools.impl.Apps.launchable(context).map { it.packageName }.toSet()
            val add = com.jarvis.agent.core.Privacy.bankingPreset.filter { it in installed }
            privateApps = privateApps + add; prefs.privateApps = privateApps
        }) { Text(tr("Add my banking & wallet apps", "Mere banking aur wallet apps add karein")) }
        Text(
            tr("Free AI plans may use your data (including screenshots) to improve their models. For sensitive work use Claude.",
                "Free AI plans aap ka data (screenshots samet) behtari ke liye istemal kar sakte hain. Hassas kaam ke liye Claude."),
            color = JarvisMuted, style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = {
                if (!confirmDelete) confirmDelete = true
                else scope.launch { com.jarvis.agent.core.Privacy.deleteAllData(); confirmDelete = false; deleted = true }
            },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    deleted -> tr("All data deleted ✓", "Sab data delete ✓")
                    confirmDelete -> tr("Tap again to delete everything", "Sab delete karne ke liye dobara tap")
                    else -> tr("Delete all my data", "Mera sara data delete karein")
                },
            )
        }
    }
}

@Composable
private fun SmartHomeSettings() {
    val g = JarvisApp.graph
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<String?>(null) }
    GlassCard {
        Text(tr("Smart home (Home Assistant)", "Smart home (Home Assistant)"), style = MaterialTheme.typography.titleMedium)
        Text(
            tr("Lights, fans, AC, plugs, curtains, locks. Without Home Assistant, Jarvis can still use your vendor app (Smart Life, Google Home) by screen control.",
                "Batti, pankha, AC, plugs, parday, locks. Home Assistant ke baghair bhi Jarvis aap ki app (Smart Life, Google Home) screen se chala sakta hai."),
            color = JarvisMuted, style = MaterialTheme.typography.bodySmall,
        )
        SavedField(tr("Home Assistant URL", "Home Assistant URL"), initial = g.prefs.homeAssistantUrl, key = "ha_url") { g.prefs.homeAssistantUrl = it }
        SavedField(tr("Long-lived access token", "Long-lived access token"), isSet = g.secure.has(Secret.HOME_ASSISTANT_TOKEN), secret = true, key = "ha_tok_$refresh") {
            g.secure.put(Secret.HOME_ASSISTANT_TOKEN, it); refresh++
        }
        TextButton(onClick = {
            result = "…"
            scope.launch { result = runCatching { "✓ " + com.jarvis.agent.home.HomeAssistant.states().size + " devices" }.getOrElse { "✗ " + it.message } }
        }) { Text(tr("Test", "Test")) }
        result?.let { Text(it, color = if (it.startsWith("✓")) JarvisOk else MaterialTheme.colorScheme.error) }
    }
}
