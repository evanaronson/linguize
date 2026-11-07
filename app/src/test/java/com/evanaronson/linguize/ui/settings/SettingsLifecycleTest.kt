package com.evanaronson.linguize.ui.settings

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.WritingRegister
import com.evanaronson.linguize.data.KeyStatus
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class SettingsLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication() as App

    @Test
    fun restoredRegisterAndPunctuationStillEditTheSelectedLanguageWithoutRestoringKeys() {
        val original = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val first = ViewModelProvider(original.get(), SettingsViewModel.factory(app))[SettingsViewModel::class.java]
        waitFor { first.state != null }
        val catalan = first.state!!.writingProfile
        first.selectWritingLanguage(Language.Spanish)
        first.setKeyDraft(Provider.Gemini, "draft-that-must-never-be-saved")
        val saved = Bundle()
        original.saveInstanceState(saved).pause().stop().destroy()

        val restored = Robolectric.buildActivity(ComponentActivity::class.java).create(saved).start().resume()
        try {
            val second = ViewModelProvider(restored.get(), SettingsViewModel.factory(app))[SettingsViewModel::class.java]
            waitFor { second.state != null }
            assertEquals("es", second.state!!.writingLanguage)
            second.setWritingRegister(WritingRegister.Formal)
            second.setWritingPunctuation(Punctuation.Strict)
            assertEquals(WritingRegister.Formal, app.settings.writingProfiles.single { it.language == "es" }.register)
            assertEquals(Punctuation.Strict, app.settings.writingProfiles.single { it.language == "es" }.punctuation)
            assertEquals(catalan, app.settings.writingProfiles.single { it.language == "ca" })
            assertEquals("", second.keyDraft(Provider.Gemini))
            assertFalse(containsText(saved, "draft-that-must-never-be-saved"))
            assertTrue(app.getSharedPreferences("keys", Context.MODE_PRIVATE).all.isEmpty())
        } finally {
            restored.pause().stop().destroy()
        }
    }

    @Test
    fun keyDraftsSurviveChildPagesAndServiceChangesWithoutBeingWritten() {
        val savedState = SavedStateHandle()
        val settings = SettingsViewModel(app, savedState = savedState)
        val store = ViewModelStore().apply { put("settings", settings) }
        try {
            waitFor { settings.state != null }
            settings.setKeyDraft(Provider.Gemini, "gemini-draft")
            settings.showModels()
            settings.selectProvider(Provider.OpenAI)
            settings.setKeyDraft(Provider.OpenAI, "openai-draft")
            settings.showModels()
            settings.selectProvider(Provider.Gemini)
            settings.showModels()
            assertEquals("gemini-draft", settings.keyDraft(Provider.Gemini))
            assertEquals("openai-draft", settings.keyDraft(Provider.OpenAI))
            assertTrue(savedState.keys().isEmpty())
            assertTrue(app.getSharedPreferences("keys", Context.MODE_PRIVATE).all.isEmpty())

            // Robolectric has no Android Keystore: a failed save must leave the draft retryable.
            settings.saveKey(settings.keyDraft(Provider.Gemini))
            waitFor { settings.state?.keySaveFailed == true }
            assertEquals("gemini-draft", settings.keyDraft(Provider.Gemini))
            settings.removeKey()
            waitFor { settings.keyDraft(Provider.Gemini).isEmpty() }
            assertEquals("openai-draft", settings.keyDraft(Provider.OpenAI))
        } finally {
            store.clear()
        }
        assertEquals("", settings.keyDraft(Provider.OpenAI))
    }

    @Test
    fun revisitingModelsReusesTheListUntilExplicitRefreshProviderChangeOrKeyRevision() {
        val requested = mutableListOf<Provider>()
        val settings = SettingsViewModel(app, keyStatus = { KeyStatus.Saved("same") }, fetchModels = {
            requested += it
            listOf("model-${requested.size}")
        })
        val store = ViewModelStore().apply { put("settings", settings) }
        try {
            waitFor { settings.state != null }
            settings.showModels()
            waitFor { settings.state?.models == ModelList.Loaded(listOf("model-1")) }
            repeat(3) { settings.showModels() }
            settings.selectProvider(Provider.Gemini)
            assertEquals(listOf(Provider.Gemini), requested)

            // Changing the chosen model or another setting does not change which models exist.
            app.settings.setModel(Provider.Gemini, "different-model")
            settings.refresh()
            waitFor { settings.state?.model == "different-model" }
            settings.showModels()
            assertEquals(listOf(Provider.Gemini), requested)

            settings.refreshModels()
            waitFor { settings.state?.models == ModelList.Loaded(listOf("model-2")) }
            settings.selectProvider(Provider.OpenAI)
            waitFor { settings.state?.models == ModelList.Loaded(listOf("model-3")) }

            // A replacement can have the same four visible characters. Revision still invalidates it.
            app.keys.set(Provider.OpenAI, "")
            settings.refresh()
            waitFor { settings.state?.models == ModelList.Loaded(listOf("model-4")) }
            assertEquals(listOf(Provider.Gemini, Provider.Gemini, Provider.OpenAI, Provider.OpenAI), requested)

            // A key for an inactive service cannot invalidate this service's list.
            app.keys.set(Provider.Gemini, "")
            app.settings.punctuation = Punctuation.Strict
            settings.refresh()
            waitFor { settings.state?.punctuation == Punctuation.Strict }
            settings.showModels()
            assertEquals(4, requested.size)
        } finally {
            store.clear()
        }
    }

    @Test
    fun anExplicitRetryReplacesFailureWithoutRetryingEveryRecomposition() {
        var requests = 0
        val settings = SettingsViewModel(app, keyStatus = { KeyStatus.Saved("same") }, fetchModels = {
            if (++requests == 1) throw CheckFailure(CheckFailure.Reason.Offline)
            emptyList()
        })
        val store = ViewModelStore().apply { put("settings", settings) }
        try {
            waitFor { settings.state != null }
            settings.showModels()
            waitFor { settings.state?.models == ModelList.Failed }
            settings.showModels()
            assertEquals(1, requests)
            settings.refreshModels()
            waitFor { settings.state?.models == ModelList.Loaded(emptyList()) }
            assertEquals(2, requests)
        } finally {
            store.clear()
        }
    }

    @Test
    fun anOlderModelRequestCannotReplaceAnExplicitRefresh() {
        val late = CompletableDeferred<List<String>>()
        val returned = CompletableDeferred<Unit>()
        var requests = 0
        val settings = SettingsViewModel(app, keyStatus = { KeyStatus.Saved("same") }, fetchModels = {
            if (++requests == 1) {
                // Exercise a provider response already arriving while cancellation is requested.
                withContext(NonCancellable) { late.await() }.also { returned.complete(Unit) }
            } else listOf("new-model")
        })
        val store = ViewModelStore().apply { put("settings", settings) }
        try {
            waitFor { settings.state != null }
            settings.showModels()
            waitFor { requests == 1 }
            settings.refreshModels()
            waitFor { settings.state?.models == ModelList.Loaded(listOf("new-model")) }
            late.complete(listOf("old-model"))
            waitFor { returned.isCompleted }
            assertEquals(ModelList.Loaded(listOf("new-model")), settings.state!!.models)
        } finally {
            late.complete(emptyList())
            store.clear()
        }
    }

    @Suppress("DEPRECATION")
    private fun containsText(value: Any?, text: String): Boolean = when (value) {
        is Bundle -> value.keySet().any { containsText(value.get(it), text) }
        is Map<*, *> -> value.values.any { containsText(it, text) }
        is Iterable<*> -> value.any { containsText(it, text) }
        is Array<*> -> value.any { containsText(it, text) }
        is String -> text in value
        else -> false
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for settings lifecycle")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }
}
