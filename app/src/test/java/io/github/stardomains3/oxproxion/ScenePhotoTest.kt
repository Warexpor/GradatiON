package io.github.stardomains3.oxproxion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScenePhotoTest {
    @Test fun encodeShrinksAWidePicture() {
        val bmp = Bitmap.createBitmap(2000, 80, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.DKGRAY)
        val raw = ByteArrayOutputStream().also {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        bmp.recycle()
        val jpeg = ScenePhoto.encode(raw)
        assertNotNull(jpeg)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg!!.size, bounds)
        assertTrue(bounds.outWidth in 1..ScenePhoto.MAX_EDGE)
        assertTrue(bounds.outHeight in 1..ScenePhoto.MAX_EDGE)
    }

    @Test fun encodeRejectsGarbage() {
        assertNull(ScenePhoto.encode(byteArrayOf(1, 2, 3, 4)))
    }
}
