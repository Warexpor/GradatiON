package io.github.stardomains3.oxproxion

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Chat-database files that are not the live `chat_database`.
 *
 * Auto Backup uploads every file in the databases directory except the names listed in the backup
 * rules. The plaintext pre-SQLCipher copy, the in-progress encrypt file, and a database set aside
 * after a failed open used to live there under names the rules did not list, so a backup could
 * upload the plaintext history. Those copies now live under [noBackupFilesDir][Context.getNoBackupFilesDir],
 * which backup and device transfer skip. A recovered database (opened when the original file could
 * not be moved) is created here too.
 *
 * The preference stores the short file name. [roomDatabaseName] resolves it on each open: an
 * absolute path would point at the wrong place after the app moves to another user id. While a
 * copy of that recovered file is still in the databases directory, the short name is returned so
 * Room keeps opening the file that has the history instead of creating an empty one here.
 *
 * The live `chat_database` stays in the databases directory. The backup rules exclude it, its
 * journal, and the old plaintext name in case a move out of that directory fails.
 */
internal object ChatDbVault {
    private const val TAG = "ChatDbVault"
    const val DIR = "chat-db"
    private val RECOVERED = Regex("^chat_database\\.recovered-[0-9]{1,16}$")
    private val UNREADABLE = Regex("^chat_database\\.unreadable-[0-9]{1,16}(-wal|-shm|-journal)?$")
    private val SIDECARS = listOf("", "-wal", "-shm", "-journal")

    fun directory(context: Context): File =
        File(context.noBackupFilesDir, DIR).apply { mkdirs() }

    fun plaintextBackup(vault: File): File = File(vault, "${AppDatabase.DB_NAME}.pre_sqlcipher")

    fun encrypting(vault: File): File = File(vault, "${AppDatabase.DB_NAME}.encrypting")

    fun encryptMarker(vault: File): File = File(vault, "${AppDatabase.DB_NAME}.encrypt_ok")

    fun unreadable(vault: File, stamp: Long): File =
        File(vault, "${AppDatabase.DB_NAME}.unreadable-$stamp")

    fun isRecoveredName(name: String): Boolean = RECOVERED.matches(name)

    /**
     * Names worth remembering. The default file, or `chat_database.recovered-<stamp>`.
     * Anything else (a path, the plaintext copy's name) is ignored so a corrupt preference
     * cannot point Room at a file the backup rules do not exclude.
     */
    fun isStoredDatabaseName(name: String): Boolean =
        name == AppDatabase.DB_NAME || isRecoveredName(name)

    /**
     * The name passed to Room. See the class comment for why a recovered file stays a relative
     * name while its copy in the databases directory is still present.
     */
    fun roomDatabaseName(context: Context, stored: String): String {
        if (!isRecoveredName(stored)) return AppDatabase.DB_NAME
        val databasesDir = context.getDatabasePath(AppDatabase.DB_NAME).parentFile
        if (databasesDir != null && dbSetPresent(databasesDir, stored)) return stored
        return File(directory(context), stored).absolutePath
    }

    fun dbSetPresent(directory: File, name: String): Boolean =
        SIDECARS.any { File(directory, name + it).exists() }

    /**
     * Moves leftover copies out of [databasesDir].
     * Returns false when [storedRecovered] is still in [databasesDir] afterwards, so the caller
     * keeps opening that file. A failure to move a plaintext or set-aside copy is logged and
     * left in place; the backup rules still name the plaintext file.
     */
    fun relocateLegacy(databasesDir: File, vault: File, storedRecovered: String?): Boolean {
        vault.mkdirs()
        moveBestEffort(File(databasesDir, "${AppDatabase.DB_NAME}.pre_sqlcipher"), plaintextBackup(vault))
        moveBestEffort(File(databasesDir, "${AppDatabase.DB_NAME}.encrypt_ok"), encryptMarker(vault))
        moveBestEffort(File(databasesDir, "${AppDatabase.DB_NAME}.encrypting"), encrypting(vault))
        databasesDir.listFiles()?.forEach { file ->
            if (UNREADABLE.matches(file.name)) moveBestEffort(file, File(vault, file.name))
        }
        val recovered = LinkedHashSet<String>()
        databasesDir.listFiles()?.forEach { file ->
            recoveredBaseName(file.name)?.let { recovered += it }
        }
        var storedMoved = true
        for (name in recovered) {
            val moved = relocateDbSet(databasesDir, vault, name)
            if (name == storedRecovered && !moved) storedMoved = false
        }
        if (storedRecovered != null && isRecoveredName(storedRecovered) && dbSetPresent(databasesDir, storedRecovered)) {
            storedMoved = false
        }
        return storedMoved
    }

    /**
     * Moves [name] and its wal, shm and journal into [vault].
     * A destination that already exists aborts before anything is moved. A failure part-way
     * puts back what this call moved, so the two directories are not left as a split pair.
     */
    fun relocateDbSet(databasesDir: File, vault: File, name: String): Boolean {
        val from = File(databasesDir, name)
        val present = SIDECARS.filter { File(from.path + it).exists() }
        if (present.isEmpty()) return true
        val destMain = File(vault, name)
        if (present.any { File(destMain.path + it).exists() }) {
            Log.w(TAG, "Leaving $name in the databases directory; the vault already has that file")
            return false
        }
        val moved = ArrayList<Pair<File, File>>(present.size)
        try {
            for (suffix in present) {
                val src = File(from.path + suffix)
                val dest = File(destMain.path + suffix)
                moveReplacing(src, dest)
                moved += src to dest
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Could not move $name out of the databases directory", e)
            for ((src, dest) in moved.asReversed()) {
                try {
                    if (dest.exists()) moveReplacing(dest, src)
                } catch (rollback: Exception) {
                    Log.e(TAG, "Could not put ${dest.name} back", rollback)
                }
            }
            return false
        }
    }

    private fun recoveredBaseName(fileName: String): String? {
        if (isRecoveredName(fileName)) return fileName
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            if (!fileName.endsWith(suffix)) continue
            val base = fileName.removeSuffix(suffix)
            if (isRecoveredName(base)) return base
        }
        return null
    }

    private fun moveBestEffort(from: File, to: File) {
        if (!from.exists()) return
        val dest = if (to.exists()) uniqueKept(to) else to
        try {
            moveReplacing(from, dest)
        } catch (e: Exception) {
            Log.e(TAG, "Could not move ${from.name} out of the databases directory", e)
        }
    }

    private fun uniqueKept(to: File): File {
        var n = 1
        var alt = File(to.parentFile, to.name + ".kept-$n")
        while (alt.exists()) {
            n++
            alt = File(to.parentFile, to.name + ".kept-$n")
        }
        return alt
    }

    fun moveReplacing(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return
        // rename across directories can fail. The copy has to reach disk before the source
        // is removed, or a kill in between loses both. A copy that fails must not leave a
        // partial file at the destination: the next launch treats that name as the real one
        // and refuses to try the move again.
        val destExisted = to.exists()
        try {
            from.copyTo(to, overwrite = true)
            RandomAccessFile(to, "rw").use { it.fd.sync() }
            syncDirectory(to.parentFile)
            if (!from.delete() && from.exists()) {
                if (!destExisted) to.delete()
                throw IOException("Could not remove ${from.path} after copying it aside")
            }
        } catch (e: Exception) {
            if (from.exists() && !destExisted) to.delete()
            if (e is IOException) throw e
            throw IOException("Could not move ${from.path} to ${to.path}", e)
        }
    }

    private fun syncDirectory(dir: File?) {
        if (dir == null) return
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
        }
    }
}
