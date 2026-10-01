package io.github.stardomains3.oxproxion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

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
        val app = ApplicationProvider.getApplicationContext<Application>()
        CharacterImportSideLog.clear(CharacterImportSideLog.file(app))
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
    fun aRolledBackCharacterImportDoesNotKeepTheNotes() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val log = CharacterImportSideLog.file(app)
        CharacterImportSideLog.clear(log)
        val exported = RpCharacterExport(
            name = "Ada",
            exportKey = "ada",
            memory = "shy",
            layout = SharedPreferencesHelper.RP_LAYOUT_BUBBLES,
        )
        try {
            repo.importCharacters(listOf(exported)) { rows ->
                val row = rows.single()
                CharacterImportSideLog.write(
                    log,
                    listOf(ImportedCharacterNote(row.id, exported.name, row.exportKey, exported)),
                )
                throw IllegalStateException("killed before commit")
            }
        } catch (_: IllegalStateException) {
        }
        assertTrue(repo.getAllCharactersOnce().isEmpty())
        assertNotNull(CharacterImportSideLog.read(log))
        assertTrue(CharacterImportSideLog.resume(app, db))
        assertEquals("", prefs.getRpMemory(1L))
        assertNull(CharacterImportSideLog.read(log))

        val imported = repo.importCharacters(listOf(exported)) { rows ->
            val row = rows.single()
            CharacterImportSideLog.write(
                log,
                listOf(ImportedCharacterNote(row.id, exported.name, row.exportKey, exported)),
            )
        }
        assertTrue(CharacterImportSideLog.resume(app, db))
        assertEquals("shy", prefs.getRpMemory(imported.single().id))
        assertEquals(SharedPreferencesHelper.RP_LAYOUT_BUBBLES, prefs.getRpLayout(imported.single().id))
        assertNull(CharacterImportSideLog.read(log))

        val wrong = ImportedCharacterNote(
            imported.single().id,
            name = "Other",
            exportKey = "other",
            exported = exported.copy(name = "Other", memory = "nope"),
        )
        CharacterImportSideLog.write(log, listOf(wrong))
        assertTrue(CharacterImportSideLog.resume(app, db))
        assertEquals("shy", prefs.getRpMemory(imported.single().id))
        assertNull(CharacterImportSideLog.read(log))
        assertTrue(prefs.mainPrefs.edit().clear().commit())
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
    fun characterBackupRestoresMemoryAndWaitsForTheLorebook() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val kept = 7L
        prefs.saveRpMemory(kept, "stay")
        prefs.saveRpLayout(kept, SharedPreferencesHelper.RP_LAYOUT_BOOK)
        prefs.saveRpVoice(kept, SharedPreferencesHelper.RpVoice("alto", 0.8f, 1.1f))
        prefs.saveRpLorebookId(kept, 4L)

        val older = Json { ignoreUnknownKeys = true }.decodeFromString(
            RpCharacterBackup.serializer(),
            """{"characters":[{"name":"Mira","exportKey":"k"}]}""",
        )
        assertNull(older.characters.single().wallpaperBase64)
        RpCharacterPrefsBackup.apply(prefs, kept, older.characters.single(), emptyList())
        assertEquals("stay", prefs.getRpMemory(kept))
        assertEquals(SharedPreferencesHelper.RP_LAYOUT_BOOK, prefs.getRpLayout(kept))
        assertEquals("alto", prefs.getRpVoice(kept).name)
        assertEquals(4L, prefs.getRpLorebookId(kept))

        val exported = RpCharacterExport(
            name = "Mira",
            exportKey = "k",
            memory = "owes a favor",
            layout = SharedPreferencesHelper.RP_LAYOUT_BUBBLES,
            voiceName = "alto",
            voicePitch = 0.8f,
            voiceRate = 1.2f,
            lorebookName = "World",
        )
        val text = buildString { RpBackupWriter.writeCharacters(this, listOf(exported)) }
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString(RpCharacterBackup.serializer(), text)
            .characters.single()
        assertEquals("owes a favor", decoded.memory)
        assertEquals(SharedPreferencesHelper.RP_LAYOUT_BUBBLES, decoded.layout)

        val fresh = 8L
        prefs.saveRpMemory(fresh, "old note")
        prefs.saveRpVoice(fresh, SharedPreferencesHelper.RpVoice("tenor", 1f, 1f))
        RpCharacterPrefsBackup.apply(prefs, fresh, decoded, emptyList())
        assertEquals("owes a favor", prefs.getRpMemory(fresh))
        assertEquals(SharedPreferencesHelper.RP_LAYOUT_BUBBLES, prefs.getRpLayout(fresh))
        assertEquals("alto", prefs.getRpVoice(fresh).name)
        assertEquals(0.8f, prefs.getRpVoice(fresh).pitch, 0.001f)
        assertEquals("World", prefs.getPendingRpLorebookName(fresh))
        assertNull(prefs.getRpLorebookId(fresh))

        val bookId = repo.saveLorebook(RpLorebook(name = "world", content = "w"))
        RpCharacterPrefsBackup.bindPending(prefs, repo.getAllLorebooksOnce())
        assertEquals(bookId, prefs.getRpLorebookId(fresh))
        assertNull(prefs.getPendingRpLorebookName(fresh))

        RpCharacterPrefsBackup.apply(
            prefs,
            fresh,
            RpCharacterExport(
                name = "Mira",
                memory = "",
                layout = "nope",
                voicePitch = 99f,
                voiceRate = 1f,
                lorebookName = "",
            ),
            emptyList(),
        )
        assertEquals("", prefs.getRpMemory(fresh))
        assertEquals(SharedPreferencesHelper.RP_LAYOUT_BUBBLES, prefs.getRpLayout(fresh))
        assertNull(prefs.getRpVoice(fresh).name)
        assertEquals(1f, prefs.getRpVoice(fresh).rate, 0.001f)
        assertEquals(0.8f, prefs.getRpVoice(fresh).pitch, 0.001f)
        assertNull(prefs.getRpLorebookId(fresh))
        prefs.savePendingRpLorebookName(fresh, "later")
        prefs.clearRpCharacterPrefs(fresh)
        assertNull(prefs.getPendingRpLorebookName(fresh))
    }

    @Test
    fun characterBackupCarriesTheWallpaperAndLeavesAnOlderOne() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val bmp = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.DKGRAY)
        val jpeg = ByteArrayOutputStream().also {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        bmp.recycle()

        val kept = 3L
        val slot = BackgroundPhoto.slotForCharacter(kept)
        assertEquals("", RpWallpaperBackup.encode(BackgroundPhoto.file(app, slot)))
        assertFalse(BackgroundPhoto.writeBytes(app, slot, byteArrayOf(1, 2, 3, 4)))
        assertFalse(BackgroundPhoto.file(app, slot).exists())
        assertTrue(BackgroundPhoto.writeBytes(app, slot, jpeg))
        assertFalse(BackgroundPhoto.writeBytes(app, slot, jpeg.copyOf(8)))
        assertTrue(jpeg.contentEquals(BackgroundPhoto.file(app, slot).readBytes()))
        val encoded = RpWallpaperBackup.encode(BackgroundPhoto.file(app, slot))
        assertFalse(encoded.isNullOrBlank())

        val text = buildString {
            RpBackupWriter.writeCharacters(
                this,
                listOf(RpCharacterExport(name = "Mira", exportKey = "k", wallpaperBase64 = encoded)),
            )
        }
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString(RpCharacterBackup.serializer(), text)
            .characters.single()
        assertEquals(encoded, decoded.wallpaperBase64)

        val fresh = 9L
        val freshSlot = BackgroundPhoto.slotForCharacter(fresh)
        RpWallpaperBackup.apply(app, fresh, null)
        assertFalse(BackgroundPhoto.hasPhoto(app, freshSlot))
        RpWallpaperBackup.apply(app, fresh, "!!!!")
        assertFalse(BackgroundPhoto.hasPhoto(app, freshSlot))
        RpWallpaperBackup.apply(app, fresh, decoded.wallpaperBase64)
        assertTrue(BackgroundPhoto.hasPhoto(app, freshSlot))
        val restored = BackgroundPhoto.file(app, freshSlot).readBytes()
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(restored, 0, restored.size, bounds)
        assertEquals(12, bounds.outWidth)
        assertEquals(8, bounds.outHeight)

        RpWallpaperBackup.apply(app, fresh, "")
        assertFalse(BackgroundPhoto.hasPhoto(app, freshSlot))
        RpWallpaperBackup.apply(app, kept, "not-a-picture")
        assertTrue(BackgroundPhoto.hasPhoto(app, slot))
        RpWallpaperBackup.apply(app, kept, null)
        assertTrue(BackgroundPhoto.hasPhoto(app, slot))

        val wide = Bitmap.createBitmap(1800, 40, Bitmap.Config.ARGB_8888)
        wide.eraseColor(Color.GRAY)
        val wideJpeg = ByteArrayOutputStream().also {
            wide.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        wide.recycle()
        val wideEncoded = android.util.Base64.encodeToString(wideJpeg, android.util.Base64.NO_WRAP)
        val capped = 11L
        RpWallpaperBackup.apply(app, capped, wideEncoded)
        val cappedFile = BackgroundPhoto.file(app, BackgroundPhoto.slotForCharacter(capped))
        val cappedBounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(cappedFile.path, cappedBounds)
        assertTrue(cappedBounds.outWidth in 1..1600)
        assertTrue(cappedBounds.outHeight in 1..1600)
        assertEquals(1600, maxOf(cappedBounds.outWidth, cappedBounds.outHeight))
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
