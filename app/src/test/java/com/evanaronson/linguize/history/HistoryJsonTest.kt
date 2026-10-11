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
    fun attemptsReadBackOrAsNone() {
        assertEquals(session.attempts, decodeAttempts(encodeAttempts(session.attempts)))
        assertEquals(emptyList<Attempt>(), decodeAttempts("{broken"))
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun names(descriptor: SerialDescriptor) = (0 until descriptor.elementsCount).map(descriptor::getElementName)
}
