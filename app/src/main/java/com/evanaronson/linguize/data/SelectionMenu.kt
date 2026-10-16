package com.evanaronson.linguize.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language

/**
 * An item the app adds to the text-selection menu. Each is an activity-alias of
 * menu.CheckActivity in the manifest, with its own label; the alias that launched
 * the check says which language to judge the text as.
 */
enum class MenuEntry(@StringRes val label: Int, val language: Language?, val alias: String) {
    Auto(R.string.menu_auto, null, "LinguizeEntry"),
    Catalan(R.string.menu_catalan, Language.Catalan, "CatalanizeEntry"),
    Castilian(R.string.menu_castilian, Language.Spanish, "CastilianizeEntry"),
}

/**
 * Switches menu entries on and off by enabling their aliases. The package
 * manager's component state is the only record of which entries are on.
 */
class SelectionMenu(private val context: Context) {
    private val packageManager get() = context.packageManager

    /** Reads the saved aliases, repairing an empty menu in the package manager itself. */
    @Synchronized
    fun enabled(): Set<MenuEntry> {
        val enabled = MenuEntry.entries.filterTo(mutableSetOf()) { isEnabled(it) }
        if (enabled.isEmpty()) {
            writeEnabled(MenuEntry.Auto, true)
            enabled += MenuEntry.Auto
        }
        return enabled
    }

    /** Applies one host's intent against the current aliases; the last entry stays enabled. */
    @Synchronized
    fun setEnabled(entry: MenuEntry, enabled: Boolean): Set<MenuEntry> {
        val current = this.enabled()
        if (enabled != (entry in current) && (enabled || current.size > 1)) {
            writeEnabled(entry, enabled)
        }
        return this.enabled()
    }

    private fun writeEnabled(entry: MenuEntry, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        packageManager.setComponentEnabledSetting(component(entry), state, PackageManager.DONT_KILL_APP)
    }

    /** The entry an intent was sent to; anything unrecognised is treated as auto-detect. */
    fun entryFor(component: ComponentName?): MenuEntry =
        MenuEntry.entries.firstOrNull { component?.className == component(it).className } ?: MenuEntry.Auto

    private fun isEnabled(entry: MenuEntry) = when (packageManager.getComponentEnabledSetting(component(entry))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> entry == MenuEntry.Auto // as declared in the manifest
        else -> false
    }

    private fun component(entry: MenuEntry) = ComponentName(context.packageName, "$ALIAS_PACKAGE.${entry.alias}")

    private companion object {
        /** Where the manifest declares the aliases (as `.menu.<alias>`). */
        const val ALIAS_PACKAGE = "com.evanaronson.linguize.menu"
    }
}
