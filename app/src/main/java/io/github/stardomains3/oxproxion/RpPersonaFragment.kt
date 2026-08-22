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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class RpPersonaFragment : Fragment() {

    private lateinit var prefs: SharedPreferencesHelper
    private lateinit var presetSpinner: Spinner
    private lateinit var deletePresetButton: MaterialButton
    private lateinit var personaInput: TextInputEditText

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_rp_persona, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = SharedPreferencesHelper(requireContext())
        personaInput = view.findViewById(R.id.rpPersonaInput)
        var baselinePersona = prefs.getRpPersona()
        personaInput.setText(baselinePersona)
        fun navigateUp() {
            if (personaInput.text?.toString().orEmpty() == baselinePersona) {
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
        refreshPresetSpinner(personaInput)

        view.findViewById<MaterialButton>(R.id.saveRpPersonaPresetButton).setOnClickListener {
            val nameInput = TextInputEditText(requireContext())
            val wrapper = TextInputLayout(requireContext()).apply {
                hint = getString(R.string.rp_persona_preset_name)
                addView(nameInput)
                setPadding(48, 16, 48, 0)
            }
            val dialog = MaterialAlertDialogBuilder(
                requireContext(),
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered
            )
                .setTitle(R.string.rp_save_persona_preset)
                .setView(wrapper)
                .setPositiveButton(R.string.action_ok, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(nameInput.windowToken, 0)
                    val name = nameInput.text?.toString()?.trim().orEmpty()
                    if (name.isBlank()) {
                        AppToast.makeText(requireContext(), getString(R.string.rp_name_required), AppToast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val presets = prefs.getRpPersonaPresets().toMutableList()
                    val droppingOldest = presets.none { it.name == name } && presets.size >= 12
                    presets.removeAll { it.name == name }
                    presets.add(0, RpPersonaPreset(name, personaInput.text?.toString().orEmpty()))
                    prefs.saveRpPersonaPresets(presets.take(12))
                    refreshPresetSpinner(personaInput)
                    if (droppingOldest) {
                        AppToast.makeText(
                            requireContext(),
                            getString(R.string.rp_persona_preset_cap),
                            AppToast.LENGTH_SHORT
                        ).show()
                    }
                    dialog.dismiss()
                }
            }
            dialog.show()
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            nameInput.requestFocus()
        }

        deletePresetButton.setOnClickListener { confirmDeleteSelectedPreset(personaInput) }

        view.findViewById<MaterialButton>(R.id.saveRpPersonaButton).setOnClickListener {
            val text = personaInput.text?.toString().orEmpty()
            prefs.saveRpPersona(text)
            baselinePersona = text
            AppToast.makeText(requireContext(), getString(R.string.rp_saved), AppToast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
        }
    }

    private fun confirmDeleteSelectedPreset(input: TextInputEditText) {
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
                refreshPresetSpinner(input)
                AppToast.makeText(requireContext(), getString(R.string.rp_persona_preset_deleted), AppToast.LENGTH_SHORT).show()
            }
        )
    }

    private fun refreshPresetSpinner(input: TextInputEditText) {
        val presets = prefs.getRpPersonaPresets()
        val labels = listOf(getString(R.string.rp_persona_preset_none)) + presets.map { it.name }
        presetSpinner.adapter = ArrayAdapter(requireContext(), R.layout.item_rp_spinner, labels).apply {
            setDropDownViewResource(R.layout.item_rp_spinner)
        }
        deletePresetButton.visibility = if (presets.isEmpty()) View.GONE else View.VISIBLE
        presetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            private var ignoreNext = true
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                deletePresetButton.isEnabled = position > 0
                if (ignoreNext) {
                    ignoreNext = false
                    return
                }
                if (position > 0) {
                    input.setText(presets[position - 1].description)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        deletePresetButton.isEnabled = presetSpinner.selectedItemPosition > 0
    }

    companion object {
        fun newInstance() = RpPersonaFragment()
    }
}
