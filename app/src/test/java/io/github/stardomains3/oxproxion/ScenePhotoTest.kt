package io.github.stardomains3.oxproxion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
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
        assertTrue("width ${bounds.outWidth}", bounds.outWidth in 1..ScenePhoto.MAX_EDGE)
        assertTrue("height ${bounds.outHeight}", bounds.outHeight in 1..ScenePhoto.MAX_EDGE)
    }
}
