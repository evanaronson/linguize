package com.evanaronson.linguize.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.evanaronson.linguize.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class HistoryExportEffectTest {
    @Test
    fun aFinishedExportWaitsForResumeAndIsSharedOnlyOnceAcrossConsumerRecreation() = runBlocking(FrameClock) {
        withTimeout(5_000) {
            val screen = ExportScreen(this)
            try {
                // The History owner stays composed without any export footer/card in view.
                val ready = screen.export()
                screen.settle()
                assertEquals(ready, screen.settings.exported)
                assertNull(shadowOf(screen.activity).nextStartedActivity)

                screen.owner.lifecycle.currentState = Lifecycle.State.RESUMED
                screen.settle()
                val chooser = checkNotNull(shadowOf(screen.activity).nextStartedActivity)
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                val send = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
                assertEquals(Intent.ACTION_SEND, send.action)
                assertEquals(ready.uri, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
                assertEquals(ready.uri, send.clipData!!.getItemAt(0).uri)
                assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertNull(screen.settings.exported)
                assertNull(screen.settings.takeExport())

                screen.recreateConsumer()
                screen.owner.lifecycle.currentState = Lifecycle.State.STARTED
                screen.settle()
                screen.owner.lifecycle.currentState = Lifecycle.State.RESUMED
                screen.settle()
                assertNull(shadowOf(screen.activity).nextStartedActivity)
                assertTrue("The receiving app still has time to read the shared file", ready.file.exists())
            } finally {
                screen.close()
            }
        }
    }

    @Test
    fun anExportThatWaitsTooLongIsDiscardedOnResumeInsteadOfOpeningALateShareSheet() = runBlocking(FrameClock) {
        withTimeout(5_000) {
            val screen = ExportScreen(this)
            try {
                val ready = screen.export()
                screen.settle()
                assertEquals(ready, screen.settings.exported)
                assertTrue(ready.file.exists())

                ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
                screen.owner.lifecycle.currentState = Lifecycle.State.RESUMED
                screen.settle()
                assertNull(screen.settings.exported)
                assertNull(shadowOf(screen.activity).nextStartedActivity)
                await { !ready.file.exists() }
                assertFalse(screen.app.exports.isCurrent(ready.file))

                screen.recreateConsumer()
                screen.settle()
                assertNull(shadowOf(screen.activity).nextStartedActivity)
            } finally {
                screen.close()
            }
        }
    }

    /** A stable History composition, independent of Android views or lazy-item visibility. */
    private class ExportScreen(scope: CoroutineScope) {
        val app = RuntimeEnvironment.getApplication() as App
        // Each Robolectric app has a new cache directory. Attach the manifest provider as
        // Android does, so FileProvider drops the previous test's cached authority roots.
        private val provider = Robolectric.buildContentProvider(FileProvider::class.java).create()
        val settings = SettingsViewModel(app)
        private val store = ViewModelStore().apply { put("settings", settings) }
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity: ComponentActivity = controller.get()
        val owner = TestLifecycleOwner()
        private val recomposer = Recomposer(scope.coroutineContext)
        private val runner = scope.launch { recomposer.runRecomposeAndApplyChanges() }
        private var composition = attach()

        private fun attach() = Composition(EmptyApplier(), recomposer).also { composition ->
            composition.setContent {
                CompositionLocalProvider(LocalContext provides activity, LocalLifecycleOwner provides owner) {
                    HistoryExportEffect(settings)
                }
            }
        }

        suspend fun export(): Export.Ready {
            settings.exportHistory()
            await { settings.exported != null }
            val ready = settings.exported
            assertTrue("History must be written successfully before exercising its delivery", ready is Export.Ready)
            return ready as Export.Ready
        }

        fun recreateConsumer() {
            composition.dispose()
            composition = attach()
        }

        suspend fun settle() {
            // The lifecycle flow and a consumed export each schedule another composition.
            repeat(3) {
                shadowOf(Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                yield()
                recomposer.awaitIdle()
            }
        }

        suspend fun close() {
            composition.dispose()
            recomposer.cancel()
            runner.cancelAndJoin()
            store.clear()
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            controller.pause().stop().destroy()
            app.exports.clear()
            provider.shutdown()
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
    }

    private object FrameClock : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            yield()
            return onFrame(System.nanoTime())
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    companion object {
        private suspend fun await(condition: () -> Boolean) {
            withTimeout(5_000) {
                while (!condition()) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(1)
                }
            }
        }
    }
}
