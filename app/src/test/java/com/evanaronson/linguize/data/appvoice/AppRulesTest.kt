package com.evanaronson.linguize.data.appvoice

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.AppRule
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingRegister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rules live in Lab's own preferences file, as tokens, and never touch Settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppRulesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences(SharedPreferencesAppRules.FILE, Context.MODE_PRIVATE)
    private val rules = SharedPreferencesAppRules(context)

    private val gmail = AppRule("com.google.android.gm", null, WritingRegister.Formal, Judgments.FixOnly)
    private val telegram = AppRule("org.telegram.messenger", Language.Catalan, WritingRegister.Casual, null)

    @Test fun savedRulesReadBackAndSurviveANewInstance() {
        rules.save(gmail)
        rules.save(telegram)
        val reopened = SharedPreferencesAppRules(context)
        assertEquals(gmail, reopened.rule(gmail.app))
        assertEquals(telegram, reopened.rule(telegram.app))
        assertEquals(listOf(gmail, telegram), reopened.all())
        assertNull(reopened.rule("com.whatsapp"))
        assertNull(reopened.rule(null))
        assertNull(reopened.rule(""))
    }

    @Test fun storesStableTokensNotLabels() {
        rules.save(gmail)
        val raw = prefs.getString("rule:${gmail.app}", null)!!
        assertTrue(raw, raw.contains("\"formal\"") && raw.contains("\"fix\""))
    }

    @Test fun savingReplacesAndAnEmptyRuleResets() {
        rules.save(gmail)
        rules.save(gmail.copy(register = WritingRegister.Everyday))
        assertEquals(WritingRegister.Everyday, rules.rule(gmail.app)?.register)
        rules.save(AppRule(gmail.app))
        assertNull(rules.rule(gmail.app))
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun resetRemovesOneAndResetAllRemovesOnlyRules() {
        rules.save(gmail)
        rules.save(telegram)
        prefs.edit().putString("unrelated", "kept").commit()
        rules.reset(gmail.app)
        assertEquals(listOf(telegram), rules.all())
        rules.resetAll()
        assertTrue(rules.all().isEmpty())
        assertEquals("kept", prefs.getString("unrelated", null))
    }

    @Test fun unknownTokensFallBackToSettingsAndUnreadableValuesAreSkipped() {
        prefs.edit()
            .putString("rule:a.b", """{"language":"fr","register":"formal","judgments":"everything","future":1}""")
            .putString("rule:c.d", "not json")
            .putString("rule:e.f", """{"language":"xx"}""")
            .commit()
        assertEquals(AppRule("a.b", register = WritingRegister.Formal), rules.rule("a.b"))
        assertNull(rules.rule("c.d"))
        assertNull(rules.rule("e.f"))
        assertEquals(listOf(AppRule("a.b", register = WritingRegister.Formal)), rules.all())
        // Unreadable values are left in place for a later build, not deleted.
        assertEquals("not json", prefs.getString("rule:c.d", null))
    }

    @Test fun neverWritesToTheSettingsFile() {
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val before = settings.all.toMap()
        rules.save(gmail)
        rules.resetAll()
        assertEquals(before, settings.all.toMap())
    }
}
