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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class RpHubFragment : Fragment() {

    private val chatViewModel: ChatViewModel by activityViewModels { AppViewModelFactory(requireActivity().application) }
    private val json = Json { ignoreUnknownKeys = true }
    private val importer = RpImportFlow(this) { chatViewModel }
    private lateinit var prefs: SharedPreferencesHelper
    private var characters: List<RpCharacter> = emptyList()
    private var lorebooks: List<RpLorebook> = emptyList()

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
                    GlassNotice.show(requireContext(), getString(R.string.rp_export_failed))
                    return@launch
                }
                GlassNotice.show(requireContext(), getString(R.string.rp_export_ok))
            } catch (_: Exception) {
                GlassNotice.show(requireContext(), getString(R.string.rp_export_failed))
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
                    GlassNotice.show(requireContext(), getString(R.string.rp_export_failed))
                    return@launch
                }
                GlassNotice.show(requireContext(), getString(R.string.rp_export_ok))
            } catch (_: Exception) {
                GlassNotice.show(requireContext(), getString(R.string.rp_export_failed))
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
        viewLifecycleOwner.lifecycleScope.launch { repo.retireLoreSwitch(prefs) }
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
            importer.pickCharacters()
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
            importer.pickLorebooks()
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
            prefs.getRpPersonaName().ifBlank {
                getString(if (prefs.getRpPersona().isBlank()) R.string.rp_ui_persona_unset else R.string.rp_ui_persona_set)
            }
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
                tagline.text = listOf(active.personality, active.scenario, active.greeting)
                    .firstOrNull { it.isNotBlank() }.orEmpty()
                    .replace(Regex("[*_#>`]"), "").replace(Regex("\\s+"), " ").trim()
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
        fm.fragments.filterIsInstance<ChatFragment>().firstOrNull()?.let {
            it.closeHistoryPanel(animated = false)
            it.closeRpHome()
        }
    }

    private fun push(target: Fragment) {
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .hide(this)
            .add(R.id.fragment_container, target)
            .addToBackStack(null)
            .commit()
    }

    companion object {
        const val BACK_STACK_TAG = "rp_hub"
        fun newInstance() = RpHubFragment()
    }
}
