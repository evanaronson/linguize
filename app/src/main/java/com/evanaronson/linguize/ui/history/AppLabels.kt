package com.evanaronson.linguize.ui.history

import android.content.pm.PackageManager
import android.os.Build

/**
 * App names by package name, looked up once each. Asks the package manager, so call
 * it off the main thread. Apps are visible through the manifest's `<queries>`.
 */
internal class AppLabels(private val packages: PackageManager) {
    private val known = mutableMapOf<String, String?>()

    /** The app's name, or null when [packageName] is null or the app isn't installed. */
    fun of(packageName: String?): String? {
        if (packageName == null) return null
        return synchronized(known) {
            if (packageName in known) known[packageName] else lookUp(packageName).also { known[packageName] = it }
        }
    }

    private fun lookUp(packageName: String): String? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packages.getApplicationInfo(packageName, 0)
        }
        packages.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
