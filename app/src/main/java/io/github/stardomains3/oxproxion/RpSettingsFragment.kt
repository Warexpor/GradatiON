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
        view.findViewById<MaterialToolbar>(R.id.toolbar).apply {
            arguments?.getInt(ARG_TITLE)?.takeIf { it != 0 }?.let(::setTitle)
            setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        }
        RpPageKit.applyInsets(view)
        view.findViewById<android.widget.LinearLayout>(R.id.rpPageBody).addView(
            RpPageKit.intro(requireContext(), getString(R.string.rp_page_style_caption)), 0
        )
        view.findViewById<SwitchCompat>(R.id.rpLoreEnabledSwitch).apply {
            isChecked = prefs.isRpLoreEnabled()
            setOnCheckedChangeListener { _, checked -> prefs.saveRpLoreEnabled(checked) }
        }
        val thirdPersonSwitch = view.findViewById<SwitchCompat>(R.id.rpThirdPersonSwitch)
        thirdPersonSwitch.isChecked = prefs.isRpThirdPerson()
        thirdPersonSwitch.setOnCheckedChangeListener { _, checked -> prefs.saveRpThirdPerson(checked) }

        view.findViewById<SwitchCompat>(R.id.rpAutoMemorySwitch).apply {
            isChecked = prefs.isRpAutoMemory()
            setOnCheckedChangeListener { _, checked -> prefs.saveRpAutoMemory(checked) }
        }

        val showThoughtsSwitch = view.findViewById<SwitchCompat>(R.id.rpShowThoughtsSwitch)
        showThoughtsSwitch.isChecked = prefs.isRpShowThoughts()
        showThoughtsSwitch.setOnCheckedChangeListener { _, checked -> prefs.saveRpShowThoughts(checked) }

        fun syncLlmGatedSwitches(llmOn: Boolean) {
            // Third-person / thoughts only affect character RP prompts.
            thirdPersonSwitch.isEnabled = !llmOn
            showThoughtsSwitch.isEnabled = !llmOn
            thirdPersonSwitch.alpha = if (llmOn) 0.45f else 1f
            showThoughtsSwitch.alpha = if (llmOn) 0.45f else 1f
        }
        syncLlmGatedSwitches(prefs.isRpLlmMode())

        val llmSwitch = view.findViewById<SwitchCompat>(R.id.rpLlmModeSwitch)
        llmSwitch.isChecked = prefs.isRpLlmMode()
        llmSwitch.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) {
                prefs.saveRpLlmMode(checked)
                syncLlmGatedSwitches(checked)
                return@setOnCheckedChangeListener
            }
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
                return@setOnCheckedChangeListener
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
                                AppToast.makeText(
                                    requireContext(),
                                    getString(R.string.rp_select_character),
                                    AppToast.LENGTH_SHORT
                                ).show()
                                chatViewModel.startNewChat()
                            } else {
                                chatViewModel.startNewRpChatKeepingCharacter()
                            }
                        }
                    },
                    destructive = false
                )
                return@setOnCheckedChangeListener
            }
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
                        AppToast.makeText(
                            requireContext(),
                            getString(R.string.rp_select_character),
                            AppToast.LENGTH_SHORT
                        ).show()
                        if (chatViewModel.isRpMode()) {
                            chatViewModel.startNewChat()
                        }
                    } else if (chatViewModel.isRpMode()) {
                        chatViewModel.startNewRpChatKeepingCharacter()
                    }
                }
            }
        }
    }

    companion object {
        private const val ARG_TITLE = "title"

        /** [title] names the page after where it was opened from: the panel's Style tile, or RP settings. */
        fun newInstance(@androidx.annotation.StringRes title: Int = 0) = RpSettingsFragment().apply {
            arguments = Bundle().apply { putInt(ARG_TITLE, title) }
        }
    }
}
