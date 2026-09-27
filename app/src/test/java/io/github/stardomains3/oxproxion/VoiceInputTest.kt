package io.github.stardomains3.oxproxion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    }

    @Test fun unknownKeysReadAsPhone() {
        assertEquals(VoiceEngine.DEVICE, VoiceEngine.fromKey("whatever"))
        assertEquals(VoiceEngine.DEVICE, VoiceEngine.fromKey(null))
    }
}
