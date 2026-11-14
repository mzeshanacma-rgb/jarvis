package com.jarvis.agent.home

import com.jarvis.agent.JarvisApp
import com.jarvis.agent.brain.obj
import com.jarvis.agent.brain.str
import com.jarvis.agent.core.SecureStore
import com.jarvis.agent.core.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Talks to the user's Home Assistant (lights, fans, AC, plugs, curtains, locks, scenes). */
object HomeAssistant {
    private val json = Json { ignoreUnknownKeys = true }
    val controllable = setOf("light", "switch", "fan", "climate", "cover", "lock", "media_player", "scene", "script", "input_boolean", "vacuum")

    /** Locks, doors/garage covers and alarms affect physical security, so they need a fingerprint. */
    fun isSecuritySensitive(entityId: String, action: String): Boolean {
        val domain = entityId.substringBefore('.')
        return domain == "lock" || domain == "alarm_control_panel" ||
            (domain == "cover" && Regex("door|garage|gate", RegexOption.IGNORE_CASE).containsMatchIn(entityId) && action in setOf("open", "toggle"))
    }

    /** Maps a friendly action to a Home Assistant service call (pure, unit tested). */
    fun service(entityId: String, action: String): Pair<String, String> {
        val domain = entityId.substringBefore('.')
        val svc = when (action) {
            "on" -> if (domain == "scene" || domain == "script") "turn_on" else "turn_on"
            "off" -> "turn_off"
            "toggle" -> "toggle"
            "open" -> "open_cover"
            "close" -> "close_cover"
            "lock" -> "lock"
            "unlock" -> "unlock"
            "set_brightness" -> "turn_on"
            "set_temperature" -> "set_temperature"
            else -> action
        }
        return domain to svc
    }

    private fun base(): Pair<String, String> {
        val g = JarvisApp.graph
        val url = g.prefs.homeAssistantUrl.ifBlank { throw IllegalStateException("Home Assistant is not set up (Settings → Advanced → Smart home).") }
        val token = g.secure.get(SecureStore.Secret.HOME_ASSISTANT_TOKEN) ?: throw IllegalStateException("Home Assistant token missing.")
        return url to token
    }

    suspend fun states(): List<JsonObject> {
        val (url, token) = base()
        val req = Request.Builder().url("$url/api/states").header("Authorization", "Bearer $token").build()
        return JarvisApp.graph.http.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Home Assistant HTTP ${resp.code}")
            (json.parseToJsonElement(resp.body?.string().orEmpty()) as JsonArray).mapNotNull { it as? JsonObject }
                .filter { it.str("entity_id")?.substringBefore('.') in controllable }
        }
    }

    suspend fun call(entityId: String, action: String, value: Double?): String {
        val (url, token) = base()
        val (domain, svc) = service(entityId, action)
        val body = buildString {
            append("{\"entity_id\":\"").append(entityId.replace("\"", "")).append('"')
            if (action == "set_brightness" && value != null) append(",\"brightness_pct\":").append(value.toInt().coerceIn(0, 100))
            if (action == "set_temperature" && value != null) append(",\"temperature\":").append(value)
            append('}')
        }
        val req = Request.Builder().url("$url/api/services/$domain/$svc")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        JarvisApp.graph.http.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Home Assistant HTTP ${resp.code}: ${resp.body?.string()?.take(150)}")
        }
        return "$domain.$svc on $entityId done"
    }

    fun describe(state: JsonObject): String =
        "${state.str("entity_id")} \"${state.obj("attributes")?.str("friendly_name").orEmpty()}\": ${state.str("state")}"
}
