package com.evanaronson.linguize.ui.language

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * The app's language switch, in the top bar of Chat, Write, Progress and History: the
 * current language's small flag and "▾", opening a menu of the writer's languages, each with
 * its flag and name. Screen readers hear "Language: Catalan".
 */
@Composable
fun LanguageSwitch(language: AppLanguage, onSelect: (String) -> Unit) {
    val current by language.current.collectAsState()
    val options by language.options.collectAsState()
    val code = current ?: return
    val shown = options.firstOrNull { it.code == code } ?: LanguageOption(code)
    FilterDropdown(
        selected = shown,
        options = options.ifEmpty { listOf(shown) },
        label = { scopeLanguageName(it.code) },
        description = stringResource(R.string.lab_language_scope_language),
        onSelect = { onSelect(it.code) },
        leading = { LanguageFlag(it) },
        closed = { LanguageFlag(it) },
    )
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
