package io.github.stardomains3.oxproxion

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Character and lore imports are one transaction: a failure on a later row leaves the library
 * as it was. Run: ./gradlew :app:testDebugUnitTest --tests '*RpLibraryImportTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class RpLibraryImportTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: RpRepository

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RpRepository(db.rpDao())
        RpImportGuard.failAt = null
    }

    @After
    fun tearDown() {
        RpImportGuard.failAt = null
        db.close()
    }

    @Test
    fun aFailedCharacterImportRollsBackTheBatch() = runBlocking {
        repo.saveCharacter(RpCharacter(name = "Keep", exportKey = "keep"))
        RpImportGuard.failAt = 1

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repo.importCharacters(
                    listOf(
                        RpCharacterExport(name = "One", exportKey = "1"),
                        RpCharacterExport(name = "Two", exportKey = "2"),
                    )
                )
            }
        }

        assertEquals(listOf("Keep"), repo.getAllCharactersOnce().map { it.name })
    }

    @Test
    fun aRepeatedExportKeyInOneFileUpdatesTheSameCharacter() = runBlocking {
        val imported = repo.importCharacters(
            listOf(
                RpCharacterExport(name = "A", exportKey = "k", personality = "1"),
                RpCharacterExport(name = "A2", exportKey = "k", personality = "2"),
            )
        )

        assertEquals(2, imported.size)
        assertEquals(imported[0].id, imported[1].id)
        val saved = repo.getAllCharactersOnce()
        assertEquals(1, saved.size)
        assertEquals("A2", saved.single().name)
        assertEquals("2", saved.single().personality)
    }

    @Test
    fun aFailedLoreImportRollsBackTheBatch() = runBlocking {
        repo.saveLorebook(RpLorebook(name = "Keep", content = "old"))
        RpImportGuard.failAt = 1

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repo.importLorebooks(
                    listOf(
                        RpLorebookExport(name = "One", content = "1"),
                        RpLorebookExport(name = "Two", content = "2"),
                    )
                )
            }
        }

        val left = repo.getAllLorebooksOnce()
        assertEquals(listOf("Keep"), left.map { it.name })
        assertEquals("old", left.single().content)
    }

    @Test
    fun loreImportActivatesTheMarkedBookAndMergesNames() = runBlocking {
        val count = repo.importLorebooks(
            listOf(
                RpLorebookExport(name = "World", content = "first"),
                RpLorebookExport(name = "world", content = "second", isActive = true),
                RpLorebookExport(name = "Notes", content = "n"),
            )
        )

        assertEquals(3, count)
        val books = repo.getAllLorebooksOnce()
        assertEquals(2, books.size)
        val world = books.single { it.name.equals("world", ignoreCase = true) }
        assertEquals("second", world.content)
        assertTrue(world.isActive)
        assertFalse(books.single { it.name == "Notes" }.isActive)
    }

    @Test
    fun updatingTheOldActiveBookAfterANewActiveOneDoesNotLeaveTwoActive() = runBlocking {
        repo.saveLorebook(RpLorebook(name = "Notes", content = "n", isActive = true))

        repo.importLorebooks(
            listOf(
                RpLorebookExport(name = "World", content = "w", isActive = true),
                RpLorebookExport(name = "Notes", content = "n2"),
            )
        )

        val books = repo.getAllLorebooksOnce()
        assertEquals(listOf("World"), books.filter { it.isActive }.map { it.name })
        assertEquals("n2", books.single { it.name == "Notes" }.content)
    }

    @Test
    fun anEmptyLibraryActivatesTheFirstBookWhenNoneIsMarked() = runBlocking {
        repo.importLorebooks(
            listOf(
                RpLorebookExport(name = "First", content = "a"),
                RpLorebookExport(name = "Second", content = "b"),
            )
        )

        val active = repo.getAllLorebooksOnce().single { it.isActive }
        assertEquals("First", active.name)
    }

    @Test
    fun characterBackupWriterRoundTrips() {
        val text = buildString {
            RpBackupWriter.writeCharacters(
                this,
                listOf(RpCharacterExport(name = "Mira", exportKey = "k", personality = "calm")),
            )
        }
        val backup = Json { ignoreUnknownKeys = true }
            .decodeFromString(RpCharacterBackup.serializer(), text)
        assertEquals("Mira", backup.characters.single().name)
        assertEquals("calm", backup.characters.single().personality)
    }
}
