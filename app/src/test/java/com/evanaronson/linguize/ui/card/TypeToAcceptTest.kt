package com.evanaronson.linguize.ui.card

import com.evanaronson.linguize.ui.card.TypeToAccept.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lab, Type to accept: what counts as typing the change. */
class TypeToAcceptTest {
    @Test fun aPrefixIsOnTrackAndOneCharacterOffIsOff() {
        assertEquals(Progress.Empty, TypeToAccept.progress("", "amigos"))
        assertEquals(Progress.OnTrack, TypeToAccept.progress("a", "amigos"))
        assertEquals(Progress.OnTrack, TypeToAccept.progress("amigo", "amigos"))
        assertEquals(Progress.Off, TypeToAccept.progress("amix", "amigos"))
        assertEquals(Progress.Off, TypeToAccept.progress("amigoss", "amigos"))
        assertEquals(Progress.Match, TypeToAccept.progress("amigos", "amigos"))
    }

    @Test fun caseAndAccentsCount() {
        assertEquals(Progress.Off, TypeToAccept.progress("Amigos", "amigos"))
        assertEquals(Progress.Off, TypeToAccept.progress("estas", "estàs"))
        assertEquals(Progress.Off, TypeToAccept.progress("estás", "estàs"))
        assertEquals(Progress.Match, TypeToAccept.progress("estàs", "estàs"))
    }

    @Test fun unicodeFormsAreTheSame() {
        val composed = "estàs"
        val decomposed = "estàs"
        assertEquals(Progress.Match, TypeToAccept.progress(decomposed, composed))
        assertEquals(Progress.Match, TypeToAccept.progress(composed, decomposed))
        // A letter still waiting for its accent isn't off yet.
        assertEquals(Progress.OnTrack, TypeToAccept.progress("esta", composed))
    }

    @Test fun apostrophesAreOne() {
        assertEquals(Progress.Match, TypeToAccept.progress("l'home", "l’home"))
        assertEquals(Progress.Match, TypeToAccept.progress("l’home", "l'home"))
        assertEquals(Progress.Off, TypeToAccept.progress("l\"home", "l'home"))
    }

    @Test fun spacingInsideCountsButNotAtTheEnds() {
        assertEquals("de la", TypeToAccept.target(" de la "))
        assertEquals(Progress.Off, TypeToAccept.progress("dela", "de la"))
        assertEquals(Progress.Off, TypeToAccept.progress(" de", "de la"))
        assertEquals("ens veiem demà", TypeToAccept.target("ens veiem\ndemà"))
    }

    @Test fun deletionsAndSpacingHaveNothingToType() {
        assertNull(TypeToAccept.target(""))
        assertNull(TypeToAccept.target(" "))
        assertNull(TypeToAccept.target("\n"))
    }

    @Test fun typedLineBreaksAreDropped() {
        assertEquals("amigos", TypeToAccept.typed("amigos\n"))
    }

    @Test fun longRewordingsAreTypedInFull() {
        val target = "Em sabria greu que no poguessis venir dissabte a la nit"
        assertEquals(target, TypeToAccept.target(target))
        assertEquals(Progress.OnTrack, TypeToAccept.progress(target.dropLast(3), target))
        assertEquals(Progress.Match, TypeToAccept.progress(target, target))
    }
}
