package io.github.stardomains3.oxproxion

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import androidx.appcompat.widget.SwitchCompat

class RpSettingsFragment : Fragment() {

    private lateinit var prefs: SharedPreferencesHelper

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_rp_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = SharedPreferencesHelper(requireContext())
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        // The whole row is the tap target; the switch beside it only shows the state.
        fun bindRow(rowId: Int, switchId: Int, on: Boolean, onChange: (Boolean) -> Unit): SwitchCompat {
            val toggle = view.findViewById<SwitchCompat>(switchId)
            toggle.isChecked = on
            view.findViewById<View>(rowId).setOnClickListener {
                if (!it.isEnabled) return@setOnClickListener
                toggle.isChecked = !toggle.isChecked
                onChange(toggle.isChecked)
            }
            return toggle
        }
        val thirdPersonSwitch = bindRow(R.id.rpThirdPersonRow, R.id.rpThirdPersonSwitch, prefs.isRpThirdPerson()) {
            prefs.saveRpThirdPerson(it)
        }
        bindRow(R.id.rpAutoMemoryRow, R.id.rpAutoMemorySwitch, prefs.isRpAutoMemory()) { prefs.saveRpAutoMemory(it) }
        val showThoughtsSwitch = bindRow(R.id.rpShowThoughtsRow, R.id.rpShowThoughtsSwitch, prefs.isRpShowThoughts()) {
            prefs.saveRpShowThoughts(it)
        }

        // Open scene is chosen from the character picker; here it only greys out what needs a character.
        run {
            val llmOn = prefs.isRpLlmMode()
            // Third-person / thoughts only affect character RP prompts.
            for ((row, toggle) in listOf(
                R.id.rpThirdPersonRow to thirdPersonSwitch,
                R.id.rpShowThoughtsRow to showThoughtsSwitch
            )) {
                view.findViewById<View>(row).isEnabled = !llmOn
                view.findViewById<View>(row).alpha = if (llmOn) 0.45f else 1f
                toggle.isEnabled = !llmOn
            }
        }
    }

    companion object {
        fun newInstance() = RpSettingsFragment()
    }
}
