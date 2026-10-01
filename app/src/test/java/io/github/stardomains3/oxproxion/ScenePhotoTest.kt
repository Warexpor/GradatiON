package io.github.stardomains3.oxproxion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test fun aDataUrlRoundTripsAndAFileUriIsKeptForEdit() {
        val payload = Base64.encodeToString(byteArrayOf(1, 2, 3, 4), Base64.NO_WRAP)
        val url = "data:image/png;base64,$payload"
        val staged = ScenePhoto.stagedFromDataUrl(url)
        assertNotNull(staged)
        assertTrue(byteArrayOf(1, 2, 3, 4).contentEquals(staged!!.bytes))
        assertEquals("image/png", staged.mime)
        assertNull(ScenePhoto.stagedFromDataUrl("data:text/plain;base64,$payload"))
        assertNull(ScenePhoto.editPhoto(null, "data:image/jpeg;base64,$payload"))
        val edit = ScenePhoto.editPhoto(url, "content://scene/1")
        assertEquals(url, edit?.dataUrl)
        assertEquals("content://scene/1", edit?.fileUri)
    }
}
