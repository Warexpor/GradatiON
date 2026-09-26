package io.github.stardomains3.oxproxion

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.RandomAccessFile

@Database(
    entities = [ChatSession::class, ChatMessage::class, RpCharacter::class, RpLorebook::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun rpDao(): RpDao

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

        private fun build(context: Context): AppDatabase {
            ensureNativeLoaded()
            val passphrase = SharedPreferencesHelper(context).getOrCreateChatDbPassphrase()
            encryptPlaintextIfNeeded(context, passphrase)
            val factory = SupportOpenHelperFactory(passphrase.copyOf())
            return Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(DatabaseMigrations.MIGRATION_1_2, DatabaseMigrations.MIGRATION_2_3)
                .build()
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
