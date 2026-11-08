package com.evanaronson.linguize.ui.appvoice

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.AppRule
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingRegister
import com.evanaronson.linguize.data.appvoice.SharedPreferencesAppRules
import com.evanaronson.linguize.history.HistoryCursor
import com.evanaronson.linguize.history.HistoryPage
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.ReuseKey
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionDetailResult
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.history.SuggestionRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.OutputStream

/** The Settings page reads apps from History, and edits and resets rules in Lab's store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppVoiceSettingsViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val rules = SharedPreferencesAppRules(context)
    private val names = mapOf("com.whatsapp" to "WhatsApp", "com.google.android.gm" to "Gmail")

    private fun model(vararg recent: String) =
        AppVoiceSettingsViewModel(rules, RecentStore(recent.toList()), "own", names::get, messaging = listOf("com.google.android.gm"))

    private fun AppVoiceSettingsViewModel.loaded(): List<AppEntry> {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (entries == null) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out loading")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        return entries!!
    }

    @Test fun listsRecentThenMessagingAppsAndEditsMoveNothing() {
        val model = model("com.whatsapp")
        assertEquals(listOf("com.whatsapp", "com.google.android.gm"), model.loaded().map { it.app })
        model.setRegister("com.whatsapp", WritingRegister.Casual)
        model.setLanguage("com.whatsapp", Language.Catalan)
        model.setJudgments("com.whatsapp", Judgments.FixOnly)
        val rule = AppRule("com.whatsapp", Language.Catalan, WritingRegister.Casual, Judgments.FixOnly)
        assertEquals(rule, rules.rule("com.whatsapp"))
        // Editing keeps the row where it was until the page loads again.
        assertEquals(AppEntry("com.whatsapp", "WhatsApp", rule, AppSection.Recent), model.loaded().first())
        model.refresh()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun clearingEveryFieldIsAReset() {
        val model = model("com.whatsapp")
        model.loaded()
        model.setRegister("com.whatsapp", WritingRegister.Formal)
        model.setRegister("com.whatsapp", null)
        assertNull(rules.rule("com.whatsapp"))
        assertNull(model.loaded().first().rule)
    }

    @Test fun resetAndResetAll() {
        rules.save(AppRule("com.google.android.gm", register = WritingRegister.Formal))
        rules.save(AppRule("com.whatsapp", register = WritingRegister.Casual))
        val model = model()
        assertEquals(AppSection.WithRule, model.loaded().first().section)
        model.reset("com.google.android.gm")
        assertNull(rules.rule("com.google.android.gm"))
        model.resetAll()
        assertTrue(rules.all().isEmpty())
    }

    private class RecentStore(private val apps: List<String>) : HistoryStore {
        override fun recent(limit: Int): Flow<List<SessionSummary>> = flowOf(apps.mapIndexed { i, app ->
            SessionSummary("s$i", startedAt = i.toLong(), origin = Origin.Menu, hostApp = app, text = "",
                outcome = null, fixes = 0, rewordings = 0, taken = 0)
        })
        override suspend fun save(session: SessionRecord) = Unit
        override suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>) = Unit
        override suspend fun reusable(key: ReuseKey, since: Long): SessionDetail? = null
        override suspend fun markAbandoned(now: Long) = Unit
        override fun count(): Flow<Int> = flowOf(0)
        override fun since(): Flow<Long?> = flowOf(null)
        override fun revisions(): Flow<Long> = flowOf(0L)
        override suspend fun page(before: HistoryCursor?, limit: Int) = HistoryPage(emptyList(), null)
        override suspend fun detail(id: String) = SessionDetailResult.Missing
        override suspend fun delete(id: String) = true
        override suspend fun clear() = Unit
        override suspend fun export(out: OutputStream) = true
    }
}
