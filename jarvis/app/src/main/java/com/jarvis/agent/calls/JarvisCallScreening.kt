package com.jarvis.agent.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.SmsManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.core.Notifications
import com.jarvis.agent.tools.impl.Contacts
import kotlinx.coroutines.launch

/** What to do with an incoming call (pure, unit tested). */
data class CallDecision(val silence: Boolean, val announce: String?, val smsReply: String?, val note: String?)

object CallRules {
    const val BUSY_SMS = "I'm busy right now and will call you back soon. (Sent by Jarvis)"

    fun decide(
        callerName: String?,
        busy: Boolean,
        busySms: Boolean,
        announce: Boolean,
        silenceUnknown: Boolean,
    ): CallDecision {
        val known = !callerName.isNullOrBlank()
        return when {
            busy -> CallDecision(
                silence = true,
                announce = null,
                smsReply = if (busySms && known) BUSY_SMS else null,
                note = "Silenced a call from ${callerName ?: "an unknown number"} (busy mode)",
            )
            !known && silenceUnknown -> CallDecision(true, null, null, "Silenced a call from an unknown number")
            announce -> CallDecision(false, if (known) "$callerName is calling" else "Unknown number calling", null, null)
            else -> CallDecision(false, null, null, null)
        }
    }
}

/**
 * Call assistant (Android 10+, once Jarvis holds the call-screening role): announces who is calling,
 * silences calls in busy/driving mode (optionally texting "will call back" to known contacts) and can
 * silence unknown numbers. Android does not let apps hear or answer the call audio itself.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class JarvisCallScreening : CallScreeningService() {

    @SuppressLint("MissingPermission")
    override fun onScreenCall(details: Call.Details) {
        val g = JarvisApp.graph
        val incoming = details.callDirection == Call.Details.DIRECTION_INCOMING
        if (!incoming) {
            respondToCall(details, CallResponse.Builder().build())
            return
        }
        val number = details.handle?.schemeSpecificPart.orEmpty()
        val name = runCatching { Contacts.nameForNumber(this, number) }.getOrNull()
        val p = g.prefs
        val d = CallRules.decide(name, p.busyMode, p.busyAutoSms, p.announceCalls, p.silenceUnknownCallers)
        respondToCall(details, CallResponse.Builder().setSilenceCall(d.silence).build())

        d.announce?.let { text -> g.appScope.launch { runCatching { g.voice.speak(text) } } }
        d.note?.let {
            g.actionLog.log(0L, "TOOL", it, toolName = "call_assistant", risk = if (d.smsReply != null) "MEDIUM" else "LOW")
            Notifications.simpleAlert(this, Notifications.ID_ROUTINE_BASE + 900, "Jarvis", "$it ($number)")
        }
        if (d.smsReply != null && number.isNotBlank() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java)!!
                else @Suppress("DEPRECATION") SmsManager.getDefault()
                sms.sendTextMessage(number, null, d.smsReply, null, null)
                g.actionLog.log(0L, "TOOL", "Busy auto-SMS to ${name ?: number}", toolName = "call_assistant", risk = "MEDIUM", outcome = "sent (user opted in)")
            }
        }
    }
}
