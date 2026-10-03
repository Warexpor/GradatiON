package io.github.stardomains3.oxproxion

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import kotlin.ranges.until
import kotlin.text.toIntOrNull

class AdvancedReasoningFragment : Fragment(R.layout.fragment_advanced_reasoning) {

    private lateinit var sharedPreferencesHelper: SharedPreferencesHelper
    private lateinit var effortGroup: MaterialButtonToggleGroup
    private lateinit var includeSwitch: SwitchCompat
    private lateinit var maxTokensEdit: TextInputEditText

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedPreferencesHelper = SharedPreferencesHelper(requireContext())

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Inflate menu directly on the toolbar
        toolbar.inflateMenu(R.menu.advanced_reasoning_menu)
        val menuItem = toolbar.menu.findItem(R.id.menu_advanced_toggle)
        if (menuItem != null) {
            val advancedToggle = menuItem.actionView as SwitchCompat // CHANGE THIS LINE
            val isEnabled = sharedPreferencesHelper.getAdvancedReasoningEnabled()
            advancedToggle.isChecked = isEnabled

            advancedToggle.applyGrokionSwitchStyle()
            advancedToggle.setOnCheckedChangeListener { _, isChecked ->
                sharedPreferencesHelper.saveAdvancedReasoningEnabled(isChecked)
                updateControlsEnabled(isChecked)
            }
        }

        effortGroup = view.findViewById(R.id.effortGroup)
        includeSwitch = view.findViewById(R.id.includeSwitch)
        maxTokensEdit = view.findViewById(R.id.maxTokensEdit)
        val isEnabled = sharedPreferencesHelper.getAdvancedReasoningEnabled()
        updateControlsEnabled(isEnabled)
        loadSettings()
        effortGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                val effort = when (checkedId) {
                    R.id.buttonMinimal -> "minimal"
                    R.id.buttonLow -> "low"
                    R.id.buttonMedium -> "medium"
                    R.id.buttonHigh -> "high"
                    else -> "medium"
                }
                sharedPreferencesHelper.saveReasoningEffort(effort)
            }
        }


        includeSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferencesHelper.saveReasoningExclude(!isChecked)  // exclude = !include
        }

        maxTokensEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s?.toString()?.toIntOrNull()
                sharedPreferencesHelper.saveReasoningMaxTokens(value)
                applyEffortEnabled(
                    reasoningEffortControlsEnabled(sharedPreferencesHelper.getAdvancedReasoningEnabled(), value)
                )
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        includeSwitch.applyGrokionSwitchStyle()
    }

    private fun loadSettings() {
        val effort = sharedPreferencesHelper.getReasoningEffort()
        val buttonId = when (effort) {
            "minimal" -> R.id.buttonMinimal
            "low" -> R.id.buttonLow
            "medium" -> R.id.buttonMedium
            "high" -> R.id.buttonHigh
            else -> R.id.buttonMedium
        }
        effortGroup.check(buttonId)

        includeSwitch.isChecked = !sharedPreferencesHelper.getReasoningExclude()
        maxTokensEdit.setText(sharedPreferencesHelper.getReasoningMaxTokens()?.toString() ?: "")
    }

    private fun updateControlsEnabled(enabled: Boolean) {
        includeSwitch.isEnabled = enabled
        includeSwitch.isClickable = enabled
        maxTokensEdit.isEnabled = enabled
        applyEffortEnabled(
            reasoningEffortControlsEnabled(enabled, sharedPreferencesHelper.getReasoningMaxTokens())
        )
    }

    private fun applyEffortEnabled(on: Boolean) {
        effortGroup.isEnabled = on
        for (i in 0 until effortGroup.childCount) {
            val button = effortGroup.getChildAt(i) as MaterialButton
            button.isEnabled = on
            button.isClickable = on
        }
    }
}

/**
 * Effort presets are dropped once a positive token budget is set (the request sends max_tokens
 * instead). The master switch off disables them too. Zero or a blank field is not a budget.
 */
internal fun reasoningEffortControlsEnabled(advancedOn: Boolean, maxTokens: Int?): Boolean =
    advancedOn && (maxTokens == null || maxTokens <= 0)
