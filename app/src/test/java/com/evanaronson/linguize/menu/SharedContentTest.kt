package com.evanaronson.linguize.menu

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SharedContentTest {
    @Test fun sharedTextIsTrimmedAndBlankTextIsNothing() {
        val shared = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "  Bon dia \n")
        assertEquals("Bon dia", SharedContent.text(shared))
        assertNull(SharedContent.text(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "   ")))
        assertNull(SharedContent.text(Intent(Intent.ACTION_SEND)))
        // Only a share counts.
        assertNull(SharedContent.text(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "Bon dia")))
    }
}
