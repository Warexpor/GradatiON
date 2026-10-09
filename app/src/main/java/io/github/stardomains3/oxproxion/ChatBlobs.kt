package io.github.stardomains3.oxproxion

import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Per-chat blobs: a Roleplay chat's reply versions and a chat's other branch, one file each.
 * They grow with every chat. In the main preferences they made every launch read all of them
 * before the first screen drew, and every save rewrite all of them.
 *
 * A write is atomic (a kill leaves the old value or the new one, never half), so these keep the
 * guarantee the `commit = true` preference writes gave.
 */
internal class ChatBlobs(private val dir: File) {

    private fun file(key: String): AtomicFile {
        require(KEY.matches(key)) { "Not a blob key: $key" }
        return AtomicFile(File(dir, "$key$SUFFIX"))
    }

    fun get(key: String): String? = synchronized(LOCK) {
        try {
            String(file(key).readFully(), Charsets.UTF_8)
        } catch (_: FileNotFoundException) {
            null
        } catch (e: IOException) {
            Log.w(TAG, "Could not read $key", e)
            null
        }
    }

    fun put(key: String, value: String): Boolean = synchronized(LOCK) {
        dir.mkdirs()
        val target = file(key)
        var out: FileOutputStream? = null
        try {
            out = target.startWrite()
            out.write(value.toByteArray(Charsets.UTF_8))
            target.finishWrite(out)
            true
        } catch (e: IOException) {
            out?.let { target.failWrite(it) }
            Log.e(TAG, "Could not save $key", e)
            false
        }
    }

    fun remove(key: String) = synchronized(LOCK) { file(key).delete() }

    /** Every stored key, in no order. */
    fun keys(): List<String> = synchronized(LOCK) {
        dir.listFiles().orEmpty().mapNotNull { f ->
            // A leftover backup of an interrupted write still names its key.
            val name = f.name.removeSuffix(".bak")
            if (!name.endsWith(SUFFIX)) return@mapNotNull null
            name.removeSuffix(SUFFIX).takeIf { KEY.matches(it) }
        }.distinct()
    }

    /**
     * A fresh database reuses row ids. The blobs keyed by the old ids move to [dest], so a new
     * chat never inherits an old chat's versions or branch. False when the move failed.
     */
    fun setAside(dest: File): Boolean = synchronized(LOCK) {
        if (!dir.exists()) return true
        dest.parentFile?.mkdirs()
        if (dir.renameTo(dest)) return true
        Log.e(TAG, "Could not move chat blobs aside")
        false
    }

    companion object {
        private const val TAG = "ChatBlobs"
        private const val SUFFIX = ".json"
        private val KEY = Regex("^[a-z_]+[0-9]+$")
        private val LOCK = Any()
        const val DIR = "chat_blobs"
    }
}
