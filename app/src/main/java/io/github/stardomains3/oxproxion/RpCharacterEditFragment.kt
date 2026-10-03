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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val pickImage = AvatarPicker(this) { uri ->
        pendingAvatarUri = uri
        clearAvatar = false
        showAvatar(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A first Save may have created the row since the page opened, so the saved id wins over the argument.
        characterId = savedInstanceState?.getLong(KEY_ID, 0L)?.takeIf { it > 0 } ?: arguments?.getLong(ARG_ID) ?: 0
        // The text fields restore themselves; the picked photo is ours to bring back.
        pendingAvatarUri = savedInstanceState?.getString(KEY_PENDING_AVATAR)?.let(Uri::parse)
        clearAvatar = savedInstanceState?.getBoolean(KEY_CLEAR_AVATAR) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_ID, characterId)
        pendingAvatarUri?.let { outState.putString(KEY_PENDING_AVATAR, it.toString()) }
        outState.putBoolean(KEY_CLEAR_AVATAR, clearAvatar)
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
        showAvatar(pendingAvatarUri)
        nameInput.doAfterTextChanged { text ->
            currentName = text?.toString().orEmpty()
            avatarMonogram.text = RpAvatars.initial(currentName)
            syncPlaceholder()
        }

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
            saveButton.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val char = chatViewModel.getRpRepository().getCharacterById(characterId)
                    if (!isAdded) return@launch
                    if (char == null) {
                        GlassNotice.show(requireContext(), getString(R.string.rp_character_gone))
                        parentFragmentManager.popBackStack()
                        return@launch
                    }
                    val examples = rpDelegate.formatExamplesForEdit(char.examplesJson)
                    // After a rotation the fields already hold the user's edits; loading would overwrite them.
                    if (savedInstanceState == null) {
                        nameInput.setText(char.name)
                        greetingInput.setText(char.greeting)
                        personalityInput.setText(char.personality)
                        styleInput.setText(char.style)
                        scenarioInput.setText(char.scenario)
                        instructionInput.setText(char.instruction)
                        promptInput.setText(char.prompt)
                        examplesInput.setText(examples)
                    }
                    when {
                        clearAvatar -> showAvatar(null)
                        pendingAvatarUri != null -> showAvatar(pendingAvatarUri)
                        !char.photoUri.isNullOrBlank() -> showAvatar(char.photoUri)
                        RpAvatarStorage.hasAvatar(requireContext(), char.id) ->
                            showAvatar(RpAvatarStorage.avatarFile(requireContext(), char.id))
                    }
                    // The baseline is what is stored, so a restored edit still counts as one.
                    baseline = CharacterEditSnapshot(
                        name = char.name,
                        greeting = char.greeting,
                        personality = char.personality,
                        style = char.style,
                        scenario = char.scenario,
                        instruction = char.instruction,
                        prompt = char.prompt,
                        examples = examples
                    )
                } finally {
                    if (isAdded) saveButton.isEnabled = true
                }
            }
        }

        val avatarFrame = view.findViewById<android.widget.FrameLayout>(R.id.rpAvatarFrame)
        pickAvatarButton.setOnClickListener { pickImage.launch(avatarFrame) }
        avatarFrame.setOnClickListener { pickImage.launch(avatarFrame) }
        TouchTargets.expand(avatarFrame, view.findViewById(R.id.rpAvatarCameraBadge))
        clearAvatarButton.setOnClickListener {
            pendingAvatarUri = null
            clearAvatar = true
            showAvatar(null)
        }

        saveButton.setOnClickListener {
            if (!saveButton.isEnabled) return@setOnClickListener
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                GlassNotice.show(requireContext(), getString(R.string.rp_name_required))
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
                    val newGreeting = greetingInput.text?.toString().orEmpty()
                    // The bubble may be a rewrite. Only a change to the greeting text replaces that.
                    val greetingRefresh = existing?.let {
                        RpGreetingSync.Refresh(
                            expandedBefore = rpDelegate.greetingMessage(it),
                            templateChanged = RpGreetingSync.greetingTextChanged(it.greeting, newGreeting)
                        )
                    }
                    val examplesRaw = examplesInput.text?.toString().orEmpty()
                    val parsedExamples = rpDelegate.parseExamplesFromEdit(examplesRaw)
                    if (examplesRaw.isNotBlank() && parsedExamples.isEmpty()) {
                        if (isAdded) {
                            GlassNotice.show(requireContext(), getString(R.string.rp_examples_format_invalid))
                            saveButton.isEnabled = true
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
                            greeting = newGreeting,
                            personality = personalityInput.text?.toString().orEmpty(),
                            style = styleInput.text?.toString().orEmpty(),
                            scenario = scenarioInput.text?.toString().orEmpty(),
                            instruction = instructionInput.text?.toString().orEmpty(),
                            prompt = promptInput.text?.toString().orEmpty(),
                            examplesJson = examplesJson,
                            photoUri = photoUri
                        )
                    )
                    // The row exists now; a retry after a failed photo must update it, not insert a twin.
                    characterId = savedId
                    if (!isAdded) return@launch
                    // Delete file only after DB cleared the uri — avoid orphaning Room on failed save.
                    if (deleteAvatarAfterSave) {
                        RpAvatarStorage.deleteAvatar(requireContext(), savedId)
                    }
                    pendingAvatarUri?.let { uri ->
                        val appContext = requireContext().applicationContext
                        val saved = withContext(Dispatchers.IO) { RpAvatarStorage.saveFromUri(appContext, uri, savedId) }
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
                        chatViewModel.syncActiveCharacterGreetingIfIdle(greetingRefresh)
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
        private const val KEY_ID = "state_character_id"
        private const val KEY_PENDING_AVATAR = "state_pending_avatar"
        private const val KEY_CLEAR_AVATAR = "state_clear_avatar"
        fun newInstance(characterId: Long) = RpCharacterEditFragment().apply {
            arguments = Bundle().apply { putLong(ARG_ID, characterId) }
        }
    }
}
