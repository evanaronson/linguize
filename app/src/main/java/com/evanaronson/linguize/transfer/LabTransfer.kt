package com.evanaronson.linguize.transfer

// TEMPORARY: lab transfer bridge, see docs/lab/transfer-bridge.md

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.net.toUri
import com.evanaronson.linguize.App
import com.evanaronson.linguize.data.vocabgaps.VocabGapsStore
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.history.SqliteHistoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The one-shot pull from Linguize X Claude into this app. Both apps declare the same
 * signature-level permission, so only an app signed with the same key can read the snapshot.
 */
object LabTransfer {
    /** The experimental app the data comes from. */
    const val SOURCE_PACKAGE = "com.evanaronson.linguize.xclaude"

    /** Fixed, not per application id, so both builds declare and hold the same permission. */
    const val PERMISSION = "com.evanaronson.linguize.permission.LAB_TRANSFER"

    /** The provider's authority is the application id plus this. */
    const val AUTHORITY_SUFFIX = ".labtransfer"
    const val PATH = "bundle"

    fun uri(packageName: String): Uri = "content://$packageName$AUTHORITY_SUFFIX/$PATH".toUri()

    /** Settings offers the row only in another app than the source, with the source installed. */
    fun offered(ownPackage: String, sourceInstalled: Boolean) = ownPackage != SOURCE_PACKAGE && sourceInstalled

    fun offered(context: Context): Boolean =
        offered(context.packageName, installed(context, SOURCE_PACKAGE))

    private fun installed(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Where an import is; one at a time per process. */
    sealed interface State {
        data object Idle : State
        data object Running : State
        data class Done(val report: TransferReport) : State

        /** The source has no bridge (an older build) or refused this app. */
        data object Unavailable : State
        data object Failed : State
    }

    private val current = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = current.asStateFlow()

    /** Back to idle once the result has been seen. */
    fun dismiss() {
        current.update { if (it == State.Running) it else State.Idle }
    }

    /** Starts an import on the app's scope, so leaving Settings doesn't stop it halfway. */
    fun start(app: App, onFinished: () -> Unit = {}) {
        if (!current.compareAndSet(State.Idle, State.Running)) return
        app.appScope.launch {
            val result = try {
                run(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Transfer failed", e)
                State.Failed
            }
            current.value = result
            withContext(Dispatchers.Main) { onFinished() }
        }
    }

    private suspend fun run(app: App): State {
        val incoming = withContext(Dispatchers.IO) {
            val descriptor = try {
                app.contentResolver.openFileDescriptor(uri(SOURCE_PACKAGE), "r")
            } catch (e: SecurityException) {
                Log.w(TAG, "The source refused the transfer", e)
                null
            } catch (e: FileNotFoundException) {
                // Also what an app without the provider answers.
                Log.w(TAG, "No transfer provider in the source", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "No transfer provider in the source", e)
                null
            } ?: return@withContext null
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { TransferBundle.read(it) }
        } ?: return State.Unavailable
        if (incoming.sourcePackage != SOURCE_PACKAGE) throw IOException("Unexpected source ${incoming.sourcePackage}")
        return State.Done(TransferImporter(targets(app)).merge(incoming))
    }

    fun targets(app: App) = TransferTargets(
        history = app.history as? SqliteHistoryStore,
        historyEnabled = { app.settings.historyEnabled },
        chat = app.chatStore,
        time = app.timeStore,
        notes = app.notesStore,
        memory = app.memoryStore,
        phrasebook = PhrasebookStore.of(app),
        lessons = app.lessonStore,
        decks = VocabGapsStore(File(app.filesDir, VocabGapsStore.DIR)),
        prefs = { app.getSharedPreferences(it, Context.MODE_PRIVATE) },
        languageSeed = { Language.all.map { it.code } },
    )

    private const val TAG = "LabTransfer"
}
