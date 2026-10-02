package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerStagedTest {

    private fun photo(uri: String = "file://a.jpg") = ComposerStaged.Entry(
        imageBytes = byteArrayOf(1, 2, 3),
        imageMime = "image/jpeg",
        imageUri = uri,
    )

    @Test fun leaving_a_chat_parks_the_photo_and_coming_back_restores_it() {
        val parked = ComposerStaged.remember(emptyMap(), 4L, photo("file://chat4.jpg"))
        assertEquals("file://chat4.jpg", ComposerStaged.get(parked, 4L).imageUri)
        assertTrue(ComposerStaged.get(parked, 5L).isEmpty)
        val cleared = ComposerStaged.remember(parked, 4L, ComposerStaged.Entry())
        assertTrue(ComposerStaged.get(cleared, 4L).isEmpty)
    }

    @Test fun a_minted_id_keeps_the_unsaved_photo() {
        val parked = ComposerStaged.remember(emptyMap(), null, photo("file://new.jpg"))
        val moved = ComposerStaged.rekey(parked, from = null, to = 8L, entry = photo("file://new.jpg"))
        assertTrue(ComposerStaged.get(moved, null).isEmpty)
        assertEquals("file://new.jpg", ComposerStaged.get(moved, 8L).imageUri)
    }

    @Test fun deleting_a_chat_drops_its_staged_photo() {
        val parked = ComposerStaged.remember(
            ComposerStaged.remember(emptyMap(), null, photo("file://new.jpg")),
            4L,
            photo("file://chat4.jpg"),
        )
        val gone = ComposerStaged.drop(parked, 4L)
        assertTrue(ComposerStaged.get(gone, 4L).isEmpty)
        assertEquals("file://new.jpg", ComposerStaged.get(gone, null).imageUri)
        assertEquals(parked.keys.toList(), ComposerStaged.drop(parked, 9L).keys.toList())
    }

    @Test fun oldest_attachments_fall_off() {
        var store: Map<String, ComposerStaged.Entry> = emptyMap()
        for (id in 1..ComposerDrafts.MAX_KEPT + 3) {
            store = ComposerStaged.remember(store, id.toLong(), photo("file://$id.jpg"))
        }
        assertEquals(ComposerDrafts.MAX_KEPT, store.size)
        assertFalse(store.containsKey("1"))
        assertEquals("file://${ComposerDrafts.MAX_KEPT + 3}.jpg", ComposerStaged.get(store, (ComposerDrafts.MAX_KEPT + 3).toLong()).imageUri)
    }

    @Test fun eviction_lists_the_photos_that_fell_off() {
        val before = ComposerStaged.remember(emptyMap(), 1L, photo("file://1.jpg"))
        var store = before
        for (id in 2..ComposerDrafts.MAX_KEPT + 1) {
            store = ComposerStaged.remember(store, id.toLong(), photo("file://$id.jpg"))
        }
        val gone = ComposerStaged.evicted(before, store)
        assertEquals(1, gone.size)
        assertEquals("file://1.jpg", gone.single().imageUri)
        assertTrue(ComposerStaged.evicted(store, store).isEmpty())
    }

    @Test fun files_and_audio_park_with_the_photo() {
        val entry = ComposerStaged.Entry(
            audioBytes = byteArrayOf(9),
            audioFormat = "wav",
            files = listOf(ComposerStaged.FilePart("notes.txt", "hi", 2)),
        )
        val parked = ComposerStaged.remember(emptyMap(), 2L, entry)
        val got = ComposerStaged.get(parked, 2L)
        assertEquals("wav", got.audioFormat)
        assertEquals(1, got.files.size)
        assertEquals("notes.txt", got.files[0].fileName)
        assertNull(got.imageUri)
    }
}
