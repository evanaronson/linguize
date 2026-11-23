package com.evanaronson.linguize.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.language.KeyboardLanguage
import com.evanaronson.linguize.data.language.LanguageCatalogAsset
import com.evanaronson.linguize.ui.language.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lab, Composer language: the keyboard hint for a field written in [tag] (a BCP 47 tag from
 * [KeyboardLanguage.tag]); null for no hint, as under Auto-detect. Keyboards that have the
 * language installed (Gboard, SwiftKey, Samsung) switch to its layout and dictionary.
 */
fun keyboardHint(tag: String?): LocaleList? = tag?.let { LocaleList(Locale(it)) }

/** [options] with the keyboard hint for [tag]; unchanged when there is none. */
fun KeyboardOptions.withLanguage(tag: String?): KeyboardOptions =
    keyboardHint(tag)?.let { copy(hintLocales = it) } ?: this

/**
 * The hint tag for a field written in [code] (null: none), with the region of the variety the
 * writer's profile names for it ("es-MX"). Known at once from the selector's cache, then read
 * from the writing profiles off the main thread; just the language where neither can be read.
 */
@Composable
fun rememberKeyboardTag(code: String?): String? {
    val context = LocalContext.current
    val tag = remember(code) {
        mutableStateOf(
            runCatching {
                val variety = code?.let { AppLanguage.of(context).varietyOf(it) }
                KeyboardLanguage.tag(code, variety, variety?.let { LanguageCatalogAsset.get(context) })
            }.getOrElse { KeyboardLanguage.tag(code) },
        )
    }
    LaunchedEffect(code) {
        if (code == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                val variety = App.of(context).settings.writingProfiles.firstOrNull { it.language == code }?.variety
                KeyboardLanguage.tag(code, variety, variety?.let { LanguageCatalogAsset.get(context) })
            }.getOrNull()
        }?.let { tag.value = it }
    }
    return tag.value
}
