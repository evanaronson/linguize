package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * Lab, Review flow: what the card shows once changes are decided. Finished, it is the final
 * text once, one summary with Undo, and the final action; partly done, a kind with nothing
 * left to decide is one quiet line. Every state is composed fresh, so nothing here waits on a
 * recomposition after a tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewFinishTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val root get() = activity.window.decorView

    // "Ayer yo fui al cine con mis amigo": two fixes (yo, amigo), a rewording that includes the
    // amigo fix, and one elsewhere (Ayer).
    private val text = "Ayer yo fui al cine con mis amigo"
    private val amigo = text.indexOf("amigo")
    private val deletion = Edit(1, EditKind.Fix, 5, 8, "yo ", "", "Why yo")
    private val fix = Edit(2, EditKind.Fix, amigo, amigo + 5, "amigo", "amigos", "Why amigos")
    private val friends = Edit(3, EditKind.Natural, text.indexOf("con"), amigo + 5, "con mis amigo",
        "amb els meus amics", "Why amics", includes = setOf(2))
    private val yesterday = Edit(4, EditKind.Natural, 0, 4, "Ayer", "Ahir", "Why Ahir")
    private val both = listOf(EditKind.Fix, EditKind.Natural)
    private val start = Revision(text, listOf(deletion, fix, friends, yesterday))

    private enum class Host(val scrollable: Boolean, val applyLabel: Int) {
        Popup(true, R.string.apply),
        Chat(true, R.string.lab_chat_send),
        Notes(false, R.string.apply),
    }

    private var undone = 0
    private var applied = 0
    private val copied = mutableListOf<Pair<String, EditKind>>()

    @After fun tearDown() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    private fun show(revision: Revision, host: Host = Host.Popup) {
        val actions = CardActions(
            onCopy = { text, kind -> copied += text to kind },
            review = ReviewActions(onAccept = {}, onAcceptAll = {}, onUndo = { undone++ }, onApply = { applied++ },
                applyLabel = host.applyLabel),
            onRetry = {}, onOpenSettings = null, onSettle = { _, _ -> },
        )
        val state = CardState.Done(CheckResult.Reviewed(revision, both, meaning = "Yesterday I went to the cinema"))
        activity.setContent {
            MaterialTheme {
                if (host.scrollable) {
                    ResultCard(state, actions, Modifier.fillMaxWidth().heightIn(max = 600.dp))
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ResultCard(state, actions, Modifier.fillMaxWidth(), scrollable = false)
                    }
                }
            }
        }
        settle()
    }

    @Test fun finishedIsTheFinalTextOnceOneSummaryAndTheFinalActionInEveryHost() {
        val finished = start.acceptAll(EditKind.Natural).acceptAll(EditKind.Fix)
        assertEquals("Ahir fui al cine amb els meus amics", finished.workingText)
        for (host in Host.entries) {
            show(finished, host)
            // The covered amigo fix is part of the rewording: three changes, not four.
            assertTrue("$host: one summary", hasText(plural(R.plurals.changes_accepted_count, 3)))
            assertEquals("$host: the final text, once", 1, textNodes(finished.workingText).size)
            assertTrue(hasText(activity.getString(R.string.undo)))
            assertEquals(1, buttons(activity.getString(host.applyLabel)).size)
            // No per-kind sections, counts, Accept all or second "accepted" line.
            for (gone in listOf(plural(R.plurals.fix_count, 2), plural(R.plurals.rewording_count, 2),
                plural(R.plurals.lab_review_rewordings_accepted, 2), plural(R.plurals.lab_review_fixes_accepted, 1),
                activity.getString(R.string.all_accepted), activity.getString(R.string.accept_all),
                activity.getString(R.string.copy_with_fixes), activity.getString(R.string.copy_with_rewordings))) {
                assertFalse("$host shows no \"$gone\"", hasText(gone) || customActions().contains(gone))
            }
        }
        click(activity.getString(R.string.undo))
        click(activity.getString(Host.Notes.applyLabel))
        assertEquals(1, undone)
        assertEquals(1, applied)
    }

    @Test fun theFinishedTextCanBeCopied() {
        val finished = start.acceptAll(EditKind.Natural).acceptAll(EditKind.Fix)
        show(finished)
        customAction(activity.getString(R.string.lab_review_copy_text))
        assertEquals(listOf(finished.workingText to EditKind.Natural), copied)
    }

    @Test fun meaningStaysAtTheFootWhenFinished() {
        show(start.acceptAll(EditKind.Natural).acceptAll(EditKind.Fix))
        assertTrue(hasText(activity.getString(R.string.meaning)))
    }

    @Test fun rewordingsComeAboveTheFixes() {
        show(start)
        val rewordings = textNodes(plural(R.plurals.rewording_count, 2)).single()
        val fixes = textNodes(plural(R.plurals.fix_count, 2)).single()
        assertTrue(rewordings.positionInRoot.y < fixes.positionInRoot.y)
        assertTrue("Opens on the first rewording", hasText("Why Ahir"))
    }

    @Test fun onceTheRewordingsAreDecidedTheyAreOneLineAndTheFixesTextCarriesThem() {
        val reworded = start.acceptAll(EditKind.Natural)
        show(reworded)
        assertTrue(hasText(plural(R.plurals.lab_review_rewordings_accepted, 2)))
        assertFalse("No rewording count above a text", hasText(plural(R.plurals.rewording_count, 2)))
        // The covered fix left the fixes: one is still on offer, and the count says so.
        assertTrue(hasText(plural(R.plurals.fix_count, 1)))
        val passage = textNodes("Ahir yo fui")
        assertTrue("The fixes' text has the accepted rewordings, plain: ${passage.map(::texts)}", passage.isNotEmpty() &&
            passage.all { node -> texts(node).any { "amb els meus amics" in it } })
        assertTrue(hasText(plural(R.plurals.changes_accepted_count, 2)))
        assertTrue(hasText(activity.getString(R.string.accept_all)))
        assertTrue("The remaining fix is open", hasText("Why yo"))
    }

    @Test fun onceTheFixesAreDecidedTheyAreOneLine() {
        val fixed = start.acceptAll(EditKind.Fix)
        show(fixed)
        assertTrue(hasText(plural(R.plurals.lab_review_fixes_accepted, 2)))
        assertFalse(hasText(plural(R.plurals.fix_count, 2)))
        assertTrue(hasText(plural(R.plurals.rewording_count, 2)))
        assertTrue(hasText("Why Ahir"))
    }

    @Test fun aFixCoveredByARewordingIsSaidSo() {
        // Only the rewording that includes the amigo fix, and the yo fix: both fixes resolved.
        val covered = start.accept(friends.id).accept(deletion.id)
        show(covered)
        assertTrue(hasText(activity.getString(R.string.lab_review_fixes_summary,
            plural(R.plurals.lab_review_fixes_accepted, 1), plural(R.plurals.lab_review_covered, 1))))
        val onlyCovered = Revision(text, listOf(fix, friends, yesterday)).accept(friends.id)
        assertTrue(onlyCovered.isRetired(fix.id))
        show(onlyCovered)
        assertTrue(hasText(plural(R.plurals.lab_review_fixes_covered, 1)))
    }

    @Test fun skippedChangesKeepTheirSectionWhenTheWalkEnds() {
        // Accepting one fix leaves the other kinds on offer: no finished state yet.
        show(start.accept(deletion.id))
        assertTrue(hasText(plural(R.plurals.rewording_count, 2)))
        assertTrue(hasText(plural(R.plurals.fix_count, 2)))
        assertTrue(hasText(plural(R.plurals.changes_accepted_count, 1)))
    }

    private fun plural(id: Int, count: Int) = activity.resources.getQuantityString(id, count, count)

    private fun settle() {
        repeat(2) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 1920)
        }
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
    }

    private fun texts(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun hasText(text: String) = nodes().any { text in texts(it) }

    /** Nodes whose text starts with [prefix]: the passages and the final text. */
    private fun textNodes(prefix: String) = nodes().filter { node -> texts(node).any { it.startsWith(prefix) } }

    private fun buttons(label: String) = nodes().filter {
        label in texts(it) && it.config.getOrNull(SemanticsActions.OnClick) != null
    }

    private fun click(label: String) {
        val action = buttons(label).single().config[SemanticsActions.OnClick].action
        assertTrue(requireNotNull(action).invoke())
    }

    private fun customActions() = nodes().flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
        .map { it.label }

    private fun customAction(label: String) {
        val action = nodes().flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
            .first { it.label == label }
        assertTrue(action.action())
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
}
