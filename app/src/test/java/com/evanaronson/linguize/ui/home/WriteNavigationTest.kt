package com.evanaronson.linguize.ui.home

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Write is one tab with its editor on top; the Writing pad, which opened its own activity, is gone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WriteNavigationTest {
    @Test fun writeRestoresFromSavedState() {
        // Write opens no pages of its own any more; removed ones are covered in TabNavigationTest.
        for (start in listOf(Navigation(Screen.Chat), Navigation())) {
            val write = start.open(Screen.Write)
            assertEquals(write, Navigation.restore(write.saved))
            assertEquals(Screen.Write, write.tab)
        }
    }

    @Test fun theWritingPadActivityIsNoLongerDeclared() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pad = Intent().setClassName(context, "com.evanaronson.linguize.menu.WritingPadActivity")
        assertNull(context.packageManager.resolveActivity(pad, 0))
        // The manifest is the app's: the activity hosting Write is declared.
        assertNotNull(context.packageManager.resolveActivity(Intent(context, HomeActivity::class.java), 0))
    }
}
