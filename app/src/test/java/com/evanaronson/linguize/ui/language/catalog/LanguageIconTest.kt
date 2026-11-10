package com.evanaronson.linguize.ui.language.catalog

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.language.LanguageCatalog
import com.evanaronson.linguize.data.language.LanguageCatalogAsset
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The catalog's circular flags: every key it names is drawn, and a language without one gets a code badge. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LanguageIconTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val catalog = LanguageCatalogAsset.get(context)

    @Test fun theAssetLoadsOnce() {
        assertSame(catalog, LanguageCatalogAsset.get(context))
        assertEquals(183, catalog.all().size)
    }

    @Test fun everyFlagTheCatalogNamesHasAnImage() {
        val keys = catalog.all().mapNotNull { it.flag } + catalog.all().flatMap { it.varieties }.map { it.flag }
        keys.forEach { key ->
            val drawable = flagDrawable(key)
            assertNotNull(key, drawable)
            val name = context.resources.getResourceEntryName(drawable!!)
            assertTrue(name, File("src/main/res/drawable-nodpi/$name.webp").isFile)
        }
        assertEquals(keys.toSet(), flagKeys)
    }

    @Test fun flagsFollowTheLanguageAndTheSpanishVariety() {
        assertEquals(R.drawable.lab_flag_es_ct, flagResource(catalog, "ca"))
        assertEquals(R.drawable.lab_flag_es, flagResource(catalog, "es", "Peninsular"))
        assertEquals(R.drawable.lab_flag_mx, flagResource(catalog, "es", "Mexican"))
        assertEquals(R.drawable.lab_flag_ar, flagResource(catalog, "es", "Argentinian"))
        assertEquals(R.drawable.lab_flag_co, flagResource(catalog, "es", "Colombian"))
        assertEquals(R.drawable.lab_flag_pt, flagResource(catalog, "pt"))
    }

    @Test fun aLanguageWithoutAFlagGetsACodeBadge() {
        assertNull(flagResource(catalog, "ae"))
        assertEquals(LanguageIconContent.Badge("AE"), languageIconContent(catalog, "ae"))
        assertEquals(LanguageIconContent.Badge("QQ"), languageIconContent(catalog, "qq"))
        assertEquals(LanguageIconContent.Badge("?"), languageIconContent(catalog, null))
        assertEquals(LanguageIconContent.Flag(R.drawable.lab_flag_gb), languageIconContent(catalog, "en"))
    }

    @Test fun badgeLabelsAreTheCodeInCapitals() {
        assertEquals("PT", badgeLabel("pt-BR"))
        assertEquals("FIL", badgeLabel("fil"))
        assertEquals("?", badgeLabel(" "))
    }

    @Test fun anEmptyCatalogDrawsOnlyBadges() {
        assertEquals(LanguageIconContent.Badge("CA"), languageIconContent(LanguageCatalog(emptyList()), "ca"))
    }
}
