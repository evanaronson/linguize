package com.evanaronson.linguize.testing

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
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
 *
 * Each frame measures the content within [width] by [height] pixels: exactly [width] wide unless
 * [exactWidth] is false, at most [height] tall unless [exactHeight] is set. Both loose matches
 * `activity.setContent`, whose wrap-content host lets `Modifier.width` narrow the content.
 */
class OwnComposition(
    private val activity: ComponentActivity,
    private val width: Int = 1080,
    private val height: Int = 4000,
    private val exactWidth: Boolean = true,
    private val exactHeight: Boolean = false,
) {
    private val frames = BroadcastFrameClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
    private val recomposer = Recomposer(scope.coroutineContext)
    private var frameTime = 0L
    private var current: ComposeView? = null

    init {
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
    }

    /** The view hosting the content most recently passed to [show]. */
    val view: ComposeView get() = checkNotNull(current) { "Nothing shown yet" }

    /** Replaces the activity's content with [content], freshly composed, and lets it settle for [frames]. */
    fun show(frames: Int = 20, content: @Composable () -> Unit) {
        current?.disposeComposition()
        current = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent(content)
        }
        activity.setContentView(current)
        settle(frames)
    }

    /** Sends [count] frames, 16 ms apart: snapshot changes applied, main looper idled, layout run. */
    fun settle(count: Int = 20) = repeat(count) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        frameTime += 16_000_000L
        frames.sendFrame(frameTime)
        current?.let {
            it.measure(View.MeasureSpec.makeMeasureSpec(width, if (exactWidth) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(height, if (exactHeight) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST))
            it.layout(0, 0, it.measuredWidth, it.measuredHeight)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Every semantics node of the shown content, unmerged, root first. */
    fun nodes(): List<SemanticsNode> {
        fun owner(view: View): SemanticsOwner? {
            val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            if (method != null) return method.invoke(view) as SemanticsOwner
            if (view is ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
            return null
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return flatten(requireNotNull(owner(view)).rootSemanticsNode)
    }

    fun dispose() {
        current?.disposeComposition()
        recomposer.cancel()
        scope.cancel()
    }
}
