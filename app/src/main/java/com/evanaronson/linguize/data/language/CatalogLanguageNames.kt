package com.evanaronson.linguize.data.language

import android.content.Context
import com.evanaronson.linguize.core.language.LanguageCatalog
import com.evanaronson.linguize.core.language.LanguageCodes
import com.evanaronson.linguize.core.language.LanguageNames
import com.evanaronson.linguize.core.language.Languages
import com.evanaronson.linguize.core.language.LocaleLanguageNames
import java.util.Locale

/**
 * Lab, lang-selector: the app's language names, from the language catalog. Every label that
 * names a language (the selector, the card's picker, Settings' writing profiles, Progress,
 * History, Chat) goes through [Languages.names], so installing this at start-up gives them all
 * the catalog's brand names ("Gallicize"), English names ("Bangla", "Odia") and autonyms.
 *
 * In an English UI a name is the catalog's English name; in another language it is the
 * platform's name in that language (CLDR too), falling back to the catalog's English. Brand names
 * are the same in every language. The catalog is read the first time a name is asked for.
 */
class CatalogLanguageNames(load: () -> LanguageCatalog) : LanguageNames {
    private val catalog by lazy(load)

    override fun name(code: String, displayIn: Locale): String {
        val english = catalog.find(code)?.english
        if (displayIn.language == Locale.ENGLISH.language && english != null) return english
        val local = LanguageCodes.displayName(code, displayIn)
        return if (local != code || english == null) local else english
    }

    override fun brand(code: String, displayIn: Locale): String? = catalog.brandName(code)

    override fun native(code: String): String? = catalog.find(code)?.native

    companion object {
        /** Makes the catalog the app's names ([Languages.names]); App calls this once in onCreate. */
        fun install(context: Context) {
            val app = context.applicationContext
            if (Languages.names === LocaleLanguageNames) {
                Languages.names = CatalogLanguageNames { LanguageCatalogAsset.get(app) }
            }
        }
    }
}
