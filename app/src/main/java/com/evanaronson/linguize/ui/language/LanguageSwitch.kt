package com.evanaronson.linguize.ui.language

import androidx.annotation.StringRes
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.language.Languages
import com.evanaronson.linguize.ui.components.FilterDropdown
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.text.displayName

/**
 * The app's one language selector, in the top bar of the four tabs (Chat, Notes, Progress,
 * History), just before Settings: the current choice's small circular icon and "▾", opening a
 * menu of **Linguize** (Auto-detect), then the writer's languages in their order
 * ([AppLanguage.options]), each with its brand name, icon and what it means underneath
 * ("Catalan"), then **Add languages…** ([onAddLanguages], the Languages page). Screen readers
 * hear "Language: Catalanize, Catalan".
 */
@Composable
fun LanguageSwitch(language: AppLanguage, onAddLanguages: (() -> Unit)? = null, onSelect: (String?) -> Unit) {
    val current by language.current.collectAsState()
    val options by language.options.collectAsState()
    val shown = current?.let { code -> options.firstOrNull { it.code == code } ?: LanguageOption(code) }
    FilterDropdown(
        selected = shown,
        options = selectorOptions(options),
        label = { brandName(it?.code) },
        detail = { brandDetail(it?.code) },
        description = stringResource(R.string.lab_language_scope_language),
        onSelect = { onSelect(it?.code) },
        leading = { LanguageIcon(it) },
        closed = { LanguageIcon(it) },
        footer = onAddLanguages?.let { add ->
            { close ->
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.lab_lang_selector_add)) },
                    onClick = { close(); add() },
                    leadingIcon = { Icon(painterResource(R.drawable.lab_chat_ic_add), contentDescription = null) },
                )
            }
        },
    )
}

/** The selector's short list: Linguize (Auto, null) first, then [options] in the writer's order. */
fun selectorOptions(options: List<LanguageOption>): List<LanguageOption?> = listOf<LanguageOption?>(null) + options

/**
 * Linguize's circular mark for Auto (null), else the language's circular flag or emblem from the
 * catalog, following the variety for Castilianize's flag. Both are drawn in `ui/language/catalog`
 * at [com.evanaronson.linguize.ui.language.catalog.LanguageIconAlpha]. Decorative.
 */
@Composable
fun LanguageIcon(option: LanguageOption?, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    if (option == null) {
        com.evanaronson.linguize.ui.language.catalog.AutoLanguageIcon(size, modifier)
    } else {
        com.evanaronson.linguize.ui.language.catalog.LanguageIcon(option.code, option.variety, size, modifier)
    }
}

/**
 * What the selector calls a choice: **Linguize** for Auto (null), **Catalanize** and
 * **Castilianize** for Catalan and Spanish (the selection-menu names, without "X Claude"),
 * the language catalog's brand name for any other ([Languages.names]: "Gallicize"), and the
 * language's own name for a code the catalog doesn't know.
 */
@Composable
fun brandName(code: String?): String = brandNameResource(code)?.let { stringResource(it) }
    ?: code?.let { catalogBrand(it) } ?: scopeLanguageName(code.orEmpty())

/** The language catalog's brand name for [code] ([Languages.names]), if it gives one. */
@Composable
private fun catalogBrand(code: String): String? =
    Languages.names.brand(code, LocalContext.current.resources.uiLocale)

/** The brand name's string for Auto (null), Catalan and Spanish; null for any other language. */
@StringRes
fun brandNameResource(code: String?): Int? = when (code) {
    null -> R.string.lab_consistency_brand_auto
    Language.Catalan.code -> R.string.lab_consistency_brand_catalan
    Language.Spanish.code -> R.string.lab_consistency_brand_castilian
    else -> null
}

/** What a brand name means, said under it in the menu and to screen readers: "Auto-detect", "Catalan". */
@Composable
fun brandDetail(code: String?): String? = when (code) {
    null -> stringResource(R.string.lab_language_scope_all)
    Language.Catalan.code, Language.Spanish.code -> scopeLanguageName(code)
    else -> if (catalogBrand(code) != null) scopeLanguageName(code) else null
}

/** A language's name in the app's language: "Catalan", "Spanish", or [Languages.names]' name for any other code. */
@Composable
fun scopeLanguageName(code: String): String = Language.forCode(code)?.displayName() ?: code
