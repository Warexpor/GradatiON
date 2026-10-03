package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { parentFragmentManager.popBackStack() }

        fun openSection(section: String) {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, SettingsDetailFragment.newInstance(section))
                .addToBackStack(null)
                .commit()
        }

        bindValues(view)

        val prefs = SharedPreferencesHelper(requireContext())
        view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.settingsRoleplaySwitch).apply {
            applyGrokionSwitchStyle()
            isChecked = prefs.isRoleplayEnabled()
            setOnCheckedChangeListener { _, on -> prefs.setRoleplayEnabled(on) }
        }
        val codeStore = io.github.stardomains3.oxproxion.code.CodeHub.get(requireContext()).store
        view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.settingsCodeSwitch).apply {
            applyGrokionSwitchStyle()
            isChecked = codeStore.enabled
            setOnCheckedChangeListener { _, on ->
                codeStore.enabled = on
                if (!on) codeStore.lastTabWasCode = false
            }
        }

        view.findViewById<View>(R.id.settingsRowAppearance)
            .setOnClickListener { openSection(SettingsDetailFragment.SECTION_APPEARANCE) }
        view.findViewById<View>(R.id.settingsRowVoice)
            .setOnClickListener { openSection(SettingsDetailFragment.SECTION_VOICE) }
        view.findViewById<View>(R.id.settingsRowHaptics)
            .setOnClickListener { openSection(SettingsDetailFragment.SECTION_HAPTICS) }
        view.findViewById<View>(R.id.settingsRowModels)
            .setOnClickListener { openSection(SettingsDetailFragment.SECTION_MODELS) }
        view.findViewById<View>(R.id.settingsRowAdvanced)
            .setOnClickListener { openSection(SettingsDetailFragment.SECTION_ADVANCED) }
        view.findViewById<View>(R.id.settingsRowData)
            .setOnClickListener { openSection(SettingsDetailFragment.SECTION_DATA) }
        view.findViewById<View>(R.id.settingsRowCode).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, io.github.stardomains3.oxproxion.code.CodeSettingsFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    /** The rows that open a section show what is set in it, so the list reads without a tap. */
    private fun bindValues(view: View) {
        val ctx = requireContext()
        val prefs = SharedPreferencesHelper(ctx)
        val theme = when (prefs.getThemeMode()) {
            SharedPreferencesHelper.THEME_LIGHT -> R.string.settings_theme_light
            SharedPreferencesHelper.THEME_DARK -> R.string.settings_theme_dark
            else -> R.string.settings_theme_system
        }
        val picked = settingsVoiceRowEngine(prefs.getVoiceInputProvider())
        val engine = if (picked == VoiceEngine.OFF) getString(R.string.settings_value_off) else getString(picked.labelRes)
        bindRowValue(view, R.id.settingsRowAppearance, R.id.settingsRowAppearanceValue, getString(theme))
        bindRowValue(view, R.id.settingsRowVoice, R.id.settingsRowVoiceValue, engine)
    }

    private fun bindRowValue(view: View, rowId: Int, valueId: Int, value: String) {
        view.findViewById<TextView>(valueId).text = value
        // The value floats over the button, so the button's own label would be all a screen reader hears.
        val row = view.findViewById<TextView>(rowId)
        row.contentDescription = getString(R.string.cd_settings_row_value, row.text, value)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) return
        view?.let { bindValues(it) }
        // Sub-screens (Code settings, Appearance…) may have changed a mode flag.
        // Only write isChecked when it disagrees so the listener does not rewrite the pref.
        view?.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.settingsRoleplaySwitch)?.let { sw ->
            val on = SharedPreferencesHelper(requireContext()).isRoleplayEnabled()
            if (sw.isChecked != on) sw.isChecked = on
        }
        view?.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.settingsCodeSwitch)?.let { sw ->
            val on = io.github.stardomains3.oxproxion.code.CodeHub.get(requireContext()).store.enabled
            if (sw.isChecked != on) sw.isChecked = on
        }
    }
}
