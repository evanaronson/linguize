package com.evanaronson.linguize.core.morphology

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Cross-checks the generated Spanish paradigms against an external frequency list of lemmas
 * and their word forms, when a copy is available on this machine (set LAB_VOCAB_ES, or the
 * shared scratchpad copy). Skipped otherwise: the list is not part of this change.
 */
class VocabularyListCrossCheckTest {
    private val file = (System.getenv("LAB_VOCAB_ES")
        ?: "/tmp/claude-0/-home-user-linguize/edd71820-0d57-593e-8fa0-d54894387c44/scratchpad/vocab-src/lab_vocab_es.csv")
        .let(::File)

    @Test
    fun generatedFormsAgreeWithTheListForKnownVerbs() {
        assumeTrue(file.isFile)
        val morphology = SharedMorphology.instance
        var total = 0
        var agreed = 0
        val misses = mutableListOf<String>()
        file.readLines().drop(1).forEach { line ->
            val columns = line.split(',')
            if (columns.size < 5 || columns[2] != "v") return@forEach
            val lemma = columns[1]
            if (!morphology.knowsVerb(lemma, "es")) return@forEach
            columns[4].split(';').filter { it.isNotBlank() }.forEach { form ->
                total++
                if (morphology.verbs(form, "es").any { it.lemma == lemma }) agreed++ else misses += "$lemma:$form"
            }
        }
        println("Spanish cross-check: $agreed of $total forms; first misses: ${misses.take(80)}")
        assertTrue("Too few forms agree: $agreed of $total", total == 0 || agreed >= total * 0.8)
    }
}
