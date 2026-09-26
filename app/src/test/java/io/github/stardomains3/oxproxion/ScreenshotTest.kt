package io.github.stardomains3.oxproxion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import java.io.File

class ScreenshotApp : Application()

/**
 * Renders key screens to PNGs under build/screenshots for visual review.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ScreenshotApp::class, sdk = [35], qualifiers = "w411dp-h891dp-night-xxhdpi")
class ScreenshotTest {

    private fun snap(view: View, name: String) {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun idle() { repeat(5) { shadowOf(Looper.getMainLooper()).idle() } }

    @Test
    fun chatDark() = chat("night", "chat_dark")

    @Test
    @Config(qualifiers = "w411dp-h891dp-notnight-xxhdpi")
    fun chatLight() = chat("notnight", "chat_light")

    @org.junit.Before
    fun setUp() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        AppDatabase.setInstanceForTesting(
            androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
                .allowMainThreadQueries().build()
        )
    }

    private fun chat(q: String, name: String) {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            idle()
            sc.onActivity { a -> idle(); snap(a.window.decorView, name) }
        }
    }
}
