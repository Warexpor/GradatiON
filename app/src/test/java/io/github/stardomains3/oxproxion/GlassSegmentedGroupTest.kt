package io.github.stardomains3.oxproxion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The glass segmented control strips its segments and keeps selection working. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class GlassSegmentedGroupTest {

    @Test fun segmentsAreBareAndSelectionWorks() {
        val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Grokion)
        val group = GlassSegmentedGroup(ctx).apply { isSingleSelection = true }
        val buttons = List(3) { i ->
            MaterialButton(ctx).apply { id = View.generateViewId(); text = "S$i" }.also { group.addView(it) }
        }
        for (b in buttons) {
            assertEquals(Color.TRANSPARENT, b.backgroundTintList?.defaultColor)
            assertEquals(0, b.strokeWidth)
        }
        group.check(buttons[1].id)
        assertEquals(buttons[1].id, group.checkedButtonId)

        group.measure(
            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        group.layout(0, 0, 900, group.measuredHeight)
        group.check(buttons[2].id)
        // Drawing mid-move and settled must not throw.
        val bmp = Bitmap.createBitmap(900, group.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        group.draw(Canvas(bmp))
        assertEquals(buttons[2].id, group.checkedButtonId)
    }
}
