package com.evanaronson.linguize.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import com.evanaronson.linguize.testing.OwnComposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Checks the merged Compose semantics of a multiple-choice row, without a UI-test dependency. */
@RunWith(RobolectricTestRunner::class)
class ChoiceRowSemanticsTest {
    @Test fun multipleChoiceRowExposesCheckboxStateAndOneClickAction() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val compose = OwnComposition(activity, width = 600, height = 400, exactHeight = true)
        val selected = mutableStateOf(false)
        var clicks = 0
        compose.show {
            MaterialTheme {
                Column(Modifier.padding(16.dp)) {
                    ChoiceRow(
                        "Option",
                        "What it does",
                        selected = selected.value,
                        onClick = {
                            clicks++
                            selected.value = !selected.value
                        },
                        multiple = true,
                    )
                }
            }
        }

        fun optionNode(): SemanticsNode {
            compose.settle()
            return compose.nodes().single { node ->
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
        compose.dispose()
        activity.finish()
    }
}
