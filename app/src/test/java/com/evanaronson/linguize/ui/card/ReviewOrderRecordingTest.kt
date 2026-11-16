package com.evanaronson.linguize.ui.card

import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.Opening
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.SessionContext
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionRecording
import com.evanaronson.linguize.history.SessionSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lab, Review flow: walking rewordings first keeps History honest. What the writer accepted
 * is Accepted; a fix an accepted rewording includes is Retired ("Covered by a rewording"),
 * never offered and never Ignored; a fix inside a skipped rewording is decided on its own.
 */
class ReviewOrderRecordingTest {
    private val both = listOf(EditKind.Fix, EditKind.Natural)
    private val text = "Ayer yo fui al cine con mis amigo"
    private val amigo = text.indexOf("amigo")
    private val deletion = Edit(1, EditKind.Fix, 5, 8, "yo ", "", "Why yo")
    private val fix = Edit(2, EditKind.Fix, amigo, amigo + 5, "amigo", "amigos", "Why amigos")
    private val friends = Edit(3, EditKind.Natural, text.indexOf("con"), amigo + 5, "con mis amigo",
        "amb els meus amics", "Why amics", includes = setOf(2))
    private val revision = Revision(text, listOf(deletion, fix, friends))

    private val context = SessionContext(
        Opening(origin = Origin.Menu, hostApp = "org.telegram.messenger", requestedLanguage = "ca",
            settings = SessionSettings(nativeLanguage = "English", punctuation = "Relaxed", judgments = "Both",
                provider = "anthropic", model = "a-model", promptHash = "p1")),
        appVersion = "1.0",
        deviceId = "device",
    )
    private var ids = 0

    /** Walks the card: [skip] the changes in it, accept the rest, recording each step. */
    private fun walk(skip: Set<Int> = emptySet()): Pair<Revision, SessionDetail> {
        val recording = SessionRecording(text, context, now = 100, newId = { "id${ids++}" })
        recording.attempt(200, emptyList(), "{}", null, null, revision, "meaning")
        var current = revision
        var focus = ReviewFocus.start(current, both)
        val offered = mutableListOf<Int>()
        while (focus.selected != null) {
            val id = focus.selected!!
            offered += id
            if (id in skip) {
                focus = focus.skip(current, both)
                continue
            }
            val next = current.accept(id)
            recording.changed(next)
            focus = focus.follow(current, next, both)
            current = next
        }
        assertEquals("Offered in order", offered, offered.distinct())
        lastOffered = offered
        return current to recording.close(300, current.workingText)
    }

    private var lastOffered = emptyList<Int>()

    private fun SessionDetail.decision(edit: Edit) =
        suggestions.single { it.kind == edit.kind && it.fromText == edit.from }.decision

    @Test fun aFixCoveredByAnAcceptedRewordingIsNeverOfferedAndIsRecordedAsCovered() {
        val (final, detail) = walk()
        assertEquals(listOf(friends.id, deletion.id), lastOffered)
        assertEquals("Ayer fui al cine amb els meus amics", final.workingText)
        assertEquals(Decision.Accepted, detail.decision(friends))
        assertEquals(Decision.Accepted, detail.decision(deletion))
        assertEquals(Decision.Retired, detail.decision(fix))
    }

    @Test fun aFixInsideASkippedRewordingIsOfferedAndRecordedOnItsOwn() {
        val (final, detail) = walk(skip = setOf(friends.id))
        assertEquals(listOf(friends.id, deletion.id, fix.id), lastOffered)
        assertEquals("Ayer fui al cine con mis amigos", final.workingText)
        assertEquals(Decision.Ignored, detail.decision(friends))
        assertEquals(Decision.Accepted, detail.decision(fix))
        assertEquals(Decision.Accepted, detail.decision(deletion))
    }
}
