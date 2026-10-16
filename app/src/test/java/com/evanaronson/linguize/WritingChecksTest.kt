package com.evanaronson.linguize

import com.evanaronson.linguize.core.*
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.llm.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Request, History key and re-check detection must all use the same immutable profile snapshot. */
class WritingChecksTest {
    @Test fun autoAndPinnedChecksKeepTheProfilesTheyActuallyStartedWith() = runBlocking {
        val base = FakePreferences()
        var profiles = listOf(
            WritingProfile.defaultFor(Language.Catalan).copy(register = WritingRegister.Casual, punctuation = Punctuation.Casual),
            WritingProfile.defaultFor(Language.Spanish).copy(register = WritingRegister.Formal, punctuation = Punctuation.Strict),
        )
        val prefs = object : CheckPreferences by base {
            override val writingProfiles get() = profiles
        }
        val client = FakeClient { ModelAnswer(Verdict(Verdict.Status.Ok, language = "Catalan"), "{}") }
        val checker = Checker(prefs, { "key" }, Prompt("", "{}"), Provider.entries.associateWith { client })
        val used = checker.context()
        val original = profiles.toList()
        profiles = profiles.map { it.copy(register = WritingRegister.Everyday) }
        for (language in listOf(null, Language.Catalan, Language.Spanish)) {
            checker.check("Bon dia", language, context = used)
            assertEquals(original, client.checks.last().third.writingProfiles)
        }
        assertEquals(original, used.stored.writingProfiles)
        assertNotEquals(used, checker.context())
        assertNotEquals(used.stored, checker.context().stored)
    }

    @Test fun clarificationAndRepairKeepProfilesAndNeverAutomaticallyRaiseAScore() = runBlocking {
        val base = FakePreferences()
        val profiles = base.writingProfiles
        val client = FakeClient {
            val verdict = Verdict(Verdict.Status.Ok, language = "Catalan", hasErrors = true,
                corrected = "Com estàs?", fixes = listOf(VerdictChange("estas", "estàs", "Accent", 1, 2)))
            ModelAnswer(verdict, "{}")
        }
        val checker = fakeChecker(base, client)
        val settled = listOf(Settled("Who sees", "I do"))
        val checked = checker.check("Com estas?", Language.Catalan, settled)
        val edit = (checked.result as CheckResult.Reviewed).revision.edits.single()
        assertEquals(SuggestionAssessment(1, 2), edit.assessment)
        assertEquals(settled, client.checks.single().third.settled)
        assertEquals(profiles, client.checks.single().third.writingProfiles)
    }
}
