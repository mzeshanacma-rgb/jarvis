package com.jarvis.agent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.jarvis.agent.JarvisApp

/** App interface language (English or Roman Urdu). Changing it re-renders every screen at once. */
object UiLang {
    val urdu = mutableStateOf(JarvisApp.graph.prefs.uiUrdu)

    fun set(isUrdu: Boolean) {
        JarvisApp.graph.prefs.uiUrdu = isUrdu
        urdu.value = isUrdu
    }
}

@Composable
fun tr(en: String, ur: String): String = if (UiLang.urdu.value) ur else en

/** Ready-made requests behind the quick-action buttons, in the language the user chose. */
object QuickPrompts {
    val urdu get() = UiLang.urdu.value

    val briefing get() = if (urdu)
        "Mujhe briefing do: aaj ka calendar, missed calls, aur zaroori unread notifications aur messages. Mukhtasar rakho."
    else "Give me my briefing: today's calendar, missed calls, and important unread notifications and messages. Keep it short."

    val attention get() = if (urdu)
        "Kya cheez meri tawajjo chahti hai? Notifications aur messages dekho, sirf woh batao jin ka jawab ya faisla chahiye, sab se zaroori pehle, har ek ke saath mukhtasar mashwara-jawab. Kuch bhejna nahi."
    else "What needs my attention? Check notifications and messages, list only items that need a reply or decision, most important first, each with a short suggested reply. Do not send anything."

    val summarize get() = if (urdu)
        "Recents se pichli app kholo jo main Jarvis se pehle istemal kar raha tha, aur us ki screen ka khulasa 3 nukaat mein batao."
    else "Open the app I was using before Jarvis (from recents) and summarize what is on its screen in 3 short points."

    /** Prefixes: the user's spoken words follow them. */
    val dictatePrefix get() = if (urdu)
        "Mere liye yeh message achhi tarah likh kar bhejo (meri zabaan rakho, bhejne se pehle mujhe dikhao):"
    else "Draft this message properly and send it (keep my language, show me before sending):"

    val reminderPrefix get() = if (urdu) "Reminder lagao:" else "Set a reminder:"
}
