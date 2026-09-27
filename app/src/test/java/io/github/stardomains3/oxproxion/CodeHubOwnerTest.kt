package io.github.stardomains3.oxproxion

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.stardomains3.oxproxion.code.CodeHub
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Code hub is per application, and a test can install a different one. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class CodeHubOwnerTest {
    private val ctx: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        AppDatabase.setInstanceForTesting(
            Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build(),
        )
        CodeHub.resetForTesting()
    }

    @After
    fun tearDown() {
        CodeHub.resetForTesting()
        AppDatabase.setInstanceForTesting(null)
    }

    @Test
    fun sameApplicationReusesTheHub() {
        assertSame(CodeHub.get(ctx), CodeHub.get(ctx))
    }

    @Test
    fun resetStartsANewHub() {
        val first = CodeHub.get(ctx)
        CodeHub.resetForTesting()
        assertNotSame(first, CodeHub.get(ctx))
    }

    @Test
    fun installedFactoryReplacesTheProcessHub() {
        val custom = CodeHub(ctx)
        CodeHub.installForTesting { custom }
        assertSame(custom, CodeHub.get(ctx))
        CodeHub.resetForTesting()
        assertNotSame(custom, CodeHub.get(ctx))
    }
}
