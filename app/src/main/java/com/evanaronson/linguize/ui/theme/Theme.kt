package com.evanaronson.linguize.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.evanaronson.linguize.ui.components.MenuHost
import com.evanaronson.linguize.ui.components.MenuState

/**
 * Proofmark: ink on paper, neutral greys, and one colour, proof vermilion, used only as a
 * mark (a fix's underline, the fix count, the explanation's edge, a switch that is on).
 * Primary actions are ink. App screens sit on a grey ground with borderless white section
 * cards, the way Android settings screens do; the popup card is paper with a hairline.
 * Follows dark mode.
 */
@Composable
fun AppTheme(menuState: MenuState = remember { MenuState() }, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    LinguizeTheme(dark) { MenuHost(menuState, content) }
}

/** The palette without the menu host, for previews and tests. */
@Composable
fun LinguizeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLinguizeColors provides if (dark) DarkTokens else LightTokens) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}

/** Design tokens Material's colour scheme has no role for. */
@Immutable
data class LinguizeColors(
    /** The one colour: marks only, never body text. */
    val proof: Color,
    /** Behind the section cards of app screens. */
    val ground: Color,
    /** Section cards on the ground. */
    val card: Color,
    /** The floating result card. */
    val popup: Color,
    /** Insets inside a card: the explanation, a meaning, an assumption. */
    val panel: Color,
    /** Hairlines between rows inside a section card. */
    val rowDivider: Color,
    /** A rewording's dotted underline. */
    val rewording: Color,
)

val LocalLinguizeColors = staticCompositionLocalOf { LightTokens }

object Linguize {
    val colors: LinguizeColors
        @Composable @ReadOnlyComposable get() = LocalLinguizeColors.current
}

// Ink is also res/values/colors.xml brand_ink, which the launcher icon and wordmark use.
private val Ink = Color(0xFF111111)
private val Paper = Color(0xFFFAFAF8)
private val Proof = Color(0xFFC4321A)
private val ProofLight = Color(0xFFFF8A70)

private val LightTokens = LinguizeColors(
    proof = Proof,
    ground = Color(0xFFF0F0EC),
    card = Color.White,
    popup = Color(0xFFFAFAF7),
    panel = Color(0xFFEAEAE6),
    rowDivider = Color(0xFFE8E8E4),
    rewording = Color(0xFF777772),
)

private val DarkTokens = LinguizeColors(
    proof = ProofLight,
    ground = Ink,
    card = Color(0xFF1D1D1C),
    popup = Color(0xFF1D1D1C),
    panel = Color(0xFF272725),
    rowDivider = Color(0xFF33332F),
    rewording = Color(0xFF9A9A94),
)

private val Light = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    primaryContainer = Color(0xFFE4E4E0),
    onPrimaryContainer = Ink,
    inversePrimary = Paper,
    secondary = Color(0xFF4A4A47),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E4E0),
    onSecondaryContainer = Ink,
    tertiary = Color(0xFF4A4A47),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE4E4E0),
    onTertiaryContainer = Ink,
    error = Proof,
    onError = Color.White,
    errorContainer = Color(0xFFF6DDD8),
    onErrorContainer = Ink,
    background = LightTokens.ground,
    onBackground = Ink,
    surface = LightTokens.ground,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE8E8E4),
    onSurfaceVariant = Color(0xFF5C5C58),
    surfaceTint = Color.Transparent,
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    outline = Color(0xFF8A8A86),
    outlineVariant = Color(0xFFD9D9D5),
    scrim = Color.Black,
    surfaceBright = Paper,
    surfaceDim = Color(0xFFE2E2DE),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F5F2),
    surfaceContainer = Color(0xFFF0F0EC),
    surfaceContainerHigh = Color(0xFFEAEAE6),
    surfaceContainerHighest = Color(0xFFE4E4E0),
)

private val Dark = darkColorScheme(
    primary = Paper,
    onPrimary = Ink,
    primaryContainer = Color(0xFF32322F),
    onPrimaryContainer = Paper,
    inversePrimary = Ink,
    secondary = Color(0xFFC8C8C4),
    onSecondary = Ink,
    secondaryContainer = Color(0xFF32322F),
    onSecondaryContainer = Paper,
    tertiary = Color(0xFFC8C8C4),
    onTertiary = Ink,
    tertiaryContainer = Color(0xFF32322F),
    onTertiaryContainer = Paper,
    error = ProofLight,
    onError = Ink,
    errorContainer = Color(0xFF5C1D12),
    onErrorContainer = Paper,
    background = DarkTokens.ground,
    onBackground = Paper,
    surface = DarkTokens.ground,
    onSurface = Paper,
    surfaceVariant = Color(0xFF33332F),
    onSurfaceVariant = Color(0xFFB0B0AB),
    surfaceTint = Color.Transparent,
    inverseSurface = Paper,
    inverseOnSurface = Ink,
    outline = Color(0xFF85857F),
    outlineVariant = Color(0xFF3A3A36),
    scrim = Color.Black,
    surfaceBright = Color(0xFF383835),
    surfaceDim = Ink,
    surfaceContainerLowest = Color(0xFF0B0B0B),
    surfaceContainerLow = Color(0xFF191918),
    surfaceContainer = Color(0xFF1D1D1C),
    surfaceContainerHigh = Color(0xFF272725),
    surfaceContainerHighest = Color(0xFF32322F),
)
