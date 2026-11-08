package com.evanaronson.linguize.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class LabReplyNavigationTest {
    @Test fun helpMeReplyIsRestoredAboveHome() {
        val reply = Navigation().open(Screen.LabReply)
        assertEquals(reply, Navigation.restore(reply.saved))
        assertEquals(Screen.Home, Navigation.restore(reply.saved).back().current)
    }
}
