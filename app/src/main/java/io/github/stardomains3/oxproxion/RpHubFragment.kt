package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class RpHubFragment : Fragment() {

    private val chatViewModel: ChatViewModel by activityViewModels()
    private val json = Json { ignoreUnknownKeys = true }

    private val exportCharsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val chars = chatViewModel.getRpRepository().getAllCharactersOnce()
                val backup = RpCharacterBackup(chars.map { c ->
                    RpCharacterExport(
                        name = c.name,
                        personality = c.personality,
                        style = c.style,
                        greeting = c.greeting,
                        scenario = c.scenario,
                        examplesJson = c.examplesJson,
                        prompt = c.prompt,
                        instruction = c.instruction,
                        exportKey = c.exportKey,
                        avatarBase64 = RpAvatarStorage.encodeAvatarBase64(requireContext(), c.id),
                        photoUri = null
                    )
                })
                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.encodeToString(RpCharacterBackup.serializer(), backup).toByteArray())
                } ?: run {
                    AppToast.makeText(requireContext(), getString(R.string.rp_export_failed), AppToast.LENGTH_SHORT).show()
                    return@launch
                }
                AppToast.makeText(requireContext(), getString(R.string.rp_export_ok), AppToast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                AppToast.makeText(requireContext(), getString(R.string.rp_export_failed), AppToast.LENGTH_SHORT).show()
            }
        }
    }

    private val importCharsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val text = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    ?: run {
                        AppToast.makeText(requireContext(), getString(R.string.rp_import_failed), AppToast.LENGTH_SHORT).show()
                        return@launch
                    }
                val backup = json.decodeFromString(RpCharacterBackup.serializer(), text)
                if (backup.characters.isEmpty()) {
                    AppToast.makeText(requireContext(), getString(R.string.rp_import_empty), AppToast.LENGTH_SHORT).show()
                    return@launch
                }
                val repo = chatViewModel.getRpRepository()
                val existingKeys = repo.getAllCharactersOnce()
                    .map { it.exportKey }
                    .filter { it.isNotBlank() }
                    .toSet()
                val overwrite = RpImportRules.characterOverwriteCount(backup.characters, existingKeys)
                if (overwrite > 0) {
                    GrokConfirmDialog.show(
                        fragment = this@RpHubFragment,
                        title = getString(R.string.rp_import_overwrite_title),
                        message = getString(R.string.rp_import_overwrite_chars, overwrite, backup.characters.size),
                        confirmText = getString(R.string.rp_import_confirm),
                        onConfirm = {
                            viewLifecycleOwner.lifecycleScope.launch { applyCharacterBackup(backup) }
                        },
                        destructive = false
                    )
                } else {
                    applyCharacterBackup(backup)
                }
            } catch (_: Exception) {
                AppToast.makeText(requireContext(), getString(R.string.rp_import_failed), AppToast.LENGTH_SHORT).show()
            }
        }
    }

    private val exportLoreLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val books = chatViewModel.getRpRepository().getAllLorebooksOnce()
                val backup = RpLorebookBackup(books.map { b ->
                    RpLorebookExport(name = b.name, content = b.content, isActive = b.isActive)
                })
                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.encodeToString(RpLorebookBackup.serializer(), backup).toByteArray())
                } ?: run {
                    AppToast.makeText(requireContext(), getString(R.string.rp_export_failed), AppToast.LENGTH_SHORT).show()
                    return@launch
                }
                AppToast.makeText(requireContext(), getString(R.string.rp_export_ok), AppToast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                AppToast.makeText(requireContext(), getString(R.string.rp_export_failed), AppToast.LENGTH_SHORT).show()
            }
        }
    }

    private val importLoreLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val text = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    ?: run {
                        AppToast.makeText(requireContext(), getString(R.string.rp_import_failed), AppToast.LENGTH_SHORT).show()
                        return@launch
                    }
                val backup = json.decodeFromString(RpLorebookBackup.serializer(), text)
                if (backup.lorebooks.isEmpty()) {
                    AppToast.makeText(requireContext(), getString(R.string.rp_import_empty), AppToast.LENGTH_SHORT).show()
                    return@launch
                }
                val repo = chatViewModel.getRpRepository()
                val existingNames = repo.getAllLorebooksOnce().map { it.name }
                val overwrite = RpImportRules.loreOverwriteCount(backup.lorebooks, existingNames)
                if (overwrite > 0) {
                    GrokConfirmDialog.show(
                        fragment = this@RpHubFragment,
                        title = getString(R.string.rp_import_overwrite_title),
                        message = getString(R.string.rp_import_overwrite_lore, overwrite, backup.lorebooks.size),
                        confirmText = getString(R.string.rp_import_confirm),
                        onConfirm = {
                            viewLifecycleOwner.lifecycleScope.launch { applyLoreBackup(backup) }
                        },
                        destructive = false
                    )
                } else {
                    applyLoreBackup(backup)
                }
            } catch (_: Exception) {
                AppToast.makeText(requireContext(), getString(R.string.rp_import_failed), AppToast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_rp_hub, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        open(R.id.rpHubCharactersButton, RpCharacterLibraryFragment.newInstance())
        open(R.id.rpHubPersonaButton, RpPersonaFragment.newInstance())
        open(R.id.rpHubLorebooksButton, RpLorebookLibraryFragment.newInstance())
        open(R.id.rpHubSettingsButton, RpSettingsFragment.newInstance())
        view.findViewById<MaterialButton>(R.id.rpHubExportCharsButton).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val chars = chatViewModel.getRpRepository().getAllCharactersOnce()
                if (chars.isEmpty()) {
                    AppToast.makeText(requireContext(), getString(R.string.rp_export_empty_chars), AppToast.LENGTH_SHORT).show()
                    return@launch
                }
                exportCharsLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, getString(R.string.rp_export_chars_filename))
                })
            }
        }
        view.findViewById<MaterialButton>(R.id.rpHubImportCharsButton).setOnClickListener {
            importCharsLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            })
        }
        view.findViewById<MaterialButton>(R.id.rpHubExportLoreButton).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val books = chatViewModel.getRpRepository().getAllLorebooksOnce()
                if (books.isEmpty()) {
                    AppToast.makeText(requireContext(), getString(R.string.rp_export_empty_lore), AppToast.LENGTH_SHORT).show()
                    return@launch
                }
                exportLoreLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, getString(R.string.rp_export_lore_filename))
                })
            }
        }
        view.findViewById<MaterialButton>(R.id.rpHubImportLoreButton).setOnClickListener {
            importLoreLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            })
        }
    }

    private fun open(buttonId: Int, target: Fragment) {
        view?.findViewById<MaterialButton>(buttonId)?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, target)
                .addToBackStack(null)
                .commit()
        }
    }

    private suspend fun applyCharacterBackup(backup: RpCharacterBackup) {
        if (!isAdded) return
        try {
            val repo = chatViewModel.getRpRepository()
            var imported = 0
            backup.characters.forEach { ex ->
                val existing = ex.exportKey.takeIf { it.isNotBlank() }?.let { key ->
                    repo.getCharacterByExportKey(key)
                }
                val base = existing ?: RpCharacter(
                    name = ex.name,
                    exportKey = ex.exportKey.ifBlank { java.util.UUID.randomUUID().toString() }
                )
                val savedId = repo.saveCharacter(
                    base.copy(
                        name = ex.name,
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
                if (existing == null && base.exportKey.isNotBlank()) {
                    val oldId = SharedPreferencesHelper(requireContext()).takeDeletedRpCharacterId(base.exportKey)
                    if (oldId != null && oldId != savedId) {
                        chatViewModel.remappingCharacterSessions(oldId, savedId)
                    }
                }
                val photoUri = when {
                    !ex.avatarBase64.isNullOrBlank() ->
                        RpAvatarStorage.saveFromBase64(requireContext(), ex.avatarBase64, savedId)
                    else -> null
                }
                if (photoUri != null) {
                    repo.getCharacterById(savedId)?.let { c ->
                        repo.saveCharacter(c.copy(photoUri = photoUri))
                    }
                }
                imported++
            }
            chatViewModel.refreshActiveRpCharacter()
            chatViewModel.syncActiveCharacterGreetingIfIdle()
            if (!isAdded) return
            AppToast.makeText(
                requireContext(),
                getString(R.string.rp_import_chars_ok, imported),
                AppToast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {
            if (isAdded) {
                AppToast.makeText(requireContext(), getString(R.string.rp_import_failed), AppToast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun applyLoreBackup(backup: RpLorebookBackup) {
        if (!isAdded) return
        try {
            val repo = chatViewModel.getRpRepository()
            val hadBooksBefore = repo.getAllLorebooksOnce().isNotEmpty()
            var imported = 0
            backup.lorebooks.forEach { ex ->
                val existing = repo.getAllLorebooksOnce()
                    .firstOrNull { it.name.equals(ex.name, ignoreCase = true) }
                val id = repo.saveLorebook(
                    (existing ?: RpLorebook(name = ex.name)).copy(
                        name = ex.name,
                        content = ex.content
                    )
                )
                if (ex.isActive) repo.setActiveLorebook(id)
                imported++
            }
            if (!hadBooksBefore && repo.getActiveLorebook() == null) {
                repo.getAllLorebooksOnce().firstOrNull()?.let { repo.setActiveLorebook(it.id) }
            }
            if (!isAdded) return
            val prefs = SharedPreferencesHelper(requireContext())
            val loreHint = if (!prefs.isRpLoreEnabled() && repo.getActiveLorebook() != null) {
                "\n" + getString(R.string.rp_lore_activated_disabled)
            } else {
                ""
            }
            AppToast.makeText(
                requireContext(),
                getString(R.string.rp_import_lore_ok, imported) + loreHint,
                AppToast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            if (isAdded) {
                AppToast.makeText(requireContext(), getString(R.string.rp_import_failed), AppToast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val BACK_STACK_TAG = "rp_hub"
        fun newInstance() = RpHubFragment()
    }
}
