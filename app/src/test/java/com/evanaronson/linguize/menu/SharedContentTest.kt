package com.evanaronson.linguize.menu

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SharedContentTest {
    private val own = "com.evanaronson.linguize.lab"

    @Test fun sharedTextIsTrimmedAndBlankTextIsNothing() {
        val shared = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "  Bon dia \n")
        assertEquals("Bon dia", SharedContent.text(shared))
        assertNull(SharedContent.text(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "   ")))
        assertNull(SharedContent.text(Intent(Intent.ACTION_SEND)))
        // Only a share counts.
        assertNull(SharedContent.text(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "Bon dia")))
    }

    @Test fun anotherAppsImageOpens() {
        val uri = Uri.parse("content://com.whatsapp.provider.media/item/1")
        val shared = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
        assertEquals(uri, SharedContent.image(shared, own))
        assertNull(SharedContent.image(Intent(Intent.ACTION_SEND).setType("image/jpeg"), own))
    }

    @Test fun thisAppsOwnFilesAndPlainFilePathsNeverOpen() {
        for (unsafe in listOf(
            "content://$own.files/exports/history.json",
            "content://0@$own.files/exports/history.json",
            "content://$own/anything",
            "file:///data/data/$own/databases/history.db",
        )) {
            val shared = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse(unsafe))
            assertNull(unsafe, SharedContent.image(shared, own))
        }
    }

    @Test fun safetyLooksAtTheWholeAuthorityNotAPrefix() {
        assertTrue(SharedContent.opensSafely("content", "media", own))
        // The other Linguize app is a different package.
        assertTrue(SharedContent.opensSafely("content", "com.evanaronson.linguize.files", own))
        assertTrue(SharedContent.opensSafely("content", "${own}x.files", own))
        assertFalse(SharedContent.opensSafely("content", "other;$own.files", own))
        assertFalse(SharedContent.opensSafely("content", "", own))
        assertFalse(SharedContent.opensSafely(null, "media", own))
    }
}
