package com.evanaronson.linguize.ui.language.catalog

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.core.language.LanguageCatalog
import com.evanaronson.linguize.data.language.LanguageCatalogAsset

/** What [LanguageIcon] draws for a language: its circular flag, or a circular badge with its code. */
sealed interface LanguageIconContent {
    data class Flag(@DrawableRes val drawable: Int) : LanguageIconContent
    data class Badge(val label: String) : LanguageIconContent
}

/**
 * The circular flag for [code] (and its [variety], so Spanish "Mexican" is Mexico's), or null
 * when the catalog has no flag for it or doesn't know the code.
 */
@DrawableRes
fun flagResource(catalog: LanguageCatalog, code: String?, variety: String? = null): Int? =
    catalog.flagKey(code, variety)?.let(::flagDrawable)

/** The flag when there is one, else the code badge ("EN", "?" for a blank code). */
fun languageIconContent(catalog: LanguageCatalog, code: String?, variety: String? = null): LanguageIconContent =
    flagResource(catalog, code, variety)?.let { LanguageIconContent.Flag(it) }
        ?: LanguageIconContent.Badge(badgeLabel(code))

/** A code badge's text: the code's first part in capitals ("pt-BR" is "PT"), at most three letters. */
fun badgeLabel(code: String?): String =
    code?.trim()?.substringBefore('-')?.substringBefore('_')?.take(3)?.uppercase()?.ifEmpty { null } ?: "?"

/**
 * A language's small circular icon, from the app's language catalog: its flag clipped to a
 * circle with a hairline outline, or, for a language with no flag, a circle of the same [size]
 * with its code. Always exactly [size] square. Decorative: say the language's name beside it.
 */
@Composable
fun LanguageIcon(code: String?, variety: String? = null, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val content = remember(code, variety) {
        languageIconContent(LanguageCatalogAsset.get(context), code, variety)
    }
    val outline = MaterialTheme.colorScheme.outlineVariant
    when (content) {
        is LanguageIconContent.Flag -> Image(
            painterResource(content.drawable), contentDescription = null,
            modifier = modifier.size(size).clip(CircleShape).border(0.5.dp, outline, CircleShape),
        )
        is LanguageIconContent.Badge -> Box(
            modifier.size(size).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Sized to the circle, not the font scale, so three letters always fit.
            val fontSize = with(LocalDensity.current) { (size * if (content.label.length > 2) 0.3f else 0.38f).toSp() }
            Text(
                content.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = fontSize,
                lineHeight = fontSize,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
