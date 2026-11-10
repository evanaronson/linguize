package com.evanaronson.linguize.ui.home

import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModelProvider
import com.evanaronson.linguize.App
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.PatternsLab
import com.evanaronson.linguize.history.HistoryEvidenceReader
import com.evanaronson.linguize.data.lab.ExplanationStore
import com.evanaronson.linguize.data.lab.SavedExplanation
import com.evanaronson.linguize.history.patterns.FakePatternHistory
import com.evanaronson.linguize.history.patterns.PatternFixtures.check
import com.evanaronson.linguize.history.patterns.PatternFixtures.fix
import com.evanaronson.linguize.llm.lab.PatternsPrompt
import com.evanaronson.linguize.ui.language.AppLanguage
import com.evanaronson.linguize.ui.patterns.PatternsViewModel
import com.evanaronson.linguize.ui.practice.PracticeViewModel
import com.evanaronson.linguize.ui.progress.ProgressView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** Real Home composition, saved Bundles and ViewModelStores; only feature evidence is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScopeLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication() as App
    private val controllers = mutableListOf<ActivityController<HomeActivity>>()
    private val history = FakePatternHistory(listOf(
        check("ca-1", 1, fix("aixo", "això"), language = "ca"),
        check("ca-2", 2, fix("cafe", "cafè"), language = "ca"),
        check("es-1", 3, fix("tambien", "también"), language = "es"),
    ))

    @Before fun evidence() {
        // No process housekeeping is needed to test Activity dispatch and its Flow observer.
        app.appScope.cancel()
        ReflectionHelpers.setField(app, "history\$delegate", lazyOf(history))
        val evidence = HistoryEvidenceReader(history)
        ReflectionHelpers.setField(app, "historyEvidence\$delegate", lazyOf(evidence))
        val lab = PatternsLab(history, object : ExplanationStore {
            override fun read(): SavedExplanation? = null
            override fun write(explanation: SavedExplanation) = Unit
            override fun delete() = Unit
        }, FakePreferences(), { null }, emptyMap(), { PatternsPrompt("Unused") }, io = Dispatchers.Main.immediate, evidence = evidence)
        ReflectionHelpers.setField(app, "patterns\$delegate", lazyOf(lab))
        AppLanguage.of(app).select(null)
    }

    @After fun close() {
        controllers.reversed().forEach { it.pause().stop().destroy() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun autoBlockSurvivesRotationAndFreshHomeUntilAnActualLanguageChange() {
        val parent = progressDestination(ProgressView.Patterns, appLanguage = null, block = "ca")
        val route = Screen.PatternDetail("accents").inScope(parent.scope)
        val original = open(savedPath(parent, route))
        val first = patterns(original)
        assertCatalan(first)

        val rotated = Configuration(original.get().resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        original.configurationChange(rotated).visible().windowFocusChanged(true)
        settle(original)
        assertSame(first, patterns(original))
        assertCatalan(patterns(original))

        val saved = Bundle()
        original.saveInstanceState(saved)
        assertEquals(route.saved, saved.getStringArrayList("navigation")!!.last())
        destroy(original)
        val restored = open(saved)
        val fresh = patterns(restored)
        assertNotSame(first, fresh)
        assertCatalan(fresh)

        AppLanguage.of(app).select("es")
        settle(restored)
        assertEquals("es", fresh.state.value.language)
        AppLanguage.of(app).select(null)
        settle(restored)
        assertEquals(null, fresh.state.value.language)
        assertEquals(setOf("ca-1", "ca-2", "es-1"), fresh.state.value.snapshot?.report?.evidenceSessions)
        val switched = Bundle()
        restored.saveInstanceState(switched)
        assertFalse(switched.getStringArrayList("navigation")!!.any { it.startsWith("scoped:") })
    }

    @Test fun savedPracticeRouteScopesAFreshHomeOwnedModelBeforeEntry() {
        val route = progressDestination(ProgressView.Practice, appLanguage = null, block = "ca")
        val original = open(savedPath(route))
        val first = ViewModelProvider(original.get())[PracticeViewModel::class.java]
        assertEquals("ca", first.state.value.language)
        val saved = Bundle()
        original.saveInstanceState(saved)
        destroy(original)

        val restored = open(saved)
        val fresh = ViewModelProvider(restored.get())[PracticeViewModel::class.java]
        assertNotSame(first, fresh)
        assertEquals("ca", fresh.state.value.language)
        assertEquals(route.saved, saved.getStringArrayList("navigation")!!.last())
    }

    private fun savedPath(vararg pages: Screen) = Bundle().apply {
        val path = pages.fold(Navigation(Screen.Chat).open(Screen.Progress)) { path, page -> path.open(page) }
        putStringArrayList("navigation", ArrayList(path.saved))
    }

    private fun open(saved: Bundle): ActivityController<HomeActivity> =
        Robolectric.buildActivity(HomeActivity::class.java).create(saved).start().resume().visible()
            .windowFocusChanged(true).also { controllers += it; settle(it) }

    private fun patterns(controller: ActivityController<HomeActivity>): PatternsViewModel =
        ViewModelProvider(controller.get())[PatternsViewModel::class.java]

    private fun assertCatalan(model: PatternsViewModel) {
        assertEquals("ca", model.state.value.language)
        assertEquals(setOf("ca-1", "ca-2"), model.state.value.snapshot?.report?.evidenceSessions)
    }

    private fun destroy(controller: ActivityController<HomeActivity>) {
        controllers.remove(controller)
        controller.pause().stop().destroy()
    }

    private fun settle(controller: ActivityController<HomeActivity>) {
        val root = controller.get().window.decorView
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 1920)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
    }
}
