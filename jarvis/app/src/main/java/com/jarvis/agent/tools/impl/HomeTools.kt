package com.jarvis.agent.tools.impl

import com.jarvis.agent.brain.double
import com.jarvis.agent.brain.str
import com.jarvis.agent.home.HomeAssistant
import com.jarvis.agent.tools.BaseTool
import com.jarvis.agent.tools.RiskAssessment
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.ToolContext
import com.jarvis.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

class SmartHomeDevicesTool : BaseTool(
    "smart_home_devices",
    "List smart-home devices from Home Assistant (lights, fans, AC, plugs, curtains, locks, scenes) and their state. " +
        "If Home Assistant isn't set up, the user's vendor app (Smart Life, Google Home…) can be driven with the screen tools.",
    RiskLevel.LOW, readOnly = true,
    { string("filter", "Optional part of the name, e.g. 'bedroom'", required = false) },
) {
    override fun describe(input: JsonObject) = "List smart-home devices"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val f = input.str("filter")?.lowercase()
        val list = HomeAssistant.states().map(HomeAssistant::describe).filter { f == null || it.lowercase().contains(f) }
        return ToolResult.untrusted("home assistant", list.take(80).joinToString("\n").ifEmpty { "No devices." })
    }
}

class SmartHomeControlTool : BaseTool(
    "smart_home_control",
    "Control a Home Assistant device by entity_id (from smart_home_devices). Locks, doors and garages need a fingerprint.",
    RiskLevel.LOW,
    schemaBlock = {
        string("entity_id", "e.g. light.bedroom, climate.living_room_ac, lock.front_door")
        string("action", "What to do", enum = listOf("on", "off", "toggle", "open", "close", "lock", "unlock", "set_brightness", "set_temperature"))
        number("value", "Brightness percent or temperature", required = false)
    },
) {
    override fun describe(input: JsonObject) = "${input.str("action")} ${input.str("entity_id")}"
    override suspend fun assess(input: JsonObject, ctx: ToolContext): RiskAssessment {
        val sensitive = HomeAssistant.isSecuritySensitive(input.str("entity_id").orEmpty(), input.str("action").orEmpty())
        return RiskAssessment(if (sensitive) RiskLevel.HIGH else RiskLevel.LOW, "Smart home: ${describe(input)}")
    }

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
        ToolResult.ok(HomeAssistant.call(input.str("entity_id").orEmpty(), input.str("action").orEmpty(), input.double("value")))
}
