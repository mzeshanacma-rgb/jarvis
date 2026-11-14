package com.jarvis.agent.ui.setup

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jarvis.agent.ui.components.GlassCard
import com.jarvis.agent.ui.theme.JarvisMuted
import com.jarvis.agent.ui.theme.JarvisOk
import com.jarvis.agent.ui.tr

/**
 * Permissions as "abilities": each row says in one line what Jarvis can do with it, shows ✓ when granted,
 * and opens the right system dialog or settings page. Re-checks whenever the user comes back.
 */
@Composable
fun PermissionList(items: List<PermissionItem>, onChanged: (grantedCount: Int) -> Unit = {}) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val granted = remember(tick) { items.associate { it.id to it.isGranted(context) } }
    LaunchedEffect(granted) { onChanged(granted.count { it.value }) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (item in items) {
            val ok = granted[item.id] == true
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (ok) JarvisOk else JarvisMuted,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (!ok) {
                        FilledTonalButton(onClick = {
                            if (item.runtime.isNotEmpty()) {
                                launcher.launch(item.runtime.toTypedArray())
                            } else {
                                item.settingsIntent?.let { f ->
                                    try {
                                        context.startActivity(f(context))
                                    } catch (_: ActivityNotFoundException) {
                                    }
                                }
                            }
                        }) { Text(tr("Allow", "Ijazat")) }
                    }
                }
                Text(item.why, color = JarvisMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 32.dp).fillMaxWidth())
            }
        }
    }
}
