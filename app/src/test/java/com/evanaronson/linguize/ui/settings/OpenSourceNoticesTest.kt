package com.evanaronson.linguize.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lab, lang-selector: Settings › About › Open-source notices shows the licences that ship in the APK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OpenSourceNoticesTest {
    @Test fun theNoticesAreReadFromTheApk() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val notices = OpenSourceNotices.read(context)
        assertEquals(listOf("lab_languages_NOTICE.txt", "lab_vocab_SOURCES.md"), notices.map { it.first.asset })
        val catalog = notices.first().second
        assertTrue(catalog.contains("circle-flags"))
        assertTrue(catalog.contains("MIT License"))
        assertTrue(catalog.contains("UNICODE LICENSE V3"))
        assertTrue(notices[1].second.contains("CC BY-SA"))
    }

    @Test fun settingsHasAQuietAboutRow() {
        val settings = File("src/main/java/com/evanaronson/linguize/ui/settings/SettingsScreen.kt").readText()
        assertTrue(settings.contains("aboutSection()"))
        val activity = File("src/main/java/com/evanaronson/linguize/ui/home/HomeActivity.kt").readText()
        assertTrue(activity.contains("AboutSection { navigation = navigation.open(Screen.Notices) }"))
        assertTrue(activity.contains("Screen.Notices -> com.evanaronson.linguize.ui.settings.OpenSourceNoticesScreen()"))
    }
}
