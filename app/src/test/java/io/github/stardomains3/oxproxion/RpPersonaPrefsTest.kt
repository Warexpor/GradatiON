package io.github.stardomains3.oxproxion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The persona Name is its own pref; the lorebook switch is retired without losing an "off". */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class RpPersonaPrefsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun personaNameFallsBackToTheMatchingPresetUntilSaved() {
        val prefs = SharedPreferencesHelper(ctx)
        prefs.saveRpPersona("A courier.")
        prefs.saveRpPersonaPresets(listOf(RpPersonaPreset("Sam", "A courier.")))
        assertEquals("Sam", prefs.getRpPersonaName())
        prefs.saveRpPersonaName("Samantha")
        assertEquals("Samantha", prefs.getRpPersonaName())
        // Saved empty means no name, not "ask the preset again".
        prefs.saveRpPersonaName("")
        assertEquals("", prefs.getRpPersonaName())
    }

    @Test fun oldPresetsUseTheirLabelAsTheName() {
        assertEquals("Sam", RpPersonaPreset("Sam", "x").personaName)
        assertEquals("Samantha", RpPersonaPreset("Work", "x", userName = "Samantha").personaName)
    }

    @Test fun loreSwitchThatWasOffIsHonouredOnce() {
        val prefs = SharedPreferencesHelper(ctx)
        prefs.mainPrefs.edit().putBoolean("rp_lore_enabled", false).apply()
        assertTrue(prefs.takeRpLoreSwitchWasOff())
        assertFalse(prefs.takeRpLoreSwitchWasOff())
    }

    @Test fun loreSwitchLeftOnChangesNothing() {
        val prefs = SharedPreferencesHelper(ctx)
        assertFalse(prefs.takeRpLoreSwitchWasOff())
    }
}
