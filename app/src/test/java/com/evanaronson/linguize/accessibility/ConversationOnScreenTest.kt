package com.evanaronson.linguize.accessibility

import android.app.Application
import android.graphics.Rect
import android.text.InputType
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Lab, Replying to: Pick their message reads the texts above the writer's field from a node tree. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConversationOnScreenTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun node(
        text: String? = null,
        top: Int = 0,
        bottom: Int = top + 80,
        className: String = "android.widget.TextView",
        visible: Boolean = true,
        editable: Boolean = false,
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain(View(context)).apply {
        packageName = "com.whatsapp"
        this.className = className
        this.text = text
        isVisibleToUser = visible
        isEnabled = true
        isEditable = editable
        setBoundsInScreen(Rect(0, top, 1080, bottom))
        children.forEach { shadowOf(this).addChild(it) }
    }

    @Test fun theLatestTextsAboveTheFieldInScreenOrder() {
        val field = node("Sí, a les", top = 1700, bottom = 1800, className = "android.widget.EditText", editable = true)
        shadowOf(field).setRefreshReturnValue(true)
        val bubble = node("Vens dissabte?", top = 900, bottom = 1000, className = "android.view.ViewGroup",
            children = listOf(node("Vens dissabte?", top = 910, bottom = 990)))
        node(top = 0, bottom = 1920, className = "android.widget.FrameLayout", children = listOf(
            node("Maria", top = 0, bottom = 100), // the title bar is a text like any other
            node(className = "androidx.recyclerview.widget.RecyclerView", top = 100, bottom = 1700, children = listOf(
                node("Hola!", top = 200, bottom = 300),
                node("Com va?", top = 400, bottom = 500),
                node("10:42", top = 500, bottom = 540),
                node("Bé, i tu?", top = 600, bottom = 700),
                node("Hidden draft", top = 700, bottom = 800, visible = false),
                bubble,
                node("A quina hora?", top = 1100, bottom = 1200),
            )),
            node("Send", top = 1700, bottom = 1800, className = "android.widget.ImageButton"),
            field,
            node("Below the field", top = 1810, bottom = 1900),
        ))

        val messages = ConversationOnScreen.above(field)
        assertEquals(listOf("Maria", "Hola!", "Com va?", "Bé, i tu?", "Vens dissabte?", "A quina hora?"), messages)
    }

    @Test fun nothingReadableIsNoMessages() {
        val field = node("Hola", top = 1700, bottom = 1800, className = "android.widget.EditText", editable = true)
        shadowOf(field).setRefreshReturnValue(true)
        node(top = 0, bottom = 1920, children = listOf(node("12:01", top = 100, bottom = 200), field))
        assertTrue(ConversationOnScreen.above(field).isEmpty())
    }

    @Test fun aFieldThatWentAwayReadsNothing() {
        val field = node("Hola", top = 1700, bottom = 1800, className = "android.widget.EditText", editable = true)
        shadowOf(field).setRefreshReturnValue(false)
        node(top = 0, bottom = 1920, children = listOf(node("Vens?", top = 100, bottom = 200), field))
        assertTrue(ConversationOnScreen.above(field).isEmpty())
        assertTrue(ConversationOnScreen.above(null).isEmpty())
    }

    @Test fun otherFieldsAreNeverRead() {
        val field = node("Hola", top = 1700, bottom = 1800, className = "android.widget.EditText", editable = true)
        shadowOf(field).setRefreshReturnValue(true)
        val password = node("secret pass", top = 300, bottom = 400).apply {
            isPassword = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        node(top = 0, bottom = 1920, children = listOf(
            node("Search chats", top = 100, bottom = 200, className = "android.widget.EditText", editable = true),
            password,
            node("Vens?", top = 500, bottom = 600),
            field,
        ))
        assertEquals(listOf("Vens?"), ConversationOnScreen.above(field))
    }
}
