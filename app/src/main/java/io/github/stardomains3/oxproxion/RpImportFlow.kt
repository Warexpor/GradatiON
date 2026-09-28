package io.github.stardomains3.oxproxion

import android.app.Activity
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Picking and importing character and lorebook files. Lives outside the hub so the empty
 * libraries can offer Import too. Build it as a property of the fragment: the result
 * launchers must be registered before the fragment starts.
 */
class RpImportFlow(
    private val fragment: Fragment,
    private val viewModel: () -> ChatViewModel
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val charsLauncher = fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                importCharacterBytes(readBytes(uri))
            } catch (_: Exception) {
                GlassNotice.show(fragment.requireContext(), fragment.getString(R.string.rp_import_not_card))
            }
        }
    }

    private val loreLauncher = fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bytes = readBytes(uri) ?: run {
                    GlassNotice.show(fragment.requireContext(), fragment.getString(R.string.rp_import_failed))
                    return@launch
                }
                importLorebookText(bytes.toString(Charsets.UTF_8))
            } catch (_: Exception) {
                GlassNotice.show(fragment.requireContext(), fragment.getString(R.string.rp_import_failed))
            }
        }
    }

    /** One picker for everything: the file's content decides between characters, a Tavern card and a lorebook backup. */
    private val anyLauncher = fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bytes = readBytes(uri)
                if (bytes != null && isLorebookBackup(bytes)) importLorebookText(bytes.toString(Charsets.UTF_8))
                else importCharacterBytes(bytes)
            } catch (_: Exception) {
                GlassNotice.show(fragment.requireContext(), fragment.getString(R.string.rp_import_not_card))
            }
        }
    }

    private suspend fun readBytes(uri: android.net.Uri): ByteArray? = withContext(Dispatchers.IO) {
        fragment.requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    private suspend fun importCharacterBytes(bytes: ByteArray?) {
        val incoming = bytes?.let { RpCharacterImport.parse(it) }
        if (incoming == null) {
            GlassNotice.show(fragment.requireContext(), fragment.getString(R.string.rp_import_not_card))
            return
        }
        if (incoming.isEmpty()) {
            GlassNotice.show(fragment.requireContext(), fragment.getString(R.string.rp_import_empty))
            return
        }
        val backup = RpCharacterBackup(incoming)
        val matches = RpImportRules.matchingCharacters(
            incoming,
            viewModel().getRpRepository().getAllCharactersOnce()
        )
        if (matches.isEmpty()) {
            applyCharacterBackup(backup, keepBoth = false)
            return
        }
        // Replace updates the ones named here; Keep both adds the import next to them.
        // Tapping outside the dialog changes nothing.
        GrokConfirmDialog.show(
            fragment = fragment,
            title = fragment.getString(R.string.rp_import_overwrite_title),
            message = fragment.getString(R.string.rp_import_overwrite_chars, matches.joinToString(", ") { it.name }),
            confirmText = fragment.getString(R.string.rp_import_replace),
            onConfirm = {
                fragment.viewLifecycleOwner.lifecycleScope.launch { applyCharacterBackup(backup, keepBoth = false) }
            },
            destructive = false,
            cancelText = fragment.getString(R.string.rp_import_keep_both),
            onCancel = {
                fragment.viewLifecycleOwner.lifecycleScope.launch { applyCharacterBackup(backup, keepBoth = true) }
            }
        )
    }

    private suspend fun importLorebookText(text: String) {
        val backup = json.decodeFromString(RpLorebookBackup.serializer(), text)
        if (backup.lorebooks.isEmpty()) {
            GlassNotice.show(fragment.requireContext(), fragment.getString(R.string.rp_import_empty))
            return
        }
        val repo = viewModel().getRpRepository()
        val existingNames = repo.getAllLorebooksOnce().map { it.name }
        val clashing = backup.lorebooks.filter { ex -> existingNames.any { it.equals(ex.name, ignoreCase = true) } }
        if (clashing.isNotEmpty()) {
            GrokConfirmDialog.show(
                fragment = fragment,
                title = fragment.getString(R.string.rp_import_lore_clash_title),
                message = fragment.getString(R.string.rp_import_lore_clash, clashing.joinToString(", ") { it.name }),
                confirmText = fragment.getString(R.string.rp_import_confirm),
                onConfirm = {
                    fragment.viewLifecycleOwner.lifecycleScope.launch { applyLoreBackup(backup) }
                },
                destructive = false
            )
        } else {
            applyLoreBackup(backup)
        }
    }

    private suspend fun applyCharacterBackup(backup: RpCharacterBackup, keepBoth: Boolean) {
        if (!fragment.isAdded) return
        try {
            val repo = viewModel().getRpRepository()
            val library = repo.getAllCharactersOnce()
            val names = library.map { it.name }.toMutableList()
            var imported = 0
            backup.characters.forEach { ex ->
                val match = RpImportRules.matchingCharacters(listOf(ex), library).firstOrNull()
                val existing = if (keepBoth) null else match
                // Keep both: the copy gets its own export key so a later backup import can't fold it back.
                val freshCopy = keepBoth && match != null
                val name = if (freshCopy) RpImportRules.uniqueName(ex.name, names) else ex.name
                val base = existing ?: RpCharacter(
                    name = name,
                    exportKey = if (freshCopy) java.util.UUID.randomUUID().toString()
                    else ex.exportKey.ifBlank { java.util.UUID.randomUUID().toString() }
                )
                val savedId = repo.saveCharacter(
                    base.copy(
                        name = name,
                        personality = ex.personality,
                        style = ex.style,
                        greeting = ex.greeting,
                        scenario = ex.scenario,
                        examplesJson = ex.examplesJson,
                        prompt = ex.prompt,
                        instruction = ex.instruction,
                        photoUri = existing?.photoUri,
                        exportKey = base.exportKey
                    )
                )
                names += name
                if (existing == null && !freshCopy && base.exportKey.isNotBlank()) {
                    val oldId = SharedPreferencesHelper(fragment.requireContext()).takeDeletedRpCharacterId(base.exportKey)
                    if (oldId != null && oldId != savedId) {
                        viewModel().remappingCharacterSessions(oldId, savedId)
                    }
                }
                val photoUri = when {
                    !ex.avatarBase64.isNullOrBlank() ->
                        RpAvatarStorage.saveFromBase64(fragment.requireContext(), ex.avatarBase64, savedId)
                    else -> null
                }
                if (photoUri != null) {
                    repo.getCharacterById(savedId)?.let { c ->
                        repo.saveCharacter(c.copy(photoUri = photoUri))
                    }
                }
                imported++
            }
            viewModel().refreshActiveRpCharacter()
            viewModel().syncActiveCharacterGreetingIfIdle()
            if (!fragment.isAdded) return
            GlassNotice.show(fragment.requireContext(), fragment.getString(R.string.rp_import_chars_ok, imported))
        } catch (_: Exception) {
            if (fragment.isAdded) GlassNotice.show(fragment.requireContext(), fragment.getString(R.string.rp_import_failed))
        }
    }

    private suspend fun applyLoreBackup(backup: RpLorebookBackup) {
        if (!fragment.isAdded) return
        try {
            val repo = viewModel().getRpRepository()
            val names = repo.getAllLorebooksOnce().map { it.name }.toMutableList()
            val hadBooksBefore = names.isNotEmpty()
            var imported = 0
            backup.lorebooks.forEach { ex ->
                // A book with the same name is a different book: number the newcomer, never merge into it.
                val name = RpImportRules.uniqueName(ex.name, names)
                names += name
                val id = repo.saveLorebook(RpLorebook(name = name, content = ex.content))
                if (ex.isActive) repo.setActiveLorebook(id)
                imported++
            }
            if (!hadBooksBefore && repo.getActiveLorebook() == null) {
                repo.getAllLorebooksOnce().firstOrNull()?.let { repo.setActiveLorebook(it.id) }
            }
            if (!fragment.isAdded) return
            GlassNotice.show(fragment.requireContext(), fragment.getString(R.string.rp_import_lore_ok, imported))
        } catch (_: Exception) {
            if (fragment.isAdded) GlassNotice.show(fragment.requireContext(), fragment.getString(R.string.rp_import_failed))
        }
    }

    /** Cards come as .png or .json, and some file providers label a .json as plain text or a blob. */
    fun pickCharacters() {
        charsLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/json", "image/png", "text/plain", "application/octet-stream")
            )
        })
    }

    /** Characters, Tavern cards and lorebook backups through one picker. */
    fun pickAny() {
        anyLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/json", "image/png", "text/plain", "application/octet-stream")
            )
        })
    }

    fun pickLorebooks() {
        loreLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        })
    }

    companion object {
        /** A lorebook backup is a JSON object with a `lorebooks` list; everything else goes to the character parser. */
        fun isLorebookBackup(bytes: ByteArray): Boolean {
            if (RpCharacterImport.isPng(bytes)) return false
            val root = try {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim())
            } catch (_: Exception) { return false }
            return root is kotlinx.serialization.json.JsonObject && "lorebooks" in root && "characters" !in root
        }
    }
}
