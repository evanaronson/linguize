package com.evanaronson.linguize.menu

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.quicktile.ClipboardJob
import com.evanaronson.linguize.ui.quicktile.QuickLaunch

/**
 * The Quick Settings tile: for apps where neither the selection menu nor the accessibility
 * button works, copy the text, pull down the shade and tap. The tile opens
 * [ClipboardActivity], which asks whether the copied text is the writer's own (Check) or a
 * message they got (Understand). It is a button, not a switch, so it always shows inactive.
 */
class ClipboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.app_name)
            subtitle = getString(R.string.lab_quick_tile_tile_subtitle)
            contentDescription = getString(R.string.lab_quick_tile_tile_description)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // Copied text is only read once the phone is unlocked and the card has focus.
        if (isLocked) unlockAndRun(::open) else open()
    }

    private fun open() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(pendingIntent(this))
        } else {
            openBeforeAndroid14()
        }
    }

    /** The PendingIntent overload needs Android 14; this one throws there, so it runs only before. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openBeforeAndroid14() = startActivityAndCollapse(intent(this))

    internal companion object {
        fun intent(context: Context): Intent = QuickLaunch.intent(context, ClipboardJob.Choose)

        /** Android 14+ collapses the shade and starts the card only through a PendingIntent. */
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
