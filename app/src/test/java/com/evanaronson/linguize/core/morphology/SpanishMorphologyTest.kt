package com.evanaronson.linguize.core.morphology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpanishMorphologyTest {
    private val morphology = SharedMorphology.instance

    private fun readings(word: String) = morphology.verbs(word, "es").map { Triple(it.lemma, it.tense, it.person) }.toSet()

    private fun assertReading(word: String, lemma: String, tense: Tense, person: Person?) {
        val found = readings(word)
        assertTrue("$word should be $lemma $tense $person, was $found", Triple(lemma, tense, person) in found)
    }

    private fun assertForms(lemma: String, tense: Tense, forms: String) {
        forms.split(' ').forEachIndexed { index, form -> assertReading(form, lemma, tense, Person.entries[index]) }
    }

    @Test
    fun regularArParadigm() {
        assertForms("hablar", Tense.Present, "hablo hablas habla hablamos habláis hablan")
        assertForms("hablar", Tense.Preterite, "hablé hablaste habló hablamos hablasteis hablaron")
        assertForms("hablar", Tense.Imperfect, "hablaba hablabas hablaba hablábamos hablabais hablaban")
        assertForms("hablar", Tense.Future, "hablaré hablarás hablará hablaremos hablaréis hablarán")
        assertForms("hablar", Tense.Conditional, "hablaría hablarías hablaría hablaríamos hablaríais hablarían")
        assertForms("hablar", Tense.PresentSubjunctive, "hable hables hable hablemos habléis hablen")
        assertForms("hablar", Tense.ImperfectSubjunctive, "hablara hablaras hablara habláramos hablarais hablaran")
        assertForms("hablar", Tense.ImperfectSubjunctive, "hablase hablases hablase hablásemos hablaseis hablasen")
        assertReading("hablad", "hablar", Tense.Imperative, Person.P2)
        assertReading("habla", "hablar", Tense.Imperative, Person.S2)
        assertReading("hablando", "hablar", Tense.Gerund, null)
        assertReading("hablado", "hablar", Tense.Participle, null)
        assertReading("habladas", "hablar", Tense.Participle, null)
        assertReading("hablar", "hablar", Tense.Infinitive, null)
    }

    @Test
    fun regularErAndIrParadigms() {
        assertForms("comer", Tense.Present, "como comes come comemos coméis comen")
        assertForms("comer", Tense.Preterite, "comí comiste comió comimos comisteis comieron")
        assertForms("comer", Tense.PresentSubjunctive, "coma comas coma comamos comáis coman")
        assertForms("comer", Tense.ImperfectSubjunctive, "comiera comieras comiera comiéramos comierais comieran")
        assertForms("vivir", Tense.Present, "vivo vives vive vivimos vivís viven")
        assertForms("vivir", Tense.Imperfect, "vivía vivías vivía vivíamos vivíais vivían")
        assertReading("vivid", "vivir", Tense.Imperative, Person.P2)
        assertReading("viviendo", "vivir", Tense.Gerund, null)
    }

    @Test
    fun spellingChanges() {
        assertReading("busqué", "buscar", Tense.Preterite, Person.S1)
        assertReading("busque", "buscar", Tense.PresentSubjunctive, Person.S3)
        assertReading("pagué", "pagar", Tense.Preterite, Person.S1)
        assertReading("empecé", "empezar", Tense.Preterite, Person.S1)
        assertReading("empiece", "empezar", Tense.PresentSubjunctive, Person.S1)
        assertReading("cojo", "coger", Tense.Present, Person.S1)
        assertReading("elijamos", "elegir", Tense.PresentSubjunctive, Person.P1)
        assertReading("averigüé", "averiguar", Tense.Preterite, Person.S1)
        assertReading("venzo", "vencer", Tense.Present, Person.S1)
    }

    @Test
    fun stemChanges() {
        assertForms("pensar", Tense.Present, "pienso piensas piensa pensamos pensáis piensan")
        assertForms("poder", Tense.Present, "puedo puedes puede podemos podéis pueden")
        assertForms("dormir", Tense.Preterite, "dormí dormiste durmió dormimos dormisteis durmieron")
        assertReading("durmiendo", "dormir", Tense.Gerund, null)
        assertReading("durmamos", "dormir", Tense.PresentSubjunctive, Person.P1)
        assertForms("pedir", Tense.Present, "pido pides pide pedimos pedís piden")
        assertReading("pidió", "pedir", Tense.Preterite, Person.S3)
        assertReading("sintieron", "sentir", Tense.Preterite, Person.P3)
        assertForms("seguir", Tense.Present, "sigo sigues sigue seguimos seguís siguen")
        assertReading("siguió", "seguir", Tense.Preterite, Person.S3)
        assertReading("juego", "jugar", Tense.Present, Person.S1)
        assertReading("juguemos", "jugar", Tense.PresentSubjunctive, Person.P1)
        assertReading("envío", "enviar", Tense.Present, Person.S1)
        assertReading("continúa", "continuar", Tense.Present, Person.S3)
        assertReading("reúnen", "reunir", Tense.Present, Person.P3)
    }

    @Test
    fun zcUirAndVowelStems() {
        assertReading("conozco", "conocer", Tense.Present, Person.S1)
        assertReading("conozcamos", "conocer", Tense.PresentSubjunctive, Person.P1)
        assertReading("traduje", "traducir", Tense.Preterite, Person.S1)
        assertReading("condujeron", "conducir", Tense.Preterite, Person.P3)
        assertForms("construir", Tense.Present, "construyo construyes construye construimos construís construyen")
        assertReading("construyó", "construir", Tense.Preterite, Person.S3)
        assertReading("construyendo", "construir", Tense.Gerund, null)
        assertReading("leyó", "leer", Tense.Preterite, Person.S3)
        assertReading("leíste", "leer", Tense.Preterite, Person.S2)
        assertReading("leído", "leer", Tense.Participle, null)
        assertReading("leyendo", "leer", Tense.Gerund, null)
        assertReading("creyera", "creer", Tense.ImperfectSubjunctive, Person.S1)
    }

    @Test
    fun irregularVerbs() {
        assertForms("ser", Tense.Present, "soy eres es somos sois son")
        assertForms("ser", Tense.Imperfect, "era eras era éramos erais eran")
        assertForms("estar", Tense.Preterite, "estuve estuviste estuvo estuvimos estuvisteis estuvieron")
        assertForms("ir", Tense.Present, "voy vas va vamos vais van")
        assertForms("ir", Tense.PresentSubjunctive, "vaya vayas vaya vayamos vayáis vayan")
        assertForms("haber", Tense.Present, "he has ha hemos habéis han")
        assertReading("hay", "haber", Tense.Present, Person.S3)
        assertForms("tener", Tense.Present, "tengo tienes tiene tenemos tenéis tienen")
        assertForms("tener", Tense.PresentSubjunctive, "tenga tengas tenga tengamos tengáis tengan")
        assertForms("tener", Tense.Future, "tendré tendrás tendrá tendremos tendréis tendrán")
        assertForms("tener", Tense.ImperfectSubjunctive, "tuviera tuvieras tuviera tuviéramos tuvierais tuvieran")
        assertForms("hacer", Tense.Preterite, "hice hiciste hizo hicimos hicisteis hicieron")
        assertReading("haz", "hacer", Tense.Imperative, Person.S2)
        assertReading("hecho", "hacer", Tense.Participle, null)
        assertForms("decir", Tense.Preterite, "dije dijiste dijo dijimos dijisteis dijeron")
        assertReading("dijera", "decir", Tense.ImperfectSubjunctive, Person.S3)
        assertReading("diciendo", "decir", Tense.Gerund, null)
        assertReading("dicho", "decir", Tense.Participle, null)
        assertForms("ver", Tense.Present, "veo ves ve vemos veis ven")
        assertReading("visto", "ver", Tense.Participle, null)
        assertForms("dar", Tense.Preterite, "di diste dio dimos disteis dieron")
        assertReading("dé", "dar", Tense.PresentSubjunctive, Person.S1)
        assertForms("saber", Tense.PresentSubjunctive, "sepa sepas sepa sepamos sepáis sepan")
        assertReading("sé", "saber", Tense.Present, Person.S1)
        assertReading("quise", "querer", Tense.Preterite, Person.S1)
        assertReading("querría", "querer", Tense.Conditional, Person.S1)
        assertReading("vine", "venir", Tense.Preterite, Person.S1)
        assertReading("vendremos", "venir", Tense.Future, Person.P1)
        assertReading("puse", "poner", Tense.Preterite, Person.S1)
        assertReading("puesto", "poner", Tense.Participle, null)
        assertReading("salgo", "salir", Tense.Present, Person.S1)
        assertReading("saldría", "salir", Tense.Conditional, Person.S3)
        assertReading("traje", "traer", Tense.Preterite, Person.S1)
        assertReading("oyó", "oír", Tense.Preterite, Person.S3)
        assertReading("oigo", "oír", Tense.Present, Person.S1)
        assertReading("anduve", "andar", Tense.Preterite, Person.S1)
        assertReading("rió", "reír", Tense.Preterite, Person.S3)
        assertReading("vuelto", "volver", Tense.Participle, null)
        assertReading("escrito", "escribir", Tense.Participle, null)
        assertReading("abierto", "abrir", Tense.Participle, null)
        assertReading("muerto", "morir", Tense.Participle, null)
    }

    @Test
    fun prefixedIrregularsFollowTheirBase() {
        assertReading("obtengo", "obtener", Tense.Present, Person.S1)
        assertReading("obtuvo", "obtener", Tense.Preterite, Person.S3)
        assertReading("obtén", "obtener", Tense.Imperative, Person.S2)
        assertReading("propuse", "proponer", Tense.Preterite, Person.S1)
        assertReading("supuesto", "suponer", Tense.Participle, null)
        assertReading("deshice", "deshacer", Tense.Preterite, Person.S1)
        assertReading("previniste", "prevenir", Tense.Preterite, Person.S2)
    }

    @Test
    fun ambiguityIsKept() {
        val fui = morphology.verbs("fui", "es").map { it.lemma }.toSet()
        assertEquals(setOf("ir", "ser"), fui)
        val cante = readings("cante")
        assertTrue(Triple("cantar", Tense.PresentSubjunctive, Person.S1) in cante)
        assertTrue(Triple("cantar", Tense.PresentSubjunctive, Person.S3) in cante)
        assertTrue(readings("canto").all { it.second == Tense.Present && it.third == Person.S1 })
        assertTrue(readings("cantó").all { it.second == Tense.Preterite && it.third == Person.S3 })
        // hablamos is both present and preterite.
        val hablamos = readings("hablamos").map { it.second }.toSet()
        assertEquals(setOf(Tense.Present, Tense.Preterite), hablamos)
    }

    @Test
    fun enclitics() {
        val dimelo = morphology.verbs("dímelo", "es")
        assertTrue(dimelo.any { it.lemma == "decir" && it.tense == Tense.Imperative && it.person == Person.S2 && it.enclitic })
        assertTrue(dimelo.none { it.tense == Tense.Preterite })
        assertTrue(morphology.verbs("hacerlo", "es").any { it.lemma == "hacer" && it.tense == Tense.Infinitive })
        assertTrue(morphology.verbs("dármelo", "es").any { it.lemma == "dar" && it.tense == Tense.Infinitive })
        assertTrue(morphology.verbs("diciéndome", "es").any { it.lemma == "decir" && it.tense == Tense.Gerund })
        assertTrue(morphology.verbs("dígamelo", "es").any { it.lemma == "decir" && it.tense == Tense.Imperative && it.person == Person.S3 })
        assertTrue(morphology.verbs("hagámoslo", "es").any { it.lemma == "hacer" && it.tense == Tense.Imperative && it.person == Person.P1 })
        assertTrue(morphology.verbs("vámonos", "es").any { it.lemma == "ir" && it.tense == Tense.Imperative && it.person == Person.P1 })
        assertTrue(morphology.verbs("sentaos", "es").any { it.lemma == "sentar" && it.tense == Tense.Imperative && it.person == Person.P2 })
        assertTrue(morphology.verbs("cómetelo", "es").any { it.lemma == "comer" && it.tense == Tense.Imperative && it.person == Person.S2 })
        // A host that can't carry pronouns is not read as one.
        assertTrue(morphology.verbs("pelo", "es").isEmpty())
    }

    @Test
    fun guessesRegularVerbsNotInTheLexicon() {
        val guessed = morphology.verbs("programaron", "es")
        assertTrue(guessed.any { it.lemma == "programar" && it.tense == Tense.Preterite && it.person == Person.P3 && it.guessed })
        assertTrue(morphology.verbs("casa", "es").none { it.guessed })
    }

    @Test
    fun adjectivesAndNounsFoldToALemma() {
        assertTrue(morphology.analyse("buenas", "es").any { it.lemma == "bueno" && it.pos == PartOfSpeech.Adjective })
        assertTrue(morphology.analyse("felices", "es").any { it.lemma == "feliz" })
        assertTrue(morphology.analyse("inglesas", "es").any { it.lemma == "inglés" })
        assertEquals("ciudad", Nominal.singular("ciudades", "es"))
        assertEquals("canción", Nominal.singular("canciones", "es"))
        assertEquals("luz", Nominal.singular("luces", "es"))
        assertEquals("casa", Nominal.singular("casas", "es"))
        assertEquals("examen", Nominal.singular("exámenes", "es"))
        assertEquals(null, Nominal.singular("lunes", "es"))
        assertEquals(null, Nominal.singular("país", "es"))
        assertFalse(morphology.analyse("mesas", "es").any { it.pos == PartOfSpeech.Verb })
    }

    @Test
    fun unknownLanguageKnowsNothing() {
        assertTrue(morphology.analyse("walked", "en").isEmpty())
        assertFalse(morphology.supports("en"))
    }
}

/** Building the tables takes a moment; tests share one instance. */
internal object SharedMorphology {
    val instance by lazy { Morphology() }
}
