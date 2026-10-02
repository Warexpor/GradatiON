package io.github.stardomains3.oxproxion

import android.graphics.Bitmap
import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class ChatPhotoTest {

    @Test fun landscape_fits_the_long_edge() {
        assertEquals(156 to 94, ChatPhoto.frame(800, 480, 156, 156))
    }

    @Test fun portrait_fits_the_long_edge() {
        assertEquals(104 to 156, ChatPhoto.frame(800, 1200, 156, 156))
    }

    @Test fun small_picture_is_not_blown_up() {
        assertEquals(40 to 40, ChatPhoto.frame(40, 40, 240, 300))
    }

    @Test fun composer_sliver_grows_to_the_minimum_and_stays_inside_the_cap() {
        val (w, h) = ChatPhoto.frame(2000, 200, 156, 156, minEdge = 64)
        assertEquals(156, w)
        assertEquals(64, h)
    }

    @Test fun unknown_bounds_fall_back_to_a_square() {
        assertEquals(72 to 72, ChatPhoto.frame(0, 0, 72, 72))
    }

    @Test fun photo_with_caption_keeps_the_text_inset() {
        assertEquals(ChatPhoto.DpBox(4, 4, 4, 12), ChatPhoto.containerInsets(hasPhoto = true, photoOnly = false))
        assertEquals(ChatPhoto.DpBox(12, 0, 12, 0), ChatPhoto.captionInsets(hasPhoto = true, photoOnly = false))
    }

    @Test fun photo_alone_is_a_slim_frame() {
        assertEquals(ChatPhoto.DpBox(4, 4, 4, 4), ChatPhoto.containerInsets(hasPhoto = true, photoOnly = true))
    }

    @Test fun text_alone_keeps_the_bubble_padding() {
        assertEquals(ChatPhoto.DpBox(16, 12, 16, 12), ChatPhoto.containerInsets(hasPhoto = false, photoOnly = false))
        assertEquals(ChatPhoto.DpBox(0, 0, 0, 0), ChatPhoto.captionInsets(hasPhoto = false, photoOnly = false))
    }

    @Test fun bubbles_shrink_around_a_picture_and_classic_stays_flat() {
        assertEquals(
            ChatPhoto.DpBox(4, 4, 4, 4),
            ChatPhoto.assistantBubbleInsets(bubbles = true, hasPhoto = true, photoOnly = true),
        )
        assertEquals(
            ChatPhoto.DpBox(16, 12, 16, 12),
            ChatPhoto.assistantBubbleInsets(bubbles = true, hasPhoto = false, photoOnly = false),
        )
        assertEquals(
            ChatPhoto.DpBox(4, 4, 4, 4),
            ChatPhoto.assistantBubbleInsets(bubbles = false, hasPhoto = false, photoOnly = false),
        )
        assertEquals(
            ChatPhoto.DpBox(4, 4, 4, 4),
            ChatPhoto.assistantBubbleInsets(bubbles = false, hasPhoto = true, photoOnly = true),
        )
    }

    @Test fun a_file_uri_wins_and_a_data_url_is_the_stored_picture() {
        val data = "data:image/jpeg;base64,AAAA"
        assertEquals(ChatPhoto.BubbleSource.File("content://photo/1"), ChatPhoto.bubbleSource("content://photo/1", data))
        assertEquals(ChatPhoto.BubbleSource.Embedded(data), ChatPhoto.bubbleSource(null, data))
        assertEquals(ChatPhoto.BubbleSource.Embedded(data), ChatPhoto.bubbleSource(data, data))
        assertEquals(null, ChatPhoto.bubbleSource(null, null))
        assertEquals(null, ChatPhoto.bubbleSource("  ", "https://example.com/x"))
    }

    @Test fun quarter_turn_flag_swaps_the_measured_sides() {
        val file = File.createTempFile("photo", ".jpg")
        val wide = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        file.outputStream().use { wide.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        assertEquals(20 to 40, ChatPhoto.orientedBounds(file.readBytes()))
    }
}
