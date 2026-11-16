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

    @Test fun rewordingsComeFirstThenFixesInReadingOrderIncludingDeletionsAndInsertions() {
        assertEquals(listOf(5, 4, 2, 1, 3), revision.reviewQueue(both).map { it.id })
    }

    @Test fun theOrderDoesNotDependOnTheOrderTheKindsAreListedIn() {
        assertEquals(revision.reviewQueue(both), revision.reviewQueue(both.reversed()))
        assertEquals(listOf(EditKind.Natural, EditKind.Fix), both.walkOrder())
    }

    @Test fun aRewordingIsOfferedBeforeTheFixesItIncludes() {
        val queue = revision.reviewQueue(both).map { it.id }
        assertTrue(queue.indexOf(rewordFriends.id) < queue.indexOf(fixAmigo.id))
    }

    @Test fun aFixCoveredByAnAcceptedRewordingIsNeverOffered() {
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
        // The amigo fix is already in "con mis amigos": it is covered, not walked.
        assertEquals(listOf(5, 4, 2, 3), visited)
        assertTrue(current.isRetired(fixAmigo.id))
        assertFalse(current.isApplied(fixAmigo.id))
        assertTrue(focus.isDone(current, both))
        assertTrue(current.reviewQueue(both).isEmpty())
        assertEquals("Ahir fui al cine con mis amigos, y mi hermano", current.workingText)
    }

    @Test fun aFixInsideASkippedRewordingIsStillOffered() {
        var focus = ReviewFocus.start(revision, both)
        var current = revision.accept(rewordFirst.id)
        focus = focus.follow(revision, current, both)
        assertEquals(rewordFriends.id, focus.selected)
        focus = focus.skip(current, both)
        assertEquals(deleteYo.id, focus.selected)
        val next = current.accept(deleteYo.id)
        focus = focus.follow(current, next, both)
        current = next
        assertEquals("The fix inside the skipped rewording comes next", fixAmigo.id, focus.selected)
        assertFalse(current.isRetired(fixAmigo.id))
    }

    @Test fun aFixOutsideEveryRewordingIsStillOfferedAfterTheRewordings() {
        val reworded = revision.acceptAll(EditKind.Natural)
        assertEquals(listOf(deleteYo.id, insertComma.id), reworded.reviewQueue(both).map { it.id })
    }

    @Test fun aRewordingAcceptedOverAnAcceptedFixStillWins() {
        // The writer jumped to the fix first, then went back to the rewording.
        val both = revision.accept(fixAmigo.id).accept(rewordFriends.id)
        assertTrue(both.isRetired(fixAmigo.id))
        assertTrue(both.isApplied(rewordFriends.id))
    }

    @Test fun anInsertionGoesBeforeAReplacementAtTheSamePlace() {
        val replace = Edit(1, EditKind.Fix, 4, 6, "ab", "AB", null)
        val insert = Edit(2, EditKind.Fix, 4, 4, "", "x", null)
        assertEquals(listOf(2, 1), Revision("....ab", listOf(replace, insert)).reviewQueue(both).map { it.id })
    }

    @Test fun onlyTheKindsShownAreWalked() {
        assertEquals(listOf(5, 4), revision.reviewQueue(listOf(EditKind.Natural)).map { it.id })
        assertEquals(listOf(2, 1, 3), revision.reviewQueue(listOf(EditKind.Fix)).map { it.id })
    }

    @Test fun aResultOpensOnItsFirstChange() {
        assertEquals(rewordFirst.id, ReviewFocus.start(revision, both).selected)
        assertNull(ReviewFocus.start(Revision("Bon dia", emptyList()), both).selected)
    }

    @Test fun skipMovesOnWithoutAcceptingAndTheChangeStaysOpen() {
        val focus = ReviewFocus.start(revision, both).skip(revision, both)
        assertEquals(rewordFriends.id, focus.selected)
        assertTrue(revision.reviewQueue(both).any { it.id == rewordFirst.id })
        assertEquals(setOf(rewordFirst.id), focus.skipped)
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
        var focus = ReviewFocus.start(revision, both).select(deleteYo.id)
        val next = revision.accept(deleteYo.id)
        focus = focus.follow(revision, next, both)
        assertEquals(fixAmigo.id, focus.selected)
    }

    @Test fun afterTheLastChangeTheWalkWrapsToEarlierOpenChanges() {
        var focus = ReviewFocus.start(revision, both).select(insertComma.id)
        val next = revision.accept(insertComma.id)
        focus = focus.follow(revision, next, both)
        assertEquals(rewordFirst.id, focus.selected)
    }

    @Test fun acceptingARewordingFromElsewhereRetiresTheOpenFixAndMovesOn() {
        var focus = ReviewFocus.start(revision, both).select(fixAmigo.id)
        val next = revision.accept(rewordFriends.id) // a screen-reader action elsewhere
        focus = focus.follow(revision, next, both)
        assertEquals(insertComma.id, focus.selected)
    }

    @Test fun acceptingAnotherChangeFromElsewhereKeepsTheSelection() {
        val focus = ReviewFocus.start(revision, both)
        val next = revision.accept(insertComma.id)
        assertEquals(rewordFirst.id, focus.follow(revision, next, both).selected)
    }

    @Test fun undoReturnsToTheChangeItBringsBack() {
        var focus = ReviewFocus.start(revision, both)
        val accepted = revision.accept(rewordFirst.id)
        focus = focus.follow(revision, accepted, both)
        assertEquals(rewordFriends.id, focus.selected)
        val undone = accepted.undo()
        focus = focus.follow(accepted, undone, both)
        assertEquals(rewordFirst.id, focus.selected)
    }

    @Test fun undoingARewordingBringsBackItAndTheFixesItCovered() {
        var focus = ReviewFocus.start(revision, both).select(rewordFriends.id)
        val accepted = revision.accept(rewordFriends.id)
        focus = focus.follow(revision, accepted, both)
        assertFalse(accepted.reviewQueue(both).any { it.id == fixAmigo.id })
        val undone = accepted.undo()
        focus = focus.follow(accepted, undone, both)
        assertEquals("Undo reopens the rewording, before its fix", rewordFriends.id, focus.selected)
        assertTrue(undone.reviewQueue(both).any { it.id == fixAmigo.id })
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
        assertEquals(insertComma.id, focus.follow(current, undone, both).selected)
    }

    @Test fun undoOfASkippedThenAcceptedChangeClearsItsSkip() {
        var focus = ReviewFocus.start(revision, both).skip(revision, both).select(rewordFirst.id)
        val accepted = revision.accept(rewordFirst.id)
        focus = focus.follow(revision, accepted, both)
        focus = focus.follow(accepted, accepted.undo(), both)
        assertEquals(rewordFirst.id, focus.selected)
        assertFalse(rewordFirst.id in focus.skipped)
    }

    @Test fun acceptAllRewordingsMovesToTheFixesStillOffered() {
        var focus = ReviewFocus.start(revision, both)
        val next = revision.acceptAll(EditKind.Natural)
        focus = focus.follow(revision, next, both)
        assertEquals(deleteYo.id, focus.selected)
        assertTrue(next.isRetired(fixAmigo.id))
        // Undo of Accept all brings every rewording back, and the walk to the first.
        assertEquals(rewordFirst.id, focus.follow(next, next.undo(), both).selected)
    }

    @Test fun acceptAllFixesWhileOnARewordingKeepsTheRewordingOpen() {
        val focus = ReviewFocus.start(revision, both)
        val next = revision.acceptAll(EditKind.Fix)
        assertEquals(rewordFirst.id, focus.follow(revision, next, both).selected)
    }

    @Test fun aNewAnswerStartsAgainAtItsFirstChange() {
        val focus = ReviewFocus.start(revision, both).select(rewordFirst.id)
        val fresh = Revision(text, listOf(insertComma.copy(id = 9)))
        assertEquals(9, focus.follow(revision, fresh, both).selected)
    }

    @Test fun positionCountsEveryChangeSoAcceptingDoesNotRenumber() {
        var focus = ReviewFocus.start(revision, both)
        assertEquals(1 to 5, focus.position(revision, both))
        var next = revision.accept(rewordFirst.id)
        focus = focus.follow(revision, next, both)
        assertEquals(2 to 5, focus.position(next, both))
        // Accepting a rewording takes the fix it covers out of the count.
        val after = next.accept(rewordFriends.id)
        focus = focus.follow(next, after, both)
        next = after
        assertEquals(deleteYo.id, focus.selected)
        assertEquals(3 to 4, focus.position(next, both))
        assertNull(ReviewFocus(null).position(next, both))
    }
}
