package com.jarvis.agent.tools.impl

import android.Manifest
import com.jarvis.agent.meeting.MeetingRecorder
import com.jarvis.agent.meeting.MeetingSummarizer
import com.jarvis.agent.tools.BaseTool
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.ToolContext
import com.jarvis.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

class StartMeetingRecordingTool : BaseTool(
    "start_meeting_recording",
    "Start recording a meeting (up to 60 minutes) to make a summary, decisions and action items afterwards. " +
        "Remind the user that everyone present should agree to being recorded.",
    RiskLevel.LOW,
) {
    override fun describe(input: JsonObject) = "Start recording the meeting"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        Contacts.require(ctx.app, Manifest.permission.RECORD_AUDIO)
        if (MeetingRecorder.state.value.recording) return ToolResult.ok("Already recording.")
        MeetingRecorder.start(ctx.app)
        return ToolResult.ok("Recording started. Say \"stop recording\" (or use the Meeting screen) to finish and get the summary.")
    }
}

class StopMeetingRecordingTool : BaseTool(
    "stop_meeting_recording",
    "Stop the meeting recording and return its summary, decisions and action items (needs a Gemini key).",
    RiskLevel.LOW,
) {
    override val timeoutMs = 180_000L
    override fun describe(input: JsonObject) = "Stop recording and summarise"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val f = MeetingRecorder.stop() ?: return ToolResult.error("No meeting is being recorded.")
        val summary = MeetingSummarizer.summarize(f)
        // Only the summary part goes back to the model; the full transcript stays in the Meeting screen.
        val short = summary.substringBefore("TRANSCRIPT").take(3000)
        return ToolResult.untrusted("meeting recording", "Saved. $short\n(Offer to create reminders for the action items.)")
    }
}
