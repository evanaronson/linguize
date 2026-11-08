package com.evanaronson.linguize.core.morphology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalanMorphologyTest {
    private val morphology = SharedMorphology.instance

    private fun readings(word: String) = morphology.verbs(word, "ca").map { Triple(it.lemma, it.tense, it.person) }.toSet()

    private fun assertReading(word: String, lemma: String, tense: Tense, person: Person?) {
        val found = readings(word)
        assertTrue("$word should be $lemma $tense $person, was $found", Triple(lemma, tense, person) in found)
    }

    private fun assertForms(lemma: String, tense: Tense, forms: String) {
        forms.split(' ').forEachIndexed { index, form -> assertReading(form, lemma, tense, Person.entries[index]) }
    }

    @Test
    fun regularArParadigm() {
        assertForms("cantar", Tense.Present, "canto cantes canta cantem canteu canten")
        assertForms("cantar", Tense.Imperfect, "cantava cantaves cantava cantàvem cantàveu cantaven")
        assertForms("cantar", Tense.Future, "cantaré cantaràs cantarà cantarem cantareu cantaran")
        assertForms("cantar", Tense.Conditional, "cantaria cantaries cantaria cantaríem cantaríeu cantarien")
        assertForms("cantar", Tense.PresentSubjunctive, "canti cantis canti cantem canteu cantin")
        assertForms("cantar", Tense.ImperfectSubjunctive, "cantés cantessis cantés cantéssim cantéssiu cantessin")
        assertForms("cantar", Tense.Preterite, "cantí cantares cantà cantàrem cantàreu cantaren")
        assertReading("cantant", "cantar", Tense.Gerund, null)
        assertReading("cantades", "cantar", Tense.Participle, null)
    }

    @Test
    fun regularReErAndIrParadigms() {
        assertForms("perdre", Tense.Present, "perdo perds perd perdem perdeu perden")
        assertForms("perdre", Tense.Future, "perdré perdràs perdrà perdrem perdreu perdran")
        assertReading("perdut", "perdre", Tense.Participle, null)
        assertReading("perduda", "perdre", Tense.Participle, null)
        assertForms("témer", Tense.Future, "temeré temeràs temerà temerem temereu temeran")
        assertForms("dormir", Tense.Present, "dormo dorms dorm dormim dormiu dormen")
        assertForms("dormir", Tense.ImperfectSubjunctive, "dormís dormissis dormís dormíssim dormíssiu dormissin")
        assertForms("servir", Tense.Present, "serveixo serveixes serveix servim serviu serveixen")
        assertForms("servir", Tense.PresentSubjunctive, "serveixi serveixis serveixi servim serviu serveixin")
        assertForms("llegir", Tense.Present, "llegeixo llegeixes llegeix llegim llegiu llegeixen")
        assertForms("sortir", Tense.Present, "surto surts surt sortim sortiu surten")
        assertReading("rep", "rebre", Tense.Present, Person.S3)
        assertReading("reps", "rebre", Tense.Present, Person.S2)
        assertReading("cuses", "cosir", Tense.Present, Person.S2)
    }

    @Test
    fun spellingChangesAndDiaeresis() {
        assertReading("toquem", "tocar", Tense.Present, Person.P1)
        assertReading("pagui", "pagar", Tense.PresentSubjunctive, Person.S1)
        assertReading("comenci", "començar", Tense.PresentSubjunctive, Person.S3)
        assertReading("menges", "menjar", Tense.Present, Person.S2)
        assertReading("estudiï", "estudiar", Tense.PresentSubjunctive, Person.S1)
        assertReading("conduïm", "conduir", Tense.Present, Person.P1)
        assertReading("condueixo", "conduir", Tense.Present, Person.S1)
        assertReading("traduït", "traduir", Tense.Participle, null)
    }

    @Test
    fun irregularVerbs() {
        assertForms("ser", Tense.Present, "sóc ets és som sou són")
        assertForms("ser", Tense.ImperfectSubjunctive, "fos fossis fos fóssim fóssiu fossin")
        assertForms("estar", Tense.Present, "estic estàs està estem esteu estan")
        assertReading("estigui", "estar", Tense.PresentSubjunctive, Person.S1)
        assertReading("estigués", "estar", Tense.ImperfectSubjunctive, Person.S3)
        assertForms("anar", Tense.Present, "vaig vas va anem aneu van")
        assertReading("aniré", "anar", Tense.Future, Person.S1)
        assertReading("vagi", "anar", Tense.PresentSubjunctive, Person.S3)
        assertForms("haver", Tense.Present, "he has ha hem heu han")
        assertReading("hagués", "haver", Tense.ImperfectSubjunctive, Person.S1)
        assertForms("tenir", Tense.Present, "tinc tens té tenim teniu tenen")
        assertReading("tindré", "tenir", Tense.Future, Person.S1)
        assertReading("tingui", "tenir", Tense.PresentSubjunctive, Person.S1)
        assertReading("tingut", "tenir", Tense.Participle, null)
        assertForms("fer", Tense.Present, "faig fas fa fem feu fan")
        assertReading("feia", "fer", Tense.Imperfect, Person.S3)
        assertReading("faci", "fer", Tense.PresentSubjunctive, Person.S1)
        assertReading("fet", "fer", Tense.Participle, null)
        assertForms("poder", Tense.Present, "puc pots pot podem podeu poden")
        assertReading("pugui", "poder", Tense.PresentSubjunctive, Person.S1)
        assertReading("pogués", "poder", Tense.ImperfectSubjunctive, Person.S3)
        assertForms("dir", Tense.Present, "dic dius diu diem dieu diuen")
        assertReading("digues", "dir", Tense.Imperative, Person.S2)
        assertReading("digueu", "dir", Tense.Imperative, Person.P2)
        assertForms("veure", Tense.Present, "veig veus veu veiem veieu veuen")
        assertReading("vist", "veure", Tense.Participle, null)
        assertForms("saber", Tense.PresentSubjunctive, "sàpiga sàpigues sàpiga sapiguem sapigueu sàpiguen")
        assertForms("voler", Tense.Present, "vull vols vol volem voleu volen")
        assertReading("vulgui", "voler", Tense.PresentSubjunctive, Person.S1)
        assertReading("volgués", "voler", Tense.ImperfectSubjunctive, Person.S1)
        assertForms("venir", Tense.Present, "vinc vens ve venim veniu venen")
        assertReading("vine", "venir", Tense.Imperative, Person.S2)
        assertForms("viure", Tense.Present, "visc vius viu vivim viviu viuen")
        assertReading("visqui", "viure", Tense.PresentSubjunctive, Person.S1)
        assertReading("viscut", "viure", Tense.Participle, null)
        assertForms("escriure", Tense.Present, "escric escrius escriu escrivim escriviu escriuen")
        assertReading("escrit", "escriure", Tense.Participle, null)
        assertReading("escrita", "escriure", Tense.Participle, null)
        assertForms("prendre", Tense.Present, "prenc prens pren prenem preneu prenen")
        assertReading("prengui", "prendre", Tense.PresentSubjunctive, Person.S1)
        assertReading("pres", "prendre", Tense.Participle, null)
        assertReading("entès", "entendre", Tense.Participle, null)
        assertReading("entesa", "entendre", Tense.Participle, null)
        assertReading("conec", "conèixer", Tense.Present, Person.S1)
        assertReading("coneixia", "conèixer", Tense.Imperfect, Person.S1)
        assertReading("conegut", "conèixer", Tense.Participle, null)
        assertReading("donar", "donar", Tense.Infinitive, null)
        assertReading("dóna", "donar", Tense.Present, Person.S3)
        assertReading("posem", "posar", Tense.Present, Person.P1)
        assertReading("obtinc", "obtenir", Tense.Present, Person.S1)
        assertReading("aprenc", "aprendre", Tense.Present, Person.S1)
        assertReading("obert", "obrir", Tense.Participle, null)
        assertReading("cal", "caldre", Tense.Present, Person.S3)
        assertReading("soc", "ser", Tense.Present, Person.S1)
        assertReading("haig", "haver", Tense.Present, Person.S1)
        assertReading("dec", "deure", Tense.Present, Person.S1)
        assertForms("obrir", Tense.Present, "obro obres obre obrim obriu obren")
        assertReading("bull", "bullir", Tense.Present, Person.S3)
        assertReading("segueixes", "seguir", Tense.Present, Person.S2)
    }

    @Test
    fun periphrasticAuxiliaries() {
        assertReading("vam", "anar", Tense.PeriphrasticPast, Person.P1)
        assertReading("vàreu", "anar", Tense.PeriphrasticPast, Person.P2)
        assertEquals(Person.S1, Catalan.PERIPHRASTIC["vaig"])
        assertEquals(Person.P3, Catalan.PERIPHRASTIC["varen"])
    }

    @Test
    fun cliticsWithHyphensAndApostrophes() {
        val donamho = morphology.verbs("dóna-m'ho", "ca")
        assertTrue(donamho.any { it.lemma == "donar" && it.tense == Tense.Imperative && it.person == Person.S2 && it.enclitic })
        assertTrue(donamho.none { it.tense == Tense.Present })
        assertTrue(morphology.verbs("fer-ho", "ca").any { it.lemma == "fer" && it.tense == Tense.Infinitive })
        assertTrue(morphology.verbs("dir-te", "ca").any { it.lemma == "dir" && it.tense == Tense.Infinitive })
        assertTrue(morphology.verbs("posa't", "ca").any { it.lemma == "posar" && it.tense == Tense.Imperative })
        assertTrue(morphology.verbs("fent-ho", "ca").any { it.lemma == "fer" && it.tense == Tense.Gerund })
        assertTrue(morphology.verbs("anem-hi", "ca").any { it.lemma == "anar" && it.tense == Tense.Imperative && it.person == Person.P1 })
        // Elided pronouns before the verb leave it as it is.
        assertTrue(morphology.verbs("l'he", "ca").any { it.lemma == "haver" && it.tense == Tense.Present && it.person == Person.S1 })
        assertTrue(morphology.verbs("m'agrada", "ca").any { it.lemma == "agradar" && it.tense == Tense.Present && it.person == Person.S3 })
        assertTrue(morphology.verbs("t'estimo", "ca").any { it.lemma == "estimar" && it.person == Person.S1 })
    }

    @Test
    fun ambiguityIsKept() {
        val canti = readings("canti")
        assertTrue(Triple("cantar", Tense.PresentSubjunctive, Person.S1) in canti)
        assertTrue(Triple("cantar", Tense.PresentSubjunctive, Person.S3) in canti)
        val estat = morphology.verbs("estat", "ca").map { it.lemma }.toSet()
        assertEquals(setOf("estar", "ser"), estat)
        val cantem = readings("cantem").map { it.second }.toSet()
        assertTrue(Tense.Present in cantem && Tense.PresentSubjunctive in cantem)
    }

    @Test
    fun adjectivesAndNounsFold() {
        assertTrue(morphology.analyse("contentes", "ca").any { it.lemma == "content" && it.pos == PartOfSpeech.Adjective })
        assertTrue(morphology.analyse("grogues", "ca").any { it.lemma == "groc" })
        assertTrue(morphology.analyse("cansades", "ca").any { it.lemma == "cansat" })
        assertTrue(morphology.analyse("bones", "ca").any { it.lemma == "bo" })
        assertTrue(morphology.analyse("catalans", "ca").any { it.lemma == "català" })
        assertEquals("casa", Nominal.singular("cases", "ca"))
        assertEquals("plaça", Nominal.singular("places", "ca"))
        assertEquals("amiga", Nominal.singular("amigues", "ca"))
        assertEquals("pare", Nominal.singular("pares", "ca"))
        assertEquals("cançó", Nominal.singular("cançons", "ca"))
        assertEquals("gat", Nominal.singular("gats", "ca"))
        assertEquals(null, Nominal.singular("dilluns", "ca"))
    }
}
