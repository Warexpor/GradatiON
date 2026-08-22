package io.github.stardomains3.oxproxion

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class RpCharacterEditFragment : Fragment() {

    private val chatViewModel: ChatViewModel by activityViewModels()
    private var characterId: Long = 0
    private var pendingAvatarUri: Uri? = null
    private var clearAvatar = false
    private lateinit var avatarPreview: ImageView
    private lateinit var clearAvatarButton: MaterialButton
    private lateinit var rpDelegate: RpChatDelegate

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingAvatarUri = uri
        clearAvatar = false
        uri?.let {
            avatarPreview.visibility = View.VISIBLE
            clearAvatarButton.visibility = View.VISIBLE
            avatarPreview.load(it) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        }
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
                        AppToast.makeText(
                            requireContext(),
                            getString(R.string.rp_character_gone),
                            AppToast.LENGTH_SHORT
                        ).show()
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
                } finally {
                    if (isAdded) saveButton.isEnabled = true
                }
            }
        } else {
            baseline = currentSnapshot()
        }

        view.findViewById<MaterialButton>(R.id.pickAvatarButton).setOnClickListener {
            pickImage.launch(arrayOf("image/*"))
        }
        clearAvatarButton.setOnClickListener {
            pendingAvatarUri = null
            clearAvatar = true
            avatarPreview.setImageResource(R.drawable.ic_gradation_mark)
            avatarPreview.visibility = View.VISIBLE
            clearAvatarButton.visibility = View.GONE
        }

        saveButton.setOnClickListener {
            if (!saveButton.isEnabled) return@setOnClickListener
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                AppToast.makeText(requireContext(), getString(R.string.rp_name_required), AppToast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveButton.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val repo = chatViewModel.getRpRepository()
                    val existing = if (characterId > 0) repo.getCharacterById(characterId) else null
                    if (characterId > 0 && existing == null) {
                        if (isAdded) {
                            AppToast.makeText(
                                requireContext(),
                                getString(R.string.rp_character_gone),
                                AppToast.LENGTH_SHORT
                            ).show()
                            parentFragmentManager.popBackStack()
                        }
                        return@launch
                    }
                    val examplesRaw = examplesInput.text?.toString().orEmpty()
                    val parsedExamples = rpDelegate.parseExamplesFromEdit(examplesRaw)
                    if (examplesRaw.isNotBlank() && parsedExamples.isEmpty()) {
                        if (isAdded) {
                            AppToast.makeText(
                                requireContext(),
                                getString(R.string.rp_examples_format_invalid),
                                AppToast.LENGTH_LONG
                            ).show()
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
                            AppToast.makeText(
                                requireContext(),
                                getString(R.string.rp_avatar_save_failed),
                                AppToast.LENGTH_LONG
                            ).show()
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
                        AppToast.makeText(
                            requireContext(),
                            getString(R.string.rp_save_failed),
                            AppToast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun showAvatar(model: Any) {
        avatarPreview.visibility = View.VISIBLE
        clearAvatarButton.visibility = View.VISIBLE
        avatarPreview.load(model) {
            crossfade(true)
            transformations(CircleCropTransformation())
        }
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
