package io.github.stardomains3.oxproxion

import android.content.Context
import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Pins, fact notes, forks and swipe versions for a chat import that has already inserted its rows.
 * The preference commit is a second step. This file is written first, under the no-backup
 * directory, so a kill before that commit is finished on the next launch instead of dropping
 * the notes. A backup from before those fields is still applied: the list says what to clear.
 */
internal object ChatImportSideLog {
    private const val TAG = "ChatImportSideLog"
    private const val NAME = "chat-import-side.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ImportedChatMeta.serializer())

    fun file(context: Context): File = File(ChatDbVault.directory(context), NAME)

    /**
     * Applies a leftover log and removes it. True when there was nothing to do, or the notes
     * were committed. False when a log is present and the commit did not land; the file stays.
     */
    fun resume(context: Context): Boolean {
        val dest = file(context)
        val entries = read(dest) ?: return true
        if (!SharedPreferencesHelper(context).applyImportedChatMetadata(entries)) {
            Log.e(TAG, "Imported chat notes are still waiting")
            return false
        }
        clear(dest)
        return true
    }

    fun write(dest: File, entries: List<ImportedChatMeta>) {
        val dir = dest.parentFile ?: throw IOException("no directory")
        dir.mkdirs()
        val partial = sibling(dest, ".partial")
        FileOutputStream(partial).use { out ->
            out.write(json.encodeToString(serializer, entries).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        syncDirectory(dir)
        val bak = sibling(dest, ".bak")
        if (dest.exists() && !dest.renameTo(bak)) {
            partial.delete()
            throw IOException("Could not replace ${dest.path}")
        }
        if (!partial.renameTo(dest)) {
            if (!dest.exists()) bak.renameTo(dest)
            partial.delete()
            throw IOException("Could not install ${dest.path}")
        }
        if (bak.exists() && !bak.delete()) Log.w(TAG, "Could not remove ${bak.path}")
        syncDirectory(dir)
    }

    fun clear(dest: File) {
        dest.delete()
        sibling(dest, ".partial").delete()
        sibling(dest, ".bak").delete()
    }

    /** The installed log, or a side file left when the install was killed. */
    internal fun read(dest: File): List<ImportedChatMeta>? {
        decode(dest)?.let { return it }
        decode(sibling(dest, ".partial"))?.let { return it }
        return decode(sibling(dest, ".bak"))
    }

    private fun decode(file: File): List<ImportedChatMeta>? {
        if (!file.isFile || file.length() == 0L) return null
        return try {
            json.decodeFromString(serializer, file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun sibling(dest: File, suffix: String) = File(dest.parentFile, dest.name + suffix)

    private fun syncDirectory(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
        }
    }
}
