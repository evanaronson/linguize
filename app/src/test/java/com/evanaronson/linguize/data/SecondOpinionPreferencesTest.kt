package com.evanaronson.linguize.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.llm.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which model a second opinion asks, and where that choice is kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SecondOpinionPreferencesTest {
    private val models = mapOf(Provider.Gemini to "gemini-a", Provider.OpenAI to "gpt-a")

    private fun plan(
        choice: SecondOpinionChoice,
        checkProvider: Provider = Provider.Gemini,
        checkModel: String = "gemini-a",
        keys: Set<Provider> = Provider.entries.toSet(),
    ) = planSecondOpinion(choice, checkProvider, checkModel, models::getValue) { it in keys }

    @Test fun automaticAsksTheOtherServiceWithItsModelFromSettings() {
        assertEquals(SecondOpinionPlan.Ready(SecondOpinionTarget(Provider.OpenAI, "gpt-a")), plan(SecondOpinionChoice.Automatic))
        assertEquals(SecondOpinionPlan.Ready(SecondOpinionTarget(Provider.Gemini, "gemini-a")),
            plan(SecondOpinionChoice.Automatic, checkProvider = Provider.OpenAI, checkModel = "gpt-a"))
    }

    @Test fun automaticWithoutTheOtherKeySaysWhichKeyIsMissing() {
        assertEquals(SecondOpinionPlan.NoKey(Provider.OpenAI), plan(SecondOpinionChoice.Automatic, keys = setOf(Provider.Gemini)))
    }

    @Test fun aSecondModelOfTheSameServiceIsReady() {
        assertEquals(SecondOpinionPlan.Ready(SecondOpinionTarget(Provider.Gemini, "gemini-pro")),
            plan(SecondOpinionChoice.Picked(Provider.Gemini, "gemini-pro"), keys = setOf(Provider.Gemini)))
    }

    @Test fun theModelChecksAlreadyUseIsNoSecondOpinion() {
        assertEquals(SecondOpinionPlan.SameAsCheck, plan(SecondOpinionChoice.Picked(Provider.Gemini)))
        assertEquals(SecondOpinionPlan.SameAsCheck, plan(SecondOpinionChoice.Picked(Provider.Gemini, "gemini-a")))
        assertEquals(SecondOpinionPlan.SameAsCheck, plan(SecondOpinionChoice.Picked(Provider.Gemini, " ")))
    }

    @Test fun offIsOffWhateverTheKeys() {
        assertEquals(SecondOpinionPlan.Off, plan(SecondOpinionChoice.Off))
        assertEquals(SecondOpinionPlan.Off, plan(SecondOpinionChoice.Off, keys = emptySet()))
    }

    @Test fun thePickedServiceWithoutAKeyIsNotReady() {
        assertEquals(SecondOpinionPlan.NoKey(Provider.OpenAI),
            plan(SecondOpinionChoice.Picked(Provider.OpenAI, "gpt-b"), keys = setOf(Provider.Gemini)))
    }

    @Test fun theChoiceIsKeptInItsOwnFileAndDefaultsToAutomatic() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        settings.edit().putString("model.gemini", "kept").commit()
        val preferences = SecondOpinionPreferences(context)
        assertEquals(SecondOpinionChoice.Automatic, preferences.choice)

        for (choice in listOf(
            SecondOpinionChoice.Picked(Provider.OpenAI, "gpt-b"),
            SecondOpinionChoice.Picked(Provider.Gemini),
            SecondOpinionChoice.Off,
            SecondOpinionChoice.Automatic,
        )) {
            preferences.choice = choice
            assertEquals(choice, SecondOpinionPreferences(context).choice)
        }
        assertEquals("kept", settings.getString("model.gemini", null))
        assertEquals(mapOf("model.gemini" to "kept"), settings.all)
        val own = context.getSharedPreferences("lab_second_opinion", Context.MODE_PRIVATE)
        assertEquals("auto", own.getString("mode", null))
        assertFalse(own.contains("provider"))
    }

    @Test fun anUnknownStoredServiceReadsAsAutomatic() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("lab_second_opinion", Context.MODE_PRIVATE).edit()
            .putString("mode", "pick").putString("provider", "someday").commit()
        assertEquals(SecondOpinionChoice.Automatic, SecondOpinionPreferences(context).choice)
    }
}
