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

    @Test fun open_thread_falls_back_to_parked_presence() {
        val parked = photo("file://parked.jpg")
        val liveEmpty = ComposerStaged.presence(
            livePhoto = false,
            liveAudio = false,
            liveFileCount = 0,
            parked = parked,
        )
        assertTrue(liveEmpty.hasPhoto)
        assertFalse(liveEmpty.hasAudio)
        assertEquals(0, liveEmpty.fileCount)
        val liveWins = ComposerStaged.presence(
            livePhoto = false,
            liveAudio = true,
            liveFileCount = 0,
            parked = parked,
        )
        assertFalse(liveWins.hasPhoto)
        assertTrue(liveWins.hasAudio)
    }

    @Test fun late_merges_keep_other_parked_parts() {
        val base = ComposerStaged.Entry(
            imageUri = "file://a.jpg",
            files = listOf(ComposerStaged.FilePart("a.txt", "a", 1)),
        )
        val withPhoto = ComposerStaged.withPhoto(base, null, "image/jpeg", "file://b.jpg")
        assertEquals("file://b.jpg", withPhoto.imageUri)
        assertEquals(1, withPhoto.files.size)
        val withFile = ComposerStaged.withFile(base, ComposerStaged.FilePart("b.txt", "b", 1))
        assertEquals("file://a.jpg", withFile.imageUri)
        assertEquals(2, withFile.files.size)
        val withAudio = ComposerStaged.withAudio(base, byteArrayOf(1), "wav")
        assertNull(withAudio.imageUri)
        assertEquals("wav", withAudio.audioFormat)
        assertEquals(1, withAudio.files.size)
    }

    @Test fun live_to_park_skips_empty_so_code_activate_keeps_parked() {
        // Away / pairing activate Code without enterCodeMode; an empty live stage must not
        // wipe a chip already in the park map (leaveCodeMode apply would then restore nothing).
        assertNull(ComposerStaged.liveToPark(ComposerStaged.Entry()))
        val live = photo("file://live.jpg")
        assertEquals("file://live.jpg", ComposerStaged.liveToPark(live)!!.imageUri)
        val parked = ComposerStaged.remember(emptyMap(), 4L, photo("file://parked.jpg"))
        val keep = ComposerStaged.liveToPark(ComposerStaged.Entry())
        assertNull(keep)
        assertEquals("file://parked.jpg", ComposerStaged.get(parked, 4L).imageUri)
    }
}
