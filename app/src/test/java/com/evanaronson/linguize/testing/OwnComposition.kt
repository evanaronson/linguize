package com.evanaronson.linguize.testing

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.robolectric.Shadows.shadowOf

/**
 * Compose content in a Robolectric activity on its own frame clock and recomposer.
 *
 * `activity.setContent` uses the process-wide Compose frame dispatcher; in a full Robolectric
 * run an earlier test's window can leave it stalled, so recompositions never happen and a test
 * that passes alone fails in the suite (seen with LanguagesScreenTest, TypeToAcceptSettingTest,
 * SpeedOverTimeTest). Driving our own clock makes every frame explicit.
 */
class OwnComposition(private val activity: ComponentActivity) {
    private val frames = BroadcastFrameClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
    private val recomposer = Recomposer(scope.coroutineContext)
    private var frameTime = 0L
    private var view: ComposeView? = null

    init {
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
    }

    /** Replaces the activity's content with [content], freshly composed, and lets it settle. */
    fun show(content: @Composable () -> Unit) {
        view?.disposeComposition()
        view = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent(content)
        }
        activity.setContentView(view)
        settle()
    }

    /** Sends [count] frames: snapshot changes applied, main looper idled, layout run. */
    fun settle(count: Int = 20) = repeat(count) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        frameTime += 16_000_000L
        frames.sendFrame(frameTime)
        view?.let {
            it.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST))
            it.layout(0, 0, it.measuredWidth, it.measuredHeight)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    fun dispose() {
        view?.disposeComposition()
        recomposer.cancel()
        scope.cancel()
    }
}
