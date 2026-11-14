package com.jarvis.agent.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.driving.DrivingMode
import com.jarvis.agent.expenses.Expense
import com.jarvis.agent.expenses.ExpenseCategories
import com.jarvis.agent.expenses.Periods
import com.jarvis.agent.expenses.ReceiptReader
import com.jarvis.agent.expenses.expensesCsv
import com.jarvis.agent.meeting.MeetingRecorder
import com.jarvis.agent.meeting.MeetingSummarizer
import com.jarvis.agent.service.JarvisService
import com.jarvis.agent.tools.impl.ImageCodec
import com.jarvis.agent.ui.components.GlassCard
import com.jarvis.agent.ui.components.SectionTitle
import com.jarvis.agent.ui.components.VoiceOrb
import com.jarvis.agent.ui.theme.JarvisAccent
import com.jarvis.agent.ui.theme.JarvisDanger
import com.jarvis.agent.ui.theme.JarvisMuted
import com.jarvis.agent.ui.theme.JarvisOk
import com.jarvis.agent.ui.theme.JarvisSurface2
import com.jarvis.agent.ui.theme.JarvisPrimary
import com.jarvis.agent.voice.TransLang
import com.jarvis.agent.voice.Translator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ToolPage { NONE, MEETING, TRANSLATE, EXPENSES, DRIVING }

@Composable
fun ToolScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

private fun share(context: android.content.Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ------------------------------------------------------------------ Meeting recorder

@Composable
fun MeetingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rec by MeetingRecorder.state.collectAsState()
    var elapsed by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var past by remember { mutableStateOf(MeetingSummarizer.list(context)) }

    LaunchedEffect(rec.recording) {
        while (rec.recording) { elapsed = System.currentTimeMillis() - rec.startedAt; delay(500) }
    }

    ToolScaffold(tr("Meeting recorder", "Meeting recorder"), onBack) {
        Text(
            tr("Record a meeting, then get a summary, decisions and action items. Tell everyone present that you are recording.",
                "Meeting record karein, phir khulasa, faislay aur action items paayein. Sab ko bata dein ke recording ho rahi hai."),
            color = JarvisMuted, style = MaterialTheme.typography.bodySmall,
        )
        GlassCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val m = elapsed / 60_000; val s = (elapsed / 1000) % 60
                Text(if (rec.recording) "%02d:%02d".format(m, s) else "00:00", fontSize = 44.sp, fontWeight = FontWeight.Bold)
                if (rec.recording) Text("● " + tr("Recording (max 60 min)", "Recording ho rahi hai (max 60 min)"), color = JarvisDanger)
                Button(
                    enabled = !busy,
                    onClick = {
                        error = null
                        if (!rec.recording) {
                            runCatching { MeetingRecorder.start(context) }.onFailure { error = it.message }
                            result = null
                        } else {
                            val f = MeetingRecorder.stop()
                            if (f != null) {
                                busy = true
                                scope.launch {
                                    runCatching { MeetingSummarizer.summarize(f) }
                                        .onSuccess { result = it }.onFailure { error = it.message }
                                    busy = false
                                    past = MeetingSummarizer.list(context)
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (rec.recording) JarvisDanger else JarvisPrimary),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(if (rec.recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord, contentDescription = null)
                    Text("  " + if (rec.recording) tr("Stop & summarize", "Rokein aur khulasa banayein") else tr("Start recording", "Recording shuru karein"))
                }
                if (busy) Text(tr("Summarizing… (usually under a minute)", "Khulasa ban raha hai… (aam tor par 1 minute se kam)"), color = JarvisAccent)
                error?.let { Text(it, color = JarvisDanger) }
            }
        }
        result?.let { r ->
            GlassCard {
                Text(r.substringBefore("TRANSCRIPT").trim(), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { share(context, r) }) { Text(tr("Share", "Share")) }
                    Button(onClick = {
                        if (JarvisService.instance == null) JarvisService.start(context)
                        JarvisService.runText(context, "Create reminders for the action items from this meeting summary (ask me if a date is unclear):\n" + r.substringBefore("TRANSCRIPT").take(2500))
                        onBack()
                    }) { Text(tr("Make reminders", "Reminders banayein")) }
                }
            }
        }
        if (past.isNotEmpty()) SectionTitle(tr("Past meetings", "Pichli meetings"))
        past.take(20).forEach { (file, summary) ->
            GlassCard(onClick = { summary?.let { result = it } }) {
                Text(file.nameWithoutExtension.removePrefix("meeting_").replace('_', ' '), style = MaterialTheme.typography.titleMedium)
                Text(summary?.substringBefore("TRANSCRIPT")?.trim()?.take(160) ?: tr("No summary yet", "Abhi khulasa nahi"), color = JarvisMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ------------------------------------------------------------------ Live translation

data class TranslationLine(val from: TransLang, val heard: String, val to: TransLang, val translated: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslateScreen(onBack: () -> Unit) {
    val g = JarvisApp.graph
    val scope = rememberCoroutineScope()
    val translator = remember { Translator(g.oneShot, g.voice) }
    var mine by remember { mutableStateOf(TransLang.URDU) }
    var theirs by remember { mutableStateOf(TransLang.ENGLISH) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val lines = remember { mutableStateListOf<TranslationLine>() }

    fun go(from: TransLang, to: TransLang) {
        if (working) return
        working = true; error = null
        scope.launch {
            runCatching { translator.turn(from, to) }
                .onSuccess { r -> if (r != null) lines.add(0, TranslationLine(from, r.first, to, r.second)) else error = "…" }
                .onFailure { error = it.message }
            working = false
        }
    }

    ToolScaffold(tr("Live translation", "Fori tarjuma"), onBack) {
        Text(tr("Each person taps their own button and speaks. Jarvis says it in the other language.",
            "Har shakhs apna button dabaye aur bole. Jarvis doosri zabaan mein bol dega."), color = JarvisMuted, style = MaterialTheme.typography.bodySmall)
        GlassCard {
            Text(tr("You speak", "Aap bolenge"), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TransLang.entries.forEach { l -> FilterChip(selected = mine == l, onClick = { mine = l }, label = { Text(l.label) }) } }
            Text(tr("They speak", "Woh bolenge"), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TransLang.entries.forEach { l -> FilterChip(selected = theirs == l, onClick = { theirs = l }, label = { Text(l.label) }) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigTalkButton("${tr("Me", "Main")} · ${mine.label}", JarvisPrimary, enabled = !working, modifier = Modifier.weight(1f)) { go(mine, theirs) }
            BigTalkButton("${tr("Them", "Woh")} · ${theirs.label}", JarvisAccent, enabled = !working, modifier = Modifier.weight(1f)) { go(theirs, mine) }
        }
        if (working) Text(tr("Listening / translating…", "Sun raha hoon / tarjuma…"), color = JarvisAccent)
        error?.takeIf { it != "…" }?.let { Text(it, color = JarvisDanger) }
        lines.forEach { l ->
            GlassCard {
                Text(l.heard, color = JarvisMuted)
                Text(l.translated, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun BigTalkButton(label: String, color: Color, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(130.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (enabled) color else JarvisSurface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        Text(label, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
    }
}

// ------------------------------------------------------------------ Expenses & receipts

@Composable
fun ExpensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val g = JarvisApp.graph
    val dao = remember { g.expenseDb.dao() }
    val scope = rememberCoroutineScope()
    val all by remember { dao.all() }.collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf<Expense?>(null) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun readBitmap(bmp: Bitmap?) {
        if (bmp == null) return
        reading = true; error = null
        scope.launch {
            runCatching { ReceiptReader(g.oneShot).read(ImageCodec.toJpegBase64(bmp, 80)) }
                .onSuccess { draft = it }.onFailure { error = it.message }
            reading = false
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { readBitmap(it) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { u -> readBitmap(context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) }) }
    }

    val (monthStart, monthEnd) = remember(all) { Periods.range("this_month") }
    val month = all.filter { it.date in monthStart until monthEnd }
    val byCat = month.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }.entries.sortedByDescending { it.value }

    ToolScaffold(tr("Expenses & receipts", "Kharche aur receipts"), onBack) {
        GlassCard {
            Text(tr("This month", "Is mahine"), color = JarvisMuted)
            Text("PKR " + "%,.0f".format(month.sumOf { it.amount }), fontSize = 34.sp, fontWeight = FontWeight.Bold)
            byCat.take(5).forEach { (c, v) -> Text("$c  ·  ${"%,.0f".format(v)}", color = JarvisMuted) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { camera.launch(null) }, enabled = !reading, modifier = Modifier.weight(1f)) { Text(tr("Scan receipt", "Receipt scan")) }
            OutlinedButton(
                onClick = { gallery.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !reading, modifier = Modifier.weight(1f),
            ) { Text(tr("From photos", "Photos se")) }
        }
        if (reading) Text(tr("Reading the receipt…", "Receipt parh raha hoon…"), color = JarvisAccent)
        error?.let { Text(it, color = JarvisDanger) }
        draft?.let { d -> ExpenseDraftCard(d, onSave = { e -> scope.launch { dao.insert(e) }; draft = null }, onCancel = { draft = null }) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(tr("All expenses", "Tamam kharche"), Modifier.weight(1f))
            IconButton(onClick = { share(context, expensesCsv(all)) }) { Icon(Icons.Filled.IosShare, contentDescription = "Export CSV") }
        }
        if (all.isEmpty()) Text(tr("Scan a receipt or say \"I spent 1500 on fuel\".", "Receipt scan karein ya kahein \"1500 ka petrol dalwaya\"."), color = JarvisMuted)
        val fmt = remember { SimpleDateFormat("d MMM", Locale.ENGLISH) }
        all.take(100).forEach { e ->
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.merchant, style = MaterialTheme.typography.titleMedium)
                        Text("${fmt.format(Date(e.date))} · ${e.category}" + if (e.note.isNotBlank()) " · ${e.note}" else "", color = JarvisMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${e.currency} ${"%,.0f".format(e.amount)}", fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { scope.launch { dao.delete(e.id) } }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpenseDraftCard(d: Expense, onSave: (Expense) -> Unit, onCancel: () -> Unit) {
    var merchant by remember(d) { mutableStateOf(d.merchant) }
    var amount by remember(d) { mutableStateOf("%.2f".format(d.amount)) }
    var category by remember(d) { mutableStateOf(d.category) }
    GlassCard {
        Text(tr("Check and save", "Dekh kar save karein"), style = MaterialTheme.typography.titleMedium, color = JarvisOk)
        OutlinedTextField(merchant, { merchant = it }, label = { Text(tr("Merchant", "Dukaan")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(amount, { amount = it }, label = { Text("${tr("Amount", "Raqam")} (${d.currency})") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExpenseCategories.all.forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(d.copy(merchant = merchant, amount = amount.toDoubleOrNull() ?: d.amount, category = category)) }) { Text(tr("Save", "Save")) }
            OutlinedButton(onClick = onCancel) { Text(tr("Cancel", "Cancel")) }
        }
    }
}

// ------------------------------------------------------------------ Driving mode

@Composable
fun DrivingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = JarvisApp.graph.prefs
    var on by remember { mutableStateOf(prefs.drivingMode) }
    var autoReply by remember { mutableStateOf(prefs.drivingAutoReply) }
    val state by com.jarvis.agent.core.JarvisRuntime.state.collectAsState()

    LaunchedEffect(Unit) { if (!on) { DrivingMode.set(true); on = true } }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(tr("Driving", "Driving"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Text(if (on) tr("ON", "ON") else tr("OFF", "OFF"), color = if (on) JarvisOk else JarvisMuted, fontWeight = FontWeight.Bold)
        }
        Text(
            tr("New messages are read aloud and calls are silenced. Keep your eyes on the road — just talk.",
                "Naye messages parh kar sunaye jaate hain aur calls khamosh. Nazar sarak par — bas bolein."),
            color = JarvisMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp),
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            VoiceOrb(state.phase, size = 170.dp) {
                if (JarvisService.instance == null) JarvisService.start(context)
                JarvisService.pushToTalk(context)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(tr("Auto-reply \"I'm driving\" to chats", "Chats ko \"main drive kar raha hoon\" ka auto-reply"), modifier = Modifier.weight(1f))
            Switch(checked = autoReply, onCheckedChange = { autoReply = it; prefs.drivingAutoReply = it })
        }
        Button(
            onClick = { DrivingMode.set(false); onBack() },
            colors = ButtonDefaults.buttonColors(containerColor = JarvisDanger),
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = 8.dp),
        ) { Text(tr("End driving mode", "Driving mode band karein"), fontSize = 18.sp) }
    }
}
