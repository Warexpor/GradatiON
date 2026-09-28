package io.github.stardomains3.oxproxion

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class RpCharacterEditFragment : Fragment() {

    private val chatViewModel: ChatViewModel by activityViewModels { AppViewModelFactory(requireActivity().application) }
    private var characterId: Long = 0
    private var pendingAvatarUri: Uri? = null
    private var clearAvatar = false
    private lateinit var avatarPreview: ImageView
    private lateinit var avatarMonogram: TextView
    private lateinit var pickAvatarButton: MaterialButton
    private var currentName: String = ""
    private var hasPhoto = false
    private lateinit var avatarPlaceholder: View
    private lateinit var clearAvatarButton: MaterialButton
    private lateinit var rpDelegate: RpChatDelegate

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingAvatarUri = uri
        clearAvatar = false
        uri?.let { showAvatar(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        characterId = arguments?.getLong(ARG_ID) ?: 0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_rp_character_edit, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rpDelegate = RpChatDelegate(chatViewModel.getRpRepository(), SharedPreferencesHelper(requireContext()))
        avatarPreview = view.findViewById(R.id.rpAvatarPreview)
        avatarMonogram = view.findViewById(R.id.rpAvatarMonogram)
        avatarPlaceholder = view.findViewById(R.id.rpAvatarPlaceholder)
        pickAvatarButton = view.findViewById(R.id.pickAvatarButton)
        clearAvatarButton = view.findViewById(R.id.clearAvatarButton)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(
            if (characterId > 0) R.string.rp_edit_character else R.string.rp_add_character_title
        )

        val nameInput = view.findViewById<TextInputEditText>(R.id.rpNameInput)
        val greetingInput = view.findViewById<TextInputEditText>(R.id.rpGreetingInput)
        val personalityInput = view.findViewById<TextInputEditText>(R.id.rpPersonalityInput)
        val styleInput = view.findViewById<TextInputEditText>(R.id.rpStyleInput)
        val scenarioInput = view.findViewById<TextInputEditText>(R.id.rpScenarioInput)
        val instructionInput = view.findViewById<TextInputEditText>(R.id.rpInstructionInput)
        val promptInput = view.findViewById<TextInputEditText>(R.id.rpPromptInput)
        val examplesInput = view.findViewById<TextInputEditText>(R.id.rpExamplesInput)
        val saveButton = view.findViewById<MaterialButton>(R.id.saveRpCharacterButton)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.rpNameLayout)
        val examplesLayout = view.findViewById<TextInputLayout>(R.id.rpExamplesLayout)
        val advancedGroup = view.findViewById<View>(R.id.rpAdvancedGroup)
        val advancedHeader = view.findViewById<View>(R.id.rpAdvancedHeader)
        val advancedChevron = view.findViewById<ImageView>(R.id.rpAdvancedChevron)
        val textInputs = listOf(
            nameInput, personalityInput, scenarioInput, greetingInput, styleInput,
            instructionInput, examplesInput, promptInput
        )
        showAvatar(null)
        nameInput.doAfterTextChanged { text ->
            currentName = text?.toString().orEmpty()
            avatarMonogram.text = RpAvatars.initial(currentName)
            syncPlaceholder()
            nameLayout.error = null
        }
        examplesInput.doAfterTextChanged { examplesLayout.error = null }

        fun setAdvancedOpen(open: Boolean) {
            advancedGroup.visibility = if (open) View.VISIBLE else View.GONE
            advancedChevron.rotation = if (open) 180f else 0f
            advancedHeader.contentDescription = getString(
                if (open) R.string.rp_ui_advanced_collapse else R.string.rp_ui_advanced_expand
            )
        }
        setAdvancedOpen(false)
        advancedHeader.setOnClickListener { setAdvancedOpen(advancedGroup.visibility != View.VISIBLE) }

        var baseline = CharacterEditSnapshot()
        fun currentSnapshot() = CharacterEditSnapshot(
            name = nameInput.text?.toString().orEmpty(),
            greeting = greetingInput.text?.toString().orEmpty(),
            personality = personalityInput.text?.toString().orEmpty(),
            style = styleInput.text?.toString().orEmpty(),
            scenario = scenarioInput.text?.toString().orEmpty(),
            instruction = instructionInput.text?.toString().orEmpty(),
            prompt = promptInput.text?.toString().orEmpty(),
            examples = examplesInput.text?.toString().orEmpty(),
            avatarChanged = pendingAvatarUri != null || clearAvatar
        )
        fun navigateUp() {
            if (currentSnapshot() == baseline) {
                parentFragmentManager.popBackStack()
                return
            }
            GrokConfirmDialog.show(
                fragment = this,
                title = getString(R.string.rp_discard_edits_title),
                message = getString(R.string.rp_discard_edits_body),
                confirmText = getString(R.string.rp_discard_edits_confirm),
                onConfirm = { parentFragmentManager.popBackStack() },
                destructive = true
            )
        }
        toolbar.setNavigationOnClickListener { navigateUp() }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = navigateUp()
            }
        )

        if (characterId > 0) {
            // The fields fill in asynchronously. Locked until then, so typing can't be overwritten
            // and the baseline below is the loaded card, not the empty form.
            saveButton.isEnabled = false
            textInputs.forEach { it.isEnabled = false }
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val char = chatViewModel.getRpRepository().getCharacterById(characterId)
                    if (!isAdded) return@launch
                    if (char == null) {
                        GlassNotice.show(requireContext(), getString(R.string.rp_character_gone))
                        parentFragmentManager.popBackStack()
                        return@launch
                    }
                    nameInput.setText(char.name)
                    greetingInput.setText(char.greeting)
                    personalityInput.setText(char.personality)
                    styleInput.setText(char.style)
                    scenarioInput.setText(char.scenario)
                    instructionInput.setText(char.instruction)
                    promptInput.setText(char.prompt)
                    examplesInput.setText(rpDelegate.formatExamplesForEdit(char.examplesJson))
                    when {
                        !char.photoUri.isNullOrBlank() -> showAvatar(char.photoUri)
                        RpAvatarStorage.avatarFile(requireContext(), char.id).exists() ->
                            showAvatar(RpAvatarStorage.avatarFile(requireContext(), char.id))
                    }
                    baseline = currentSnapshot().copy(avatarChanged = false)
                    if (listOf(char.instruction, char.prompt).any { it.isNotBlank() } ||
                        examplesInput.text?.isNotBlank() == true
                    ) {
                        setAdvancedOpen(true)
                    }
                } finally {
                    if (isAdded) {
                        textInputs.forEach { it.isEnabled = true }
                        saveButton.isEnabled = true
                    }
                }
            }
        } else {
            baseline = currentSnapshot()
        }

        pickAvatarButton.setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        view.findViewById<View>(R.id.rpAvatarFrame).setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        clearAvatarButton.setOnClickListener {
            pendingAvatarUri = null
            clearAvatar = true
            showAvatar(null)
        }

        saveButton.setOnClickListener {
            if (!saveButton.isEnabled) return@setOnClickListener
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                nameLayout.error = getString(R.string.rp_name_required)
                nameInput.requestFocus()
                return@setOnClickListener
            }
            val examplesRaw = examplesInput.text?.toString().orEmpty()
            val parsedExamples = rpDelegate.parseExamplesFromEdit(examplesRaw)
            if (examplesRaw.isNotBlank() && parsedExamples.isEmpty()) {
                // The field lives in the folded Advanced section: open it so the error is visible.
                setAdvancedOpen(true)
                examplesLayout.error = getString(R.string.rp_examples_format_invalid)
                examplesInput.requestFocus()
                return@setOnClickListener
            }
            saveButton.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val repo = chatViewModel.getRpRepository()
                    val existing = if (characterId > 0) repo.getCharacterById(characterId) else null
                    if (characterId > 0 && existing == null) {
                        if (isAdded) {
                            GlassNotice.show(requireContext(), getString(R.string.rp_character_gone))
                            parentFragmentManager.popBackStack()
                        }
                        return@launch
                    }
                    val examplesJson = rpDelegate.encodeExamples(parsedExamples)
                    var photoUri = existing?.photoUri
                    val deleteAvatarAfterSave = clearAvatar && characterId > 0
                    if (clearAvatar) {
                        photoUri = null
                    }
                    val savedId = repo.saveCharacter(
                        (existing ?: RpCharacter(name = name)).copy(
                            name = name,
                            greeting = greetingInput.text?.toString().orEmpty(),
                            personality = personalityInput.text?.toString().orEmpty(),
                            style = styleInput.text?.toString().orEmpty(),
                            scenario = scenarioInput.text?.toString().orEmpty(),
                            instruction = instructionInput.text?.toString().orEmpty(),
                            prompt = promptInput.text?.toString().orEmpty(),
                            examplesJson = examplesJson,
                            photoUri = photoUri
                        )
                    )
                    if (!isAdded) return@launch
                    // Delete file only after DB cleared the uri — avoid orphaning Room on failed save.
                    if (deleteAvatarAfterSave) {
                        RpAvatarStorage.deleteAvatar(requireContext(), savedId)
                    }
                    pendingAvatarUri?.let { uri ->
                        val saved = RpAvatarStorage.saveFromUri(requireContext(), uri, savedId)
                        if (saved != null) {
                            repo.getCharacterById(savedId)?.let { c ->
                                repo.saveCharacter(c.copy(photoUri = saved))
                            }
                        } else if (isAdded) {
                            GlassNotice.show(requireContext(), getString(R.string.rp_avatar_save_failed))
                            saveButton.isEnabled = true
                            return@launch
                        }
                    }
                    if (chatViewModel.isRpMode() &&
                        SharedPreferencesHelper(requireContext()).getRpActiveCharacterId() == savedId
                    ) {
                        chatViewModel.refreshActiveRpCharacter()
                        chatViewModel.syncActiveCharacterGreetingIfIdle()
                    }
                    parentFragmentManager.popBackStack()
                } catch (_: Exception) {
                    if (isAdded) {
                        saveButton.isEnabled = true
                        GlassNotice.show(requireContext(), getString(R.string.rp_save_failed))
                    }
                }
            }
        }
    }

    /** Person glyph only while there is neither a photo nor a name to draw a monogram from. */
    private fun syncPlaceholder() {
        avatarPlaceholder.visibility =
            if (!hasPhoto && RpAvatars.initial(currentName).isEmpty()) View.VISIBLE else View.GONE
    }

    /** Photo when [model] is set, otherwise the name's monogram on neutral gray. */
    private fun showAvatar(model: Any?) {
        RpAvatars.bindModel(avatarPreview, avatarMonogram, model, currentName)
        hasPhoto = model != null
        syncPlaceholder()
        clearAvatarButton.visibility = if (model != null) View.VISIBLE else View.GONE
        pickAvatarButton.setText(if (model != null) R.string.rp_ui_change_photo else R.string.rp_ui_add_photo)
    }

    private data class CharacterEditSnapshot(
        val name: String = "",
        val greeting: String = "",
        val personality: String = "",
        val style: String = "",
        val scenario: String = "",
        val instruction: String = "",
        val prompt: String = "",
        val examples: String = "",
        val avatarChanged: Boolean = false
    )

    companion object {
        private const val ARG_ID = "character_id"
        fun newInstance(characterId: Long) = RpCharacterEditFragment().apply {
            arguments = Bundle().apply { putLong(ARG_ID, characterId) }
        }
    }
}
