package com.jarvis.agent

import com.jarvis.agent.brain.AssistantTurn
import com.jarvis.agent.brain.LlmClient
import com.jarvis.agent.brain.ModelConfig
import com.jarvis.agent.brain.OneShot
import com.jarvis.agent.brain.StreamListener
import com.jarvis.agent.calls.CallRules
import com.jarvis.agent.core.Privacy
import com.jarvis.agent.driving.DrivingMode
import com.jarvis.agent.expenses.Expense
import com.jarvis.agent.expenses.ExpenseCategories
import com.jarvis.agent.expenses.Periods
import com.jarvis.agent.expenses.ReceiptReader
import com.jarvis.agent.expenses.expensesCsv
import com.jarvis.agent.home.HomeAssistant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CallRulesTest {
    @Test
    fun busyModeSilencesAndTextsOnlyKnownContacts() {
        val known = CallRules.decide("Ali", busy = true, busySms = true, announce = true, silenceUnknown = false)
        assertTrue(known.silence); assertEquals(CallRules.BUSY_SMS, known.smsReply); assertNull(known.announce)
        val unknown = CallRules.decide(null, busy = true, busySms = true, announce = true, silenceUnknown = false)
        assertTrue(unknown.silence); assertNull("never text unknown numbers", unknown.smsReply)
    }

    @Test
    fun announcesOrSilencesUnknown() {
        assertEquals("Ali is calling", CallRules.decide("Ali", false, false, true, false).announce)
        assertFalse(CallRules.decide("Ali", false, false, true, true).silence)
        assertTrue(CallRules.decide(null, false, false, true, true).silence)
        val quiet = CallRules.decide("Ali", false, false, false, false)
        assertFalse(quiet.silence); assertNull(quiet.announce)
    }
}

class DrivingTest {
    @Test
    fun autoReplyOncePerConversationPer30Minutes() {
        val t = 1_000_000L
        assertTrue(DrivingMode.shouldAutoReply("Ammi-test", t))
        assertFalse(DrivingMode.shouldAutoReply("Ammi-test", t + 10 * 60_000))
        assertTrue(DrivingMode.shouldAutoReply("Ali-test", t + 10 * 60_000))
        assertTrue(DrivingMode.shouldAutoReply("Ammi-test", t + 31 * 60_000))
        assertTrue(DrivingMode.isMessaging("com.whatsapp"))
        assertFalse(DrivingMode.isMessaging("com.android.chrome"))
    }
}

class SmartHomeTest {
    @Test
    fun locksAndDoorsNeedFingerprintLightsDoNot() {
        assertTrue(HomeAssistant.isSecuritySensitive("lock.front_door", "unlock"))
        assertTrue(HomeAssistant.isSecuritySensitive("cover.garage_door", "open"))
        assertFalse(HomeAssistant.isSecuritySensitive("cover.bedroom_curtain", "open"))
        assertFalse(HomeAssistant.isSecuritySensitive("light.bedroom", "on"))
        assertFalse(HomeAssistant.isSecuritySensitive("climate.living_ac", "set_temperature"))
    }

    @Test
    fun mapsActionsToServices() {
        assertEquals("light" to "turn_on", HomeAssistant.service("light.kitchen", "on"))
        assertEquals("cover" to "open_cover", HomeAssistant.service("cover.curtain", "open"))
        assertEquals("climate" to "set_temperature", HomeAssistant.service("climate.ac", "set_temperature"))
        assertEquals("lock" to "unlock", HomeAssistant.service("lock.door", "unlock"))
    }
}

class ExpensesTest {
    private val noLlm = OneShot(object : LlmClient {
        override fun currentConfig() = ModelConfig("x", "low", false, isClaude = false)
        override suspend fun stream(body: JsonObject, listener: StreamListener?) =
            AssistantTurn(JsonArray(emptyList()), "", emptyList(), "end_turn", null)
    })

    @Test
    fun parsesReceiptJson() {
        val e = ReceiptReader(noLlm).fromJson(
            Json.parseToJsonElement("""{"merchant":"Imtiaz","total":"Rs. 4,560.50","currency":"pkr","date":"2026-11-10","category":"groceries","note":"weekly"}""").jsonObject,
        )
        assertEquals("Imtiaz", e.merchant)
        assertEquals(4560.50, e.amount, 0.001)
        assertEquals("PKR", e.currency)
        assertEquals("Groceries", e.category)
        val c = Calendar.getInstance().apply { timeInMillis = e.date }
        assertEquals(10, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun findsJsonInsideProse() {
        assertEquals("""{"a":1}""", noLlm.jsonIn("Sure! ```json\n{\"a\":1}\n``` done"))
        assertNull(noLlm.jsonIn("no json here"))
    }

    @Test
    fun categoriesAndPeriods() {
        assertEquals("Fuel", ExpenseCategories.normalize("Petrol pump"))
        assertEquals("Transport", ExpenseCategories.normalize("Careem ride"))
        assertEquals("Other", ExpenseCategories.normalize(null))
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 14, 15, 0) }.timeInMillis
        val (from, to) = Periods.range("this_month", now)
        val f = Calendar.getInstance().apply { timeInMillis = from }
        assertEquals(1, f.get(Calendar.DAY_OF_MONTH)); assertEquals(Calendar.NOVEMBER, f.get(Calendar.MONTH))
        assertTrue(now in from until to)
        val (lf, lt) = Periods.range("last_month", now)
        assertEquals(from, lt); assertTrue(lf < lt)
    }

    @Test
    fun csvEscapesQuotes() {
        val csv = expensesCsv(listOf(Expense(merchant = "Ali's \"Cafe\"", amount = 500.0, category = "Food", date = 0L)))
        assertTrue(csv.lines()[1].contains("\"Ali's \"\"Cafe\"\"\""))
    }
}

class PrivacyTest {
    @Test
    fun privateAppsMatchPackageAndSubpackages() {
        val list = setOf("com.hbl.android.hblmobilebanking", "com.techlogix.mobilinkcustomer")
        assertTrue(Privacy.isPrivate("com.techlogix.mobilinkcustomer", list))
        assertTrue(Privacy.isPrivate("com.hbl.android.hblmobilebanking.debug", list))
        assertFalse(Privacy.isPrivate("com.whatsapp", list))
        assertFalse(Privacy.isPrivate(null, list))
    }
}
