package io.github.stardomains3.oxproxion

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The animator-scale answer is cached, so it must still follow the system setting. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class MotionAnimationsCacheTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun setScale(scale: Float) {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
    }

    @Test fun offMeansOffAndOnMeansOn() {
        setScale(0f)
        assertFalse(Motion.areAnimationsEnabled(ctx))
        setScale(1f)
        assertTrue(Motion.areAnimationsEnabled(ctx))
    }

    @Test fun anExplicitRefreshAlwaysRereads() {
        setScale(1f)
        assertTrue(Motion.areAnimationsEnabled(ctx))
        setScale(0f)
        assertFalse(Motion.refreshAnimations(ctx))
        assertFalse(Motion.areAnimationsEnabled(ctx))
    }

    @Test fun repeatedCallsAgree() {
        setScale(0.5f)
        val first = Motion.areAnimationsEnabled(ctx)
        repeat(5) { assertTrue(first == Motion.areAnimationsEnabled(ctx)) }
    }
}
