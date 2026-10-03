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
 * Seeding and a later save must not throw away a preference blob this version cannot decode.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*PrefsCorruptBlobTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class PrefsCorruptBlobTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun clearPrefs() {
        app.getSharedPreferences(SharedPreferencesHelper.MAIN_PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun helper() = SharedPreferencesHelper(app)

    @Test
    fun corruptModelListSurvivesSeeding() {
        val prefs = helper()
        prefs.mainPrefs.edit().putString("custom_models", "{torn").commit()

        prefs.seedDefaultModelsIfNeeded()

        assertEquals("{torn", prefs.mainPrefs.getString("custom_models", null))
        assertFalse(prefs.mainPrefs.getBoolean("default_models_seeded", false))
        assertFalse(prefs.mainPrefs.contains("custom_models.unreadable"))
        assertTrue(prefs.customModelsUnreadable())
        assertTrue(prefs.getCustomModels().isEmpty())
    }

    @Test
    fun corruptSystemMessageListSurvivesSeeding() {
        val prefs = helper()
        prefs.mainPrefs.edit().putString("custom_system_messages", "{torn").commit()

        prefs.seedDefaultSystemMessagesIfNeeded()

        assertEquals("{torn", prefs.mainPrefs.getString("custom_system_messages", null))
        assertFalse(prefs.mainPrefs.getBoolean("default_system_messages_seeded", false))
    }

    @Test
    fun savingOverAnUnreadableDraftArchivesIt() {
        val prefs = helper()
        prefs.mainPrefs.edit().putString("ask_composer_drafts", "{torn").commit()

        prefs.saveAskComposerDrafts(mapOf("4" to "still writing"))

        assertEquals("{torn", prefs.mainPrefs.getString("ask_composer_drafts.unreadable", null))
        assertEquals("still writing", prefs.getAskComposerDrafts()["4"])

        prefs.saveAskComposerDrafts(emptyMap())
        assertEquals("{torn", prefs.mainPrefs.getString("ask_composer_drafts.unreadable", null))
        assertTrue(prefs.getAskComposerDrafts().isEmpty())
    }

    @Test
    fun aReadableEmptyModelListStillGetsTheDemoModel() {
        val prefs = helper()

        prefs.seedDefaultModelsIfNeeded()

        assertTrue(prefs.getCustomModels().any { DemoModel.isDemo(it.apiIdentifier) })
        assertTrue(prefs.mainPrefs.getBoolean("default_models_seeded", false))
        assertFalse(prefs.mainPrefs.contains("custom_models.unreadable"))
    }

    @Test
    fun savingOverAnUnreadableBlobArchivesItFirst() {
        val prefs = helper()
        prefs.mainPrefs.edit().putString("custom_models", "{torn").commit()

        prefs.saveCustomModels(listOf(DemoModel.model()))

        assertEquals("{torn", prefs.mainPrefs.getString("custom_models.unreadable", null))
        assertTrue(prefs.getCustomModels().any { DemoModel.isDemo(it.apiIdentifier) })

        prefs.saveCustomModels(emptyList())
        assertEquals("{torn", prefs.mainPrefs.getString("custom_models.unreadable", null))
        assertTrue(prefs.getCustomModels().isEmpty())
    }

    @Test
    fun aReadableSaveDoesNotArchive() {
        val prefs = helper()
        prefs.saveCustomPrompts(listOf(Prompt("Title", "Body")))
        assertFalse(prefs.mainPrefs.contains("custom_prompts.unreadable"))
        assertEquals("Title", prefs.getCustomPrompts().single().title)
    }

    @Test
    fun aWrongTypeDoesNotCrashAndTheValueStays() {
        val prefs = helper()
        prefs.mainPrefs.edit()
            .putString("font_size", "nope")
            .putString("pinned_session_ids", "1")
            .putString("chat_db_recovery_stamp", "soon")
            .commit()

        assertEquals(100, prefs.getFontSize())
        assertTrue(prefs.getPinnedSessionIds().isEmpty())
        assertNull(prefs.recoveryPendingStamp())
        assertEquals("nope", prefs.mainPrefs.all["font_size"])
    }

    @Test
    fun constructingTheHelperSurvivesAWrongTypeMigrationFlag() {
        app.getSharedPreferences(SharedPreferencesHelper.MAIN_PREFS, Context.MODE_PRIVATE)
            .edit().putString("has_migrated_to_kotlin_serialization", "yes").commit()

        val prefs = helper()
        assertEquals(100, prefs.getFontSize())
    }

    @Test
    fun savingToolsArchivesAnUnreadableList() {
        val prefs = helper()
        prefs.mainPrefs.edit().putString("enabled_tools", "{torn").commit()

        prefs.saveEnabledTools(setOf("read"))

        assertEquals("{torn", prefs.mainPrefs.getString("enabled_tools.unreadable", null))
        assertEquals(setOf("read"), prefs.getEnabledTools())
    }

    @Test
    fun takingADeletedCharacterDoesNotReplaceAnUnreadableRemap() {
        val prefs = helper()
        prefs.mainPrefs.edit().putString("rp_deleted_char_remap", "{torn").commit()

        assertNull(prefs.takeDeletedRpCharacterId("k"))
        assertEquals("{torn", prefs.mainPrefs.getString("rp_deleted_char_remap", null))
        assertFalse(prefs.mainPrefs.contains("rp_deleted_char_remap.unreadable"))
    }

    @Test
    fun roleplayAndLlmModeSurviveAFreshHelper() {
        val prefs = helper()
        prefs.setRoleplayEnabled(true)
        prefs.saveRpLlmMode(true)
        val again = helper()
        assertTrue(again.isRoleplayEnabled())
        assertTrue(again.isRpLlmMode())
        again.setRoleplayEnabled(false)
        again.saveRpLlmMode(false)
        assertFalse(helper().isRoleplayEnabled())
        assertFalse(helper().isRpLlmMode())
    }

    @Test
    fun themePersonaAndVoiceModelSurviveAFreshHelper() {
        val prefs = helper()
        prefs.saveThemeMode(SharedPreferencesHelper.THEME_LIGHT)
        prefs.saveBackgroundStyle("drift")
        prefs.setRpPersonaEnabled(false)
        prefs.setVoiceInputModel("whisper-1")
        val again = helper()
        assertEquals(SharedPreferencesHelper.THEME_LIGHT, again.getThemeMode())
        assertEquals("drift", again.getBackgroundStyle())
        assertFalse(again.isRpPersonaEnabled())
        assertEquals("whisper-1", again.getVoiceInputModel())
        again.saveThemeMode(SharedPreferencesHelper.THEME_DARK)
        again.setRpPersonaEnabled(true)
        again.setVoiceInputModel("")
        assertEquals(SharedPreferencesHelper.THEME_DARK, helper().getThemeMode())
        assertTrue(helper().isRpPersonaEnabled())
        assertEquals("", helper().getVoiceInputModel())
    }

    @Test
    fun hapticsDataStyleAndModeSurviveAFreshHelper() {
        val prefs = helper()
        prefs.saveHapticButtons(false)
        prefs.saveHapticResponding(false)
        prefs.saveBiometricEnabled(true)
        prefs.saveNotiPreference(false)
        prefs.saveKeepScreenOnPreference(true)
        prefs.saveAllowDestructiveTools(true)
        prefs.saveTrustSelfSignedLan(true)
        prefs.saveChatMarkStyle(SharedPreferencesHelper.CHAT_MARK_PLAIN)
        prefs.saveChatMode(ChatMode.RP)
        prefs.saveRpLoreEnabled(false)
        prefs.saveRpThirdPerson(true)
        prefs.saveRpAutoMemory(true)
        prefs.saveRpShowThoughts(true)
        prefs.saveShowThinkingBlocks(false)
        prefs.saveExpandableInput(true)
        prefs.saveScrollersPreference(true)
        prefs.saveScrollProgressEnabled(true)
        prefs.saveVolumeScrollEnabled(true)
        prefs.saveExtPreference(true)
        prefs.saveExtPreference2(true)
        prefs.saveExtendedTopBarEnabled(true)
        prefs.saveStreamingPreference(false)
        BackgroundPhoto.setOption(app, BackgroundPhoto.KEY_BLUR, false)
        BackgroundPhoto.setOption(app, BackgroundPhoto.KEY_COLOR, true)
        val again = helper()
        assertFalse(again.getHapticButtons())
        assertFalse(again.getHapticResponding())
        assertTrue(again.getBiometricEnabled())
        assertFalse(again.getNotiPreference())
        assertTrue(again.getKeepScreenOnPreference())
        assertTrue(again.getAllowDestructiveTools())
        assertTrue(again.getTrustSelfSignedLan())
        assertEquals(SharedPreferencesHelper.CHAT_MARK_PLAIN, again.getChatMarkStyle())
        assertEquals(ChatMode.RP, again.getChatMode())
        assertFalse(again.isRpLoreEnabled())
        assertTrue(again.isRpThirdPerson())
        assertTrue(again.isRpAutoMemory())
        assertTrue(again.isRpShowThoughts())
        assertFalse(again.isShowThinkingBlocks())
        assertTrue(again.getExpandableInput())
        assertTrue(again.getScrollersPreference())
        assertTrue(again.getScrollProgressEnabled())
        assertTrue(again.getVolumeScrollEnabled())
        assertTrue(again.getExtPreference())
        assertTrue(again.getExtPreference2())
        assertTrue(again.getExtendedTopBarEnabled())
        assertFalse(again.getStreamingPreference())
        val opts = BackgroundPhoto.readOptions(again.mainPrefs)
        assertFalse(opts.blur)
        assertTrue(opts.color)
        again.saveHapticButtons(true)
        again.saveChatMode(ChatMode.ASK)
        again.saveChatMarkStyle(SharedPreferencesHelper.CHAT_MARK_LIQUID)
        again.saveShowThinkingBlocks(true)
        assertTrue(helper().getHapticButtons())
        assertEquals(ChatMode.ASK, helper().getChatMode())
        assertEquals(SharedPreferencesHelper.CHAT_MARK_LIQUID, helper().getChatMarkStyle())
        assertTrue(helper().isShowThinkingBlocks())
    }

    @Test
    fun chatTextSizeAndLanPinSurviveAFreshHelper() {
        val prefs = helper()
        prefs.saveFontSizeCh(130)
        prefs.lanCertPinStore().savePin("192.168.1.9:11434", "sha256/abc")
        val again = helper()
        assertEquals(130, again.getFontSizeCh())
        assertEquals("sha256/abc", again.lanCertPinStore().pinFor("192.168.1.9:11434"))
        again.clearLanCertPins()
        assertNull(helper().lanCertPinStore().pinFor("192.168.1.9:11434"))
        again.saveFontSizeCh(100)
        assertEquals(100, helper().getFontSizeCh())
    }

    @Test
    fun powerToolsObserverDoesNotWriteWhileTheTapDrivesBothHalves() {
        assertFalse(powerToolsObserverWritesSwitch(userDriving = true, switchChecked = false, combinedOn = true))
        assertFalse(powerToolsObserverWritesSwitch(userDriving = false, switchChecked = true, combinedOn = true))
        assertTrue(powerToolsObserverWritesSwitch(userDriving = false, switchChecked = false, combinedOn = true))
    }

    @Test
    fun chatTextTilesStayDarkUnlessTheStoredScaleIsThatPreset() {
        val presets = listOf(90, 100, 115, 130)
        assertEquals(100, chatTextSizeTileToSelect(100, presets))
        assertEquals(130, chatTextSizeTileToSelect(130, presets))
        assertNull(chatTextSizeTileToSelect(120, presets))
        assertNull(chatTextSizeTileToSelect(50, presets))
        assertNull(chatTextSizeTileToSelect(108, presets))
    }

    @Test
    fun reasoningEffortFollowsTheMasterSwitchAndAPositiveBudget() {
        assertTrue(reasoningEffortControlsEnabled(advancedOn = true, maxTokens = null))
        assertTrue(reasoningEffortControlsEnabled(advancedOn = true, maxTokens = 0))
        assertFalse(reasoningEffortControlsEnabled(advancedOn = true, maxTokens = 256))
        assertFalse(reasoningEffortControlsEnabled(advancedOn = false, maxTokens = null))
        assertFalse(reasoningEffortControlsEnabled(advancedOn = false, maxTokens = 256))
        val prefs = helper()
        prefs.saveAdvancedReasoningEnabled(true)
        prefs.saveReasoningEffort("high")
        prefs.saveReasoningExclude(false)
        prefs.saveReasoningMaxTokens(256)
        val again = helper()
        assertTrue(again.getAdvancedReasoningEnabled())
        assertEquals("high", again.getReasoningEffort())
        assertFalse(again.getReasoningExclude())
        assertEquals(256, again.getReasoningMaxTokens())
        again.saveReasoningMaxTokens(null)
        assertNull(helper().getReasoningMaxTokens())
    }
}
