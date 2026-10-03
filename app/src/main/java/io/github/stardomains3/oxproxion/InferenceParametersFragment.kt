package io.github.stardomains3.oxproxion

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.core.graphics.toColorInt
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.textfield.TextInputLayout

class InferenceParametersFragment : Fragment(R.layout.fragment_inference_parameters) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }

        val prefs = SharedPreferencesHelper(requireContext())

        // Temperature
        setupParameter(
            view = view,
            switchId = R.id.tempSwitch,
            inputLayoutId = R.id.tempInputLayout,
            editId = R.id.tempEdit,
            isEnabled = prefs.getInferenceTempEnabled(),
            currentValue = prefs.getInferenceTempValue().toString(),
            onSwitchChanged = { prefs.saveInferenceTempEnabled(it) },
            onValueChanged = { text -> prefs.saveInferenceTempValue(text) },
            accepts = { acceptedInferenceDecimal(InferenceKind.TEMPERATURE, it) != null },
            rangeError = decimalRangeError(InferenceKind.TEMPERATURE),
        )

        // Top P
        setupParameter(
            view = view,
            switchId = R.id.topPSwitch,
            inputLayoutId = R.id.topPInputLayout,
            editId = R.id.topPEdit,
            isEnabled = prefs.getInferenceTopPEnabled(),
            currentValue = prefs.getInferenceTopPValue().toString(),
            onSwitchChanged = { prefs.saveInferenceTopPEnabled(it) },
            onValueChanged = { text -> prefs.saveInferenceTopPValue(text) },
            accepts = { acceptedInferenceDecimal(InferenceKind.TOP_P, it) != null },
            rangeError = decimalRangeError(InferenceKind.TOP_P),
        )

        // Top K
        setupParameter(
            view = view,
            switchId = R.id.topKSwitch,
            inputLayoutId = R.id.topKInputLayout,
            editId = R.id.topKEdit,
            isEnabled = prefs.getInferenceTopKEnabled(),
            currentValue = prefs.getInferenceTopKValue().toString(),
            onSwitchChanged = { prefs.saveInferenceTopKEnabled(it) },
            onValueChanged = { text -> acceptedTopK(text)?.let { prefs.saveInferenceTopKValue(it) } },
            accepts = { acceptedTopK(it) != null },
            rangeError = getString(R.string.inference_error_whole_range, 1, 100_000),
        )

        // Min P
        setupParameter(
            view = view,
            switchId = R.id.minPSwitch,
            inputLayoutId = R.id.minPInputLayout,
            editId = R.id.minPEdit,
            isEnabled = prefs.getInferenceMinPEnabled(),
            currentValue = prefs.getInferenceMinPValue().toString(),
            onSwitchChanged = { prefs.saveInferenceMinPEnabled(it) },
            onValueChanged = { text -> prefs.saveInferenceMinPValue(text) },
            accepts = { acceptedInferenceDecimal(InferenceKind.MIN_P, it) != null },
            rangeError = decimalRangeError(InferenceKind.MIN_P),
        )

        // Repetition Penalty
        setupParameter(
            view = view,
            switchId = R.id.repPenaltySwitch,
            inputLayoutId = R.id.repPenaltyInputLayout,
            editId = R.id.repPenaltyEdit,
            isEnabled = prefs.getInferenceRepetitionPenaltyEnabled(),
            currentValue = prefs.getInferenceRepetitionPenaltyValue().toString(),
            onSwitchChanged = { prefs.saveInferenceRepetitionPenaltyEnabled(it) },
            onValueChanged = { text -> prefs.saveInferenceRepetitionPenaltyValue(text) },
            accepts = { acceptedInferenceDecimal(InferenceKind.REPETITION, it) != null },
            rangeError = decimalRangeError(InferenceKind.REPETITION),
        )

        // Presence Penalty
        setupParameter(
            view = view,
            switchId = R.id.presPenaltySwitch,
            inputLayoutId = R.id.presPenaltyInputLayout,
            editId = R.id.presPenaltyEdit,
            isEnabled = prefs.getInferencePresencePenaltyEnabled(),
            currentValue = prefs.getInferencePresencePenaltyValue().toString(),
            onSwitchChanged = { prefs.saveInferencePresencePenaltyEnabled(it) },
            onValueChanged = { text -> prefs.saveInferencePresencePenaltyValue(text) },
            accepts = { acceptedInferenceDecimal(InferenceKind.PRESENCE, it) != null },
            rangeError = decimalRangeError(InferenceKind.PRESENCE),
        )

        // Apply custom switch styling
        listOf(
            R.id.tempSwitch,
            R.id.topPSwitch,
            R.id.topKSwitch,
            R.id.minPSwitch,
            R.id.repPenaltySwitch,
            R.id.presPenaltySwitch
        ).forEach { id ->
            view.findViewById<SwitchCompat>(id)?.styleSwitch()
        }
    }

    private fun setupParameter(
        view: View,
        switchId: Int,
        inputLayoutId: Int,
        editId: Int,
        isEnabled: Boolean,
        currentValue: String,
        onSwitchChanged: (Boolean) -> Unit,
        onValueChanged: (String) -> Unit,
        accepts: (String) -> Boolean,
        rangeError: String,
    ) {
        val switch = view.findViewById<SwitchCompat>(switchId)
        val inputLayout = view.findViewById<TextInputLayout>(inputLayoutId)
        val edit = view.findViewById<EditText>(editId) // TextInputEditText extends EditText

        switch.isChecked = isEnabled
        inputLayout.isEnabled = isEnabled

        edit.setText(currentValue)

        switch.setOnCheckedChangeListener { _, isChecked ->
            onSwitchChanged(isChecked)
            inputLayout.isEnabled = isChecked
        }

        // A value outside the range is not saved; say so, or the field shows a number that never applies.
        edit.doAfterTextChanged { text ->
            val typed = text?.toString().orEmpty()
            inputLayout.error = when {
                typed.isBlank() -> null
                accepts(typed) -> { onValueChanged(typed); null }
                else -> rangeError
            }
        }
    }

    /** "Enter a number from 0 to 5", without a trailing ".0" on whole bounds. */
    private fun decimalRangeError(kind: InferenceKind): String {
        fun bound(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
        return getString(R.string.inference_error_range, bound(kind.min), bound(kind.max))
    }

    // Copy of your exact switch style helper
    private fun SwitchCompat.styleSwitch() = applyGrokionSwitchStyle()
}
