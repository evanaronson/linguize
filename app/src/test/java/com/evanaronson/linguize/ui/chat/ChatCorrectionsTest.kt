package com.evanaronson.linguize.ui.chat

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.ChatPartner
import com.evanaronson.linguize.FakeClient
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.QUESTION
import com.evanaronson.linguize.QUESTION_JSON
import com.evanaronson.linguize.QUESTION_VERDICT
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.chat.ChatChange
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.chat.SendState
import com.evanaronson.linguize.core.chat.SentCheck
import com.evanaronson.linguize.core.time.DraftTiming
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.data.chat.ChatPreferences
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.fakeChecker
import com.evanaronson.linguize.history.CheckHistory
import com.evanaronson.linguize.history.HistoryEnvironment
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.SqliteHistoryStore
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.ui.card.CARET_MARK
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.RecordedChange
import com.evanaronson.linguize.ui.card.recordPassages
import com.evanaronson.linguize.ui.card.markChanges
import com.evanaronson.linguize.ui.theme.LinguizeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import java.time.Duration

/**
 * Lab, Chat corrections: a corrected message's bubble says how it was checked at its bottom end,
 * a tap on it opens the card's change view under it (the typed text with every change written
 * in place, no list; a tap on a change opens only its reason, read-only), and closes it; a
 * reply's tap still flips it, and the review's Edit message and system Back both go back to the draft.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatCorrectionsTest {
    private val corrected = ChatMessage(
        "w1", "c1", fromWriter = true, raw = "Com estas?", text = "Com estàs?",
        changes = listOf(ChatChange(EditKind.Fix, 4, 9, "estas", "estàs", "Accent marks the verb.")),
        check = SentCheck.Corrected, at = 1, sessionId = "s1",
    )
    private val clean = ChatMessage("w2", "c1", true, "Bé", "Bé", check = SentCheck.LooksGood, at = 2)
    private val reply = ChatMessage.partner("p1", "c1", "Què fem?", 3, translation = "What shall we do?")

    /** Ten changes, given out of order, a fix or rewording in almost every phrase. */
    private val manyRaw = "ahir anarem a el cine amb la meva amiga i despres vam menjar un pizza molt bo  a casa seva"
    private val manyChanges = listOf(
        "despres" to "després", "ahir" to "Ahir", "anarem" to "vam anar", "a el" to "al", "cine" to "cinema",
        "un" to "una", "bo" to "bona", "  " to " ", "la meva amiga" to "una amiga", "seva" to "seva.",
    ).map { (from, to) ->
        val start = manyRaw.indexOf(from).also { require(it >= 0) { from } }
        ChatChange(if (from == "la meva amiga") EditKind.Natural else EditKind.Fix, start, start + from.length, from, to, "Why $from")
    }
    private val many = ChatMessage(
        "w3", "c1", fromWriter = true, raw = manyRaw,
        text = manyChanges.sortedByDescending { it.start }.fold(manyRaw) { text, c -> text.replaceRange(c.start, c.end, c.to) },
        changes = manyChanges.shuffled(java.util.Random(7)), check = SentCheck.Corrected, at = 4, sessionId = "s3",
    )

    @Test fun theTypedTextShowsEachChangeInPlaceTheWayTheCardDoes() {
        val one = chatPassages(corrected, null).single()
        assertNull(one.kind)
        assertEquals("Com estàs?", one.text)
        val marked = markChanges(one.text, one.highlights)
        assertEquals("Com estas estàs?", marked.text)
        assertEquals(listOf(4..8), marked.struck)
        assertEquals(4..14, marked.ranges.getValue(0))
        assertEquals(10..14, marked.added.getValue(0))
        assertTrue(hasCorrections(corrected))
        assertFalse(hasCorrections(clean))
        assertFalse(hasCorrections(reply))

        // Ten changes given out of order: one text, every change in place in reading order, the
        // old words first, a fix solid and the rewording dotted; spacing as the card's caret.
        val passage = chatPassages(many, null).single()
        assertEquals(many.text, passage.text)
        val drawn = markChanges(passage.text, passage.highlights)
        val ordered = manyChanges.sortedBy { it.start }
        var last = -1
        ordered.forEach { change ->
            val highlight = passage.highlights.single { it.from == change.from }
            assertEquals(change.kind, highlight.kind)
            val range = drawn.ranges.getValue(highlight.id)
            assertTrue(range.first > last)
            last = range.last
            val span = drawn.text.substring(range)
            if (change.from.isNotBlank()) assertTrue(span, span.startsWith(change.from))
            if (change.to.isNotBlank()) assertTrue(span, span.endsWith(change.to))
        }
        assertTrue(drawn.text, drawn.text.contains("bo bona$CARET_MARK a casa"))
        assertEquals(ordered.count { it.from.isNotBlank() }, drawn.struck.size)

        // A fix and the rewording that later replaced it overlap: the card's two texts, fixes then
        // rewordings on the corrected text.
        val raw = "yo estas bien"
        val overlapping = many.copy(raw = raw, text = "estoy bien", changes = listOf(
            ChatChange(EditKind.Natural, 0, 8, "yo estas", "estoy", "Why estoy"),
            ChatChange(EditKind.Fix, 3, 8, "estas", "estás", "Why estás"),
        ))
        val passages = chatPassages(overlapping, null)
        assertEquals(listOf(EditKind.Fix, EditKind.Natural), passages.map { it.kind })
        assertEquals("yo estás bien", passages[0].text)
        assertEquals("estoy bien", passages[1].text)
        assertEquals(listOf("estas"), passages[0].highlights.map { it.from })
        assertEquals(listOf("yo estas"), passages[1].highlights.map { it.from })

        // A change that doesn't fit the text, or overlaps one of its own kind, is left out, never misplaced.
        val kept = recordPassages("Com estas?", listOf(
            RecordedChange(EditKind.Fix, 4, 9, "estas", "estàs", null),
            RecordedChange(EditKind.Fix, 20, 30, "x", "y", null),
            RecordedChange(EditKind.Fix, 6, 9, "tas", "tàs", null),
        ), dropUnfit = true)!!.single()
        assertEquals(listOf("estas"), kept.highlights.map { it.from })
        assertNull(recordPassages("Com estas?", listOf(RecordedChange(EditKind.Fix, 20, 30, "x", "y", null))))
    }

    @Test fun tappingACorrectedBubbleOpensTheCardsChangeViewWithNoList() {
        var expanded by mutableStateOf(false)
        show({
            MessageBubble(corrected, expanded, onToggle = { expanded = !expanded }, flipped = null, onFlip = {}, hint = false,
                language = "ca")
        }) { root, activity, flush ->
            val label = activity.resources.getQuantityString(R.plurals.lab_chat_changes_count, 1, 1)
            val bubble = bubble(root, activity.getString(R.string.lab_chat_you_said, "Com estàs?") + ". " + label)
            assertEquals(activity.getString(R.string.lab_expand_pattern_collapsed),
                bubble.config.getOrNull(SemanticsProperties.StateDescription))
            assertEquals(activity.getString(R.string.lab_chat_corrections_show), bubble.config[SemanticsActions.OnClick].label)
            assertTrue(bubble.config[SemanticsActions.CustomActions].any {
                it.label == activity.getString(R.string.lab_chat_corrections_show)
            })
            val written = activity.getString(R.string.lab_chat_changes_you_wrote, "Com estas?")
            assertFalse(descriptions(root).contains(written))

            bubble.config[SemanticsActions.OnClick].action!!.invoke()
            flush()
            // The card's change view: the typed text with the change written in place.
            val text = changeText(root, written)
            val drawn = text.config[SemanticsProperties.Text].single()
            assertEquals("Com estas estàs?", drawn.text)
            assertTrue(drawn.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough && it.start == 4 && it.end == 9 })
            assertTrue(text.config[SemanticsActions.CustomActions].any {
                it.label == activity.getString(R.string.change_why_replace, "estas", "estàs")
            })
            // No list of changes, no History sections, no reason until a change is tapped.
            assertTrue(rows(root, activity).isEmpty())
            assertFalse(hasText(root, activity.getString(R.string.history_before)))
            assertFalse(hasText(root, activity.getString(R.string.history_suggestions)))
            assertFalse(hasText(root, "Accent marks the verb."))
            assertFalse(descriptions(root).contains(activity.getString(R.string.change_becomes, "estas", "estàs")))

            // The change's Why (a tap, for screen readers) opens its explanation: the card's panel,
            // with nothing to accept. Real taps: OneChangeViewTest.
            text.config[SemanticsActions.CustomActions]
                .single { it.label == activity.getString(R.string.change_why_replace, "estas", "estàs") }.action()
            flush()
            assertTrue(hasText(root, "Accent marks the verb."))
            assertTrue(descriptions(root).contains(activity.getString(R.string.change_becomes, "estas", "estàs")))
            assertFalse(hasText(root, activity.getString(R.string.accept)))
            assertFalse(hasText(root, activity.getString(R.string.lab_review_flow_skip)))

            val open = bubble(root, activity.getString(R.string.lab_chat_you_said, "Com estàs?") + ". " + label)
            assertEquals(activity.getString(R.string.lab_expand_pattern_expanded),
                open.config.getOrNull(SemanticsProperties.StateDescription))
            assertEquals(activity.getString(R.string.lab_chat_corrections_hide), open.config[SemanticsActions.OnClick].label)

            open.config[SemanticsActions.OnClick].action!!.invoke()
            flush()
            assertFalse(descriptions(root).contains(written))
            assertTrue(nodes(root).none { it.config.getOrNull(SemanticsProperties.TestTag) == CHANGES_TAG })
        }
    }

    @Test fun oneChangeStaysShort() {
        show({ Column { ChatCorrections(corrected, "ca") } }) { root, activity, _ ->
            val block = nodes(root).single { it.config.getOrNull(SemanticsProperties.TestTag) == CHANGES_TAG }
            val dp = block.boundsInRoot.height / activity.resources.displayMetrics.density
            // The typed line alone: nothing like the old Before and Suggestions sections, or a row per change.
            assertTrue("$dp dp", dp < 90f)
        }
    }

    @Test fun manyChangesStayOneTextAndEachTapOpensOnlyItsOwnReason() {
        show({ Column { ChatCorrections(many, "ca") } }) { root, activity, flush ->
            val ordered = manyChanges.sortedBy { it.start }
            val written = activity.getString(R.string.lab_chat_changes_you_wrote, many.raw)
            // No list: the block is the text, a few lines, not a row per change.
            assertTrue(rows(root, activity).isEmpty())
            val block = nodes(root).single { it.config.getOrNull(SemanticsProperties.TestTag) == CHANGES_TAG }
            assertTrue(block.boundsInRoot.height / activity.resources.displayMetrics.density < 200f)
            assertTrue(ordered.none { hasText(root, it.why!!) })

            val text = changeText(root, written)
            assertEquals(ordered.size, text.config[SemanticsActions.CustomActions].size)
            text.config[SemanticsActions.CustomActions]
                .single { it.label == activity.getString(R.string.change_why_replace, "anarem", "vam anar") }.action()
            flush()
            assertTrue(hasText(root, "Why anarem"))
            // Another change's action moves the one open reason there.
            changeText(root, written).config[SemanticsActions.CustomActions]
                .single { it.label == activity.getString(R.string.change_why_replace, "cine", "cinema") }.action()
            flush()
            assertTrue(hasText(root, "Why cine"))
            assertFalse(hasText(root, "Why anarem"))
            assertEquals(1, ordered.count { hasText(root, it.why!!) })
            // Every change with old words shows them struck through, in the text.
            val drawn = changeText(root, written).config[SemanticsProperties.Text].single()
            assertEquals(ordered.count { it.from.isNotBlank() },
                drawn.spanStyles.count { it.item.textDecoration == TextDecoration.LineThrough })
        }
    }

    /** Any old change rows: toggles that open one change's explanation. There should be none. */
    private fun rows(root: View, activity: ComponentActivity): List<SemanticsNode> = nodes(root).filter { node ->
        node.config.getOrNull(SemanticsActions.OnClick)?.label == activity.getString(R.string.history_show_explanation)
    }

    /** The change view's text: the node screen readers hear as [written]. */
    private fun changeText(root: View, written: String): SemanticsNode =
        nodes(root).single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(written) == true }

    @Test fun theCheckLineSitsAtTheBubblesBottomEnd() {
        show({
            MessageBubble(corrected, false, onToggle = {}, flipped = null, onFlip = {}, hint = false)
        }) { root, activity, _ ->
            val label = activity.resources.getQuantityString(R.plurals.lab_chat_changes_count, 1, 1)
            val bubble = bubble(root, activity.getString(R.string.lab_chat_you_said, "Com estàs?") + ". " + label).boundsInRoot
            val meta = nodes(root).single { it.config.getOrNull(SemanticsProperties.TestTag) == CHECK_META_TAG }.boundsInRoot
            val padding = 14 * activity.resources.displayMetrics.density
            assertTrue("inside the bubble", bubble.contains(meta.center))
            assertEquals("at its end", bubble.right - padding, meta.right, 2f)
            assertTrue("at its bottom", meta.top > bubble.top + bubble.height / 2)
        }
    }

    @Test fun aCleanMessageSaysLooksGoodInTheSamePlaceAndOpensNothing() {
        show({
            MessageBubble(clean, false, onToggle = { error("nothing to open") }, flipped = null, onFlip = {}, hint = false)
        }) { root, activity, _ ->
            val bubble = bubble(root, activity.getString(R.string.lab_chat_you_said, "Bé") + ". " +
                activity.getString(R.string.lab_chat_looks_good))
            assertNull(bubble.config.getOrNull(SemanticsActions.OnClick))
            assertNull(bubble.config.getOrNull(SemanticsProperties.StateDescription))
            assertNotNull(nodes(root).singleOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == CHECK_META_TAG })
            // Copy stays.
            assertTrue(bubble.config[SemanticsActions.CustomActions].any { it.label == activity.getString(R.string.lab_chat_copy) })
        }
    }

    @Test fun aReplysTapStillTranslatesIt() {
        var flips = 0
        show({
            MessageBubble(reply, false, onToggle = { error("replies have no corrections") }, flipped = null,
                onFlip = { flips++ }, hint = false)
        }) { root, activity, _ ->
            val bubble = bubble(root, activity.getString(R.string.lab_chat_partner_said, "Què fem?"))
            assertEquals(activity.getString(R.string.lab_chat_show_translation), bubble.config[SemanticsActions.OnClick].label)
            bubble.config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(1, flips)
            assertTrue(nodes(root).none { it.config.getOrNull(SemanticsProperties.TestTag) == CHECK_META_TAG })
        }
    }

    @Test fun onlyTheWritersOwnBubbleOffersEditMessageAndOnlyBetweenTurns() {
        var edits = 0
        var canEdit by mutableStateOf(true)
        show({
            Column {
                MessageBubble(clean, false, onToggle = {}, flipped = null, onFlip = {}, hint = false,
                    onEdit = { edits++ }, canEdit = canEdit)
                MessageBubble(reply, false, onToggle = {}, flipped = null, onFlip = {}, hint = false,
                    onEdit = { error("replies are not editable") }, canEdit = canEdit)
            }
        }) { root, activity, flush ->
            val label = activity.getString(R.string.lab_chat_edit_sent)
            fun actions(description: String) = bubble(root, description).config[SemanticsActions.CustomActions].map { it.label }
            val mine = activity.getString(R.string.lab_chat_you_said, "Bé") + ". " + activity.getString(R.string.lab_chat_looks_good)
            val theirs = activity.getString(R.string.lab_chat_partner_said, "Què fem?")
            assertTrue(actions(mine).contains(label))
            assertFalse(actions(theirs).contains(label))
            bubble(root, mine).config[SemanticsActions.CustomActions].single { it.label == label }.action()
            assertEquals(1, edits)

            // Mid-turn: no Edit action for screen readers (the menu item is dimmed).
            canEdit = false
            flush()
            assertFalse(actions(mine).contains(label))
            assertTrue(actions(mine).contains(activity.getString(R.string.lab_chat_copy)))
        }
    }

    @Test fun editMessageAndSystemBackBothGoBackToTheDraft() {
        var backs = 0
        show({ BackToDraft({ backs++ }) }) { root, activity, flush ->
            assertTrue(hasText(root, activity.getString(R.string.lab_chat_corrections_edit_message)))
            val button = nodes(root).single { it.config.getOrNull(SemanticsActions.OnClick) != null }
            button.config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(1, backs)
            activity.onBackPressedDispatcher.onBackPressed()
            flush()
            assertEquals(2, backs)
        }
    }

    @Test fun theChatReviewKeepsAcceptAndUndoSeparateFromSendWithoutPhraseSaving() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ChatStore(context, name = null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val models = ViewModelStore()
        val preferences = FakePreferences()
        val checker = fakeChecker(preferences, FakeClient { ModelAnswer(QUESTION_VERDICT, QUESTION_JSON) })
        val card = CheckViewModel({ checker }, CheckHistory(SqliteHistoryStore(context), object : HistoryEnvironment {
            override val enabled = false
            override val deviceId = "device"
            override val appVersion = "1"
        }, scope))
        val chat = ChatViewModel(
            store, ChatPartner(preferences, ProviderKeys { null }, emptyMap(), { error("No partner request") }) {
                error("No partner request")
            }, ChatPreferences(context), memory = { error("No learning during review") },
            startLanguage = { "ca" }, drainDeletions = {}, people = { emptyList() },
            translate = { error("No translation during review") }, warmTargets = {}, appScope = scope,
        )
        models.put("card", card)
        models.put("chat", chat)
        chat.draftCheck = object : DraftCheck {
            override val sessionId: String? get() = card.pendingSessionId
            override fun start(draft: String, language: Language, replyingTo: String?) = card.check(draft, language, Origin.Chat)
            override fun stop() = card.discard()
            override fun finish(sent: String, timing: DraftTiming?): String? = card.dismissSent(sent, timing)
        }
        fun waitFor(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!condition()) {
                check(System.nanoTime() < deadline) { "Timed out waiting for the chat review" }
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(2)
            }
        }
        try {
            runBlocking {
                store.create(Conversation("review", "", "ca", 1, 1))
                store.add(reply.copy(conversationId = "review"))
            }
            chat.open("review")
            waitFor { chat.thread != null }
            chat.onDraftChange(TextFieldValue(QUESTION))
            chat.send()
            waitFor { card.state is CardState.Done }
            chat.onCardState(card.state)
            val reviewed = (card.state as CardState.Done).result as CheckResult.Reviewed
            val correction = reviewed.revision.edits.single().replacement.trim()
            show({ if (chat.send != SendState.Composing) ReviewPanel(chat, card, 600.dp, {}) }) { root, activity, flush ->
                fun click(id: Int, afterClick: () -> Unit = {}) {
                    val button = nodes(root, merged = true).single { node ->
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == activity.getString(id) } == true &&
                            node.config.getOrNull(SemanticsActions.OnClick) != null
                    }
                    assertTrue(requireNotNull(button.config[SemanticsActions.OnClick].action).invoke())
                    afterClick()
                    flush()
                }
                assertFalse(descriptions(root).contains(activity.getString(R.string.lab_phrasebook_save_phrase, correction)))
                assertTrue(hasText(root, activity.getString(R.string.lab_chat_send_as_written)))
                click(R.string.accept)
                assertEquals("Com estàs?", card.workingText)
                assertTrue(hasText(root, activity.getString(R.string.lab_chat_send)))
                assertFalse(hasText(root, activity.getString(R.string.apply)))
                assertFalse(chat.thread!!.messages.any { it.fromWriter })
                click(R.string.undo)
                assertNull(card.workingText)
                assertFalse(chat.thread!!.messages.any { it.fromWriter })
                click(R.string.accept)
                click(R.string.lab_chat_send) {
                    waitFor { chat.thread!!.messages.any { it.fromWriter } && chat.conversations.singleOrNull()?.writerMessages == 1 }
                }
                assertEquals("Com estàs?", chat.thread!!.messages.single { it.fromWriter }.text)
                assertEquals(SendState.Composing, chat.send)
                assertEquals("", chat.draft.text)
                assertNull(card.state)
            }
        } finally {
            scope.cancel()
            models.clear()
            store.close()
        }
    }

    private fun bubble(root: View, description: String): SemanticsNode = nodes(root).single { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
    }

    private fun show(content: @Composable () -> Unit, check: (View, ComponentActivity, () -> Unit) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = controller.get()
        val frames = BroadcastFrameClock()
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
        val recomposer = Recomposer(uiScope.coroutineContext)
        uiScope.launch { recomposer.runRecomposeAndApplyChanges() }
        val root = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent { LinguizeTheme(dark = false) { content() } }
        }
        var frameTime = 0L
        fun flush() {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            var delivered = 0
            while (recomposer.hasPendingWork || frames.hasAwaiters) {
                check(delivered++ < 240) { "Composition did not become idle" }
                frameTime += 16_666_667L
                frames.sendFrame(frameTime)
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idle()
            }
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 1920)
        }
        try {
            activity.setContentView(root)
            flush()
            flush()
            check(root, activity, ::flush)
        } finally {
            root.disposeComposition()
            recomposer.cancel()
            uiScope.cancel()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
    }

    private fun hasText(root: View, text: String): Boolean = nodes(root).any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }

    private fun descriptions(root: View): List<String> =
        nodes(root).flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    private fun nodes(root: View, merged: Boolean = false): List<SemanticsNode> {
        fun owner(view: View): SemanticsOwner? {
            val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            if (method != null) return method.invoke(view) as SemanticsOwner
            if (view is ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
            return null
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        val semantics = requireNotNull(owner(root))
        return flatten(if (merged) semantics.rootSemanticsNode else semantics.unmergedRootSemanticsNode)
    }
}
