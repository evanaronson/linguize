package com.evanaronson.linguize.ui.components

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.evanaronson.linguize.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** The shared expand-in-place pattern: one header that toggles, its state spoken, content in place. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DisclosureTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        .setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val frames = BroadcastFrameClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
    private val recomposer = Recomposer(scope.coroutineContext)
    private val root = ComposeView(activity)
    private var frameTime = 0L

    init {
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        root.setParentCompositionContext(recomposer)
        activity.setContentView(root)
    }

    @After fun tearDown() {
        root.disposeComposition()
        recomposer.cancel()
        scope.cancel()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun theHeaderOpensAndClosesTheContentInPlaceAndSaysWhichItIs() {
        root.setContent {
            MaterialTheme {
                Column {
                    Disclosure("Why", summary = "One change") { Text("Accent marks the verb.") }
                }
            }
        }
        settle()
        val collapsed = activity.getString(R.string.lab_expand_pattern_collapsed)
        val expanded = activity.getString(R.string.lab_expand_pattern_expanded)

        var header = header("Why")
        assertFalse(hasText("Accent marks the verb."))
        assertTrue(hasText("One change"))
        assertEquals(collapsed, header.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(activity.getString(R.string.lab_expand_pattern_show), header.config[SemanticsActions.OnClick].label)
        assertNotNull(header.config.getOrNull(SemanticsActions.Expand))
        assertNull(header.config.getOrNull(SemanticsActions.Collapse))

        assertTrue(header.config[SemanticsActions.OnClick].action!!.invoke())
        settle()
        header = header("Why")
        // The header stays, and the content opens under it.
        assertTrue(hasText("One change"))
        assertTrue(hasText("Accent marks the verb."))
        assertEquals(expanded, header.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(activity.getString(R.string.lab_expand_pattern_hide), header.config[SemanticsActions.OnClick].label)
        assertNotNull(header.config.getOrNull(SemanticsActions.Collapse))
        assertNull(header.config.getOrNull(SemanticsActions.Expand))

        // The screen reader's collapse action closes it too.
        assertTrue(header.config[SemanticsActions.Collapse].action!!.invoke())
        settle()
        assertFalse(hasText("Accent marks the verb."))
        assertEquals(collapsed, header("Why").config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test fun hoistedStateAndOwnLabelsAreUsed() {
        var open by mutableStateOf(false)
        root.setContent {
            MaterialTheme {
                Disclosure(
                    expanded = open,
                    onExpandedChange = { open = it },
                    header = { Text("3 changes") },
                    expandLabel = "Show changes",
                    collapseLabel = "Hide changes",
                ) { Text("estas → estàs") }
            }
        }
        settle()
        assertEquals("Show changes", header("3 changes").config[SemanticsActions.OnClick].label)
        assertTrue(header("3 changes").config[SemanticsActions.Expand].action!!.invoke())
        settle()
        assertTrue(open)
        assertTrue(hasText("estas → estàs"))
        assertEquals("Hide changes", header("3 changes").config[SemanticsActions.OnClick].label)
        open = false
        settle()
        assertFalse(hasText("estas → estàs"))
    }

    @Test fun theOpenStateIsSavedAndRestored() {
        // One call site, so both compositions give the saved state the same key.
        fun show(registry: SaveableStateRegistry) = root.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                MaterialTheme { Disclosure("Why", key = "row-1") { Text("Because.") } }
            }
        }
        val registry = SaveableStateRegistry(null) { true }
        show(registry)
        settle()
        header("Why").config[SemanticsActions.OnClick].action!!.invoke()
        settle()
        assertTrue(hasText("Because."))
        val saved = registry.performSave()

        val restored = SaveableStateRegistry(saved) { true }
        root.disposeComposition()
        show(restored)
        settle()
        assertTrue(hasText("Because."))
        assertEquals(activity.getString(R.string.lab_expand_pattern_expanded),
            header("Why").config.getOrNull(SemanticsProperties.StateDescription))
    }

    private fun header(text: String): SemanticsNode = nodes().first {
        it.config.getOrNull(SemanticsActions.OnClick) != null && containsText(it, text)
    }

    private fun hasText(text: String) = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }

    private fun containsText(node: SemanticsNode, text: String): Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
            node.children.any { containsText(it, text) }

    private fun settle() {
        repeat(40) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            frameTime += 16_666_667L
            frames.sendFrame(frameTime)
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            ownerView(root)?.forceLayout()
            root.forceLayout()
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 1920)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun ownerView(view: View): View? {
        if (view.javaClass.methods.any { it.name == "getSemanticsOwner" && it.parameterCount == 0 }) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            ownerView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun nodes(): List<SemanticsNode> {
        val view = requireNotNull(ownerView(root))
        val owner = view.javaClass.methods.first { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            .invoke(view) as SemanticsOwner
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return flatten(owner.rootSemanticsNode)
    }
}
