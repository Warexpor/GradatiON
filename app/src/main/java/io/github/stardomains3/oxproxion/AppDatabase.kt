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
         * deleted, a fresh database starts, and a flag tells the UI to say so once.
         * Blocking: call from a background thread.
         */
        private fun build(context: Context): AppDatabase {
            ensureNativeLoaded()
            val prefs = SharedPreferencesHelper(context)
            // One retry first: the Keystore can answer badly for a moment (right after unlock, say),
            // and setting a healthy database aside for that would look like lost history.
            for (attempt in 1..2) {
                try {
                    return open(context, prefs.getOrCreateChatDbPassphrase())
                } catch (e: Exception) {
                    Log.e(TAG, "Chat database could not be opened (attempt $attempt)", e)
                    if (attempt == 1) Thread.sleep(300)
                }
            }
            val dbFile = context.getDatabasePath(DB_NAME)
            setAside(dbFile, System.currentTimeMillis())
            val fresh = open(context, prefs.resetChatDbPassphrase())
            prefs.markChatDbRecovered()
            return fresh
        }

        /** Encrypts a leftover plaintext file, opens Room, and forces the real open so a bad key surfaces here. */
        private fun open(context: Context, passphrase: ByteArray): AppDatabase {
            encryptPlaintextIfNeeded(context, passphrase)
            val factory = SupportOpenHelperFactory(passphrase.copyOf())
            val db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
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
            return db
        }

        /**
         * Moves [dbFile] and its -wal/-shm/-journal next to it as `chat_database.unreadable-<stamp>`
         * (sidecars keep their suffix after that). Never deletes: the file may still be recoverable.
         * Returns the moved main file, or null when there was nothing to move.
         */
        @androidx.annotation.VisibleForTesting
        internal fun setAside(dbFile: File, stamp: Long): File? {
            val target = File(dbFile.path + ".unreadable-$stamp")
            var moved: File? = null
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                val from = File(dbFile.path + suffix)
                if (!from.exists()) continue
                val to = File(target.path + suffix)
                if (!from.renameTo(to)) {
                    // Rename can fail across mounts; a copy that finished is as good as a move.
                    from.copyTo(to, overwrite = true)
                    from.delete()
                }
                if (suffix.isEmpty()) moved = to
            }
            return moved
        }

        /**
         * One-shot: if an unencrypted Room DB already exists, rewrite it via
         * sqlcipher_export before Room opens with SupportOpenHelperFactory.
         *
         * Already-encrypted (or corrupt) files must not enter this path — probing
         * them with an empty key throws and used to crash cold start.
         */
        private fun encryptPlaintextIfNeeded(context: Context, passphrase: ByteArray) {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists() || dbFile.length() == 0L) return
            if (!isPlaintextSqliteHeader(dbFile)) return

            val parent = dbFile.parentFile ?: return
            val encryptedTemp = File(parent, "$DB_NAME.encrypting")
            val backup = File(parent, "$DB_NAME.pre_sqlcipher")
            encryptedTemp.delete()
            backup.delete()

            val hexKey = passphrase.joinToString("") { b -> "%02x".format(b) }
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
                    "ATTACH DATABASE '${encryptedTemp.absolutePath}' AS encrypted KEY \"x'$hexKey'\";"
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
                deleteSidecars(backup)
                backup.delete()
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
