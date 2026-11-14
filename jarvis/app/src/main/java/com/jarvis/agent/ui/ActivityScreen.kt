package com.jarvis.agent.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.log.ActionLogEntry
import com.jarvis.agent.ui.components.GlassCard
import com.jarvis.agent.ui.theme.JarvisCyan
import com.jarvis.agent.ui.theme.JarvisDanger
import com.jarvis.agent.ui.theme.JarvisMuted
import com.jarvis.agent.ui.theme.JarvisOk
import com.jarvis.agent.ui.theme.JarvisWarn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A task as the user thinks of it: what they asked, each action taken (with its safety level), the answer. */
data class TaskView(val id: Long, val time: Long, val request: String, val steps: List<ActionLogEntry>, val reply: String?)

fun groupTasks(entries: List<ActionLogEntry>): List<TaskView> =
    entries.groupBy { it.taskId }
        .map { (id, list) ->
            val sorted = list.sortedBy { it.id }
            TaskView(
                id = id,
                time = sorted.first().timestamp,
                request = sorted.firstOrNull { it.kind == "TASK" }?.summary ?: sorted.first().summary,
                steps = sorted.filter { it.kind in setOf("TOOL", "CONFIRM", "ERROR", "KILL") },
                reply = sorted.lastOrNull { it.kind == "REPLY" }?.summary,
            )
        }
        .sortedByDescending { it.time }

fun exportText(tasks: List<TaskView>): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
    return buildString {
        append("Jarvis activity export\n\n")
        for (t in tasks) {
            append("[${fmt.format(Date(t.time))}] ").append(t.request).append('\n')
            for (s in t.steps) {
                append("   - ${s.kind}${s.risk?.let { " ($it)" } ?: ""}: ${s.summary}")
                s.outcome?.let { append(" -> $it") }
                append('\n')
            }
            t.reply?.let { append("   = ").append(it).append('\n') }
            append('\n')
        }
    }
}

@Composable
fun ActivityScreen() {
    val context = LocalContext.current
    val logger = JarvisApp.graph.actionLog
    val entries by remember { logger.recent(600) }.collectAsState(initial = emptyList())
    val tasks = remember(entries) { groupTasks(entries) }
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(tr("Activity", "Sargarmi"), style = MaterialTheme.typography.headlineMedium)
                Text(tr("Everything Jarvis did, step by step", "Jarvis ne jo kuch kiya, qadam ba qadam"), color = JarvisMuted, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, exportText(tasks))
                context.startActivity(Intent.createChooser(send, "Export").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Icon(Icons.Filled.IosShare, contentDescription = tr("Export", "Export")) }
            IconButton(onClick = { logger.clear() }) { Icon(Icons.Filled.DeleteSweep, contentDescription = tr("Clear", "Saaf karein")) }
        }
        if (tasks.isEmpty()) {
            Text(tr("Nothing yet.", "Abhi tak kuch nahi."), color = JarvisMuted, modifier = Modifier.padding(top = 24.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(tasks, key = { it.id }) { t ->
                GlassCard {
                    Row {
                        Text(t.request, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(fmt.format(Date(t.time)), color = JarvisMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    for (s in t.steps) StepRow(s)
                    t.reply?.let { Text("→ $it", color = JarvisCyan, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun StepRow(e: ActionLogEntry) {
    val (dot, color) = when {
        e.kind == "KILL" -> "■" to JarvisDanger
        e.kind == "ERROR" -> "✕" to JarvisDanger
        e.kind == "CONFIRM" && e.outcome == "APPROVED" -> "✓" to JarvisOk
        e.kind == "CONFIRM" -> "✕" to JarvisWarn
        e.risk == "HIGH" -> "●" to JarvisDanger
        e.risk == "MEDIUM" -> "●" to JarvisWarn
        else -> "•" to JarvisMuted
    }
    val label = when (e.kind) {
        "CONFIRM" -> (if (e.outcome == "APPROVED") "Approved: " else "Not approved: ") + e.summary
        "ERROR" -> e.summary + (e.outcome?.let { " — $it" } ?: "")
        else -> e.summary
    }
    Row {
        Text(dot, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
        Text(label, color = if (e.kind == "TOOL") Color.White.copy(alpha = 0.85f) else color, style = MaterialTheme.typography.bodyMedium)
    }
}
