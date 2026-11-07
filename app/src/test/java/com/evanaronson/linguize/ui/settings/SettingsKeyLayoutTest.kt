package com.evanaronson.linguize.ui.settings

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.KeyStatus
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.ui.theme.LinguizeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsKeyLayoutTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        .setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val settings = SettingsViewModel(RuntimeEnvironment.getApplication() as App)
    private val store = ViewModelStore().apply { put("settings", settings) }
    private lateinit var root: ComposeView

    @After fun tearDown() {
        if (::root.isInitialized) root.disposeComposition()
        store.clear()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun allKeyActionsKeepVisibleTextInsideANarrowCardAtDoubleFontSize() {
        settings.setKeyDraft(Provider.Gemini, "replacement-key")
        val state = SettingsState(provider = Provider.Gemini, keys = mapOf(Provider.Gemini to KeyStatus.Unreadable))
        root = ComposeView(activity).apply {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                    LinguizeTheme(dark = false) {
                        // SectionCard's two 20dp insets leave 248dp for the three actions.
                        Column(Modifier.width(288.dp).semantics { contentDescription = CARD }) {
                            ModelCard(state, settings, onOpen = {})
                        }
                    }
                }
            }
        }
        activity.setContentView(root)
        settle()

        val nodes = nodes()
        val card = nodes.single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(CARD) }
        assertEquals(288f, card.boundsInRoot.width, 0.5f)
        val actions = listOf(R.string.save, R.string.get_key, R.string.delete_key).map { resource ->
            val label = activity.getString(resource)
            nodes.single {
                it.config.getOrNull(SemanticsActions.OnClick) != null &&
                    it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
            }.also { action ->
                val bounds = action.boundsInRoot
                assertTrue("$label has a visible button", bounds.width > 0f && bounds.height > 0f)
                assertTrue("$label fits the card's left inset", bounds.left >= card.boundsInRoot.left + 20f - 0.5f)
                assertTrue("$label fits the card's right inset", bounds.right <= card.boundsInRoot.right - 20f + 0.5f)
                assertTrue("$label fits the card vertically", bounds.top >= card.boundsInRoot.top && bounds.bottom <= card.boundsInRoot.bottom)
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(action.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                val layout = layouts.single()
                assertTrue("$label keeps positive text width", layout.size.width > 0)
                assertFalse("$label does not overflow horizontally", layout.didOverflowWidth)
                assertTrue("$label text fits its button", layout.size.width <= bounds.width + 0.5f)
            }
        }
        actions.forEachIndexed { index, action ->
            actions.drop(index + 1).forEach { other ->
                assertFalse("Key actions remain separate targets", action.boundsInRoot.overlaps(other.boundsInRoot))
            }
        }
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 600, 1800)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
    }

    private fun nodes(): List<SemanticsNode> {
        fun owner(view: View): SemanticsOwner? {
            val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            if (method != null) return method.invoke(view) as SemanticsOwner
            if (view is ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
            return null
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return flatten(requireNotNull(owner(root)).rootSemanticsNode)
    }

    private companion object {
        const val CARD = "API key card"
    }
}
