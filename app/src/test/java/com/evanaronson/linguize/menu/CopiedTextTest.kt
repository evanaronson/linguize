package com.evanaronson.linguize.menu

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Selection
import com.evanaronson.linguize.ui.ClipboardText
import com.evanaronson.linguize.ui.quicktile.ClipboardJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35], application = Application::class)
class CopiedTextTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    @Test
    fun copiedTextKeepsItsSurroundingWhitespaceOutsideTheCheck() {
        val copied = Copied.of(ClipData.newPlainText("message", "  Bon dia, com estas?\n"))
        val text = copied as Copied.Text
        assertEquals("Bon dia, com estas?", text.selection.text)
        assertEquals("  Bon dia, com estàs?\n", text.selection.with("Bon dia, com estàs?"))
    }

    @Test
    fun nothingOrOnlyBlankTextIsEmpty() {
        assertEquals(Copied.Empty, Copied.of(null))
        assertEquals(Copied.Empty, Copied.of(ClipData.newPlainText("blank", " \n\t ")))
        assertEquals(Copied.Empty, Copied.of(ClipData.newPlainText("empty", "")))
    }

    @Test
    fun urisAndIntentsAreNotTextAndAreNeverResolved() {
        val uri = ClipData.newRawUri("photo", Uri.parse("content://media/external/images/1"))
        assertEquals(Copied.NotText, Copied.of(uri))
        assertEquals(Copied.NotText, Copied.of(ClipData.newIntent("intent", Intent("hidden"))))
        val image = ClipData(ClipDescription("image", arrayOf("image/png")), ClipData.Item(null, null, null, null))
        assertEquals(Copied.NotText, Copied.of(image))
    }

    @Test
    fun htmlKeepsOnlyItsCharacters() {
        val copied = Copied.of(ClipData.newHtmlText("html", "Hola, que tal?", "<b>Hola</b>, que tal?"))
        assertEquals("Hola, que tal?", (copied as Copied.Text).selection.text)
    }

    @Test
    fun understandSeesTheTrimmedTextOrNoText() {
        assertEquals(ClipboardText.Text("Hola"), Copied.Text(Selection.of(" Hola ")).forUnderstand)
        assertEquals(ClipboardText.NoText, Copied.Empty.forUnderstand)
        assertEquals(ClipboardText.NoText, Copied.NotText.forUnderstand)
        assertEquals(ClipboardText.Unavailable, Copied.Unavailable.forUnderstand)
    }

    @Test
    fun readingTheClipboardTakesTheFirstItemOnly() {
        val clip = ClipData.newPlainText("two", "First message")
        clip.addItem(ClipData.Item("Second message"))
        clipboard.setPrimaryClip(clip)
        assertEquals("First message", (context.readClipboard() as Copied.Text).selection.text)
        clipboard.clearPrimaryClip()
        assertEquals(Copied.Empty, context.readClipboard())
    }

    @Test
    fun eachJobRoutesTextToItsCard() {
        val selection = Selection.of("Bon dia")
        val text = Copied.Text(selection)
        assertEquals(ClipboardStep.Check(selection), ClipboardStep.after(ClipboardJob.Check, text))
        assertEquals(ClipboardStep.Understand(selection), ClipboardStep.after(ClipboardJob.Understand, text))
        assertEquals(ClipboardStep.Choose(selection), ClipboardStep.after(ClipboardJob.Choose, text))
    }

    @Test
    fun anythingButTextShowsWhyForEveryJob() {
        for (job in ClipboardJob.entries) {
            for (copied in listOf(Copied.Empty, Copied.NotText, Copied.Unavailable)) {
                assertEquals(ClipboardStep.Message(copied), ClipboardStep.after(job, copied))
            }
        }
    }

    @Test
    fun acceptedChangesGoBackOnTheClipboardWithAConfirmationInWords() {
        assertTrue(context.putBackOnClipboard("Bon dia, com estàs?"))
        val clip = clipboard.primaryClip!!
        assertEquals("Bon dia, com estàs?", clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        // Shown on every version: Android's own confirmation doesn't mention the changes.
        assertEquals(context.getString(R.string.lab_quick_tile_copied_with_changes), ShadowToast.getTextOfLatestToast())
    }
}
