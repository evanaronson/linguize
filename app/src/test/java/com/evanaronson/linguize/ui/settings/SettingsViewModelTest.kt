package com.evanaronson.linguize.ui.settings

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.llm.Provider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class SettingsViewModelTest {
    @Test
    fun completingAKeyWriteReconcilesChangesMadeByAnotherSettingsHost() {
        val app = RuntimeEnvironment.getApplication() as App
        app.settings.provider = Provider.Gemini
        app.settings.punctuation = Punctuation.Moderate
        val settings = SettingsViewModel(app)
        val store = ViewModelStore().apply { put("settings", settings) }
        try {
            waitFor { settings.state != null }
            // This screen still holds the old snapshot when a key action begins.
            app.settings.provider = Provider.OpenAI
            app.settings.punctuation = Punctuation.Strict
            settings.removeKey()
            waitFor { settings.state?.provider == Provider.OpenAI }
            assertEquals(Punctuation.Strict, settings.state!!.punctuation)
        } finally {
            store.clear()
        }
    }

    @Test
    fun showingTheModelListDoesNotCancelARefreshFromAnotherHost() {
        val app = RuntimeEnvironment.getApplication() as App
        app.settings.punctuation = Punctuation.Moderate
        val settings = SettingsViewModel(app)
        val store = ViewModelStore().apply { put("settings", settings) }
        try {
            waitFor { settings.state != null }
            assertEquals(Punctuation.Moderate, settings.state!!.punctuation)
            // Another popup changed the stored settings while this host was away.
            app.settings.punctuation = Punctuation.Strict
            settings.refresh()
            // Composition can ask for models immediately, before that read comes back.
            settings.showModels()
            waitFor { settings.state?.punctuation == Punctuation.Strict }
            assertEquals(Punctuation.Strict, settings.state!!.punctuation)
        } finally {
            store.clear()
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for settings")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }
}
