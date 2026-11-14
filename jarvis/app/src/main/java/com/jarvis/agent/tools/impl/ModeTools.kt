package com.jarvis.agent.tools.impl

import com.jarvis.agent.brain.bool
import com.jarvis.agent.brain.str
import com.jarvis.agent.driving.DrivingMode
import com.jarvis.agent.tools.BaseTool
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.ToolContext
import com.jarvis.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

class SetModeTool : BaseTool(
    "set_mode",
    "Turn a mode on or off. driving: read new messages aloud, silence calls. busy: silence calls (meeting).",
    RiskLevel.LOW,
    schemaBlock = {
        string("mode", "Which mode", enum = listOf("driving", "busy"))
        boolean("on", "true = on")
    },
) {
    override fun describe(input: JsonObject) = "${input.str("mode")} mode ${if (input.bool("on") == true) "on" else "off"}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val on = input.bool("on") == true
        when (input.str("mode")) {
            "driving" -> DrivingMode.set(on)
            else -> ctx.graph.prefs.busyMode = on
        }
        return ToolResult.ok(describe(input) + ".")
    }
}
