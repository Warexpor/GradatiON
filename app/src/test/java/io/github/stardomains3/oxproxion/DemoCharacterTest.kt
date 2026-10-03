package io.github.stardomains3.oxproxion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class DemoCharacterTest {
    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: RpRepository
    private lateinit var prefs: SharedPreferencesHelper

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = RpRepository(db.rpDao())
        prefs = SharedPreferencesHelper(ctx)
        prefs.mainPrefs.edit()
            .remove("demo_character_seeded")
            .remove("demo_character_avatar_rev")
            .commit()
    }

    @After fun tearDown() = db.close()

    @Test fun seedsOnceAndStaysDeleted() = runBlocking {
        DemoCharacter.seedOnce(repo, prefs, ctx)
        DemoCharacter.seedOnce(repo, prefs, ctx)
        val chars = repo.getAllCharactersOnce()
        assertEquals(listOf("Vesna"), chars.map { it.name })
        assertEquals(DemoCharacter.EXPORT_KEY, chars.single().exportKey)
        assertTrue(RpAvatarStorage.avatarFile(ctx, chars.single().id).exists())
        assertEquals(DemoCharacter.STOCK_AVATAR_REVISION, prefs.demoCharacterAvatarRevision())
        repo.deleteCharacter(chars.single().id)
        DemoCharacter.seedOnce(repo, prefs, ctx)
        assertEquals(0, repo.getAllCharactersOnce().size)
    }

    @Test fun upgradesLegacyStockPersonality() = runBlocking {
        val legacy = "A river cartographer who maps what the charts leave out. Dry, patient, " +
            "quietly brave; she notices everything and says half of it. Keeps her promises and " +
            "expects the same."
        val id = repo.saveCharacter(
            RpCharacter(exportKey = DemoCharacter.EXPORT_KEY, name = "Vesna", personality = legacy)
        )
        prefs.markDemoCharacterSeeded()
        DemoCharacter.seedOnce(repo, prefs, ctx)
        val updated = repo.getCharacterById(id)!!
        assertEquals(DemoCharacter.character().personality, updated.personality)
        assertEquals(id, updated.id)
        assertTrue(RpAvatarStorage.avatarFile(ctx, id).exists())
    }

    @Test fun stockAvatarDoesNotOverwriteAfterUserClears() = runBlocking {
        DemoCharacter.seedOnce(repo, prefs, ctx)
        val id = repo.getAllCharactersOnce().single().id
        RpAvatarStorage.deleteAvatar(ctx, id)
        DemoCharacter.seedOnce(repo, prefs, ctx)
        assertTrue(!RpAvatarStorage.avatarFile(ctx, id).exists())
    }

    @Test fun aPresentVesnaRepairsTheSeedFlagSoDeleteStaysGone() = runBlocking {
        val id = repo.saveCharacter(DemoCharacter.character())
        assertFalse(prefs.isDemoCharacterSeeded())
        DemoCharacter.seedOnce(repo, prefs, ctx)
        assertTrue(prefs.isDemoCharacterSeeded())
        repo.deleteCharacter(id)
        DemoCharacter.seedOnce(repo, prefs, ctx)
        assertEquals(0, repo.getAllCharactersOnce().size)
    }

    @Test fun legacyUpgradeKeepsEditsAndRefreshesStockFields() = runBlocking {
        val legacyPersonality = "A river cartographer who maps what the charts leave out. Dry, patient, " +
            "quietly brave; she notices everything and says half of it. Keeps her promises and " +
            "expects the same."
        val legacyGreeting = "*The inn door bangs open on the wind. Vesna doesn't look up from the map " +
            "spread across the table, weighted down by a lantern and two cold cups of tea.*\n\n" +
            "\"Sit. You'll want to see this before you decide anything.\""
        val legacyStyle = "Short spoken lines in quotes, actions in italics. Weather and small gestures " +
            "carry the mood. She asks one question at a time."
        val id = repo.saveCharacter(
            RpCharacter(
                exportKey = DemoCharacter.EXPORT_KEY,
                name = "V",
                personality = legacyPersonality,
                style = legacyStyle,
                greeting = "I rewrote the opening.",
                scenario = "My own room.",
                photoUri = "file:///keep.jpg",
            )
        )
        DemoCharacter.seedOnce(repo, prefs, ctx)
        val updated = repo.getCharacterById(id)!!
        assertEquals(DemoCharacter.character().personality, updated.personality)
        assertEquals(DemoCharacter.character().style, updated.style)
        assertEquals("I rewrote the opening.", updated.greeting)
        assertEquals("My own room.", updated.scenario)
        assertEquals("V", updated.name)
        assertEquals("file:///keep.jpg", updated.photoUri)
        assertEquals(DemoCharacter.EXPORT_KEY, updated.exportKey)
    }
}
