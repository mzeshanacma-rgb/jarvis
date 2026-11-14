package com.jarvis.agent.driving

import android.content.Context
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.core.Privacy
import com.jarvis.agent.notifications.JarvisNotificationListener
import com.jarvis.agent.notifications.StoredNotification
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands-free mode for the car: new messages are read aloud, the phone is in busy mode (calls silenced,
 * optionally answered by SMS), and — if the user opted in — chats get a one-time "I'm driving" reply.
 */
object DrivingMode {
    private val messagingApps = listOf(
        "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "org.telegram.messenger", "com.facebook.orca", "org.thoughtcrime.securesms", "com.instagram.android",
        "com.google.android.gm", "com.microsoft.teams", "com.Slack",
    )
    private val lastAutoReply = ConcurrentHashMap<String, Long>()
    const val AUTO_REPLY_TEXT = "I'm driving right now and will reply when I stop. (Auto-reply by Jarvis)"
    private const val REPLY_COOLDOWN_MS = 30 * 60_000L

    fun isMessaging(pkg: String) = messagingApps.any { pkg == it || pkg.startsWith("$it.") }

    fun set(on: Boolean) {
        val p = JarvisApp.graph.prefs
        p.drivingMode = on
        p.busyMode = on
    }

    /** Called for each newly posted notification. */
    fun onPosted(context: Context, n: StoredNotification) {
        val g = JarvisApp.graph
        if (!g.prefs.drivingMode || !isMessaging(n.packageName) || Privacy.isPrivate(n.packageName)) return
        val text = n.text.take(220)
        g.appScope.launch {
            runCatching { g.voice.speak("${n.title}, ${n.appLabel}: $text") }
        }
        if (g.prefs.drivingAutoReply && shouldAutoReply(n.title, System.currentTimeMillis()) && n.replyAction != null) {
            if (JarvisNotificationListener.reply(context, n, AUTO_REPLY_TEXT)) {
                g.actionLog.log(0L, "TOOL", "Driving auto-reply to ${n.title} (${n.appLabel})", risk = "MEDIUM", outcome = "sent (user opted in)")
            }
        }
    }

    /** One auto-reply per conversation per 30 minutes (pure, unit tested). */
    fun shouldAutoReply(conversation: String, now: Long): Boolean {
        val last = lastAutoReply[conversation]
        if (last != null && now - last < REPLY_COOLDOWN_MS) return false
        lastAutoReply[conversation] = now
        return true
    }
}
