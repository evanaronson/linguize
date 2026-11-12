package com.evanaronson.linguize.transfer

// TEMPORARY: lab transfer bridge, see docs/lab/transfer-bridge.md

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.evanaronson.linguize.App
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.data.time.TimeStore
import com.evanaronson.linguize.history.HistorySchema
import com.evanaronson.linguize.history.SqliteHistoryStore
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Hands one read-only snapshot of this app's data ([TransferExporter]) to an app signed with
 * the same key. The manifest guards it with [LabTransfer.PERMISSION] (signature level); it is
 * checked here again. Only `openFile` in read mode answers; there is nothing to query, insert,
 * update or delete, and nothing here ever changes this app's data.
 */
class LabTransferProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = context ?: throw FileNotFoundException("Not ready")
        if (context.checkCallingOrSelfPermission(LabTransfer.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Requires ${LabTransfer.PERMISSION}")
        }
        if (mode != "r" || uri.pathSegments != listOf(LabTransfer.PATH)) throw FileNotFoundException("Read-only: $uri")
        val sources = sources(context)
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        Thread({
            try {
                val out = FileOutputStream(write.fileDescriptor)
                runBlocking { TransferExporter(sources).write(out) }
                out.flush()
                write.close()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't write the transfer snapshot", e)
                // The reader sees an error, not a short bundle; the bundle has no end line either way.
                runCatching { write.closeWithError(e.message ?: "Transfer failed") }
            }
        }, "lab-transfer").start()
        return read
    }

    private fun sources(context: Context): TransferSources {
        val app = App.of(context)
        fun exists(name: String) = context.getDatabasePath(name).exists()
        return TransferSources(
            packageName = context.packageName,
            appVersion = app.appVersion,
            history = (app.history as? SqliteHistoryStore)?.takeIf { exists(HistorySchema.NAME) },
            chat = app.chatStore.takeIf { exists(ChatStore.DATABASE) },
            time = app.timeStore.takeIf { exists(TimeStore.DATABASE) },
            filesDir = context.filesDir,
            prefs = { context.getSharedPreferences(it, Context.MODE_PRIVATE) },
        )
    }

    override fun getType(uri: Uri): String = "application/x-ndjson"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")

    private companion object {
        const val TAG = "LabTransfer"
    }
}
