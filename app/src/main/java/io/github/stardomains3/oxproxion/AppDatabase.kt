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
            val dbName = prefs.chatDbFileName()
            val dbFile = context.getDatabasePath(dbName)
            val pending = prefs.recoveryPendingStamp()
            // A previous launch moved the file aside and died before the fresh database existed.
            // Opening now would create an empty file with the old key and hide the failure.
            if (pending != null && !dbFile.exists()) {
                Log.w(TAG, "Chat database recovery was interrupted; starting a fresh database")
                return openFreshAfterRecovery(context, prefs, dbName)
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

        private fun recover(context: Context, prefs: SharedPreferencesHelper, dbName: String): AppDatabase {
            val dbFile = context.getDatabasePath(dbName)
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
            prefs.archiveChatDbPassphrase(stamp)
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
                return openFreshAfterRecovery(context, prefs, fallback)
            }
            val actual = stampOf(moved) ?: stamp
            if (actual != stamp) prefs.archiveChatDbPassphrase(actual)
            prefs.markRecoveryPending(actual)
            return openFreshAfterRecovery(context, prefs, dbName)
        }

        private fun openFreshAfterRecovery(
            context: Context,
            prefs: SharedPreferencesHelper,
            dbName: String
        ): AppDatabase {
            val fresh = open(context, prefs.resetChatDbPassphrase(), dbName)
            prefs.markChatDbRecovered()
            return fresh
        }

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

        /** Encrypts a leftover plaintext file, opens Room, and forces the real open so a bad key surfaces here. */
        private fun open(context: Context, passphrase: ByteArray, dbName: String): AppDatabase {
            val dbFile = context.getDatabasePath(dbName)
            encryptPlaintextIfNeeded(dbFile, passphrase)
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
            // The encrypted file opened. The plaintext copy kept for a crash mid-encrypt can go.
            discardPlaintextBackup(dbFile)
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
         *
         * Already-encrypted (or corrupt) files must not enter this path — probing
         * them with an empty key throws and used to crash cold start.
         */
        private fun encryptPlaintextIfNeeded(dbFile: File, passphrase: ByteArray) {
            if (!dbFile.exists() || dbFile.length() == 0L) return
            if (!isPlaintextSqliteHeader(dbFile)) return

            val parent = dbFile.parentFile ?: return
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
         * Puts a leftover plaintext copy back when the encrypted file did not open. The failed
         * encrypted file is moved aside first, not deleted.
         */
        private fun restorePlaintextBackup(dbFile: File): Boolean {
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
