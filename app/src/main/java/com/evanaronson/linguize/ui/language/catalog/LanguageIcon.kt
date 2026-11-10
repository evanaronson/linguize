package com.evanaronson.linguize.ui.language.catalog

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.language.LanguageCatalog
import com.evanaronson.linguize.data.language.LanguageCatalogAsset

/**
 * What [LanguageIcon] draws for a language: its circular flag, the emblem Linguize draws for a
 * language no flag fits (Navajo, Inuktitut, Pali, …), or, for a code the catalog doesn't know,
 * a friendly speech bubble. Never a code badge.
 */
sealed interface LanguageIconContent {
    data class Flag(@param:DrawableRes val drawable: Int) : LanguageIconContent
    data class Emblem(@param:DrawableRes val drawable: Int) : LanguageIconContent
    data object Unknown : LanguageIconContent
}

/**
 * The circular flag for [code] (and its [variety], so Spanish "Mexican" is Mexico's), or null
 * when the catalog has no flag for it or doesn't know the code.
 */
@DrawableRes
fun flagResource(catalog: LanguageCatalog, code: String?, variety: String? = null): Int? =
    catalog.flagKey(code, variety)?.let(::flagDrawable)

/** The flag when there is one, else the language's emblem, else (an unknown code) [LanguageIconContent.Unknown]. */
fun languageIconContent(catalog: LanguageCatalog, code: String?, variety: String? = null): LanguageIconContent =
    flagResource(catalog, code, variety)?.let { LanguageIconContent.Flag(it) }
        ?: catalog.emblemKey(code)?.let(::emblemDrawable)?.let { LanguageIconContent.Emblem(it) }
        ?: LanguageIconContent.Unknown

/**
 * A language's small circular icon, from the app's language catalog: its flag (following the
 * writer's [variety]: Castilianize is Mexico's for Mexican Spanish) or emblem clipped to a circle
 * with a hairline outline, or, for a code the catalog doesn't know, a speech bubble in the same
 * circle. Always exactly [size] square. Decorative: say the language's name beside it.
 */
@Composable
fun LanguageIcon(code: String?, variety: String? = null, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val content = remember(code, variety) {
        languageIconContent(LanguageCatalogAsset.get(context), code, variety)
    }
    val outline = MaterialTheme.colorScheme.outlineVariant
    when (content) {
        is LanguageIconContent.Flag -> Picture(content.drawable, size, modifier)
        is LanguageIconContent.Emblem -> Picture(content.drawable, size, modifier)
        LanguageIconContent.Unknown -> Box(
            modifier.size(size).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.lab_emblem_unknown), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.62f),
            )
        }
    }
}

@Composable
private fun Picture(@DrawableRes drawable: Int, size: Dp, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Image(
        painterResource(drawable), contentDescription = null,
        modifier = modifier.size(size).clip(CircleShape).border(0.5.dp, outline, CircleShape),
    )
}
