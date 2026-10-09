package io.github.stardomains3.oxproxion

import android.content.Context
import android.util.Base64
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

    /**
     * Test hook. The next [restorePictures] returns false once, as a failed wallpaper or
     * portrait write would. Cleared when it fires.
     */
    @androidx.annotation.VisibleForTesting
    internal var failPictureRestoreForTest: Boolean = false

    /**
     * Test hook. Runs once after Memory/layout/voice are committed and before pictures,
     * so a test can write another note into the log the way a concurrent import would.
     */
    @androidx.annotation.VisibleForTesting
    internal var afterPrefsAppliedForTest: (() -> Unit)? = null

    fun file(context: Context): File = File(ChatDbVault.directory(context), NAME)

    fun matches(note: ImportedCharacterNote, character: RpCharacter?): Boolean {
        if (character == null) return false
        // The side log is written before the database commit. A kill there used to apply
        // Memory and pictures onto the card that rolled back, because the export key still
        // matched. The row keeps its old stamp when the commit did not land. A later edit
        // moves the stamp, so a portrait that is still waiting is not dropped.
        if (note.writtenUpdatedAt != 0L &&
            note.previousUpdatedAt != note.writtenUpdatedAt &&
            character.updatedAt == note.previousUpdatedAt
        ) {
            return false
        }
        // exportKey is the stable identity. A rename (or a pack that changes the display
        // name) while pictures are still waiting must not drop the portrait forever.
        if (note.exportKey.isNotBlank()) return note.exportKey == character.exportKey
        // Old log without a key: the name must still match so a recycled id cannot inherit.
        return note.name == character.name
    }

    /**
     * One note per imported row. A blank name is not a row, so it is skipped here the same
     * way the import skips it. Pairing by the raw file index attached the blank row's
     * pictures to the next character.
     */
    fun notesFor(
        rows: List<ImportedCharacter>,
        incoming: List<RpCharacterExport>,
    ): List<ImportedCharacterNote> {
        val exported = incoming.filter { it.name.trim().isNotEmpty() }
        return rows.mapIndexedNotNull { index, row ->
            val ex = exported.getOrNull(index) ?: return@mapIndexedNotNull null
            ImportedCharacterNote(
                id = row.id,
                name = ex.name,
                exportKey = row.exportKey,
                exported = ex,
                writtenUpdatedAt = row.writtenUpdatedAt,
                previousUpdatedAt = row.previousUpdatedAt,
            )
        }
    }

    /**
     * Applies a leftover log. True when there was nothing to do, or the notes and pictures
     * were committed. False when a matching row is still waiting. Pictures are written after
     * the preference commit; a failed write leaves those rows in the log so the next launch
     * tries the pictures again.
     */
    suspend fun resume(context: Context, db: AppDatabase): Boolean {
        val dest = file(context)
        val raw = synchronized(lock) { read(dest) } ?: return true
        // The same export key twice in one file is one character. The last copy is the row
        // that was saved. Leaving the earlier copy in the log re-applied its Memory and
        // pictures on the next launch, over the copy that won.
        val snapshot = raw.groupBy { it.id }.map { (_, notes) -> notes.last() }
        val dao = db.rpDao()
        val accepted = snapshot.filter { matches(it, dao.getCharacterById(it.id)) }
        val acceptedIds = accepted.map { it.id }.toSet()
        if (accepted.isEmpty()) {
            // Drop only the rejected snapshot rows. A concurrent import may have added others.
            reconcile(dest, raw, doneIds = emptySet(), acceptedIds = emptySet())
            return true
        }
        val prefs = SharedPreferencesHelper(context)
        val saved = RpCharacterPrefsBackup.applyAll(
            prefs,
            accepted.map { it.id to it.exported },
            dao.getAllLorebooksOnce(),
        )
        if (!saved) {
            Log.e(TAG, "Imported character notes are still waiting")
            // Still drop rejected snapshot rows; leave accepted for the next launch.
            reconcile(dest, raw, doneIds = emptySet(), acceptedIds = acceptedIds)
            return false
        }
        afterPrefsAppliedForTest?.let { hook ->
            afterPrefsAppliedForTest = null
            hook()
        }
        val doneIds = HashSet<Long>()
        for (note in accepted) {
            if (restorePictures(context, dao, note)) doneIds += note.id
        }
        val waiting = acceptedIds - doneIds
        reconcile(dest, raw, doneIds = doneIds, acceptedIds = acceptedIds)
        if (waiting.isNotEmpty()) {
            Log.e(TAG, "Imported character pictures are still waiting")
            return false
        }
        return true
    }

    /**
     * Removes snapshot rows that this resume finished or rejected, without wiping notes a
     * concurrent import wrote (or a newer note that replaced one we finished).
     */
    private fun reconcile(
        dest: File,
        snapshot: List<ImportedCharacterNote>,
        doneIds: Set<Long>,
        acceptedIds: Set<Long>,
    ) {
        synchronized(lock) {
            // Every version of an id from this snapshot, not only the last. associateBy would
            // keep a duplicate that is not equal to the winner, and the next launch would
            // apply that older copy.
            val versionsById = snapshot.groupBy { it.id }
            val current = read(dest).orEmpty()
            val still = current.filter { note ->
                val versions = versionsById[note.id] ?: return@filter true
                if (versions.none { it == note }) return@filter true
                when {
                    note.id in doneIds -> false
                    note.id !in acceptedIds -> false
                    else -> true
                }
            }
            if (still.isEmpty()) clear(dest)
            else if (still != current) write(dest, still)
        }
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

    /**
     * True when the wallpaper and portrait from [note] are on disk (or the backup did not
     * carry a usable picture). False when a finished JPEG still could not be written.
     */
    private suspend fun restorePictures(context: Context, dao: RpDao, note: ImportedCharacterNote): Boolean {
        if (failPictureRestoreForTest) {
            failPictureRestoreForTest = false
            return false
        }
        try {
            when (val action = RpWallpaperBackup.restore(note.exported.wallpaperBase64)) {
                RpWallpaperBackup.Restore.Leave -> Unit
                RpWallpaperBackup.Restore.Clear -> {
                    val slot = BackgroundPhoto.slotForCharacter(note.id)
                    if (BackgroundPhoto.hasPhoto(context, slot)) {
                        BackgroundPhoto.delete(context, slot)
                    }
                    // A delete that does not land, or a side file recover() puts back, would
                    // leave the picture after this note was dropped. Retry, as a portrait clear does.
                    if (BackgroundPhoto.hasPhoto(context, slot)) {
                        Log.w(TAG, "Character wallpaper still waiting")
                        return false
                    }
                }
                is RpWallpaperBackup.Restore.Write -> {
                    val slot = BackgroundPhoto.slotForCharacter(note.id)
                    // prepare fails for undecodeable bytes; do not fall back to the raw
                    // stub and retry forever the way a short SOI/EOI portrait used to.
                    val jpeg = BackgroundPhoto.prepare(action.jpeg)
                    if (jpeg == null) {
                        Log.w(TAG, "Character wallpaper in the backup is not a picture; leaving it")
                    } else if (!BackgroundPhoto.writeBytes(context, slot, jpeg)) {
                        Log.w(TAG, "Character wallpaper still waiting")
                        return false
                    }
                }
            }
            val encoded = note.exported.avatarBase64 ?: return true
            if (encoded.isBlank()) {
                // Empty means the portrait was removed. Null (absent) left it alone above.
                RpAvatarStorage.deleteAvatar(context, note.id)
                if (RpAvatarStorage.hasAvatar(context, note.id)) {
                    Log.w(TAG, "Character portrait still waiting")
                    return false
                }
                dao.getCharacterById(note.id)?.let { character ->
                    if (!character.photoUri.isNullOrBlank()) {
                        dao.updateCharacter(character.copy(photoUri = null))
                    }
                }
                return true
            }
            val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
            // A PNG card carries its own PNG as the portrait; it is re-encoded to JPEG on save.
            if (bytes == null || bytes.isEmpty() || !(ScenePhoto.completeJpeg(bytes) || RpCardImport.isPng(bytes))) {
                Log.w(TAG, "Character portrait in the backup is not a picture; leaving it")
                return true
            }
            // SOI/EOI alone (or any tiny stub) is not a portrait. Robolectric's BitmapFactory
            // can invent bounds for junk, so size is checked before a decode probe.
            if (bytes.size < 64) {
                Log.w(TAG, "Character portrait in the backup is not a picture; leaving it")
                return true
            }
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "Character portrait in the backup is not a picture; leaving it")
                return true
            }
            // A finished file already on disk is not success. saveFromBase64 used to return
            // null for a bad picture and for a write that did not replace that file, and the
            // old file made the import give up on the new one.
            when (val saved = RpAvatarStorage.saveFromBase64Result(context, encoded, note.id)) {
                is RpAvatarStorage.SaveResult.Saved -> {
                    dao.getCharacterById(note.id)?.let { character ->
                        if (character.photoUri != saved.uri) {
                            dao.updateCharacter(character.copy(photoUri = saved.uri))
                        }
                    }
                    return true
                }
                RpAvatarStorage.SaveResult.NotAPicture -> {
                    Log.w(TAG, "Character portrait in the backup is not a picture; leaving it")
                    return true
                }
                RpAvatarStorage.SaveResult.Failed -> {
                    Log.w(TAG, "Character portrait still waiting")
                    return false
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Character picture skipped", e)
            return false
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
    /** 0 on a note written before the import stamp existed. Those still match by export key. */
    val writtenUpdatedAt: Long = 0,
    val previousUpdatedAt: Long = 0,
)
