package com.evanaronson.linguize.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-app voice precedence: the card's choice, then the app's rule, then Settings. */
class AppVoiceTest {
    private val gmail = AppRule("com.google.android.gm", register = WritingRegister.Formal, judgments = Judgments.FixOnly)
    private val telegram = AppRule("org.telegram.messenger", language = Language.Catalan)

    @Test fun noRuleKeepsSettings() {
        val voice = AppVoice.resolve(null)
        assertEquals(Voice.Default, voice)
        assertTrue(voice.isDefault)
        assertEquals(VoiceSource.Settings, voice.source)
    }

    @Test fun ruleAppliesWhenTheCardChoseNothing() {
        val voice = AppVoice.resolve(gmail)
        assertEquals(WritingRegister.Formal, voice.register)
        assertEquals(VoiceSource.Rule, voice.registerSource)
        assertEquals(Judgments.FixOnly, voice.judgments)
        assertEquals(VoiceSource.Rule, voice.judgmentsSource)
        assertEquals(VoiceSource.Rule, voice.source)
    }

    @Test fun cardChoiceBeatsTheRuleFieldByField() {
        val voice = AppVoice.resolve(gmail, VoiceOverride(register = WritingRegister.Casual))
        assertEquals(WritingRegister.Casual, voice.register)
        assertEquals(VoiceSource.ThisCheck, voice.registerSource)
        // Suggestions weren't touched on the card, so the rule still sets them.
        assertEquals(Judgments.FixOnly, voice.judgments)
        assertEquals(VoiceSource.Rule, voice.judgmentsSource)
        assertEquals(VoiceSource.ThisCheck, voice.source)
    }

    @Test fun cardChoiceAppliesWithoutARule() {
        val voice = AppVoice.resolve(null, VoiceOverride(judgments = Judgments.NaturalizeOnly))
        assertNull(voice.register)
        assertEquals(VoiceSource.Settings, voice.registerSource)
        assertEquals(Judgments.NaturalizeOnly, voice.judgments)
    }

    @Test fun ruleFieldsLeftEmptyFallThroughToSettings() {
        val voice = AppVoice.resolve(telegram)
        assertTrue(voice.isDefault)
        assertEquals(VoiceSource.Settings, voice.source)
    }

    @Test fun explicitLanguageBeatsRuleWhichBeatsAutoDetect() {
        assertEquals(Language.Spanish, AppVoice.language(Language.Spanish, telegram))
        assertEquals(Language.Catalan, AppVoice.language(null, telegram))
        assertNull(AppVoice.language(null, gmail))
        assertNull(AppVoice.language(null, null))
        assertEquals(Language.Spanish, AppVoice.language(Language.Spanish, null))
    }

    @Test fun registerReplacesEveryProfileButKeepsIdentityAndPunctuation() {
        val profiles = listOf(
            WritingProfile.defaultFor(Language.Catalan, Punctuation.Strict),
            WritingProfile.defaultFor(Language.Spanish).copy(register = WritingRegister.Casual),
        )
        val formal = AppVoice.profiles(profiles, AppVoice.resolve(gmail))
        assertEquals(listOf(WritingRegister.Formal, WritingRegister.Formal), formal.map { it.register })
        assertEquals(profiles.map { it.copy(register = WritingRegister.Formal) }, formal)
        assertSame(profiles, AppVoice.profiles(profiles, Voice.Default))
    }

    @Test fun judgmentsFollowTheVoiceOrSettings() {
        assertEquals(Judgments.FixOnly, AppVoice.judgments(Judgments.Both, AppVoice.resolve(gmail)))
        assertEquals(Judgments.Both, AppVoice.judgments(Judgments.Both, Voice.Default))
    }

    @Test fun savingMergesCardChoicesIntoTheRuleAndKeepsItsLanguage() {
        val saved = AppVoice.saved(telegram.app, telegram, VoiceOverride(register = WritingRegister.Casual))
        assertEquals(AppRule(telegram.app, Language.Catalan, WritingRegister.Casual, null), saved)
        assertEquals(gmail, AppVoice.saved(gmail.app, gmail, VoiceOverride.None))
        assertNull(AppVoice.saved("com.example", null, VoiceOverride.None))
    }

    @Test fun emptyRuleIsTheSameAsNone() {
        assertTrue(AppRule("com.example").isEmpty)
        assertTrue(VoiceOverride.None.isEmpty)
    }
}
