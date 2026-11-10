package com.evanaronson.linguize.ui.card

import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lab, Review flow: the order the card walks its changes in, and where the selection goes. */
class ReviewQueueTest {
    private val both = listOf(EditKind.Fix, EditKind.Natural)

    // "Ayer yo fui al cine con mis amigo y mi hermano"
    //  0123456789...
    private val text = "Ayer yo fui al cine con mis amigo y mi hermano"
    private val amigo = text.indexOf("amigo")
    private val y = text.indexOf(" y ")
    // Ids deliberately out of text order: the queue goes by position, not id.
    private val fixAmigo = Edit(1, EditKind.Fix, amigo, amigo + 5, "amigo", "amigos", "Plural")
    private val deleteYo = Edit(2, EditKind.Fix, 5, 8, "yo ", "", "Not needed")
    private val insertComma = Edit(3, EditKind.Fix, y, y, "", ",", "Pause")
    private val rewordFriends = Edit(4, EditKind.Natural, text.indexOf("con"), amigo + 5, "con mis amigo",
        "con mis amigos", "Smoother", includes = setOf(1))
    private val rewordFirst = Edit(5, EditKind.Natural, 0, 4, "Ayer", "Ahir", "Other")
    private val revision = Revision(text, listOf(fixAmigo, deleteYo, insertComma, rewordFriends, rewordFirst))

    @Test fun fixesComeFirstInReadingOrderIncludingDeletionsAndInsertions() {
        assertEquals(listOf(2, 1, 3, 5, 4), revision.reviewQueue(both).map { it.id })
    }

    @Test fun aFixIsOfferedBeforeTheRewordingThatOverlapsIt() {
        val queue = revision.reviewQueue(both).map { it.id }
        assertTrue(queue.indexOf(fixAmigo.id) < queue.indexOf(rewordFriends.id))
    }

    @Test fun anInsertionGoesBeforeAReplacementAtTheSamePlace() {
        val replace = Edit(1, EditKind.Fix, 4, 6, "ab", "AB", null)
        val insert = Edit(2, EditKind.Fix, 4, 4, "", "x", null)
        assertEquals(listOf(2, 1), Revision("....ab", listOf(replace, insert)).reviewQueue(both).map { it.id })
    }

    @Test fun onlyTheKindsShownAreWalked() {
        assertEquals(listOf(5, 4), revision.reviewQueue(listOf(EditKind.Natural)).map { it.id })
    }

    @Test fun aResultOpensOnItsFirstChange() {
        assertEquals(deleteYo.id, ReviewFocus.start(revision, both).selected)
        assertNull(ReviewFocus.start(Revision("Bon dia", emptyList()), both).selected)
    }

    @Test fun acceptMovesToTheNextChangeInOrder() {
        var focus = ReviewFocus.start(revision, both)
        var current = revision
        val visited = mutableListOf<Int>()
        while (focus.selected != null) {
            val id = focus.selected!!
            visited += id
            val next = current.accept(id)
            focus = focus.follow(current, next, both)
            current = next
        }
        assertEquals(listOf(2, 1, 3, 5, 4), visited)
        assertTrue(focus.isDone(current, both))
    }

    @Test fun skipMovesOnWithoutAcceptingAndTheChangeStaysOpen() {
        val focus = ReviewFocus.start(revision, both).skip(revision, both)
        assertEquals(fixAmigo.id, focus.selected)
        assertTrue(revision.reviewQueue(both).any { it.id == deleteYo.id })
        assertEquals(setOf(deleteYo.id), focus.skipped)
        assertFalse(focus.isDone(revision, both))
    }

    @Test fun skippingEverythingEndsTheWalkButLeavesTheChangesOnOffer() {
        var focus = ReviewFocus.start(revision, both)
        repeat(5) { focus = focus.skip(revision, both) }
        assertNull(focus.selected)
        assertTrue(focus.isDone(revision, both))
        assertEquals(5, revision.reviewQueue(both).size)
    }

    @Test fun tapJumpsAndAcceptContinuesFromThere() {
        var focus = ReviewFocus.start(revision, both).select(insertComma.id)
        val next = revision.accept(insertComma.id)
        focus = focus.follow(revision, next, both)
        assertEquals(rewordFirst.id, focus.selected)
    }

    @Test fun afterTheLastChangeTheWalkWrapsToEarlierOpenChanges() {
        var focus = ReviewFocus.start(revision, both).select(rewordFriends.id)
        val next = revision.accept(rewordFriends.id)
        focus = focus.follow(revision, next, both)
        // The rewording retired the amigo fix; the first open change is next.
        assertEquals(deleteYo.id, focus.selected)
        assertTrue(next.isRetired(fixAmigo.id))
    }

    @Test fun acceptingARewordingRetiresItsFixFromTheWalk() {
        var focus = ReviewFocus.start(revision, both).select(fixAmigo.id)
        val next = revision.accept(rewordFriends.id) // a screen-reader action elsewhere
        focus = focus.follow(revision, next, both)
        assertEquals(insertComma.id, focus.selected)
    }

    @Test fun acceptingAnotherChangeFromElsewhereKeepsTheSelection() {
        val focus = ReviewFocus.start(revision, both)
        val next = revision.accept(insertComma.id)
        assertEquals(deleteYo.id, focus.follow(revision, next, both).selected)
    }

    @Test fun undoReturnsToTheChangeItBringsBack() {
        var focus = ReviewFocus.start(revision, both)
        val accepted = revision.accept(deleteYo.id)
        focus = focus.follow(revision, accepted, both)
        assertEquals(fixAmigo.id, focus.selected)
        val undone = accepted.undo()
        focus = focus.follow(accepted, undone, both)
        assertEquals(deleteYo.id, focus.selected)
    }

    @Test fun undoAfterTheWalkEndedReopensTheLastChange() {
        var focus = ReviewFocus.start(revision, both)
        var current = revision
        while (focus.selected != null) {
            val next = current.accept(focus.selected!!)
            focus = focus.follow(current, next, both)
            current = next
        }
        val undone = current.undo()
        assertEquals(rewordFriends.id, focus.follow(current, undone, both).selected)
    }

    @Test fun undoOfASkippedThenAcceptedChangeClearsItsSkip() {
        var focus = ReviewFocus.start(revision, both).skip(revision, both).select(deleteYo.id)
        val accepted = revision.accept(deleteYo.id)
        focus = focus.follow(revision, accepted, both)
        focus = focus.follow(accepted, accepted.undo(), both)
        assertEquals(deleteYo.id, focus.selected)
        assertFalse(deleteYo.id in focus.skipped)
    }

    @Test fun acceptAllMovesPastTheSection() {
        var focus = ReviewFocus.start(revision, both)
        val next = revision.acceptAll(EditKind.Fix)
        focus = focus.follow(revision, next, both)
        assertEquals(rewordFirst.id, focus.selected)
    }

    @Test fun aNewAnswerStartsAgainAtItsFirstChange() {
        val focus = ReviewFocus.start(revision, both).select(rewordFirst.id)
        val fresh = Revision(text, listOf(insertComma.copy(id = 9)))
        assertEquals(9, focus.follow(revision, fresh, both).selected)
    }

    @Test fun positionCountsEveryChangeSoAcceptingDoesNotRenumber() {
        var focus = ReviewFocus.start(revision, both)
        assertEquals(1 to 5, focus.position(revision, both))
        val next = revision.accept(deleteYo.id)
        focus = focus.follow(revision, next, both)
        assertEquals(2 to 5, focus.position(next, both))
        assertNull(ReviewFocus(null).position(next, both))
    }
}
