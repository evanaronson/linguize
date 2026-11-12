package com.evanaronson.linguize.ui.appvoice

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.FakeClient
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.QUESTION
import com.evanaronson.linguize.QUESTION_JSON
import com.evanaronson.linguize.QUESTION_VERDICT
import com.evanaronson.linguize.core.AppRule
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.VoiceOverride
import com.evanaronson.linguize.core.VoiceSource
import com.evanaronson.linguize.core.WritingRegister
import com.evanaronson.linguize.data.appvoice.AppRules
import com.evanaronson.linguize.fakeChecker
import com.evanaronson.linguize.history.CheckHistory
import com.evanaronson.linguize.history.HistoryCursor
import com.evanaronson.linguize.history.HistoryEnvironment
import com.evanaronson.linguize.history.HistoryPage
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.ReuseKey
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionDetailResult
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SessionRecording
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.history.SuggestionRecord
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.OutputStream

/**
 * The card's voice on a real checker and history with a fake provider: a rule reaches the
 * request and the recorded settings, a choice on the card beats it, and Settings fill the rest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CardVoiceViewModelTest {
    private val gmail = AppRule("com.google.android.gm", register = WritingRegister.Formal, judgments = Judgments.FixOnly)
    private val telegram = AppRule("org.telegram.messenger", language = Language.Catalan)
    private val rules = MemoryRules(gmail, telegram)

    private val preferences = FakePreferences()
    private val client = FakeClient { ModelAnswer(QUESTION_VERDICT, QUESTION_JSON) }
    private val checker = fakeChecker(preferences, client)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history = CheckHistory(EmptyStore(), object : HistoryEnvironment {
        override val enabled = true
        override val deviceId = "device"
        override val appVersion = "1.0"
    }, scope)
    private val viewModels = ViewModelStore()
    private val check = ViewModelProvider(
        viewModels,
        viewModelFactory { initializer { CheckViewModel(checker = { checker }, history = history) } },
    )[CheckViewModel::class.java]
    private val voice = ViewModelProvider(
        viewModels,
        viewModelFactory { initializer { CardVoiceViewModel(rules) { if (it == gmail.app) "Gmail" else null } } },
    )[CardVoiceViewModel::class.java]

    @After
    fun stop() {
        viewModels.clear()
        scope.cancel()
    }

    @Test fun ruleReachesTheRequestAndTheRecordedSnapshot() {
        voice.attach(check)
        val language = voice.start(gmail.app, null)
        assertNull(language)
        check.check(QUESTION, language, Origin.Menu, gmail.app)
        done()
        val request = client.checks.single().third
        assertEquals(Judgments.FixOnly, request.judgments)
        assertEquals(setOf(WritingRegister.Formal), request.writingProfiles.map { it.register }.toSet())
        val recorded = pending().settings
        assertEquals("fix", recorded.judgments)
        assertEquals(setOf(WritingRegister.Formal), recorded.writingProfiles.map { it.register }.toSet())
        assertTrue(voice.visible)
        assertEquals(VoiceSource.Rule, voice.voice.source)
        waitFor("the app name") { voice.appName == "Gmail" }
    }

    @Test fun cardChoiceBeatsTheRuleOnRecheckAndSettingsFillTheRest() {
        voice.attach(check)
        check.check(QUESTION, voice.start(gmail.app, null), Origin.Menu, gmail.app)
        done()
        assertTrue(voice.chooseRegister(WritingRegister.Casual))
        check.recheck()
        waitFor("the second request") { client.checks.size == 2 && check.state is CardState.Done }
        val request = client.checks.last().third
        assertEquals(setOf(WritingRegister.Casual), request.writingProfiles.map { it.register }.toSet())
        assertEquals(Judgments.FixOnly, request.judgments)
        assertEquals(setOf(WritingRegister.Casual), pending().settings.writingProfiles.map { it.register }.toSet())
        assertEquals(VoiceSource.ThisCheck, voice.voice.source)
        // Picking the rule's own value again is no card choice at all.
        assertTrue(voice.chooseRegister(WritingRegister.Formal))
        assertEquals(VoiceOverride.None, voice.override)
        assertFalse(voice.chooseJudgments(Judgments.FixOnly))
    }

    @Test fun noRuleSendsSettingsUnchanged() {
        preferences.judgments = Judgments.NaturalizeOnly
        voice.attach(check)
        check.check(QUESTION, voice.start("com.whatsapp", null), Origin.Button, "com.whatsapp")
        done()
        val request = client.checks.single().third
        assertEquals(Judgments.NaturalizeOnly, request.judgments)
        assertEquals(setOf(WritingRegister.Everyday), request.writingProfiles.map { it.register }.toSet())
        assertFalse(voice.visible)
    }

    @Test fun pinnedLanguageAppliesOnlyWithoutAnExplicitRequest() {
        assertEquals(Language.Catalan, voice.start(telegram.app, null))
        assertEquals(Language.Spanish, voice.start(telegram.app, Language.Spanish))
        assertNull(voice.start(null, null))
        assertFalse(voice.visible)
    }

    @Test fun unknownAppHasNoRuleAndNoNote() {
        voice.start(null, null)
        assertTrue(voice.voice.isDefault)
        assertFalse(voice.visible)
    }

    @Test fun savingTheCardChoiceUpdatesTheRuleAndKeepsItsLanguage() {
        voice.start(telegram.app, null)
        voice.chooseRegister(WritingRegister.Formal)
        voice.chooseJudgments(Judgments.Both)
        voice.saveRule()
        val saved = AppRule(telegram.app, Language.Catalan, WritingRegister.Formal, Judgments.Both)
        assertEquals(saved, rules.rule(telegram.app))
        assertEquals(saved, voice.rule)
        assertEquals(VoiceOverride.None, voice.override)
        assertEquals(VoiceSource.Rule, voice.voice.source)
    }

    @Test fun startingAgainForgetsTheLastCardsChoice() {
        voice.start(gmail.app, null)
        voice.chooseRegister(WritingRegister.Casual)
        voice.start(gmail.app, null)
        assertEquals(WritingRegister.Formal, voice.voice.register)
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }

    private fun done() = waitFor("an answer") { check.state is CardState.Done }

    private fun pending(): SessionRecord =
        ReflectionHelpers.getField<SessionRecording>(history, "recording").session

    private class MemoryRules(vararg initial: AppRule) : AppRules {
        private val rules = initial.associateBy { it.app }.toMutableMap()
        override fun rule(app: String?) = app?.let(rules::get)
        override fun all() = rules.values.sortedBy { it.app }
        override fun save(rule: AppRule) {
            if (rule.isEmpty) rules.remove(rule.app) else rules[rule.app] = rule
        }
        override fun reset(app: String) {
            rules.remove(app)
        }
        override fun resetAll() = rules.clear()
    }

    private class EmptyStore : HistoryStore {
        override suspend fun save(session: SessionRecord) = Unit
        override suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>) = Unit
        override suspend fun reusable(key: ReuseKey, since: Long): SessionDetail? = null
        override suspend fun markAbandoned(now: Long) = Unit
        override fun count(): Flow<Int> = flowOf(0)
        override fun since(): Flow<Long?> = flowOf(null)
        override fun recent(limit: Int): Flow<List<SessionSummary>> = flowOf(emptyList())
        override fun revisions(): Flow<Long> = flowOf(0L)
        override suspend fun page(before: HistoryCursor?, limit: Int) = HistoryPage(emptyList(), null)
        override suspend fun detail(id: String) = SessionDetailResult.Missing
        override suspend fun delete(id: String) = true
        override suspend fun clear() = Unit
        override suspend fun export(out: OutputStream) = true
    }
}
