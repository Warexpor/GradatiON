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

        /** Written only after a plaintext file has been encrypted and that encrypted file has opened. */
        private const val ENCRYPT_OK_NAME = "$DB_NAME.encrypt_ok"

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
            val dbName = prefs.chatDbFileName()
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
                    prefs.hasArchivedChatDbPassphrase(pending)
                )
            }
            val passphrase = try {
                prefs.getOrCreateChatDbPassphrase()
            } catch (e: Exception) {
                Log.e(TAG, "Chat database passphrase could not be read", e)
                return recover(context, prefs, dbName)
            }
            // One retry first: the Keystore can answer badly for a moment (right after unlock, say),
            // and setting a healthy database aside for that would look like lost history.
            for (attempt in 1..2) {
                try {
                    val db = open(context, passphrase, dbName)
                    if (pending != null) finishInterruptedRecovery(prefs, dbFile, pending, db)
                    return db
                } catch (e: Exception) {
                    Log.e(TAG, "Chat database could not be opened (attempt $attempt)", e)
                    if (attempt == 1) Thread.sleep(300)
                }
            }
            return recover(context, prefs, dbName)
        }

        /**
         * The recovery marker was left set. If this open is the fresh database from a recovery that
         * died before the notice was recorded, record it. If the original database opened after all
         * (a transient failure, the move never happened), drop the marker and say nothing.
         */
        private fun finishInterruptedRecovery(
            prefs: SharedPreferencesHelper,
            dbFile: File,
            pendingStamp: Long,
            db: AppDatabase
        ) {
            val aside = File(dbFile.path + ".unreadable-$pendingStamp")
            if (aside.exists() && !hasChatRows(db)) prefs.markChatDbRecovered()
            else prefs.clearRecoveryPending()
        }

        private fun hasChatRows(db: AppDatabase): Boolean = try {
            db.openHelper.writableDatabase.query("SELECT 1 FROM chat_sessions LIMIT 1").use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not tell whether the chat database has rows", e)
            false
        }

        /**
         * True when any user table has a row. A failure counts as "has rows": an empty result is
         * the only signal that an encrypted file is the blank one a crash left behind, and a
         * query error must not cause that file to be replaced.
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

        private fun recover(context: Context, prefs: SharedPreferencesHelper, dbName: String): AppDatabase {
            val dbFile = context.getDatabasePath(dbName)
            // A leftover confirm mark belongs to an encrypt that did not finish cleanly. A fresh
            // database must not treat it as permission to delete the plaintext copy.
            clearPlaintextBackupConfirmation(dbFile)
            // A half-finished encrypt leaves the plaintext copy. Prefer that over an empty database.
            if (restorePlaintextBackup(dbFile)) {
                try {
                    val db = open(context, prefs.getOrCreateChatDbPassphrase(), dbName)
                    prefs.clearRecoveryPending()
                    return db
                } catch (e: Exception) {
                    Log.e(TAG, "Restored plaintext chat database still could not be opened", e)
                }
            }
            val stamp = firstFreeStamp(dbFile, System.currentTimeMillis())
            // Copy the wrapped passphrase before anything deletes it. The set-aside file is
            // unreadable without this blob, and recovery used to throw the only copy away.
            val archived = prefs.archiveChatDbPassphrase(stamp)
            val moved = try {
                setAside(dbFile, stamp)
            } catch (e: Exception) {
                // The corrupt file is still in place. Opening it again next launch would crash-loop,
                // so the app switches to a new file and leaves this one where it is.
                Log.e(TAG, "Could not move the chat database aside; opening a new file", e)
                val directory = dbFile.parentFile ?: throw e
                val fallback = recoveredFileName(directory, stamp)
                prefs.saveChatDbFileName(fallback)
                prefs.markRecoveryPending(stamp)
                return openFreshAfterRecovery(context, prefs, fallback, archived)
            }
            val actual = stampOf(moved) ?: stamp
            val archivedActual = if (actual != stamp) prefs.archiveChatDbPassphrase(actual) else archived
            prefs.markRecoveryPending(actual)
            return openFreshAfterRecovery(context, prefs, dbName, archivedActual)
        }

        private fun openFreshAfterRecovery(
            context: Context,
            prefs: SharedPreferencesHelper,
            dbName: String,
            archiveSaved: Boolean
        ): AppDatabase {
            val dbFile = context.getDatabasePath(dbName)
            clearPlaintextBackupConfirmation(dbFile)
            // Replacing the key is safe only when the previous blob was archived, or when there
            // was nothing to archive. A failed archive used to mint a new key and leave the
            // set-aside file with no passphrase.
            val passphrase = if (replacesPassphraseAfterRecovery(archiveSaved, prefs.hasWrappedChatDbPassphrase())) {
                prefs.resetChatDbPassphrase()
            } else {
                Log.e(TAG, "Keeping the chat database passphrase; it could not be archived")
                prefs.getOrCreateChatDbPassphrase()
            }
            val fresh = open(context, passphrase, dbName, restoreEmpty = false)
            prefs.markChatDbRecovered()
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
        internal fun recoveredFileName(directory: File, stamp: Long): String {
            var s = stamp
            while (File(directory, "$DB_NAME.recovered-$s").exists()) s++
            return "$DB_NAME.recovered-$s"
        }

        private fun stampOf(moved: File?): Long? =
            moved?.name?.substringAfterLast("unreadable-", "")?.toLongOrNull()

        /**
         * Encrypts a leftover plaintext file, opens Room, and forces the real open so a bad key surfaces here.
         * [restoreEmpty] is false for the fresh database minted after recovery: that open must not
         * put the plaintext copy back in place of the file it just created.
         */
        private fun open(
            context: Context,
            passphrase: ByteArray,
            dbName: String,
            restoreEmpty: Boolean = true
        ): AppDatabase {
            val dbFile = context.getDatabasePath(dbName)
            // Died after the plaintext file was renamed aside and before the encrypted file was
            // installed. Opening now would create an empty database, and the next successful open
            // used to delete the plaintext copy.
            if (restoreEmpty && shouldRestorePlaintextBackup(dbFile)) {
                Log.w(TAG, "Chat database file is missing; restoring the plaintext copy")
                clearPlaintextBackupConfirmation(dbFile)
                if (!restorePlaintextBackup(dbFile)) {
                    // Leave the plaintext copy where it is. The empty-database check below tries
                    // once more after Room opens, instead of minting a fresh database every launch.
                    Log.e(TAG, "Missing chat database could not be restored from the plaintext copy")
                }
            }
            val migrated = encryptPlaintextIfNeeded(dbFile, passphrase)
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
            if (migrated) {
                confirmPlaintextBackupDisposable(dbFile)
            } else if (
                restoreEmpty &&
                isPlaintextSqliteHeader(plaintextBackupFile(dbFile)) &&
                !hasUserRows(db)
            ) {
                // An older launch created an empty encrypted file and died before it could delete
                // the plaintext copy. That copy is the history. Put it back and encrypt it.
                try {
                    db.close()
                } catch (_: Exception) {
                }
                clearPlaintextBackupConfirmation(dbFile)
                if (!restorePlaintextBackup(dbFile)) {
                    Log.e(TAG, "Empty chat database could not be replaced with the plaintext copy")
                    return open(context, passphrase, dbName, restoreEmpty = false)
                }
                return open(context, passphrase, dbName, restoreEmpty = false)
            }
            discardPlaintextBackupIfConfirmed(dbFile)
            return db
        }

        /**
         * Moves [dbFile] and its -wal/-shm/-journal next to it as `chat_database.unreadable-<stamp>`
         * (sidecars keep their suffix after that). A stamp that is already taken uses the next free
         * one, so a second recovery never overwrites the first. A failure puts back anything already
         * moved: the original name is never left half-moved beside a new database.
         * Never deletes: the file may still be recoverable.
         * Returns the moved main file, or null when there was nothing to move.
         */
        @androidx.annotation.VisibleForTesting
        internal fun setAside(dbFile: File, stamp: Long): File? {
            val suffixes = listOf("", "-wal", "-shm", "-journal")
            val present = suffixes.filter { File(dbFile.path + it).exists() }
            if (present.isEmpty()) return null
            val chosen = firstFreeStamp(dbFile, stamp)
            val target = File(dbFile.path + ".unreadable-$chosen")
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

        /** First stamp at or after [stamp] whose aside files are all free. */
        @androidx.annotation.VisibleForTesting
        internal fun firstFreeStamp(dbFile: File, stamp: Long): Long {
            var s = stamp
            while (listOf("", "-wal", "-shm", "-journal").any {
                    File(dbFile.path + ".unreadable-$s" + it).exists()
                }
            ) {
                s++
            }
            return s
        }

        private fun moveReplacing(from: File, to: File) {
            if (from.renameTo(to)) return
            // Rename can fail across mounts; a copy that finished is as good as a move.
            from.copyTo(to, overwrite = true)
            if (!from.delete() && from.exists()) {
                // The original is still in place. Drop the copy so a later open does not see two
                // files, and fail so the caller can put back what it already moved.
                to.delete()
                throw java.io.IOException("Could not remove ${from.path} after copying it aside")
            }
        }

        /**
         * One-shot: if an unencrypted Room DB already exists, rewrite it via
         * sqlcipher_export before Room opens with SupportOpenHelperFactory.
         * Returns true only when the encrypted file was installed over the plaintext one.
         *
         * Already-encrypted (or corrupt) files must not enter this path — probing
         * them with an empty key throws and used to crash cold start.
         */
        private fun encryptPlaintextIfNeeded(dbFile: File, passphrase: ByteArray): Boolean {
            if (!dbFile.exists() || dbFile.length() == 0L) return false
            if (!isPlaintextSqliteHeader(dbFile)) return false

            val parent = dbFile.parentFile ?: return false
            val encryptedTemp = File(parent, "$DB_NAME.encrypting")
            val backup = File(parent, "$DB_NAME.pre_sqlcipher")
            encryptedTemp.delete()
            backup.delete()

            val hexKey = passphrase.joinToString("") { b -> "%02x".format(b) }
            // A database path with a quote would break out of the ATTACH string.
            val quotedTemp = encryptedTemp.absolutePath.replace("'", "''")
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
                plaintext.rawExecSQL(
                    "ATTACH DATABASE '$quotedTemp' AS encrypted KEY \"x'$hexKey'\";"
                )
                plaintext.rawExecSQL("SELECT sqlcipher_export('encrypted');")
                plaintext.rawExecSQL("DETACH DATABASE encrypted;")
                plaintext.close()
                plaintext = null

                if (!dbFile.renameTo(backup)) {
                    encryptedTemp.delete()
                    throw IllegalStateException("Could not backup plaintext chat DB before encryption")
                }
                if (!encryptedTemp.renameTo(dbFile)) {
                    backup.renameTo(dbFile)
                    encryptedTemp.delete()
                    throw IllegalStateException("Could not install encrypted chat DB")
                }
                // Sidecars belong to the plaintext file. The backup itself stays until the encrypted
                // database has actually opened (see open); a crash here can still restore it.
                deleteSidecars(dbFile)
                Log.i(TAG, "Migrated plaintext chat_database to SQLCipher")
                return true
            } catch (e: Exception) {
                try {
                    plaintext?.close()
                } catch (_: Exception) {
                }
                encryptedTemp.delete()
                if (backup.exists() && !dbFile.exists()) {
                    backup.renameTo(dbFile)
                }
                Log.e(TAG, "Failed to encrypt existing chat DB", e)
                throw e
            }
        }

        /** `chat_database.pre_sqlcipher`, the plaintext copy kept across the encrypt step. */
        private fun plaintextBackupFile(dbFile: File): File =
            File(dbFile.parentFile, "$DB_NAME.pre_sqlcipher")

        /**
         * The encrypted file is missing or empty and [chat_database.pre_sqlcipher] is still a
         * plaintext database. A wal/shm/journal next to the main name belongs to that file, so
         * it is left alone.
         */
        @androidx.annotation.VisibleForTesting
        internal fun shouldRestorePlaintextBackup(dbFile: File): Boolean {
            if (!isPlaintextSqliteHeader(plaintextBackupFile(dbFile))) return false
            if (sidecarExists(dbFile)) return false
            return !dbFile.exists() || dbFile.length() == 0L
        }

        /**
         * Puts a leftover plaintext copy back when the encrypted file did not open. The failed
         * encrypted file is moved aside first, not deleted.
         */
        @androidx.annotation.VisibleForTesting
        internal fun restorePlaintextBackup(dbFile: File): Boolean {
            val backup = plaintextBackupFile(dbFile)
            if (!backup.exists() || !isPlaintextSqliteHeader(backup)) return false
            if (dbFile.exists() || sidecarExists(dbFile)) {
                try {
                    setAside(dbFile, System.currentTimeMillis())
                } catch (e: Exception) {
                    Log.e(TAG, "Could not set the failed encrypted database aside", e)
                    return false
                }
            }
            if (!backup.renameTo(dbFile)) {
                backup.copyTo(dbFile, overwrite = true)
                if (!backup.delete() && backup.exists()) {
                    Log.w(TAG, "Plaintext backup was copied back but the copy could not be removed")
                }
            }
            deleteSidecars(dbFile)
            return isPlaintextSqliteHeader(dbFile)
        }

        private fun sidecarExists(dbFile: File): Boolean =
            listOf("-wal", "-shm", "-journal").any { File(dbFile.path + it).exists() }

        private fun encryptMarker(dbFile: File): File =
            File(dbFile.parentFile, ENCRYPT_OK_NAME)

        /** Marks the plaintext copy as safe to remove. Call only after the encrypted file has opened. */
        @androidx.annotation.VisibleForTesting
        internal fun confirmPlaintextBackupDisposable(dbFile: File) {
            encryptMarker(dbFile).writeText("ok")
        }

        @androidx.annotation.VisibleForTesting
        internal fun clearPlaintextBackupConfirmation(dbFile: File) {
            encryptMarker(dbFile).delete()
        }

        /**
         * Removes [chat_database.pre_sqlcipher] only after [confirmPlaintextBackupDisposable].
         * Deleting it on every successful open used to erase the chats when the process died
         * mid-encrypt and the next launch created an empty database.
         */
        @androidx.annotation.VisibleForTesting
        internal fun discardPlaintextBackupIfConfirmed(dbFile: File) {
            val marker = encryptMarker(dbFile)
            if (!marker.exists()) return
            discardPlaintextBackup(dbFile)
            if (!marker.delete()) Log.w(TAG, "Could not remove encrypt marker ${marker.path}")
        }

        private fun discardPlaintextBackup(dbFile: File) {
            val backup = plaintextBackupFile(dbFile)
            if (!backup.exists()) return
            deleteSidecars(backup)
            if (!backup.delete()) Log.w(TAG, "Could not remove plaintext backup ${backup.path}")
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
