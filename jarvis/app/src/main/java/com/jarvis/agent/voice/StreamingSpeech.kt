package com.jarvis.agent.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Splits streamed text into complete sentences (pure logic, unit tested). */
object SentenceSplitter {
    private const val ENDS = ".!?۔؟\n"

    /** Removes and returns the complete sentences at the start of [buf]; the unfinished tail stays in it. */
    fun take(buf: StringBuilder): List<String> {
        var cut = -1
        for (i in buf.indices) {
            val c = buf[i]
            if (c in ENDS) {
                val next = buf.getOrNull(i + 1)
                // "3.5" or "e.g." mid-word is not a sentence end; a following space/newline is.
                if (c == '\n' || (next != null && next.isWhitespace())) cut = i
            }
        }
        if (cut < 0) return emptyList()
        val done = buf.substring(0, cut + 1)
        buf.delete(0, cut + 1)
        return done.split(Regex("(?<=[.!?۔؟\\n])\\s+")).map { it.trim() }.filter { it.length > 1 }
    }
}

/**
 * Speaks a model turn sentence by sentence while it is still streaming, so the first words are heard
 * about a second after the model starts answering instead of after the whole reply. Text that comes
 * before a tool call (narration) is dropped once a tool call starts.
 */
class StreamingSpeaker(scope: CoroutineScope, private val speak: suspend (String) -> Unit) {
    private val buf = StringBuilder()
    private val queue = Channel<String>(Channel.UNLIMITED)
    private var toolStarted = false

    @Volatile var spokeAnything = false
        private set

    private val job = scope.launch { for (s in queue) speak(s) }

    fun onDelta(text: String) {
        if (toolStarted) return
        buf.append(text)
        for (s in SentenceSplitter.take(buf)) {
            spokeAnything = true
            queue.trySend(s)
        }
    }

    fun onToolUseStart() {
        toolStarted = true
        buf.setLength(0)
    }

    /** End of a final answer: speak what is left and wait until speech has finished. */
    suspend fun finishAndWait() {
        if (!toolStarted) {
            val rest = buf.toString().trim()
            if (rest.length > 1) {
                spokeAnything = true
                queue.trySend(rest)
            }
        }
        buf.setLength(0)
        queue.close()
        job.join()
    }

    /** End of a turn that called tools: stop speaking any queued narration. */
    fun abandon() {
        queue.close()
        job.cancel()
    }
}

/** Commands handled locally, without the AI (instant and free). */
object VoiceCommands {
    private val stop = Regex(
        "^\\s*(jarvis[,\\s]*)?(stop|stop it|cancel|enough|quiet|shut up|ruko|ruk jao|ruk|bas|bas karo|band karo|chup|" +
            "chup karo|khamosh|cancel karo|rehne do|رکو|رک جاؤ|بس|بند کرو|چپ)[\\s.!۔]*$",
        RegexOption.IGNORE_CASE,
    )

    fun isStop(text: String): Boolean = stop.matches(text.trim())
}
