package com.evanaronson.linguize.history

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The records' serialized shape: the columns follow it, and the export, written from the rows, has it. */
class HistoryJsonTest {
    private val session = HistorySchemaTest().session
    private val suggestion = HistorySchemaTest().suggestion

    @Test
    fun everyFieldOfASessionHasAColumn() {
        val fields = names(SessionRecord.serializer().descriptor)
        assertEquals(fields, HistorySchema.SESSION_COLUMNS)
        assertEquals(fields, session.values().keys.toList())
        fields.forEach { assertTrue(it, HistorySchema.CREATE[0].contains("\"$it\" ")) }
    }

    @Test
    fun everyFieldOfASuggestionHasAColumn() {
        val fields = names(SuggestionRecord.serializer().descriptor)
        assertEquals(fields, HistorySchema.SUGGESTION_COLUMNS)
        assertEquals(fields, suggestion.values().keys.toList())
        fields.forEach { assertTrue(it, HistorySchema.CREATE[1].contains("\"$it\" ")) }
    }

    /** The export line of [detail]'s rows, as the store writes it. */
    private fun exported(detail: SessionDetail) = exportLine(
        rowJson(MapRow(detail.session.values()), HistorySchema.SESSION_COLUMNS),
        detail.suggestions.map { rowJson(MapRow(it.values()), HistorySchema.SUGGESTION_COLUMNS) },
    )

    @Test
    fun anExportLineIsOneLineThatReadsBack() {
        val detail = SessionDetail(session, listOf(suggestion))
        val line = exported(detail)
        assertFalse(line.contains('\n'))
        assertTrue(line.contains("\"schema\":${SessionRecord.SCHEMA}"))
        assertEquals(detail, historyJson.decodeFromString(SessionDetail.serializer(), line))
    }

    @Test
    fun theExportUsesTheSameTokensAsTheDatabase() {
        val line = exported(SessionDetail(session, listOf(suggestion)))
        assertTrue(line, line.contains("\"origin\":\"button\""))
        assertTrue(line, line.contains("\"outcome\":\"applied\""))
        assertTrue(line, line.contains("\"status\":\"ok\""))
        assertTrue(line, line.contains("\"kind\":\"fix\""))
        assertTrue(line, line.contains("\"decision\":\"accepted\""))
        assertTrue(line, line.contains("\"reusedFrom\":\"s0\""))
        assertTrue(line, line.contains("\"raw\":"))
        assertTrue(line, line.contains("\"nativeLanguage\":\"English\""))
    }

    @Test
    fun languageBackfillPreservesRawAndUnknownEvidenceWithoutGuessing() {
        val raw = """{"status":"ok","language":"Catalan","meaning":"How are you?","assumptions":[],
            "has_errors":true,"corrected":"Com estàs?",
            "fixes":[{"from":"estas","to":"estàs","why":"Accent","confidence":3,"impact":1,
                "assumption_indices":null}],
            "more_natural":false,"natural":"","natural_changes":[]}"""
        val stored = """[{"at":1,"raw":${kotlinx.serialization.json.JsonPrimitive(raw)},"future":42},
            {"at":2,"requestedLanguage":"es","raw":${kotlinx.serialization.json.JsonPrimitive(raw)}},
            {"at":3,"raw":${kotlinx.serialization.json.JsonPrimitive(raw)},"failure":"bad_response"},
            {"at":4,"raw":"broken"}]"""
        val upgraded = upgradeLanguageAttempts(stored)
        val attempts = decodeAttempts(upgraded)
        assertEquals(listOf("ca", "es", null, null), attempts.map { it.targetLanguage })
        assertEquals(listOf("ca", "ca", null, null), attempts.map { it.detectedLanguage })
        assertEquals(raw, attempts.first().raw)
        assertTrue(upgraded.contains("\"future\":42"))
        assertEquals(upgraded, upgradeLanguageAttempts(upgraded))
        assertEquals("broken", upgradeLanguageAttempts("broken"))
        assertEquals("en", detectedLanguage("""{"status":"wrong_language","language":"English"}""", null))
        assertEquals(null, detectedLanguage("""{"status":"ok","language":"made up"}""", null))
    }

    @Test
    fun languageDetectionRequiresKnownStatusAndStringLanguageEvidence() {
        val invalid = listOf(
            "broken", "[]", "null",
            """{"language":"Catalan"}""",
            """{"status":"future","language":"Catalan"}""",
            """{"status":1,"language":"Catalan"}""",
            """{"status":true,"language":"Catalan"}""",
            """{"status":null,"language":"Catalan"}""",
            """{"status":{},"language":"Catalan"}""",
            """{"status":"unclear","language":"Catalan"}""",
            """{"status":"ok"}""",
            """{"status":"ok","language":1}""",
            """{"status":"ok","language":true}""",
            """{"status":"ok","language":null}""",
            """{"status":"ok","language":{}}""",
        )
        for (raw in invalid) {
            assertEquals(raw, null, detectedLanguage(raw, null))
            val stored = """[{"at":1,"raw":${kotlinx.serialization.json.JsonPrimitive(raw)},"future":42}]"""
            val upgraded = upgradeLanguageAttempts(stored)
            val attempt = decodeAttempts(upgraded).single()
            assertEquals(raw, attempt.raw)
            assertEquals(raw, null, attempt.targetLanguage)
            assertEquals(raw, null, attempt.detectedLanguage)
            assertTrue(upgraded.contains("\"future\":42"))
        }
    }

    @Test
    fun attemptsReadBackOrAsNone() {
        assertEquals(session.attempts, decodeAttempts(encodeAttempts(session.attempts)))
        assertEquals(emptyList<Attempt>(), decodeAttempts("{broken"))
    }

    @Test
    fun replayInvalidationRoundTripsWithoutChangingAnswerEvidenceAndDefaultsForOldJson() {
        val answer = Attempt(at = 1, raw = "raw answer", requestedLanguage = "ca", model = "model")
        val invalidated = answer.copy(replayInvalidated = true)
        val attempts = listOf(invalidated, Attempt(at = 2, failure = "offline", failureDetail = "detail"))
        assertEquals(attempts, decodeAttempts(encodeAttempts(attempts)))
        assertTrue(decodeAttempts(encodeAttempts(attempts)).first().succeeded)
        assertEquals(answer, decodeAttempts("""[{"at":1,"raw":"raw answer","requestedLanguage":"ca","model":"model","future":42}]""").single())
        assertEquals(listOf(invalidated.raw, null), decodeAttempts(encodeAttempts(attempts)).map { it.raw })
    }

    @Test
    fun errorMessageTokensRoundTripAndOlderAttemptsNeedNoMigration() {
        val attempt = Attempt(at = 1, failure = "bad_response", failureMessage = "empty_reply")
        assertEquals(listOf(attempt), decodeAttempts(encodeAttempts(listOf(attempt))))
        val older = decodeAttempts("""[{"at":1,"failure":"bad_response","failureDetail":"Empty reply"}]""").single()
        assertEquals(null, older.failureMessage)
        assertEquals("Empty reply", older.failureDetail)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun names(descriptor: SerialDescriptor) = (0 until descriptor.elementsCount).map(descriptor::getElementName)
}
