package com.evanaronson.linguize.ui

import android.app.Application
import android.content.ClipboardManager
import android.os.Build
import com.evanaronson.linguize.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35], application = Application::class)
class ClipboardTest {
    @Test
    fun copiedWritingIsSensitiveOnAndroid12AndNewer() {
        val context = RuntimeEnvironment.getApplication()
        context.copyToClipboard("A private message")

        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("A private message", clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        if (Build.VERSION.SDK_INT < 33) {
            assertEquals(context.getString(R.string.clipboard_copied), ShadowToast.getTextOfLatestToast())
        } else {
            assertNull("Android supplies its own confirmation", ShadowToast.getTextOfLatestToast())
        }
    }
}
