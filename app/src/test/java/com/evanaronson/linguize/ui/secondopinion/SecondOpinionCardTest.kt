package com.evanaronson.linguize.ui.secondopinion

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.FakeClient
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.VerdictChange
import com.evanaronson.linguize.data.SecondOpinionPlan
import com.evanaronson.linguize.data.SecondOpinionTarget
import com.evanaronson.linguize.history.CheckHistory
import com.evanaronson.linguize.history.HistoryEnvironment
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.ResultCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy
import java.time.Duration

/** The second opinion on the real card: offered under the suggestions, then summarised with badges. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecondOpinionCardTest {
    private val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    private val activity = activityController.get()

    private fun answer(verdict: Verdict) = ModelAnswer(verdict, Json.encodeToString(verdict))

    private val gemini = FakeClient {
        answer(Verdict(Verdict.Status.Ok, language = "Catalan", hasErrors = true, corrected = "Com estàs? Fins demà",
            fixes = listOf(VerdictChange("estas", "estàs", confidence = 9, impact = 6),
                VerdictChange("dema", "demà", confidence = 9, impact = 6))))
    }
    private val openai = FakeClient {
        answer(Verdict(Verdict.Status.Ok, language = "Catalan", hasErrors = true, corrected = "Com està? Fins demà",
            fixes = listOf(VerdictChange("estas", "està", confidence = 5, impact = 6),
                VerdictChange("dema", "demà", confidence = 9, impact = 6))))
    }
    private val checker = Checker(FakePreferences(), { "key" }, Prompt(system = "instructions", schemaJson = "{}"),
        mapOf(Provider.Gemini to gemini, Provider.OpenAI to openai))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** "Try it" checks are never recorded; the store refuses writes to prove nothing is kept. */
    private val store = Proxy.newProxyInstance(HistoryStore::class.java.classLoader, arrayOf(HistoryStore::class.java)) { _, method, _ ->
        if (method.name == "save" || method.name == "close") throw AssertionError("History must not be written")
        null
    } as HistoryStore
    private val history = CheckHistory(store, object : HistoryEnvironment {
        override val enabled = false
        override val deviceId = "device"
        override val appVersion = "1.0"
    }, scope)
    private val viewModels = ViewModelStore()
    private val check = ViewModelProvider(viewModels, viewModelFactory {
        initializer {
            CheckViewModel(checker = { checker }, history = history,
                secondOpinionPlan = { SecondOpinionPlan.Ready(SecondOpinionTarget(Provider.OpenAI, "gpt-x")) })
        }
    })[CheckViewModel::class.java]

    @After fun tearDown() {
        viewModels.clear()
        scope.cancel()
        activityController.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun offersThenSummarisesTheSecondOpinion() {
        check.check("Com estas? Fins dema", null, Origin.Tester)
        waitFor("the check") { check.state is CardState.Done }
        activity.setContent {
            MaterialTheme {
                check.state?.let { ResultCard(it, CardActions.of(check, activity, onClose = {}, onOpenSettings = null)) }
            }
        }
        waitFor("the offer") { texts().any { it == "Ask OpenAI" } }
        assertFalse(texts().any { it.contains("differ") })

        val ask = nodes(merged = true).first { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Ask OpenAI" } == true &&
                node.config.getOrNull(SemanticsActions.OnClick) != null
        }
        assertTrue(requireNotNull(ask.config[SemanticsActions.OnClick].action).invoke())

        waitFor("the summary") { texts().any { it == "Google Gemini and OpenAI differ" } }
        val shown = texts()
        assertTrue(shown.toString(), shown.contains("1 agreed, 1 disagreed"))
        assertTrue(shown.toString(), shown.contains("Second opinion: Both agree"))
        assertTrue(shown.toString(), shown.contains("Second opinion: Disagree"))
        assertTrue(shown.toString(), shown.contains("Google Gemini says estàs"))
        assertTrue(shown.toString(), shown.contains("OpenAI says està"))
        assertTrue(shown.toString(), shown.contains("dema becomes demà"))
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            if (condition()) return
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for $what: ${runCatching { texts() }.getOrNull()}")
            Thread.sleep(5)
        }
    }

    /** Every text and content description on screen. */
    private fun texts(): List<String> = nodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun nodes(merged: Boolean = false): List<SemanticsNode> {
        fun owner(view: View): SemanticsOwner? {
            val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            if (method != null) return method.invoke(view) as SemanticsOwner
            if (view is ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
            return null
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        val root = owner(activity.window.decorView) ?: return emptyList()
        return flatten(if (merged) root.rootSemanticsNode else root.unmergedRootSemanticsNode)
    }
}
