package com.jarvis.agent.voice

import com.jarvis.agent.brain.OneShot
import java.util.Locale

/** Languages offered for live translation (speech tag, TTS locale, display name). */
enum class TransLang(val tag: String, val locale: Locale, val label: String) {
    URDU("ur-PK", Locale("ur", "PK"), "اردو Urdu"),
    ENGLISH("en-US", Locale.US, "English"),
    ARABIC("ar-SA", Locale("ar", "SA"), "العربية Arabic"),
    PUNJABI("pa-IN", Locale("pa", "IN"), "Punjabi"),
    HINDI("hi-IN", Locale("hi", "IN"), "Hindi"),
    CHINESE("zh-CN", Locale.SIMPLIFIED_CHINESE, "中文 Chinese"),
    TURKISH("tr-TR", Locale("tr", "TR"), "Türkçe Turkish"),
    PERSIAN("fa-IR", Locale("fa", "IR"), "فارسی Persian"),
    FRENCH("fr-FR", Locale.FRANCE, "Français French"),
    SPANISH("es-ES", Locale("es", "ES"), "Español Spanish"),
}

/** Two-way spoken translation: listen in one language, translate with the AI, speak in the other. */
class Translator(private val oneShot: OneShot, private val voice: VoiceIO) {

    suspend fun translate(text: String, from: TransLang, to: TransLang): String = oneShot.ask(
        system = "You are a professional interpreter. Translate faithfully and naturally. Output only the translation.",
        text = "Translate from ${from.name.lowercase()} to ${to.name.lowercase()} (use the native script):\n$text",
        maxTokens = 600,
    ).trim().trim('"')

    /** Listens in [from], translates, speaks it in [to]. Returns heard text and translation (or null). */
    suspend fun turn(from: TransLang, to: TransLang): Pair<String, String>? {
        val heard = voice.listen(tagOverride = from.tag) ?: return null
        val out = translate(heard, from, to)
        voice.speakIn(out, to.locale)
        return heard to out
    }
}
