package com.jarvis.agent

import com.jarvis.agent.brain.ApiException
import com.jarvis.agent.brain.ModelConfig
import com.jarvis.agent.brain.OpenAiConverter
import com.jarvis.agent.brain.OpenAiStreamAccumulator
import com.jarvis.agent.brain.Provider
import com.jarvis.agent.brain.RequestFactory
import com.jarvis.agent.brain.arr
import com.jarvis.agent.brain.obj
import com.jarvis.agent.brain.str
import com.jarvis.agent.tools.ToolCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatTest {

    private fun run(vararg chunks: String): com.jarvis.agent.brain.AssistantTurn {
        val acc = OpenAiStreamAccumulator()
        chunks.forEach(acc::onData)
        acc.onData("[DONE]")
        return acc.build()
    }

    @Test
    fun parsesOpenAiStyleToolCallSplitAcrossChunks() {
        val turn = run(
            """{"choices":[{"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"launch_app","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"app\": \"What"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"sApp\"}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )
        assertEquals("tool_use", turn.stopReason)
        assertEquals(1, turn.toolCalls.size)
        assertEquals("call_abc", turn.toolCalls[0].id)
        assertEquals("WhatsApp", turn.toolCalls[0].input.str("app"))
        assertNull(turn.toolCalls[0].parseError)
    }

    @Test
    fun geminiStyleParallelCallsWithoutIdsAndStopReason() {
        val turn = run(
            """{"choices":[{"delta":{"role":"assistant","tool_calls":[{"index":0,"function":{"name":"read_screen","arguments":"{}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"stop"}]}""",
        )
        assertEquals(2, turn.toolCalls.size)
        assertEquals(listOf("read_screen", "get_time"), turn.toolCalls.map { it.name })
        assertEquals(2, turn.toolCalls.map { it.id }.toSet().size) // generated ids are unique
        assertTrue(turn.toolCalls.all { it.id.isNotBlank() })
        assertEquals("tool_use", turn.stopReason) // provider said "stop" but tools were called
    }

    @Test
    fun textOnlyAndFinishReasons() {
        val t = run(
            """{"choices":[{"delta":{"content":"Ho "}}]}""",
            """{"choices":[{"delta":{"content":"gaya."},"finish_reason":"stop"}]}""",
        )
        assertEquals("Ho gaya.", t.text)
        assertEquals("end_turn", t.stopReason)
        assertEquals("max_tokens", run("""{"choices":[{"delta":{"content":"x"},"finish_reason":"length"}]}""").stopReason)
        assertEquals("refusal", run("""{"choices":[{"delta":{},"finish_reason":"content_filter"}]}""").stopReason)
    }

    @Test
    fun brokenToolJsonIsFlagged() {
        val t = run(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"send_sms","arguments":"{\"to\": \"Al"}}]},"finish_reason":"length"}]}""",
        )
        assertNotNull(t.toolCalls[0].parseError)
    }

    @Test(expected = ApiException::class)
    fun errorChunkThrows() {
        run("""{"error":{"code":429,"message":"quota","status":"RESOURCE_EXHAUSTED"}}""")
    }

    // ---------------------------------------------------------------- request conversion

    private val messages = listOf(
        RequestFactory.userMessage(listOf(RequestFactory.textBlock("<context>…</context>"), RequestFactory.textBlock("kya time hua hai"))),
        RequestFactory.assistantMessage(
            JsonArray(
                listOf(
                    RequestFactory.textBlock("ok"),
                    kotlinx.serialization.json.buildJsonObject {
                        put("type", JsonPrimitive("tool_use")); put("id", JsonPrimitive("t1")); put("name", JsonPrimitive("get_time"))
                        put("input", JsonObject(emptyMap()))
                    },
                ),
            ),
        ),
        RequestFactory.userMessage(listOf(RequestFactory.toolResult("t1", "Tuesday 12:00", false, "QUJD"))),
    )

    private fun body(provider: Provider = Provider.GEMINI) = OpenAiConverter.toChatRequest(
        RequestFactory.build(
            ModelConfig("x", "medium", directApi = false, isClaude = false), "SYSTEM",
            ToolCatalog.build().apiDefinitions(false), messages,
        ),
        provider, "gemini-2.5-flash",
    )

    @Test
    fun convertsMessagesInProviderOrder() {
        val msgs = body().arr("messages")!!.map { it as JsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool", "user"), msgs.map { it.str("role") })
        assertEquals("SYSTEM", msgs[0].str("content"))
        // assistant: text + tool call with the original id
        val call = msgs[2].arr("tool_calls")!![0] as JsonObject
        assertEquals("t1", call.str("id"))
        assertEquals("get_time", call.obj("function")?.str("name"))
        // tool result keeps the id; its image follows as a user message with a data URI
        assertEquals("t1", msgs[3].str("tool_call_id"))
        assertTrue(msgs[4].toString().contains("data:image/jpeg;base64,QUJD"))
    }

    @Test
    fun imagesAreDroppedForTextOnlyProviders() {
        val msgs = body(Provider.GROQ).arr("messages")!!.map { it as JsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool"), msgs.map { it.str("role") })
        assertTrue(msgs[3].str("content")!!.contains("screenshot omitted"))
        assertFalse(msgs.toString().contains("base64"))
    }

    @Test
    fun toolSchemasAreCleanedForFreeProviders() {
        val b = body()
        assertEquals("true", b.str("stream"))
        val tools = b.arr("tools")!!.map { it as JsonObject }
        assertEquals(ToolCatalog.build().all.size, tools.size)
        for (t in tools) {
            val fn = t.obj("function")!!
            val params = fn.obj("parameters")
            if (params != null) {
                assertFalse(fn.str("name") + " has additionalProperties", params.toString().contains("additionalProperties"))
                assertTrue(params.obj("properties")!!.isNotEmpty())
            }
        }
        // a tool without parameters has no `parameters` at all
        assertNull(tools.first { it.obj("function")?.str("name") == "get_time" }.obj("function")?.get("parameters"))
        assertNull(b["thinking"]); assertNull(b["output_config"]); assertNull(b["fallbacks"])
    }

    @Test
    fun nonClaudeConfigPrunesOldScreenshots() {
        val c = ModelConfig("gemini-2.5-flash", "medium", directApi = false, isClaude = false)
        assertTrue(c.canPruneHistory)
        assertFalse(c.thinkingBetweenTools)
        assertFalse(c.supportsDefaultFallbacks)
        // a Claude model name on a non-Claude provider must not trigger Claude-only request fields
        assertFalse(ModelConfig("claude-sonnet-5-5", "high", directApi = true, isClaude = false).thinkingBetweenTools)
    }
}
