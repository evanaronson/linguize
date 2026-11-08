package com.evanaronson.linguize.ui.tonecheck

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.FakeClient
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.QUESTION
import com.evanaronson.linguize.QUESTION_JSON
import com.evanaronson.linguize.QUESTION_VERDICT
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.fakeChecker
import com.evanaronson.linguize.history.CheckHistory
import com.evanaronson.linguize.history.HistoryEnvironment
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** A version taken from "How it lands" is what goes back, until the card changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChosenVersionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val viewModels = ViewModelStore()

    // Try it checks are never recorded, so history must not touch the store.
    private val store = Proxy.newProxyInstance(HistoryStore::class.java.classLoader, arrayOf(HistoryStore::class.java)) { _, method, _ ->
        throw AssertionError("History store used: ${method.name}")
    } as HistoryStore
    private val environment = object : HistoryEnvironment {
        override val enabled = true
        override val deviceId = "device"
        override val appVersion = "1.0"
    }
    private val client = FakeClient { ModelAnswer(QUESTION_VERDICT, QUESTION_JSON) }
    private val checker = fakeChecker(FakePreferences(), client)
    private val check = ViewModelProvider(
        viewModels,
        viewModelFactory { initializer { CheckViewModel(checker = { checker }, history = CheckHistory(store, environment, scope)) } },
    )[CheckViewModel::class.java]

    @After fun stop() {
        viewModels.clear()
        scope.cancel()
    }

    private fun reviewed(): CheckResult.Reviewed {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (check.state !is CardState.Done) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for an answer")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        return (check.state as CardState.Done).result as CheckResult.Reviewed
    }

    @Test fun aChosenVersionIsPutBackUntilTheWriterGoesBack() {
        check.check(QUESTION, null, Origin.Tester)
        reviewed()
        assertNull(check.workingText)
        check.useVersion("  Hola, com va tot?  ")
        assertEquals("Hola, com va tot?", check.chosenVersion)
        assertEquals("Hola, com va tot?", check.workingText)
        check.useVersion(null)
        assertNull(check.chosenVersion)
        assertNull(check.workingText)
    }

    @Test fun acceptingAChangeDropsTheChosenVersion() {
        check.check(QUESTION, null, Origin.Tester)
        reviewed()
        check.useVersion("Hola, com va tot?")
        check.acceptAll(EditKind.Fix)
        assertNull(check.chosenVersion)
        assertEquals("Com estàs?", check.workingText)
    }

    @Test fun checkingAgainDropsTheChosenVersion() {
        check.check(QUESTION, null, Origin.Tester)
        reviewed()
        check.useVersion("Hola, com va tot?")
        check.recheck()
        reviewed()
        assertNull(check.chosenVersion)
        assertNull(check.workingText)
    }

    @Test fun nothingIsChosenWithoutACardOrWithBlankText() {
        check.useVersion("Hola")
        assertNull(check.chosenVersion)
        check.check(QUESTION, null, Origin.Tester)
        reviewed()
        check.useVersion("   ")
        assertNull(check.chosenVersion)
    }
}
