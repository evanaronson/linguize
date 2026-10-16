package com.evanaronson.linguize.ui.components

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Checks the merged Compose semantics on the actual Material menu item, without a UI-test dependency. */
@RunWith(RobolectricTestRunner::class)
class MultiSelectMenuSemanticsTest {
    @Test fun menuItemExposesCheckboxStateAndOneClickAction() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val selected = mutableStateOf(false)
        var clicks = 0
        val root = ComposeView(activity).apply {
            setContent {
                MaterialTheme {
                    Column(Modifier.padding(16.dp)) {
                        DropdownMenuItem(
                            text = { Text("Option") },
                            leadingIcon = { Checkbox(checked = selected.value, onCheckedChange = null) },
                            onClick = {
                                clicks++
                                selected.value = !selected.value
                            },
                            modifier = Modifier.semantics {
                                role = Role.Checkbox
                                toggleableState = if (selected.value) ToggleableState.On else ToggleableState.Off
                            },
                        )
                    }
                }
            }
        }
        activity.setContentView(root)

        fun optionNode(): SemanticsNode {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 600, 400)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            fun owner(view: View): SemanticsOwner? {
                val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
                if (method != null) return method.invoke(view) as SemanticsOwner
                if (view is ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
                return null
            }
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            return flatten(requireNotNull(owner(root)).rootSemanticsNode).single { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Option" } == true
            }
        }

        val off = optionNode()
        assertEquals(Role.Checkbox, off.config.getOrNull(SemanticsProperties.Role))
        assertEquals(ToggleableState.Off, off.config.getOrNull(SemanticsProperties.ToggleableState))
        assertNotNull(off.config.getOrNull(SemanticsActions.OnClick))
        assertTrue(off.config[SemanticsActions.OnClick].action!!.invoke())
        assertEquals(1, clicks)
        assertEquals(ToggleableState.On, optionNode().config.getOrNull(SemanticsProperties.ToggleableState))
        activity.finish()
    }
}
