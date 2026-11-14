package com.jarvis.agent.core

import com.jarvis.agent.JarvisApp

/** Enforces the user's privacy choices in code, whatever the AI decides. */
object Privacy {
    /** One-tap presets for the private-apps list. */
    val bankingPreset = listOf(
        "com.techlogix.mobilinkcustomer", "pk.com.telenor.phoenix", "com.hbl.android.hblmobilebanking",
        "com.mcb.mcblive", "com.meezanbank.meezanmobile", "com.ubl.digital", "com.bankalfalah.alfamobile",
        "com.sadapay.app", "com.nayapay.app", "com.google.android.apps.walletnfcrel", "com.paypal.android.p2pmobile",
    )

    fun isPrivate(packageName: String?, list: Set<String> = JarvisApp.graph.prefs.privateApps): Boolean =
        packageName != null && list.any { packageName == it || packageName.startsWith("$it.") }

    const val BLOCKED_MESSAGE =
        "PRIVATE_APP: the user marked this app as private, so Jarvis may not read or operate it. " +
            "Use hand_off_to_user and ask the user to do this step themselves."

    val incognito: Boolean get() = JarvisApp.graph.prefs.incognito

    /** Deletes history, memories, routines, expenses and the activity log. Keys and settings are kept. */
    suspend fun deleteAllData() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val g = JarvisApp.graph
        g.memoryDb.clearAllTables()
        g.expenseDb.clearAllTables()
        g.actionLog.clear()
        runCatching { androidx.work.WorkManager.getInstance(g.context).cancelAllWorkByTag("routine") }
        runCatching { androidx.work.WorkManager.getInstance(g.context).cancelAllWorkByTag("reminder") }
        java.io.File(g.context.filesDir, "meetings").deleteRecursively()
    }
}
