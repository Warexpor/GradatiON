package io.github.stardomains3.oxproxion

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.stardomains3.oxproxion.code.CodeSessionDao
import io.github.stardomains3.oxproxion.code.CodeSessionEntity
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.RandomAccessFile

@Database(
    entities = [
        ChatSession::class, ChatMessage::class, RpCharacter::class, RpLorebook::class,
        CodeSessionEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun rpDao(): RpDao
    abstract fun codeSessionDao(): CodeSessionDao

    companion object {
        const val DB_NAME = "chat_database"
        private const val TAG = "AppDatabase"
        private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        @Volatile
        private var INSTANCE: AppDatabase? = null

        @Volatile
        private var nativeLoaded = false

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }
        }

        /** Screenshot/unit tests: swap in an in-memory DB (no SQLCipher native lib on the JVM). */
        @androidx.annotation.VisibleForTesting
        fun setInstanceForTesting(db: AppDatabase?) {
            INSTANCE = db
        }

        private fun ensureNativeLoaded() {
            if (!nativeLoaded) {
                System.loadLibrary("sqlcipher")
                nativeLoaded = true
            }
        }

        /** True once the database is open; lets callers skip a pointless hop to a background thread. */
        fun isOpen(): Boolean = INSTANCE != null

        /**
         * Opens the database, and never crash-loops on one that can't be read (Keystore wiped,
         * wrong passphrase, corrupt file, failed encrypt step): the old files are moved aside, not
         * deleted, the passphrase that can still read them is kept, a fresh database starts, and a
         * flag tells the UI to say so once.
         * Blocking: call from a background thread.
         */
        private fun build(context: Context): AppDatabase {
            ensureNativeLoaded()
            val prefs = SharedPreferencesHelper(context)
            val vault = ChatDbVault.directory(context)
            val databasesDir = context.getDatabasePath(DB_NAME).parentFile
            // Before Room opens: a plaintext copy or a recovered file left in the databases
            // directory would be eligible for Auto Backup. A move that fails is left in place
            // and still opened (see ChatDbVault.roomDatabaseName), not replaced with an empty file.
            if (databasesDir != null) {
                val stored = prefs.chatDbFileName()
                ChatDbVault.relocateLegacy(
                    databasesDir,
                    vault,
                    stored.takeIf { ChatDbVault.isRecoveredName(it) }
                )
            }
            val dbName = ChatDbVault.roomDatabaseName(context, prefs.chatDbFileName())
            val dbFile = context.getDatabasePath(dbName)
            val pending = prefs.recoveryPendingStamp()
            // A previous launch moved the file aside and died before the fresh database existed.
            // Opening now would create an empty file with the old key and hide the failure.
            if (pending != null && !dbFile.exists()) {
                Log.w(TAG, "Chat database recovery was interrupted; starting a fresh database")
                // Do not archive again: the active key may already be the new one, and copying it
                // over the stamp would replace the passphrase that opens the set-aside file.
                return openFreshAfterRecovery(
                    context,
                    prefs,
                    dbName,
                    vault,
                    prefs.hasArchivedChatDbPassphrase(pending),
                    pending
                )
            }
            val passphrase = try {
                prefs.getOrCreateChatDbPassphrase()
            } catch (e: Exception) {
                Log.e(TAG, "Chat database passphrase could not be read", e)
                return recover(context, prefs, dbName, vault)
            }
            // One retry first: the Keystore can answer badly for a moment (right after unlock, say),
            // and setting a healthy database aside for that would look like lost history.
            for (attempt in 1..2) {
                try {
                    val db = open(context, passphrase, dbName, vault)
                    if (pending != null) {
                        finishInterruptedRecovery(
                            context,
                            prefs,
                            databasesDir,
                            vault,
                            pending,
                            db,
                            openedRecoveredFile = ChatDbVault.isRecoveredName(File(dbName).name),
                        )
                    }
                    return db
                } catch (e: Exception) {
                    Log.e(TAG, "Chat database could not be opened (attempt $attempt)", e)
                    if (attempt == 1) Thread.sleep(300)
                }
            }
            return recover(context, prefs, dbName, vault)
        }

        /**
         * The recovery marker was left set. An empty file still restarts Room ids, including when
         * the old file could not be moved aside (no aside copy) or was already gone. Notes are
         * set aside before the marker is cleared, so a failed commit is tried again. A file that
         * already has rows is the original database, or a fresh one the user has already used.
         */
        private fun finishInterruptedRecovery(
            context: Context,
            prefs: SharedPreferencesHelper,
            databasesDir: File?,
            vault: File,
            pendingStamp: Long,
            db: AppDatabase,
            openedRecoveredFile: Boolean,
        ) {
            // The set-aside file is named from the original database, not from a recovered path.
            // Appending ".unreadable-" to the file Room just opened would miss it.
            val aside = ChatDbVault.unreadable(vault, pendingStamp)
            val legacy = databasesDir?.let { File(it, "${DB_NAME}.unreadable-$pendingStamp") }
            // A vault move that failed parks the set-aside file under chat_db_hold.
            val holdAside = databasesDir?.let {
                File(File(it, ChatDbVault.HOLD_DIR), "${DB_NAME}.unreadable-$pendingStamp")
            }
            val asideExists =
                aside.exists() || legacy?.exists() == true || holdAside?.exists() == true
            if (!shouldQuarantineInterruptedRecovery(
                    databaseEmpty = !hasUserRows(db),
                    asideExists = asideExists,
                    openedRecoveredFile = openedRecoveredFile,
                    quarantineArmed = prefs.isChatDbQuarantineDue(),
                )
            ) {
                prefs.clearRecoveryPending()
                return
            }
            if (quarantineRowPrefs(context, prefs, vault, pendingStamp)) {
                prefs.markChatDbRecovered()
            } else {
                Log.e(TAG, "Row-scoped preferences could not be set aside; will retry next launch")
            }
        }

        /**
         * True when a pending recovery still has to move notes aside.
         * Rows mean this file is already in use, so its notes are the current ones.
         * An empty file restarts ids. The aside copy is missing when the old file could not be
         * moved ([openedRecoveredFile]) and when the fresh file was created after the old one
         * was already gone ([quarantineArmed], set before Room ran).
         */
        @androidx.annotation.VisibleForTesting
        internal fun shouldQuarantineInterruptedRecovery(
            databaseEmpty: Boolean,
            asideExists: Boolean,
            openedRecoveredFile: Boolean,
            quarantineArmed: Boolean,
        ): Boolean {
            if (!databaseEmpty) return false
            return asideExists || openedRecoveredFile || quarantineArmed
        }

        /**
         * A fresh database starts its ids over. Move the notes keyed by the old ids aside before
         * clearing the recovery stamp, so a kill here is tried again instead of inherited.
         * Returns false when the preference edit did not commit. The stamp stays set in that case.
         */
        private fun quarantineRowPrefs(
            context: Context,
            prefs: SharedPreferencesHelper,
            vault: File,
            stamp: Long
        ): Boolean {
            // Reply versions and fork branches are keyed by row id too, in their own files.
            if (DbPrefQuarantine.quarantine(prefs.mainPrefs, context.filesDir, vault, stamp) &&
                prefs.blobs.setAside(File(vault, "aside-$stamp-${System.nanoTime()}/${ChatBlobs.DIR}"))
            ) return true
            Log.e(TAG, "Row-scoped preferences could not be set aside")
            return false
        }

        /**
         * True when any user table has a row. A failure counts as "has rows": an empty result is
         * the only signal that an encrypted file is the blank one a crash left behind, and a
         * query error must not cause that file to be replaced or its notes to be set aside.
         */
        private fun hasUserRows(db: AppDatabase): Boolean = try {
            val sql = db.openHelper.writableDatabase
            listOf("chat_sessions", "rp_characters", "rp_lorebooks", "code_session").any { table ->
                sql.query("SELECT 1 FROM $table LIMIT 1").use { it.moveToFirst() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not tell whether the chat database has rows", e)
            true
        }

        private fun recover(
            context: Context,
            prefs: SharedPreferencesHelper,
            dbName: String,
            vault: File
        ): AppDatabase {
            val dbFile = context.getDatabasePath(dbName)
            // A leftover confirm mark belongs to an encrypt that did not finish cleanly. A fresh
            // database must not treat it as permission to delete the plaintext copy.
            clearPlaintextBackupConfirmation(vault)
            // A half-finished encrypt leaves the plaintext copy. Prefer that over an empty database.
            // Only the primary file: restoring onto a recovered path would hide the set-aside original.
            if (dbFile.name == DB_NAME && restorePlaintextBackup(dbFile, vault)) {
                try {
                    val db = open(context, prefs.getOrCreateChatDbPassphrase(), dbName, vault)
                    prefs.clearRecoveryPending()
                    return db
                } catch (e: Exception) {
                    Log.e(TAG, "Restored plaintext chat database still could not be opened", e)
                }
            }
            val databasesDir = context.getDatabasePath(DB_NAME).parentFile
            // Skip stamps already used in the vault, at the databases root, under chat_db_hold,
            // or as a passphrase archive. Reusing one would overwrite an earlier recovery's key
            // or make Room open a parked recovered file instead of the fresh empty one.
            var stamp = firstFreeStamp(vault, System.currentTimeMillis(), databasesDir)
            while (prefs.hasArchivedChatDbPassphrase(stamp) && stamp < Long.MAX_VALUE) {
                stamp = firstFreeStamp(vault, stamp + 1, databasesDir)
            }
            // Copy the wrapped passphrase before anything deletes it. The set-aside file is
            // unreadable without this blob, and recovery used to throw the only copy away.
            val archived = prefs.archiveChatDbPassphrase(stamp)
            val moved = try {
                setAside(dbFile, stamp, vault, databasesDir)
            } catch (e: Exception) {
                // The corrupt file is still in place. Opening it again next launch would crash-loop,
                // so the app switches to a new file and leaves this one where it is.
                Log.e(TAG, "Could not move the chat database aside; opening a new file", e)
                // The file Room failed to open may already live in the vault. The name to avoid
                // is the one in the databases directory (or its hold folder), which backup would
                // upload from the root, or which Room prefers when opening a recovered name.
                val fallback = recoveredFileName(vault, stamp, databasesDir)
                // recoveredFileName may walk past [stamp] when that recovered name is taken.
                // Quarantine and the passphrase archive have to use the stamp in the file name.
                val fallbackStamp = stampOfRecoveredName(fallback) ?: stamp
                val archivedForName =
                    if (fallbackStamp == stamp) archived
                    else prefs.archiveChatDbPassphrase(fallbackStamp) || archived
                prefs.saveChatDbFileName(fallback)
                prefs.markRecoveryPending(fallbackStamp)
                return openFreshAfterRecovery(
                    context,
                    prefs,
                    File(vault, fallback).absolutePath,
                    vault,
                    archivedForName,
                    fallbackStamp
                )
            }
            val actual = stampOf(moved) ?: stamp
            val archivedActual = if (actual != stamp) prefs.archiveChatDbPassphrase(actual) else archived
            prefs.markRecoveryPending(actual)
            return openFreshAfterRecovery(context, prefs, dbName, vault, archivedActual, actual)
        }

        private fun openFreshAfterRecovery(
            context: Context,
            prefs: SharedPreferencesHelper,
            dbName: String,
            vault: File,
            archiveSaved: Boolean,
            stamp: Long
        ): AppDatabase {
            clearPlaintextBackupConfirmation(vault)
            // Before Room creates the file. A kill after that file exists and before the notes
            // are moved has nothing set aside when the old database could not be moved.
            prefs.markChatDbQuarantineDue()
            // Replacing the key is safe only when the previous blob was archived, or when there
            // was nothing to archive. A failed archive used to mint a new key and leave the
            // set-aside file with no passphrase.
            val passphrase = if (replacesPassphraseAfterRecovery(archiveSaved, prefs.hasWrappedChatDbPassphrase())) {
                prefs.resetChatDbPassphrase()
            } else {
                Log.e(TAG, "Keeping the chat database passphrase; it could not be archived")
                prefs.getOrCreateChatDbPassphrase()
            }
            val fresh = open(context, passphrase, dbName, vault, restoreEmpty = false)
            if (quarantineRowPrefs(context, prefs, vault, stamp)) {
                prefs.markChatDbRecovered()
            } else {
                Log.e(TAG, "Row-scoped preferences could not be set aside; will retry next launch")
            }
            return fresh
        }

        /**
         * A fresh database gets a new passphrase when the previous one was archived, or when
         * there was no wrapped passphrase to keep. A failed archive leaves the active key:
         * replacing it would make the set-aside file unreadable.
         */
        @androidx.annotation.VisibleForTesting
        internal fun replacesPassphraseAfterRecovery(archiveSaved: Boolean, wrappedPresent: Boolean): Boolean =
            archiveSaved || !wrappedPresent

        /**
         * Name of a new database file when the unreadable one cannot be moved aside.
         * A stamp that is already taken uses the next free one.
         */
        @androidx.annotation.VisibleForTesting
        internal fun recoveredFileName(directory: File, stamp: Long, alsoAvoid: File? = null): String {
            var s = stamp
            while (recoveredNameTaken(directory, s) ||
                (alsoAvoid != null && recoveredNameTakenInDatabases(alsoAvoid, s))
            ) {
                if (s == Long.MAX_VALUE) break
                s++
            }
            return "$DB_NAME.recovered-$s"
        }

        private fun recoveredNameTaken(directory: File, stamp: Long): Boolean =
            ChatDbVault.dbSetPresent(directory, "$DB_NAME.recovered-$stamp")

        /** Root of [databasesDir] or its [ChatDbVault.HOLD_DIR] park. */
        private fun recoveredNameTakenInDatabases(databasesDir: File, stamp: Long): Boolean {
            val name = "$DB_NAME.recovered-$stamp"
            if (ChatDbVault.dbSetPresent(databasesDir, name)) return true
            return ChatDbVault.dbSetPresent(File(databasesDir, ChatDbVault.HOLD_DIR), name)
        }

        private fun stampOf(moved: File?): Long? =
            moved?.name?.substringAfterLast("unreadable-", "")?.toLongOrNull()

        /** Stamp embedded in `chat_database.recovered-<stamp>`. */
        @androidx.annotation.VisibleForTesting
        internal fun stampOfRecoveredName(name: String): Long? {
            if (!ChatDbVault.isRecoveredName(name)) return null
            return name.substringAfterLast('-').toLongOrNull()
        }

        /**
         * Encrypts a leftover plaintext file, opens Room, and forces the real open so a bad key surfaces here.
         * [restoreEmpty] is false for the fresh database minted after recovery: that open must not
         * put the plaintext copy back in place of the file it just created.
         */
        private fun open(
            context: Context,
            passphrase: ByteArray,
            dbName: String,
            vault: File,
            restoreEmpty: Boolean = true
        ): AppDatabase {
            val dbFile = context.getDatabasePath(dbName)
            // Plaintext restore applies only to the primary file. Doing it for a recovered file
            // would rename the snapshot onto that path and leave the original database behind.
            val primary = dbFile.name == DB_NAME
            // Died after the plaintext file was renamed aside and before the encrypted file was
            // installed. Opening now would create an empty database, and the next successful open
            // used to delete the plaintext copy.
            if (restoreEmpty && primary && shouldRestorePlaintextBackup(dbFile, vault)) {
                Log.w(TAG, "Chat database file is missing; restoring the plaintext copy")
                clearPlaintextBackupConfirmation(vault)
                if (!restorePlaintextBackup(dbFile, vault)) {
                    // Leave the plaintext copy where it is. The empty-database check below tries
                    // once more after Room opens, instead of minting a fresh database every launch.
                    Log.e(TAG, "Missing chat database could not be restored from the plaintext copy")
                }
            }
            val migrated = if (primary) encryptPlaintextIfNeeded(dbFile, passphrase, vault) else false
            val factory = SupportOpenHelperFactory(passphrase.copyOf())
            val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .openHelperFactory(factory)
                .addMigrations(
                    DatabaseMigrations.MIGRATION_1_2,
                    DatabaseMigrations.MIGRATION_2_3,
                    DatabaseMigrations.MIGRATION_3_4
                )
                .build()
            try {
                db.openHelper.writableDatabase
            } catch (e: Exception) {
                try {
                    db.close()
                } catch (_: Exception) {
                }
                throw e
            }
            if (primary) {
                if (migrated) {
                    confirmPlaintextBackupDisposable(dbFile, vault)
                } else if (
                    restoreEmpty &&
                    isPlaintextSqliteHeader(ChatDbVault.plaintextBackup(vault)) &&
                    !hasUserRows(db)
                ) {
                    // An older launch created an empty encrypted file and died before it could delete
                    // the plaintext copy. That copy is the history. Put it back and encrypt it.
                    try {
                        db.close()
                    } catch (_: Exception) {
                    }
                    clearPlaintextBackupConfirmation(vault)
                    if (!restorePlaintextBackup(dbFile, vault)) {
                        Log.e(TAG, "Empty chat database could not be replaced with the plaintext copy")
                        return open(context, passphrase, dbName, vault, restoreEmpty = false)
                    }
                    return open(context, passphrase, dbName, vault, restoreEmpty = false)
                }
                discardPlaintextBackupIfConfirmed(dbFile, vault)
            }
            return db
        }

        /**
         * Moves [dbFile] and its -wal/-shm/-journal into [vault] as `chat_database.unreadable-<stamp>`
         * (sidecars keep their suffix after that). [vault] defaults to the database's own directory
         * so a caller that has no backup vault still keeps the old layout. Production passes the
         * no-backup directory: a set-aside file next to the live database would be uploaded.
         * A stamp that is already taken uses the next free one, so a second recovery never
         * overwrites the first. A failure puts back anything already moved: the original name is
         * never left half-moved beside a new database.
         * Never deletes: the file may still be recoverable.
         * Returns the moved main file, or null when there was nothing to move.
         */
        @androidx.annotation.VisibleForTesting
        internal fun setAside(
            dbFile: File,
            stamp: Long,
            vault: File = dbFile.parentFile ?: dbFile,
            alsoAvoid: File? = null,
        ): File? {
            val suffixes = listOf("", "-wal", "-shm", "-journal")
            val present = suffixes.filter { File(dbFile.path + it).exists() }
            if (present.isEmpty()) return null
            val chosen = firstFreeStamp(vault, stamp, alsoAvoid)
            val target = File(vault, "$DB_NAME.unreadable-$chosen")
            val moved = ArrayList<Pair<File, File>>(present.size)
            try {
                for (suffix in present) {
                    val from = File(dbFile.path + suffix)
                    val to = File(target.path + suffix)
                    moveReplacing(from, to)
                    moved += from to to
                    val remaining = movesBeforeFailure
                    if (remaining != null) {
                        if (remaining <= 1) {
                            movesBeforeFailure = null
                            throw java.io.IOException("simulated recovery move failure")
                        }
                        movesBeforeFailure = remaining - 1
                    }
                }
            } catch (e: Exception) {
                for ((from, to) in moved.asReversed()) {
                    try {
                        if (to.exists()) moveReplacing(to, from)
                    } catch (rollback: Exception) {
                        Log.e(TAG, "Could not put ${to.name} back after a failed recovery move", rollback)
                    }
                }
                throw e
            }
            return if ("" in present) target else null
        }

        /**
         * Test hook. When set, [setAside] throws after this many successful moves so a test can
         * check that a failed recovery puts the files back. Null in production.
         */
        @androidx.annotation.VisibleForTesting
        internal var movesBeforeFailure: Int? = null

        /**
         * First stamp at or after [stamp] whose aside and recovered files are free in [vault].
         * [alsoAvoid] is the databases directory: stamps already used there or under
         * [ChatDbVault.HOLD_DIR] are skipped so a later recovery cannot collide with a parked copy,
         * or reuse a stamp that already names a recovered database (passphrase archives are per stamp).
         */
        @androidx.annotation.VisibleForTesting
        internal fun firstFreeStamp(vault: File, stamp: Long, alsoAvoid: File? = null): Long {
            var s = stamp
            while (
                unreadableStampTaken(vault, s) ||
                recoveredNameTaken(vault, s) ||
                (alsoAvoid != null && (
                    unreadableStampTakenInDatabases(alsoAvoid, s) ||
                    recoveredNameTakenInDatabases(alsoAvoid, s)
                ))
            ) {
                if (s == Long.MAX_VALUE) return stamp
                s++
            }
            return s
        }

        private fun unreadableStampTaken(directory: File, stamp: Long): Boolean {
            if (!directory.exists()) return false
            return listOf("", "-wal", "-shm", "-journal").any {
                File(directory, "$DB_NAME.unreadable-$stamp$it").exists()
            }
        }

        private fun unreadableStampTakenInDatabases(databasesDir: File, stamp: Long): Boolean =
            unreadableStampTaken(databasesDir, stamp) ||
                unreadableStampTaken(File(databasesDir, ChatDbVault.HOLD_DIR), stamp)

        private fun moveReplacing(from: File, to: File) = ChatDbVault.moveReplacing(from, to)

        /**
         * One-shot: if an unencrypted Room DB already exists, rewrite it via
         * sqlcipher_export before Room opens with SupportOpenHelperFactory.
         * Returns true only when the encrypted file was installed over the plaintext one.
         *
         * Already-encrypted (or corrupt) files must not enter this path — probing
         * them with an empty key throws and used to crash cold start.
         */
        private fun encryptPlaintextIfNeeded(dbFile: File, passphrase: ByteArray, vault: File): Boolean {
            if (!dbFile.exists() || dbFile.length() == 0L) return false
            if (!isPlaintextSqliteHeader(dbFile)) return false

            // Temp and plaintext snapshot go to the vault. Writing them beside the live file put
            // the plaintext history in a directory Auto Backup uploads.
            val encryptedTemp = ChatDbVault.encrypting(vault)
            val backup = ChatDbVault.plaintextBackup(vault)
            // A crashed export can leave -wal/-shm beside these names. Deleting only the main
            // lets ATTACH replay that wal into the new ciphertext, or pairs it with the snapshot.
            // If a leftover cannot be moved off that name, do not ATTACH and do not delete the
            // plaintext snapshot: throwing here would run recovery and could replace the live file.
            if (!clearPlaintextExportLeftovers(vault)) {
                Log.e(TAG, "Leftover SQLCipher export files could not be removed; leaving the plaintext database")
                return false
            }

            var plaintext: SQLiteDatabase? = null
            try {
                plaintext = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    "",
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                    null,
                    null
                )
                plaintext.rawExecSQL("PRAGMA wal_checkpoint(FULL);")
                // Room keys with sqlite3_key(passphrase). That is a PBKDF2 passphrase, not a raw
                // key. ATTACH KEY "x'hex'" writes a raw key, so the next open could not read the
                // file and recovery set the history aside. Bind the same bytes Room will use.
                val (attachSql, attachArgs) = sqlCipherExportAttach(encryptedTemp.absolutePath, passphrase)
                plaintext.rawExecSQL(attachSql, *attachArgs)
                val userVersion = readSqliteUserVersion(plaintext)
                plaintext.rawExecSQL("SELECT sqlcipher_export('encrypted');")
                // sqlcipher_export does not copy PRAGMA user_version. Left at 0, Room runs
                // onCreate and skips migrations, so an older plaintext file never gains columns.
                sqlCipherExportUserVersionSql(userVersion)?.let { plaintext.rawExecSQL(it) }
                plaintext.rawExecSQL("DETACH DATABASE encrypted;")
                plaintext.close()
                plaintext = null

                if (!moveIntoPlace(dbFile, backup)) {
                    deleteEncryptingLeftover(vault)
                    throw IllegalStateException("Could not backup plaintext chat DB before encryption")
                }
                if (!moveIntoPlace(encryptedTemp, dbFile)) {
                    moveIntoPlace(backup, dbFile)
                    deleteEncryptingLeftover(vault)
                    throw IllegalStateException("Could not install encrypted chat DB")
                }
                // Sidecars belong to the plaintext file. A wal left on this name is replayed into
                // the ciphertext. The snapshot itself stays until the encrypted database has opened.
                if (!releaseLivePlaintextSidecars(dbFile, vault)) {
                    val holding = File(vault, "$DB_NAME.encrypting.rollback")
                    if (moveIntoPlace(dbFile, holding)) {
                        if (!moveIntoPlace(backup, dbFile)) {
                            moveIntoPlace(holding, dbFile)
                        } else if (!holding.delete()) {
                            Log.w(TAG, "Could not remove rolled-back ciphertext ${holding.path}")
                        }
                    }
                    throw IllegalStateException("Could not detach plaintext sidecars from the encrypted chat DB")
                }
                Log.i(TAG, "Migrated plaintext chat_database to SQLCipher")
                return true
            } catch (e: Exception) {
                try {
                    plaintext?.close()
                } catch (_: Exception) {
                }
                deleteEncryptingLeftover(vault)
                if (backup.exists() && !dbFile.exists()) {
                    moveIntoPlace(backup, dbFile)
                }
                Log.e(TAG, "Failed to encrypt existing chat DB", e)
                throw e
            }
        }

        /**
         * Rename, or copy when the two directories will not rename (the plaintext snapshot lives
         * in the no-backup directory, the live file in databases/). A failed copy leaves [from]
         * in place and removes a partial [to].
         */
        private fun moveIntoPlace(from: File, to: File): Boolean {
            if (!from.exists()) return false
            if (from.renameTo(to)) return true
            return try {
                moveReplacing(from, to)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Could not move ${from.path} to ${to.path}", e)
                false
            }
        }

        /**
         * The encrypted file is missing or empty and the plaintext snapshot in [vault] is still a
         * database. [vault] defaults to the database's own directory. A wal/shm/journal next to
         * the main name belongs to that file, so it is left alone.
         */
        @androidx.annotation.VisibleForTesting
        internal fun shouldRestorePlaintextBackup(
            dbFile: File,
            vault: File = dbFile.parentFile ?: dbFile
        ): Boolean {
            if (!isPlaintextSqliteHeader(ChatDbVault.plaintextBackup(vault))) return false
            if (sidecarExists(dbFile)) return false
            return !dbFile.exists() || dbFile.length() == 0L
        }

        /**
         * Puts a leftover plaintext copy back when the encrypted file did not open. The failed
         * encrypted file is moved aside first, not deleted. The snapshot is read from [vault].
         */
        @androidx.annotation.VisibleForTesting
        internal fun restorePlaintextBackup(
            dbFile: File,
            vault: File = dbFile.parentFile ?: dbFile
        ): Boolean {
            val backup = ChatDbVault.plaintextBackup(vault)
            if (!backup.exists() || !isPlaintextSqliteHeader(backup)) return false
            if (dbFile.exists() || sidecarExists(dbFile)) {
                try {
                    setAside(dbFile, System.currentTimeMillis(), vault)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not set the failed encrypted database aside", e)
                    return false
                }
            }
            if (!moveIntoPlace(backup, dbFile)) {
                Log.e(TAG, "Could not put the plaintext copy back at ${dbFile.path}")
                return false
            }
            deleteSidecars(dbFile)
            return isPlaintextSqliteHeader(dbFile)
        }

        private fun sidecarExists(dbFile: File): Boolean =
            listOf("-wal", "-shm", "-journal").any { File(dbFile.path + it).exists() }

        /** Marks the plaintext copy as safe to remove. Call only after the encrypted file has opened. */
        @androidx.annotation.VisibleForTesting
        internal fun confirmPlaintextBackupDisposable(
            dbFile: File,
            vault: File = dbFile.parentFile ?: dbFile
        ) {
            ChatDbVault.encryptMarker(vault).writeText("ok")
        }

        @androidx.annotation.VisibleForTesting
        internal fun clearPlaintextBackupConfirmation(vault: File) {
            ChatDbVault.encryptMarker(vault).delete()
        }

        /**
         * Removes the plaintext snapshot (and any leftover `encrypting` set, including orphan
         * sidecars and `.kept-*` renames) only after [confirmPlaintextBackupDisposable].
         * Deleting the snapshot on every successful open used to erase the chats when the process
         * died mid-encrypt and the next launch created an empty database.
         * The same pass clears those disposable names in the vault, at the databases root, and
         * under [ChatDbVault.HOLD_DIR] before the marker is removed. Otherwise the next open
         * would see no marker and drain a hold copy back into the vault.
         * The marker stays when a delete fails, so the next open retries instead of forgetting
         * that the leftover was disposable.
         */
        @androidx.annotation.VisibleForTesting
        internal fun discardPlaintextBackupIfConfirmed(
            dbFile: File,
            vault: File = dbFile.parentFile ?: dbFile
        ) {
            val marker = ChatDbVault.encryptMarker(vault)
            if (!marker.exists()) return
            val dirs = ArrayList<File>(3)
            dirs.add(vault)
            val databasesDir = dbFile.parentFile
            if (databasesDir != null && databasesDir.absolutePath != vault.absolutePath) {
                dirs.add(databasesDir)
            }
            if (databasesDir != null) {
                val hold = File(databasesDir, ChatDbVault.HOLD_DIR)
                if (hold.isDirectory && dirs.none { it.absolutePath == hold.absolutePath }) {
                    dirs.add(hold)
                }
            }
            for (dir in dirs) {
                if (!removeDisposableDbSet(ChatDbVault.plaintextBackup(dir))) {
                    Log.w(TAG, "Could not remove plaintext backup ${ChatDbVault.plaintextBackup(dir).path}")
                    return
                }
                if (!removeDisposableDbSet(ChatDbVault.encrypting(dir))) {
                    Log.w(TAG, "Could not remove encrypting leftover ${ChatDbVault.encrypting(dir).path}")
                    return
                }
                if (!ChatDbVault.discardDisposableKeptRenames(dir)) {
                    Log.w(TAG, "Could not remove disposable kept renames in ${dir.path}")
                    return
                }
                if (!ChatDbVault.discardDisposableStuckRenames(dir)) {
                    Log.w(TAG, "Could not remove disposable stuck renames in ${dir.path}")
                    return
                }
            }
            if (!marker.delete()) Log.w(TAG, "Could not remove encrypt marker ${marker.path}")
        }

        /**
         * Drops in-progress `encrypting` and `pre_sqlcipher` mains and their wal/shm/journal
         * before a new export. Ciphertext names go first. A name that cannot be deleted (a
         * non-empty directory) is renamed off the path SQLite would open. If that still fails,
         * the plaintext snapshot is left in place and this returns false so ATTACH does not run.
         * Called only once the live file is still plaintext SQLite.
         */
        @androidx.annotation.VisibleForTesting
        internal fun clearPlaintextExportLeftovers(vault: File): Boolean {
            if (!removeExportName(ChatDbVault.encrypting(vault))) return false
            return removeExportName(ChatDbVault.plaintextBackup(vault))
        }

        /**
         * ATTACH for [sqlcipher_export]. The key argument is the same byte array Room passes to
         * `sqlite3_key` (a passphrase). It must not be the raw-key blob literal `x'hex'`.
         */
        @androidx.annotation.VisibleForTesting
        internal fun sqlCipherExportAttach(tempPath: String, passphrase: ByteArray): Pair<String, Array<Any>> =
            "ATTACH DATABASE ? AS encrypted KEY ?" to arrayOf(tempPath, passphrase)

        /**
         * `PRAGMA` that copies [userVersion] onto the attached export.
         * Null when there is nothing to copy (a brand-new file is already version 0).
         * [sqlcipher_export] leaves the target at 0; Room would then skip migrations.
         */
        @androidx.annotation.VisibleForTesting
        internal fun sqlCipherExportUserVersionSql(userVersion: Int): String? {
            if (userVersion <= 0) return null
            return "PRAGMA encrypted.user_version = $userVersion"
        }

        private fun readSqliteUserVersion(db: SQLiteDatabase): Int = db.version

        /**
         * When set, [removeExportFile] refuses this file name so a test can keep the snapshot.
         * Null in production.
         */
        @androidx.annotation.VisibleForTesting
        internal var blockExportLeftoverNameForTest: String? = null

        /**
         * After the encrypted main is installed, plaintext `-wal`/`-shm`/`-journal` still use the
         * live name and SQLite would replay them. Delete each one, or move a stuck one into
         * [vault] (backup skips that directory) under `name.stuck-N`. False when a sidecar is
         * still on the live name — the caller puts the plaintext snapshot back.
         */
        @androidx.annotation.VisibleForTesting
        internal fun releaseLivePlaintextSidecars(dbFile: File, vault: File): Boolean {
            vault.mkdirs()
            for (suffix in listOf("-shm", "-wal", "-journal")) {
                if (!removeOrVaultSidecar(File(dbFile.path + suffix), vault)) return false
            }
            return !sidecarExists(dbFile)
        }

        private fun removeOrVaultSidecar(sidecar: File, vault: File): Boolean {
            if (!sidecar.exists()) return true
            if (sidecar.name == blockExportLeftoverNameForTest) return false
            if (sidecar.delete() || !sidecar.exists()) return true
            val parked = parkedExportFile(File(vault, sidecar.name))
            if (sidecar.renameTo(parked) && !sidecar.exists()) return true
            return try {
                moveReplacing(sidecar, parked)
                !sidecar.exists()
            } catch (e: Exception) {
                Log.e(TAG, "Could not move ${sidecar.path} off the live database", e)
                false
            }
        }

        /** Drops the in-progress ciphertext and its sidecars. The plaintext snapshot stays. */
        private fun deleteEncryptingLeftover(vault: File) {
            removeExportName(ChatDbVault.encrypting(vault))
        }

        /** True when [main] and its wal/shm/journal are gone (deleted or renamed aside). */
        private fun removeExportName(main: File): Boolean {
            for (suffix in listOf("-shm", "-wal", "-journal", "")) {
                if (!removeExportFile(File(main.path + suffix))) return false
            }
            return !main.exists() && !sidecarExists(main)
        }

        /**
         * Removes one export leftover. A directory is emptied first: `File.delete` leaves a
         * non-empty directory, and ATTACH would still see that name. A file that cannot be
         * deleted is renamed to `name.stuck-N` in the same directory (the vault, which backup
         * skips) so it is no longer the wal SQLite would replay.
         */
        private fun removeExportFile(file: File): Boolean {
            if (!file.exists()) return true
            if (file.name == blockExportLeftoverNameForTest) return false
            if (file.isDirectory) {
                val children = file.listFiles()
                if (children != null) {
                    for (child in children) {
                        if (!removeExportFile(child)) return false
                    }
                }
            }
            if (file.delete() || !file.exists()) return true
            val parked = parkedExportFile(file)
            return file.renameTo(parked) && !file.exists()
        }

        private fun parkedExportFile(file: File): File {
            val parent = file.parentFile
            var n = 1
            var alt = File(parent, file.name + ".stuck-$n")
            while (alt.exists() && n < Int.MAX_VALUE) {
                n++
                alt = File(parent, file.name + ".stuck-$n")
            }
            return alt
        }

        /** Deletes [main] and its wal/shm/journal. False when any piece is still there. */
        private fun removeDisposableDbSet(main: File): Boolean {
            deleteSidecars(main)
            if (main.exists() && !main.delete()) return false
            if (main.exists()) return false
            return !listOf("-wal", "-shm", "-journal").any { File(main.path + it).exists() }
        }

        /** True only when the file header is standard unencrypted SQLite. */
        private fun isPlaintextSqliteHeader(dbFile: File): Boolean {
            return try {
                RandomAccessFile(dbFile, "r").use { raf ->
                    if (raf.length() < SQLITE_MAGIC.size) return false
                    val header = ByteArray(SQLITE_MAGIC.size)
                    raf.readFully(header)
                    header.contentEquals(SQLITE_MAGIC)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read DB header for $DB_NAME", e)
                false
            }
        }

        private fun deleteSidecars(dbFile: File) {
            File(dbFile.path + "-shm").delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-journal").delete()
        }
    }
}
