package com.evanaronson.linguize.llm.memory

import com.evanaronson.linguize.core.memory.Fact
import com.evanaronson.linguize.core.memory.FactCategory
import com.evanaronson.linguize.core.memory.FactUpdate
import com.evanaronson.linguize.core.memory.MemoryMessage
import com.evanaronson.linguize.core.memory.NewFact
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MemoryPromptTest {
    private val prompt = MemoryPrompt.load { File("src/main/assets/$it").readText() }
    private val facts = listOf(
        Fact("uuid-a", "Lives in Gràcia, Barcelona", FactCategory.About, listOf("chat:1"), 1),
        Fact("uuid-b", "Prefers casual Spanish", FactCategory.Preferences, emptyList(), 2, byWriter = true),
    )

    @Test fun theRequestCarriesFactsByReferenceAndWhoSaidWhat() {
        val request = prompt.request(
            facts,
            listOf(MemoryMessage(false, "Hola! On vius?"), MemoryMessage(true, "Visc a Sants ara")),
            "English",
        )
        assertEquals("memory", request.name)
        assertTrue(request.system.contains("Only the writer's own messages are evidence"))
        val user = Json.parseToJsonElement(request.user).jsonObject
        assertEquals("English", user["writeIn"]!!.jsonPrimitive.content)
        val sent = user["facts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("f1", "f2"), sent.map { it["id"]!!.jsonPrimitive.content })
        assertFalse(request.user.contains("uuid-a"))
        assertTrue(sent[1]["yours"]!!.jsonPrimitive.boolean)
        assertEquals(38, user["room"]!!.jsonPrimitive.int)
        val conversation = user["conversation"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("partner", "writer"), conversation.map { it["from"]!!.jsonPrimitive.content })
    }

    @Test fun longConversationsKeepTheirEnd() {
        val messages = (1..40).map { MemoryMessage(it % 2 == 0, "m$it " + "x".repeat(1000)) }
        val kept = MemoryPrompt.recent(messages)
        assertTrue(kept.sumOf { it.text.length } <= MemoryPrompt.MAX_CONVERSATION_CHARS)
        assertTrue(kept.last().text.startsWith("m40 "))
        assertTrue(kept.size < messages.size)
    }

    @Test fun readsChangesAndMapsReferencesBackToIds() {
        val changes = prompt.read(
            """{"add":[{"text":"Preparing for a job interview","category":"goals"}],
               "update":[{"id":"f1","text":"Lives in Sants, Barcelona","category":"about"}],
               "remove":["f2"]}""",
            facts,
        )
        assertEquals(listOf(NewFact("Preparing for a job interview", FactCategory.Goals)), changes.add)
        assertEquals(listOf(FactUpdate("uuid-a", "Lives in Sants, Barcelona", FactCategory.About)), changes.update)
        assertEquals(listOf("uuid-b"), changes.remove)
    }

    @Test fun skipsItemsItCantUse() {
        val changes = prompt.read(
            """{"add":[{"text":"","category":"goals"},{"text":"Has a cat","category":"health"},{"text":"Has a dog","category":"about"},3],
               "update":[{"id":"f9","text":"x","category":"about"},{"id":"f1","text":"Lives in Sants","category":"nope"}],
               "remove":["f7", 4, "f1"], "extra": true}""",
            facts,
        )
        assertEquals(listOf(NewFact("Has a dog", FactCategory.About)), changes.add)
        assertEquals(listOf(FactUpdate("uuid-a", "Lives in Sants", null)), changes.update)
        assertEquals(listOf("uuid-a"), changes.remove)
    }

    @Test fun anEmptyAnswerIsNoChange() {
        assertTrue(prompt.read("""{"add":[],"update":[],"remove":[]}""", facts).isEmpty)
    }

    @Test(expected = IllegalArgumentException::class) fun blankIsUnusable() {
        prompt.read(" ", facts)
    }

    @Test(expected = IllegalArgumentException::class) fun notAnObjectIsUnusable() {
        prompt.read("[1,2]", facts)
    }

    @Test fun theSchemaNamesEveryCategory() {
        val enum = prompt.schema["properties"]!!.jsonObject["add"]!!.jsonObject["items"]!!.jsonObject["properties"]!!
            .jsonObject["category"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(FactCategory.entries.map { it.token }, enum)
    }
}
