package io.github.stardomains3.oxproxion

import android.graphics.Outline
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bottom-sheet glass ([GlassDrawable.topOnly]) must outline without throwing; the real
 * top-round path is used on API 30+ (Robolectric's Outline.radius after setPath is not
 * always RADIUS_UNDEFINED, so we only assert clip-ability here). Below API 30 the outline
 * is empty so elevation does not fake rounded bottom corners.
 * [GlassChrome.clearDuplicateSheetGlass] must drop a stacked content GlassDrawable, including
 * one wrapper deep.
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
    @Config(sdk = [29])
    fun topOnly_preR_outlineIsEmpty() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val d = GlassDrawable.sheet(ctx, topOnly = true)
        d.setBounds(0, 0, 400, 800)
        val outline = Outline()
        d.getOutline(outline)
        assertFalse(outline.canClip())
    }

    @Test
    @Config(sdk = [29])
    fun fullSheet_preR_outlineStillClips() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val d = GlassDrawable.sheet(ctx, topOnly = false)
        d.setBounds(0, 0, 400, 400)
        val outline = Outline()
        d.getOutline(outline)
        assertTrue(outline.canClip())
        assertTrue(outline.radius > 0f)
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

    @Test
    fun clearDuplicateSheetGlass_dropsContentGlassOnly() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sheet = android.widget.FrameLayout(ctx)
        val content = android.view.View(ctx).also {
            it.background = GlassDrawable.sheet(ctx, topOnly = true)
        }
        sheet.addView(content)
        sheet.background = GlassDrawable.sheet(ctx, topOnly = true)
        GlassChrome.clearDuplicateSheetGlass(sheet)
        assertTrue(sheet.background is GlassDrawable)
        assertTrue(content.background == null)
    }

    @Test
    fun clearDuplicateSheetGlass_dropsNestedContentGlass() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sheet = android.widget.FrameLayout(ctx)
        val wrap = android.widget.FrameLayout(ctx)
        val nested = android.view.View(ctx).also {
            it.background = GlassDrawable.sheet(ctx, topOnly = true)
        }
        wrap.addView(nested)
        sheet.addView(wrap)
        sheet.background = GlassDrawable.sheet(ctx, topOnly = true)
        GlassChrome.clearDuplicateSheetGlass(sheet)
        assertTrue(sheet.background is GlassDrawable)
        assertTrue(nested.background == null)
    }

    @Test
    fun clearDuplicateSheetGlass_leavesNonGlassContent() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sheet = android.widget.FrameLayout(ctx)
        val content = android.view.View(ctx).also {
            it.setBackgroundColor(android.graphics.Color.GRAY)
        }
        sheet.addView(content)
        GlassChrome.clearDuplicateSheetGlass(sheet)
        assertTrue(content.background != null)
    }
}
