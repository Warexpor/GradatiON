package io.github.stardomains3.oxproxion

import android.graphics.Outline
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Bottom-sheet glass ([GlassDrawable.topOnly]) must outline without throwing; the real
 * top-round path is used (Robolectric's Outline.radius after setPath is not always
 * RADIUS_UNDEFINED, so we only assert clip-ability here).
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

    @Test
    fun sheetGlass_survivesBottomSheetFirstLayout() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Grokion)
        val dialog = BottomSheetDialog(activity, R.style.ThemeOverlay_Grokion_BottomSheet)
        val content = android.widget.FrameLayout(activity).also {
            it.background = GlassDrawable.sheet(activity, topOnly = true)
        }
        dialog.setContentView(content)
        GlassChrome.glassDialog(dialog)
        dialog.show()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val sheet = dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
        assertTrue(sheet!!.background is GlassDrawable)
        assertTrue((sheet.background as GlassDrawable).topOnly)
        assertNull(content.background)
    }
}
