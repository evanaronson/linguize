package com.evanaronson.linguize.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class LabReplyNavigationTest {
    @Test fun helpMeReplyIsRestoredAboveWrite() {
        val reply = Navigation().open(Screen.LabReply)
        assertEquals(reply, Navigation.restore(reply.saved))
        assertEquals(Screen.Write, Navigation.restore(reply.saved).back().current)
    }
}
