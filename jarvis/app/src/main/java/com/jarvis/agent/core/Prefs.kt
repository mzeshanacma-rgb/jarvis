package com.jarvis.agent.core

import android.content.Context
import android.content.SharedPreferences
import com.jarvis.agent.brain.Provider

enum class SttEngine { ANDROID, WHISPER }
enum class TtsEngine { ANDROID, ELEVENLABS }

/** User preference for the language Jarvis listens in. AUTO lets the recognizer detect Urdu/English. */
/**
 * AUTO = mixed Urdu/English. Below Android 14 the recognizer cannot auto-detect, and "ur-PK" garbles English,
 * so AUTO uses "en-IN", which handles English plus Urdu/Hindi words (written in Roman script) well.
 */
enum class ListenLanguage(val tag: String) { AUTO("en-IN"), URDU("ur-PK"), ENGLISH("en-US") }

/**
 * Non-secret settings. Secrets (API keys) live in [SecureStore], never here.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    var wakeWordEnabled: Boolean
        get() = sp.getBoolean("wake_word", true)
        set(v) = sp.edit().putBoolean("wake_word", v).apply()

    var wakeSensitivity: Float
        get() = sp.getFloat("wake_sensitivity", 0.6f)
        set(v) = sp.edit().putFloat("wake_sensitivity", v.coerceIn(0f, 1f)).apply()

    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlay", true)
        set(v) = sp.edit().putBoolean("overlay", v).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("start_on_boot", true)
        set(v) = sp.edit().putBoolean("start_on_boot", v).apply()

    var sttEngine: SttEngine
        get() = enumOr(sp.getString("stt", null), SttEngine.ANDROID)
        set(v) = sp.edit().putString("stt", v.name).apply()

    var ttsEngine: TtsEngine
        get() = enumOr(sp.getString("tts", null), TtsEngine.ANDROID)
        set(v) = sp.edit().putString("tts", v.name).apply()

    var listenLanguage: ListenLanguage
        get() = enumOr(sp.getString("listen_lang", null), ListenLanguage.AUTO)
        set(v) = sp.edit().putString("listen_lang", v.name).apply()

    var elevenLabsVoiceId: String
        get() = sp.getString("eleven_voice", null) ?: "21m00Tcm4TlvDq8ikWAM"
        set(v) = sp.edit().putString("eleven_voice", v.trim()).apply()

    /** Base URL of the Claude API, or of the user's own proxy server. */
    var apiBaseUrl: String
        get() = sp.getString("api_base", null) ?: DEFAULT_API_BASE
        set(v) = sp.edit().putString("api_base", v.trim().trimEnd('/').ifEmpty { DEFAULT_API_BASE }).apply()

    /** Which AI service is used. Defaults to a free one so Jarvis works without a paid key. */
    var provider: Provider
        get() = enumOr(sp.getString("provider", null), Provider.GEMINI)
        set(v) = sp.edit().putString("provider", v.name).apply()

    /** Model for the *current* provider (each provider remembers its own). */
    var model: String
        get() = modelFor(provider)
        set(v) = setModelFor(provider, v)

    fun modelFor(p: Provider): String {
        val stored = sp.getString("model_${p.name}", null)?.trim()
        return if (stored.isNullOrEmpty() || stored in RETIRED_MODELS) p.defaultModel else stored
    }

    /** Saving the default name just clears the override, so future default upgrades apply automatically. */
    fun setModelFor(p: Provider, v: String) {
        val name = v.trim()
        val e = sp.edit()
        if (name.isEmpty() || name == p.defaultModel || name in RETIRED_MODELS) e.remove("model_${p.name}")
        else e.putString("model_${p.name}", name)
        e.apply()
    }

    /** Effort for the agent loop. `between_tools` thinking accepts `high` or below. */
    var effort: String
        get() = sp.getString("effort", null) ?: "medium"
        set(v) = sp.edit().putString("effort", v).apply()

    var maxSteps: Int
        get() = sp.getInt("max_steps", 25)
        set(v) = sp.edit().putInt("max_steps", v.coerceIn(1, 25)).apply()

    var maxRequestsPerMinute: Int
        get() = sp.getInt("rpm", 10)
        set(v) = sp.edit().putInt("rpm", v.coerceIn(1, 60)).apply()

    var taskTimeoutSec: Int
        get() = sp.getInt("task_timeout", 300)
        set(v) = sp.edit().putInt("task_timeout", v.coerceIn(30, 900)).apply()

    /** App interface language: false = English, true = Roman Urdu. */
    var uiUrdu: Boolean
        get() = sp.getBoolean("ui_urdu", false)
        set(v) = sp.edit().putBoolean("ui_urdu", v).apply()

    // ---- privacy
    /** Apps Jarvis must never read or operate (banking, official apps…). */
    var privateApps: Set<String>
        get() = sp.getStringSet("private_apps", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("private_apps", v).apply()

    /** Incognito: no conversation history and no new memories are saved (the safety log is still kept). */
    var incognito: Boolean
        get() = sp.getBoolean("incognito", false)
        set(v) = sp.edit().putBoolean("incognito", v).apply()

    /** Ask for fingerprint / screen lock when opening the app. */
    var appLock: Boolean
        get() = sp.getBoolean("app_lock", false)
        set(v) = sp.edit().putBoolean("app_lock", v).apply()

    // ---- modes
    var drivingMode: Boolean
        get() = sp.getBoolean("driving", false)
        set(v) = sp.edit().putBoolean("driving", v).apply()

    /** While driving: auto-reply "I'm driving" to message notifications (opt-in, fixed text). */
    var drivingAutoReply: Boolean
        get() = sp.getBoolean("driving_reply", false)
        set(v) = sp.edit().putBoolean("driving_reply", v).apply()

    /** Busy (meeting) mode: incoming calls are silenced; optionally an SMS "will call back" is sent. */
    var busyMode: Boolean
        get() = sp.getBoolean("busy", false)
        set(v) = sp.edit().putBoolean("busy", v).apply()

    var busyAutoSms: Boolean
        get() = sp.getBoolean("busy_sms", false)
        set(v) = sp.edit().putBoolean("busy_sms", v).apply()

    var announceCalls: Boolean
        get() = sp.getBoolean("announce_calls", true)
        set(v) = sp.edit().putBoolean("announce_calls", v).apply()

    var silenceUnknownCallers: Boolean
        get() = sp.getBoolean("silence_unknown", false)
        set(v) = sp.edit().putBoolean("silence_unknown", v).apply()

    /** Home Assistant base URL, e.g. https://home.example.com or http://homeassistant.local:8123 */
    var homeAssistantUrl: String
        get() = sp.getString("ha_url", null).orEmpty()
        set(v) = sp.edit().putString("ha_url", v.trim().trimEnd('/')).apply()

    var setupDone: Boolean
        get() = sp.getBoolean("setup_done", false)
        set(v) = sp.edit().putBoolean("setup_done", v).apply()

    val usesDirectApi: Boolean get() = provider == Provider.CLAUDE && apiBaseUrl == DEFAULT_API_BASE

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

    companion object {
        const val DEFAULT_API_BASE = "https://api.anthropic.com"

        /** Model names providers have shut down; a saved one of these falls back to the current default. */
        val RETIRED_MODELS = setOf("gemini-2.5-flash", "gemini-2.0-flash")
    }
}
