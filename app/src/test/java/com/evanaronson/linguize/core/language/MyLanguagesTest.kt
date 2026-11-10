package com.evanaronson.linguize.core.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lab, language-generic: the writer's ordered list of languages. */
class MyLanguagesTest {
    @Test fun theListStartsFromTheProfilesElseCatalanAndSpanish() {
        assertEquals(listOf("ca", "es"), MyLanguages.seed(listOf("ca", "es")))
        assertEquals(listOf("es", "fr"), MyLanguages.seed(listOf("es", null, "fr", "es", "Klingon")))
        assertEquals(listOf("ca", "es"), MyLanguages.seed(emptyList()))
    }

    @Test fun storedListsReadBackAndJunkReadsAsNothing() {
        assertEquals(listOf("fr", "ca"), MyLanguages.read(MyLanguages.write(listOf("fr", "ca"))))
        assertEquals(listOf("fr"), MyLanguages.read("fr,,xx,FR"))
        assertNull(MyLanguages.read(""))
        assertNull(MyLanguages.read(null))
    }

    @Test fun addRemoveAndMoveKeepAtLeastOneLanguage() {
        val mine = listOf("ca", "es")
        assertEquals(listOf("ca", "es", "fr"), MyLanguages.add(mine, "FR"))
        assertEquals(mine, MyLanguages.add(mine, "es"))
        assertEquals(mine, MyLanguages.add(mine, "zz"))
        assertEquals(listOf("es"), MyLanguages.remove(mine, "ca"))
        assertEquals(listOf("es"), MyLanguages.remove(listOf("es"), "es"))
        assertEquals(listOf("es", "ca"), MyLanguages.move(mine, "es", 0))
        assertEquals(listOf("es", "ca"), MyLanguages.move(mine, "ca", 9))
        assertEquals(mine, MyLanguages.move(mine, "fr", 0))
        val full = LanguageCodes.all.take(MyLanguages.MAX)
        assertEquals(full, MyLanguages.add(full, LanguageCodes.all.last()))
    }
}
