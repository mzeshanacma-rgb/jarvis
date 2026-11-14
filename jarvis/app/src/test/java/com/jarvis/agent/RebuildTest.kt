package com.jarvis.agent

import com.jarvis.agent.accessibility.ScreenSnapshot
import com.jarvis.agent.accessibility.UiNode
import com.jarvis.agent.brain.ToolRouting
import com.jarvis.agent.safety.UiRiskClassifier
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.ToolCatalog
import com.jarvis.agent.voice.SentenceSplitter
import com.jarvis.agent.voice.VoiceCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRoutingTest {
    private val registry = ToolCatalog.build()

    @Test
    fun everyToolIsInExactlyOneGroupAndExists() {
        val all = ToolRouting.groups.values.flatten()
        assertEquals("a tool is listed in two groups", all.size, all.toSet().size)
        val names = registry.all.map { it.name }.toSet()
        assertEquals("routing and registry disagree", names, all.toSet())
    }

    @Test
    fun picksGroupsFromWords() {
        assertEquals(setOf("core"), ToolRouting.groupsFor("open youtube"))
        assertTrue("communication" in ToolRouting.groupsFor("Ammi ko message bhej do"))
        assertTrue("calendar" in ToolRouting.groupsFor("kal subah 7 baje alarm"))
        assertTrue("calendar" in ToolRouting.groupsFor("remind me at 5"))
        assertTrue("device" in ToolRouting.groupsFor("phone silent kar do"))
        assertTrue("web" in ToolRouting.groupsFor("what is the weather in Lahore"))
        val briefing = ToolRouting.groupsFor("Give me my briefing")
        assertTrue("calendar" in briefing && "notifications" in briefing)
    }

    @Test
    fun coreSubsetIsMuchSmallerThanEverything() {
        val core = registry.apiDefinitions(false, ToolRouting.toolNames(setOf("core"))).size
        assertTrue("core=$core of ${registry.all.size}", core in 10..25 && core < registry.all.size / 2)
        assertTrue(ToolRouting.toolNames(setOf("core")).contains("enable_tools"))
    }
}

class StreamingSpeechTest {
    @Test
    fun splitsCompleteSentencesOnly() {
        val buf = StringBuilder("Theek hai. Main ne alarm laga di")
        assertEquals(listOf("Theek hai."), SentenceSplitter.take(buf))
        assertEquals(" Main ne alarm laga di", buf.toString())
        buf.append("۔ Aur kuch?")
        assertEquals(listOf("Main ne alarm laga di۔"), SentenceSplitter.take(buf))
        assertEquals(" Aur kuch?", buf.toString()) // no trailing space yet → still open
    }

    @Test
    fun doesNotBreakNumbers() {
        val buf = StringBuilder("It costs 3.5 dollars")
        assertTrue(SentenceSplitter.take(buf).isEmpty())
    }

    @Test
    fun stopCommandsAreRecognised() {
        listOf("stop", "Stop!", "ruko", "ruk jao", "bas", "Jarvis, stop", "band karo", "رکو", "chup").forEach {
            assertTrue(it, VoiceCommands.isStop(it))
        }
        listOf("stop the music on spotify", "don't stop", "bas stop pe utaar do", "send it").forEach {
            assertFalse(it, VoiceCommands.isStop(it))
        }
    }
}

class ScreenMatchTest {
    private fun n(id: Int, label: String, x: Int, y: Int) =
        UiNode(id, "Button", label, null, null, null, x - 50, y - 20, x + 50, y + 20, clickable = true)

    @Test
    fun findsTheSameElementAfterIdsShift() {
        val seen = ScreenSnapshot("p", "App", 1080, 2400, listOf(n(1, "Back", 50, 100), n(2, "Send", 900, 2200)))
        // a new notification row was inserted, so "Send" is now id 3
        val fresh = ScreenSnapshot("p", "App", 1080, 2400, listOf(n(1, "Back", 50, 100), n(2, "New message", 500, 300), n(3, "Send", 900, 2205)))
        assertEquals(3, fresh.match(seen.node(2)!!)?.id)
    }

    @Test
    fun refusesWhenTheElementMovedOrVanished() {
        val seen = n(2, "Send", 900, 2200)
        assertNull(ScreenSnapshot("p", "App", 1080, 2400, listOf(n(1, "Send", 100, 400))).match(seen))
        assertNull(ScreenSnapshot("p", "App", 1080, 2400, listOf(n(1, "Delete", 900, 2200))).match(seen))
    }
}

class CalmerConfirmationsTest {
    @Test
    fun everydayButtonsDoNotAskButRiskyOnesStillDo() {
        listOf("Allow", "Accept", "Update", "Join", "Confirm", "Follow", "OK").forEach {
            assertEquals(it, RiskLevel.LOW, UiRiskClassifier.classifyTap(it, "com.example", "").level)
        }
        assertEquals(RiskLevel.MEDIUM, UiRiskClassifier.classifyTap("Send", "com.whatsapp", "").level)
        assertEquals(RiskLevel.MEDIUM, UiRiskClassifier.classifyTap("Install", "com.android.vending", "").level)
        assertEquals(RiskLevel.HIGH, UiRiskClassifier.classifyTap("Pay now", "com.example", "").level)
        assertEquals(RiskLevel.HIGH, UiRiskClassifier.classifyTap("Delete", "com.example", "").level)
    }
}
