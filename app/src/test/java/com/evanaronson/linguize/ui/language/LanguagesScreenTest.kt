package com.evanaronson.linguize.ui.language

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.language.Languages
import com.evanaronson.linguize.core.language.MyLanguages
import com.evanaronson.linguize.data.language.CatalogLanguageNames
import com.evanaronson.linguize.data.language.LanguageChoice
import com.evanaronson.linguize.data.language.LanguageList
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Lab, lang-selector: the Languages page, drawn for real. Checking adds a language to the
 * selector's short list, unchecking removes it (never the last), search and Suggested work, and
 * rows read as checkboxes with the language's full names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LanguagesScreenTest {
    private val before = Languages.names

    @After fun restore() {
        pages.forEach { it.close() }
        pages.clear()
        Languages.names = before
    }

    /** The writer's languages in memory, with the rules MyLanguagesStore applies. */
    private class Memory(var list: List<String>) : LanguageList {
        override val codes get() = list
        override fun add(code: String) = MyLanguages.add(list, code).also { list = it }
        override fun remove(code: String) = MyLanguages.remove(list, code).also { list = it }
        override fun move(code: String, index: Int) = MyLanguages.move(list, code, index).also { list = it }
    }

    private class Choice(override var current: String? = null) : LanguageChoice

    /**
     * Drives its own frames: an earlier Robolectric window in the same test process can keep the
     * shared frame dispatcher, and then this page's LaunchedEffect never runs (seen in full runs).
     */
    private class Page(val root: ComposeView, val activity: ComponentActivity, val frames: BroadcastFrameClock,
        val recomposer: Recomposer, val uiScope: CoroutineScope) {
        private var frameTime = 0L
        fun idle() {
            repeat(8) {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                frameTime += 16_666_667L
                frames.sendFrame(frameTime)
                Thread.sleep(5)
            }
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(30000, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 30000)
            // Compose measures and places in draw, which a test doesn't do: ask the owner directly.
            fun measureAndLayout(view: View) {
                view.javaClass.methods.firstOrNull { it.name == "measureAndLayout" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }
                    ?.invoke(view, true)
                if (view is ViewGroup) for (index in 0 until view.childCount) measureAndLayout(view.getChildAt(index))
            }
            measureAndLayout(root)
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun nodes(): List<SemanticsNode> {
            idle()
            fun owner(view: View): SemanticsOwner? {
                val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
                if (method != null) return method.invoke(view) as SemanticsOwner
                if (view is ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
                return null
            }
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            return flatten(requireNotNull(owner(root)).rootSemanticsNode)
        }

        fun tagged(tag: String): SemanticsNode? = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun row(code: String) = tagged("$LANGUAGES_ROW_TAG$code")
        fun texts() = nodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        fun click(node: SemanticsNode?) = assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action!!.invoke())

        fun close() {
            root.disposeComposition()
            recomposer.cancel()
            uiScope.cancel()
        }
    }

    private val pages = mutableListOf<Page>()

    private fun open(mine: List<String>, history: List<String> = emptyList()): Triple<Page, AppLanguage, Memory> {
        Languages.names = CatalogLanguageNames { com.evanaronson.linguize.data.language.LanguageCatalogAsset.get(ApplicationProviderContext.get()) }
        val memory = Memory(mine)
        val profiles = listOf(WritingProfile.defaultFor(Language.Spanish).copy(variety = "Mexican"))
        val language = AppLanguage(Choice(), { profiles }, { history }, memory)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val frames = BroadcastFrameClock()
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
        val recomposer = Recomposer(uiScope.coroutineContext)
        uiScope.launch { recomposer.runRecomposeAndApplyChanges() }
        val root = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent { MaterialTheme { LanguagesScreen(language) } }
        }
        activity.setContentView(root)
        val page = Page(root, activity, frames, recomposer, uiScope).also(pages::add)
        // The page reads the writer's languages and History off the main thread first.
        waitFor(page) { language.mine.value == mine && language.suggested.value == history.filter { it !in mine } }
        return Triple(page, language, memory)
    }

    /** Edits run off the main thread, which a busy machine can make slow: wait up to 30 s. */
    private fun waitFor(page: Page, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            page.idle()
        }
        assertTrue("timed out", condition())
    }

    @Test fun checkingAndUncheckingUpdatesTheSelectorAndKeepsTheLastLanguage() {
        val (page, language, memory) = open(listOf("ca", "es"))
        val french = requireNotNull(page.row("fr"))
        assertEquals(Role.Checkbox, french.config.getOrNull(SemanticsProperties.Role))
        assertEquals(ToggleableState.Off, french.config.getOrNull(SemanticsProperties.ToggleableState))
        // Screen readers hear the brand, English and native names.
        assertEquals(listOf("Gallicize, French, Français"), french.config.getOrNull(SemanticsProperties.ContentDescription))

        page.click(french)
        waitFor(page) { language.options.value.map { it.code } == listOf("ca", "es", "fr") }
        assertEquals(listOf("ca", "es", "fr"), memory.list)
        assertEquals(ToggleableState.On, page.row("fr")!!.config.getOrNull(SemanticsProperties.ToggleableState))
        assertEquals(listOf(null, "ca", "es", "fr"), selectorOptions(language.options.value).map { it?.code })

        page.click(page.row("ca"))
        waitFor(page) { memory.list == listOf("es", "fr") }
        page.click(page.row("fr"))
        waitFor(page) { memory.list == listOf("es") }
        // The last one stays checked: unchecking it explains instead.
        page.click(page.row("es"))
        page.idle()
        assertEquals(listOf("es"), memory.list)
        assertEquals(ToggleableState.On, page.row("es")!!.config.getOrNull(SemanticsProperties.ToggleableState))
        assertEquals("Castilianize is your only language. Check another one first.",
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        page.activity.finish()
    }

    @Test fun aLanguageMovesUpTheShortList() {
        val (page, language, memory) = open(listOf("ca", "es", "fr"))
        assertNull(page.tagged("${LANGUAGES_UP_TAG}ca"))
        val up = page.tagged("${LANGUAGES_UP_TAG}fr")
        assertEquals(listOf("Move Gallicize up"), up!!.config.getOrNull(SemanticsProperties.ContentDescription))
        page.click(up)
        waitFor(page) { memory.list == listOf("ca", "fr", "es") }
        waitFor(page) { language.options.value.map { it.code } == listOf("ca", "fr", "es") }
        page.activity.finish()
    }

    @Test fun searchAndSuggested() {
        val (page, _, _) = open(listOf("ca", "es"), history = listOf("es", "de"))
        waitFor(page) { "Suggested" in page.texts() }
        assertTrue("Your languages" in page.texts())
        assertTrue("Every language" in page.texts())
        val search = requireNotNull(page.tagged(LANGUAGES_SEARCH_TAG))
        assertTrue(search.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("gallic")))
        waitFor(page) { page.row("ca") == null }
        assertNotNull(page.row("fr"))
        assertFalse("Suggested" in page.texts())
        page.activity.finish()
    }

    @Test fun bothEntryPointsOpenTheSameScreen() {
        val activity = File("src/main/java/com/evanaronson/linguize/ui/home/HomeActivity.kt").readText()
        // One page component, drawn for one screen.
        assertEquals(1, Regex("""LanguagesScreen\(""").findAll(activity).count())
        assertEquals(2, Regex("""navigation\.open\(Screen\.Languages\)""").findAll(activity).count())
        val switch = File("src/main/java/com/evanaronson/linguize/ui/language/LanguageSwitch.kt").readText()
        assertTrue(switch.contains("R.string.lab_lang_selector_add"))
    }
}

/** The test application's context. */
private object ApplicationProviderContext {
    fun get(): Application = androidx.test.core.app.ApplicationProvider.getApplicationContext()
}
