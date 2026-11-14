package com.jarvis.agent.brain

import com.jarvis.agent.core.SecureStore

/**
 * Which AI service powers Jarvis. CLAUDE uses the Anthropic Messages API directly (or your own proxy);
 * the others speak the OpenAI-style chat format and have free tiers, so Jarvis works without a paid key.
 */
enum class Provider(
    val label: String,
    val defaultModel: String,
    /** Base URL for OpenAI-style providers (null for Claude, which has its own client). */
    val openAiBaseUrl: String?,
    val secret: SecureStore.Secret,
    val keyHelp: String,
    /** Whether images (screenshots, photos) can be sent to the default model. */
    val supportsImages: Boolean,
    val free: Boolean,
) {
    GEMINI(
        "Google Gemini (free)", "gemini-3.1-flash-lite",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        SecureStore.Secret.GEMINI_API_KEY,
        "Free key: aistudio.google.com/apikey (Google account). Urdu aur screenshots dono ke liye behtareen free option. " +
            "(Purana gemini-2.5-flash naye users ke liye band hai.)",
        supportsImages = true, free = true,
    ),
    GROQ(
        "Groq (free)", "llama-3.3-70b-versatile",
        "https://api.groq.com/openai/v1",
        SecureStore.Secret.GROQ_API_KEY,
        "Free key: console.groq.com/keys. Bohot tez, lekin screenshots nahi dekhta.",
        supportsImages = false, free = true,
    ),
    OPENROUTER(
        "OpenRouter (free models)", "meta-llama/llama-3.3-70b-instruct:free",
        "https://openrouter.ai/api/v1",
        SecureStore.Secret.OPENROUTER_API_KEY,
        "Free key: openrouter.ai/keys. Model ke naam ke aakhir mein :free wale muft hain.",
        supportsImages = false, free = true,
    ),
    CLAUDE(
        "Claude (Anthropic)", "claude-sonnet-5-5",
        null,
        SecureStore.Secret.ANTHROPIC_API_KEY,
        "console.anthropic.com se key, ya apna proxy (README section 7).",
        supportsImages = true, free = false,
    ),
}
