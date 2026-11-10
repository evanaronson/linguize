package com.evanaronson.linguize.data.language

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.language.Languages
import com.evanaronson.linguize.core.language.LocaleLanguageNames
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lab, lang-selector: the catalog is the app's language names, installed at start-up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CatalogLanguageNamesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val before = Languages.names

    @After fun restore() {
        Languages.names = before
    }

    @Test fun installingMakesEveryLabelUseTheCatalog() {
        Languages.names = LocaleLanguageNames
        CatalogLanguageNames.install(context)
        val names = Languages.names
        assertTrue(names is CatalogLanguageNames)
        assertEquals("Gallicize", names.brand("fr", Locale.ENGLISH))
        assertEquals("Catalanize", names.brand("ca", Locale.ENGLISH))
        assertEquals("Castilianize", names.brand("es", Locale.ENGLISH))
        assertEquals("Navajize", names.brand("nv", Locale.ENGLISH))
        assertNull(names.brand("qq", Locale.ENGLISH))
        // English names are CLDR's, as the catalog has them.
        assertEquals("Bangla", names.name("bn", Locale.ENGLISH))
        assertEquals("French", names.name("fr", Locale.ENGLISH))
        assertEquals("català", names.native("ca"))
        assertEquals("日本語", names.native("ja"))
    }

    @Test fun anotherUiLanguageGetsThePlatformsNameAndTheSameBrand() {
        val names = CatalogLanguageNames { LanguageCatalogAsset.get(context) }
        assertEquals("francés", names.name("fr", Locale.forLanguageTag("es")).lowercase())
        assertEquals("Gallicize", names.brand("fr", Locale.forLanguageTag("es")))
        // A code nobody knows is its own name.
        assertEquals("qq", names.name("qq", Locale.ENGLISH))
    }

    @Test fun theAppInstallsItAtStartUp() {
        val app = File("src/main/java/com/evanaronson/linguize/App.kt").readText()
        assertTrue(app.contains("CatalogLanguageNames.install(this)"))
    }
}
