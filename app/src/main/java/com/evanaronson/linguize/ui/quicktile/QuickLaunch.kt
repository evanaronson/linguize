package com.evanaronson.linguize.ui.quicktile

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.home.HomeActivity
import kotlinx.coroutines.launch

/** What a launch from the Quick Settings tile or a launcher shortcut asks for. */
enum class ClipboardJob(val action: String) {
    /** Check the copied text as the writer's own writing. */
    Check("com.evanaronson.linguize.action.CHECK_COPIED_TEXT"),

    /** Translate and explain the copied text, a message from someone else. */
    Understand("com.evanaronson.linguize.action.UNDERSTAND_COPIED_TEXT"),

    /** Show the copied text and let the writer pick Check or Understand (the tile). */
    Choose("com.evanaronson.linguize.action.CHOOSE_COPIED_TEXT"),
    ;

    companion object {
        /** The job an intent asks for; null for anything else, which the host refuses. */
        fun of(intent: Intent?): ClipboardJob? = entries.firstOrNull { it.action == intent?.action }
    }
}

/**
 * Intents for the clipboard host in `menu`. They name an action and this package rather
 * than the class, so app screens and the launcher shortcuts don't depend on the host.
 */
object QuickLaunch {
    /** Opens the clipboard host in a task of its own, over whatever app is showing. */
    fun intent(context: Context, job: ClipboardJob): Intent = Intent(job.action)
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Shortcut ids, stable across releases so pinned shortcuts keep working. */
    const val SHORTCUT_CHECK = "lab_quick_tile_check"
    const val SHORTCUT_UNDERSTAND = "lab_quick_tile_understand"
    const val SHORTCUT_TRY_IT = "lab_quick_tile_try_it"

    /** The launcher shortcuts, in the order they appear when the icon is long-pressed. */
    fun shortcuts(context: Context): List<ShortcutInfo> = listOf(
        shortcut(context, SHORTCUT_CHECK, R.string.lab_quick_tile_shortcut_check,
            R.string.lab_quick_tile_shortcut_check_long, R.drawable.lab_quick_tile_check, rank = 0,
            intent(context, ClipboardJob.Check)),
        shortcut(context, SHORTCUT_UNDERSTAND, R.string.lab_quick_tile_shortcut_understand,
            R.string.lab_quick_tile_shortcut_understand_long, R.drawable.lab_quick_tile_understand, rank = 1,
            intent(context, ClipboardJob.Understand)),
        shortcut(context, SHORTCUT_TRY_IT, R.string.lab_quick_tile_shortcut_try_it,
            R.string.lab_quick_tile_shortcut_try_it_long, R.drawable.lab_quick_tile_try_it, rank = 2,
            HomeActivity.writeIntent(context).setAction(Intent.ACTION_VIEW)),
    )

    /** [publish], off the main thread so it never delays the app's first frame. */
    fun publishInBackground(context: Context) {
        val app = context.applicationContext as? App ?: return
        app.appScope.launch { publish(app) }
    }

    /**
     * Adds or refreshes this feature's launcher shortcuts, leaving any others alone. Run
     * when the app opens, so the labels follow the current language.
     */
    fun publish(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        try {
            shortcuts(context).forEach(manager::pushDynamicShortcut)
        } catch (_: RuntimeException) {
            // Locked user or launcher limits: the shortcuts are a convenience, never a failure.
        }
    }

    private fun shortcut(
        context: Context,
        id: String,
        shortLabel: Int,
        longLabel: Int,
        icon: Int,
        rank: Int,
        intent: Intent,
    ): ShortcutInfo = ShortcutInfo.Builder(context, id)
        .setShortLabel(context.getString(shortLabel))
        .setLongLabel(context.getString(longLabel))
        .setIcon(Icon.createWithResource(context, icon))
        .setRank(rank)
        .setIntent(intent)
        .build()
}
