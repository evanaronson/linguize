package com.evanaronson.linguize

import com.evanaronson.linguize.core.AppRule
import com.evanaronson.linguize.core.AppVoice
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Voice
import com.evanaronson.linguize.core.VoiceOverride
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.WritingRegister
import com.evanaronson.linguize.llm.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A rule reaches history through the same snapshot as Settings: judgments and complete profiles. */
class AppVoiceContextTest {
    private val context = CheckContext(
        provider = Provider.Gemini,
        model = "m",
        punctuation = Punctuation.Moderate,
        judgments = Judgments.Both,
        native = "English",
        promptHash = "hash",
        writingProfiles = Language.all.map { WritingProfile.defaultFor(it) },
    )

    @Test fun defaultVoiceLeavesTheContextAlone() {
        assertSame(context, context.withVoice(Voice.Default))
    }

    @Test fun ruleChangesTheStoredSnapshotOnly() {
        val rule = AppRule("com.google.android.gm", register = WritingRegister.Formal, judgments = Judgments.FixOnly)
        val adjusted = context.withVoice(AppVoice.resolve(rule))
        val stored = adjusted.stored
        assertEquals("fix", stored.judgments)
        assertEquals(listOf(WritingRegister.Formal, WritingRegister.Formal), stored.writingProfiles.map { it.register })
        assertEquals(listOf("default-ca", "default-es"), stored.writingProfiles.map { it.id })
        // Nothing else a check depends on moves.
        assertEquals(context.stored.copy(judgments = "fix", writingProfiles = stored.writingProfiles), stored)
    }

    @Test fun cardChoiceWinsOverTheRuleInTheSnapshot() {
        val rule = AppRule("com.whatsapp", register = WritingRegister.Formal)
        val adjusted = context.withVoice(AppVoice.resolve(rule, VoiceOverride(register = WritingRegister.Casual)))
        assertEquals(setOf(WritingRegister.Casual), adjusted.writingProfiles.map { it.register }.toSet())
        assertEquals(Judgments.Both, adjusted.judgments)
    }
}
