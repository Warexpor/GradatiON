package io.github.stardomains3.oxproxion

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Memory, layout, voice, lore pin, wallpaper and portrait for a character import.
 * Written inside the database transaction, before it commits. The next launch applies it
 * when that launch is the one that died before the preference commit. A row that is not
 * this character is dropped, so a rolled-back import cannot attach the notes to the next id.
 */
internal object CharacterImportSideLog {
    private const val TAG = "CharacterImportSideLog"
    private const val NAME = "character-import-side.json"
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ImportedCharacterNote.serializer())

    fun file(context: Context): File = File(ChatDbVault.directory(context), NAME)

    fun matches(note: ImportedCharacterNote, character: RpCharacter?): Boolean {
        if (character == null) return false
        if (note.name != character.name) return false
        if (note.exportKey.isNotBlank() && note.exportKey != character.exportKey) return false
        return true
    }

    /**
     * Applies a leftover log. True when there was nothing to do, or the notes were committed.
     * False when a matching row is still waiting. Pictures are written after the preference
     * commit; a kill there leaves the log so the next launch tries the pictures again.
     */
    suspend fun resume(context: Context, db: AppDatabase): Boolean {
        val notes = synchronized(lock) { read(file(context)) } ?: return true
        val dao = db.rpDao()
        val accepted = notes.filter { matches(it, dao.getCharacterById(it.id)) }
        if (accepted.size != notes.size) {
            synchronized(lock) {
                if (accepted.isEmpty()) clear(file(context))
                else write(file(context), accepted)
            }
            if (accepted.isEmpty()) return true
        }
        val prefs = SharedPreferencesHelper(context)
        val saved = RpCharacterPrefsBackup.applyAll(
            prefs,
            accepted.map { it.id to it.exported },
            dao.getAllLorebooksOnce(),
        )
        if (!saved) {
            Log.e(TAG, "Imported character notes are still waiting")
            return false
        }
        for (note in accepted) {
            restorePictures(context, dao, note)
        }
        clear(file(context))
        return true
    }

    fun write(dest: File, notes: List<ImportedCharacterNote>) {
        synchronized(lock) {
            SideFile.write(dest, json.encodeToString(serializer, notes).toByteArray(Charsets.UTF_8))
        }
    }

    fun clear(dest: File) {
        synchronized(lock) { SideFile.clear(dest) }
    }

    fun read(dest: File): List<ImportedCharacterNote>? {
        for (candidate in SideFile.candidates(dest)) {
            decode(candidate)?.let { return it }
        }
        return null
    }

    private suspend fun restorePictures(context: Context, dao: RpDao, note: ImportedCharacterNote) {
        try {
            RpWallpaperBackup.apply(context, note.id, note.exported.wallpaperBase64)
            val encoded = note.exported.avatarBase64?.takeIf { it.isNotBlank() } ?: return
            val photoUri = RpAvatarStorage.saveFromBase64(context, encoded, note.id) ?: return
            dao.getCharacterById(note.id)?.let { character ->
                if (character.photoUri != photoUri) {
                    dao.updateCharacter(character.copy(photoUri = photoUri))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Character picture skipped", e)
        }
    }

    private fun decode(file: File): List<ImportedCharacterNote>? {
        if (!file.isFile || file.length() == 0L) return null
        return try {
            json.decodeFromString(serializer, file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
internal data class ImportedCharacterNote(
    val id: Long,
    val name: String,
    val exportKey: String,
    val exported: RpCharacterExport,
)
