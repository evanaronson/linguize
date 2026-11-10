package com.evanaronson.linguize.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Replaces one UTF-8 file only after its complete contents have been written and synced.
 * A failed write or rename leaves the destination intact and throws [IOException].
 * Callers serialize access to the file and publish cached state only after this returns.
 * The temporary file is in the same directory so replacement is a single filesystem rename.
 */
fun writeAtomically(file: File, contents: String) {
    val temp = File(file.parentFile, file.name + ".tmp")
    try {
        file.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                throw IOException("Couldn't create ${parent.name}")
            }
        }
        FileOutputStream(temp).use { output ->
            output.write(contents.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temp.renameTo(file)) throw IOException("Couldn't replace ${file.name}")
    } catch (e: IOException) {
        // Never delete the destination to make a failed replacement appear successful.
        if (temp.isFile) temp.delete()
        throw e
    }
}
