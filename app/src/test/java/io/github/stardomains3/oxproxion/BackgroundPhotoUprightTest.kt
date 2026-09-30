package io.github.stardomains3.oxproxion

import android.graphics.Bitmap
import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Phone photos carry a sideways flag; the stored background has to be turned upright. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class BackgroundPhotoUprightTest {

    private fun wide() = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)

    @Test fun upright_photos_are_left_alone() {
        val src = wide()
        assertSame(src, BackgroundPhoto.upright(src, ExifInterface.ORIENTATION_NORMAL))
        assertSame(src, BackgroundPhoto.upright(src, ExifInterface.ORIENTATION_UNDEFINED))
    }

    @Test fun quarter_turns_swap_width_and_height() {
        for (o in listOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)) {
            val turned = BackgroundPhoto.upright(wide(), o)
            assertEquals("orientation $o width", 4, turned.width)
            assertEquals("orientation $o height", 8, turned.height)
        }
    }

    @Test fun half_turns_and_flips_keep_the_size() {
        for (o in listOf(ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_FLIP_VERTICAL)) {
            val turned = BackgroundPhoto.upright(wide(), o)
            assertEquals("orientation $o width", 8, turned.width)
            assertEquals("orientation $o height", 4, turned.height)
        }
    }
}
