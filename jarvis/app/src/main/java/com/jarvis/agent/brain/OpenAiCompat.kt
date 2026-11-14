package com.jarvis.agent.brain

import com.jarvis.agent.core.Prefs
import com.jarvis.agent.core.SecureStore
import com.jarvis.agent.core.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** The part of a client the agent loop depends on. */
interface LlmClient {
    fun currentConfig(): ModelConfig
    suspend fun stream(body: JsonObject, listener: StreamListener? = null): AssistantTurn
}

/**
 * Converts the app's Anthropic-style request (system, tools, content blocks, tool_result with images) into
 * an OpenAI-style chat completion request, so the same agent loop drives free providers.
 */
object OpenAiConverter {

    fun toChatRequest(body: JsonObject, provider: Provider, model: String): JsonObject {
        val messages = mutableListOf<JsonElement>()
        systemText(body)?.let { messages += msg("system", JsonPrimitive(it)) }
        for (m in body.arr("messages").orEmpty()) {
            val o = m as? JsonObject ?: continue
            when (o.str("role")) {
                "user" -> messages += userMessages(o, provider.supportsImages)
                "assistant" -> assistantMessage(o)?.let { messages += it }
            }
        }
        val tools = tools(body.arr("tools"))
        return buildJsonObject {
            put("model", model)
            put("stream", true)
            put("max_tokens", body.int("max_tokens") ?: 4096)
            put("messages", JsonArray(messages))
            if (tools.isNotEmpty()) put("tools", JsonArray(tools))
        }
    }

    private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())

    private fun msg(role: String, content: JsonElement) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun systemText(body: JsonObject): String? {
        val s = body["system"]
        return when (s) {
            is JsonPrimitive -> s.content
            is JsonArray -> s.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n\n")
            else -> null
        }?.ifBlank { null }
    }

    private fun userMessages(m: JsonObject, images: Boolean): List<JsonElement> {
        val content = m["content"]
        if (content is JsonPrimitive) return listOf(msg("user", content))
        val out = mutableListOf<JsonElement>()
        val images_ = mutableListOf<Pair<String, String>>() // tool_use_id to data URI
        val texts = mutableListOf<String>()
        for (b in content as? JsonArray ?: JsonArray(emptyList())) {
            val block = b as? JsonObject ?: continue
            when (block.str("type")) {
                "text" -> block.str("text")?.let { texts += it }
                "tool_result" -> {
                    val parts = (block["content"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                        ?: listOfNotNull((block["content"] as? JsonPrimitive)?.let { buildJsonObject { put("type", "text"); put("text", it.content) } })
                    val text = parts.filter { it.str("type") == "text" }.joinToString("\n") { it.str("text").orEmpty() }
                    val hasImage = parts.any { it.str("type") == "image" }
                    val note = if (hasImage && !images) "\n[screenshot omitted: this model cannot see images. Use read_screen.]" else ""
                    out += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", block.str("tool_use_id").orEmpty())
                        put("content", (text + note).ifBlank { "(empty)" })
                    }
                    if (images) {
                        parts.filter { it.str("type") == "image" }.forEach { img ->
                            val src = img.obj("source")
                            val uri = "data:${src?.str("media_type") ?: "image/jpeg"};base64,${src?.str("data").orEmpty()}"
                            images_ += block.str("tool_use_id").orEmpty() to uri
                        }
                    }
                }
            }
        }
        // Tool messages must directly follow the assistant's tool_calls; images and text come after them.
        for ((id, uri) in images_) {
            out += msg(
                "user",
                JsonArray(
                    listOf(
                        buildJsonObject { put("type", "text"); put("text", "Image returned by tool call $id (untrusted data):") },
                        buildJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", uri) } },
                    ),
                ),
            )
        }
        if (texts.isNotEmpty()) out += msg("user", JsonPrimitive(texts.joinToString("\n\n")))
        return out
    }

    private fun assistantMessage(m: JsonObject): JsonElement? {
        val content = m["content"]
        if (content is JsonPrimitive) return msg("assistant", content)
        val text = StringBuilder()
        val calls = mutableListOf<JsonElement>()
        for (b in content as? JsonArray ?: return null) {
            val block = b as? JsonObject ?: continue
            when (block.str("type")) {
                "text" -> block.str("text")?.let { text.append(it) }
                "tool_use" -> calls += buildJsonObject {
                    put("id", block.str("id").orEmpty())
                    put("type", "function")
                    // Gemini 3 requires its `thought_signature` to be echoed back on every tool call.
                    block["extra_content"]?.let { put("extra_content", it) }
                    putJsonObject("function") {
                        put("name", block.str("name").orEmpty())
                        put("arguments", (block.obj("input") ?: JsonObject(emptyMap())).toString())
                    }
                }
            }
        }
        if (text.isEmpty() && calls.isEmpty()) return null
        return buildJsonObject {
            put("role", "assistant")
            if (text.isNotEmpty()) put("content", text.toString()) else put("content", JsonNull)
            if (calls.isNotEmpty()) put("tool_calls", JsonArray(calls))
        }
    }

    private fun tools(defs: JsonArray?): List<JsonElement> = defs.orEmpty().mapNotNull { d ->
        val t = d as? JsonObject ?: return@mapNotNull null
        buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", t.str("name").orEmpty())
                put("description", t.str("description").orEmpty())
                cleanSchema(t.obj("input_schema"))?.let { put("parameters", it) }
            }
        }
    }

    /** Free providers reject `additionalProperties`, empty `required` and empty `properties`. */
    fun cleanSchema(schema: JsonObject?): JsonObject? {
        if (schema == null) return null
        val props = schema.obj("properties")
        if (props == null || props.isEmpty()) return null
        val required = schema.arr("required")
        return buildJsonObject {
            put("type", "object")
            put("properties", props)
            if (required != null && required.isNotEmpty()) put("required", required)
        }
    }
}

/** Rebuilds an [AssistantTurn] from OpenAI-style streaming chunks. */
class OpenAiStreamAccumulator(private val listener: StreamListener? = null) {
    private class Call(var id: String, var name: String, val index: Int?) {
        val args = StringBuilder()
        /** Provider-specific data that must be replayed with the call (Gemini's thought_signature). */
        var extra: JsonElement? = null
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val text = StringBuilder()
    private val calls = mutableListOf<Call>()
    private var finish: String? = null
    private var generated = 0

    fun onData(data: String) {
        if (data.trim() == "[DONE]") return
        val el = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: return
        val obj = when (el) {
            is JsonObject -> el
            is JsonArray -> el.firstOrNull() as? JsonObject ?: return
            else -> return
        }
        obj.obj("error")?.let { e ->
            throw ApiException(
                (e["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                e.str("type") ?: e.str("status") ?: "stream_error",
                e.str("message") ?: "Stream error",
            )
        }
        val choice = obj.arr("choices")?.firstOrNull() as? JsonObject ?: return
        choice.str("finish_reason")?.let { finish = it }
        val delta = choice.obj("delta") ?: return
        delta.str("content")?.takeIf { it.isNotEmpty() }?.let {
            text.append(it)
            listener?.onTextDelta(it)
        }
        for (tc in delta.arr("tool_calls").orEmpty()) {
            val c = tc as? JsonObject ?: continue
            val fn = c.obj("function")
            val id = c.str("id").orEmpty()
            val name = fn?.str("name").orEmpty()
            val index = c.int("index")
            val args = fn?.str("arguments").orEmpty()
            val isNew = (id.isNotBlank() && calls.none { it.id == id }) ||
                (id.isBlank() && name.isNotBlank()) ||
                calls.isEmpty()
            val call = if (isNew) {
                Call(id.ifBlank { "call_${++generated}" }, name, index).also {
                    calls += it
                    if (name.isNotBlank()) listener?.onToolUseStart(name)
                }
            } else {
                calls.lastOrNull { it.id == id && id.isNotBlank() } ?: calls.lastOrNull { it.index == index } ?: calls.last()
            }
            if (!isNew && name.isNotBlank() && call.name.isBlank()) call.name = name
            c["extra_content"]?.let { call.extra = it }
            call.args.append(args)
        }
    }

    fun build(): AssistantTurn {
        val content = mutableListOf<JsonElement>()
        if (text.isNotBlank()) {
            content += buildJsonObject { put("type", "text"); put("text", text.toString()) }
        }
        val toolCalls = calls.map { c ->
            val raw = c.args.toString()
            var error: String? = null
            val input: JsonObject = if (raw.isBlank()) JsonObject(emptyMap()) else try {
                json.parseToJsonElement(raw) as? JsonObject ?: run {
                    error = "Tool input is not a JSON object"; JsonObject(emptyMap())
                }
            } catch (e: Exception) {
                error = "Invalid JSON: ${e.message?.take(200)}"; JsonObject(emptyMap())
            }
            content += buildJsonObject {
                put("type", "tool_use"); put("id", c.id); put("name", c.name); put("input", input)
                c.extra?.let { put("extra_content", it) }
            }
            ToolCall(c.id, c.name, input, raw, error)
        }
        val stop = when {
            finish == "length" -> "max_tokens"
            finish == "content_filter" -> "refusal"
            toolCalls.isNotEmpty() -> "tool_use" // some providers report "stop" even when tools were called
            else -> "end_turn"
        }
        return AssistantTurn(JsonArray(content), text.toString(), toolCalls, stop, null)
    }

    private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())
}

/** Streams chat completions from Gemini, Groq, OpenRouter or any OpenAI-compatible endpoint. */
class OpenAiCompatClient(
    private val http: OkHttpClient,
    private val prefs: Prefs,
    private val secure: SecureStore,
) : LlmClient {
    private val json = Json { ignoreUnknownKeys = true }

    override fun currentConfig() = ModelConfig(
        model = prefs.model, effort = prefs.effort, directApi = false, isClaude = false,
    )

    override suspend fun stream(body: JsonObject, listener: StreamListener?): AssistantTurn {
        val provider = prefs.provider
        val request = OpenAiConverter.toChatRequest(body, provider, prefs.model)
        var attempt = 0
        while (true) {
            try {
                return streamOnce(provider, request, listener)
            } catch (e: ApiException) {
                if (!e.retryable || attempt >= MAX_RETRIES) throw e
                delay(((e.retryAfterSec ?: 0L) * 1000L).coerceAtLeast(1000L shl attempt.coerceAtMost(4)).coerceAtMost(30_000L))
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                if (attempt >= MAX_RETRIES) throw e
                delay(1000L shl attempt.coerceAtMost(4))
            }
            attempt++
        }
    }

    private suspend fun streamOnce(provider: Provider, request: JsonObject, listener: StreamListener?): AssistantTurn =
        withContext(Dispatchers.IO) {
            val key = secure.get(provider.secret)
            if (key.isNullOrBlank()) {
                throw ApiException(401, "missing_api_key", "${provider.label} ki API key set nahi hai. Settings mein daalein.")
            }
            val base = provider.openAiBaseUrl ?: throw ApiException(0, "config", "No base URL for ${provider.name}")
            val builder = Request.Builder()
                .url("$base/chat/completions")
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .header("authorization", "Bearer $key")
            if (provider == Provider.OPENROUTER) builder.header("X-Title", "Jarvis")
            builder.post(request.toString().toRequestBody(JSON))

            val call = http.newCall(builder.build())
            val cancelHandle = coroutineContext.job.invokeOnCompletion { call.cancel() }
            try {
                call.await().use { resp ->
                    if (!resp.isSuccessful) {
                        val raw = resp.body?.string().orEmpty()
                        val err = runCatching {
                            val el = json.parseToJsonElement(raw)
                            (if (el is JsonArray) el.firstOrNull() as? JsonObject else el as? JsonObject)?.obj("error")
                        }.getOrNull()
                        throw ApiException(
                            status = resp.code,
                            type = err?.str("status") ?: err?.str("type") ?: "http_${resp.code}",
                            message = err?.str("message") ?: raw.take(300).ifBlank { "HTTP ${resp.code}" },
                            retryAfterSec = resp.header("retry-after")?.toLongOrNull(),
                        )
                    }
                    val source = resp.body?.source() ?: throw IOException("Empty response body")
                    val acc = OpenAiStreamAccumulator(listener)
                    val sse = SseParser { _, data -> acc.onData(data) }
                    while (true) {
                        coroutineContext.ensureActive()
                        val line = source.readUtf8Line() ?: break
                        sse.feedLine(line)
                    }
                    sse.finish()
                    acc.build()
                }
            } finally {
                cancelHandle.dispose()
            }
        }

    private companion object {
        const val MAX_RETRIES = 3
        val JSON = "application/json".toMediaType()
    }
}

/** Sends each request to the provider chosen in Settings. */
class RoutingLlmClient(
    private val prefs: Prefs,
    private val claude: ClaudeClient,
    private val openAi: OpenAiCompatClient,
) : LlmClient {
    private fun active(): LlmClient = if (prefs.provider == Provider.CLAUDE) claude else openAi
    override fun currentConfig() = active().currentConfig()
    override suspend fun stream(body: JsonObject, listener: StreamListener?) = active().stream(body, listener)
}
