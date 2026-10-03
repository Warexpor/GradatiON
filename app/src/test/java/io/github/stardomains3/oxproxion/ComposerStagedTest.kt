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

    @Test fun park_live_skips_empty_so_switch_under_code_keeps_parked() {
        // Code cleared the live chip into the map; switching chats must not remember empty
        // for the chat you leave (that would drop the JPEG History / leaveCodeMode need).
        val parked = ComposerStaged.remember(emptyMap(), 4L, photo("file://chat4.jpg"))
        val kept = ComposerStaged.parkLive(parked, 4L, ComposerStaged.Entry())
        assertEquals("file://chat4.jpg", ComposerStaged.get(kept, 4L).imageUri)
        val moved = ComposerStaged.parkLive(parked, 4L, photo("file://fresh.jpg"))
        assertEquals("file://fresh.jpg", ComposerStaged.get(moved, 4L).imageUri)
    }

    @Test fun promote_keeps_parked_when_live_empty() {
        // Unsaved chat staged a photo, Code parked it under null, then first save promotes.
        // Empty live must move the map entry onto the new id — not rekey empty (evict JPEG).
        val parked = ComposerStaged.remember(emptyMap(), null, photo("file://new.jpg"))
        val promoted = ComposerStaged.promote(parked, from = null, to = 8L, live = ComposerStaged.Entry())
        assertTrue(ComposerStaged.get(promoted, null).isEmpty)
        assertEquals("file://new.jpg", ComposerStaged.get(promoted, 8L).imageUri)
        val liveWins = ComposerStaged.promote(parked, from = null, to = 8L, live = photo("file://fresh.jpg"))
        assertEquals("file://fresh.jpg", ComposerStaged.get(liveWins, 8L).imageUri)
        assertTrue(ComposerStaged.promote(emptyMap(), from = null, to = 8L, live = ComposerStaged.Entry()).isEmpty())
    }

    @Test fun promote_does_not_evict_the_jpeg_it_just_moved() {
        // First save under Code rekeys null → id. Key-only eviction treated that as a drop,
        // and the fragment deleted the scene file History still shows as Photo.
        val before = ComposerStaged.remember(emptyMap(), null, photo("file://new.jpg"))
        val after = ComposerStaged.promote(before, from = null, to = 8L, live = ComposerStaged.Entry())
        assertEquals("file://new.jpg", ComposerStaged.get(after, 8L).imageUri)
        assertTrue(ComposerStaged.evicted(before, after).isEmpty())
        // A copied entry (same uri, not the same object) is still the file the new id holds.
        val copied = ComposerStaged.rekey(before, from = null, to = 8L, entry = photo("file://new.jpg"))
        assertTrue(ComposerStaged.evicted(before, copied).isEmpty())
        // Audio-only has no uri; the same entry moving keys is not a drop either.
        val clip = ComposerStaged.Entry(audioBytes = byteArrayOf(9), audioFormat = "wav")
        val audioBefore = ComposerStaged.remember(emptyMap(), null, clip)
        val audioAfter = ComposerStaged.promote(audioBefore, from = null, to = 8L, live = ComposerStaged.Entry())
        assertEquals("wav", ComposerStaged.get(audioAfter, 8L).audioFormat)
        assertTrue(ComposerStaged.evicted(audioBefore, audioAfter).isEmpty())
    }

    @Test fun replacing_a_parked_photo_evicts_only_the_old_file() {
        // Same key, new uri: the previous JPEG is no longer parked (audio replaces a photo,
        // or a second pick). A re-park of the same uri must not look evicted.
        val before = ComposerStaged.remember(emptyMap(), 4L, photo("file://a.jpg"))
        val replaced = ComposerStaged.remember(before, 4L, photo("file://b.jpg"))
        assertEquals(listOf("file://a.jpg"), ComposerStaged.evicted(before, replaced).map { it.imageUri })
        val audio = ComposerStaged.remember(
            before,
            4L,
            ComposerStaged.Entry(audioBytes = byteArrayOf(1), audioFormat = "wav"),
        )
        assertEquals(listOf("file://a.jpg"), ComposerStaged.evicted(before, audio).map { it.imageUri })
        val same = ComposerStaged.remember(before, 4L, photo("file://a.jpg"))
        assertTrue(ComposerStaged.evicted(before, same).isEmpty())
        // Another chat still holding that uri keeps the file.
        val shared = ComposerStaged.remember(before, 9L, photo("file://a.jpg"))
        val dropped = ComposerStaged.remember(shared, 4L, ComposerStaged.Entry())
        assertTrue(ComposerStaged.evicted(shared, dropped).isEmpty())
    }
}
