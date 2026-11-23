package com.evanaronson.linguize.ui.components

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.data.lab.TypeToAcceptPreferences
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.FeedbackOutput
import com.evanaronson.linguize.ui.card.LocalFeedbackOutput
import com.evanaronson.linguize.ui.card.ResultCard
import com.evanaronson.linguize.ui.card.ReviewActions
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.card.typeToAcceptKeyboardOptions
import com.evanaronson.linguize.ui.chat.Composer
import com.evanaronson.linguize.ui.chat.composerKeyboardOptions
import com.evanaronson.linguize.ui.chat.composerPlaceholder
import com.evanaronson.linguize.ui.notes.noteCheckLanguage
import com.evanaronson.linguize.ui.notes.noteKeyboardOptions
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lab, Composer language: the fields the writer types the target language in hint it to the
 * keyboard (EditorInfo.hintLocales): the Chat composer, the Type to accept field and Notes'
 * editor, only where the language is known. Each test composes afresh on its own frame clock
 * and reads what the focused field hands the keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardHintTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val root get() = activity.window.decorView
    private val frames = BroadcastFrameClock()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
    private val recomposer = Recomposer(uiScope.coroutineContext)
    private var frameTime = 0L

    @After fun tearDown() {
        TypeToAcceptPreferences(activity).enabled = false
        recomposer.cancel()
        uiScope.cancel()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun theComposerHintsTheConversationsLanguage() {
        show { Composer(TextFieldValue(), {}, language = "ca", canSend = false, onSend = {}) }
        assertEquals("ca", focusAndReadHint())
    }

    @Test fun theTypeToAcceptFieldHintsTheChecksLanguage() {
        showCard(language = "fr")
        assertEquals("fr", focusAndReadHint())
    }

    @Test fun theTypeToAcceptFieldHasNoHintUnderAutoDetect() {
        showCard(language = null)
        assertNull(focusAndReadHint())
    }

    @Test fun notesHintTheirLanguage() {
        show { NoteField("es") }
        assertEquals("es", focusAndReadHint())
    }

    @Test fun notesUnderLinguizeHaveNoHint() {
        show { NoteField(null) }
        assertNull(focusAndReadHint())
    }

    @Test fun theComposerPlaceholderFallsBackToTheAppsString() {
        assertEquals("Escriu un missatge", composerPlaceholder("ca", FALLBACK))
        assertEquals("Escribe un mensaje", composerPlaceholder("es-MX", FALLBACK))
        assertEquals("Écris un message", composerPlaceholder("fr", FALLBACK))
        assertEquals("Not bundled", FALLBACK, composerPlaceholder("oc", FALLBACK))
        assertEquals("No conversation yet", FALLBACK, composerPlaceholder(null, FALLBACK))
        assertEquals(activity.getString(R.string.lab_chat_composer_placeholder), "Write a message")
    }

    @Test fun theOptionsCarryTheHintOnlyWhenThereIsATag() {
        assertEquals("ca-ES-valencia", KeyboardOptionsHint.of(composerKeyboardOptions("ca-ES-valencia")))
        assertEquals("The composer keeps its sentence capitals", KeyboardCapitalization.Sentences,
            composerKeyboardOptions("ca").capitalization)
        assertNull(composerKeyboardOptions(null).hintLocales)
        assertEquals("fr", KeyboardOptionsHint.of(typeToAcceptKeyboardOptions("fr")))
        assertEquals("…and Type to accept its exact typing", false, typeToAcceptKeyboardOptions("fr").autoCorrectEnabled)
        assertNull(typeToAcceptKeyboardOptions(null).hintLocales)
        assertEquals("es-MX", KeyboardOptionsHint.of(noteKeyboardOptions("es-MX")))
        assertNull(noteKeyboardOptions(null).hintLocales)
        assertNull("Auto-detect", noteCheckLanguage(null))
    }

    /** Notes' editor field as NoteEditor builds it for the app's language [appLanguage]. */
    @Composable
    private fun NoteField(appLanguage: String?) {
        LinguizeTextField(
            value = "",
            onValueChange = {},
            accessibleLabel = FIELD,
            keyboardOptions = noteKeyboardOptions(rememberKeyboardTag(noteCheckLanguage(appLanguage)?.code)),
        )
    }

    private fun showCard(language: String?) {
        TypeToAcceptPreferences(activity).enabled = true
        val text = "Ayer fui al cine con mis amigo"
        val at = text.indexOf("amigo")
        val fix = Edit(1, EditKind.Fix, at, at + 5, "amigo", "amigos", "Why amigos")
        val state = CardState.Done(CheckResult.Reviewed(Revision(text, listOf(fix)), listOf(EditKind.Fix, EditKind.Natural)))
        val actions = CardActions(
            onCopy = { _, _ -> },
            review = ReviewActions(onAccept = {}, onAcceptAll = {}, onUndo = {}, onApply = {}),
            onRetry = {}, onOpenSettings = null, onSettle = { _, _ -> },
            keyboardLanguage = { language },
        )
        val silent = object : FeedbackOutput {
            override fun haptic() = Unit
            override fun sound() = Unit
        }
        show {
            CompositionLocalProvider(LocalFeedbackOutput provides silent) {
                ResultCard(state, actions, Modifier.fillMaxWidth().heightIn(max = 600.dp))
            }
        }
    }

    private fun show(content: @Composable () -> Unit) {
        uiScope.launch { recomposer.runRecomposeAndApplyChanges() }
        activity.setContentView(ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent { MaterialTheme { content() } }
        })
        settle()
    }

    /** Focuses the one editable field and returns the language tag it hands the keyboard, if any. */
    private fun focusAndReadHint(): String? {
        val field = nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertTrue(requireNotNull(field.config[SemanticsActions.RequestFocus].action).invoke())
        settle()
        val info = EditorInfo()
        assertTrue("The focused field opens an input connection", composeView(root)?.onCreateInputConnection(info) != null)
        val locales = info.hintLocales ?: return null
        assertEquals(1, locales.size())
        return locales[0].toLanguageTag()
    }

    private fun settle() {
        repeat(30) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            frameTime += 16_000_000L
            frames.sendFrame(frameTime)
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 1920)
        }
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The view Compose draws into (the one with the semantics owner), which answers the keyboard. */
    private fun composeView(view: View): View? {
        if (view.javaClass.methods.any { it.name == "getSemanticsOwner" && it.parameterCount == 0 }) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) composeView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun nodes(): List<SemanticsNode> {
        val view = requireNotNull(composeView(root))
        val owner = view.javaClass.methods.first { it.name == "getSemanticsOwner" && it.parameterCount == 0 }.invoke(view) as SemanticsOwner
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return flatten(owner.rootSemanticsNode)
    }

    private object KeyboardOptionsHint {
        fun of(options: androidx.compose.foundation.text.KeyboardOptions): String? =
            options.hintLocales?.let { list -> list.localeList.joinToString(",") { it.toLanguageTag() } }
    }

    private companion object {
        const val FIELD = "Your text"
        const val FALLBACK = "Write a message"
    }
}
