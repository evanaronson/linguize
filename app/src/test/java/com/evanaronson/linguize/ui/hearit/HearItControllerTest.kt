package com.evanaronson.linguize.ui.hearit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class HearItControllerTest {
    private val spain = Locale.forLanguageTag("es-ES")
    private val spanish = Locale.forLanguageTag("es")
    private val english = Locale.forLanguageTag("en")

    private class FakeEngine(
        val ready: (Boolean) -> Unit,
        val utterance: (String, Utterance) -> Unit,
        val voices: Map<Locale, Voice>,
        val accepts: Boolean,
    ) : SpeechEngine {
        val spoken = mutableListOf<Triple<String, Locale, Float>>()
        val ids = mutableListOf<String>()
        var stops = 0
        var shutDown = false
        override fun voice(locale: Locale) = voices[locale] ?: Voice.NotSupported
        override fun speak(text: String, locale: Locale, rate: Float, id: String): Boolean {
            spoken += Triple(text, locale, rate)
            ids += id
            return accepts
        }
        override fun stop() { stops++ }
        override fun shutdown() { shutDown = true }
    }

    private val engines = mutableListOf<FakeEngine>()
    private var voices = mapOf(spain to Voice.Available, spanish to Voice.Available, english to Voice.Available)
    private var accepts = true
    private val speed = MemorySpeedStore()
    private val controller = HearItController(
        engines = { ready, utterance -> FakeEngine(ready, utterance, voices, accepts).also { engines += it } },
        speed = speed,
    )
    private val engine get() = engines.last()

    @Test
    fun `binds the engine on first play only and speaks once it is ready`() {
        assertTrue(engines.isEmpty())
        controller.play("text", "Hola, ¿qué tal?", listOf(spain, spanish))
        assertEquals(1, engines.size)
        assertEquals("text", controller.playing)
        assertTrue(controller.preparing)
        assertTrue(engine.spoken.isEmpty())

        engine.ready(true)
        assertFalse(controller.preparing)
        assertEquals(listOf(Triple("Hola, ¿qué tal?", spain, HearItController.NORMAL_RATE)), engine.spoken)

        engine.utterance(engine.ids.single(), Utterance.Done)
        assertNull(controller.playing)

        controller.play("meaning", "Hi, how are you?", listOf(english))
        assertEquals("The ready engine is reused", 1, engines.size)
        assertEquals(english, engine.spoken.last().second)
    }

    @Test
    fun `pressing the playing control stops it and another control switches`() {
        controller.play("text", "Hola", listOf(spain))
        engine.ready(true)
        controller.toggle("change:podré", "podré", listOf(spain))
        assertEquals("change:podré", controller.playing)
        assertEquals("podré", engine.spoken.last().first)

        // The first utterance ending late doesn't end the second.
        engine.utterance(engine.ids.first(), Utterance.Done)
        assertEquals("change:podré", controller.playing)

        controller.toggle("change:podré", "podré", listOf(spain))
        assertNull(controller.playing)
        assertTrue(engine.stops >= 2)
    }

    @Test
    fun `stopIf only stops the control that left`() {
        controller.play("text", "Hola", listOf(spain))
        engine.ready(true)
        controller.stopIf("meaning")
        assertEquals("text", controller.playing)
        controller.stopIf("text")
        assertNull(controller.playing)
    }

    @Test
    fun `falls back to the next locale when the regional voice is missing`() {
        voices = mapOf(spain to Voice.NotSupported, spanish to Voice.Available)
        controller.play("text", "Hola", listOf(spain, spanish))
        engine.ready(true)
        assertEquals(spanish, engine.spoken.single().second)
        assertNull(controller.problem)
    }

    @Test
    fun `no voice at all reports the preferred locale and rebinds next time`() {
        voices = mapOf(spain to Voice.MissingData, spanish to Voice.NotSupported)
        controller.play("text", "Hola", listOf(spain, spanish))
        engine.ready(true)
        assertNull(controller.playing)
        assertEquals("text" to SpeechProblem.MissingVoice(spain, downloadable = true), controller.problem)
        assertTrue("Released, so a voice installed from settings is seen", engine.shutDown)

        voices = mapOf(spain to Voice.Available)
        controller.play("text", "Hola", listOf(spain, spanish))
        assertEquals(2, engines.size)
        assertNull("A new attempt clears the problem", controller.problem)
        engine.ready(true)
        assertEquals(spain, engine.spoken.single().second)
    }

    @Test
    fun `a voice the engine can't download is not offered as a download`() {
        voices = emptyMap()
        controller.play("text", "Bon dia", listOf(Locale.forLanguageTag("ca-ES")))
        engine.ready(true)
        assertEquals(SpeechProblem.MissingVoice(Locale.forLanguageTag("ca-ES"), downloadable = false), controller.problem?.second)
    }

    @Test
    fun `no engine is reported on the control that asked`() {
        controller.play("meaning", "Hi", listOf(english))
        engine.ready(false)
        assertNull(controller.playing)
        assertFalse(controller.preparing)
        assertEquals("meaning" to SpeechProblem.NoEngine, controller.problem)
        assertTrue(engine.shutDown)
        controller.clearProblem()
        assertNull(controller.problem)
    }

    @Test
    fun `a refused or failed utterance is a failure`() {
        accepts = false
        controller.play("text", "Hola", listOf(spain))
        engine.ready(true)
        assertEquals("text" to SpeechProblem.Failed, controller.problem)

        accepts = true
        controller.play("text", "Hola", listOf(spain))
        engine.ready(true)
        engine.utterance(engine.ids.single(), Utterance.Failed)
        assertNull(controller.playing)
        assertEquals("text" to SpeechProblem.Failed, controller.problem)
    }

    @Test
    fun `slow is remembered and used for the next play`() {
        controller.changeSpeed(true)
        assertTrue(speed.slow)
        controller.play("text", "Hola", listOf(spain))
        engine.ready(true)
        assertEquals(HearItController.SLOW_RATE, engine.spoken.single().third)

        val next = HearItController({ r, u -> FakeEngine(r, u, voices, true) }, speed)
        assertTrue("A new card starts slow too", next.slow)
    }

    @Test
    fun `shutdown stops and releases, and a stop before ready speaks nothing`() {
        controller.play("text", "Hola", listOf(spain))
        controller.stop()
        engine.ready(true)
        assertTrue(engine.spoken.isEmpty())
        assertNull(controller.playing)

        controller.play("text", "Hola", listOf(spain))
        controller.shutdown()
        assertTrue(engine.shutDown)
        assertNull(controller.playing)
    }

    @Test
    fun `blank text plays nothing`() {
        controller.play("text", "  ", listOf(spain))
        assertTrue(engines.isEmpty())
        assertNull(controller.playing)
    }
}
