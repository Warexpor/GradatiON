package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class RpHubFragment : Fragment() {

    private val chatViewModel: ChatViewModel by activityViewModels { AppViewModelFactory(requireActivity().application) }
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var prefs: SharedPreferencesHelper
    private var characters: List<RpCharacter> = emptyList()
    private var lorebooks: List<RpLorebook> = emptyList()

    private val exportCharsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireContext().applicationContext
            try {
                withContext(Dispatchers.IO) {
                    val repo = chatViewModel.getRpRepository()
                    val chars = repo.getAllCharactersOnce()
                    val loreNameById = repo.getAllLorebooksOnce().associate { it.id to it.name }
                    val sidePrefs = SharedPreferencesHelper(app)
                    // Several portraits and wallpapers at their own caps used to make a file
                    // an import will not read. Shrink until the library fits.
                    val exports = RpCharacterBackupFit.withinImportCap { portraitCap, wallpaperCap ->
                        chars.map { c ->
                            val voice = sidePrefs.getRpVoice(c.id)
                            val loreName = sidePrefs.getRpLorebookId(c.id)?.let { loreNameById[it] }
                                ?: sidePrefs.getPendingRpLorebookName(c.id)
                                ?: ""
                            RpCharacterExport(
                                name = c.name.trim(),
                                personality = c.personality,
                                style = c.style,
                                greeting = c.greeting,
                                scenario = c.scenario,
                                examplesJson = c.examplesJson,
                                prompt = c.prompt,
                                instruction = c.instruction,
                                exportKey = c.exportKey,
                                avatarBase64 = RpAvatarStorage.encodeAvatarBase64(app, c.id, portraitCap),
                                photoUri = null,
                                memory = sidePrefs.getRpMemory(c.id),
                                layout = sidePrefs.getRpLayout(c.id),
                                voiceName = voice.name,
                                voicePitch = voice.pitch,
                                voiceRate = voice.rate,
                                lorebookName = loreName,
                                wallpaperBase64 = RpWallpaperBackup.encode(
                                    BackgroundPhoto.file(app, BackgroundPhoto.slotForCharacter(c.id)),
                                    wallpaperCap,
                                ),
                            )
                        }
                    }
                    val cache = File(app.cacheDir, "rp-chars-${System.nanoTime()}.json")
                    BackupIo.publish(cache, { app.contentResolver.openOutputStream(uri, "wt") }) { stream ->
                        stream.writer(Charsets.UTF_8).buffered().use {
                            RpBackupWriter.writeCharacters(it, exports)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isAdded) GlassNotice.show(requireContext(), getString(R.string.rp_export_failed))
            }
        }
    }

    private val importCharsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireContext().applicationContext
            try {
                val bytes = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use {
                        ImportBounds.readBytes(it, ImportBounds.MAX_RP_BYTES)
                    }
                } ?: run {
                    GlassNotice.show(requireContext(), getString(R.string.rp_import_failed))
                    return@launch
                }
                // A SillyTavern or Chub card, as a PNG or as JSON, imports as a one-character backup.
                val text = if (RpCardImport.isPng(bytes)) null else ImportBounds.decode(bytes)
                val card = withContext(Dispatchers.Default) {
                    if (text == null) RpCardImport.fromPng(bytes) else RpCardImport.fromJson(text)
                }
                if (text == null && card == null) {
                    GlassNotice.show(requireContext(), getString(R.string.rp_import_not_a_card))
                    return@launch
                }
                cardLorebook = card?.lorebook
                val parsed = if (card != null) RpCharacterBackup(listOf(card.character))
                    else json.decodeFromString(RpCharacterBackup.serializer(), text!!)
                // A blank name is not a character. Counting it asked to update a card import then skipped.
                val backup = parsed.copy(
                    characters = parsed.characters.filter { it.name.trim().isNotEmpty() },
                )
                if (backup.characters.isEmpty()) {
                    GlassNotice.show(requireContext(), getString(R.string.rp_import_empty))
                    return@launch
                }
                val repo = chatViewModel.getRpRepository()
                val existing = repo.getAllCharactersOnce()
                val existingKeys = existing.map { it.exportKey }.filter { it.isNotBlank() }.toSet()
                val overwrite = RpImportRules.characterOverwriteCount(
                    backup.characters,
                    existingKeys,
                    existing.map { it.name to it.exportKey },
                )
                val total = RpImportRules.characterFileCount(backup.characters)
                if (overwrite > 0) {
                    GrokConfirmDialog.show(
                        fragment = this@RpHubFragment,
                        title = getString(R.string.rp_import_overwrite_title),
                        message = getString(R.string.rp_import_overwrite_chars, overwrite, total),
                        confirmText = getString(R.string.rp_import_confirm),
                        onConfirm = {
                            viewLifecycleOwner.lifecycleScope.launch { applyCharacterBackup(backup) }
                        },
                        destructive = false
                    )
                } else {
                    applyCharacterBackup(backup)
                }
            } catch (e: ImportBounds.TooLarge) {
                if (isAdded) {
                    GlassNotice.show(
                        requireContext(),
                        getString(R.string.import_error_too_large, e.limitBytes / (1024 * 1024))
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isAdded) GlassNotice.show(requireContext(), getString(R.string.rp_import_failed))
            }
        }
    }

    private val exportLoreLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireContext().applicationContext
            try {
                withContext(Dispatchers.IO) {
                    val books = chatViewModel.getRpRepository().getAllLorebooksOnce()
                    val exports = RpLoreBackup.exports(books)
                    val cache = File(app.cacheDir, "rp-lore-${System.nanoTime()}.json")
                    BackupIo.publish(cache, { app.contentResolver.openOutputStream(uri, "wt") }) { stream ->
                        stream.writer(Charsets.UTF_8).buffered().use {
                            RpBackupWriter.writeLorebooks(it, exports)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isAdded) GlassNotice.show(requireContext(), getString(R.string.rp_export_failed))
            }
        }
    }

    private val importLoreLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireContext().applicationContext
            try {
                val text = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use {
                        ImportBounds.readUtf8(it, ImportBounds.MAX_RP_BYTES)
                    }
                } ?: run {
                    GlassNotice.show(requireContext(), getString(R.string.rp_import_failed))
                    return@launch
                }
                val backup = json.decodeFromString(RpLorebookBackup.serializer(), text)
                // A blank name is not a book. A file of only those used to say the import worked.
                if (RpImportRules.loreFileCount(backup.lorebooks) == 0) {
                    GlassNotice.show(requireContext(), getString(R.string.rp_import_empty))
                    return@launch
                }
                val repo = chatViewModel.getRpRepository()
                val existingNames = repo.getAllLorebooksOnce().map { it.name }
                val overwrite = RpImportRules.loreOverwriteCount(backup.lorebooks, existingNames)
                val total = RpImportRules.loreFileCount(backup.lorebooks)
                if (overwrite > 0) {
                    GrokConfirmDialog.show(
                        fragment = this@RpHubFragment,
                        title = getString(R.string.rp_import_overwrite_title),
                        message = getString(R.string.rp_import_overwrite_lore, overwrite, total),
                        confirmText = getString(R.string.rp_import_confirm),
                        onConfirm = {
                            viewLifecycleOwner.lifecycleScope.launch { applyLoreBackup(backup) }
                        },
                        destructive = false
                    )
                } else {
                    applyLoreBackup(backup)
                }
            } catch (e: ImportBounds.TooLarge) {
                if (isAdded) {
                    GlassNotice.show(
                        requireContext(),
                        getString(R.string.import_error_too_large, e.limitBytes / (1024 * 1024))
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isAdded) GlassNotice.show(requireContext(), getString(R.string.rp_import_failed))
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
        prefs = SharedPreferencesHelper(requireContext())
        setupRow(view, R.id.rpHubCharactersRow, R.drawable.rp_ic_characters, R.string.rp_characters_title) {
            push(RpCharacterLibraryFragment.newInstance())
        }
        setupRow(view, R.id.rpHubPersonaRow, R.drawable.rp_ic_persona, R.string.rp_persona_title) {
            push(RpPersonaFragment.newInstance())
        }
        setupRow(view, R.id.rpHubLorebooksRow, R.drawable.rp_ic_book, R.string.rp_lorebooks_title) {
            push(RpLorebookLibraryFragment.newInstance())
        }
        setupRow(view, R.id.rpHubSettingsRow, R.drawable.ic_sliders, R.string.rp_settings_title) {
            push(RpSettingsFragment.newInstance())
        }
        val backupOptions = view.findViewById<View>(R.id.rpHubBackupOptions)
        val backupRow = view.findViewById<View>(R.id.rpHubBackupRow)
        val backupChevron = backupRow.findViewById<ImageView>(R.id.rpHubRowChevron)
        backupChevron.rotation = 90f
        backupRow.findViewById<TextView>(R.id.rpHubRowValue).setText(R.string.rp_ui_backup_value)
        setupRow(view, R.id.rpHubBackupRow, R.drawable.rp_ic_archive, R.string.rp_ui_backup_title) {
            val open = backupOptions.visibility != View.VISIBLE
            backupOptions.visibility = if (open) View.VISIBLE else View.GONE
            backupChevron.animate().rotation(if (open) -90f else 90f).setDuration(220)
                .setInterpolator(Motion.iosOut).start()
            backupRow.contentDescription = getString(if (open) R.string.rp_ui_collapse else R.string.rp_ui_expand)
        }
        backupRow.contentDescription = getString(R.string.rp_ui_expand)

        val repo = chatViewModel.getRpRepository()
        repo.allCharacters.observe(viewLifecycleOwner) { list ->
            characters = list.orEmpty()
            render()
        }
        repo.allLorebooks.observe(viewLifecycleOwner) { list ->
            lorebooks = list.orEmpty()
            render()
        }
        view.findViewById<MaterialButton>(R.id.rpHubExportCharsButton).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val chars = chatViewModel.getRpRepository().getAllCharactersOnce()
                if (chars.isEmpty()) {
                    GlassNotice.show(requireContext(), getString(R.string.rp_export_empty_chars))
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
                // App backups, and character cards as PNG or JSON. Some file managers label
                // a card's .json as plain text or a binary stream.
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/json", "image/png", "text/plain", "application/octet-stream")
                )
            })
        }
        view.findViewById<MaterialButton>(R.id.rpHubExportLoreButton).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val books = chatViewModel.getRpRepository().getAllLorebooksOnce()
                if (books.isEmpty()) {
                    GlassNotice.show(requireContext(), getString(R.string.rp_export_empty_lore))
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

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // Back from a pushed screen: the active character, persona or lorebook may have changed.
        if (!hidden) render()
    }

    private fun setupRow(root: View, rowId: Int, iconRes: Int, titleRes: Int, onClick: () -> Unit) {
        val row = root.findViewById<View>(rowId)
        row.findViewById<ImageView>(R.id.rpHubRowIcon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.rpHubRowTitle).setText(titleRes)
        row.setOnClickListener { onClick() }
    }

    private fun setRowValue(rowId: Int, value: String) {
        view?.findViewById<View>(rowId)?.findViewById<TextView>(R.id.rpHubRowValue)?.text = value
    }

    private fun render() {
        val root = view ?: return
        if (!isAdded) return
        setRowValue(R.id.rpHubCharactersRow, if (characters.isEmpty()) "" else characters.size.toString())
        setRowValue(
            R.id.rpHubPersonaRow,
            getString(
                when {
                    prefs.getRpPersona().isBlank() && prefs.getRpPersonaName().isBlank() -> R.string.rp_ui_persona_unset
                    !prefs.isRpPersonaEnabled() -> R.string.rp_ui_persona_off
                    else -> R.string.rp_ui_persona_set
                }
            )
        )
        setRowValue(
            R.id.rpHubLorebooksRow,
            lorebooks.firstOrNull { it.isActive }?.name
                ?: if (lorebooks.isEmpty()) "" else getString(R.string.rp_ui_lore_none)
        )

        val avatar = root.findViewById<ImageView>(R.id.rpHubHeroAvatar)
        val monogram = root.findViewById<TextView>(R.id.rpHubHeroMonogram)
        val glyph = root.findViewById<ImageView>(R.id.rpHubHeroGlyph)
        val label = root.findViewById<TextView>(R.id.rpHubHeroLabel)
        val name = root.findViewById<TextView>(R.id.rpHubHeroName)
        val tagline = root.findViewById<TextView>(R.id.rpHubHeroTagline)
        val action = root.findViewById<MaterialButton>(R.id.rpHubContinueButton)

        val activeId = prefs.getRpActiveCharacterId()
        val active = characters.firstOrNull { it.id == activeId }
        val llm = prefs.isRpLlmMode()
        fun showGlyph(res: Int) {
            RpAvatars.bindModel(avatar, monogram, null, "")
            monogram.visibility = View.GONE
            glyph.setImageResource(res)
            glyph.visibility = View.VISIBLE
        }
        when {
            llm -> {
                showGlyph(R.drawable.ic_gradation_mark)
                label.visibility = View.VISIBLE
                name.setText(R.string.rp_ui_hub_llm_title)
                tagline.setText(R.string.rp_ui_hub_llm_body)
                action.setText(R.string.rp_ui_continue)
                action.setOnClickListener { continueChat() }
            }
            active != null -> {
                glyph.visibility = View.GONE
                RpAvatars.bind(avatar, monogram, active)
                label.visibility = View.VISIBLE
                name.text = active.name
                // Same first line as the character list. Folding the whole field here left
                // {{char}} in place and turned snake_case and C# into other words.
                tagline.text = RpChatSummaries.tagline(
                    active,
                    prefs.activeRpPersonaName().ifBlank { getString(R.string.rp_you) },
                )
                tagline.visibility = if (tagline.text.isNullOrBlank()) View.GONE else View.VISIBLE
                action.setText(R.string.rp_ui_continue)
                action.setOnClickListener { continueChat() }
            }
            else -> {
                showGlyph(R.drawable.rp_ic_characters)
                label.visibility = View.GONE
                name.setText(R.string.rp_ui_hub_none_title)
                tagline.setText(R.string.rp_ui_hub_none_body)
                tagline.visibility = View.VISIBLE
                action.setText(R.string.rp_ui_browse)
                action.setOnClickListener { push(RpCharacterLibraryFragment.newInstance()) }
            }
        }
        if (llm) tagline.visibility = View.VISIBLE
    }

    /** Back to the chat in RP mode, resuming the parked RP thread. */
    private fun continueChat() {
        chatViewModel.setChatMode(ChatMode.RP)
        val fm = parentFragmentManager
        fm.popBackStackImmediate(BACK_STACK_TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        fm.popBackStackImmediate("settings", FragmentManager.POP_BACK_STACK_INCLUSIVE)
        // Leave Code if it was covering chat — setChatMode alone does not.
        fm.fragments.filterIsInstance<ChatFragment>().firstOrNull()?.uncoverFromHub()
    }

    private fun push(target: Fragment) {
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .hide(this)
            .add(R.id.fragment_container, target)
            .addToBackStack(null)
            .commit()
    }

    /** The book of the card being imported, written just before the character so its pin binds. */
    private var cardLorebook: RpLorebookExport? = null

    private suspend fun applyCharacterBackup(backup: RpCharacterBackup) {
        if (!isAdded) return
        try {
            val repo = chatViewModel.getRpRepository()
            cardLorebook?.let {
                repo.importLorebooks(listOf(it), activateFirstIfNone = false)
                RpCharacterPrefsBackup.bindPending(prefs, repo.getAllLorebooksOnce())
            }
            cardLorebook = null
            val activeBefore = prefs.getRpActiveCharacterId()?.let { repo.getCharacterById(it) }
            val delegate = RpChatDelegate(repo, prefs)
            val expandedBefore = activeBefore?.let { delegate.greetingMessage(it) }
            val library = if (activeBefore != null) repo.getAllCharactersOnce() else emptyList()
            val greetingChanged = activeBefore != null && RpImportRules.greetingChanged(
                activeBefore.greeting,
                activeBefore.exportKey,
                backup.characters,
                characterName = activeBefore.name,
                namedKeysNewestFirst = library.map { it.name to it.exportKey },
            )
            val app = requireContext().applicationContext
            val carried = CharacterImportSideLog.read(CharacterImportSideLog.file(app)).orEmpty()
            // The side file is written before the transaction commits. A kill after the rows
            // land is finished on the next launch, including the portraits.
            val imported = repo.importCharacters(backup.characters) { rows ->
                val fresh = CharacterImportSideLog.notesFor(rows, backup.characters)
                val freshIds = fresh.map { it.id }.toSet()
                CharacterImportSideLog.write(
                    CharacterImportSideLog.file(app),
                    carried.filter { it.id !in freshIds } + fresh,
                )
            }
            val db = AppDatabase.getDatabase(app)
            if (!CharacterImportSideLog.resume(app, db)) {
                android.util.Log.e("RpHub", "Imported character notes could not be saved; will retry next launch")
            }
            imported.forEach { row ->
                if (row.isNew && row.exportKey.isNotBlank()) {
                    val oldId = prefs.takeDeletedRpCharacterId(row.exportKey)
                    if (oldId != null && oldId != row.id) {
                        chatViewModel.remappingCharacterSessions(oldId, row.id)
                    }
                }
            }
            chatViewModel.refreshActiveRpCharacter()
            val refresh = expandedBefore?.let { RpGreetingSync.Refresh(it, greetingChanged) }
            chatViewModel.syncActiveCharacterGreetingIfIdle(refresh)
            if (!isAdded) return
            GlassNotice.show(
                requireContext(),
                getString(R.string.rp_import_chars_ok, RpImportRules.importedCharacterCount(imported)),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (isAdded) {
                GlassNotice.show(requireContext(), getString(R.string.rp_import_failed))
            }
        }
    }

    private suspend fun applyLoreBackup(backup: RpLorebookBackup) {
        if (!isAdded) return
        try {
            val repo = chatViewModel.getRpRepository()
            val imported = repo.importLorebooks(backup.lorebooks)
            RpCharacterPrefsBackup.bindPending(prefs, repo.getAllLorebooksOnce())
            if (!isAdded) return
            val prefs = SharedPreferencesHelper(requireContext())
            val loreHint = if (!prefs.isRpLoreEnabled() && repo.getActiveLorebook() != null) {
                "\n" + getString(R.string.rp_lore_activated_disabled)
            } else {
                ""
            }
            GlassNotice.show(requireContext(), getString(R.string.rp_import_lore_ok, imported) + loreHint)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (isAdded) {
                GlassNotice.show(requireContext(), getString(R.string.rp_import_failed))
            }
        }
    }

    companion object {
        const val BACK_STACK_TAG = "rp_hub"
        fun newInstance() = RpHubFragment()
    }
}
