package com.evanaronson.linguize.data.teachme

import android.app.Application
import com.evanaronson.linguize.core.teachme.Lesson
import com.evanaronson.linguize.core.teachme.LessonExample
import com.evanaronson.linguize.core.teachme.LessonTopic
import com.evanaronson.linguize.core.teachme.QuizQuestion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LessonStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val file get() = File(folder.root, LessonStore.FILE_NAME)

    private fun lesson(language: String = "Catalan", rule: String = "Rule") = Lesson(
        language, rule, "Yours", List(3) { LessonExample("S$it", "T$it") }, "Trap",
        listOf(QuizQuestion("Q", listOf("a", "b"), 0, "F"), QuizQuestion("Q2", listOf("c", "d"), 1, "F2")),
    )

    private val topic = LessonTopic("vaig poder", "podré", "Future, not past", "Catalan", "Demà vaig poder venir.")

    @Test fun aSavedLessonOpensAgainAfterRestart() {
        LessonStore(file).put(topic, "English", lesson())
        val reopened = LessonStore(file)
        assertEquals(lesson(), reopened.get(topic, "English"))
        // Spacing and language case don't make a different decision.
        assertEquals(lesson(), reopened.get(topic.copy(from = " vaig  poder ", language = "catalan"), "english"))
    }

    @Test fun theKeyIsLanguageChangeReasonAndTheWritersLanguage() {
        val store = LessonStore(file)
        store.put(topic, "English", lesson())
        assertNull(store.get(topic.copy(language = "Spanish"), "English"))
        assertNull(store.get(topic.copy(to = "podria"), "English"))
        assertNull(store.get(topic.copy(why = "Conditional"), "English"))
        assertNull(store.get(topic, "Catalan"))
        // The sentence is context, not part of the key.
        assertEquals(lesson(), store.get(topic.copy(sentence = "Another one."), "English"))
    }

    @Test fun aCardThatDoesntKnowTheLanguageFindsTheSameDecision() {
        val store = LessonStore(file)
        val unknown = topic.copy(language = null)
        store.put(unknown, "English", lesson(language = "Catalan"))
        // Saved under the language the model named, so History (which knows it) finds it too.
        assertEquals(lesson(), store.get(topic, "English"))
        assertEquals(lesson(), store.get(unknown, "English"))
        assertNull(store.get(topic.copy(language = "Spanish"), "English"))
    }

    @Test fun neverKeepsTheWritersSentence() {
        LessonStore(file).put(topic, "English", lesson())
        assertFalse(file.readText().contains("Demà vaig poder venir."))
    }

    @Test fun aNewerLessonReplacesTheOld() {
        val store = LessonStore(file)
        store.put(topic, "English", lesson(rule = "Old"))
        store.put(topic, "English", lesson(rule = "New"))
        assertEquals("New", store.get(topic, "English")!!.rule)
        assertEquals(1, store.size())
    }

    @Test fun keepsOnlyTheNewestLessons() {
        val store = LessonStore(file, capacity = 3)
        repeat(5) { store.put(topic.copy(to = "to$it"), "English", lesson()) }
        assertEquals(3, LessonStore(file, capacity = 3).size())
        assertNull(store.get(topic.copy(to = "to0"), "English"))
        assertNotNull(store.get(topic.copy(to = "to4"), "English"))
    }

    @Test fun anUnreadableFileCountsAsEmptyAndIsReplaced() {
        file.writeText("{not json")
        val store = LessonStore(file)
        assertNull(store.get(topic, "English"))
        store.put(topic, "English", lesson())
        assertEquals(lesson(), LessonStore(file).get(topic, "English"))
    }
}
