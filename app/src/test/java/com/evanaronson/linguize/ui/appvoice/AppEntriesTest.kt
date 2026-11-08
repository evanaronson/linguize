package com.evanaronson.linguize.ui.appvoice

import com.evanaronson.linguize.core.AppRule
import com.evanaronson.linguize.core.WritingRegister
import com.evanaronson.linguize.ui.home.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Per-app voice list: rules, then recent History apps, then installed messaging apps, each once. */
class AppEntriesTest {
    private val names = mapOf(
        "com.google.android.gm" to "Gmail",
        "com.whatsapp" to "WhatsApp",
        "org.telegram.messenger" to "Telegram",
        "com.slack" to "Slack",
    )

    @Test fun eachAppAppearsOnceInItsFirstSection() {
        val gmail = AppRule("com.google.android.gm", register = WritingRegister.Formal)
        val gone = AppRule("com.uninstalled", register = WritingRegister.Casual)
        val entries = appEntries(
            rules = listOf(gone, gmail),
            recent = listOf("com.whatsapp", null, "com.google.android.gm", "own.app", "com.whatsapp", "com.slack"),
            messaging = listOf("org.telegram.messenger", "com.whatsapp", "not.installed"),
            own = "own.app",
            name = names::get,
        )
        assertEquals(
            listOf(
                AppEntry("com.google.android.gm", "Gmail", gmail, AppSection.WithRule),
                // An uninstalled app keeps its rule and can still be reset.
                AppEntry("com.uninstalled", null, gone, AppSection.WithRule),
                AppEntry("com.whatsapp", "WhatsApp", null, AppSection.Recent),
                AppEntry("com.slack", "Slack", null, AppSection.Recent),
                AppEntry("org.telegram.messenger", "Telegram", null, AppSection.Messaging),
            ),
            entries,
        )
    }

    @Test fun nothingKnownIsEmpty() {
        assertEquals(emptyList<AppEntry>(), appEntries(emptyList(), emptyList(), listOf("x.y"), "own", { null }))
    }

    @Test fun pagesSurviveSavedState() {
        listOf(Screen.AppVoice(), Screen.AppVoice("com.whatsapp")).forEach {
            assertEquals(it, Screen.restore(it.saved))
        }
        assertEquals(null, Screen.restore("appvoice:"))
    }
}
