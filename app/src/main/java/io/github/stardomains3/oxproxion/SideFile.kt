package io.github.stardomains3.oxproxion

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * A small file replaced by writing a side file, syncing it, then renaming.
 * A kill leaves the side file or the previous file, not a half-written one under the real name.
 * [candidates] lists the real file and those side files, newest first, so a finished side file
 * is read even when the previous file is still there.
 */
internal object SideFile {
    private const val TAG = "SideFile"

    fun write(dest: File, bytes: ByteArray) {
        val dir = dest.parentFile ?: throw IOException("no directory")
        dir.mkdirs()
        val partial = sibling(dest, ".partial")
        FileOutputStream(partial).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        // A clock that did not tick would make this look older than the file it replaces.
        val floor = maxOf(dest.lastModified(), sibling(dest, ".bak").lastModified())
        if (partial.lastModified() <= floor) partial.setLastModified(floor + 1)
        syncDirectory(dir)
        val bak = sibling(dest, ".bak")
        if (dest.exists() && !dest.renameTo(bak)) {
            throw IOException("Could not replace ${dest.path}")
        }
        if (!partial.renameTo(dest)) {
            if (!dest.exists() && bak.exists()) bak.renameTo(dest)
            // Leave the side file. The next read still finds it, newer than the restored file.
            throw IOException("Could not install ${dest.path}")
        }
        if (bak.exists() && !bak.delete()) Log.w(TAG, "Could not remove ${bak.path}")
        syncDirectory(dir)
    }

    /**
     * The installed file, then a side file from a kill, newest first.
     * Empty files are skipped. A torn newest file is still listed; the caller tries the next.
     */
    fun candidates(dest: File): List<File> {
        val partial = sibling(dest, ".partial")
        val bak = sibling(dest, ".bak")
        return listOf(dest, partial, bak)
            .filter { it.isFile && it.length() > 0L }
            .sortedWith(
                compareByDescending<File> { it.lastModified() }
                    .thenBy {
                        when (it) {
                            dest -> 0
                            partial -> 1
                            else -> 2
                        }
                    }
            )
    }

    fun clear(dest: File) {
        dest.delete()
        sibling(dest, ".partial").delete()
        sibling(dest, ".bak").delete()
    }

    private fun sibling(dest: File, suffix: String) = File(dest.parentFile, dest.name + suffix)

    private fun syncDirectory(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
        }
    }
}
