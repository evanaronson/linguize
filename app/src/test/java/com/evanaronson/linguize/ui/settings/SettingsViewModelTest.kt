package com.evanaronson.linguize.ui.settings

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.evanaronson.linguize.data.PopupPosition
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingRegister
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
    fun writingPreferencesEditOnlySelectedLanguageAndRefreshPreservesSelection() {
        val app = RuntimeEnvironment.getApplication() as App
        val settings = SettingsViewModel(app)
        val store = ViewModelStore().apply { put("settings", settings) }
        try {
            waitFor { settings.state != null }
            val spanish = app.settings.writingProfiles.single { it.language == "es" }
            settings.setWritingRegister(WritingRegister.Casual)
            settings.setWritingPunctuation(Punctuation.Strict)
            assertEquals(WritingRegister.Casual, settings.state!!.writingProfile.register)
            assertEquals(Punctuation.Strict, settings.state!!.writingProfile.punctuation)
            settings.selectWritingLanguage(Language.Spanish)
            assertEquals(spanish, settings.state!!.writingProfile)
            settings.setWritingRegister(WritingRegister.Formal)
            settings.refresh()
            waitFor { settings.state?.writingLanguage == "es" && settings.state?.writingProfile?.register == WritingRegister.Formal }
            assertEquals("es", settings.state!!.writingLanguage)
            assertEquals(Punctuation.Strict, app.settings.writingProfiles.single { it.language == "ca" }.punctuation)
            assertEquals(WritingRegister.Casual, app.settings.writingProfiles.single { it.language == "ca" }.register)
        } finally {
            store.clear()
        }
    }

    @Test
    fun popupPositionSavesAndRefreshesFromAnotherHost() {
        val app = RuntimeEnvironment.getApplication() as App
        val settings = SettingsViewModel(app)
        val store = ViewModelStore().apply { put("settings", settings) }
        try {
            waitFor { settings.state != null }
            assertEquals(PopupPosition.Bottom, settings.state!!.popupPosition)
            settings.setPopupPosition(PopupPosition.Top)
            assertEquals(PopupPosition.Top, settings.state!!.popupPosition)
            assertEquals(PopupPosition.Top, app.settings.popupPosition)
            app.settings.popupPosition = PopupPosition.Middle
            settings.refresh()
            waitFor { settings.state?.popupPosition == PopupPosition.Middle }
        } finally {
            store.clear()
        }
    }

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
