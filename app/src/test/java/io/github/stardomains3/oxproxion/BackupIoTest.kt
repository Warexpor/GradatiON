package io.github.stardomains3.oxproxion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.OutputStream

/**
 * Run: ./gradlew :app:testDebugUnitTest --tests '*BackupIoTest*'
 */
class BackupIoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun copyAllBytesThrowsWhenTheDestinationStopsEarly() {
        val source = tmp.newFile("src.json").apply { writeText("{\"sessions\":[]}") }
        val dest = object : OutputStream() {
            var written = 0
            override fun write(b: Int) {
                if (written >= 4) throw IOException("disk full")
                written++
            }
        }

        assertThrows(IOException::class.java) { BackupIo.copyAllBytes(source, dest) }
    }

    @Test
    fun copyAllBytesWritesTheWholeFile() {
        val source = tmp.newFile("src.json").apply { writeText("{\"ok\":true}") }
        val dest = tmp.newFile("out.json")

        dest.outputStream().use { BackupIo.copyAllBytes(source, it) }

        assertEquals("{\"ok\":true}", dest.readText())
    }

    @Test
    fun publishDoesNotOpenTheDestinationWhenWritingTheCacheFails() {
        val cache = tmp.newFile("cache.json")
        var opened = false

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                BackupIo.publish(cache, {
                    opened = true
                    tmp.newFile("dest.json").outputStream()
                }) {
                    throw IllegalStateException("encode failed")
                }
            }
        }

        assertFalse(opened)
        assertFalse(cache.exists())
    }

    @Test
    fun publishCopiesOnlyAfterTheCacheIsComplete() {
        val cache = tmp.newFile("cache.json")
        val dest = tmp.newFile("dest.json")

        runBlocking {
            BackupIo.publish(cache, { dest.outputStream() }) { stream ->
                stream.writer(Charsets.UTF_8).use { it.write("{\"characters\":[]}") }
            }
        }

        assertEquals("{\"characters\":[]}", dest.readText())
        assertFalse(cache.exists())
    }
}
