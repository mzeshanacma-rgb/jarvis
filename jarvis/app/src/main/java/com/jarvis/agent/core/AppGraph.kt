package com.jarvis.agent.core

import android.content.Context
import com.jarvis.agent.brain.AgentLoop
import com.jarvis.agent.brain.ClaudeClient
import com.jarvis.agent.brain.LlmClient
import com.jarvis.agent.brain.OpenAiCompatClient
import com.jarvis.agent.brain.RoutingLlmClient
import com.jarvis.agent.brain.MemoryProvider
import com.jarvis.agent.brain.NoMemory
import com.jarvis.agent.brain.RateLimiter
import com.jarvis.agent.log.ActionLogDatabase
import com.jarvis.agent.log.ActionLogger
import com.jarvis.agent.memory.MemoryDatabase
import com.jarvis.agent.memory.MemoryManager
import com.jarvis.agent.routines.RoutineScheduler
import com.jarvis.agent.tools.impl.Contacts
import kotlinx.coroutines.launch
import com.jarvis.agent.safety.AndroidConfirmer
import com.jarvis.agent.safety.ConfirmationBroker
import com.jarvis.agent.tools.ToolCatalog
import com.jarvis.agent.tools.ToolRegistry
import com.jarvis.agent.voice.AndroidSpeechInput
import com.jarvis.agent.voice.AndroidTts
import com.jarvis.agent.voice.ElevenLabsTts
import com.jarvis.agent.voice.SwitchingSpeechOutput
import com.jarvis.agent.voice.VoiceIO
import com.jarvis.agent.voice.WhisperSpeechInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-written dependency graph (no DI framework needed for an app this size). */
class AppGraph(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Prefs(context)
    val secure = SecureStore(context)
    val http = Http.newClient()
    val tasks = TaskTracker()

    val voice: VoiceIO by lazy {
        val androidTts = AndroidTts(context)
        VoiceIO(
            prefs = prefs,
            androidStt = AndroidSpeechInput(context),
            whisperStt = WhisperSpeechInput(http, secure),
            tts = SwitchingSpeechOutput(prefs, androidTts, ElevenLabsTts(context, http, secure, prefs, androidTts)),
        )
    }

    val actionLog: ActionLogger by lazy { ActionLogger(ActionLogDatabase.create(context).dao(), appScope) }
    val claude: LlmClient by lazy {
        RoutingLlmClient(prefs, ClaudeClient(http, prefs, secure), OpenAiCompatClient(http, prefs, secure))
    }
    val tools: ToolRegistry by lazy { ToolCatalog.build() }
    val oneShot by lazy { com.jarvis.agent.brain.OneShot(claude) }
    val confirmer by lazy { AndroidConfirmer(context, voice) }

    val memoryDb: MemoryDatabase by lazy { MemoryDatabase.create(context) }
    val expenseDb by lazy { com.jarvis.agent.expenses.ExpenseDatabase.create(context) }
    val memoryManager: MemoryManager by lazy { MemoryManager(memoryDb.dao()) }
    val routineScheduler: RoutineScheduler by lazy { RoutineScheduler(context) { memoryDb.dao() } }

    /** Long-term memory used by the agent loop (Room-backed). */
    @Volatile var memory: MemoryProvider = NoMemory

    val commandHandler: CommandHandler by lazy {
        AgentLoop(
            graph = this,
            claude = claude,
            registry = tools,
            confirmer = confirmer,
            log = actionLog,
            voice = voice,
            memory = { memory },
            rateLimiter = RateLimiter(maxPerWindow = { prefs.maxRequestsPerMinute }),
        )
    }

    /** Sends a tiny request through the same path real tasks use and returns a human-readable result. */
    suspend fun testAi(): String {
        val cfg = claude.currentConfig()
        val body = com.jarvis.agent.brain.RequestFactory.build(
            cfg, "Reply with the single word OK.", kotlinx.serialization.json.JsonArray(emptyList()),
            listOf(com.jarvis.agent.brain.RequestFactory.userMessage(listOf(com.jarvis.agent.brain.RequestFactory.textBlock("ping")))),
            maxTokens = 64,
        )
        val p = prefs.provider
        return try {
            val turn = kotlinx.coroutines.withTimeout(40_000L) { claude.stream(body) }
            "✓ ${p.label} chal raha hai (model: ${cfg.model}). Jawab: ${turn.text.trim().take(60).ifEmpty { "(khali)" }}"
        } catch (e: com.jarvis.agent.brain.ApiException) {
            "✗ ${p.label} (model: ${cfg.model}) — HTTP ${e.status} ${e.type}: ${e.message?.take(300)}"
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            "✗ 40 second mein jawab nahi aaya (internet ya model slow hai)."
        } catch (e: Exception) {
            "✗ ${e.javaClass.simpleName}: ${e.message?.take(200)}"
        }
    }

    init {
        KillSwitch.addListener { reason ->
            tasks.cancelAll()
            ConfirmationBroker.denyAll()
            voice.stopSpeaking()
            actionLog.log(0L, "KILL", "Kill switch: $reason")
        }
        actionLog.pruneOlderThanDays(30)
        memory = memoryManager
        Contacts.nicknameResolver = { name -> memoryManager.resolveNickname(name) }
        appScope.launch { runCatching { routineScheduler.rescheduleAll() } }
    }
}
