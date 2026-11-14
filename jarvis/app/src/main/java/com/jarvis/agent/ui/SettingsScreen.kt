package com.jarvis.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.brain.Provider
import com.jarvis.agent.core.ListenLanguage
import com.jarvis.agent.core.SecureStore.Secret
import com.jarvis.agent.core.SttEngine
import com.jarvis.agent.core.TtsEngine

@Composable
fun SettingsScreen() {
    val graph = JarvisApp.graph
    val prefs = graph.prefs
    val secure = graph.secure
    var saved by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var provider by remember { mutableStateOf(prefs.provider) }
    val providerKeys = remember { mutableStateMapOf<Provider, String>() }
    val models = remember { mutableStateMapOf<Provider, String>().apply { Provider.entries.forEach { put(it, prefs.modelFor(it)) } } }
    var proxyToken by remember { mutableStateOf("") }
    var picovoice by remember { mutableStateOf("") }
    var eleven by remember { mutableStateOf("") }
    var openai by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf(prefs.apiBaseUrl) }
    var voiceId by remember { mutableStateOf(prefs.elevenLabsVoiceId) }
    var wake by remember { mutableStateOf(prefs.wakeWordEnabled) }
    var overlay by remember { mutableStateOf(prefs.overlayEnabled) }
    var boot by remember { mutableStateOf(prefs.startOnBoot) }
    var stt by remember { mutableStateOf(prefs.sttEngine) }
    var tts by remember { mutableStateOf(prefs.ttsEngine) }
    var lang by remember { mutableStateOf(prefs.listenLanguage) }
    var effort by remember { mutableStateOf(prefs.effort) }
    var maxSteps by remember { mutableStateOf(prefs.maxSteps.toString()) }
    var rpm by remember { mutableStateOf(prefs.maxRequestsPerMinute.toString()) }
    var taskTimeout by remember { mutableStateOf(prefs.taskTimeoutSec.toString()) }

    Column(
        Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Section("AI service (dimaagh)")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Provider.entries.forEach { p ->
                FilterChip(selected = provider == p, onClick = { provider = p }, label = { Text(if (p.free) p.name.lowercase() + " ✓free" else p.name.lowercase()) })
            }
        }
        Text(provider.label, fontWeight = FontWeight.SemiBold)
        Text(provider.keyHelp, style = MaterialTheme.typography.bodySmall)
        KeyField("${provider.label} API key", secure.has(provider.secret), providerKeys[provider].orEmpty()) { providerKeys[provider] = it }
        OutlinedTextField(
            models[provider].orEmpty(), { models[provider] = it },
            label = { Text("Model") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        if (provider.free) {
            Text(
                "Free plans ki rate limit kam hoti hai aur free tier par aap ka data provider training ke liye istemal kar sakta hai " +
                    "(screenshots samet). Hassas kaam ke liye baad mein Claude istemal karein.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Doosri keys (Android Keystore mein encrypted)")
        KeyField("Picovoice AccessKey (wake word)", secure.has(Secret.PICOVOICE_ACCESS_KEY), picovoice) { picovoice = it }
        KeyField("ElevenLabs API key (optional)", secure.has(Secret.ELEVENLABS_API_KEY), eleven) { eleven = it }
        KeyField("OpenAI key for Whisper STT (optional)", secure.has(Secret.OPENAI_API_KEY), openai) { openai = it }

        if (provider == Provider.CLAUDE) {
            Section("Claude (proxy aur effort)")
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("API base URL ya apna proxy") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            KeyField("Proxy token (sirf proxy ke liye)", secure.has(Secret.PROXY_TOKEN), proxyToken) { proxyToken = it }
            Text("Effort (kam = tez jawab)")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("low", "medium", "high").forEach { e ->
                    FilterChip(selected = effort == e, onClick = { effort = e }, label = { Text(e) })
                }
            }
        }

        Section("Hadood (limits)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(maxSteps, { maxSteps = it.filter(Char::isDigit) }, label = { Text("Max steps (≤25)") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(rpm, { rpm = it.filter(Char::isDigit) }, label = { Text("Requests/min") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(taskTimeout, { taskTimeout = it.filter(Char::isDigit) }, label = { Text("Task timeout s") }, modifier = Modifier.weight(1f), singleLine = true)
        }

        Section("Awaaz")
        Toggle("\"Hey Jarvis\" wake word", wake) { wake = it }
        Toggle("Floating mic button", overlay) { overlay = it }
        Toggle("Phone on hone par shuru karein", boot) { boot = it }
        Text("Sunne ki zabaan")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ListenLanguage.entries.forEach { l ->
                FilterChip(selected = lang == l, onClick = { lang = l }, label = { Text(l.name) })
            }
        }
        Text("Speech-to-text")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SttEngine.entries.forEach { e -> FilterChip(selected = stt == e, onClick = { stt = e }, label = { Text(e.name) }) }
        }
        Text("Text-to-speech")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TtsEngine.entries.forEach { e -> FilterChip(selected = tts == e, onClick = { tts = e }, label = { Text(e.name) }) }
        }
        OutlinedTextField(voiceId, { voiceId = it }, label = { Text("ElevenLabs voice ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

        val save: () -> Unit = {
            providerKeys.forEach { (p, v) -> if (v.isNotBlank()) secure.put(p.secret, v) }
            providerKeys.clear()
            prefs.provider = provider
            models.forEach { (p, m) -> prefs.setModelFor(p, m) }
            if (proxyToken.isNotBlank()) secure.put(Secret.PROXY_TOKEN, proxyToken)
            if (picovoice.isNotBlank()) secure.put(Secret.PICOVOICE_ACCESS_KEY, picovoice)
            if (eleven.isNotBlank()) secure.put(Secret.ELEVENLABS_API_KEY, eleven)
            if (openai.isNotBlank()) secure.put(Secret.OPENAI_API_KEY, openai)
            proxyToken = ""; picovoice = ""; eleven = ""; openai = ""
            prefs.apiBaseUrl = baseUrl
            prefs.effort = effort
            maxSteps.toIntOrNull()?.let { prefs.maxSteps = it }
            rpm.toIntOrNull()?.let { prefs.maxRequestsPerMinute = it }
            taskTimeout.toIntOrNull()?.let { prefs.taskTimeoutSec = it }
            prefs.elevenLabsVoiceId = voiceId
            prefs.wakeWordEnabled = wake
            prefs.overlayEnabled = overlay
            prefs.startOnBoot = boot
            prefs.sttEngine = stt
            prefs.ttsEngine = tts
            prefs.listenLanguage = lang
        }
        Button(
            onClick = { save(); saved = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        Button(
            onClick = {
                save(); saved = true
                testing = true; testResult = "Test ho raha hai…"
                scope.launch { testResult = graph.testAi(); testing = false }
            },
            enabled = !testing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save + AI connection test karein") }
        testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (saved) Text("Mehfooz ho gaya. Wake word/bubble ki tabdeeli ke liye Jarvis dobara shuru karein.")
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun KeyField(label: String, isSet: Boolean, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(if (isSet) "$label ✓ (badalne ke liye likhein)" else label) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
