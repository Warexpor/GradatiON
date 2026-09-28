package io.github.stardomains3.oxproxion

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/** Checks that the cheaper paths still match the old results. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class PerfInvariantsTest {

    @Test fun localHourMatchesCalendar() {
        val samples = longArrayOf(
            0L,
            System.currentTimeMillis(),
            System.currentTimeMillis() - 86_400_000L * 200,
            System.currentTimeMillis() + 86_400_000L * 40,
        )
        val cal = Calendar.getInstance()
        for (now in samples) {
            cal.timeInMillis = now
            assertEquals(cal.get(Calendar.HOUR_OF_DAY), localHourOfDay(now))
        }
    }

    @Test fun boxBlurSecondCallMatchesTheFirst() {
        fun sample(): Bitmap {
            val b = Bitmap.createBitmap(9, 7, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.BLACK)
            b.setPixel(1, 1, Color.WHITE)
            b.setPixel(4, 3, Color.argb(180, 40, 40, 40))
            b.setPixel(8, 6, Color.WHITE)
            return b
        }
        val a = sample()
        val b = sample()
        GlassMaterial.boxBlur(a, 2)
        GlassMaterial.boxBlur(b, 2)
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                assertEquals("pixel $x,$y", a.getPixel(x, y), b.getPixel(x, y))
            }
        }
    }

    @Test fun boxBlurSmallerImageIsNotTaintedByAPreviousLargerOne() {
        fun small(): Bitmap {
            val b = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            b.setPixel(0, 0, Color.BLACK)
            return b
        }
        val first = small()
        GlassMaterial.boxBlur(first, 1)
        val large = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        large.eraseColor(Color.RED)
        GlassMaterial.boxBlur(large, 3)
        val after = small()
        GlassMaterial.boxBlur(after, 1)
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                assertEquals(first.getPixel(x, y), after.getPixel(x, y))
            }
        }
    }
}
