package io.github.stardomains3.oxproxion

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
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
 * absolute path would point at the wrong place after the app moves to another user id. When a
 * recovered or set-aside (unreadable) file cannot enter the vault (the vault already has that
 * name, or the move fails), it is parked under [HOLD_DIR] inside the databases directory. Move
 * temps for those names are parked there too. The backup rules exclude that folder, and Room
 * opens a parked recovered file by absolute path so the history is not uploaded.
 *
 * The live `chat_database` stays in the databases directory. The backup rules exclude it, its
 * journal, and the old plaintext name in case a move out of that directory fails.
 */
internal object ChatDbVault {
    private const val TAG = "ChatDbVault"
    const val DIR = "chat-db"
    /** Leftover recovered/unreadable copies that could not enter the vault. Excluded from Auto Backup. */
    const val HOLD_DIR = "chat_db_hold"
    private val RECOVERED = Regex("^chat_database\\.recovered-[0-9]{1,16}$")
    private val UNREADABLE = Regex("^chat_database\\.unreadable-[0-9]{1,16}(-wal|-shm|-journal)?$")
    private val KEEP_SUFFIX = Regex("\\.kept-[0-9]+$")
    private val SIDECARS = listOf("", "-wal", "-shm", "-journal")

    fun directory(context: Context): File =
        File(context.noBackupFilesDir, DIR).apply { mkdirs() }

    fun holdDirectory(databasesDir: File): File =
        File(databasesDir, HOLD_DIR).apply { mkdirs() }

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
     * The name passed to Room. A recovered file still at the databases root keeps a relative
     * name so Room opens that copy. One parked under [HOLD_DIR], or only in the vault, is an
     * absolute path: those directories are outside the default Room folder.
     */
    fun roomDatabaseName(context: Context, stored: String): String {
        if (!isRecoveredName(stored)) return AppDatabase.DB_NAME
        val databasesDir = context.getDatabasePath(AppDatabase.DB_NAME).parentFile
        if (databasesDir != null) {
            val hold = File(databasesDir, HOLD_DIR)
            // Prefer the parked history over a stale vault copy that blocked the move.
            if (hold.isDirectory && dbSetPresent(hold, stored)) {
                return File(hold, stored).absolutePath
            }
            if (dbSetPresent(databasesDir, stored)) return stored
        }
        return File(directory(context), stored).absolutePath
    }

    fun dbSetPresent(directory: File, name: String): Boolean =
        SIDECARS.any { File(directory, name + it).exists() }

    /**
     * Moves leftover copies out of [databasesDir].
     * Returns false when [storedRecovered] is still at the databases root afterwards, so the
     * caller keeps opening that file. A recovered or set-aside (unreadable) set that cannot enter
     * the vault is parked under [HOLD_DIR] (excluded from Auto Backup) instead of being left at
     * the root or renamed aside inside the vault. Move temps (`.partial` / `.ready` / `.bak`) and
     * `.kept-*` leftovers of those names are parked the same way: the backup rules do not list
     * them. A failure to move a plaintext or encrypt copy is logged and left in place; the backup
     * rules still name those files.
     */
    fun relocateLegacy(databasesDir: File, vault: File, storedRecovered: String?): Boolean {
        vault.mkdirs()
        // Earlier parks that the vault can take now.
        drainHold(databasesDir, vault)
        moveBestEffort(File(databasesDir, "${AppDatabase.DB_NAME}.pre_sqlcipher"), plaintextBackup(vault))
        moveBestEffort(File(databasesDir, "${AppDatabase.DB_NAME}.encrypt_ok"), encryptMarker(vault))
        moveBestEffort(File(databasesDir, "${AppDatabase.DB_NAME}.encrypting"), encrypting(vault))
        // Set-aside (unreadable) copies use the same set move as recovered names: when the vault
        // already has that stamp, park under hold. moveBestEffort used to uniqueKept into the vault,
        // which could split a main file from its wal and still leave a stamp collision for the key.
        val unreadable = LinkedHashSet<String>()
        databasesDir.listFiles()?.forEach { file ->
            unreadableBaseName(file.name)?.let { unreadable += it }
        }
        for (name in unreadable) {
            if (!relocateDbSet(databasesDir, vault, name)) {
                parkDbSet(databasesDir, holdDirectory(databasesDir), name)
            }
        }
        val recovered = LinkedHashSet<String>()
        databasesDir.listFiles()?.forEach { file ->
            recoveredBaseName(file.name)?.let { recovered += it }
        }
        var storedMoved = true
        for (name in recovered) {
            val moved = relocateDbSet(databasesDir, vault, name)
            if (!moved) {
                // Vault already has that name, or the move failed. Park under the excluded folder
                // so Auto Backup cannot upload the history while Room still opens it.
                if (!parkDbSet(databasesDir, holdDirectory(databasesDir), name)) {
                    if (name == storedRecovered) storedMoved = false
                }
            }
        }
        // A failed cross-directory move can leave .partial / .ready / .bak (or a .kept-* rename)
        // next to the live database. Those names are not in the backup rules.
        parkMoveTemps(databasesDir)
        if (storedRecovered != null && isRecoveredName(storedRecovered) && dbSetPresent(databasesDir, storedRecovered)) {
            storedMoved = false
        }
        return storedMoved
    }

    /**
     * Moves a recovered set from [HOLD_DIR] into [vault] when the vault is free.
     * Leftovers stay in the hold folder, which Auto Backup skips.
     */
    private fun drainHold(databasesDir: File, vault: File) {
        val hold = File(databasesDir, HOLD_DIR)
        if (!hold.isDirectory) return
        val names = LinkedHashSet<String>()
        hold.listFiles()?.forEach { file ->
            recoveredBaseName(file.name)?.let { names += it }
            if (UNREADABLE.matches(file.name)) {
                val base = file.name.removeSuffix("-wal").removeSuffix("-shm").removeSuffix("-journal")
                names += base
            }
        }
        for (name in names) {
            relocateDbSet(hold, vault, name)
        }
    }

    /**
     * Moves [name] and its sidecars from [fromDir] into [holdDir].
     * A destination that already exists is renamed aside first, so the live history keeps the
     * short name Room looks up. Returns false when nothing moved or a failure put the files back.
     */
    fun parkDbSet(fromDir: File, holdDir: File, name: String): Boolean {
        val present = SIDECARS.filter { File(fromDir, name + it).exists() }
        if (present.isEmpty()) return true
        holdDir.mkdirs()
        // Free the short name in the hold folder so Room can find this history there.
        for (suffix in present) {
            val dest = File(holdDir, name + suffix)
            if (!dest.exists()) continue
            val kept = uniqueKept(dest)
            try {
                moveReplacing(dest, kept)
            } catch (e: Exception) {
                Log.e(TAG, "Could not free ${dest.name} in the hold folder", e)
                return false
            }
        }
        return relocateDbSet(fromDir, holdDir, name)
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
            Log.w(TAG, "Leaving $name in ${databasesDir.path}; the destination already has that file")
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

    private fun unreadableBaseName(fileName: String): String? {
        if (!UNREADABLE.matches(fileName)) return null
        return fileName.removeSuffix("-wal").removeSuffix("-shm").removeSuffix("-journal")
    }

    /**
     * Parks cross-directory move leftovers at the databases root under [HOLD_DIR].
     * Auto Backup has no wildcards for `chat_database*.partial` (or `.ready` / `.bak` / `.kept-*`),
     * so those files would be uploaded if left beside the live database.
     */
    private fun parkMoveTemps(databasesDir: File) {
        val hold = holdDirectory(databasesDir)
        databasesDir.listFiles()?.forEach { file ->
            if (!file.isFile || !isMoveTempOrKept(file.name)) return@forEach
            moveBestEffort(file, File(hold, file.name))
        }
    }

    private fun isLiveDatabaseFileName(name: String): Boolean =
        name == AppDatabase.DB_NAME ||
            name == "${AppDatabase.DB_NAME}-wal" ||
            name == "${AppDatabase.DB_NAME}-shm" ||
            name == "${AppDatabase.DB_NAME}-journal"

    /** True for a chat-database move temp or `.kept-*` rename that the backup rules do not list. */
    internal fun isMoveTempOrKept(name: String): Boolean {
        if (!name.startsWith(AppDatabase.DB_NAME)) return false
        if (isLiveDatabaseFileName(name)) return false
        if (name.endsWith(".partial") || name.endsWith(".ready") || name.endsWith(".bak")) return true
        return KEEP_SUFFIX.containsMatchIn(name)
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

    /**
     * Test hook. The next [moveReplacing] copies instead of renaming, as a move across
     * directories does when rename is refused. Cleared when that move starts the copy.
     */
    @androidx.annotation.VisibleForTesting
    internal var copyInsteadForTest: Boolean = false

    /**
     * Test hook. After the copy is durable and before it takes the destination's name,
     * return and leave the files as a killed process would. Cleared when it fires.
     */
    @androidx.annotation.VisibleForTesting
    internal var stopAfterReadyForTest: Boolean = false

    /**
     * Test hook. After the previous destination has been moved aside, return before the
     * finished copy takes its name. Cleared when it fires.
     */
    @androidx.annotation.VisibleForTesting
    internal var stopAfterBakForTest: Boolean = false

    fun moveReplacing(from: File, to: File) {
        to.parentFile?.mkdirs()
        val copyInstead = copyInsteadForTest
        copyInsteadForTest = false
        if (finishReadyPartial(from, to)) return
        if (!copyInstead && from.renameTo(to)) {
            discardMoveTemps(to)
            return
        }
        // rename across directories can fail. The copy has to reach disk before the source
        // is removed, or a kill in between loses both. Bytes go to a side file, not to [to]:
        // a kill used to leave a half-written file under the real name, and the next launch
        // treated that name as the database and refused to try the move again.
        if (!from.exists()) throw IOException("Could not move ${from.path} to ${to.path}")
        val partial = partialFile(to)
        val ready = readyFile(to)
        val bak = bakFile(to)
        val destExisted = to.exists()
        try {
            partial.delete()
            ready.delete()
            from.copyTo(partial, overwrite = true)
            RandomAccessFile(partial, "rw").use { it.fd.sync() }
            syncDirectory(partial.parentFile)
            markReady(ready)
            if (stopAfterReadyForTest) {
                stopAfterReadyForTest = false
                return
            }
            if (destExisted) {
                if (bak.exists() && !bak.delete()) {
                    throw IOException("Could not move ${to.path} aside")
                }
                if (!to.renameTo(bak)) throw IOException("Could not move ${to.path} aside")
                syncDirectory(to.parentFile)
                if (stopAfterBakForTest) {
                    stopAfterBakForTest = false
                    return
                }
            }
            if (!partial.renameTo(to)) {
                if (!to.exists() && bak.exists()) bak.renameTo(to)
                throw IOException("Could not replace ${to.path}")
            }
            syncDirectory(to.parentFile)
            ready.delete()
            if (bak.exists() && !bak.delete()) Log.w(TAG, "Could not remove ${bak.path}")
            if (!from.delete() && from.exists()) {
                if (!destExisted) to.delete()
                throw IOException("Could not remove ${from.path} after copying it aside")
            }
        } catch (e: Exception) {
            if (!ready.exists()) partial.delete()
            if (!to.exists() && bak.exists() && !(ready.exists() && partial.exists())) {
                bak.renameTo(to)
            }
            if (e is IOException) throw e
            throw IOException("Could not move ${from.path} to ${to.path}", e)
        }
    }

    /**
     * A previous move copied [from] and died before the side file took [to]'s name.
     * The side file is only finished when the ready marker is present; a torn copy is removed.
     * Returns true when [to] now holds that copy and [from] has been removed.
     */
    private fun finishReadyPartial(from: File, to: File): Boolean {
        val partial = partialFile(to)
        val ready = readyFile(to)
        val bak = bakFile(to)
        val readyCopy = ready.exists() && partial.isFile && partial.length() > 0L
        if (readyCopy && !to.exists() && (!from.exists() || from.length() == partial.length())) {
            if (partial.renameTo(to)) {
                ready.delete()
                if (bak.exists() && !bak.delete()) Log.w(TAG, "Could not remove ${bak.path}")
                syncDirectory(to.parentFile)
                if (from.exists() && !from.delete() && from.exists()) {
                    throw IOException("Could not remove ${from.path} after copying it aside")
                }
                return true
            }
        }
        if (!to.exists() && bak.exists() && !readyCopy) {
            if (!bak.renameTo(to)) Log.e(TAG, "Could not restore ${bak.path}")
        }
        if (to.exists() && !readyCopy && bak.exists() && !bak.delete()) {
            Log.w(TAG, "Could not remove ${bak.path}")
        }
        if (!ready.exists() || to.exists()) {
            partial.delete()
            ready.delete()
        }
        return false
    }

    private fun discardMoveTemps(to: File) {
        partialFile(to).delete()
        readyFile(to).delete()
        val bak = bakFile(to)
        if (bak.exists() && !bak.delete()) Log.w(TAG, "Could not remove ${bak.path}")
    }

    private fun partialFile(to: File) = File(to.parentFile, to.name + ".partial")

    private fun readyFile(to: File) = File(to.parentFile, to.name + ".ready")

    private fun bakFile(to: File) = File(to.parentFile, to.name + ".bak")

    private fun markReady(ready: File) {
        FileOutputStream(ready).use { out ->
            out.write(1)
            out.fd.sync()
        }
        syncDirectory(ready.parentFile)
    }

    private fun syncDirectory(dir: File?) {
        if (dir == null) return
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
        }
    }
}
