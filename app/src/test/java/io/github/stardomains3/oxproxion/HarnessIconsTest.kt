package io.github.stardomains3.oxproxion

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import io.github.stardomains3.oxproxion.code.HarnessKind
import io.github.stardomains3.oxproxion.code.iconRes
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every harness mark must inflate: SVG shorthand Android can't parse throws at runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class HarnessIconsTest {
    @Test fun everyHarnessIconInflates() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        for (k in HarnessKind.entries) {
            assertNotNull(k.name, ContextCompat.getDrawable(ctx, k.iconRes))
        }
    }
}
