package com.jarvis.agent.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.agent.core.AgentPhase
import com.jarvis.agent.ui.theme.JarvisBorder
import com.jarvis.agent.ui.theme.JarvisCyan
import com.jarvis.agent.ui.theme.JarvisDanger
import com.jarvis.agent.ui.theme.JarvisMuted
import com.jarvis.agent.ui.theme.JarvisOk
import com.jarvis.agent.ui.theme.JarvisSurface
import com.jarvis.agent.ui.theme.JarvisViolet
import com.jarvis.agent.ui.theme.JarvisWarn

fun phaseColor(p: AgentPhase): Color = when (p) {
    AgentPhase.LISTENING -> JarvisOk
    AgentPhase.THINKING, AgentPhase.ACTING -> JarvisWarn
    AgentPhase.SPEAKING -> JarvisViolet
    AgentPhase.WAITING_CONFIRMATION -> JarvisDanger
    else -> JarvisCyan
}

fun AgentPhase.isBusy() = this != AgentPhase.OFF && this != AgentPhase.WAITING_WAKE_WORD

/** The animated voice orb: breathes when idle, pulses fast while listening or working. */
@Composable
fun VoiceOrb(phase: AgentPhase, size: Dp = 104.dp, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val busy = phase.isBusy()
    val pulse by t.animateFloat(
        initialValue = 1f, targetValue = if (busy) 1.12f else 1.04f,
        animationSpec = infiniteRepeatable(tween(if (busy) 650 else 1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val glow by t.animateFloat(
        initialValue = 0.25f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "glow",
    )
    val c = phaseColor(phase)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size * 1.45f)) {
        Box(
            Modifier
                .size(size * 1.35f)
                .scale(pulse)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(c.copy(alpha = glow), Color.Transparent))),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .scale(if (busy) pulse else 1f)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(JarvisViolet, c)))
                .clickable(onClick = onClick),
        ) {
            Icon(
                if (busy) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (busy) "Stop" else "Talk",
                tint = Color.White,
                modifier = Modifier.size(size * 0.42f),
            )
        }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JarvisSurface.copy(alpha = 0.92f))
            .border(1.dp, JarvisBorder, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun StatusPill(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = JarvisMuted, fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(top = 18.dp, bottom = 6.dp, start = 4.dp),
    )
}

/** Chat bubble: user on the right (gradient), Jarvis on the left (surface). */
@Composable
fun ChatBubble(text: String, fromUser: Boolean, footnote: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        val shape = RoundedCornerShape(
            topStart = 20.dp, topEnd = 20.dp,
            bottomStart = if (fromUser) 20.dp else 6.dp, bottomEnd = if (fromUser) 6.dp else 20.dp,
        )
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(if (fromUser) Brush.linearGradient(listOf(JarvisViolet, Color(0xFF5B8CFF))) else Brush.linearGradient(listOf(JarvisSurface, JarvisSurface)))
                .border(if (fromUser) 0.dp else 1.dp, JarvisBorder, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            footnote?.let { Text(it, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
