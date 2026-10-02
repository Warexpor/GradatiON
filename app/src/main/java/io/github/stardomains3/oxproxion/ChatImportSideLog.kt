package io.github.stardomains3.oxproxion

import android.content.Context
import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Pins, fact notes, forks and swipe versions for a chat import.
 * The file is written inside the database transaction, before it commits, under the no-backup
 * directory. A kill after that commit and before the preference commit is finished on the next
 * launch. A log whose chat is not in the database is dropped, so a transaction that rolled back
 * cannot attach those notes to the next chat. A backup from before those fields is still applied:
 * the list says what to clear.
 */
internal object ChatImportSideLog {
    private const val TAG = "ChatImportSideLog"
    private const val NAME = "chat-import-side.json"
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ImportedChatMeta.serializer())

    fun file(context: Context): File = File(ChatDbVault.directory(context), NAME)

    /**
     * Applies a leftover log. [accept] drops a row whose chat is not the one this log names,
     * so a log written before the database commit cannot attach to the next chat that reuses
     * the id. Rejected rows are removed before the commit. True when there was nothing to do,
     * or the notes were committed. False when accepted notes are still waiting.
     */
    fun resume(context: Context, accept: (ImportedChatMeta) -> Boolean = { true }): Boolean {
        synchronized(lock) {
            val dest = file(context)
            val entries = read(dest) ?: return true
            val accepted = entries.filter(accept)
            if (accepted.size != entries.size) {
                if (accepted.isEmpty()) {
                    clear(dest)
                    return true
                }
                write(dest, accepted)
            }
            if (!SharedPreferencesHelper(context).applyImportedChatMetadata(accepted)) {
                Log.e(TAG, "Imported chat notes are still waiting")
                return false
            }
            clear(dest)
            return true
        }
    }

    /**
     * True when [session] is the chat this entry was written for.
     * A missing chat is not. Timestamp and message count fingerprint the row so a recycled
     * id cannot inherit; a rename while notes are still waiting must not drop them (title
     * alone used to). A log from before those fields matches any chat that is still there.
     * Title is only checked when stamp and count are both absent (older logs).
     */
    fun matches(entry: ImportedChatMeta, session: ChatSession?, messageCount: Int): Boolean {
        if (session == null) return false
        if (entry.timestamp != null && entry.timestamp != session.timestamp) return false
        if (entry.messageCount != null && entry.messageCount != messageCount) return false
        if (entry.timestamp == null && entry.messageCount == null &&
            entry.title != null && entry.title != session.title
        ) {
            return false
        }
        return true
    }

    fun write(dest: File, entries: List<ImportedChatMeta>) {
        synchronized(lock) {
            SideFile.write(dest, json.encodeToString(serializer, entries).toByteArray(Charsets.UTF_8))
        }
    }

    fun clear(dest: File) {
        synchronized(lock) { SideFile.clear(dest) }
    }

    /** The installed log, or a newer side file left when the install was killed. */
    internal fun read(dest: File): List<ImportedChatMeta>? {
        for (candidate in SideFile.candidates(dest)) {
            decode(candidate)?.let { return it }
        }
        return null
    }

    private fun decode(file: File): List<ImportedChatMeta>? {
        if (!file.isFile || file.length() == 0L) return null
        return try {
            json.decodeFromString(serializer, file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

}
