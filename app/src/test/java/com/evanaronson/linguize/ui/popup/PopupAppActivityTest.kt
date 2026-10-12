package com.evanaronson.linguize.ui.popup

import android.app.Application
import android.os.Bundle
import com.evanaronson.linguize.ui.home.HomeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PopupAppActivityTest {
    @Test
    fun recreationKeepsThePopupHiddenWithoutOpeningAnotherAppInstance() {
        var returns = 0
        val onReturn = { returns++; Unit }
        val token = PopupReturn.await(onReturn)
        val intent = PopupAppActivity.intent(RuntimeEnvironment.getApplication(), token)
        val controller = Robolectric.buildActivity(PopupAppActivity::class.java, intent)
        try {
            controller.create().start().resume()
            val opened = shadowOf(controller.get()).nextStartedActivityForResult
            assertEquals(HomeActivity::class.java.name, opened.intent.component?.className)
            val saved = Bundle()
            controller.saveInstanceState(saved).pause().stop().destroy()
            assertEquals(0, returns)
            val restored = Robolectric.buildActivity(PopupAppActivity::class.java, intent).create(saved)
            assertNull(shadowOf(restored.get()).nextStartedActivityForResult)
            assertEquals(0, returns)
            restored.get().finish()
            restored.destroy()
            assertEquals(1, returns)
        } finally {
            PopupReturn.cancel(onReturn)
        }
    }

    @Test
    fun aGatewayThatFinishesBeforeItsFirstResumeRestoresThePopup() {
        var returns = 0
        val onReturn = { returns++; Unit }
        val token = PopupReturn.await(onReturn)
        val intent = PopupAppActivity.intent(RuntimeEnvironment.getApplication(), token)

        val controller = Robolectric.buildActivity(PopupAppActivity::class.java, intent)
        try {
            controller.create()
            controller.get().finish()
            assertTrue(controller.get().isFinishing)
            controller.destroy()
            assertEquals(1, returns)
            PopupReturn.left(token)
            assertEquals(1, returns)
        } finally {
            PopupReturn.cancel(onReturn)
        }
    }
}
