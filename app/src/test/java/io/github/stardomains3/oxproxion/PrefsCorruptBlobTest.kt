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
}
