package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.os.Bundle
import android.view.View
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

        val prefs = SharedPreferencesHelper(requireContext())
        view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.settingsRoleplaySwitch).apply {
            isChecked = prefs.isRoleplayEnabled()
            setOnCheckedChangeListener { _, on -> prefs.setRoleplayEnabled(on) }
        }
        val codeStore = io.github.stardomains3.oxproxion.code.CodeHub.get(requireContext()).store
        view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.settingsCodeSwitch).apply {
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

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) return
        // Code settings has its own switch for the same flag.
        view?.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.settingsCodeSwitch)?.isChecked =
            io.github.stardomains3.oxproxion.code.CodeHub.get(requireContext()).store.enabled
    }
}
