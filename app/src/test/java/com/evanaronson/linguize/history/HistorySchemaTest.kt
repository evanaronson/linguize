package com.evanaronson.linguize.history

import com.evanaronson.linguize.codec.Tokens
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySchemaTest {
    internal val session = SessionRecord(
        id = "s1",
        deviceId = "device",
        createdAt = 1_000,
        updatedAt = 2_000,
        startedAt = 1_000,
        closedAt = 2_000,
        origin = Origin.Button,
        hostApp = "org.telegram.messenger",
        requestedLanguage = "ca",
        nativeLanguage = "English",
        text = "Com estas?\nBé.",
        textHash = "abc123",
        finalText = "Com estàs?\nBé.",
        outcome = Outcome.Applied,
        punctuation = "moderate",
        judgments = "both",
        provider = "gemini",
        model = "gemini-x",
        promptHash = "p1",
        appVersion = "0.1.7",
        attempts = listOf(
            Attempt(at = 1_100, failure = "Timeout"),
            Attempt(at = 1_500, settled = listOf(Settled("tu", "informal")), raw = """{"status":"ok"}""", reusedFrom = "s0"),
        ),
        meaning = "How are you?",
        status = Verdict.Status.Ok,
    )

    internal val suggestion = SuggestionRecord(
        id = "g1",
        sessionId = "s1",
        deviceId = "device",
        createdAt = 2_000,
        updatedAt = 2_000,
        attempt = 1,
        kind = EditKind.Fix,
        start = 4,
        end = 9,
        fromText = "estas",
        toText = "estàs",
        why = "Accent",
        decision = Decision.Accepted,
        decidedAt = 2_000,
    )

    @Test
    fun aSessionReadsBackAsItWasWritten() {
        assertEquals(session, sessionRecord(MapRow(session.values())))
        val open = session.copy(
            closedAt = null, outcome = null, finalText = null, hostApp = null, nativeLanguage = null, attempts = emptyList(), status = null,
        )
        assertEquals(open, sessionRecord(MapRow(open.values())))
    }

    @Test
    fun aSuggestionReadsBackAsItWasWritten() {
        assertEquals(suggestion, suggestionRecord(MapRow(suggestion.values())))
        val bare = suggestion.copy(why = null, decision = Decision.Superseded)
        assertEquals(bare, suggestionRecord(MapRow(bare.values())))
    }

    @Test
    fun enumsAreStoredAsLowercaseTokens() {
        assertEquals("button", session.values()["origin"])
        assertEquals("applied", session.values()["outcome"])
        assertEquals("ok", session.values()["status"])
        assertEquals("accepted", suggestion.values()["decision"])
        assertEquals("fix", suggestion.values()["kind"])
        assertEquals("wrong_language", Verdict.Status.tokens.encode(Verdict.Status.WrongLanguage))
        assertEquals("naturalize", Judgments.tokens.encode(Judgments.NaturalizeOnly))
        assertEquals("casual", Punctuation.tokens.encode(Punctuation.Casual))
        assertEquals("openai", Provider.tokens.encode(Provider.OpenAI))
    }

    @Test
    fun everyTokenReadsBack() {
        fun <E : Enum<E>> roundTrips(tokens: Tokens<E>, entries: List<E>) = entries.forEach { assertEquals(it, tokens.decode(tokens.encode(it))) }
        roundTrips(Origin.tokens, Origin.entries)
        roundTrips(Decision.tokens, Decision.entries)
        roundTrips(Outcome.tokens, Outcome.entries)
        roundTrips(EditKind.tokens, EditKind.entries)
        roundTrips(Verdict.Status.tokens, Verdict.Status.entries)
        roundTrips(Punctuation.tokens, Punctuation.entries)
        roundTrips(Judgments.tokens, Judgments.entries)
        roundTrips(Provider.tokens, Provider.entries)
        roundTrips(CheckFailure.Reason.tokens, CheckFailure.Reason.entries)
    }

    @Test
    fun whatUpgradesSpellOutMatchesTheEnums() {
        // History spells out the names older versions kept, since it doesn't depend on llm.
        assertEquals(Provider.tokens.renames(), HistorySchema.VERSION_1_PROVIDERS)
        assertEquals(CheckFailure.Reason.tokens.renames(), LEGACY_FAILURES)
        // A verdict's status is written with the same token in its JSON and in its column.
        for (status in Verdict.Status.entries) {
            assertEquals(status.token, Verdict.Status.serializer().descriptor.getElementName(status.ordinal))
        }
    }

    @Test
    fun version2UpgradesTheRequestedLanguageToItsCode() {
        val statements = HistorySchema.upgrade(2, HistorySchema.SESSION_COLUMNS.toSet())!!
        assertTrue(statements.any { it.contains("SET \"requestedLanguage\" = CASE") && it.contains("WHEN 'Catalan' THEN 'ca'") && it.contains("WHEN 'Spanish' THEN 'es'") })
        // Nothing of version 1's renames: version 2 already stores tokens.
        assertFalse(statements.any { it.contains("SET \"origin\"") || it.contains("ADD COLUMN") })
        assertTrue(statements.any { it.contains("SET \"schema\" = ${SessionRecord.SCHEMA}") })
        assertTrue(HistorySchema.upgrade(1, HistorySchema.SESSION_COLUMNS.toSet())!!.any { it.contains("WHEN 'Catalan' THEN 'ca'") })
    }

    @Test
    fun anUpgradedAttemptKeepsItsFailureAsAToken() {
        val v2 = """[{"at":1,"settled":[],"raw":null,"failure":"BadKey","failureDetail":"no"},{"at":2,"raw":"{}","failure":null}]"""
        val v3 = upgradeAttempts(v2)
        val attempts = historyJson.parseToJsonElement(v3).jsonArray.map { it.jsonObject }
        assertEquals("bad_key", attempts[0]["failure"]!!.jsonPrimitive.content)
        assertEquals(listOf("at", "settled", "raw", "failure", "failureDetail"), attempts[0].keys.toList())
        assertEquals(v3, upgradeAttempts(v3))
        // A token, or a reason this build doesn't know, is left as it is.
        val newer = """[{"at":1,"failure":"quota"}]"""
        assertEquals(newer, upgradeAttempts(newer))
    }

    @Test
    fun languagesAreKeptByCodeAndOldNamesStillRead() {
        assertEquals(listOf("ca", "es"), Language.all.map { it.code })
        assertEquals(Language.Catalan, Language.read("ca"))
        assertEquals(Language.Spanish, Language.read("Spanish"))
        assertNull(Language.read("Klingon"))
        assertNull(Language.read(null))
        assertNull(Language.forCode("Catalan"))
    }

    @Test
    fun unknownTokensReadLeniently() {
        assertNull(Decision.tokens.decode("postponed"))
        assertNull(Decision.tokens.decode(null))
        // Settings written by earlier builds hold constant names.
        assertEquals(Judgments.FixOnly, Judgments.tokens.decodeOrName("FixOnly"))
        assertNull(Judgments.tokens.decodeOrName("Everything"))

        val newer = session.values() + mapOf("origin" to "widget", "outcome" to "snoozed", "status" to "maybe", "attempts" to "not json")
        val read = sessionRecord(MapRow(newer))
        // Names are read as tokens only: the upgrade rewrote them.
        assertNull(sessionRecord(MapRow(session.values() + ("origin" to "Button"))).origin)
        assertNull(read.origin)
        assertNull(read.outcome)
        assertNull(read.status)
        assertTrue(read.attempts.isEmpty())

        assertNull(suggestionRecord(MapRow(suggestion.values() + ("kind" to "style"))))
        assertNull(suggestionRecord(MapRow(suggestion.values() + ("decision" to "postponed"))))

        val summary = sessionSummary(MapRow(mapOf<String, Any?>(
            "id" to "s1", "startedAt" to 1L, "origin" to "widget", "hostApp" to null, "text" to "t",
            "outcome" to "snoozed", "status" to "unclear", "fixes" to 1L, "rewordings" to 0L, "taken" to 0L,
        )))
        assertNull(summary.origin)
        assertNull(summary.outcome)
        assertEquals(Verdict.Status.Unclear, summary.status)
    }

    @Test
    fun recentReadsOnlyThePrefixOfEachText() {
        val sql = HistorySchema.recent(50)
        assertTrue(sql.contains("substr(\"text\", 1, ${SessionSummary.TEXT_PREFIX})"))
        assertFalse(sql.contains("SELECT * FROM"))
        assertTrue(sql.contains("'superseded'") && sql.contains("'accepted'") && sql.contains("'fix'"))
    }

    @Test
    fun theReuseKeyBindsInOrder() {
        val key = session.reuseKey
        assertEquals(
            listOf("abc123", "ca", "English", "moderate", "both", "gemini", "gemini-x", "p1", "5"),
            HistorySchema.reusableArgs(key, 5),
        )
        assertEquals(8, HistorySchema.reusableArgs(key.copy(requestedLanguage = null), 5).size)
        assertEquals(7, HistorySchema.reusableArgs(key.copy(requestedLanguage = null, settings = key.settings.copy(nativeLanguage = null)), 5).size)
    }

    @Test
    fun aClearOrDeleteForgetsSessionsStillOpen() {
        val forgotten = Forgotten()
        assertTrue(forgotten.saving("s1"))
        assertTrue(forgotten.saving("s2"))
        forgotten.deleted("s1")
        assertFalse(forgotten.saving("s1"))
        assertFalse(forgotten.closing("s1"))
        assertTrue(forgotten.saving("s2"))

        // Whatever the clock says: what was open at the clear is gone, what opens after isn't.
        forgotten.cleared()
        assertFalse(forgotten.saving("s2"))
        assertFalse(forgotten.closing("s2"))
        assertTrue(forgotten.saving("s3"))
        assertTrue(forgotten.closing("s3"))
    }

    @Test
    fun theSessionsOpenedHereAreTheOnesStillOpen() {
        val forgotten = Forgotten()
        forgotten.saving("a")
        forgotten.saving("b")
        forgotten.saving("c")
        forgotten.closing("b")
        forgotten.deleted("c")
        assertEquals(listOf("a"), forgotten.openHere())
    }

    @Test
    fun abandonedMeansOpenAndNotOpenedHere() {
        val none = HistorySchema.markAbandoned(0)
        assertTrue(none.contains("\"closedAt\" IS NULL"))
        assertFalse(none.contains("NOT IN"))
        assertFalse(none.contains("startedAt"))
        assertTrue(HistorySchema.markAbandoned(2).contains("\"id\" NOT IN (?, ?)"))
    }

    @Test
    fun theCountIsWhatRecentShows() {
        assertTrue(HistorySchema.COUNT.contains("\"closedAt\" IS NOT NULL"))
        assertTrue(HistorySchema.SINCE.contains("\"closedAt\" IS NOT NULL"))
    }

    @Test
    fun onlyASessionWhoseLastAttemptSucceededCanBeReused() {
        assertTrue(session.lastAttemptSucceeded())
        assertFalse(session.copy(attempts = session.attempts.reversed()).lastAttemptSucceeded())
        assertFalse(session.copy(attempts = emptyList()).lastAttemptSucceeded())
        // An answer that came but couldn't be read isn't one to show again.
        val unreadable = Attempt(at = 1_600, raw = "not json", failure = "BadResponse")
        assertFalse(session.copy(attempts = session.attempts + unreadable).lastAttemptSucceeded())
    }

    @Test
    fun theDecidedAttemptIsTheOneTheDecidedSuggestionsCameFrom() {
        val ok = Attempt(at = 1, raw = "{}")
        val failed = Attempt(at = 2, failure = "Offline")
        fun decided(attempts: List<Attempt>, vararg rows: Pair<Int, Decision>) = SessionDetail(
            session.copy(attempts = attempts),
            rows.map { (attempt, decision) -> suggestion.copy(attempt = attempt, decision = decision) },
        ).decidedAttempt()
        // The suggestions decided at the close came from attempt 0; attempt 1 was unclear.
        assertEquals(0, decided(listOf(ok, ok), 0 to Decision.Accepted))
        assertEquals(1, decided(listOf(ok, ok), 0 to Decision.Superseded, 1 to Decision.Ignored))
        // No suggestions: the last that succeeded.
        assertEquals(1, decided(listOf(ok, ok, failed)))
        assertNull(decided(listOf(failed)))
        assertNull(decided(emptyList()))
    }

    @Test
    fun theLanguagesAreMatchedEvenWhenAuto() {
        val key = session.reuseKey
        assertTrue(HistorySchema.reusable(key.copy(requestedLanguage = null)).contains("\"requestedLanguage\" IS NULL"))
        assertTrue(HistorySchema.reusable(key).contains("\"requestedLanguage\" = ?"))
        assertTrue(HistorySchema.reusable(key).contains("\"nativeLanguage\" = ?"))
        assertTrue(HistorySchema.reusable(key.copy(settings = key.settings.copy(nativeLanguage = null))).contains("\"nativeLanguage\" IS NULL"))
    }

    @Test
    fun version1UpgradesAddingWhatsMissingAndRenamingEnums() {
        val withoutStatus = HistorySchema.SESSION_COLUMNS.toSet() - "status" - "nativeLanguage"
        val first = HistorySchema.upgrade(1, withoutStatus)!!
        assertTrue(first.any { it.contains("ADD COLUMN \"status\"") })
        assertTrue(first.any { it.contains("ADD COLUMN \"nativeLanguage\"") })
        val later = HistorySchema.upgrade(1, HistorySchema.SESSION_COLUMNS.toSet() - "nativeLanguage")!!
        assertFalse(later.any { it.contains("ADD COLUMN \"status\"") })
        for (column in listOf("origin", "outcome", "punctuation", "judgments", "provider", "kind", "decision")) {
            assertTrue(column, later.any { it.contains("SET \"$column\" = CASE") })
        }
        assertTrue(later.any { it.contains("WHEN 'Accepted' THEN 'accepted'") })
        assertTrue(later.any { it.contains("WHEN 'FixOnly' THEN 'fix'") })
        assertTrue(later.any { it.contains("WHEN 'Gemini' THEN 'gemini'") })
        assertTrue(later.any { it.contains("WHEN 'Menu' THEN 'menu'") })
    }

    @Test
    fun everyVersionUpgradesByDeletingTheTestersSessions() {
        assertEquals(4, HistorySchema.VERSION)
        for (from in 1..3) {
            val statements = HistorySchema.upgrade(from, HistorySchema.SESSION_COLUMNS.toSet())!!
            val deletes = statements.filter { it.startsWith("DELETE") }
            assertEquals(from.toString(), HistorySchema.DELETE_UNRECORDED, deletes)
            // After version 1's names have become tokens, and suggestions before their sessions.
            assertTrue(statements.indexOf(deletes.first()) > statements.indexOfFirst { it.contains("SET \"origin\"") })
            assertTrue(deletes[0].startsWith("DELETE FROM suggestions") && deletes[0].contains("'tester'"))
            assertTrue(deletes[1].startsWith("DELETE FROM sessions") && deletes[1].endsWith("\"origin\" IN ('tester')"))
        }
    }

    @Test
    fun anUpgradedAttemptGetsTheSessionsLanguageAndSettings() {
        val v3 = """[{"at":1,"raw":"{}","failure":null},{"at":2,"raw":null,"failure":"offline"}]"""
        val row = mapOf(
            "requestedLanguage" to "ca", "nativeLanguage" to "English", "punctuation" to "moderate", "judgments" to "both",
            "provider" to "gemini", "model" to "m", "promptHash" to "p1",
        )
        assertEquals(HistorySchema.ATTEMPT_CONTEXT, row.keys.toList())
        val v4 = upgradeAttempts(v3, row)
        val attempts = decodeAttempts(v4)
        assertEquals(listOf("ca", "ca"), attempts.map { it.requestedLanguage })
        assertEquals(listOf("m", "m"), attempts.map { it.model })
        assertEquals(listOf("{}", null), attempts.map { it.raw })
        val upgraded = session.copy(textHash = "h", requestedLanguage = "ca", nativeLanguage = "English", punctuation = "moderate",
            judgments = "both", provider = "gemini", model = "m", promptHash = "p1", attempts = attempts)
        assertEquals(upgraded.reuseKey, upgraded.reuseKeyOf(0))
        assertEquals(v4, upgradeAttempts(v4, row))
        // Auto-detect reads as null with nothing added; a field an attempt has is kept.
        assertFalse(upgradeAttempts(v3, row + ("requestedLanguage" to null)).contains("requestedLanguage"))
        val kept = """[{"at":1,"requestedLanguage":null}]"""
        assertEquals(null, decodeAttempts(upgradeAttempts(kept, row)).single().requestedLanguage)
        assertEquals(v3, upgradeAttempts(v3))
    }

    @Test
    fun aVersionWithNoWayUpHasNone() {
        assertNull(HistorySchema.upgrade(0, emptySet()))
        assertNull(HistorySchema.upgrade(HistorySchema.VERSION, emptySet()))
    }

    @Test
    fun anUpgradedAttemptKeepsItsAnswerAsRaw() {
        val v1 = """[{"at":1,"settled":[{"about":"tu","answer":"informal"}],"verdict":"{\"status\":\"ok\"}","failure":null}]"""
        val v2 = upgradeAttempts(v1)
        val attempt = historyJson.parseToJsonElement(v2).jsonArray.single().jsonObject
        assertEquals(listOf("at", "settled", "raw", "failure"), attempt.keys.toList())
        assertEquals("""{"status":"ok"}""", attempt["raw"]!!.jsonPrimitive.content)
        assertEquals("informal", attempt["settled"]!!.jsonArray.single().jsonObject["answer"]!!.jsonPrimitive.content)
        // Already upgraded, or not readable: left as it is.
        assertEquals(v2, upgradeAttempts(v2))
        assertEquals("{broken", upgradeAttempts("{broken"))
        assertEquals("[]", upgradeAttempts("[]"))
    }

    @Test
    fun anExportedRowIsTheRowAsStored() {
        val row = MapRow(session.values() + mapOf("origin" to "widget", "status" to "maybe"))
        val json = rowJson(row, HistorySchema.SESSION_COLUMNS)
        assertEquals(HistorySchema.SESSION_COLUMNS, json.keys.toList())
        assertEquals("\"widget\"", json["origin"].toString())
        assertEquals("\"maybe\"", json["status"].toString())
        assertEquals("1000", json["startedAt"].toString())
        assertEquals("null", json["deletedAt"].toString())
        // Attempts are their JSON, not a string of it.
        assertTrue(json["attempts"].toString().startsWith("[{"))
        val unreadable = rowJson(MapRow(session.values() + ("attempts" to "{broken")), HistorySchema.SESSION_COLUMNS)
        assertEquals("\"{broken\"", unreadable["attempts"].toString())

        val line = exportLine(json, listOf(rowJson(MapRow(suggestion.values() + ("kind" to "style")), HistorySchema.SUGGESTION_COLUMNS)))
        assertFalse(line.contains('\n'))
        assertTrue(line, line.startsWith("{\"session\":{\"id\":\"s1\""))
        assertTrue(line, line.contains("\"suggestions\":[{\"id\":\"g1\""))
        assertTrue(line, line.contains("\"kind\":\"style\""))
    }
}

/** A row of a table as a map, standing in for SQLite's cursor. */
internal class MapRow(private val values: Map<String, Any?>) : Row {
    override fun stringOrNull(column: String) = value(column) as String?

    override fun longOrNull(column: String) = (value(column) as Number?)?.toLong()

    private fun value(name: String): Any? {
        check(name in values) { "No column $name" }
        return values[name]
    }
}
