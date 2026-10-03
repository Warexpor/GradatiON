package io.github.stardomains3.oxproxion

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import androidx.core.view.isVisible

class PresetEditFragment : Fragment() {

    private lateinit var viewModel: ChatViewModel
    private lateinit var prefs: SharedPreferencesHelper
    private lateinit var toolbar: MaterialToolbar

    private var editingPreset: Preset? = null
    private lateinit var toolsSwitch: SwitchCompat

    private lateinit var webSearchSwitch: SwitchCompat
    private lateinit var titleInput: TextInputEditText
    private lateinit var modelAutoComplete: MaterialAutoCompleteTextView
    private lateinit var systemMessageAutoComplete: MaterialAutoCompleteTextView
    private lateinit var streamingSwitch: SwitchCompat
    private lateinit var reasoningSwitch: SwitchCompat
    private lateinit var conversationSwitch: SwitchCompat
    private lateinit var saveBtn: MaterialButton
    private lateinit var cancelBtn: MaterialButton

    private var selectedModelIdentifier: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_preset_edit, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewModel = ViewModelProvider(requireActivity(), AppViewModelFactory(requireActivity().application))[ChatViewModel::class.java]
        prefs = SharedPreferencesHelper(requireContext())

        initViews(view)
        setupToolbar()

        // Load preset for editing if available
        val currentPreset = arguments?.getString(ARG_PRESET_ID)?.let { id ->
            PresetRepository(requireContext()).findById(id)
        }
        editingPreset = currentPreset

        // Setup AutoComplete dropdowns
        setupModelAutoComplete()
        setupSystemMessageAutoComplete()

        // Load data if editing
        if (editingPreset != null) {
            populateEditData(editingPreset!!)
        }

        // Setup click listeners
        saveBtn.setOnClickListener { save() }
        cancelBtn.setOnClickListener { parentFragmentManager.popBackStack() }
        listOf(streamingSwitch, reasoningSwitch, conversationSwitch, toolsSwitch, webSearchSwitch).forEach { switch ->
            switch.applyGrokionSwitchStyle()
        }

      /*  streamingSwitch.trackTintList = trackTintSelector
        streamingSwitch.thumbTintList = thumbTintSelector
        streamingSwitch.thumbTintMode = PorterDuff.Mode.SRC_ATOP
        streamingSwitch.trackTintMode = PorterDuff.Mode.SRC_ATOP

        reasoningSwitch.trackTintList = trackTintSelector
        reasoningSwitch.thumbTintList = thumbTintSelector
        reasoningSwitch.thumbTintMode = PorterDuff.Mode.SRC_ATOP
        reasoningSwitch.trackTintMode = PorterDuff.Mode.SRC_ATOP

        conversationSwitch.trackTintList = trackTintSelector
        conversationSwitch.thumbTintList = thumbTintSelector
        conversationSwitch.thumbTintMode = PorterDuff.Mode.SRC_ATOP
        conversationSwitch.trackTintMode = PorterDuff.Mode.SRC_ATOP*/
    }

    private fun initViews(view: View) {
        toolsSwitch = view.findViewById(R.id.switchTools)
        toolbar = view.findViewById(R.id.toolbar)
        titleInput = view.findViewById(R.id.editPresetTitle)
        webSearchSwitch = view.findViewById(R.id.switchWebSearch)
        modelAutoComplete = view.findViewById(R.id.autoCompleteModel)
        systemMessageAutoComplete = view.findViewById(R.id.autoCompleteSystemMessage)
        streamingSwitch = view.findViewById(R.id.switchStreaming)
        reasoningSwitch = view.findViewById(R.id.switchReasoning)
        conversationSwitch = view.findViewById(R.id.switchConversation)
        saveBtn = view.findViewById(R.id.buttonSave)
        cancelBtn = view.findViewById(R.id.buttonCancel)
        toolsSwitch.isChecked = false
        streamingSwitch.isChecked = false // default: off
        reasoningSwitch.isChecked = true // default: on
        conversationSwitch.isChecked = false // default: off
        titleInput.inputLayout()?.clearErrorOnEdit()
        modelAutoComplete.inputLayout()?.clearErrorOnEdit()
    }

    private fun setupToolbar() {
        toolbar.title = getString(if (editingPreset == null) R.string.preset_edit_create_title else R.string.preset_edit_edit_title)
        toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_save_prompt -> {
                    save() // This calls your existing save() function
                    true // Return true to indicate the click was handled
                }
                else -> false // Return false for any other menu items
            }
        }
    }

    private fun setupModelAutoComplete() {
        val allModels = getAllModels()
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            allModels.map { ModelNames.withoutProvider(it.displayName, it.apiIdentifier) },
        )
        modelAutoComplete.setAdapter(adapter)

        // Use TextWatcher for immediate updates (like working EditPresetFragment)
        modelAutoComplete.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val selectedModelName = s?.toString()?.trim().orEmpty()
                if (selectedModelName.isNotEmpty()) {
                    val allModels = getAllModels()
                    val selectedModel = allModels.find {
                        ModelNames.withoutProvider(it.displayName, it.apiIdentifier) == selectedModelName
                    } ?: allModels.find { it.displayName == selectedModelName }
                    selectedModel?.let {
                        selectedModelIdentifier = it.apiIdentifier
                        updateSwitchVisibility()
                    }
                }
            }
        })
    }

    private fun setupSystemMessageAutoComplete() {
        val allMessages = getAllSystemMessages()
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, allMessages.map { it.title })
        systemMessageAutoComplete.setAdapter(adapter)
    }

    private fun populateEditData(preset: Preset) {
        titleInput.setText(preset.title)

        // Set model text
        val allModels = getAllModels()
        val matched = allModels.find { it.apiIdentifier.equals(preset.modelIdentifier, ignoreCase = true) }
        val modelDisplayName = matched?.let { ModelNames.withoutProvider(it.displayName, it.apiIdentifier) }
            ?: getString(R.string.preset_model_missing, preset.modelIdentifier)
        modelAutoComplete.setText(modelDisplayName, false)
        selectedModelIdentifier = preset.modelIdentifier

        // Set system message text
        val allMessages = getAllSystemMessages()
        val systemMessageTitle = allMessages.find {
            it.title == preset.systemMessage.title && it.prompt == preset.systemMessage.prompt
        }?.title ?: preset.systemMessage.title
        systemMessageAutoComplete.setText(systemMessageTitle, false)

        // Set toggles
        toolsSwitch.isChecked = preset.tools
        webSearchSwitch.isChecked = preset.webSearch
        streamingSwitch.isChecked = preset.streaming
        reasoningSwitch.isChecked = preset.reasoning
        conversationSwitch.isChecked = preset.conversationMode

        // Update reasoning visibility based on selected model
        updateSwitchVisibility()
    }
    private fun updateSwitchVisibility() {
        val modelId = selectedModelIdentifier ?: return

        // --- Reasoning ---
        val isReasoning = viewModel.canRequestReasoning(modelId)
        // Reset if hidden
        if (!isReasoning) {
            reasoningSwitch.isChecked = false
        }
        reasoningSwitch.visibility = if (isReasoning) View.VISIBLE else View.GONE

        // --- Web Search ---
        val isLan = viewModel.isLanModel(modelId)
        if (isLan) {
            webSearchSwitch.isChecked = false
        }
        webSearchSwitch.visibility = if (isLan) View.GONE else View.VISIBLE
    }

    private fun getAllModels(): List<LlmModel> {
        val builtIn = viewModel.getBuiltInModels()
        val custom = prefs.getCustomModels()
        return (builtIn + custom).distinctBy { it.apiIdentifier.lowercase() }
            .sortedBy { ModelNames.withoutProvider(it.displayName, it.apiIdentifier).lowercase() }
    }

    private fun getAllSystemMessages(): List<SystemMessage> {
        val default = prefs.getDefaultSystemMessage()
        val custom = prefs.getCustomSystemMessages()
        return (listOf(default) + custom).sortedBy { it.title.lowercase() }
    }

    private fun getSelectedModel(): LlmModel? {
        val modelName = modelAutoComplete.text.toString().trim()
        return getAllModels().find {
            ModelNames.withoutProvider(it.displayName, it.apiIdentifier) == modelName
        } ?: getAllModels().find { it.displayName == modelName }
    }

    private fun getSelectedSystemMessage(): SystemMessage? {
        val messageTitle = systemMessageAutoComplete.text.toString().trim()
        return getAllSystemMessages().find { it.title == messageTitle }
    }

    private fun save() {
        val title = titleInput.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            titleInput.inputLayout()?.error = getString(R.string.preset_edit_title_required)
            return
        }

        val model = getSelectedModel()
        if (model == null) {
            // A name that no longer matches a model still saves (it shows as "Missing: ..."); a blank one can't.
            val modelName = modelAutoComplete.text.toString().trim()
            if (modelName.isEmpty()) {
                modelAutoComplete.inputLayout()?.error = getString(R.string.preset_edit_model_required)
                return
            }
        }

        val sysMsg = getSelectedSystemMessage() ?: prefs.getDefaultSystemMessage()

        val streaming = streamingSwitch.isChecked
        val reasoning = if (reasoningSwitch.isVisible) reasoningSwitch.isChecked else false
        val conversationMode = conversationSwitch.isChecked
        val tools = toolsSwitch.isChecked
        val webSearch = webSearchSwitch.isChecked
        val preset = Preset(
            id = editingPreset?.id ?: java.util.UUID.randomUUID().toString(),
            title = title,
            modelIdentifier = model?.apiIdentifier ?: keptMissingModel() ?: "unknown-model",
            systemMessage = sysMsg,
            streaming = streaming,
            reasoning = reasoning,
            conversationMode = conversationMode,
            tools = tools,
            webSearch = webSearch
        )

        val repo = PresetRepository(requireContext())
        repo.upsert(preset)

        parentFragmentManager.popBackStack()
    }

    /**
     * The edited preset's own model id while the field still shows it as "Missing: id", so a
     * preset whose model was removed keeps pointing at it and works again once it is re-added.
     */
    private fun keptMissingModel(): String? = editingPreset?.modelIdentifier?.takeIf {
        modelAutoComplete.text.toString().trim() == getString(R.string.preset_model_missing, it)
    }

    /** The TextInputLayout around this field, where errors show. */
    private fun View.inputLayout(): com.google.android.material.textfield.TextInputLayout? {
        var p = parent
        while (p != null && p !is com.google.android.material.textfield.TextInputLayout) p = p.parent
        return p as? com.google.android.material.textfield.TextInputLayout
    }

    companion object {
        private const val ARG_PRESET_ID = "preset_id"
        fun newInstance(preset: Preset?): PresetEditFragment {
            return PresetEditFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PRESET_ID, preset?.id)
                }
            }
        }
    }
}
