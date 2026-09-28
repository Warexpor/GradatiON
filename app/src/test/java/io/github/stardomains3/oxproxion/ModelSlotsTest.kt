package io.github.stardomains3.oxproxion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Chat and Roleplay each remember their own model. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class ModelSlotsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun slotKeysAreSeparateAndChatKeepsTheOldKey() {
        assertEquals("modelvalenewchat", ModelSlots.key(ChatMode.ASK))
        assertNotEquals(ModelSlots.key(ChatMode.ASK), ModelSlots.key(ChatMode.RP))
    }

    @Test fun unpickedRoleplayStartsFromChat() {
        val store = mapOf(ModelSlots.KEY_CHAT to "local-a")
        assertEquals("local-a", ModelSlots.resolve(ChatMode.RP, { store[it] }, "dflt"))
        assertEquals("local-a", ModelSlots.resolve(ChatMode.ASK, { store[it] }, "dflt"))
    }

    @Test fun ownPickWinsAndOtherModeIsUntouched() {
        val store = mapOf(ModelSlots.KEY_CHAT to "cloud", ModelSlots.KEY_RP to "local")
        assertEquals("cloud", ModelSlots.resolve(ChatMode.ASK, { store[it] }, "dflt"))
        assertEquals("local", ModelSlots.resolve(ChatMode.RP, { store[it] }, "dflt"))
    }

    @Test fun nothingSavedUsesTheDefault() {
        assertEquals("dflt", ModelSlots.resolve(ChatMode.RP, { null }, "dflt"))
        assertEquals("dflt", ModelSlots.resolve(ChatMode.ASK, { "" }, "dflt"))
    }

    @Test fun prefsKeepOneModelPerMode() {
        val prefs = SharedPreferencesHelper(ctx)
        prefs.savePreferenceModelFor(ChatMode.ASK, "cloud/model")
        prefs.pinModelSlot(ChatMode.RP)
        // The split starts both modes on the same model...
        assertEquals("cloud/model", prefs.getPreferenceModelFor(ChatMode.RP))
        // ...and a later Chat pick no longer drags Roleplay along.
        prefs.savePreferenceModelFor(ChatMode.ASK, "cloud/other")
        assertEquals("cloud/model", prefs.getPreferenceModelFor(ChatMode.RP))
        prefs.savePreferenceModelFor(ChatMode.RP, "llama3:8b")
        assertEquals("cloud/other", prefs.getPreferenceModelFor(ChatMode.ASK))
        assertEquals("llama3:8b", prefs.getPreferenceModelFor(ChatMode.RP))
    }

    @Test fun modelInUseFollowsTheSavedMode() {
        val prefs = SharedPreferencesHelper(ctx)
        prefs.savePreferenceModelFor(ChatMode.ASK, "cloud/model")
        prefs.savePreferenceModelFor(ChatMode.RP, "llama3:8b")
        prefs.saveChatMode(ChatMode.ASK)
        assertEquals("cloud/model", prefs.getPreferenceModelnew())
        prefs.saveChatMode(ChatMode.RP)
        assertEquals("llama3:8b", prefs.getPreferenceModelnew())
        prefs.savePreferenceModelnewchat("qwen3:8b")
        assertEquals("qwen3:8b", prefs.getPreferenceModelFor(ChatMode.RP))
        assertEquals("cloud/model", prefs.getPreferenceModelFor(ChatMode.ASK))
    }

    @Test fun savedEndpointIsCleanedOnRead() {
        val prefs = SharedPreferencesHelper(ctx)
        prefs.setLanEndpoint("192.168.1.20:11434/v1/")
        assertEquals("http://192.168.1.20:11434", prefs.getLanEndpoint())
    }
}
