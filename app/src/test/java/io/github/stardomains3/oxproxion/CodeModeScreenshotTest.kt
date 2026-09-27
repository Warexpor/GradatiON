package io.github.stardomains3.oxproxion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.stardomains3.oxproxion.code.CodeDiffFragment
import io.github.stardomains3.oxproxion.code.CodeEvent
import io.github.stardomains3.oxproxion.code.CodeHub
import io.github.stardomains3.oxproxion.code.CodeSessionFragment
import io.github.stardomains3.oxproxion.code.CodeSettingsFragment
import io.github.stardomains3.oxproxion.code.HarnessKind
import io.github.stardomains3.oxproxion.code.NewSessionRequest
import io.github.stardomains3.oxproxion.code.PermissionMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Code mode screens rendered to app/build/screenshots (code_*.png), driven by the built-in demo
 * machine, plus behaviour checks for the third tab. Separate from ScreenshotTest so Code mode
 * work never collides with chat screen work.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*CodeModeScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ScreenshotApp::class, sdk = [35], qualifiers = CODE_DARK)
class CodeModeScreenshotTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        AppDatabase.setInstanceForTesting(
            Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        )
        CodeHub.resetForTesting()
    }

    @After
    fun tearDown() = CodeHub.resetForTesting()

    // ── screens ───────────────────────────────────────────────────────────────────────────

    @Test fun codeOnboardDark() = withCode(demo = false) { a, _ -> snap(root(a), "code_onboard_dark") }

    @Test @Config(qualifiers = CODE_LIGHT)
    fun codeOnboardLight() = withCode(demo = false) { a, _ -> snap(root(a), "code_onboard_light") }

    @Test fun codeHomeEmptyDark() = withCode(seedSessions = false) { a, _ -> snap(root(a), "code_home_empty_dark") }

    @Test fun codeHomeDark() = withCode { a, _ ->
        startDemo("Add a follow-system option to the theme setting")
        idle(4)
        snap(root(a), "code_home_dark")
    }

    @Test @Config(qualifiers = CODE_LIGHT)
    fun codeHomeLight() = withCode { a, _ ->
        startDemo("Add a follow-system option to the theme setting")
        idle(4)
        snap(root(a), "code_home_light")
    }

    @Test fun codeSessionApprovalDark() = withCode { a, _ ->
        val id = startDemo("Add a follow-system option to the theme setting")
        push(a, CodeSessionFragment.newInstance(id))
        idle(12)
        assertTrue("demo should be waiting on an approval",
            CodeHub.get(ctx).sessions.value[id]!!.events.any { it is CodeEvent.Approval && it.chosen == null })
        snap(root(a), "code_session_approval_dark")
    }

    @Test @Config(qualifiers = CODE_LIGHT)
    fun codeSessionApprovalLight() = withCode { a, _ ->
        val id = startDemo("Add a follow-system option to the theme setting")
        push(a, CodeSessionFragment.newInstance(id))
        idle(12)
        snap(root(a), "code_session_approval_light")
    }

    @Test fun codeSessionDoneDark() = withCode { a, _ ->
        val hub = CodeHub.get(ctx)
        val id = startDemo("Add a follow-system option to the theme setting")
        push(a, CodeSessionFragment.newInstance(id))
        idle(12)
        val approval = hub.sessions.value[id]!!.events.filterIsInstance<CodeEvent.Approval>().single()
        hub.answer(id, approval.requestId, approval.options.first { it.id == "allow" })
        idle(16)
        assertEquals(false, hub.sessions.value[id]!!.running)
        snap(root(a), "code_session_done_dark")
    }

    @Test fun codeDiffDark() = withCode { a, _ ->
        val hub = CodeHub.get(ctx)
        val id = startDemo("Add a follow-system option to the theme setting")
        idle(12)
        val diff = hub.sessions.value[id]!!.events.filterIsInstance<CodeEvent.FileDiff>().single()
        push(a, CodeDiffFragment.newInstance(id, diff.key))
        snap(root(a), "code_diff_dark")
    }

    @Test fun codeSettingsDark() = withCode { a, _ ->
        push(a, CodeSettingsFragment())
        snap(root(a), "code_settings_dark")
    }

    @Test fun codeHostDialogDark() = withCode { a, chat ->
        push(a, CodeSettingsFragment())
        val f = a.supportFragmentManager.fragments.last { it is CodeSettingsFragment }
        io.github.stardomains3.oxproxion.code.CodeHostDialog.show(f, null)
        idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        snap(dialog.window!!.decorView, "code_host_dialog_dark")
    }

    // ── behaviour ─────────────────────────────────────────────────────────────────────────

    @Test fun codeDictatingDark() = withCode { a, _ ->
        VoiceInput.deviceAvailableOverride = true
        val home = a.supportFragmentManager.fragments.flatMap { listOf(it) + it.childFragmentManager.fragments }
            .filterIsInstance<io.github.stardomains3.oxproxion.code.CodeHomeFragment>().first()
        val composer = home.javaClass.getDeclaredField("composer").apply { isAccessible = true }.get(home)
        val d = composer.javaClass.getDeclaredField("dictation").apply { isAccessible = true }.get(composer) as VoiceDictation
        d.refresh()
        d.onStateChanged(VoiceInput.State.LISTENING)
        d.onCommit("add a dark mode toggle to settings")
        d.onPartial("and remember it")
        val speech = floatArrayOf(0.2f, 0.6f, 0.95f, 0.5f, 0.3f, 0.85f, 0.4f, 0.1f)
        repeat(60) { i ->
            d.onLevel(speech[i % speech.size])
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(66))
        }
        snap(root(a), "code_dictating_dark")
        VoiceInput.deviceAvailableOverride = null
    }

    @Test fun codeTabHiddenWhenDisabled() {
        CodeHub.get(ctx).store.enabled = false
        launch { a, _ ->
            assertEquals(View.GONE, a.findViewById<View>(R.id.tabCode).visibility)
        }
    }

    @Test fun codeHarnessPickerDark() = withCode { a, _ ->
        a.findViewById<View>(R.id.codeComposerAgent).performClick()
        idle(3)
        snap(root(a), "code_harness_picker_dark")
    }

    @Test fun codeTabSwitchesAndComesBack() = withCode { a, _ ->
        val code = a.findViewById<View>(R.id.codeModeContainer)
        assertEquals(View.VISIBLE, code.visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.composerDock).visibility)
        a.findViewById<View>(R.id.tabChat).performClick()
        idle()
        assertEquals(View.GONE, code.visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.composerDock).visibility)
        assertTrue(a.findViewById<View>(R.id.tabChat).isSelected)
        a.findViewById<View>(R.id.tabCode).performClick()
        idle()
        assertEquals(View.VISIBLE, code.visibility)
        assertTrue(a.findViewById<View>(R.id.tabCode).isSelected)
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private fun startDemo(prompt: String): String = runBlocking {
        CodeHub.get(ctx).startSession(
            NewSessionRequest("demo", HarnessKind.CLAUDE_CODE, "~/code/GradatiON", prompt, PermissionMode.ASK)
        ).getOrThrow()
    }

    /** Code enabled, last tab Code, optionally the demo machine (which lists two past sessions). */
    private fun withCode(demo: Boolean = true, seedSessions: Boolean = true, block: (MainActivity, ChatFragment) -> Unit) {
        val hub = CodeHub.get(ctx)
        hub.store.enabled = true
        hub.store.lastTabWasCode = true
        if (demo) {
            hub.addDemoHost()
            if (!seedSessions) CodeHub.get(ctx).sessions.value.keys.toList().forEach { hub.forget(it) }
        }
        launch { a, chat ->
            if (demo && !seedSessions) {
                CodeHub.get(ctx).sessions.value.keys.toList().forEach { hub.forget(it) }
                idle()
            }
            block(a, chat)
        }
    }

    private fun launch(block: (MainActivity, ChatFragment) -> Unit) {
        val night = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        SharedPreferencesHelper(ctx).saveThemeMode(
            if (night) SharedPreferencesHelper.THEME_DARK else SharedPreferencesHelper.THEME_LIGHT
        )
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            idle()
            sc.onActivity { a ->
                idle()
                block(a, a.supportFragmentManager.findFragmentByTag("ChatFragment") as ChatFragment)
            }
        }
    }

    private fun push(a: MainActivity, f: androidx.fragment.app.Fragment) {
        a.supportFragmentManager.beginTransaction().add(R.id.fragment_container, f).commitNow()
        idle()
    }

    private fun root(a: MainActivity) = a.window.decorView

    /** Advances the main looper in small steps so coroutine delays and frame callbacks both run. */
    private fun idle(seconds: Int = 2) {
        repeat(seconds * 10) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        }
    }

    private fun snap(view: View, name: String) {
        val bmp = runCatching { renderHardware(view) }.getOrNull() ?: Bitmap.createBitmap(
            view.width, view.height, Bitmap.Config.ARGB_8888
        ).also { view.draw(Canvas(it)) }
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun renderHardware(view: View): Bitmap {
        val w = view.width
        val h = view.height
        val node = RenderNode("snap").apply {
            setPosition(0, 0, w, h)
            val c = beginRecording()
            view.draw(c)
            endRecording()
        }
        val reader = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val renderer = HardwareRenderer().apply {
            setContentRoot(node)
            setSurface(reader.surface)
        }
        try {
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage()
            val plane = image.planes[0]
            val full = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            image.close()
            return Bitmap.createBitmap(full, 0, 0, w, h)
        } finally {
            renderer.destroy()
            reader.close()
        }
    }
}

private const val CODE_DARK = "w411dp-h891dp-night-xxhdpi"
private const val CODE_LIGHT = "w411dp-h891dp-notnight-xxhdpi"
