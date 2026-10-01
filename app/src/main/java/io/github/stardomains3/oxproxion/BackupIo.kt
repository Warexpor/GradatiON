package io.github.stardomains3.oxproxion

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Writes a backup to a cache file, syncs it, then copies it. The destination is not opened until
 * the cache file is complete, and a copy that stops early is an error rather than a success.
 */
internal object BackupIo {
    fun sync(file: File) {
        RandomAccessFile(file, "rw").use { it.fd.sync() }
    }

    fun copyAllBytes(source: File, dest: OutputStream) {
        val expected = source.length()
        var copied = 0L
        source.inputStream().buffered().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) throw IOException("Export read returned no bytes")
                dest.write(buf, 0, n)
                copied += n
            }
        }
        dest.flush()
        if (copied != expected) {
            throw IOException("Export wrote $copied of $expected bytes")
        }
    }

    /**
     * [openDest] runs only after [write] has finished and the cache file has been synced.
     * The cache file is removed afterwards, including when [write] or the copy fails.
     */
    fun publish(cache: File, openDest: () -> OutputStream?, write: (OutputStream) -> Unit) {
        try {
            cache.outputStream().buffered().use { write(it) }
            sync(cache)
            val dest = openDest() ?: throw IOException("Could not open the export file")
            dest.use { copyAllBytes(cache, it) }
        } finally {
            cache.delete()
        }
    }
}
