package com.evanaronson.linguize.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyPutBackTest {
    @Test fun onlyAFieldThatStillHoldsWhatItHeldIsFilled() {
        assertTrue(ReplyPutBack.untouched(now = null, showingHint = false, openedWith = null))
        assertTrue(ReplyPutBack.untouched(now = "  ", showingHint = false, openedWith = ""))
        assertTrue("WhatsApp's placeholder text", ReplyPutBack.untouched(now = "Message", showingHint = false, openedWith = "Message"))
        assertTrue(ReplyPutBack.untouched(now = "Type a message", showingHint = true, openedWith = null))
        assertFalse("the writer typed since", ReplyPutBack.untouched(now = "Ja vinc", showingHint = false, openedWith = ""))
    }
}
