package com.jarvis.agent.brain

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A single question to the configured AI with no tools: used for translation and reading receipts.
 * Works with Claude and the free providers alike.
 */
class OneShot(private val llm: LlmClient) {

    suspend fun ask(system: String, text: String, imageJpegBase64: String? = null, maxTokens: Int = 1024): String {
        val blocks = mutableListOf<JsonObject>()
        if (imageJpegBase64 != null) {
            blocks += buildJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64"); put("media_type", "image/jpeg"); put("data", imageJpegBase64)
                }
            }
        }
        blocks += RequestFactory.textBlock(text)
        val body = RequestFactory.build(
            llm.currentConfig(), system, JsonArray(emptyList()), listOf(RequestFactory.userMessage(blocks)), maxTokens,
        )
        val turn = withTimeout(60_000L) { llm.stream(body) }
        if (turn.stopReason == "refusal") throw ApiException(0, "refusal", "The AI declined this request.")
        return turn.text.trim()
    }

    /** Extracts the first JSON object from a reply that may be wrapped in prose or code fences. */
    fun jsonIn(reply: String): String? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        return if (start >= 0 && end > start) reply.substring(start, end + 1) else null
    }
}
