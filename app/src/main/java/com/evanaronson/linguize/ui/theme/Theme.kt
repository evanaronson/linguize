package com.evanaronson.linguize.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import com.evanaronson.linguize.ui.components.MenuHost
import com.evanaronson.linguize.ui.components.MenuState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The brand: ink on paper, neutral greys, and cobalt as the one colour (buttons,
 * selection, rewordings). Fixes keep the error red. Follows dark mode.
 */
@Composable
fun AppTheme(menuState: MenuState = remember { MenuState() }, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light) {
        MenuHost(menuState, content)
    }
}

// The same values as res/values/colors.xml, which the launcher icon and wordmark use.
private val Cobalt = Color(0xFF3340F0)
private val CobaltLight = Color(0xFF7C84FF)
private val Ink = Color(0xFF111111)
private val Paper = Color(0xFFFAFAF8)

private val Light = lightColorScheme(
    primary = Cobalt,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E5FD),
    onPrimaryContainer = Color(0xFF151B66),
    inversePrimary = CobaltLight,
    secondary = Color(0xFF4A4A47),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8E4),
    onSecondaryContainer = Ink,
    tertiary = Color(0xFF4A4A47),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8E8E4),
    onTertiaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE8E8E4),
    onSurfaceVariant = Color(0xFF5C5C58),
    surfaceTint = Color.Transparent,
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    outline = Color(0xFF8A8A86),
    outlineVariant = Color(0xFFD9D9D5),
    surfaceBright = Paper,
    surfaceDim = Color(0xFFE2E2DE),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F5F2),
    surfaceContainer = Color(0xFFF0F0EC),
    surfaceContainerHigh = Color(0xFFEAEAE6),
    surfaceContainerHighest = Color(0xFFE4E4E0),
)

private val Dark = darkColorScheme(
    primary = CobaltLight,
    onPrimary = Ink,
    primaryContainer = Color(0xFF262E8C),
    onPrimaryContainer = Color(0xFFE3E5FD),
    inversePrimary = Cobalt,
    secondary = Color(0xFFC8C8C4),
    onSecondary = Ink,
    secondaryContainer = Color(0xFF33332F),
    onSecondaryContainer = Paper,
    tertiary = Color(0xFFC8C8C4),
    onTertiary = Ink,
    tertiaryContainer = Color(0xFF33332F),
    onTertiaryContainer = Paper,
    background = Ink,
    onBackground = Paper,
    surface = Ink,
    onSurface = Paper,
    surfaceVariant = Color(0xFF33332F),
    onSurfaceVariant = Color(0xFFB0B0AB),
    surfaceTint = Color.Transparent,
    inverseSurface = Paper,
    inverseOnSurface = Ink,
    outline = Color(0xFF85857F),
    outlineVariant = Color(0xFF3A3A36),
    surfaceBright = Color(0xFF383835),
    surfaceDim = Ink,
    surfaceContainerLowest = Color(0xFF0B0B0B),
    surfaceContainerLow = Color(0xFF191918),
    surfaceContainer = Color(0xFF1D1D1C),
    surfaceContainerHigh = Color(0xFF272725),
    surfaceContainerHighest = Color(0xFF32322F),
)

