package com.evanaronson.linguize.ui.components

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.evanaronson.linguize.testing.OwnComposition
import com.evanaronson.linguize.ui.theme.Linguize
import com.evanaronson.linguize.ui.theme.LinguizeColors
import com.evanaronson.linguize.ui.theme.LinguizeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Guard the actual light/dark combinations used by controls and inset text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ThemeContrastTest {
    @Test fun activeSwitchThumbRemainsDistinctFromItsProofTrackInBothThemes() {
        listOf(false, true).forEach { dark ->
            val palette = palette(dark)
            assertEquals(palette.tokens.onProof, palette.switch.checkedThumbColor)
            assertEquals(palette.tokens.proof, palette.switch.checkedTrackColor)
            assertContrast(palette.switch.checkedThumbColor, palette.switch.checkedTrackColor, 3f, dark)
        }
    }

    @Test fun insetActionsAndMutedTextMeetTextContrastInBothThemes() {
        listOf(false, true).forEach { dark ->
            val palette = palette(dark)
            listOf(palette.tokens.panel, palette.tokens.popup, palette.tokens.card).forEach { background ->
                assertContrast(palette.material.error, background, 4.5f, dark)
                assertContrast(palette.material.onSurfaceVariant, background, 4.5f, dark)
            }
        }
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Float, dark: Boolean) {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        val ratio = (lighter + 0.05f) / (darker + 0.05f)
        assertTrue("Contrast $ratio must be at least $minimum (dark=$dark)", ratio >= minimum)
    }

    private data class Palette(val tokens: LinguizeColors, val material: ColorScheme, val switch: SwitchColors)

    private fun palette(dark: Boolean): Palette {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val compose = OwnComposition(controller.get(), width = 600, height = 400, exactHeight = true)
        var result: Palette? = null
        try {
            compose.show {
                LinguizeTheme(dark) {
                    result = Palette(Linguize.colors, MaterialTheme.colorScheme, brandSwitchColors())
                }
            }
            return requireNotNull(result)
        } finally {
            compose.dispose()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
