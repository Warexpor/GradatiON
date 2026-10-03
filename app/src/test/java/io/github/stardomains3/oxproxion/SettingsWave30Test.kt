package io.github.stardomains3.oxproxion

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings wave 30: tool switches and the local-server base URL.
 * Run: ./gradlew :app:testDebugUnitTest --tests io.github.stardomains3.oxproxion.SettingsWave30Test
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class SettingsWave30Test {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun clearPrefs() {
        app.getSharedPreferences(SharedPreferencesHelper.MAIN_PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun turningARenamedFileToolOffDropsTheOldNames() {
        val stored = setOf("list_grokion_files", "list_oxproxion_files", "set_timer", "read_oxproxion_file")
        val afterList = ToolItem.enabledToolsAfterToggle(stored, "list_gradation_files", enabled = false)
        assertFalse("list_grokion_files" in afterList)
        assertFalse("list_oxproxion_files" in afterList)
        assertFalse(ToolItem.isToolEnabled("list_gradation_files", afterList))
        assertTrue("set_timer" in afterList)
        assertTrue(ToolItem.isToolEnabled("read_gradation_file", afterList))

        val afterRead = ToolItem.enabledToolsAfterToggle(afterList, "read_gradation_file", enabled = false)
        assertEquals(setOf("set_timer"), afterRead)
        assertFalse(ToolItem.effectiveEnabledTools(afterRead).contains("read_gradation_file"))
    }

    @Test
    fun turningAToolOnKeepsUnrelatedNames() {
        val next = ToolItem.enabledToolsAfterToggle(setOf("set_timer"), "make_file", enabled = true)
        assertEquals(setOf("set_timer", "make_file"), next)
    }

    @Test
    fun createFileDoesNotNeedTheFolderGrant() {
        assertFalse(ToolItem.needsFolderGrant("make_file"))
        assertFalse(ToolItem.needsFolderGrant("create_folder"))
        assertFalse(ToolItem.needsFolderGrant("set_timer"))
        assertTrue(ToolItem.needsFolderGrant("delete_files"))
        assertTrue(ToolItem.needsFolderGrant("list_gradation_files"))
        assertTrue(ToolItem.needsFolderGrant("read_gradation_file"))
        assertTrue(ToolItem.needsFolderGrant("open_file"))
        assertTrue(ToolItem.needsFolderGrant("edit_file"))
        assertTrue(ToolItem.needsFolderGrant("copy_file"))
    }

    @Test
    fun lanBaseDropsATrailingSlashAndAPastedV1Suffix() {
        assertEquals(
            "http://10.0.0.23:11434",
            LanEndpointValidator.normalizedBase("http://10.0.0.23:11434/"),
        )
        assertEquals(
            "http://10.0.0.23:11434",
            LanEndpointValidator.normalizedBase("http://10.0.0.23:11434/v1"),
        )
        assertEquals(
            "http://10.0.0.23:11434",
            LanEndpointValidator.normalizedBase("  http://10.0.0.23:11434/v1/ "),
        )
        assertEquals(
            "https://example.com/openai",
            LanEndpointValidator.normalizedBase("https://example.com/openai/V1/"),
        )
        assertEquals("http://10.0.0.23:11434", LanEndpointValidator.normalizedBase("http://10.0.0.23:11434"))
        // A longer suffix is part of the path, not the OpenAI base.
        assertEquals("http://10.0.0.23:11434/v10", LanEndpointValidator.normalizedBase("http://10.0.0.23:11434/v10"))
        // The scheme separator is not a /v1 path.
        assertEquals("http://v1", LanEndpointValidator.normalizedBase("http://v1"))
    }

    @Test
    fun savedLanEndpointIsTheBaseTheRequestsAppendTo() {
        val prefs = SharedPreferencesHelper(app)
        prefs.setLanEndpoint("http://192.168.1.10:11434/v1/")
        assertEquals("http://192.168.1.10:11434", prefs.getLanEndpoint())
        assertEquals("http://192.168.1.10:11434", helperRaw())
        // A value written before this strip still reads as the base.
        prefs.mainPrefs.edit().putString("lan_endpoint", "http://10.0.0.5:1234/").commit()
        assertEquals("http://10.0.0.5:1234", prefs.getLanEndpoint())
        prefs.setLanEndpoint("   ")
        assertNull(prefs.getLanEndpoint())
    }

    private fun helperRaw(): String? =
        app.getSharedPreferences(SharedPreferencesHelper.MAIN_PREFS, Context.MODE_PRIVATE)
            .getString("lan_endpoint", null)
}
