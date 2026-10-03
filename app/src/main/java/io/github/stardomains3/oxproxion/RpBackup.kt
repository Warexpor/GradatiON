package io.github.stardomains3.oxproxion

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class RpCharacterBackup(
    val characters: List<RpCharacterExport>
)

@Serializable
data class RpCharacterExport(
    val name: String,
    val personality: String = "",
    val style: String = "",
    val greeting: String = "",
    val scenario: String = "",
    val examplesJson: String = "[]",
    val prompt: String = "",
    val instruction: String = "",
    /** Stable key used to rematch chats after re-import. */
    val exportKey: String = "",
    /** Embedded JPEG as Base64; preferred over [photoUri] for portable backups. */
    val avatarBase64: String? = null,
    /** Legacy local file:// path — ignored on import when [avatarBase64] is set. */
    val photoUri: String? = null,
    /**
     * Story memory. Null means a backup from before this field existed, which must not wipe
     * memory already on the phone. An empty string is a memory the user cleared.
     */
    val memory: String? = null,
    /** [SharedPreferencesHelper.RP_LAYOUT_CLASSIC] and the other layouts. Null leaves the local one. */
    val layout: String? = null,
    /** Null with no pitch or rate means the backup does not carry a voice. */
    val voiceName: String? = null,
    val voicePitch: Float? = null,
    val voiceRate: Float? = null,
    /**
     * Lorebook pinned to this character, by name. Null leaves the local pin. Empty clears it.
     * A name with no matching book is kept until that book is imported.
     */
    val lorebookName: String? = null,
    /**
     * This character's wallpaper, as a JPEG. Null means a backup from before this field,
     * which must not remove a picture already on the phone. Empty means no wallpaper.
     */
    val wallpaperBase64: String? = null
)

@Serializable
data class RpLorebookBackup(
    val lorebooks: List<RpLorebookExport>
)

@Serializable
data class RpLorebookExport(
    val name: String,
    val content: String = "",
    val isActive: Boolean = false
)

/** Writes one character or lorebook at a time so the backup is not also held as one string. */
internal object RpBackupWriter {
    private val json = Json { ignoreUnknownKeys = true }

    fun writeCharacters(out: Appendable, characters: List<RpCharacterExport>) {
        out.append("{\"characters\":[")
        characters.forEachIndexed { index, character ->
            if (index > 0) out.append(',')
            out.append(json.encodeToString(RpCharacterExport.serializer(), character))
        }
        out.append("]}")
    }

    fun writeLorebooks(out: Appendable, lorebooks: List<RpLorebookExport>) {
        out.append("{\"lorebooks\":[")
        lorebooks.forEachIndexed { index, book ->
            if (index > 0) out.append(',')
            out.append(json.encodeToString(RpLorebookExport.serializer(), book))
        }
        out.append("]}")
    }
}

/**
 * Memory, layout, voice and the lorebook pin live in preferences, keyed by the character's
 * Room id, so a backup of the card alone used to drop them on the next phone.
 */
internal object RpCharacterPrefsBackup {
    private const val TAG = "RpCharacterBackup"
    private val layouts = setOf(
        SharedPreferencesHelper.RP_LAYOUT_CLASSIC,
        SharedPreferencesHelper.RP_LAYOUT_BUBBLES,
        SharedPreferencesHelper.RP_LAYOUT_BOOK,
    )

    fun apply(
        prefs: SharedPreferencesHelper,
        characterId: Long,
        exported: RpCharacterExport,
        lorebooks: List<RpLorebook>,
    ) {
        applyAll(prefs, listOf(characterId to exported), lorebooks)
    }

    /**
     * Every character in one commit. A kill between Memory and the voice used to keep one
     * and drop the other. Wallpaper and the portrait are files and follow this write.
     */
    fun applyAll(
        prefs: SharedPreferencesHelper,
        rows: List<Pair<Long, RpCharacterExport>>,
        lorebooks: List<RpLorebook>,
    ): Boolean {
        if (rows.isEmpty()) return true
        val editor = prefs.mainPrefs.edit()
        for ((characterId, exported) in rows) {
            write(editor, prefs, characterId, exported, lorebooks)
        }
        val saved = editor.commit()
        if (!saved) Log.e(TAG, "Character backup notes could not be saved")
        return saved
    }

    private fun write(
        editor: SharedPreferences.Editor,
        prefs: SharedPreferencesHelper,
        characterId: Long,
        exported: RpCharacterExport,
        lorebooks: List<RpLorebook>,
    ) {
        val memory = exported.memory?.take(RpPromptEngine.MEMORY_MAX_CHARS)
        val layout = exported.layout?.takeIf { it in layouts }
        // Pitch or rate present means this backup carries a voice. A missing name is the default
        // voice, not "leave whatever is already set".
        val voice = if (exported.voicePitch != null || exported.voiceRate != null) {
            val current = prefs.getRpVoice(characterId)
            SharedPreferencesHelper.RpVoice(
                name = exported.voiceName?.takeIf { it.isNotBlank() },
                pitch = exported.voicePitch?.takeIf { it.isFinite() && it in 0.25f..4f } ?: current.pitch,
                rate = exported.voiceRate?.takeIf { it.isFinite() && it in 0.25f..4f } ?: current.rate,
            )
        } else {
            null
        }
        var lorebookId: Long? = null
        var clearLorebook = false
        var pending: String? = null
        var clearPending = false
        when {
            exported.lorebookName == null -> Unit
            exported.lorebookName.isBlank() -> {
                clearLorebook = true
                clearPending = true
            }
            else -> {
                val wanted = pinName(exported.lorebookName)
                val match = lorebooks.firstOrNull { pinName(it.name).equals(wanted, ignoreCase = true) }
                if (match != null) {
                    lorebookId = match.id
                    clearPending = true
                } else {
                    pending = wanted
                }
            }
        }
        prefs.writeCharacterBackupFields(
            editor,
            characterId,
            memory = memory,
            layout = layout,
            voice = voice,
            lorebookId = lorebookId,
            clearLorebook = clearLorebook,
            pendingLorebook = pending,
            clearPendingLorebook = clearPending,
        )
    }

    /** After a lorebook import, attach pins that were waiting for a book that was not here yet. */
    fun bindPending(prefs: SharedPreferencesHelper, lorebooks: List<RpLorebook>) {
        for ((characterId, name) in prefs.pendingRpLorebookNames()) {
            bindLorebook(prefs, characterId, name, lorebooks)
        }
    }

    /**
     * Pin names are compared trimmed and capped. A backup used to store the first 200
     * characters and then look for the full book name, so a long title never attached.
     * Spaces around the name missed the book the editor saved.
     */
    private fun pinName(name: String) = name.trim().take(200)

    private fun bindLorebook(
        prefs: SharedPreferencesHelper,
        characterId: Long,
        name: String,
        lorebooks: List<RpLorebook>,
    ) {
        val wanted = pinName(name)
        val match = lorebooks.firstOrNull { pinName(it.name).equals(wanted, ignoreCase = true) }
        if (match != null) {
            prefs.saveRpLorebookId(characterId, match.id)
            prefs.savePendingRpLorebookName(characterId, null)
        } else if (wanted != name) {
            // Already stored at the pin key. Rewriting it on every launch would commit for nothing.
            prefs.savePendingRpLorebookName(characterId, wanted)
        }
    }
}

/**
 * A character's wallpaper is a file beside the card, keyed by the Room id, so a backup of the
 * card alone used to leave it behind on the next phone.
 */
internal object RpWallpaperBackup {
    /** Larger than this and the backup leaves the phone's copy alone instead of dropping it. */
    private const val MAX_BYTES = 2_000_000

    /** Empty when there is no picture. Null when the file cannot be carried. */
    fun encode(file: File): String? {
        ScenePhoto.recover(file)
        if (!file.isFile || file.length() == 0L) return ""
        // A half-written wallpaper is not a picture; leave the phone's copy alone.
        if (!ScenePhoto.completeJpeg(file)) return null
        if (file.length() > MAX_BYTES) return null
        return try {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun apply(context: Context, characterId: Long, encoded: String?) {
        val slot = BackgroundPhoto.slotForCharacter(characterId)
        when (val action = restore(encoded)) {
            Restore.Leave -> Unit
            Restore.Clear -> if (BackgroundPhoto.hasPhoto(context, slot)) BackgroundPhoto.delete(context, slot)
            is Restore.Write -> {
                // A backup can carry a camera JPEG. Store the same upright, capped picture a
                // pick would, so the chat does not decode the full file on every open.
                // prepare fails for undecodeable bytes; do not fall back to the raw stub
                // (CharacterImportSideLog already leaves those).
                val jpeg = BackgroundPhoto.prepare(action.jpeg)
                if (jpeg != null) BackgroundPhoto.writeBytes(context, slot, jpeg)
            }
        }
    }

    internal sealed class Restore {
        data object Leave : Restore()
        data object Clear : Restore()
        data class Write(val jpeg: ByteArray) : Restore()
    }

    internal fun restore(encoded: String?): Restore {
        if (encoded == null) return Restore.Leave
        if (encoded.isBlank()) return Restore.Clear
        if (encoded.length > MAX_BYTES * 2) return Restore.Leave
        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return Restore.Leave
        }
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return Restore.Leave
        // Incomplete or SOI/EOI-only stubs are not a wallpaper. BitmapFactory can still
        // invent bounds for junk (and a truncated file with SOF), which used to return
        // Write and then retry forever when writeBytes refused the incomplete JPEG.
        if (!ScenePhoto.completeJpeg(bytes) || bytes.size < 64) return Restore.Leave
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Robolectric's decoder throws IIOException on a padded SOI/EOI stub instead of
        // reporting empty bounds. That is still not a wallpaper.
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        } catch (_: Throwable) {
            return Restore.Leave
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Restore.Leave
        return Restore.Write(bytes)
    }
}
