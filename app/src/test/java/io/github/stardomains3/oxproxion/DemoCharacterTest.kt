package io.github.stardomains3.oxproxion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class DemoCharacterTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: RpRepository
    private lateinit var prefs: SharedPreferencesHelper

    @Before fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = RpRepository(db.rpDao())
        prefs = SharedPreferencesHelper(ctx)
        prefs.mainPrefs.edit().remove("demo_character_seeded").commit()
    }

    @After fun tearDown() = db.close()

    @Test fun seedsOnceAndStaysDeleted() = runBlocking {
        DemoCharacter.seedOnce(repo, prefs)
        DemoCharacter.seedOnce(repo, prefs)
        val chars = repo.getAllCharactersOnce()
        assertEquals(listOf("Vesna"), chars.map { it.name })
        assertEquals(DemoCharacter.EXPORT_KEY, chars.single().exportKey)
        repo.deleteCharacter(chars.single().id)
        DemoCharacter.seedOnce(repo, prefs)
        assertEquals(0, repo.getAllCharactersOnce().size)
    }
}
