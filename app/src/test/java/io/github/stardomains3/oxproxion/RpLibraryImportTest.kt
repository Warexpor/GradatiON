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
import java.io.File

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
        CharacterImportSideLog.failPictureRestoreForTest = false
        CharacterImportSideLog.afterPrefsAppliedForTest = null
        BackgroundPhoto.failNextDeleteForTest = false
    }

    @After
    fun tearDown() {
        RpImportGuard.failAt = null
        CharacterImportSideLog.failPictureRestoreForTest = false
        CharacterImportSideLog.afterPrefsAppliedForTest = null
        BackgroundPhoto.failNextDeleteForTest = false
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
    fun aRenameWhilePicturesWaitStillMatchesByExportKey() = runBlocking {
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
            wallpaperBase64 = "",
        )
        val imported = repo.importCharacters(listOf(exported)) { rows ->
            val row = rows.single()
            CharacterImportSideLog.write(
                log,
                listOf(ImportedCharacterNote(row.id, exported.name, row.exportKey, exported)),
            )
        }
        val id = imported.single().id
        CharacterImportSideLog.failPictureRestoreForTest = true
        assertFalse(CharacterImportSideLog.resume(app, db))
        assertNotNull(CharacterImportSideLog.read(log))

        val row = repo.getCharacterById(id)!!
        repo.saveCharacter(row.copy(name = "Ada Lovelace"))
        assertTrue(
            CharacterImportSideLog.matches(
                ImportedCharacterNote(id, "Ada", "ada", exported),
                repo.getCharacterById(id),
            )
        )
        assertTrue(CharacterImportSideLog.resume(app, db))
        assertEquals("shy", prefs.getRpMemory(id))
        assertNull(CharacterImportSideLog.read(log))
        assertTrue(prefs.mainPrefs.edit().clear().commit())
    }

    @Test
    fun resumeDoesNotWipeAConcurrentImportNote() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val log = CharacterImportSideLog.file(app)
        CharacterImportSideLog.clear(log)
        val first = RpCharacterExport(
            name = "Ada",
            exportKey = "ada",
            memory = "shy",
            wallpaperBase64 = "",
        )
        val second = RpCharacterExport(
            name = "Bea",
            exportKey = "bea",
            memory = "bold",
        )
        val imported = repo.importCharacters(listOf(first, second)) { rows ->
            CharacterImportSideLog.write(
                log,
                rows.mapIndexed { index, row ->
                    val exported = listOf(first, second)[index]
                    ImportedCharacterNote(row.id, exported.name, row.exportKey, exported)
                },
            )
        }
        val adaId = imported[0].id
        val beaId = imported[1].id
        // Only Ada is in the snapshot resume will see; Bea is injected mid-resume.
        CharacterImportSideLog.write(
            log,
            listOf(ImportedCharacterNote(adaId, first.name, "ada", first)),
        )
        CharacterImportSideLog.afterPrefsAppliedForTest = {
            CharacterImportSideLog.write(
                log,
                CharacterImportSideLog.read(log).orEmpty() +
                    ImportedCharacterNote(beaId, second.name, "bea", second),
            )
        }
        assertTrue(CharacterImportSideLog.resume(app, db))
        assertEquals("shy", prefs.getRpMemory(adaId))
        // Bea's note must still be waiting — a blind clear() used to wipe it.
        val left = CharacterImportSideLog.read(log)
        assertNotNull(left)
        assertEquals(listOf(beaId), left!!.map { it.id })
        assertTrue(CharacterImportSideLog.resume(app, db))
        assertEquals("bold", prefs.getRpMemory(beaId))
        assertNull(CharacterImportSideLog.read(log))
        assertTrue(prefs.mainPrefs.edit().clear().commit())
    }

    @Test
    fun anUndecodeableWallpaperIsLeftRatherThanRetriedForever() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val log = CharacterImportSideLog.file(app)
        CharacterImportSideLog.clear(log)
        // SOI + EOI only: completeJpeg accepts it; restore must Leave, not Write/retry.
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val encoded = android.util.Base64.encodeToString(fakeJpeg, android.util.Base64.NO_WRAP)
        val exported = RpCharacterExport(
            name = "Ada",
            exportKey = "ada",
            memory = "shy",
            wallpaperBase64 = encoded,
        )
        val imported = repo.importCharacters(listOf(exported)) { rows ->
            val row = rows.single()
            CharacterImportSideLog.write(
                log,
                listOf(ImportedCharacterNote(row.id, exported.name, row.exportKey, exported)),
            )
        }
        assertTrue(CharacterImportSideLog.resume(app, db))
        assertEquals("shy", prefs.getRpMemory(imported.single().id))
        assertNull(CharacterImportSideLog.read(log))
        assertFalse(BackgroundPhoto.hasPhoto(app, BackgroundPhoto.slotForCharacter(imported.single().id)))
        assertTrue(prefs.mainPrefs.edit().clear().commit())
    }

    @Test
    fun aTornWallpaperInTheBackupIsLeftAlone() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val bmp = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.DKGRAY)
        val jpeg = ByteArrayOutputStream().also {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        bmp.recycle()
        assertTrue(jpeg.size >= 64)
        assertTrue(ScenePhoto.completeJpeg(jpeg))
        val torn = jpeg.copyOf(jpeg.size - 2) // drop EOI
        assertFalse(ScenePhoto.completeJpeg(torn))
        val encoded = android.util.Base64.encodeToString(torn, android.util.Base64.NO_WRAP)
        val kept = 21L
        val slot = BackgroundPhoto.slotForCharacter(kept)
        assertTrue(BackgroundPhoto.writeBytes(app, slot, jpeg))
        // Truncated backup must not clear or replace the phone's copy, and must not Write.
        assertEquals(RpWallpaperBackup.Restore.Leave, RpWallpaperBackup.restore(encoded))
        RpWallpaperBackup.apply(app, kept, encoded)
        assertTrue(BackgroundPhoto.hasPhoto(app, slot))
        assertTrue(jpeg.contentEquals(BackgroundPhoto.file(app, slot).readBytes()))
        assertEquals(RpWallpaperBackup.Restore.Leave, RpWallpaperBackup.restore(
            android.util.Base64.encodeToString(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
                android.util.Base64.NO_WRAP,
            )
        ))
    }

    @Test
    fun applyDoesNotInstallAStubWhenPrepareFails() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // completeJpeg + size floor pass; BitmapFactory bounds usually fail so restore Leaves.
        // When a factory invents bounds (Write), prepare still returns null for this pad —
        // apply must not fall back to writing the raw stub the way it used to.
        val stub = ByteArray(80) { 0 }
        stub[0] = 0xFF.toByte()
        stub[1] = 0xD8.toByte()
        stub[78] = 0xFF.toByte()
        stub[79] = 0xD9.toByte()
        assertTrue(ScenePhoto.completeJpeg(stub))
        assertNull(BackgroundPhoto.prepare(stub))
        val encoded = android.util.Base64.encodeToString(stub, android.util.Base64.NO_WRAP)
        val kept = 22L
        val slot = BackgroundPhoto.slotForCharacter(kept)
        BackgroundPhoto.delete(app, slot)
        RpWallpaperBackup.apply(app, kept, encoded)
        assertFalse(BackgroundPhoto.hasPhoto(app, slot))
    }

    @Test
    fun encodeAvatarSkipsATornPortrait() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val id = 33L
        val file = RpAvatarStorage.avatarFile(app, id)
        file.parentFile?.mkdirs()
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.GRAY)
        val jpeg = ByteArrayOutputStream().also {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        bmp.recycle()
        assertTrue(jpeg.size >= 64)
        file.writeBytes(jpeg.copyOf(jpeg.size - 2)) // drop EOI
        assertFalse(ScenePhoto.completeJpeg(file))
        assertNull(RpAvatarStorage.encodeAvatarBase64(app, id))
        file.delete()
    }

    @Test
    fun anUndecodeablePortraitIsLeftRatherThanRetriedForever() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val log = CharacterImportSideLog.file(app)
        CharacterImportSideLog.clear(log)
        // SOI + EOI only: completeJpeg accepts it, BitmapFactory cannot decode it.
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val encoded = android.util.Base64.encodeToString(fakeJpeg, android.util.Base64.NO_WRAP)
        val exported = RpCharacterExport(
            name = "Ada",
            exportKey = "ada",
            memory = "shy",
            avatarBase64 = encoded,
        )
        val imported = repo.importCharacters(listOf(exported)) { rows ->
            val row = rows.single()
            CharacterImportSideLog.write(
                log,
                listOf(ImportedCharacterNote(row.id, exported.name, row.exportKey, exported)),
            )
        }
        assertTrue(CharacterImportSideLog.resume(app, db))
        assertEquals("shy", prefs.getRpMemory(imported.single().id))
        assertNull(CharacterImportSideLog.read(log))
        assertTrue(prefs.mainPrefs.edit().clear().commit())
    }

    @Test
    fun aFailedPictureRestoreLeavesTheLogForRetry() = runBlocking {
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
            wallpaperBase64 = "",
        )
        val imported = repo.importCharacters(listOf(exported)) { rows ->
            val row = rows.single()
            CharacterImportSideLog.write(
                log,
                listOf(ImportedCharacterNote(row.id, exported.name, row.exportKey, exported)),
            )
        }
        CharacterImportSideLog.failPictureRestoreForTest = true
        assertFalse(CharacterImportSideLog.resume(app, db))
        assertEquals("shy", prefs.getRpMemory(imported.single().id))
        assertNotNull(CharacterImportSideLog.read(log))

        assertTrue(CharacterImportSideLog.resume(app, db))
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

    @Test
    fun aRepeatedExportKeyDoesNotReapplyTheFirstNotesNextLaunch() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val log = CharacterImportSideLog.file(app)
        CharacterImportSideLog.clear(log)
        val first = RpCharacterExport(name = "A", exportKey = "k", memory = "one", personality = "1")
        val second = RpCharacterExport(name = "A2", exportKey = "k", memory = "two", personality = "2")
        val imported = repo.importCharacters(listOf(first, second)) { rows ->
            CharacterImportSideLog.write(
                log,
                rows.mapIndexed { index, row ->
                    val exported = listOf(first, second)[index]
                    ImportedCharacterNote(row.id, exported.name, row.exportKey, exported)
                },
            )
        }
        assertEquals(imported[0].id, imported[1].id)
        assertTrue(CharacterImportSideLog.resume(app, db))
        assertEquals("two", prefs.getRpMemory(imported[0].id))
        assertNull(CharacterImportSideLog.read(log))
        assertTrue(CharacterImportSideLog.resume(app, db))
        assertEquals("two", prefs.getRpMemory(imported[0].id))
        assertTrue(prefs.mainPrefs.edit().clear().commit())
    }

    @Test
    fun aLoreBackupWithNothingActiveTurnsTheLocalBookOff() = runBlocking {
        repo.saveLorebook(RpLorebook(name = "Notes", content = "old", isActive = true))
        repo.importLorebooks(
            listOf(
                RpLorebookExport(name = "Notes", content = "n2", isActive = false),
                RpLorebookExport(name = "World", content = "w", isActive = false),
            )
        )
        val books = repo.getAllLorebooksOnce()
        assertEquals(2, books.size)
        assertTrue(books.none { it.isActive })
        assertEquals("n2", books.single { it.name == "Notes" }.content)
    }

    @Test
    fun loreImportMergesANameThatOnlyDiffersBySpaces() = runBlocking {
        repo.saveLorebook(RpLorebook(name = "World", content = "old"))
        repo.importLorebooks(listOf(RpLorebookExport(name = " world ", content = "new")))
        val books = repo.getAllLorebooksOnce()
        assertEquals(1, books.size)
        assertEquals("world", books.single().name)
        assertEquals("new", books.single().content)
    }

    @Test
    fun aBlankLoreNameIsNotImported() = runBlocking {
        repo.importLorebooks(
            listOf(
                RpLorebookExport(name = "   ", content = "nope"),
                RpLorebookExport(name = "Notes", content = "n"),
            )
        )
        val books = repo.getAllLorebooksOnce()
        assertEquals(listOf("Notes"), books.map { it.name })
    }

    @Test
    fun aLongOrPaddedLorePinStillAttaches() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val id = 12L
        val longName = "L".repeat(250)
        RpCharacterPrefsBackup.apply(
            prefs,
            id,
            RpCharacterExport(name = "Mira", lorebookName = longName),
            emptyList(),
        )
        assertEquals(longName.take(200), prefs.getPendingRpLorebookName(id))
        val bookId = repo.saveLorebook(RpLorebook(name = longName, content = "w"))
        RpCharacterPrefsBackup.bindPending(prefs, repo.getAllLorebooksOnce())
        assertEquals(bookId, prefs.getRpLorebookId(id))
        assertNull(prefs.getPendingRpLorebookName(id))

        val fresh = 13L
        val paddedId = repo.saveLorebook(RpLorebook(name = "World", content = "w"))
        RpCharacterPrefsBackup.apply(
            prefs,
            fresh,
            RpCharacterExport(name = "Mira", lorebookName = " World "),
            repo.getAllLorebooksOnce(),
        )
        assertEquals(paddedId, prefs.getRpLorebookId(fresh))
        assertNull(prefs.getPendingRpLorebookName(fresh))
        assertTrue(prefs.mainPrefs.edit().clear().commit())
    }

    @Test
    fun theLastInactiveCopyOfALorebookDoesNotStayOn() = runBlocking {
        repo.saveLorebook(RpLorebook(name = "Notes", content = "n", isActive = true))
        repo.importLorebooks(
            listOf(
                RpLorebookExport(name = "World", content = "first", isActive = true),
                RpLorebookExport(name = " world ", content = "second", isActive = false),
            )
        )
        val books = repo.getAllLorebooksOnce()
        val world = books.single { it.name.equals("world", ignoreCase = true) }
        assertEquals("world", world.name)
        assertEquals("second", world.content)
        assertFalse(world.isActive)
        // The earlier duplicate must not have cleared the book that was already active.
        assertTrue(books.single { it.name == "Notes" }.isActive)
    }

    @Test
    fun choosingALorePinDropsTheNameTheBackupWasWaitingToAttach() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val id = 21L
        RpCharacterPrefsBackup.apply(
            prefs,
            id,
            RpCharacterExport(name = "Mira", lorebookName = "Forest"),
            emptyList(),
        )
        assertEquals("Forest", prefs.getPendingRpLorebookName(id))
        val city = repo.saveLorebook(RpLorebook(name = "City", content = "c"))
        prefs.saveRpLorebookId(id, city)
        assertNull(prefs.getPendingRpLorebookName(id))
        repo.saveLorebook(RpLorebook(name = "Forest", content = "f"))
        RpCharacterPrefsBackup.bindPending(prefs, repo.getAllLorebooksOnce())
        assertEquals(city, prefs.getRpLorebookId(id))

        RpCharacterPrefsBackup.apply(
            prefs,
            id,
            RpCharacterExport(name = "Mira", lorebookName = "Forest"),
            emptyList(),
        )
        assertEquals("Forest", prefs.getPendingRpLorebookName(id))
        prefs.saveRpLorebookId(id, null)
        assertNull(prefs.getPendingRpLorebookName(id))
        RpCharacterPrefsBackup.bindPending(prefs, repo.getAllLorebooksOnce())
        assertNull(prefs.getRpLorebookId(id))
        assertTrue(prefs.mainPrefs.edit().clear().commit())
    }

    @Test
    fun anEmptyPortraitClearsTheOldOneAndAMissingFieldLeavesIt() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val log = CharacterImportSideLog.file(app)
        CharacterImportSideLog.clear(log)
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.MAGENTA)
        val jpeg = ByteArrayOutputStream().also {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        bmp.recycle()
        assertTrue(jpeg.size >= 64)
        val id = repo.saveCharacter(RpCharacter(name = "Ada", exportKey = "ada"))
        val file = RpAvatarStorage.avatarFile(app, id)
        file.writeBytes(jpeg)
        val uri = file.toURI().toString()
        repo.saveCharacter(repo.getCharacterById(id)!!.copy(photoUri = uri))
        assertTrue(RpAvatarStorage.hasAvatar(app, id))
        assertEquals("", RpAvatarStorage.encodeAvatarBase64(app, 404L))

        val keep = RpCharacterExport(name = "Ada", exportKey = "ada", avatarBase64 = null)
        repo.importCharacters(listOf(keep)) { rows ->
            val row = rows.single()
            CharacterImportSideLog.write(
                log,
                listOf(ImportedCharacterNote(row.id, keep.name, row.exportKey, keep)),
            )
        }
        assertTrue(CharacterImportSideLog.resume(app, db))
        assertTrue(RpAvatarStorage.hasAvatar(app, id))
        assertEquals(uri, repo.getCharacterById(id)!!.photoUri)

        val clear = RpCharacterExport(name = "Ada", exportKey = "ada", avatarBase64 = "")
        repo.importCharacters(listOf(clear)) { rows ->
            val row = rows.single()
            CharacterImportSideLog.write(
                log,
                listOf(ImportedCharacterNote(row.id, clear.name, row.exportKey, clear)),
            )
        }
        assertTrue(CharacterImportSideLog.resume(app, db))
        assertFalse(RpAvatarStorage.hasAvatar(app, id))
        assertNull(repo.getCharacterById(id)!!.photoUri)
        assertEquals("", RpAvatarStorage.encodeAvatarBase64(app, id))
        file.delete()
        assertTrue(prefs.mainPrefs.edit().clear().commit())
    }

    @Test
    fun aWaitingLorePinReplacesTheBookAlreadyPinned() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val id = 31L
        val city = repo.saveLorebook(RpLorebook(name = "City", content = "c"))
        prefs.saveRpLorebookId(id, city)
        RpCharacterPrefsBackup.apply(
            prefs,
            id,
            RpCharacterExport(name = "Mira", lorebookName = "Forest"),
            emptyList(),
        )
        assertNull(prefs.getRpLorebookId(id))
        assertEquals("Forest", prefs.getPendingRpLorebookName(id))
        val exportedName = prefs.getRpLorebookId(id)?.let { pinned ->
            repo.getAllLorebooksOnce().associate { it.id to it.name }[pinned]
        } ?: prefs.getPendingRpLorebookName(id) ?: ""
        assertEquals("Forest", exportedName)

        val forest = repo.saveLorebook(RpLorebook(name = "Forest", content = "f"))
        RpCharacterPrefsBackup.bindPending(prefs, repo.getAllLorebooksOnce())
        assertEquals(forest, prefs.getRpLorebookId(id))
        assertNull(prefs.getPendingRpLorebookName(id))
        assertTrue(prefs.mainPrefs.edit().clear().commit())
    }

    @Test
    fun aFailedPortraitWriteOverAnExistingOneIsRetried() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val log = CharacterImportSideLog.file(app)
        CharacterImportSideLog.clear(log)
        val oldJpeg = solidJpeg(Color.MAGENTA)
        val newJpeg = solidJpeg(Color.CYAN)
        assertTrue(oldJpeg.size >= 64 && newJpeg.size >= 64)
        assertFalse(oldJpeg.contentEquals(newJpeg))
        val id = repo.saveCharacter(RpCharacter(name = "Ada", exportKey = "ada"))
        val file = RpAvatarStorage.avatarFile(app, id)
        file.writeBytes(oldJpeg)
        val uri = file.toURI().toString()
        repo.saveCharacter(repo.getCharacterById(id)!!.copy(photoUri = uri))
        val encoded = android.util.Base64.encodeToString(newJpeg, android.util.Base64.NO_WRAP)
        val incoming = RpCharacterExport(name = "Ada", exportKey = "ada", avatarBase64 = encoded)
        repo.importCharacters(listOf(incoming)) { rows ->
            val row = rows.single()
            CharacterImportSideLog.write(
                log,
                listOf(ImportedCharacterNote(row.id, incoming.name, row.exportKey, incoming)),
            )
        }
        ScenePhoto.failNextRenameForTest()
        assertFalse(CharacterImportSideLog.resume(app, db))
        assertTrue(oldJpeg.contentEquals(file.readBytes()))
        assertEquals(uri, repo.getCharacterById(id)!!.photoUri)
        assertNotNull(CharacterImportSideLog.read(log))

        assertTrue(CharacterImportSideLog.resume(app, db))
        assertNull(CharacterImportSideLog.read(log))
        assertTrue(RpAvatarStorage.hasAvatar(app, id))
        assertFalse(oldJpeg.contentEquals(file.readBytes()))
        assertNotNull(repo.getCharacterById(id)!!.photoUri)
        file.delete()
        assertTrue(prefs.mainPrefs.edit().clear().commit())
    }

    @Test
    fun aSecondCopyOfALorebookTakesTheImportedTextAndActiveFlag() = runBlocking {
        val older = repo.saveLorebook(RpLorebook(name = "World", content = "old", isActive = true))
        val newer = repo.saveLorebook(RpLorebook(name = "World", content = "new", isActive = false))
        val notes = repo.saveLorebook(RpLorebook(name = "Notes", content = "n", isActive = true))
        repo.importLorebooks(
            listOf(RpLorebookExport(name = " world ", content = "imported", isActive = false))
        )
        assertEquals("imported", repo.getLorebookById(older)!!.content)
        assertEquals("imported", repo.getLorebookById(newer)!!.content)
        assertEquals("world", repo.getLorebookById(older)!!.name)
        assertTrue(repo.getAllLorebooksOnce().filter { it.name.equals("world", ignoreCase = true) }.none { it.isActive })
        assertEquals("n", repo.getLorebookById(notes)!!.content)
        assertTrue(repo.getLorebookById(notes)!!.isActive)
    }

    @Test
    fun loreExportKeepsTheNewestTextAndAnActiveCopy() {
        val older = RpLorebook(
            id = 1,
            name = "World",
            content = "old",
            isActive = true,
            updatedAt = 1,
        )
        val newer = RpLorebook(
            id = 2,
            name = " world ",
            content = "new",
            isActive = false,
            updatedAt = 2,
        )
        val notes = RpLorebook(id = 3, name = "Notes", content = "n", isActive = false, updatedAt = 3)
        val exported = RpLoreBackup.exports(listOf(older, newer, notes))
        assertEquals(listOf("world", "Notes"), exported.map { it.name })
        val world = exported.single { it.name.equals("world", ignoreCase = true) }
        assertEquals("new", world.content)
        assertTrue(world.isActive)
        assertFalse(exported.single { it.name == "Notes" }.isActive)
    }

    @Test
    fun aCharacterBackupKeepsTheLorePinOnTheCopyAlreadyChosen() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val older = db.rpDao().insertLorebook(
            RpLorebook(name = "World", content = "pinned text", isActive = false, updatedAt = 1, createdAt = 1),
        )
        db.rpDao().insertLorebook(
            RpLorebook(name = "World", content = "other text", isActive = true, updatedAt = 9, createdAt = 9),
        )
        val id = 45L
        prefs.saveRpLorebookId(id, older)
        val books = repo.getAllLorebooksOnce()
        RpCharacterPrefsBackup.apply(
            prefs,
            id,
            RpCharacterExport(name = "Mira", lorebookName = "World"),
            books,
        )
        assertEquals(older, prefs.getRpLorebookId(id))
        assertNull(prefs.getPendingRpLorebookName(id))

        val notes = db.rpDao().insertLorebook(
            RpLorebook(name = "Notes", content = "n", updatedAt = 3, createdAt = 3),
        )
        RpCharacterPrefsBackup.apply(
            prefs,
            id,
            RpCharacterExport(name = "Mira", lorebookName = "Notes"),
            repo.getAllLorebooksOnce(),
        )
        assertEquals(notes, prefs.getRpLorebookId(id))
        assertNull(prefs.getPendingRpLorebookName(id))

        RpCharacterPrefsBackup.apply(
            prefs,
            id,
            RpCharacterExport(name = "Mira", lorebookName = "Forest"),
            repo.getAllLorebooksOnce(),
        )
        assertNull(prefs.getRpLorebookId(id))
        assertEquals("Forest", prefs.getPendingRpLorebookName(id))
        assertTrue(prefs.mainPrefs.edit().clear().commit())
    }

    @Test
    fun aWaitingLorePinAttachesToTheActiveCopyNotTheNewerOne() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val older = db.rpDao().insertLorebook(
            RpLorebook(name = "World", content = "old", isActive = true, updatedAt = 1, createdAt = 1),
        )
        db.rpDao().insertLorebook(
            RpLorebook(name = " world ", content = "new", isActive = false, updatedAt = 9, createdAt = 9),
        )
        val books = repo.getAllLorebooksOnce()
        assertEquals(older, books.last { it.name.equals("world", ignoreCase = true) }.id)
        val fresh = 46L
        RpCharacterPrefsBackup.apply(
            prefs,
            fresh,
            RpCharacterExport(name = "Mira", lorebookName = " World "),
            books,
        )
        assertEquals(older, prefs.getRpLorebookId(fresh))
        assertNull(prefs.getPendingRpLorebookName(fresh))

        val waiting = 47L
        prefs.savePendingRpLorebookName(waiting, "World")
        assertNull(prefs.getRpLorebookId(waiting))
        RpCharacterPrefsBackup.bindPending(prefs, books)
        assertEquals(older, prefs.getRpLorebookId(waiting))
        assertNull(prefs.getPendingRpLorebookName(waiting))
        assertTrue(prefs.mainPrefs.edit().clear().commit())
    }

    @Test
    fun aFailedWallpaperClearIsRetried() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val log = CharacterImportSideLog.file(app)
        CharacterImportSideLog.clear(log)
        val jpeg = solidJpeg(Color.DKGRAY)
        val id = repo.saveCharacter(RpCharacter(name = "Ada", exportKey = "ada"))
        val slot = BackgroundPhoto.slotForCharacter(id)
        assertTrue(BackgroundPhoto.writeBytes(app, slot, jpeg))
        val clear = RpCharacterExport(name = "Ada", exportKey = "ada", wallpaperBase64 = "")
        repo.importCharacters(listOf(clear)) { rows ->
            val row = rows.single()
            CharacterImportSideLog.write(
                log,
                listOf(ImportedCharacterNote(row.id, clear.name, row.exportKey, clear)),
            )
        }
        BackgroundPhoto.failNextDeleteForTest = true
        assertFalse(CharacterImportSideLog.resume(app, db))
        assertTrue(BackgroundPhoto.hasPhoto(app, slot))
        assertTrue(jpeg.contentEquals(BackgroundPhoto.file(app, slot).readBytes()))
        assertNotNull(CharacterImportSideLog.read(log))

        assertTrue(CharacterImportSideLog.resume(app, db))
        assertFalse(BackgroundPhoto.hasPhoto(app, slot))
        assertNull(CharacterImportSideLog.read(log))
        assertTrue(prefs.mainPrefs.edit().clear().commit())
    }

    @Test
    fun aPaddedCharacterNameIsTrimmedAndABlankNameIsSkipped() = runBlocking {
        val mira = repo.saveCharacter(RpCharacter(name = "Mira", exportKey = "mira", personality = "keep"))
        val imported = repo.importCharacters(
            listOf(
                RpCharacterExport(name = "  Mira  ", exportKey = "mira", personality = "new"),
                RpCharacterExport(name = " \n ", exportKey = "ghost", personality = "nope"),
                RpCharacterExport(name = " Ada ", exportKey = "ada", personality = "calm"),
            ),
        )
        assertEquals(listOf("mira", "ada"), imported.map { it.exportKey })
        val saved = repo.getAllCharactersOnce()
        assertEquals(setOf("Mira", "Ada"), saved.map { it.name }.toSet())
        assertEquals("new", repo.getCharacterById(mira)!!.personality)
        assertEquals("Ada", saved.single { it.exportKey == "ada" }.name)
        assertTrue(saved.none { it.exportKey == "ghost" || it.personality == "nope" })
    }

    @Test
    fun aRolledBackUpdateDoesNotApplyNotesOntoTheOldCard() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val log = CharacterImportSideLog.file(app)
        CharacterImportSideLog.clear(log)
        val id = repo.saveCharacter(RpCharacter(name = "Ada", exportKey = "ada", personality = "old"))
        prefs.saveRpMemory(id, "old note")
        val first = RpCharacterExport(
            name = "Ada",
            exportKey = "ada",
            personality = "mid",
            memory = "mid",
        )
        val second = RpCharacterExport(
            name = "Ada",
            exportKey = "ada",
            personality = "new",
            memory = "new note",
        )
        val incoming = listOf(first, second)
        try {
            repo.importCharacters(incoming) { rows ->
                CharacterImportSideLog.write(log, CharacterImportSideLog.notesFor(rows, incoming))
                throw IllegalStateException("killed before commit")
            }
        } catch (_: IllegalStateException) {
        }
        assertEquals("old", repo.getCharacterById(id)!!.personality)
        assertNotNull(CharacterImportSideLog.read(log))
        assertTrue(CharacterImportSideLog.resume(app, db))
        assertEquals("old note", prefs.getRpMemory(id))
        assertEquals("old", repo.getCharacterById(id)!!.personality)
        assertNull(CharacterImportSideLog.read(log))
        assertTrue(prefs.mainPrefs.edit().clear().commit())
    }

    @Test
    fun aLaterEditStillReceivesNotesThatWereWaiting() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val log = CharacterImportSideLog.file(app)
        CharacterImportSideLog.clear(log)
        val id = repo.saveCharacter(RpCharacter(name = "Ada", exportKey = "ada", personality = "old"))
        db.rpDao().updateCharacter(repo.getCharacterById(id)!!.copy(updatedAt = 1L))
        val exported = RpCharacterExport(
            name = "Ada",
            exportKey = "ada",
            personality = "new",
            memory = "shy",
            wallpaperBase64 = "",
        )
        repo.importCharacters(listOf(exported)) { rows ->
            CharacterImportSideLog.write(log, CharacterImportSideLog.notesFor(rows, listOf(exported)))
        }
        assertEquals("new", repo.getCharacterById(id)!!.personality)
        CharacterImportSideLog.failPictureRestoreForTest = true
        assertFalse(CharacterImportSideLog.resume(app, db))
        assertEquals("shy", prefs.getRpMemory(id))
        repo.saveCharacter(repo.getCharacterById(id)!!.copy(name = "Ada Lovelace"))
        assertTrue(
            CharacterImportSideLog.matches(
                CharacterImportSideLog.read(log)!!.single(),
                repo.getCharacterById(id),
            )
        )
        assertTrue(CharacterImportSideLog.resume(app, db))
        assertEquals("shy", prefs.getRpMemory(id))
        assertEquals("Ada Lovelace", repo.getCharacterById(id)!!.name)
        assertNull(CharacterImportSideLog.read(log))
        assertTrue(prefs.mainPrefs.edit().clear().commit())
    }

    @Test
    fun notesForDoesNotGiveTheNextCharacterABlankRowsPictures() = runBlocking {
        val incoming = listOf(
            RpCharacterExport(name = "Ada", exportKey = "ada", memory = "a"),
            RpCharacterExport(name = " \n ", exportKey = "ghost", memory = "nope"),
            RpCharacterExport(name = "Bea", exportKey = "bea", memory = "b"),
        )
        val imported = repo.importCharacters(incoming)
        val notes = CharacterImportSideLog.notesFor(imported, incoming)
        assertEquals(listOf("ada", "bea"), notes.map { it.exportKey })
        assertEquals(listOf("a", "b"), notes.map { it.exported.memory })
        assertEquals(imported[0].id, notes[0].id)
        assertEquals(imported[1].id, notes[1].id)
        assertEquals(imported[0].writtenUpdatedAt, notes[0].writtenUpdatedAt)
        assertEquals(imported[1].previousUpdatedAt, notes[1].previousUpdatedAt)
    }

    @Test
    fun anOversizedWallpaperIsScaledIntoTheBackup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val jpeg = solidJpeg(Color.DKGRAY)
        val padded = padJpeg(jpeg, 20_000)
        assertTrue(padded.size > jpeg.size + 10_000)
        assertTrue(ScenePhoto.completeJpeg(padded))
        val file = File(app.cacheDir, "oversized-wallpaper.jpg")
        file.writeBytes(padded)
        val encoded = RpWallpaperBackup.encode(file, padded.size - 1)
        assertFalse(encoded.isNullOrBlank())
        val out = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        assertTrue(out.size < padded.size)
        assertTrue(ScenePhoto.completeJpeg(out))
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(out, 0, out.size, bounds)
        assertEquals(8, bounds.outWidth)
        assertEquals(8, bounds.outHeight)
        file.delete()
    }

    @Test
    fun anOversizedPortraitIsScaledIntoTheBackup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val jpeg = solidJpeg(Color.CYAN)
        val padded = padJpeg(jpeg, 20_000)
        val id = 91L
        val file = RpAvatarStorage.avatarFile(app, id)
        file.writeBytes(padded)
        val encoded = RpAvatarStorage.encodeAvatarBase64(app, id, padded.size - 1)
        assertFalse(encoded.isNullOrBlank())
        val out = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        assertTrue(out.size < padded.size)
        assertTrue(ScenePhoto.completeJpeg(out))
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(out, 0, out.size, bounds)
        assertTrue(bounds.outWidth in 1..512)
        assertTrue(bounds.outHeight in 1..512)
        file.delete()
    }

    /** A JPEG comment after the start marker, so the file is large but still a picture. */
    private fun padJpeg(jpeg: ByteArray, extra: Int): ByteArray {
        val len = extra + 2
        val segment = ByteArray(4 + extra)
        segment[0] = 0xFF.toByte()
        segment[1] = 0xFE.toByte()
        segment[2] = (len shr 8).toByte()
        segment[3] = (len and 0xFF).toByte()
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun solidJpeg(color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        val jpeg = ByteArrayOutputStream().also {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        bmp.recycle()
        return jpeg
    }
}
