package io.github.stardomains3.oxproxion

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.launch

class RpSettingsFragment : Fragment() {

    private lateinit var prefs: SharedPreferencesHelper
    private val chatViewModel: ChatViewModel by activityViewModels { AppViewModelFactory(requireActivity().application) }

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

        fun syncLlmGatedSwitches(llmOn: Boolean) {
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
        syncLlmGatedSwitches(prefs.isRpLlmMode())

        val llmSwitch = view.findViewById<SwitchCompat>(R.id.rpLlmModeSwitch)
        llmSwitch.isChecked = prefs.isRpLlmMode()
        // Flipping LLM mode can wipe the running chat, so the switch only moves once that is settled.
        fun requestLlmMode(checked: Boolean) {
            val button = llmSwitch
            if (checked && chatViewModel.isRpMode() && chatViewModel.rpChatHasContent()) {
                button.isChecked = false
                GrokConfirmDialog.show(
                    fragment = this,
                    title = getString(R.string.rp_llm_wipe_title),
                    message = getString(R.string.rp_llm_wipe_body),
                    confirmText = getString(R.string.rp_new_chat_confirm),
                    onConfirm = {
                        prefs.saveRpLlmMode(true)
                        button.isChecked = true
                        syncLlmGatedSwitches(true)
                        chatViewModel.startRpLlmChat()
                    },
                    destructive = false
                )
                return
            }
            if (!checked && chatViewModel.isRpMode() && chatViewModel.rpChatHasContent()) {
                button.isChecked = true
                GrokConfirmDialog.show(
                    fragment = this,
                    title = getString(R.string.rp_llm_off_wipe_title),
                    message = getString(R.string.rp_llm_off_wipe_body),
                    confirmText = getString(R.string.rp_new_chat_confirm),
                    onConfirm = {
                        prefs.saveRpLlmMode(false)
                        button.isChecked = false
                        syncLlmGatedSwitches(false)
                        viewLifecycleOwner.lifecycleScope.launch {
                            chatViewModel.refreshActiveRpCharacter()
                            if (!isAdded) return@launch
                            if (prefs.getRpActiveCharacterId() == null) {
                                GlassNotice.show(requireContext(), getString(R.string.rp_select_character))
                                chatViewModel.startNewChat()
                            } else {
                                chatViewModel.startNewRpChatKeepingCharacter()
                            }
                        }
                    },
                    destructive = false
                )
                return
            }
            button.isChecked = checked
            prefs.saveRpLlmMode(checked)
            syncLlmGatedSwitches(checked)
            if (checked) {
                if (chatViewModel.isRpMode()) {
                    chatViewModel.startRpLlmChat()
                } else {
                    // Keep character selection for the next RP session; only LLM flag flips in Ask.
                    viewLifecycleOwner.lifecycleScope.launch { chatViewModel.refreshActiveRpCharacter() }
                }
            } else {
                // Leaving LLM mode: restart with character greeting when one is selected so
                // session isLlm / transcript stay consistent.
                viewLifecycleOwner.lifecycleScope.launch {
                    chatViewModel.refreshActiveRpCharacter()
                    if (!isAdded) return@launch
                    if (prefs.getRpActiveCharacterId() == null) {
                        GlassNotice.show(requireContext(), getString(R.string.rp_select_character))
                        if (chatViewModel.isRpMode()) {
                            chatViewModel.startNewChat()
                        }
                    } else if (chatViewModel.isRpMode()) {
                        chatViewModel.startNewRpChatKeepingCharacter()
                    }
                }
            }
        }
        view.findViewById<View>(R.id.rpLlmModeRow).setOnClickListener { requestLlmMode(!llmSwitch.isChecked) }
    }

    companion object {
        fun newInstance() = RpSettingsFragment()
    }
}
