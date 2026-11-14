package com.jarvis.agent.brain

/**
 * Free AI tiers have small quotas, and sending all ~55 tool definitions with every request burns them fast.
 * For those providers we send a "core" set plus the groups the user's words point to; the model can
 * switch on any other group with the enable_tools tool. Claude always gets every tool (prompt caching
 * makes that cheap, and changing tools mid-conversation would invalidate its history).
 */
object ToolRouting {

    val groups: Map<String, Set<String>> = mapOf(
        "core" to setOf(
            "ask_user", "hand_off_to_user", "wait", "get_time", "device_status", "enable_tools",
            "read_screen", "screenshot", "tap", "long_press", "type_text", "scroll", "swipe", "press_key",
            "list_apps", "launch_app", "open_url", "remember", "recall",
        ),
        "communication" to setOf(
            "search_contacts", "make_call", "send_sms", "read_sms", "send_email", "read_call_log", "save_contact_nickname",
        ),
        "notifications" to setOf("read_notifications", "reply_notification", "open_notification", "dismiss_notification"),
        "calendar" to setOf(
            "set_alarm", "set_timer", "read_calendar", "add_calendar_event", "delete_calendar_event", "set_reminder",
        ),
        "device" to setOf(
            "set_volume", "set_brightness", "set_do_not_disturb", "flashlight", "open_settings",
            "clipboard_read", "clipboard_write", "get_location",
        ),
        "web" to setOf("web_search", "read_web_page"),
        "files" to setOf("list_files", "read_text_file", "delete_file", "read_pdf_page", "recent_photos", "view_photo", "open_camera"),
        "routines" to setOf("forget", "create_routine", "list_routines", "set_routine_enabled", "delete_routine"),
    )

    private fun kw(vararg w: String) = Regex(w.joinToString("|") { "(?<![\\p{L}])$it" }, RegexOption.IGNORE_CASE)

    private val triggers: Map<String, Regex> = mapOf(
        "communication" to kw(
            "call", "phone", "dial", "sms", "text", "message", "msg", "email", "mail", "gmail", "contact", "whatsapp",
            "missed", "ring", "bhej", "paigham", "number", "draft", "reply", "jawab", "کال", "فون", "میسج", "پیغام", "ای ?میل",
        ),
        "notifications" to kw(
            "notification", "attention", "unread", "whatsapp", "reply", "jawab", "message", "briefing", "update",
            "zaroori", "important", "اطلاع", "نوٹیفکیشن",
        ),
        "calendar" to kw(
            "alarm", "timer", "remind", "reminder", "yaad", "calendar", "meeting", "schedule", "event", "appointment",
            "briefing", "today", "tomorrow", "aaj", "kal", "baje", "o'clock", "agenda", "الارم", "میٹنگ", "یاد",
        ),
        "device" to kw(
            "volume", "brightness", "silent", "dnd", "disturb", "flash", "torch", "wifi", "wi-fi", "bluetooth",
            "data", "hotspot", "setting", "battery", "location", "where am i", "kahan", "clipboard", "copy",
            "awaaz", "roshni", "آواز", "سیٹنگ",
        ),
        "web" to kw(
            "search", "google", "find", "look up", "news", "weather", "mausam", "price", "qeemat", "website", "web",
            "who is", "what is", "kya hai", "kaun", "تلاش", "خبر", "موسم",
        ),
        "files" to kw(
            "file", "pdf", "document", "download", "photo", "picture", "image", "gallery", "camera", "selfie",
            "tasveer", "summar", "فائل", "تصویر",
        ),
        "routines" to kw("routine", "every day", "har roz", "daily", "every morning", "forget", "bhool", "روزانہ"),
    )

    /** Groups to send for this request (always includes core). */
    fun groupsFor(text: String): MutableSet<String> {
        val out = mutableSetOf("core")
        for ((g, r) in triggers) if (r.containsMatchIn(text)) out += g
        return out
    }

    fun toolNames(groups: Set<String>): Set<String> = groups.flatMap { this.groups[it].orEmpty() }.toSet()

    /** Group names a model can pass to enable_tools. */
    val optionalGroups: List<String> get() = groups.keys.filter { it != "core" }
}
