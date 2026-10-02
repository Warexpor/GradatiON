package io.github.stardomains3.oxproxion

import android.graphics.Outline
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bottom-sheet glass ([GlassDrawable.topOnly]) must outline without throwing; the real
 * top-round path is used on API 30+ (Robolectric's Outline.radius after setPath is not
 * always RADIUS_UNDEFINED, so we only assert clip-ability here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class GlassDrawableOutlineTest {

    @Test
    fun topOnly_outlineIsClipable() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val d = GlassDrawable.sheet(ctx, topOnly = true)
        d.setBounds(0, 0, 400, 800)
        val outline = Outline()
        d.getOutline(outline)
        assertTrue(outline.canClip())
    }

    @Test
    fun fullSheet_outlineIsRoundRect() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val d = GlassDrawable.sheet(ctx, topOnly = false)
        d.setBounds(0, 0, 400, 400)
        val outline = Outline()
        d.getOutline(outline)
        assertTrue(outline.radius > 0f)
        assertTrue(outline.canClip())
    }
}
