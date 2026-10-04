package io.github.stardomains3.oxproxion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which engine a mic tap uses, on a phone without a recognizer (de-Googled) and with one. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class VoiceInputTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = SharedPreferencesHelper(ctx)

    @Before fun noRecognizer() { VoiceInput.deviceAvailableOverride = false }
    @After fun reset() { VoiceInput.deviceAvailableOverride = null }

    @Test fun phoneWinsWhenThePhoneCanRecognize() {
        VoiceInput.deviceAvailableOverride = true
        prefs.setVoiceInputModel("openai/whisper-1")
        assertEquals(VoiceEngine.DEVICE, VoiceInput.resolve(ctx, prefs))
    }

    @Test fun phoneIsTheDefault() {
        assertEquals(VoiceEngine.DEVICE, VoiceEngine.fromKey(prefs.getVoiceInputProvider()))
    }

    @Test fun noRecognizerAndNoModelMeansNoMic() {
        assertFalse(VoiceInput.deviceAvailable(ctx))
        assertNull(VoiceInput.resolve(ctx, prefs))
    }

    @Test fun noRecognizerFallsBackToOpenRouter() {
        prefs.setVoiceInputModel("openai/whisper-1")
        assertEquals(VoiceEngine.CLOUD, VoiceInput.resolve(ctx, prefs))
    }

    @Test fun grokSttModelIsPinned() {
        assertEquals("grok-voice-transcribe-2.0", VoiceEngine.GROK_STT_MODEL)
    }

    @Test fun settingsRowNamesTheStoredEngineWhenThePhoneCannotRecognize() {
        prefs.setVoiceInputProvider(VoiceEngine.DEVICE.key)
        prefs.setVoiceInputModel("")
        assertNull(VoiceInput.resolve(ctx, prefs))
        assertEquals(VoiceEngine.DEVICE, settingsVoiceRowEngine(prefs.getVoiceInputProvider()))
        prefs.setVoiceInputModel("openai/whisper-1")
        assertEquals(VoiceEngine.CLOUD, VoiceInput.resolve(ctx, prefs))
        assertEquals(VoiceEngine.DEVICE, settingsVoiceRowEngine(prefs.getVoiceInputProvider()))
        prefs.setVoiceInputProvider(VoiceEngine.OFF.key)
        assertEquals(VoiceEngine.OFF, settingsVoiceRowEngine(prefs.getVoiceInputProvider()))
        prefs.setVoiceInputProvider(VoiceEngine.DEVICE.key)
        prefs.setVoiceInputModel("")
    }

    @Test fun offWins() {
        prefs.setVoiceInputModel("openai/whisper-1")
        prefs.setVoiceInputProvider(VoiceEngine.OFF.key)
        assertNull(VoiceInput.resolve(ctx, prefs))
    }

    @Test fun turningVoiceOffRemembersTheEngine() {
        prefs.setVoiceInputProvider(VoiceEngine.GROK.key)
        assertEquals(VoiceEngine.GROK.key, prefs.getVoiceInputLastEngine())
        prefs.setVoiceInputProvider(VoiceEngine.OFF.key)
        assertEquals(VoiceEngine.OFF.key, prefs.getVoiceInputProvider())
        assertEquals(VoiceEngine.GROK.key, prefs.getVoiceInputLastEngine())
        // Settings turns the master switch back on with the remembered chip.
        prefs.setVoiceInputProvider(prefs.getVoiceInputLastEngine())
        assertEquals(VoiceEngine.GROK, VoiceInput.resolve(ctx, prefs))
    }

    @Test fun pickingAnEngineWhileVoiceIsOffRemembersIt() {
        prefs.setVoiceInputProvider(VoiceEngine.CLOUD.key)
        prefs.setVoiceInputProvider(VoiceEngine.OFF.key)
        prefs.rememberVoiceInputEngine(VoiceEngine.GROK.key)
        assertEquals(VoiceEngine.OFF.key, prefs.getVoiceInputProvider())
        assertNull(VoiceInput.resolve(ctx, prefs))
        assertEquals(VoiceEngine.GROK.key, prefs.getVoiceInputLastEngine())
        prefs.rememberVoiceInputEngine(VoiceEngine.OFF.key)
        assertEquals(VoiceEngine.GROK.key, prefs.getVoiceInputLastEngine())
        prefs.setVoiceInputProvider(prefs.getVoiceInputLastEngine())
        assertEquals(VoiceEngine.GROK, VoiceInput.resolve(ctx, prefs))
    }

    @Test fun lastEngineFallsBackToPhoneWhenNeverSet() {
        prefs.mainPrefs.edit().remove("voice_input_last_engine").remove("voice_input_provider").commit()
        assertEquals(VoiceEngine.DEVICE.key, prefs.getVoiceInputLastEngine())
        prefs.setVoiceInputProvider(VoiceEngine.OFF.key)
        assertEquals(VoiceEngine.DEVICE.key, prefs.getVoiceInputLastEngine())
    }

    @Test fun pickedEnginesAreKept() {
        prefs.setVoiceInputProvider(VoiceEngine.LAN.key)
        assertEquals(VoiceEngine.LAN, VoiceInput.resolve(ctx, prefs))
        prefs.setVoiceInputProvider(VoiceEngine.CLOUD.key)
        assertEquals(VoiceEngine.CLOUD, VoiceInput.resolve(ctx, prefs))
        prefs.setVoiceInputProvider(VoiceEngine.GROK.key)
        assertEquals(VoiceEngine.GROK, VoiceInput.resolve(ctx, prefs))
    }

    @Test fun phoneNeedsNothingUpFront() {
        assertNull(VoiceInput.preflight(VoiceEngine.DEVICE, prefs))
    }

    @Test fun cloudNeedsAModelThenAKey() {
        assertEquals(R.string.voice_need_model, VoiceInput.preflight(VoiceEngine.CLOUD, prefs))
        prefs.setVoiceInputModel("openai/whisper-1")
        // No OpenRouter key has been saved in this test's prefs.
        assertEquals(R.string.voice_need_openrouter_key, VoiceInput.preflight(VoiceEngine.CLOUD, prefs))
    }

    @Test fun cloudSttUsesTheSavedOpenRouterKeyNotTheActiveChatKey() {
        // After a local-model send, activeChatApiKey holds the LAN key. Cloud STT must still
        // send the saved OpenRouter key that Settings > Voice preflight checked.
        assertEquals("sk-or", cloudVoiceOpenRouterKey("sk-or", "lan-key"))
        assertEquals("", cloudVoiceOpenRouterKey("", "lan-key"))
        assertEquals("sk-or", cloudVoiceOpenRouterKey("sk-or", ""))
    }

    @Test fun settingsOpenRouterApiKeyReadsTheModelsAlias() {
        // Chat send / title / OpenRouter transcription must use this, not activeChatApiKey.
        assertEquals("", settingsOpenRouterApiKey(prefs))
    }

    @Test fun grokNeedsAnXaiKey() {
        assertEquals(R.string.voice_need_xai_key, VoiceInput.preflight(VoiceEngine.GROK, prefs))
    }

    @Test fun localNeedsAModelThenAServer() {
        assertEquals(R.string.voice_need_model, VoiceInput.preflight(VoiceEngine.LAN, prefs))
        prefs.setVoiceInputModel("whisper-large")
        assertEquals(R.string.voice_need_lan_endpoint, VoiceInput.preflight(VoiceEngine.LAN, prefs))
        prefs.setLanEndpoint("http://192.168.1.10:8080")
        assertNull(VoiceInput.preflight(VoiceEngine.LAN, prefs))
    }

    @Test fun everyEngineHasASettingsLabel() {
        for (e in VoiceEngine.entries) assertTrue(e.name, ctx.getString(e.labelRes).isNotBlank())
    }

    @Test fun aClipPastTheByteCapIsNotReadable() {
        assertTrue(VoiceClip.readable(1))
        assertTrue(VoiceClip.readable(VoiceClip.MAX_BYTES))
        assertFalse(VoiceClip.readable(0))
        assertFalse(VoiceClip.readable(VoiceClip.MAX_BYTES + 1))
        assertEquals(5 * 60 * 1000L, VoiceClip.MAX_DURATION_MS)
    }

    @Test fun unknownKeysReadAsPhone() {
        assertEquals(VoiceEngine.DEVICE, VoiceEngine.fromKey("whatever"))
        assertEquals(VoiceEngine.DEVICE, VoiceEngine.fromKey(null))
    }
}
