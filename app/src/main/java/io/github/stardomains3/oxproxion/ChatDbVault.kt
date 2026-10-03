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
 * opens a parked recovered file by absolute path so the history is not uploaded. An orphan
 * sidecar (no main file) is discarded rather than moved into the vault, so Room does not
 * create an empty database beside it. Vault leftovers without a main are cleared so a
 * complete hold set can drain. Legacy plaintext / encrypting copies move as a set too:
 * a leftover `-wal` beside `chat_database.pre_sqlcipher` used to stay at the databases root.
 * The backup rules also name those sidecars so a backup before the next open cannot upload them.
 *
 * The live `chat_database` stays in the databases directory. The backup rules exclude it, its
 * journal, and the old plaintext / encrypting / encrypt_ok names (and their wal/shm/journal) in
 * case a move out of that directory fails. Move temps (`.partial` / `.ready` / `.bak`) of those
 * names — including their `-wal`/`-shm`/`-journal` sidecars — are listed too, so a backup
 * before the next open cannot upload a torn copy.
 * `code_mode.xml` is excluded too: a failed Keystore vault write can leave a pairing token in the
 * hosts JSON until the next successful scrub.
 *
 * When the vault already has the encrypt marker, leftover `pre_sqlcipher` / `encrypting` at the
 * databases root or under hold are discarded rather than drained back into the vault: the marker
 * means the plaintext snapshot was confirmed disposable. `.kept-*` renames of those disposable
 * names (including a second collision, `name.kept-1.kept-2`) are discarded too. Recovered and
 * unreadable `.kept-*` parks stay.
 * A wal that could not be deleted is renamed `name.stuck-N` (in this vault, or a live sidecar
 * parked here). Those renames are discarded once the encrypt marker is present. Recovered
 * `.stuck-*` names stay.
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
     * The root file wins when it is still there: a failed park leaves the current history at
     * the root beside an older hold copy, and opening the hold file would hide it.
     * A 0-byte name is not that history. Room leaves one when an open dies before the header
     * is written, and treating it as the database hid the copy that still has bytes.
     */
    fun roomDatabaseName(context: Context, stored: String): String {
        if (!isRecoveredName(stored)) return AppDatabase.DB_NAME
        val databasesDir = context.getDatabasePath(AppDatabase.DB_NAME).parentFile
        if (databasesDir != null) {
            if (hasBytes(File(databasesDir, stored))) return stored
            val hold = File(databasesDir, HOLD_DIR)
            // Hold beats a stale vault copy that blocked the move.
            // Require the main file: an orphan -wal/-shm in hold must not hide the vault copy
            // (Room would create an empty main beside that sidecar). An empty main is the same.
            if (hold.isDirectory && hasBytes(File(hold, stored))) {
                return File(hold, stored).absolutePath
            }
        }
        return File(directory(context), stored).absolutePath
    }

    /** A main file Room can open. A directory or a 0-byte placeholder is not one. */
    private fun hasBytes(file: File) = file.isFile && file.length() > 0L

    fun dbSetPresent(directory: File, name: String): Boolean =
        SIDECARS.any { File(directory, name + it).exists() }

    /**
     * Moves leftover copies out of [databasesDir].
     * Returns false when [storedRecovered] is still at the databases root afterwards, so the
     * caller keeps opening that file. A recovered or set-aside (unreadable) set that cannot enter
     * the vault is parked under [HOLD_DIR] (excluded from Auto Backup) instead of being left at
     * the root or renamed aside inside the vault. Sidecar-only leftovers (no main file) are parked
     * under hold and then discarded: moving them into the vault would let Room mint an empty main
     * beside the orphan. Legacy `pre_sqlcipher` / `encrypting` copies (and their sidecars) move as
     * one set into the vault, or park under hold when that name is taken — a lone `-wal` was not
     * in the backup rules. Move temps (`.partial` / `.ready` / `.bak`) and `.kept-*` leftovers of
     * those names are parked the same way: the backup rules do not list them. When the vault
     * already has the encrypt marker, a leftover at the databases root is discarded (not
     * `uniqueKept` into the vault). A failure to move it is logged and left in place; the backup
     * rules still name that file and its sidecars. When that marker is present, leftover
     * `pre_sqlcipher` / `encrypting` at the root or under hold are discarded too (do not
     * resurrect disposable plaintext). `.kept-*` renames of those names are discarded as well
     * (recovered `.kept-*` stay). Move temps parked under hold are discarded after the
     * park pass: Room never opens them, and the backup rules cover the root window.
     */
    fun relocateLegacy(databasesDir: File, vault: File, storedRecovered: String?): Boolean {
        vault.mkdirs()
        // A 0-byte main is not history. Install a finished side file onto that name
        // before anything moves the empty file or deletes the side file.
        installReadyCopiesOverEmpty(databasesDir)
        installReadyCopiesOverEmpty(File(databasesDir, HOLD_DIR))
        installReadyCopiesOverEmpty(vault)
        // Drop vault orphans before drain so a complete hold set is not blocked by a leftover -wal.
        discardIncompleteSets(vault)
        // Earlier parks that the vault can take now.
        drainHold(databasesDir, vault)
        // Move plaintext / encrypting as a set (main + wal/shm/journal). moveBestEffort alone
        // left sidecars at the databases root, and Auto Backup has no wildcards for those names.
        relocateLegacyNamed(databasesDir, vault, "${AppDatabase.DB_NAME}.pre_sqlcipher")
        relocateLegacyNamed(databasesDir, vault, "${AppDatabase.DB_NAME}.encrypting")
        relocateEncryptMarker(databasesDir, vault)
        // Set-aside (unreadable) copies use the same set move as recovered names: when the vault
        // already has that stamp, park under hold. moveBestEffort used to uniqueKept into the vault,
        // which could split a main file from its wal and still leave a stamp collision for the key.
        val unreadable = LinkedHashSet<String>()
        databasesDir.listFiles()?.forEach { file ->
            unreadableBaseName(file.name)?.let { unreadable += it }
        }
        for (name in unreadable) {
            if (!File(databasesDir, name).isFile) {
                // Sidecar-only: park out of Auto Backup; drainHold discards (no main to open).
                if (dbSetPresent(databasesDir, name)) {
                    parkDbSet(databasesDir, holdDirectory(databasesDir), name)
                }
                continue
            }
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
            if (!File(databasesDir, name).isFile) {
                if (dbSetPresent(databasesDir, name)) {
                    parkDbSet(databasesDir, holdDirectory(databasesDir), name)
                }
                continue
            }
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
        // next to the live database. Park under hold, then discard: Room never opens those names,
        // and the backup rules list the finite root temps for the pre-open window.
        // Drop disposable plaintext .kept-* at the root before park, so a hold-name
        // collision cannot rename them to `.kept-1.kept-1` and hide them from this pass.
        if (encryptMarker(vault).isFile) {
            discardDisposableKeptRenames(databasesDir)
            discardDisposableKeptRenames(vault)
            discardDisposableStuckRenames(databasesDir)
            discardDisposableStuckRenames(vault)
        }
        parkMoveTemps(databasesDir)
        // Temps just parked under hold can sit next to a 0-byte main. Install them
        // before the hold pass deletes every .partial / .ready / .bak.
        installReadyCopiesOverEmpty(File(databasesDir, HOLD_DIR))
        discardHoldMoveTemps(databasesDir)
        if (encryptMarker(vault).isFile) {
            discardDisposableKeptRenames(holdDirectory(databasesDir))
            discardDisposableKeptRenames(databasesDir)
            discardDisposableStuckRenames(holdDirectory(databasesDir))
            discardDisposableStuckRenames(databasesDir)
        }
        // Orphans parked after the first drain (sidecar-only sets) are discarded here.
        drainHold(databasesDir, vault)
        if (storedRecovered != null && isRecoveredName(storedRecovered) && dbSetPresent(databasesDir, storedRecovered)) {
            storedMoved = false
        }
        return storedMoved
    }

    /**
     * Moves a recovered set from [HOLD_DIR] into [vault] when the vault is free.
     * Leftovers stay in the hold folder, which Auto Backup skips. Orphan sidecars (no main)
     * are discarded instead of being promoted into the vault.
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
            legacyPlainBaseName(file.name)?.let { names += it }
        }
        for (name in names) {
            val holdFile = File(hold, name)
            val vaultFile = File(vault, name)
            val holdMain = holdFile.isFile
            val vaultMain = vaultFile.isFile
            if (!holdMain) {
                // Orphan sidecars only. Never move them into the vault: Room would create an
                // empty main beside the leftover -wal/-shm. Drop them whether or not the vault
                // already has a main (wave 19 handled the vault-has-main case only).
                discardShortNameSet(hold, name)
                continue
            }
            if (vaultMain) {
                if (isLegacyPlainName(name)) {
                    // Restore only reads the vault copy. Drop the parked duplicate.
                    discardShortNameSet(hold, name)
                    continue
                }
                // A 0-byte vault main is the placeholder an open leaves before the header.
                // It is not history. Leaving it in place blocked the hold copy that still
                // has bytes from ever moving into the vault. Drop it below and move that copy.
                if (hasBytes(vaultFile) || !hasBytes(holdFile)) continue
            }
            if (isDisposableLegacyPlain(name) && encryptMarker(vault).isFile) {
                // Migration was confirmed. Do not resurrect disposable plaintext into the vault.
                discardShortNameSet(hold, name)
                continue
            }
            if (dbSetPresent(vault, name)) {
                // Vault has only leftovers; clear so the complete hold set can move in.
                discardShortNameSet(vault, name)
            }
            relocateDbSet(hold, vault, name)
        }
    }

    /**
     * Drops recovered/unreadable/legacy-plaintext/encrypt_ok sidecar leftovers that have no main file.
     * An orphan -wal in the vault used to block a later hold drain, and Room opening that
     * recovered name would mint an empty main beside it.
     */
    private fun discardIncompleteSets(directory: File) {
        if (!directory.isDirectory) return
        val names = LinkedHashSet<String>()
        directory.listFiles()?.forEach { file ->
            recoveredBaseName(file.name)?.let { names += it }
            if (UNREADABLE.matches(file.name)) {
                val base = file.name.removeSuffix("-wal").removeSuffix("-shm").removeSuffix("-journal")
                names += base
            }
            legacyPlainBaseName(file.name)?.let { names += it }
        }
        for (name in names) {
            if (!File(directory, name).isFile && dbSetPresent(directory, name)) {
                discardShortNameSet(directory, name)
            }
        }
    }

    /** Removes short-name main/wal/shm/journal pieces. Leaves `.kept-*` renames alone. */
    private fun discardShortNameSet(directory: File, name: String) {
        for (suffix in SIDECARS) {
            File(directory, name + suffix).delete()
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
        val sourceMain = File(fromDir, name)
        val destMain = File(holdDir, name)
        // Room leaves a 0-byte main when an open dies before the header is written.
        // Freeing the hold name for that file renamed the copy that still has bytes
        // to `.kept-N`, which Room does not open.
        if (sourceMain.isFile && !hasBytes(sourceMain) && hasBytes(destMain)) {
            discardShortNameSet(fromDir, name)
            return !sourceMain.exists()
        }
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

    private fun isLegacyPlainName(name: String): Boolean =
        name == "${AppDatabase.DB_NAME}.pre_sqlcipher" ||
            name == "${AppDatabase.DB_NAME}.encrypting" ||
            name == "${AppDatabase.DB_NAME}.encrypt_ok"

    /** Main or `-wal`/`-shm`/`-journal` of a legacy plaintext / encrypting / encrypt_ok copy. */
    private fun legacyPlainBaseName(fileName: String): String? {
        for (base in listOf(
            "${AppDatabase.DB_NAME}.pre_sqlcipher",
            "${AppDatabase.DB_NAME}.encrypting",
            "${AppDatabase.DB_NAME}.encrypt_ok",
        )) {
            if (fileName == base) return base
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                if (fileName == base + suffix) return base
            }
        }
        return null
    }

    /**
     * Moves a leftover encrypt marker into [vault]. When the vault already has one, discard
     * the databases-root copy (and any unexpected sidecars) instead of `uniqueKept` into the vault.
     * Sidecar-only leftovers are discarded: a marker has no useful wal/shm.
     */
    private fun relocateEncryptMarker(databasesDir: File, vault: File) {
        val name = "${AppDatabase.DB_NAME}.encrypt_ok"
        val from = File(databasesDir, name)
        if (encryptMarker(vault).isFile) {
            if (dbSetPresent(databasesDir, name)) {
                discardShortNameSet(databasesDir, name)
            }
            return
        }
        if (!from.isFile) {
            if (dbSetPresent(databasesDir, name)) {
                discardShortNameSet(databasesDir, name)
            }
            return
        }
        moveBestEffort(from, encryptMarker(vault))
        // Sidecars of a marker are unexpected; drop any that the main move left behind.
        if (dbSetPresent(databasesDir, name) && !from.isFile) {
            discardShortNameSet(databasesDir, name)
        }
    }

    /**
     * Moves a legacy plaintext / encrypting copy (and its sidecars) into [vault].
     * When the vault already has that short name, the databases-root set is discarded.
     * When the vault already has the encrypt marker, leftovers are discarded too (do not
     * resurrect disposable plaintext). Otherwise a move failure parks under [HOLD_DIR].
     */
    private fun relocateLegacyNamed(databasesDir: File, vault: File, name: String) {
        if (File(vault, name).isFile) {
            // Vault already has the restore copy. Drop the databases-root set so Auto Backup
            // cannot upload a leftover -wal; do not uniqueKept into the vault.
            if (dbSetPresent(databasesDir, name)) {
                discardShortNameSet(databasesDir, name)
            }
            return
        }
        if (isDisposableLegacyPlain(name) && encryptMarker(vault).isFile) {
            // encrypt_ok means the plaintext snapshot was confirmed disposable. A leftover
            // at the databases root (or under hold) must not re-enter the vault.
            if (dbSetPresent(databasesDir, name)) {
                discardShortNameSet(databasesDir, name)
            }
            val hold = File(databasesDir, HOLD_DIR)
            if (hold.isDirectory && dbSetPresent(hold, name)) {
                discardShortNameSet(hold, name)
            }
            return
        }
        if (!File(databasesDir, name).isFile) {
            if (dbSetPresent(databasesDir, name)) {
                parkDbSet(databasesDir, holdDirectory(databasesDir), name)
            }
            return
        }
        if (!relocateDbSet(databasesDir, vault, name)) {
            parkDbSet(databasesDir, holdDirectory(databasesDir), name)
        }
    }

    /**
     * Parks cross-directory move leftovers at the databases root under [HOLD_DIR].
     * Auto Backup has no wildcards for stamp-scoped temps; the backup rules name the finite
     * live / plaintext / encrypting / encrypt_ok `.partial` / `.ready` / `.bak` set (mains and
     * their `-wal`/`-shm`/`-journal`) so a backup before the next open cannot upload those.
     * Stamp-scoped recovered/unreadable temps still rely on this park.
     */
    private fun parkMoveTemps(databasesDir: File) {
        val hold = holdDirectory(databasesDir)
        databasesDir.listFiles()?.forEach { file ->
            if (!file.isFile || !isMoveTempOrKept(file.name)) return@forEach
            moveBestEffort(file, File(hold, file.name))
        }
    }

    /**
     * Drops torn `.partial` / `.ready` / `.bak` copies under [HOLD_DIR]. Those are never opened
     * by Room. `.kept-*` renames stay: [parkDbSet] may have just moved a previous hold main aside
     * under that name, and deleting it would drop parked history after a failed set move.
     */
    private fun discardHoldMoveTemps(databasesDir: File) {
        val hold = File(databasesDir, HOLD_DIR)
        if (!hold.isDirectory) return
        hold.listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.startsWith(AppDatabase.DB_NAME)) return@forEach
            val n = file.name
            if (n.endsWith(".partial") || n.endsWith(".ready") || n.endsWith(".bak")) {
                file.delete()
            }
        }
    }

    /** Plaintext snapshot or in-progress encrypt file — disposable once [encryptMarker] exists. */
    private fun isDisposableLegacyPlain(name: String): Boolean =
        name == "${AppDatabase.DB_NAME}.pre_sqlcipher" ||
            name == "${AppDatabase.DB_NAME}.encrypting"

    /**
     * `.kept-*` from [uniqueKept] of a disposable plaintext / encrypting main or sidecar.
     * A hold collision can stack another `.kept-N`. Recovered / unreadable parks do not match.
     */
    private val DISPOSABLE_KEPT = Regex(
        "^" + Regex.escape(AppDatabase.DB_NAME) +
            "\\.(pre_sqlcipher|encrypting)(-wal|-shm|-journal)?(\\.kept-[0-9]+)+$"
    )

    internal fun isDisposablePlainKeptName(name: String): Boolean = DISPOSABLE_KEPT.matches(name)

    /**
     * `name.stuck-N` from a plaintext / encrypting file (or its sidecar), or from a live
     * `-wal`/`-shm`/`-journal` parked off the database Room opens. Recovered names do not match.
     */
    private val DISPOSABLE_STUCK = Regex(
        "^" + Regex.escape(AppDatabase.DB_NAME) +
            "(\\.(pre_sqlcipher|encrypting)(-wal|-shm|-journal)?|(-wal|-shm|-journal))\\.stuck-[0-9]+$"
    )

    internal fun isDisposablePlainStuckName(name: String): Boolean = DISPOSABLE_STUCK.matches(name)

    /**
     * Drops [isDisposablePlainStuckName] files. A non-empty directory with that name cannot be
     * deleted and counts as failure so the encrypt marker stays for a retry.
     * Returns false if any matching name remains.
     */
    internal fun discardDisposableStuckRenames(directory: File): Boolean {
        if (!directory.isDirectory) return true
        var ok = true
        directory.listFiles()?.forEach { file ->
            if (!isDisposablePlainStuckName(file.name)) return@forEach
            if (!file.delete() && file.exists()) ok = false
        }
        return ok && directory.listFiles()?.none { isDisposablePlainStuckName(it.name) } != false
    }

    /**
     * Drops [isDisposablePlainKeptName] files. A non-empty directory with that name cannot be
     * deleted and counts as failure so the encrypt marker stays for a retry.
     * Returns false if any matching name remains.
     */
    internal fun discardDisposableKeptRenames(directory: File): Boolean {
        if (!directory.isDirectory) return true
        var ok = true
        directory.listFiles()?.forEach { file ->
            if (!isDisposablePlainKeptName(file.name)) return@forEach
            if (!file.delete() && file.exists()) ok = false
        }
        return ok && directory.listFiles()?.none { isDisposablePlainKeptName(it.name) } != false
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
        // A 0-byte name is the placeholder an open leaves before the header is written.
        // Treating it as the destination used to delete the finished side file.
        if (to.isFile && to.length() == 0L && readyCopy) {
            if (!to.delete() && to.exists()) return false
        }
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

    /**
     * Puts a finished `.partial` onto a 0-byte recovered or set-aside main in [directory].
     * That empty file is not the database. Moving it, then deleting the side file,
     * dropped the copy that still has bytes. A main that already has bytes is left
     * alone, and so is a side file with no ready marker.
     */
    private fun installReadyCopiesOverEmpty(directory: File) {
        if (!directory.isDirectory) return
        val names = LinkedHashSet<String>()
        directory.listFiles()?.forEach { file ->
            val base = when {
                file.name.endsWith(".partial") -> file.name.removeSuffix(".partial")
                file.name.endsWith(".ready") -> file.name.removeSuffix(".ready")
                else -> return@forEach
            }
            if (isRecoveredName(base) || UNREADABLE.matches(base)) names += base
        }
        for (name in names) {
            val to = File(directory, name)
            if (!to.isFile || to.length() != 0L) continue
            val partial = partialFile(to)
            val ready = readyFile(to)
            if (!ready.isFile || !partial.isFile || partial.length() <= 0L) continue
            if (!to.delete() && to.exists()) continue
            if (!partial.renameTo(to)) continue
            if (ready.exists() && !ready.delete()) Log.w(TAG, "Could not remove ${ready.path}")
        }
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
