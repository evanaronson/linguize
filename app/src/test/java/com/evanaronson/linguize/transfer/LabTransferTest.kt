package com.evanaronson.linguize.transfer

// TEMPORARY: lab transfer bridge, see docs/lab/transfer-bridge.md. Delete with the bridge.

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.memory.Fact
import com.evanaronson.linguize.core.memory.FactCategory
import com.evanaronson.linguize.core.memory.MemoryState
import com.evanaronson.linguize.core.teachme.Lesson
import com.evanaronson.linguize.core.teachme.LessonExample
import com.evanaronson.linguize.core.teachme.LessonTopic
import com.evanaronson.linguize.core.teachme.QuizQuestion
import com.evanaronson.linguize.core.time.TimeRecord
import com.evanaronson.linguize.core.vocab.DeckEntry
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.data.memory.MemoryStore
import com.evanaronson.linguize.data.phrasebook.Phrase
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import com.evanaronson.linguize.data.teachme.LessonStore
import com.evanaronson.linguize.data.time.TimeStore
import com.evanaronson.linguize.data.vocabgaps.VocabGapsStore
import com.evanaronson.linguize.data.write.WriteDraft
import com.evanaronson.linguize.data.write.WriteDraftStore
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.SessionDetailResult
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SessionRecording
import com.evanaronson.linguize.history.SqliteHistoryStore
import com.evanaronson.linguize.history.SuggestionRecord
import com.evanaronson.linguize.history.TransferSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The transfer bridge end to end, with two "apps" in one Robolectric process: every store of
 * each side has its own database, folder and preferences names. A plain Application, so the
 * app's own start-up doesn't touch the stores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LabTransferTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sides = mutableListOf<Side>()

    private inner class Side(val prefix: String) {
        val history = SqliteHistoryStore(context, "${prefix}_history.db")
        val chat = ChatStore(context, "${prefix}_chat")
        val time = TimeStore(context, scope, "${prefix}_time")
        val files = File(context.filesDir, prefix).apply { mkdirs() }
        val notes = WriteDraftStore(File(files, WriteDraftStore.FILE_NAME), legacy = File(files, WriteDraftStore.PAD_FILE_NAME))
        val memory = MemoryStore(File(files, MemoryStore.FILE_NAME))
        val phrasebook = PhrasebookStore(File(files, PhrasebookStore.FILE_NAME))
        val lessons = LessonStore(File(files, LessonStore.FILE_NAME))
        val decks = VocabGapsStore(File(files, VocabGapsStore.DIR))
        var historyOn = true

        init { sides += this }

        fun prefs(name: String) = context.getSharedPreferences("${prefix}_$name", Context.MODE_PRIVATE)

        /** As the provider builds them: a database that doesn't exist isn't opened. */
        fun sources() = TransferSources(
            LabTransfer.SOURCE_PACKAGE, "v000123", history,
            chat.takeIf { context.getDatabasePath("${prefix}_chat").exists() },
            time.takeIf { context.getDatabasePath("${prefix}_time").exists() },
            files, ::prefs,
        )

        fun targets() = TransferTargets(
            history, { historyOn }, chat, time, notes, memory, phrasebook, lessons, decks, ::prefs, { listOf("ca", "es") },
        )

        fun close() {
            chat.close()
            time.close()
            listOf("history.db", "chat", "time").forEach { context.deleteDatabase("${prefix}_$it") }
            files.deleteRecursively()
        }
    }

    @After
    fun cleanUp() {
        sides.forEach { it.close() }
        scope.cancel()
    }

    private suspend fun bundle(from: Side): ByteArray = ByteArrayOutputStream().also { TransferExporter(from.sources()).write(it) }.toByteArray()

    private suspend fun transfer(from: Side, to: Side): TransferReport =
        TransferImporter(to.targets()).merge(TransferBundle.read(ByteArrayInputStream(bundle(from))))

    // Fixtures ------------------------------------------------------------------------------

    private fun session(id: String, at: Long, text: String = "Com estas?", origin: Origin = Origin.Menu, closed: Boolean = true) = SessionRecord(
        id = id, deviceId = "device-x", createdAt = at, updatedAt = at, startedAt = at, origin = origin,
        requestedLanguage = "ca", nativeLanguage = "English", text = text, textHash = SessionRecording.hash(text),
        punctuation = "moderate", judgments = "both", provider = "gemini", model = "m", promptHash = "p1", appVersion = "1.0",
    ).let { if (closed) it.copy(closedAt = at + 1, updatedAt = at + 1, outcome = Outcome.Applied, finalText = "Com estàs?") else it }

    private fun suggestion(session: SessionRecord, id: String) = SuggestionRecord(
        id = id, sessionId = session.id, deviceId = "device-x", createdAt = session.updatedAt, updatedAt = session.updatedAt,
        attempt = 0, kind = EditKind.Fix, start = 4, end = 9, fromText = "estas", toText = "estàs", why = "Accent",
        decision = Decision.Accepted, decidedAt = session.updatedAt,
    )

    private suspend fun Side.saveSession(record: SessionRecord, vararg suggestionIds: String) {
        history.save(record.copy(closedAt = null, outcome = null, finalText = null))
        if (record.closedAt != null) history.close(record, suggestionIds.map { suggestion(record, it) })
    }

    private fun lesson(rule: String) = Lesson(
        "Catalan", rule, "Yours", List(3) { LessonExample("S$it", "T$it") }, "Trap",
        listOf(QuizQuestion("Q", listOf("a", "b"), 0, "F"), QuizQuestion("Q2", listOf("c", "d"), 1, "F2")),
    )

    private fun profiles(vararg records: Pair<String, String>) = records.joinToString(",", "[", "]") { (id, language) ->
        """{"id":"$id","name":"$language","language":"$language","variety":null,"register":"everyday","punctuation":"moderate"}"""
    }

    /** A source with one of everything. */
    private suspend fun richSource(): Side = Side("src").apply {
        saveSession(session("s-menu", 1_000), "g-1", "g-2")
        saveSession(session("s-chat", 2_000, "Hola!", Origin.Chat), "g-3")
        chat.create(Conversation("c-1", "Weekend", "ca", 1_500, 1_500))
        chat.add(ChatMessage("m-1", "c-1", fromWriter = true, raw = "Hola!", text = "Hola!", at = 2_000, sessionId = "s-chat"))
        chat.add(ChatMessage("m-2", "c-1", fromWriter = false, raw = "Bon dia", text = "Bon dia", at = 2_100, translation = "Good morning"))
        time.save(TimeRecord("s-chat", "chat", "ca", 4_000, 2_000, 1, 0, 0, 2_000, messageId = "m-1"))
        time.save(TimeRecord("s-menu", "menu", "ca", null, 3_000, 2, 1, 1, 1_000))
        notes.saveCurrent(WriteDraft("n-1", "Una nota", "ca", updatedAt = 10))
        notes.saveCurrent(WriteDraft("n-2", "Una altra", null, updatedAt = 20))
        memory.update { _, _, _ ->
            MemoryState(facts = listOf(
                Fact("f-1", "Lives in Gràcia", FactCategory.About, listOf("chat:c-1"), 5, 5),
                Fact("f-2", "Writes to a landlord", FactCategory.People, emptyList(), 6, 6, byWriter = true),
            ), sources = mapOf("chat:c-1" to 5L))
        }
        phrasebook.restore(Phrase("p-1", "Què tal?", createdAt = 7, language = "ca"))
        lessons.put(LessonTopic("estas", "estàs", "Accent", "Catalan"), "English", lesson("Accents"))
        decks.import("Mine", "es", listOf(DeckEntry(1, "casa", "n", "house", listOf("casas"))))
        prefs("settings").edit()
            .putString("provider", "openai").putString("model.openai", "gpt-x").putString("judgments", "fixes")
            .putString("writingProfiles.v1", profiles("default-ca" to "ca", "default-fr" to "fr"))
            .putString("deviceId", "source-device").putBoolean("history", true).putLong("historyEpoch", 3)
            .commit()
        prefs("keys").edit().putString("gemini", "SECRET-SEALED-KEY").commit()
        prefs("lab_chat").edit().putString("startOn", "notes").putString("lastLanguage", "ca").commit()
        prefs("lab_language_scope").edit().putString("current", "ca").commit()
        prefs("lab_lang_generic").edit().putString("languages", "es,fr").commit()
        prefs("lab_app_voice").edit().putString("rule:com.whatsapp", """{"language":"ca"}""")
            .putString("rule:org.telegram", """{"register":"formal"}""").commit()
    }

    // Bundle --------------------------------------------------------------------------------

    @Test
    fun theBundleRoundTripsEveryKindAndNeverCarriesKeysOrTheDeviceId() = runBlocking {
        val source = richSource()
        val bytes = bundle(source)
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse("API keys never travel", "SECRET-SEALED-KEY" in text)
        assertFalse("the install's id never travels", "source-device" in text)
        val incoming = TransferBundle.read(ByteArrayInputStream(bytes))
        assertEquals(LabTransfer.SOURCE_PACKAGE, incoming.sourcePackage)
        assertEquals(TransferRules.historyVersion, incoming.historyVersion)
        assertEquals(setOf("s-menu", "s-chat"), incoming.sessions.map { it.session["id"] }.toSet())
        val menu = incoming.sessions.single { it.session["id"] == "s-menu" }
        assertEquals(setOf("g-1", "g-2"), menu.suggestions.map { it["id"] }.toSet())
        assertEquals(1_001L, menu.session["closedAt"])
        assertEquals(2, incoming.chat!!.messages.size)
        assertEquals(2, incoming.time.size)
        assertTrue(incoming.files.keys.containsAll(listOf(
            WriteDraftStore.FILE_NAME, MemoryStore.FILE_NAME, PhrasebookStore.FILE_NAME, LessonStore.FILE_NAME,
            "${VocabGapsStore.DIR}/decks/index.json",
        )))
        assertFalse("keys" in incoming.prefs)
        assertEquals(setOf("provider", "model.openai", "judgments", "writingProfiles.v1"), incoming.prefs.getValue("settings").keys)
        assertTrue(incoming.unreadable.isEmpty())
    }

    @Test
    fun aBundleWithoutItsEndIsRefusedWhole() = runBlocking<Unit> {
        val lines = bundle(richSource()).toString(Charsets.UTF_8).trimEnd().lines()
        val cut = lines.dropLast(1).joinToString("\n").toByteArray()
        try {
            TransferBundle.read(ByteArrayInputStream(cut))
            fail("A cut-off bundle must not read")
        } catch (_: IOException) {
        }
    }

    // Merge ---------------------------------------------------------------------------------

    @Test
    fun anEmptyTargetGetsEverythingWithChatAndHistoryStillLinked() = runBlocking {
        val source = richSource()
        val target = Side("dst")
        val report = transfer(source, target)

        assertTrue(report.toString(), report.failed.isEmpty())
        assertEquals(2, report.added[TransferKind.Checks])
        assertEquals(1, report.added[TransferKind.Chats])
        assertEquals(2, report.added[TransferKind.Messages])
        assertEquals(2, report.added[TransferKind.Time])
        assertEquals(2, report.added[TransferKind.Notes])
        assertEquals(2, report.added[TransferKind.AboutYou])
        assertEquals(1, report.added[TransferKind.Phrases])
        assertEquals(1, report.added[TransferKind.Lessons])
        assertEquals(1, report.added[TransferKind.Decks])
        assertEquals(2, report.added[TransferKind.AppVoice])

        // Chat ↔ History: the message's link names a session that's here, with the same id.
        val messages = target.chat.messages("c-1")
        assertEquals(listOf("m-1", "m-2"), messages.map { it.id })
        assertEquals("Good morning", messages[1].translation)
        val link = messages.first().sessionId!!
        val detail = (target.history.detail(link) as SessionDetailResult.Found).detail
        assertEquals("Hola!", detail.session.text)
        assertEquals(mapOf(link to "c-1"), target.chat.threads(listOf(link)).mapValues { it.value.conversationId })
        assertEquals(setOf("g-1", "g-2"), (target.history.detail("s-menu") as SessionDetailResult.Found).detail.suggestions.map { it.id }.toSet())
        assertEquals(setOf("s-chat", "s-menu"), target.time.all().map { it.sessionId }.toSet())

        assertEquals(setOf("n-1", "n-2"), target.notes.load().drafts.map { it.id }.toSet())
        assertEquals(setOf("f-1", "f-2"), target.memory.load().facts.map { it.id }.toSet())
        assertEquals(mapOf("chat:c-1" to 5L), target.memory.load().sources)
        assertEquals(listOf("p-1"), target.phrasebook.load().map { it.id })
        assertEquals(1, target.lessons.size())
        assertEquals(source.decks.imported(), target.decks.imported())
        assertNotNull(target.decks.importedDeck(source.decks.imported().single().id))

        // Preferences that were never set here are filled; keys and the install's own stay behind.
        val settings = target.prefs("settings")
        assertEquals("openai", settings.getString("provider", null))
        assertEquals("gpt-x", settings.getString("model.openai", null))
        assertFalse(settings.contains("deviceId"))
        assertFalse(settings.contains("history"))
        assertFalse(settings.contains("historyEpoch"))
        assertTrue(target.prefs("keys").all.isEmpty())
        assertEquals("notes", target.prefs("lab_chat").getString("startOn", null))
        // Never edited here: the seed (Catalan, Spanish), then what only the other app has.
        assertEquals("ca,es,fr", target.prefs("lab_lang_generic").getString("languages", null))
    }

    @Test
    fun runningItTwiceAddsNothingTheSecondTime() = runBlocking {
        val source = richSource()
        val target = Side("dst")
        transfer(source, target)
        val before = snapshot(target)
        val again = transfer(source, target)
        assertFalse(again.toString(), again.addedAnything)
        assertTrue(again.failed.isEmpty())
        assertTrue(again.alreadyHere > 0)
        assertEquals(before, snapshot(target))
    }

    @Test
    fun whatIsHereAlreadyWinsAndIsNeverReplaced() = runBlocking {
        val source = richSource()
        val target = Side("dst").apply {
            // Same ids, different content: the target's stays.
            saveSession(session("s-menu", 9_000, text = "Target's own text"), "g-1")
            chat.create(Conversation("c-1", "Target title", "ca", 9_000, 9_000))
            chat.add(ChatMessage("m-1", "c-1", fromWriter = true, raw = "Mine", text = "Mine", at = 9_100))
            time.save(TimeRecord("s-menu", "menu", "ca", null, 99_000, 9, 9, 9, 9_000))
            notes.saveCurrent(WriteDraft("n-1", "Target note", "es", updatedAt = 99))
            memory.update { _, _, _ -> MemoryState(enabled = false, facts = listOf(Fact("f-1", "Target fact", FactCategory.Goals, emptyList(), 1, 1, true))) }
            phrasebook.restore(Phrase("p-1", "Target phrase", createdAt = 1))
            prefs("settings").edit().putString("provider", "gemini")
                .putString("writingProfiles.v1", profiles("mine-ca" to "ca")).commit()
            prefs("lab_chat").edit().putBoolean("openToChat", true).commit()
            prefs("lab_lang_generic").edit().putString("languages", "it,es").commit()
            prefs("lab_app_voice").edit().putString("rule:com.whatsapp", """{"language":"es"}""").commit()
        }
        val report = transfer(source, target)
        assertTrue(report.toString(), report.failed.isEmpty())

        val menu = (target.history.detail("s-menu") as SessionDetailResult.Found).detail
        assertEquals("Target's own text", menu.session.text)
        assertEquals(listOf("g-1"), menu.suggestions.map { it.id })
        assertEquals(1, report.added[TransferKind.Checks]) // only s-chat

        // The conversation stays the target's; only the message it lacks is added.
        val conversation = target.chat.conversation("c-1")!!
        assertEquals("Target title", conversation.title)
        assertEquals(setOf("m-1", "m-2"), target.chat.messages("c-1").map { it.id }.toSet())
        assertEquals("Mine", target.chat.messages("c-1").single { it.id == "m-1" }.text)
        assertEquals(9_100L, conversation.updatedAt)

        assertEquals(99_000L, target.time.all().single { it.sessionId == "s-menu" }.reviewMs)
        assertEquals("Target note", target.notes.load().drafts.single { it.id == "n-1" }.text)
        val memory = target.memory.load()
        assertFalse("learning stays off here", memory.enabled)
        assertEquals("Target fact", memory.facts.single { it.id == "f-1" }.text)
        assertTrue(memory.facts.any { it.id == "f-2" })
        assertEquals("Target phrase", target.phrasebook.load().single().phrase)

        val settings = target.prefs("settings")
        assertEquals("gemini", settings.getString("provider", null))
        assertEquals("gpt-x", settings.getString("model.openai", null))
        // The profile for a language here stays; the one for a language it lacks is added.
        assertEquals(profiles("mine-ca" to "ca", "default-fr" to "fr").replace(" ", ""),
            settings.getString("writingProfiles.v1", null)!!.replace(" ", ""))
        assertEquals(1, report.added[TransferKind.WritingProfiles])
        // "Start on" was already decided here by the old switch.
        assertFalse(target.prefs("lab_chat").contains("startOn"))
        // Union with this app's order first.
        assertEquals("it,es,fr", target.prefs("lab_lang_generic").getString("languages", null))
        val voice = target.prefs("lab_app_voice")
        assertEquals("""{"language":"es"}""", voice.getString("rule:com.whatsapp", null))
        assertEquals("""{"register":"formal"}""", voice.getString("rule:org.telegram", null))
        assertEquals(1, report.added[TransferKind.AppVoice])
    }

    @Test
    fun theSourceIsLeftExactlyAsItWas() = runBlocking {
        val source = richSource()
        val before = snapshot(source)
        val filesBefore = fileBytes(source.files)
        val prefsBefore = PREF_FILES.associateWith { source.prefs(it).all.toMap() }
        transfer(source, Side("dst"))
        assertEquals(before, snapshot(source))
        assertEquals(filesBefore.mapValues { it.value.toList() }, fileBytes(source.files).mapValues { it.value.toList() })
        assertEquals(prefsBefore, PREF_FILES.associateWith { source.prefs(it).all.toMap() })
    }

    @Test
    fun exportingNeverMakesADatabaseOrAFileTheSourceDidNotHave() = runBlocking {
        val source = Side("bare")
        val incoming = TransferBundle.read(ByteArrayInputStream(bundle(source)))
        assertTrue(incoming.sessions.isEmpty())
        assertFalse(context.getDatabasePath("bare_history.db").exists())
        assertTrue(source.files.list().orEmpty().isEmpty())
    }

    @Test
    fun deletedUnfinishedAndPendingChecksStayBehind() = runBlocking {
        val source = richSource().apply {
            saveSession(session("s-open", 3_000, closed = false))
            saveSession(session("s-undo", 4_000), "g-undo")
            prefs("recent").edit().putString("pendingOperation:abc:s-undo", "s-undo").commit()
            // A chat deleted there, whose History and memory cleanup hasn't run yet.
            saveSession(session("s-gone", 5_000, "Adéu", Origin.Chat))
            chat.create(Conversation("c-gone", "", "ca", 5_000, 5_000))
            chat.add(ChatMessage("m-gone", "c-gone", fromWriter = true, raw = "Adéu", text = "Adéu", at = 5_000, sessionId = "s-gone"))
            time.save(TimeRecord("s-gone", "chat", "ca", 1, 1, 1, 0, 0, 5_000))
            memory.update { state, _, _ ->
                state.copy(facts = state.facts + Fact("f-gone", "Said goodbye", FactCategory.About, listOf("chat:c-gone"), 8, 8),
                    sources = state.sources + ("chat:c-gone" to 8L))
            }
            assertTrue(chat.delete("c-gone"))
        }
        val target = Side("dst")
        val report = transfer(source, target)
        assertEquals(1, report.skipped[TransferSkip.UnfinishedChecks])
        assertEquals(2, report.skipped[TransferSkip.DeletedChecks])
        for (id in listOf("s-open", "s-undo", "s-gone")) {
            assertEquals(id, SessionDetailResult.Missing, target.history.detail(id))
        }
        assertFalse(target.time.all().any { it.sessionId == "s-gone" })
        assertNull(target.chat.conversation("c-gone"))
        assertFalse(target.memory.load().facts.any { it.id == "f-gone" })
        assertFalse("chat:c-gone" in target.memory.load().sources)
    }

    @Test
    fun aChatDeletedHereStaysDeleted() = runBlocking {
        val source = richSource()
        val target = Side("dst")
        transfer(source, target)
        assertTrue(target.chat.delete("c-1")) // cleanup still pending
        val report = transfer(source, target)
        assertEquals(1, report.skipped[TransferSkip.ChatsDeletedHere])
        assertNull(target.chat.conversation("c-1"))
    }

    @Test
    fun withHistoryOffHereChecksAndTheirTimeStayBehind() = runBlocking {
        val source = richSource()
        val target = Side("dst").apply { historyOn = false }
        val report = transfer(source, target)
        assertEquals(2, report.skipped[TransferSkip.HistoryOff])
        assertNull(report.added[TransferKind.Checks])
        assertTrue(target.time.all().isEmpty())
        // Chat works without History; its messages still come.
        assertEquals(2, target.chat.messages("c-1").size)
        assertEquals(SessionDetailResult.Missing, target.history.detail("s-chat"))
    }

    @Test
    fun anotherHistoryVersionStaysBehindWhole() = runBlocking {
        val source = richSource()
        val target = Side("dst")
        val incoming = TransferBundle.read(ByteArrayInputStream(bundle(source)))
        val report = TransferImporter(target.targets()).merge(incoming.copy(historyVersion = TransferRules.historyVersion + 1))
        assertEquals(2, report.skipped[TransferSkip.HistoryVersion])
        assertEquals(SessionDetailResult.Missing, target.history.detail("s-menu"))
    }

    @Test
    fun theWritingPadIsReadWhenNotesWereNeverOpened() = runBlocking {
        val source = Side("pad")
        File(source.files, WriteDraftStore.PAD_FILE_NAME).writeText(
            """{"drafts":[{"id":"pad-1","title":" Old ","text":"Del pad","updatedAt":3},{"id":"pad-2","text":"  "}]}""",
        )
        val target = Side("dst")
        val report = transfer(source, target)
        assertEquals(1, report.added[TransferKind.Notes])
        assertEquals(WriteDraft("pad-1", "Del pad", null, "Old", 3), target.notes.load().drafts.single())
        assertFalse("the source's pad isn't migrated by the export", File(source.files, WriteDraftStore.FILE_NAME).exists())
    }

    @Test
    fun insertIfAbsentNeverTouchesAnExistingSession() = runBlocking {
        val target = Side("dst")
        target.saveSession(session("s-1", 1_000, "Here"), "g-1")
        val incoming = TransferSession(
            mapOf("id" to "s-1", "text" to "There"),
            listOf(mapOf("id" to "g-x", "sessionId" to "s-1")),
        )
        assertEquals(0, target.history.insertIfAbsent(listOf(incoming)))
        val detail = (target.history.detail("s-1") as SessionDetailResult.Found).detail
        assertEquals("Here", detail.session.text)
        assertEquals(listOf("g-1"), detail.suggestions.map { it.id })
    }

    // Manifest and Settings ---------------------------------------------------------------

    @Suppress("DEPRECATION")
    @Test
    fun theProviderIsGuardedByASignaturePermission() {
        val info: PackageInfo = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS,
        )
        val permission = info.permissions.orEmpty().single { it.name == LabTransfer.PERMISSION }
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE)
        assertTrue(LabTransfer.PERMISSION in info.requestedPermissions.orEmpty())
        val provider = info.providers.orEmpty().single { it.name == LabTransferProvider::class.java.name }
        assertEquals(context.packageName + LabTransfer.AUTHORITY_SUFFIX, provider.authority)
        assertTrue(provider.exported)
        assertEquals(LabTransfer.PERMISSION, provider.readPermission)
        assertEquals(LabTransfer.PERMISSION, provider.writePermission)
        assertFalse(provider.grantUriPermissions)
    }

    @Test
    fun theProviderRefusesWithoutThePermissionAndAnswersOnlyReads() {
        val provider = Robolectric.setupContentProvider(LabTransferProvider::class.java)
        val uri = LabTransfer.uri(context.packageName)
        val app = shadowOf(context as Application)
        app.denyPermissions(LabTransfer.PERMISSION)
        assertThrows(SecurityException::class.java) { provider.openFile(uri, "r") }
        app.grantPermissions(LabTransfer.PERMISSION)
        assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "w") }
        assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "rw") }
        assertThrows(UnsupportedOperationException::class.java) { provider.delete(uri, null, null) }
        assertThrows(UnsupportedOperationException::class.java) { provider.insert(uri, null) }
        assertNull(provider.query(uri, null, null, null, null))
    }

    @Test
    fun theRowIsOfferedOnlyInAnotherAppWithTheSourceInstalled() {
        assertTrue(LabTransfer.offered("com.evanaronson.linguize", sourceInstalled = true))
        assertFalse(LabTransfer.offered("com.evanaronson.linguize", sourceInstalled = false))
        assertFalse(LabTransfer.offered(LabTransfer.SOURCE_PACKAGE, sourceInstalled = true))
        assertFalse(LabTransfer.offered(LabTransfer.SOURCE_PACKAGE, sourceInstalled = false))

        val own = context.packageName
        if (own != LabTransfer.SOURCE_PACKAGE) assertFalse(LabTransfer.offered(context))
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = LabTransfer.SOURCE_PACKAGE })
        assertEquals(own != LabTransfer.SOURCE_PACKAGE, LabTransfer.offered(context))
    }

    // Helpers -------------------------------------------------------------------------------

    /** Everything a side holds, as comparable values. */
    private suspend fun snapshot(side: Side): List<Any?> {
        val sessions = mutableListOf<TransferSession>()
        assertTrue(side.history.transferRows { sessions += it })
        return listOf(
            sessions,
            side.chat.transferRows().let { it.copy(messages = it.messages.map { row -> row - "seq" }) },
            side.time.all(),
            side.notes.load(),
            side.memory.load(),
            side.phrasebook.load(),
            side.lessons.size(),
            side.decks.imported(),
            PREF_FILES.associateWith { side.prefs(it).all.toMap() },
        )
    }

    private fun fileBytes(root: File): Map<String, ByteArray> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes() }

    private companion object {
        val PREF_FILES = listOf("settings", "keys", "recent", "lab_chat", "lab_language_scope", "lab_lang_generic", "lab_app_voice", "lab_patterns")
    }
}
