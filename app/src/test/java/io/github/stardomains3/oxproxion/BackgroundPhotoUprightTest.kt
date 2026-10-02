package io.github.stardomains3.oxproxion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

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

    @Test fun a_sideways_photo_is_stored_upright_and_a_wide_one_is_capped() {
        val wide = Bitmap.createBitmap(48, 16, Bitmap.Config.ARGB_8888)
        wide.eraseColor(Color.DKGRAY)
        val file = File.createTempFile("wall", ".jpg")
        file.outputStream().use { wide.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        wide.recycle()
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val upright = BackgroundPhoto.prepare(file.readBytes())
        assertNotNull(upright)
        val turned = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(upright, 0, upright!!.size, turned)
        assertEquals(16, turned.outWidth)
        assertEquals(48, turned.outHeight)

        val huge = Bitmap.createBitmap(1800, 30, Bitmap.Config.ARGB_8888)
        huge.eraseColor(Color.GRAY)
        val raw = ByteArrayOutputStream().also { huge.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        huge.recycle()
        val fitted = BackgroundPhoto.prepare(raw)
        assertNotNull(fitted)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(fitted, 0, fitted!!.size, bounds)
        assertTrue("width ${bounds.outWidth}", bounds.outWidth in 1..1600)
        assertTrue("height ${bounds.outHeight}", bounds.outHeight in 1..1600)
        assertEquals(1600, maxOf(bounds.outWidth, bounds.outHeight))
    }

    @Test fun half_turns_and_flips_keep_the_size() {
        for (o in listOf(ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_FLIP_VERTICAL)) {
            val turned = BackgroundPhoto.upright(wide(), o)
            assertEquals("orientation $o width", 8, turned.width)
            assertEquals("orientation $o height", 4, turned.height)
        }
    }

    @Test fun a_torn_wallpaper_file_is_not_a_photo() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val slot = BackgroundPhoto.slotForCharacter(42L)
        BackgroundPhoto.delete(ctx, slot)
        assertFalse(BackgroundPhoto.hasPhoto(ctx, slot))
        val file = BackgroundPhoto.file(ctx, slot)
        file.parentFile?.mkdirs()
        // SOI only — recover cannot mint EOI, so this must not light the Wallpaper tile.
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01))
        assertTrue(file.isFile)
        assertFalse(BackgroundPhoto.hasPhoto(ctx, slot))
        val jpeg = BackgroundPhoto.prepare(
            ByteArrayOutputStream().also {
                wide().compress(Bitmap.CompressFormat.JPEG, 90, it)
            }.toByteArray()
        )
        assertNotNull(jpeg)
        assertTrue(BackgroundPhoto.writeBytes(ctx, slot, jpeg!!))
        assertTrue(BackgroundPhoto.hasPhoto(ctx, slot))
        BackgroundPhoto.delete(ctx, slot)
    }
}
