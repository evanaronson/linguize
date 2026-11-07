package com.evanaronson.linguize.ui.history

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.ui.theme.Linguize
import com.evanaronson.linguize.ui.theme.LinguizeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Real pointer events exercise the drag and its release, rather than setting dismissal state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SwipeToDeleteGestureTest {
    @Test
    fun slowSwipeWaitsForReleaseAndDeletesOnce() = withSwipe {
        down(at(0.9f))
        moveSlowlyTo(at(0.2f))
        hold()

        // currentValue changes before this point, but the finger still owns the row.
        assertEquals("Holding a drag must not delete the row", 0, deletes)
        up()

        assertEquals(1, deletes)
        assertEquals(0, opens)
        hold()
        assertEquals("The completed gesture is handled once", 1, deletes)
    }

    @Test
    fun slowSwipeCanReturnBeforeReleaseWithoutDeleting() = withSwipe {
        down(at(0.9f))
        moveSlowlyTo(at(0.2f))
        hold()
        assertEquals(0, deletes)

        moveSlowlyTo(at(0.85f))
        hold()
        up()

        assertEquals("Crossing the midpoint does not commit an unfinished gesture", 0, deletes)
        assertEquals(0, opens)
    }

    @Test
    fun shortSlowDragKeepsTheRowAndAnOrdinaryTapStillOpensIt() = withSwipe {
        down(at(0.9f))
        moveSlowlyTo(at(0.84f)) // About 19dp: past touch slop, below the 56dp dismissal threshold.
        hold()
        up()
        assertEquals(0, deletes)
        assertEquals(0, opens)

        down(at(0.5f))
        up()
        assertEquals(1, opens)
        assertEquals(0, deletes)
    }

    private fun withSwipe(block: SwipeFixture.() -> Unit) {
        val fixture = SwipeFixture()
        try {
            fixture.block()
        } finally {
            fixture.close()
        }
    }

    private class SwipeFixture {
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java)
            .setup().visible().windowFocusChanged(true)
        private val activity = controller.get()
        // Compose's default Android frame dispatcher can outlive Robolectric's Choreographer.
        // Own frame delivery so multiple pointer tests do not depend on an earlier window.
        private val frames = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
        private val recomposer = Recomposer(scope.coroutineContext)
        private val root = ComposeView(activity)
        private var frameTime = 0L
        private var bounds = Rect.Zero
        private var point = Offset.Zero
        private var downTime = 0L
        var deletes = 0
            private set
        var opens = 0
            private set

        init {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            root.setParentCompositionContext(recomposer)
            root.setContent {
                LinguizeTheme(dark = false) {
                    Box(Modifier.fillMaxSize()) {
                        // The wrapper stays put while its child follows the pointer.
                        Box(Modifier.width(320.dp).onGloballyPositioned { bounds = it.boundsInRoot() }) {
                            SwipeToDelete(onDelete = { deletes++ }) {
                                Box(Modifier.width(320.dp).height(72.dp)
                                    .background(Linguize.colors.card)
                                    .clickable { opens++ })
                            }
                        }
                    }
                }
            }
            activity.setContentView(root)
            settle()
            assertTrue("The swipe row is laid out", bounds.width > 0f && bounds.height > 0f)
        }

        fun at(fraction: Float) = Offset(bounds.left + bounds.width * fraction, bounds.center.y)

        fun down(position: Offset) {
            point = position
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN)
        }

        fun moveSlowlyTo(position: Offset) {
            val start = point
            // 2.4 seconds per drag, below fling speed, with a frame after every move.
            for (step in 1..24) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                point = start + (position - start) * (step / 24f)
                send(MotionEvent.ACTION_MOVE)
            }
        }

        fun hold() {
            // Release at rest: deletion has to follow distance, not a fast fling.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            settle()
        }

        fun up() = send(MotionEvent.ACTION_UP)

        private fun send(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                root.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
            settle()
        }

        private fun settle() {
            fun flushFrames() {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idle()
                var delivered = 0
                while (recomposer.hasPendingWork || frames.hasAwaiters) {
                    check(delivered++ < 120) { "Swipe composition did not become idle" }
                    frameTime += 16_666_667L
                    frames.sendFrame(frameTime)
                    Snapshot.sendApplyNotifications()
                    shadowOf(Looper.getMainLooper()).idle()
                }
            }
            flushFrames()
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 1920)
            flushFrames()
        }

        fun close() {
            root.disposeComposition()
            recomposer.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
    }
}
