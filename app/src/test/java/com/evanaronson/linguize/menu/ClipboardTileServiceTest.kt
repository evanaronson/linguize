package com.evanaronson.linguize.menu

import android.app.Application
import android.service.quicksettings.Tile
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.quicktile.ClipboardJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClipboardTileServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    @Config(sdk = [33])
    fun tappingTheTileBeforeAndroid14StartsTheChoiceCard() {
        val service = Robolectric.buildService(ClipboardTileService::class.java).create().get()
        service.onClick()
        val started = shadowOf(context).nextStartedActivity
        assertEquals(ClipboardJob.Choose, ClipboardJob.of(started))
        assertEquals(context.packageName, started.`package`)
    }

    @Test
    @Config(sdk = [34, 35])
    fun android14AndNewerGetAnImmutablePendingIntentForTheChoiceCard() {
        val pending = ClipboardTileService.pendingIntent(context)
        val shadow = shadowOf(pending)
        assertTrue(shadow.isActivity)
        assertTrue(shadow.isImmutable)
        assertEquals(ClipboardJob.Choose, ClipboardJob.of(shadow.savedIntent))
    }

    @Test
    @Config(sdk = [35])
    fun theTileIsAButtonNamedForTheApp() {
        val controller = Robolectric.buildService(ClipboardTileService::class.java).create()
        val service = controller.get()
        service.onStartListening()
        val tile = service.qsTile
        assertEquals(Tile.STATE_INACTIVE, tile.state)
        assertEquals(context.getString(R.string.app_name), tile.label)
        assertEquals(context.getString(R.string.lab_quick_tile_tile_subtitle), tile.subtitle)
    }
}
