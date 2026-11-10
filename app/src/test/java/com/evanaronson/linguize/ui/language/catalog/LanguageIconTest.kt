package com.evanaronson.linguize.ui.language.catalog

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.language.LanguageCatalog
import com.evanaronson.linguize.data.language.LanguageCatalogAsset
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The catalog's circular icons: every key it names is drawn, every language gets a flag or one
 * of Linguize's emblems (never a code badge), and an unknown code gets the speech bubble.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LanguageIconTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val catalog = LanguageCatalogAsset.get(context)

    private val withoutFlag = listOf("ae", "cr", "cu", "ff", "ik", "iu", "nv", "oj", "os", "pi")

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

    @Test fun everyEmblemTheCatalogNamesIsAVectorDrawable() {
        val keys = catalog.all().mapNotNull { it.emblem }
        keys.forEach { key ->
            val drawable = emblemDrawable(key)
            assertNotNull(key, drawable)
            val name = context.resources.getResourceEntryName(drawable!!)
            assertTrue(name, File("src/main/res/drawable/$name.xml").isFile)
        }
        assertEquals(keys.toSet(), emblemKeys)
    }

    @Test fun everyCatalogLanguageHasAFlagOrAnEmblemNeverABadge() {
        catalog.all().forEach { language ->
            val content = languageIconContent(catalog, language.code)
            assertNotEquals(language.code, LanguageIconContent.Unknown, content)
            // Exactly one of the two: a language with a flag has no emblem.
            assertTrue(language.code, (language.flag == null) == (language.emblem != null))
        }
        assertEquals(withoutFlag, catalog.all().filter { it.flag == null }.map { it.code }.sorted())
    }

    @Test fun theTenLanguagesWithoutAFlagGetTheirEmblems() {
        val drawn = withoutFlag.associateWith { (languageIconContent(catalog, it) as LanguageIconContent.Emblem).drawable }
        assertEquals(R.drawable.lab_emblem_nv, drawn["nv"])
        assertEquals(R.drawable.lab_emblem_inuit, drawn["iu"])
        assertEquals(R.drawable.lab_emblem_inuit, drawn["ik"])
        assertEquals(R.drawable.lab_emblem_os, drawn["os"])
        assertEquals(R.drawable.lab_emblem_pi, drawn["pi"])
        // Inuktitut and Inupiaq share the Inuit flag; the others are each their own.
        assertEquals(9, drawn.values.toSet().size)
        assertNull(flagResource(catalog, "ae"))
    }

    @Test fun flagsFollowTheLanguageAndTheSpanishVariety() {
        assertEquals(R.drawable.lab_flag_es_ct, flagResource(catalog, "ca"))
        assertEquals(R.drawable.lab_flag_es, flagResource(catalog, "es", "Peninsular"))
        assertEquals(R.drawable.lab_flag_mx, flagResource(catalog, "es", "Mexican"))
        assertEquals(R.drawable.lab_flag_ar, flagResource(catalog, "es", "Argentinian"))
        assertEquals(R.drawable.lab_flag_co, flagResource(catalog, "es", "Colombian"))
        assertEquals(R.drawable.lab_flag_pt, flagResource(catalog, "pt"))
        assertEquals(LanguageIconContent.Flag(R.drawable.lab_flag_mx), languageIconContent(catalog, "es", "Mexican Spanish"))
        assertEquals(LanguageIconContent.Flag(R.drawable.lab_flag_gb), languageIconContent(catalog, "en"))
    }

    @Test fun anUnknownCodeGetsTheGenericGlyph() {
        assertEquals(LanguageIconContent.Unknown, languageIconContent(catalog, "qq"))
        assertEquals(LanguageIconContent.Unknown, languageIconContent(catalog, null))
        assertEquals(LanguageIconContent.Unknown, languageIconContent(LanguageCatalog(emptyList()), "ca"))
        assertTrue(File("src/main/res/drawable/lab_emblem_unknown.xml").isFile)
    }

    @Test fun theOldFlagMappingAndItsDrawablesAreGone() {
        assertFalse(File("src/main/java/com/evanaronson/linguize/ui/language/LanguageFlags.kt").exists())
        listOf("ar", "ca", "co", "es", "mx").forEach {
            assertFalse(it, File("src/main/res/drawable/lab_language_scope_flag_$it.xml").exists())
        }
    }
}
