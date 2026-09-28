package io.github.stardomains3.oxproxion

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class RpPersonaFragment : Fragment() {

    private lateinit var prefs: SharedPreferencesHelper
    private lateinit var presetSpinner: Spinner
    private lateinit var deletePresetButton: MaterialButton
    private lateinit var personaInput: TextInputEditText
    private lateinit var nameInput: TextInputEditText

    /** Spinner row currently in effect (0 = none), so a declined preset load can put it back. */
    private var appliedPreset = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_rp_persona, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = SharedPreferencesHelper(requireContext())
        personaInput = view.findViewById(R.id.rpPersonaInput)
        nameInput = view.findViewById(R.id.rpPersonaNameInput)
        var baselinePersona = prefs.getRpPersona()
        var baselineName = prefs.getRpPersonaName()
        personaInput.setText(baselinePersona)
        nameInput.setText(baselineName)
        fun navigateUp() {
            if (!hasUnsavedText(baselineName, baselinePersona)) {
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
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { navigateUp() }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = navigateUp()
            }
        )
        presetSpinner = view.findViewById(R.id.rpPersonaPresetSpinner)
        deletePresetButton = view.findViewById(R.id.deleteRpPersonaPresetButton)
        refreshPresetSpinner(selectName = null) { baselineName to baselinePersona }

        view.findViewById<MaterialButton>(R.id.saveRpPersonaPresetButton).setOnClickListener {
            showSavePresetDialog { baselineName to baselinePersona }
        }

        deletePresetButton.setOnClickListener { confirmDeleteSelectedPreset { baselineName to baselinePersona } }

        view.findViewById<MaterialButton>(R.id.saveRpPersonaButton).setOnClickListener {
            val text = personaInput.text?.toString().orEmpty()
            val name = nameInput.text?.toString()?.trim().orEmpty()
            prefs.saveRpPersona(text)
            prefs.saveRpPersonaName(name)
            baselinePersona = text
            baselineName = name
            GlassNotice.show(requireContext(), getString(R.string.rp_saved))
            parentFragmentManager.popBackStack()
        }
    }

    private fun hasUnsavedText(savedName: String, savedPersona: String): Boolean =
        nameInput.text?.toString()?.trim().orEmpty() != savedName.trim() ||
            personaInput.text?.toString().orEmpty() != savedPersona

    private fun showSavePresetDialog(saved: () -> Pair<String, String>) {
        val density = resources.displayMetrics.density
        val presetName = TextInputEditText(requireContext())
        val wrapper = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.rp_persona_preset_name)
            addView(presetName)
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
        }
        // Start from the preset in use, else the persona's own name: saving again is one tap.
        val current = prefs.getRpPersonaPresets().getOrNull(presetSpinner.selectedItemPosition - 1)?.name
            ?: nameInput.text?.toString()?.trim().orEmpty()
        presetName.setText(current)
        presetName.setSelection(current.length)
        val dialog = GlassAlertDialogBuilder(
            requireContext(),
            R.style.CustomMaterialAlertDialogTheme
        )
            .setTitle(R.string.rp_save_persona_preset)
            .setView(wrapper)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = presetName.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    wrapper.error = getString(R.string.rp_name_required)
                    return@setOnClickListener
                }
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(presetName.windowToken, 0)
                dialog.dismiss()
                if (prefs.getRpPersonaPresets().any { it.name == name }) {
                    GrokConfirmDialog.show(
                        fragment = this,
                        title = getString(R.string.rp_persona_overwrite_title),
                        message = getString(R.string.rp_persona_overwrite_body, name),
                        confirmText = getString(R.string.rp_persona_overwrite_confirm),
                        onConfirm = { savePreset(name, saved) },
                        destructive = false
                    )
                } else {
                    savePreset(name, saved)
                }
            }
        }
        dialog.show()
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        presetName.requestFocus()
    }

    private fun savePreset(name: String, saved: () -> Pair<String, String>) {
        val presets = prefs.getRpPersonaPresets().toMutableList()
        val droppingOldest = presets.none { it.name == name } && presets.size >= 12
        presets.removeAll { it.name == name }
        presets.add(
            0,
            RpPersonaPreset(
                name = name,
                description = personaInput.text?.toString().orEmpty(),
                userName = nameInput.text?.toString()?.trim().orEmpty()
            )
        )
        prefs.saveRpPersonaPresets(presets.take(12))
        refreshPresetSpinner(selectName = name, saved = saved)
        if (droppingOldest) {
            GlassNotice.show(requireContext(), getString(R.string.rp_persona_preset_cap))
        }
    }

    private fun confirmDeleteSelectedPreset(saved: () -> Pair<String, String>) {
        val presets = prefs.getRpPersonaPresets()
        val position = presetSpinner.selectedItemPosition
        if (position <= 0 || position > presets.size) return
        val target = presets[position - 1]
        GrokConfirmDialog.show(
            fragment = this,
            title = getString(R.string.rp_persona_delete_preset),
            message = target.name,
            confirmText = getString(R.string.rp_menu_delete),
            onConfirm = {
                prefs.saveRpPersonaPresets(presets.filterNot { it.name == target.name })
                refreshPresetSpinner(selectName = null, saved = saved)
                GlassNotice.show(requireContext(), getString(R.string.rp_persona_preset_deleted))
            }
        )
    }

    private fun applyPreset(preset: RpPersonaPreset) {
        nameInput.setText(preset.personaName)
        personaInput.setText(preset.description)
    }

    /** Rebuilds the list; [selectName] picks that preset without loading it (it was just saved from the fields). */
    private fun refreshPresetSpinner(selectName: String?, saved: () -> Pair<String, String>) {
        val presets = prefs.getRpPersonaPresets()
        val labels = listOf(getString(R.string.rp_persona_preset_none)) + presets.map { it.name }
        presetSpinner.adapter = ArrayAdapter(requireContext(), R.layout.item_rp_spinner, labels).apply {
            setDropDownViewResource(R.layout.item_rp_spinner)
        }
        deletePresetButton.visibility = if (presets.isEmpty()) View.GONE else View.VISIBLE
        view?.findViewById<View>(R.id.rpPersonaDeleteDivider)?.visibility = deletePresetButton.visibility
        val selected = selectName?.let { n -> presets.indexOfFirst { it.name == n } + 1 } ?: 0
        presetSpinner.setSelection(selected, false)
        appliedPreset = selected
        presetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            private var ignoreNext = true
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                deletePresetButton.isEnabled = position > 0
                if (ignoreNext) {
                    ignoreNext = false
                    return
                }
                if (position == appliedPreset) return
                if (position == 0) {
                    appliedPreset = 0
                    return
                }
                val preset = presets[position - 1]
                val (savedName, savedPersona) = saved()
                if (hasUnsavedText(savedName, savedPersona) && !isCurrentPreset(presets)) {
                    // Loading replaces both fields, so a change nobody saved gets a say first.
                    presetSpinner.setSelection(appliedPreset, false)
                    deletePresetButton.isEnabled = appliedPreset > 0
                    GrokConfirmDialog.show(
                        fragment = this@RpPersonaFragment,
                        title = getString(R.string.rp_persona_load_title),
                        message = getString(R.string.rp_persona_load_body, preset.name),
                        confirmText = getString(R.string.rp_persona_load_confirm),
                        onConfirm = {
                            presetSpinner.setSelection(position, false)
                            appliedPreset = position
                            deletePresetButton.isEnabled = true
                            applyPreset(preset)
                        },
                        destructive = false
                    )
                } else {
                    appliedPreset = position
                    applyPreset(preset)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        deletePresetButton.isEnabled = selected > 0
    }

    /** True when the fields already match a saved preset, so loading another one loses nothing. */
    private fun isCurrentPreset(presets: List<RpPersonaPreset>): Boolean {
        val name = nameInput.text?.toString()?.trim().orEmpty()
        val text = personaInput.text?.toString().orEmpty()
        return presets.any { it.description == text && it.personaName.trim() == name }
    }

    companion object {
        fun newInstance() = RpPersonaFragment()
    }
}
