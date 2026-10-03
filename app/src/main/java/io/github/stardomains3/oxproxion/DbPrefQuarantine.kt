package io.github.stardomains3.oxproxion

import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * Notes kept outside the chat database, but keyed by a Room id: a chat's facts, pin, fork and
 * unsent line, and a character's Memory, layout, voice, lore pin, portrait and wallpaper.
 *
 * Room does not reuse an id inside one file. A fresh file after a failed open starts at 1 again.
 * Leaving these keys in place used to attach the previous chat's notes to the next one.
 * The values are renamed, not deleted. Portrait files move next to the set-aside database,
 * including a `.bak` or `.partial` left by a killed replace. Those side files are a finished
 * picture: the next open would put them back on the live name, and a fresh database reuses the id.
 */
internal object DbPrefQuarantine {
    private const val TAG = "DbPrefQuarantine"
    private const val UNREADABLE = ".unreadable"
    private const val LLM_SUFFIX = "_llm"

    /** Prefix of a key already set aside. Live reads do not use it. */
    internal const val ASIDE_MARK = "aside."

    private val CHAR_JPEG = Regex("^char_[0-9]{1,16}\\.jpg$")

    /**
     * A killed replace leaves the finished bytes beside the live name.
     * [ScenePhoto.recover] puts that file back, so a fresh database would show the old
     * portrait or wallpaper on the reused Room id.
     */
    private val CHAR_JPEG_SIDE = Regex(
        "^char_[0-9]{1,16}\\.jpg\\.(bak|partial|partial\\.incoming)$"
    )

    private val EXACT = setOf(
        "pinned_session_ids",
        "ask_composer_drafts",
        "rp_draft_session_ask",
        "rp_draft_session_rp",
        "rp_active_character_id",
        "rp_deleted_char_remap",
        "rp_pending_instruct",
    )

    private val PREFIXES = listOf(
        "chat_fork_",
        "rp_swipe_",
        "rp_facts_",
        "rp_memory_",
        "rp_layout_",
        "rp_voice_",
        "rp_lorebook_",
        "bg_photo_version_char_",
    )

    internal fun asideKey(stamp: Long, key: String) = "$ASIDE_MARK$stamp.$key"

    /** True for a preference that stores a chat or character row id. An archived key is not. */
    internal fun isRowScoped(key: String): Boolean {
        if (key.startsWith(ASIDE_MARK)) return false
        val base = if (key.endsWith(UNREADABLE)) key.removeSuffix(UNREADABLE) else key
        // GradatiON-as-a-character is not a Room row. Its note survives a fresh database.
        if (base.endsWith(LLM_SUFFIX)) return false
        if (base in EXACT) return true
        return PREFIXES.any { base.startsWith(it) }
    }

    /**
     * Moves row-scoped preferences and character pictures aside.
     * Returns false only when the preference edit did not commit. Nothing is removed in that case.
     * A stamp that already holds one of these keys uses the next free one, so a second recovery
     * does not overwrite the first archive.
     */
    fun quarantine(prefs: SharedPreferences, filesDir: File, vault: File, stamp: Long): Boolean {
        val snapshot = HashMap(prefs.all)
        val keys = snapshot.keys.filter { isRowScoped(it) }
        val chosen = freeStamp(prefs, keys, stamp)
        moveCharacterFiles(File(filesDir, "rp_avatars"), File(vault, "aside-$chosen/rp_avatars"))
        moveCharacterFiles(File(filesDir, "backgrounds"), File(vault, "aside-$chosen/backgrounds"))
        if (keys.isEmpty()) return true
        val editor = prefs.edit()
        var changed = false
        for (key in keys) {
            val dest = asideKey(chosen, key)
            if (prefs.contains(dest)) continue
            if (!putCopy(editor, dest, snapshot[key])) continue
            editor.remove(key)
            changed = true
        }
        if (!changed) return true
        return editor.commit()
    }

    private fun freeStamp(prefs: SharedPreferences, keys: List<String>, stamp: Long): Long {
        if (keys.isEmpty()) return stamp
        var s = stamp
        while (keys.any { prefs.contains(asideKey(s, it)) }) {
            if (s == Long.MAX_VALUE) return stamp
            s++
        }
        return s
    }

    private fun putCopy(editor: SharedPreferences.Editor, key: String, value: Any?): Boolean {
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> {
                val strings = value.mapNotNull { it as? String }
                if (strings.size != value.size) return false
                editor.putStringSet(key, strings.toSet())
            }
            else -> return false
        }
        return true
    }

    private fun moveCharacterFiles(fromDir: File, destDir: File) {
        val files = fromDir.listFiles() ?: return
        for (file in files) {
            if (!file.isFile || !isCharacterPicture(file.name)) continue
            destDir.mkdirs()
            val dest = uniqueFile(destDir, file.name)
            try {
                ChatDbVault.moveReplacing(file, dest)
            } catch (e: Exception) {
                Log.e(TAG, "Could not move ${file.name} aside", e)
                parkInPlace(file)
            }
        }
    }

    private fun isCharacterPicture(name: String): Boolean =
        CHAR_JPEG.matches(name) || CHAR_JPEG_SIDE.matches(name)

    /** A name the live loaders do not open (`char_<id>.jpg` only). */
    private fun parkInPlace(file: File) {
        val parent = file.parentFile ?: return
        var n = 1
        var parked = File(parent, "${file.nameWithoutExtension}.set-aside.jpg")
        while (parked.exists()) {
            n++
            parked = File(parent, "${file.nameWithoutExtension}.set-aside-$n.jpg")
        }
        if (!file.renameTo(parked)) Log.e(TAG, "Could not park ${file.path}")
    }

    private fun uniqueFile(dir: File, name: String): File {
        val direct = File(dir, name)
        if (!direct.exists()) return direct
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        var alt = File(dir, "$stem.kept-$n$ext")
        while (alt.exists()) {
            n++
            alt = File(dir, "$stem.kept-$n$ext")
        }
        return alt
    }
}
