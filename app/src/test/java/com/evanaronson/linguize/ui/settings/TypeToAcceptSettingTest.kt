package com.evanaronson.linguize.ui.settings

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.lab.TypeToAcceptPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Lab, Type to accept: the switch under Settings › Checking, off by default, kept in its own file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypeToAcceptSettingTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val stored = context.getSharedPreferences("lab_type_to_accept", Context.MODE_PRIVATE)
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    private val activity = controller.get()

    @Before fun clear() {
        stored.edit().clear().commit()
    }

    @After fun tearDown() {
        stored.edit().clear().commit()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun offUntilTurnedOn() {
        assertFalse(TypeToAcceptPreferences(context).enabled)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse("Nothing in the app's settings turns it on", TypeToAcceptPreferences(context).enabled)
    }

    @Test fun theSwitchPersistsAcrossScreens() {
        show()
        assertEquals(ToggleableState.Off, switch().config[SemanticsProperties.ToggleableState])
        assertTrue(hasText(activity.getString(R.string.lab_type_to_accept_summary)))
        toggle()
        // Checked through what was saved, then a freshly composed screen: a recomposition
        // after the tap depends on the shared frame clock, which a full run can leave stalled.
        assertTrue("Saved in its own file", stored.getBoolean("enabled", false))
        assertTrue(TypeToAcceptPreferences(context).enabled)

        // A new Settings screen reads it back.
        show()
        assertEquals(ToggleableState.On, switch().config[SemanticsProperties.ToggleableState])
        toggle()
        assertFalse(TypeToAcceptPreferences(context).enabled)
    }

    @Test fun feedbackSwitchesShowUnderItAndPersist() {
        val preferences = TypeToAcceptPreferences(context)
        assertTrue("Vibrate is on by default", preferences.vibrate)
        assertFalse("Sound is off by default", preferences.sound)
        show()
        assertFalse("Only while Type to accept is on", hasText(activity.getString(R.string.lab_type_to_accept_vibrate)))

        preferences.enabled = true
        show()
        assertEquals(ToggleableState.On, switch(R.string.lab_type_to_accept_vibrate).config[SemanticsProperties.ToggleableState])
        assertEquals(ToggleableState.Off, switch(R.string.lab_type_to_accept_sound).config[SemanticsProperties.ToggleableState])
        toggle(R.string.lab_type_to_accept_vibrate)
        toggle(R.string.lab_type_to_accept_sound)
        assertFalse("Saved in the same file", stored.getBoolean("vibrate", true))
        assertTrue(stored.getBoolean("sound", false))

        show()
        assertEquals(ToggleableState.Off, switch(R.string.lab_type_to_accept_vibrate).config[SemanticsProperties.ToggleableState])
        assertEquals(ToggleableState.On, switch(R.string.lab_type_to_accept_sound).config[SemanticsProperties.ToggleableState])
    }

    private fun switch(title: Int) = nodes().single {
        it.config.getOrNull(SemanticsProperties.ToggleableState) != null &&
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == activity.getString(title) } == true
    }

    private fun toggle(title: Int) {
        assertTrue(requireNotNull(switch(title).config[SemanticsActions.OnClick].action).invoke())
        settle()
    }

    private var shows = 0

    private fun show() {
        // Keyed, so each show is a freshly composed screen that reads what was saved.
        val generation = ++shows
        activity.setContent { MaterialTheme { key(generation) { TypeToAcceptSwitch() } } }
        settle()
    }

    private fun switch() = nodes().single {
        it.config.getOrNull(SemanticsProperties.ToggleableState) != null &&
            it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                text.text == activity.getString(R.string.lab_type_to_accept_title)
            } == true
    }

    private fun toggle() {
        assertTrue(requireNotNull(switch().config[SemanticsActions.OnClick].action).invoke())
        settle()
    }

    private fun hasText(text: String) = nodes().any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
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
        return flatten(requireNotNull(owner(activity.window.decorView)).rootSemanticsNode)
    }
}
