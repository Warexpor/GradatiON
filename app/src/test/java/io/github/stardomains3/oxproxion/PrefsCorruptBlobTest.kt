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
    fun takingADeletedCharacterDoesNotReplaceAnUnreadableRemap() {
        val prefs = helper()
        prefs.mainPrefs.edit().putString("rp_deleted_char_remap", "{torn").commit()

        assertNull(prefs.takeDeletedRpCharacterId("k"))
        assertEquals("{torn", prefs.mainPrefs.getString("rp_deleted_char_remap", null))
        assertFalse(prefs.mainPrefs.contains("rp_deleted_char_remap.unreadable"))
    }
}
