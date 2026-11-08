package com.evanaronson.linguize.core.morphology

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Reports how many of the most frequent Catalan word forms the morphology reads, against a
 * frequency list when a copy is available on this machine (LAB_VOCAB_CA, or the shared
 * scratchpad copy). Skipped otherwise. Words it can't read are printed to guide the lexicon.
 */
class CatalanFormListCoverageTest {
    private val file = (System.getenv("LAB_VOCAB_CA")
        ?: "/tmp/claude-0/-home-user-linguize/edd71820-0d57-593e-8fa0-d54894387c44/scratchpad/vocab-src/lab_vocab_ca.csv")
        .let(::File)

    @Test
    fun frequentVerbLikeFormsAreRead() {
        assumeTrue(file.isFile)
        val morphology = SharedMorphology.instance
        val forms = file.readLines().drop(1).mapNotNull { it.split(',').getOrNull(1) }.take(2000)
        val read = forms.count { form -> morphology.analyse(form, "ca").any { !it.guessed } }
        val guessedVerbs = forms.filter { form ->
            val all = morphology.analyse(form, "ca")
            all.isNotEmpty() && all.all { it.guessed } && all.any { it.pos == PartOfSpeech.Verb }
        }
        val lemmatizer = MorphologyLemmatizer(morphology)
        val unknown = forms.filter { form -> lemmatizer.lexemes(form, "ca").all { it.pos == com.evanaronson.linguize.core.corpus.Pos.Unknown } }
        println("Catalan coverage: $read of ${forms.size} frequent forms have a known reading")
        println("Unknown to the lemmatizer (${unknown.size}): ${unknown.take(700)}")
        println("Guessed verbs only: ${guessedVerbs.take(120)}")
        assertTrue(read > 0)
    }
}
