package com.evanaronson.linguize.ui.settings

import android.content.Intent
import androidx.compose.runtime.staticCompositionLocalOf

/** Presentation and external launches come from the host; settings logic is shared. */
internal data class SettingsHost(val embedded: Boolean = false, val openExternal: ((Intent) -> Unit)? = null)
internal val LocalSettingsHost = staticCompositionLocalOf { SettingsHost() }
