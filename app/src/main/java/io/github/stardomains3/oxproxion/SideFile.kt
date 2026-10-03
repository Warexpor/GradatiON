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

    /**
     * Test hook. After the new bytes are durable and before they replace an existing side
     * file, throw and leave that side file as a killed process would. Cleared when it fires.
     */
    @androidx.annotation.VisibleForTesting
    internal var failAfterIncomingForTest: Boolean = false

    /**
     * Test hook. [clear] returns after the side files are gone and before the
     * installed file is removed, as a kill in that window would. Cleared when it fires.
     */
    @androidx.annotation.VisibleForTesting
    internal var stopAfterClearingSidesForTest: Boolean = false

    fun write(dest: File, bytes: ByteArray) {
        val dir = dest.parentFile ?: throw IOException("no directory")
        dir.mkdirs()
        val partial = sibling(dest, ".partial")
        // The bytes go to a new file. Opening [partial] for write truncates it first, and a
        // kill there used to drop the finished side file that the next read was still using.
        val incoming = sibling(dest, ".partial.incoming")
        try {
            incoming.delete()
            FileOutputStream(incoming).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            syncDirectory(dir)
            if (failAfterIncomingForTest) {
                failAfterIncomingForTest = false
                incoming.delete()
                throw IOException("simulated side-file write failure")
            }
            // A clock that did not tick would make this look older than the file it replaces.
            val floor = maxOf(
                dest.lastModified(),
                sibling(dest, ".bak").lastModified(),
                partial.lastModified(),
            )
            if (incoming.lastModified() <= floor) incoming.setLastModified(floor + 1)
            if (!incoming.renameTo(partial)) {
                throw IOException("Could not replace ${partial.path}")
            }
        } catch (e: Exception) {
            incoming.delete()
            if (e is IOException) throw e
            throw IOException("Could not write ${partial.path}", e)
        }
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
                        // Same timestamp: the side file is the replacement that did not get a
                        // newer stamp. Preferring the installed file used to hide it.
                        when (it) {
                            partial -> 0
                            dest -> 1
                            else -> 2
                        }
                    }
            )
    }

    fun clear(dest: File) {
        // Side files first. Removing the installed notes first used to leave an older
        // side file, and the next read applied those notes.
        sibling(dest, ".partial").delete()
        sibling(dest, ".partial.incoming").delete()
        sibling(dest, ".bak").delete()
        if (stopAfterClearingSidesForTest) {
            stopAfterClearingSidesForTest = false
            return
        }
        dest.delete()
    }

    private fun sibling(dest: File, suffix: String) = File(dest.parentFile, dest.name + suffix)

    private fun syncDirectory(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
        }
    }
}
