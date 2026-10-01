package io.github.stardomains3.oxproxion

import android.app.Application
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The pieces of "never crash-loop on an unreadable chat database" that run without the SQLCipher
 * native library: setting the files aside (never deleting them), and the one-shot notice flag.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*ChatDatabaseRecoveryTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class ChatDatabaseRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun unreadableFilesAreMovedAsideWithTheirSidecarsAndNothingIsDeleted() {
        val db = tmp.newFile("chat_database").apply { writeText("main") }
        File(db.path + "-wal").writeText("wal")
        File(db.path + "-shm").writeText("shm")

        val moved = AppDatabase.setAside(db, 1234L)!!

        assertEquals("chat_database.unreadable-1234", moved.name)
        assertEquals("main", moved.readText())
        assertEquals("wal", File(moved.path + "-wal").readText())
        assertEquals("shm", File(moved.path + "-shm").readText())
        assertFalse(db.exists())
        assertFalse(File(db.path + "-wal").exists())
        assertFalse(File(db.path + "-shm").exists())
    }

    @Test
    fun nothingToMoveIsNotAnError() {
        val db = File(tmp.root, "chat_database")
        assertNull(AppDatabase.setAside(db, 1L))
    }

    @Test
    fun twoRecoveriesKeepBothOldDatabases() {
        val db = tmp.newFile("chat_database").apply { writeText("first") }
        AppDatabase.setAside(db, 1L)
        db.writeText("second")
        AppDatabase.setAside(db, 2L)

        assertEquals("first", File(tmp.root, "chat_database.unreadable-1").readText())
        assertEquals("second", File(tmp.root, "chat_database.unreadable-2").readText())
    }

    @Test
    fun aStampThatIsAlreadyTakenUsesTheNextFreeOne() {
        val db = tmp.newFile("chat_database").apply { writeText("first") }
        AppDatabase.setAside(db, 5L)
        db.writeText("second")
        val moved = AppDatabase.setAside(db, 5L)!!

        assertEquals("chat_database.unreadable-6", moved.name)
        assertEquals("second", moved.readText())
        assertEquals("first", File(tmp.root, "chat_database.unreadable-5").readText())
    }

    @Test
    fun aFailedMovePutsTheFilesBack() {
        val db = tmp.newFile("chat_database").apply { writeText("main") }
        File(db.path + "-wal").writeText("wal")
        AppDatabase.movesBeforeFailure = 1
        try {
            assertThrows(Exception::class.java) { AppDatabase.setAside(db, 9L) }
        } finally {
            AppDatabase.movesBeforeFailure = null
        }

        assertEquals("main", db.readText())
        assertEquals("wal", File(db.path + "-wal").readText())
        assertFalse(File(tmp.root, "chat_database.unreadable-9").exists())
        assertFalse(File(tmp.root, "chat_database.unreadable-9-wal").exists())
    }

    @Test
    fun theRecoveryPassphraseIsArchivedAndTheActiveCopyCanBeDropped() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("ApiKeysPrefsStore", 0)
        prefs.edit().clear().commit()
        prefs.edit()
            .putString("chat_db_passphrase_encrypted", "wrapped-key")
            .putString("chat_db_passphrase_iv", "wrapped-iv")
            .commit()

        val helper = SharedPreferencesHelper(app)
        assertTrue(helper.hasWrappedChatDbPassphrase())
        assertTrue(helper.archiveChatDbPassphrase(42L))
        assertTrue(helper.hasArchivedChatDbPassphrase(42L))
        assertFalse(helper.hasArchivedChatDbPassphrase(43L))
        helper.discardActiveChatDbPassphrase()
        assertFalse(helper.hasWrappedChatDbPassphrase())

        val prefix = SharedPreferencesHelper.chatDbPassphraseArchivePrefix(42L)
        assertEquals("wrapped-key", prefs.getString("${prefix}_encrypted", null))
        assertEquals("wrapped-iv", prefs.getString("${prefix}_iv", null))
        assertNull(prefs.getString("chat_db_passphrase_encrypted", null))
        assertNull(prefs.getString("chat_db_passphrase_iv", null))
        assertFalse(helper.archiveChatDbPassphrase(43L))
    }

    @Test
    fun aFailedArchiveDoesNotReplaceThePassphrase() {
        assertFalse(AppDatabase.replacesPassphraseAfterRecovery(archiveSaved = false, wrappedPresent = true))
        assertTrue(AppDatabase.replacesPassphraseAfterRecovery(archiveSaved = true, wrappedPresent = true))
        assertTrue(AppDatabase.replacesPassphraseAfterRecovery(archiveSaved = false, wrappedPresent = false))
    }

    @Test
    fun aMissingDatabaseIsRestoredFromThePlaintextCopy() {
        val db = File(tmp.root, "chat_database")
        val backup = File(tmp.root, "chat_database.pre_sqlcipher")
        backup.writeBytes(sqliteHeader("rest"))
        assertTrue(AppDatabase.shouldRestorePlaintextBackup(db))
        assertTrue(AppDatabase.restorePlaintextBackup(db))
        assertEquals("rest", String(db.readBytes().copyOfRange(16, db.length().toInt()), Charsets.US_ASCII))
        assertFalse(backup.exists())
    }

    @Test
    fun anEmptyDatabaseFileIsRestoredFromThePlaintextCopy() {
        val db = tmp.newFile("chat_database")
        File(tmp.root, "chat_database.pre_sqlcipher").writeBytes(sqliteHeader("old"))
        assertTrue(AppDatabase.shouldRestorePlaintextBackup(db))
        assertTrue(AppDatabase.restorePlaintextBackup(db))
        assertEquals("old", String(db.readBytes().copyOfRange(16, db.length().toInt()), Charsets.US_ASCII))
    }

    @Test
    fun aPresentDatabaseIsNotReplacedByThePlaintextCopy() {
        val db = tmp.newFile("chat_database")
        db.writeBytes(sqliteHeader("live"))
        File(tmp.root, "chat_database.pre_sqlcipher").writeBytes(sqliteHeader("old"))
        assertFalse(AppDatabase.shouldRestorePlaintextBackup(db))
    }

    @Test
    fun aWalSidecarBlocksRestoringOverAMissingMainFile() {
        val db = File(tmp.root, "chat_database")
        File(db.path + "-wal").writeText("wal")
        File(tmp.root, "chat_database.pre_sqlcipher").writeBytes(sqliteHeader("old"))
        assertFalse(AppDatabase.shouldRestorePlaintextBackup(db))
    }

    @Test
    fun thePlaintextCopyStaysUntilEncryptIsConfirmed() {
        val db = tmp.newFile("chat_database")
        val backup = File(tmp.root, "chat_database.pre_sqlcipher").apply { writeText("plain") }
        AppDatabase.discardPlaintextBackupIfConfirmed(db)
        assertEquals("plain", backup.readText())
        AppDatabase.confirmPlaintextBackupDisposable(db)
        AppDatabase.discardPlaintextBackupIfConfirmed(db)
        assertFalse(backup.exists())
        assertFalse(File(tmp.root, "chat_database.encrypt_ok").exists())
    }

    @Test
    fun aPassphraseMustBe32Bytes() {
        val key = ByteArray(32) { it.toByte() }
        val encoded = Base64.encodeToString(key, Base64.NO_WRAP)
        assertArrayEquals(key, SharedPreferencesHelper.decodeChatDbPassphrase(encoded))
        // DEFAULT encoding inserts newlines; that still has to decode to the same key.
        val wrapped = Base64.encodeToString(key, Base64.DEFAULT)
        assertArrayEquals(key, SharedPreferencesHelper.decodeChatDbPassphrase(wrapped))
        assertThrows(IllegalStateException::class.java) {
            SharedPreferencesHelper.decodeChatDbPassphrase(
                Base64.encodeToString(ByteArray(8), Base64.NO_WRAP)
            )
        }
    }

    @Test
    fun aPendingRecoveryIsClearedWhenTheNoticeIsRecorded() {
        val prefs = SharedPreferencesHelper(ApplicationProvider.getApplicationContext<Application>())
        assertNull(prefs.recoveryPendingStamp())
        prefs.markRecoveryPending(99L)
        prefs.markChatDbQuarantineDue()
        assertEquals(99L, prefs.recoveryPendingStamp())
        assertTrue(prefs.isChatDbQuarantineDue())
        prefs.markChatDbRecovered()
        assertNull(prefs.recoveryPendingStamp())
        assertFalse(prefs.isChatDbQuarantineDue())
        assertTrue(prefs.consumeChatDbRecovered())
    }

    @Test
    fun anEmptyFreshDatabaseIsQuarantinedEvenWhenNothingWasMovedAside() {
        // The old file could not be moved, so there is no aside copy. The new file is a
        // recovered name, or the quarantine flag was set before Room created it.
        assertTrue(AppDatabase.shouldQuarantineInterruptedRecovery(
            databaseEmpty = true, asideExists = false, openedRecoveredFile = true, quarantineArmed = false
        ))
        assertTrue(AppDatabase.shouldQuarantineInterruptedRecovery(
            databaseEmpty = true, asideExists = false, openedRecoveredFile = false, quarantineArmed = true
        ))
        assertTrue(AppDatabase.shouldQuarantineInterruptedRecovery(
            databaseEmpty = true, asideExists = true, openedRecoveredFile = false, quarantineArmed = false
        ))
        // The original database opened and already has rows. Its notes stay.
        assertFalse(AppDatabase.shouldQuarantineInterruptedRecovery(
            databaseEmpty = false, asideExists = true, openedRecoveredFile = true, quarantineArmed = true
        ))
        // Nothing was replaced. An empty original is not a fresh file.
        assertFalse(AppDatabase.shouldQuarantineInterruptedRecovery(
            databaseEmpty = true, asideExists = false, openedRecoveredFile = false, quarantineArmed = false
        ))
        val prefs = SharedPreferencesHelper(ApplicationProvider.getApplicationContext<Application>())
        prefs.markRecoveryPending(4L)
        prefs.markChatDbQuarantineDue()
        prefs.clearRecoveryPending()
        assertNull(prefs.recoveryPendingStamp())
        assertFalse(prefs.isChatDbQuarantineDue())
    }

    @Test
    fun theRecoveryNoticeIsShownOnce() {
        val prefs = SharedPreferencesHelper(ApplicationProvider.getApplicationContext<Application>())
        assertFalse(prefs.consumeChatDbRecovered())

        prefs.markChatDbRecovered()
        assertTrue(prefs.consumeChatDbRecovered())
        assertFalse(prefs.consumeChatDbRecovered())
    }

    @Test
    fun aTakenRecoveryFileNameUsesTheNextFreeOne() {
        File(tmp.root, "chat_database.recovered-5").writeText("taken")
        assertEquals("chat_database.recovered-6", AppDatabase.recoveredFileName(tmp.root, 5L))
        assertEquals("chat_database.recovered-7", AppDatabase.recoveredFileName(tmp.root, 7L))
    }

    @Test
    fun anUnsafeDatabaseFileNameIsIgnored() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().putString("chat_db_file", "../chat_database").commit()
        assertEquals(AppDatabase.DB_NAME, prefs.chatDbFileName())
        prefs.mainPrefs.edit().putString("chat_db_file", "chat_database.pre_sqlcipher").commit()
        assertEquals(AppDatabase.DB_NAME, prefs.chatDbFileName())
        prefs.mainPrefs.edit().putString("chat_db_file", "/tmp/chat_database.recovered-5").commit()
        assertEquals(AppDatabase.DB_NAME, prefs.chatDbFileName())
        prefs.saveChatDbFileName("chat_database.recovered-5")
        assertEquals("chat_database.recovered-5", prefs.chatDbFileName())
        prefs.mainPrefs.edit().remove("chat_db_file").commit()
    }

    @Test
    fun setAsideWritesIntoTheVaultNotBesideTheDatabase() {
        val db = tmp.newFile("chat_database").apply { writeText("main") }
        File(db.path + "-wal").writeText("wal")
        val vault = tmp.newFolder("vault")

        val moved = AppDatabase.setAside(db, 4L, vault)!!

        assertEquals(vault.canonicalFile, moved.parentFile!!.canonicalFile)
        assertEquals("chat_database.unreadable-4", moved.name)
        assertEquals("main", moved.readText())
        assertEquals("wal", File(moved.path + "-wal").readText())
        assertFalse(db.exists())
        assertFalse(File(tmp.root, "chat_database.unreadable-4").exists())
    }

    @Test
    fun thePlaintextCopyIsReadFromTheVault() {
        val db = File(tmp.root, "chat_database")
        val vault = tmp.newFolder("plain-vault")
        File(vault, "chat_database.pre_sqlcipher").writeBytes(sqliteHeader("vaulted"))
        File(tmp.root, "chat_database.pre_sqlcipher").writeBytes(sqliteHeader("sibling"))

        assertTrue(AppDatabase.shouldRestorePlaintextBackup(db, vault))
        assertTrue(AppDatabase.restorePlaintextBackup(db, vault))

        assertEquals("vaulted", String(db.readBytes().copyOfRange(16, db.length().toInt()), Charsets.US_ASCII))
        assertFalse(File(vault, "chat_database.pre_sqlcipher").exists())
        assertTrue(File(tmp.root, "chat_database.pre_sqlcipher").exists())
    }

    @Test
    fun aRecoveredNameStillInTheDatabasesDirectoryIsNotReused() {
        val vault = tmp.newFolder("name-vault")
        val databases = tmp.newFolder("name-databases")
        File(databases, "chat_database.recovered-5-wal").writeText("wal")

        assertEquals("chat_database.recovered-6", AppDatabase.recoveredFileName(vault, 5L, databases))
    }

    @Test
    fun plaintextCopiesLeaveTheDatabasesDirectory() {
        val databases = tmp.newFolder("legacy-databases")
        val vault = tmp.newFolder("legacy-vault")
        File(databases, "chat_database").writeText("live")
        File(databases, "chat_database-wal").writeText("livewal")
        File(databases, "chat_database.pre_sqlcipher").writeBytes(sqliteHeader("plain"))
        File(databases, "chat_database.encrypting").writeText("temp")
        File(databases, "chat_database.encrypt_ok").writeText("ok")
        File(databases, "chat_database.unreadable-3").writeText("old")
        File(databases, "chat_database.unreadable-3-wal").writeText("wal")

        assertTrue(ChatDbVault.relocateLegacy(databases, vault, null))

        assertEquals("live", File(databases, "chat_database").readText())
        assertEquals("livewal", File(databases, "chat_database-wal").readText())
        assertFalse(File(databases, "chat_database.pre_sqlcipher").exists())
        assertFalse(File(databases, "chat_database.encrypting").exists())
        assertFalse(File(databases, "chat_database.encrypt_ok").exists())
        assertFalse(File(databases, "chat_database.unreadable-3").exists())
        assertFalse(File(databases, "chat_database.unreadable-3-wal").exists())
        assertTrue(ChatDbVault.plaintextBackup(vault).readBytes().copyOfRange(16, 21).contentEquals("plain".toByteArray()))
        assertEquals("temp", ChatDbVault.encrypting(vault).readText())
        assertEquals("ok", ChatDbVault.encryptMarker(vault).readText())
        assertEquals("old", File(vault, "chat_database.unreadable-3").readText())
        assertEquals("wal", File(vault, "chat_database.unreadable-3-wal").readText())
    }

    @Test
    fun aPlaintextCopyAlreadyInTheVaultIsNotOverwritten() {
        val databases = tmp.newFolder("both-databases")
        val vault = tmp.newFolder("both-vault")
        File(databases, "chat_database.pre_sqlcipher").writeText("legacy")
        ChatDbVault.plaintextBackup(vault).writeText("already")

        assertTrue(ChatDbVault.relocateLegacy(databases, vault, null))

        assertEquals("already", ChatDbVault.plaintextBackup(vault).readText())
        assertFalse(File(databases, "chat_database.pre_sqlcipher").exists())
        val kept = vault.listFiles()?.filter { it.name.startsWith("chat_database.pre_sqlcipher.kept-") }.orEmpty()
        assertEquals(1, kept.size)
        assertEquals("legacy", kept.single().readText())
    }

    @Test
    fun aFailedMoveLeavesTheSourceAndNoPartialDestination() {
        val root = tmp.newFolder("failed-move")
        val from = File(root, "from").apply { writeText("history") }
        val blocked = File(root, "not-a-directory").apply { writeText("x") }
        val to = File(blocked, "child")
        assertThrows(Exception::class.java) { ChatDbVault.moveReplacing(from, to) }
        assertEquals("history", from.readText())
        assertFalse(to.exists())
        assertEquals("x", blocked.readText())
    }

    @Test
    fun aRecoveredFileAlreadyInTheVaultIsLeftBesideTheLiveDatabase() {
        val databases = tmp.newFolder("split-databases")
        val vault = tmp.newFolder("split-vault")
        File(databases, "chat_database.recovered-2").writeText("legacy")
        File(databases, "chat_database.recovered-2-wal").writeText("wal")
        File(vault, "chat_database.recovered-2").writeText("vaultcopy")

        assertFalse(ChatDbVault.relocateLegacy(databases, vault, "chat_database.recovered-2"))

        assertEquals("legacy", File(databases, "chat_database.recovered-2").readText())
        assertEquals("wal", File(databases, "chat_database.recovered-2-wal").readText())
        assertEquals("vaultcopy", File(vault, "chat_database.recovered-2").readText())
        assertFalse(File(vault, "chat_database.recovered-2-wal").exists())
    }

    @Test
    fun aRecoveredNameOpensUnderNoBackupOnceTheDatabasesCopyIsGone() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val databases = app.getDatabasePath(AppDatabase.DB_NAME).parentFile!!
        databases.mkdirs()
        val vault = ChatDbVault.directory(app)
        val stored = "chat_database.recovered-11"
        val legacy = File(databases, stored)
        val legacyWal = File(databases, "$stored-wal")
        legacy.writeText("live")
        legacyWal.writeText("wal")
        val resolved = File(vault, stored)
        try {
            assertEquals(stored, ChatDbVault.roomDatabaseName(app, stored))
            assertTrue(ChatDbVault.relocateLegacy(databases, vault, stored))
            val roomName = ChatDbVault.roomDatabaseName(app, stored)
            assertEquals(resolved.canonicalPath, File(roomName).canonicalPath)
            assertEquals("live", resolved.readText())
            assertEquals("wal", File(resolved.path + "-wal").readText())
            assertFalse(legacy.exists())
            assertFalse(legacyWal.exists())
            val opened = app.getDatabasePath(roomName)
            assertEquals(resolved.canonicalPath, opened.canonicalPath)
            assertEquals(AppDatabase.DB_NAME, ChatDbVault.roomDatabaseName(app, "chat_database.pre_sqlcipher"))
        } finally {
            legacy.delete()
            legacyWal.delete()
            resolved.delete()
            File(resolved.path + "-wal").delete()
        }
    }

    @Test
    fun backupRulesExcludePlaintextCopiesAndHostTokens() {
        val rules = xmlText("backup_rules.xml")
        val extraction = xmlText("data_extraction_rules.xml")
        val names = listOf(
            "chat_database-journal",
            "chat_database.pre_sqlcipher",
            "chat_database.encrypting",
            "chat_database.encrypt_ok",
            "code_mode_secrets.xml"
        )
        for (name in names) {
            assertTrue(name, rules.contains("path=\"$name\""))
            // cloud-backup and device-transfer each name the file once
            assertEquals(name, 2, Regex.fromLiteral("path=\"$name\"").findAll(extraction).count())
        }
    }

    private fun xmlText(name: String): String {
        val file = listOf(File("src/main/res/xml/$name"), File("app/src/main/res/xml/$name"))
            .firstOrNull { it.isFile }
            ?: error("$name not found from ${File(".").absolutePath}")
        return file.readText()
    }

    @Test
    fun theRecoveryNoticeTextIsTheAgreedOne() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(
            "Your chat history couldn't be opened, so it was set aside and a fresh one started.",
            app.getString(R.string.notice_chat_db_recovered)
        )
    }

    private fun sqliteHeader(tail: String): ByteArray =
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + tail.toByteArray(Charsets.US_ASCII)
}
