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

    @Test fun offWins() {
        prefs.setVoiceInputModel("openai/whisper-1")
        prefs.setVoiceInputProvider(VoiceEngine.OFF.key)
        assertNull(VoiceInput.resolve(ctx, prefs))
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
        assertTrue(VoiceInput.VoiceClip.readable(1))
        assertTrue(VoiceInput.VoiceClip.readable(VoiceInput.VoiceClip.MAX_BYTES))
        assertFalse(VoiceInput.VoiceClip.readable(0))
        assertFalse(VoiceInput.VoiceClip.readable(VoiceInput.VoiceClip.MAX_BYTES + 1))
        assertEquals(5 * 60 * 1000L, VoiceInput.VoiceClip.MAX_DURATION_MS)
    }

    @Test fun unknownKeysReadAsPhone() {
        assertEquals(VoiceEngine.DEVICE, VoiceEngine.fromKey("whatever"))
        assertEquals(VoiceEngine.DEVICE, VoiceEngine.fromKey(null))
    }
}
