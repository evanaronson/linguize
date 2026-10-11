package com.evanaronson.linguize.ui.popup

import android.app.Application
import android.content.Intent
import com.evanaronson.linguize.ui.home.SettingsReturn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PopupExternalActivityTest {
    @Test
    fun aGatewayThatFinishesBeforeItsFirstResumeRestoresThePopup() {
        var returns = 0
        val onReturn = { returns++; Unit }
        val token = SettingsReturn.await(onReturn)
        val intent = PopupExternalActivity.intent(RuntimeEnvironment.getApplication(), Intent(), token)
            .apply { removeExtra("external") }
        val controller = Robolectric.buildActivity(PopupExternalActivity::class.java, intent)
        try {
            controller.create()
            assertTrue(controller.get().isFinishing)
            controller.destroy()
            assertEquals(1, returns)
            SettingsReturn.left(token)
            assertEquals(1, returns)
        } finally {
            SettingsReturn.cancel(onReturn)
        }
    }
}
