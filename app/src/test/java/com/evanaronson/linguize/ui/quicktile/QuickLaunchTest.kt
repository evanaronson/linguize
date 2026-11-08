package com.evanaronson.linguize.ui.quicktile

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutManager
import com.evanaronson.linguize.ui.home.HomeActivity
import com.evanaronson.linguize.ui.home.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QuickLaunchTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun eachJobIsReadBackFromItsOwnIntent() {
        for (job in ClipboardJob.entries) {
            assertEquals(job, ClipboardJob.of(QuickLaunch.intent(context, job)))
        }
    }

    @Test
    fun otherIntentsAreRefused() {
        assertNull(ClipboardJob.of(null))
        assertNull(ClipboardJob.of(Intent()))
        assertNull(ClipboardJob.of(Intent(Intent.ACTION_PROCESS_TEXT)))
        assertNull(ClipboardJob.of(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun intentsStayInThisAppAndOpenTheClipboardHostInANewTask() {
        for (job in ClipboardJob.entries) {
            val intent = QuickLaunch.intent(context, job)
            assertEquals(context.packageName, intent.`package`)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            val resolved = context.packageManager.resolveActivity(intent, 0)
            assertEquals("com.evanaronson.linguize.menu.ClipboardActivity", resolved?.activityInfo?.name)
        }
    }

    @Test
    fun shortcutsCheckUnderstandAndTryItInThatOrder() {
        val shortcuts = QuickLaunch.shortcuts(context)
        assertEquals(
            listOf(QuickLaunch.SHORTCUT_CHECK, QuickLaunch.SHORTCUT_UNDERSTAND, QuickLaunch.SHORTCUT_TRY_IT),
            shortcuts.map { it.id },
        )
        assertEquals(listOf(0, 1, 2), shortcuts.map { it.rank })
        assertEquals(ClipboardJob.Check, ClipboardJob.of(shortcuts[0].intent))
        assertEquals(ClipboardJob.Understand, ClipboardJob.of(shortcuts[1].intent))
        val tryIt = shortcuts[2].intent!!
        assertEquals(HomeActivity::class.java.name, tryIt.component?.className)
        // Try it is on Write, whichever tab the app starts on.
        assertEquals(Screen.Write, HomeActivity.requestedTab(tryIt))
        assertNull(HomeActivity.requestedTab(HomeActivity.homeIntent(context)))
        assertNull(HomeActivity.requestedTab(HomeActivity.homeIntent(context).putExtra("openTab", "practice")))
        // Launchers refuse shortcut intents without an action.
        assertTrue(shortcuts.all { it.intent?.action != null })
        assertTrue(shortcuts.all { !it.shortLabel.isNullOrBlank() && !it.longLabel.isNullOrBlank() })
    }

    @Test
    fun publishingAddsOurShortcutsAndKeepsOthers() {
        val manager = context.getSystemService(ShortcutManager::class.java)
        val other = android.content.pm.ShortcutInfo.Builder(context, "other_feature")
            .setShortLabel("Other").setIntent(Intent(Intent.ACTION_VIEW)).build()
        manager.pushDynamicShortcut(other)

        QuickLaunch.publish(context)
        QuickLaunch.publish(context)

        val ids = manager.dynamicShortcuts.map { it.id }
        assertEquals(
            setOf("other_feature", QuickLaunch.SHORTCUT_CHECK, QuickLaunch.SHORTCUT_UNDERSTAND, QuickLaunch.SHORTCUT_TRY_IT),
            ids.toSet(),
        )
        assertEquals(ids.size, ids.toSet().size)
    }
}
