package com.evanaronson.linguize.ui.language

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.ui.components.FilterDropdown
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.text.displayName
import java.util.Locale

/**
 * The app's one language selector, in the top bar of the four tabs (Chat, Notes, Progress,
 * History), just before Settings: the current choice's small icon and "▾", opening a menu of
 * **Linguize** (Auto, all languages), **Catalanize**, **Castilianize**, then any other
 * language found in the data, each with its icon and what it means underneath. Screen readers
 * hear "Language: Catalanize, Catalan".
 */
@Composable
fun LanguageSwitch(language: AppLanguage, onSelect: (String?) -> Unit) {
    val current by language.current.collectAsState()
    val options by language.options.collectAsState()
    val shown = current?.let { code -> options.firstOrNull { it.code == code } ?: LanguageOption(code) }
    FilterDropdown(
        selected = shown,
        options = listOf<LanguageOption?>(null) + options,
        label = { brandName(it?.code) },
        detail = { brandDetail(it?.code) },
        description = stringResource(R.string.lab_language_scope_language),
        onSelect = { onSelect(it?.code) },
        leading = { LanguageIcon(it) },
        closed = { LanguageIcon(it) },
    )
}

/** Linguize's mark for Auto (null), else the language's flag or code badge. Decorative. */
@Composable
fun LanguageIcon(option: LanguageOption?, modifier: Modifier = Modifier) {
    if (option == null) {
        Icon(
            painterResource(R.drawable.lab_consistency_auto), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(width = 24.dp, height = 16.dp),
        )
    } else {
        LanguageFlag(option, modifier)
    }
}

/**
 * What the selector calls a choice: **Linguize** for Auto (null), **Catalanize** and
 * **Castilianize** for Catalan and Spanish (the selection-menu names, without "X Claude"),
 * and the language's own name for any other.
 */
@Composable
fun brandName(code: String?): String = brandNameResource(code)?.let { stringResource(it) } ?: scopeLanguageName(code.orEmpty())

/** The brand name's string for Auto (null), Catalan and Spanish; null for any other language. */
@StringRes
fun brandNameResource(code: String?): Int? = when (code) {
    null -> R.string.lab_consistency_brand_auto
    Language.Catalan.code -> R.string.lab_consistency_brand_catalan
    Language.Spanish.code -> R.string.lab_consistency_brand_castilian
    else -> null
}

/** What a brand name means, said under it in the menu and to screen readers: "All languages", "Catalan". */
@Composable
fun brandDetail(code: String?): String? = when (code) {
    null -> stringResource(R.string.lab_language_scope_all)
    Language.Catalan.code, Language.Spanish.code -> scopeLanguageName(code)
    else -> null
}

/** A language's flag, or a small code badge when there is no flag for it. Decorative: the name is said elsewhere. */
@Composable
fun LanguageFlag(option: LanguageOption, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(3.dp)
    val outline = MaterialTheme.colorScheme.outlineVariant
    val flag = flagFor(option.code, option.variety)
    if (flag != null) {
        Image(
            painterResource(flag), contentDescription = null,
            modifier = modifier.size(width = 24.dp, height = 16.dp).clip(shape).border(0.5.dp, outline, shape),
        )
    } else {
        Box(modifier.sizeIn(minWidth = 24.dp, minHeight = 16.dp).border(1.dp, outline, shape), contentAlignment = Alignment.Center) {
            Text(option.code.uppercase(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 3.dp))
        }
    }
}

/** A language's name in the app's language: "Catalan", "Spanish", or the system's name for any other code. */
@Composable
fun scopeLanguageName(code: String): String {
    Language.forCode(code)?.let { return it.displayName() }
    val locale = LocalContext.current.resources.uiLocale
    return Locale.forLanguageTag(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }.ifEmpty { code }
}
