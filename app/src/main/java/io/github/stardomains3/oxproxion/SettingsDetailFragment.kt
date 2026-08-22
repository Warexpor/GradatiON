package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class SettingsDetailFragment : Fragment(R.layout.fragment_settings_detail) {

    private val section: String
        get() = requireArguments().getString(ARG_SECTION) ?: SECTION_APPEARANCE

    private val savedChatsViewModel: SavedChatsViewModel by viewModels()

    private val exportChatsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val json = savedChatsViewModel.getChatsAsJson()
                        requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(json.toByteArray())
                        }
                        AppToast.makeText(requireContext(), "Chats exported successfully", AppToast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        AppToast.makeText(requireContext(), "Error exporting chats", AppToast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val importChatsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val json = requireContext().contentResolver.openInputStream(uri)?.use {
                            it.bufferedReader().readText()
                        }
                        if (json != null) {
                            savedChatsViewModel.importChatsFromJson(json) { importResult ->
                                when (importResult) {
                                    is ChatImportResult.Success ->
                                        AppToast.makeText(requireContext(), "Chats imported successfully", AppToast.LENGTH_SHORT).show()
                                    is ChatImportResult.Error ->
                                        AppToast.makeText(requireContext(), importResult.message, AppToast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            throw Exception("Failed to read file content.")
                        }
                    } catch (_: Exception) {
                        AppToast.makeText(requireContext(), "Import failed. Check file format.", AppToast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = sectionTitle(section)
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }

        bindAllControls(view)
        applySectionVisibility(view, section)
    }

    private fun sectionTitle(section: String): String = when (section) {
        SECTION_APPEARANCE -> getString(R.string.settings_section_appearance)
        SECTION_VOICE -> getString(R.string.settings_section_voice)
        SECTION_HAPTICS -> getString(R.string.settings_section_haptics)
        SECTION_MODELS -> getString(R.string.settings_section_models)
        SECTION_ADVANCED -> getString(R.string.settings_section_advanced)
        SECTION_DATA -> getString(R.string.settings_section_data)
        else -> getString(R.string.settings_title)
    }

    private fun bindAllControls(view: View) {
        val prefs = SharedPreferencesHelper(requireContext())
        val viewModel: ChatViewModel by activityViewModels()

        val themeToggleGroup = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.themeToggleGroup)
        val inferenceParamsButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.inferenceParamsButton)
        val chatMemoryButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.chatMemoryButton)
        val toolsButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.toolsButton)
        val animateBarOnErrorSwitch = view.findViewById<SwitchCompat>(R.id.animateBarOnErrorSwitch)
        val showCitationsSwitch = view.findViewById<SwitchCompat>(R.id.showCitationsSwitch)
        val powerToolsBarSwitch = view.findViewById<SwitchCompat>(R.id.powerToolsBarSwitch)
        val copyOrDismissSwitch = view.findViewById<SwitchCompat>(R.id.copyOrdismissSwitch)
        val expandableInputSwitch = view.findViewById<SwitchCompat>(R.id.expandableInputSwitch)
        val copyOrOpenSwitch = view.findViewById<SwitchCompat>(R.id.copyOropenSwitch)
        val autoDisableWebSearchSwitch = view.findViewById<SwitchCompat>(R.id.autoDisableWebSearchSwitch)
        val biometricsSwitch = view.findViewById<SwitchCompat>(R.id.biometricsSwitch)
        val notificationsSwitch = view.findViewById<SwitchCompat>(R.id.notificationsSwitch)
        val keepScreenOnSwitch = view.findViewById<SwitchCompat>(R.id.keepScreenOnSwitch)
        val scrollButtonsSwitch = view.findViewById<SwitchCompat>(R.id.scrollButtonsSwitch)
        val volumeScrollSwitch = view.findViewById<SwitchCompat>(R.id.volumeScrollSwitch)
        val timeoutButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.timeoutButton)
        val presetsExtendedSwitch = view.findViewById<SwitchCompat>(R.id.presetsExtendedSwitch)
        val scrollProgressSwitch = view.findViewById<SwitchCompat>(R.id.scrollProgressSwitch)
        val apiKeyButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.apiKeyButton)
        val braveApiKeyButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.braveApiKeyButton)
        val promptsButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.promptsButton)
        val presetsButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.presetsButton)
        val systemMessagesButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.systemMessagesButton)
        val advancedReasoningButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.advancedReasoningButton)
        val creditsButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.creditsButton)
        val helpButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.helpButton)
        val licensesButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.licensesButton)
        val importHistoryButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.importHistoryButton)
        val exportHistoryButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.exportHistoryButton)
        val maxTokensButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.maxTokensButton)
        val lanButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.lanButton)
        val trustSelfSignedLanSwitch = view.findViewById<SwitchCompat>(R.id.trustSelfSignedLanSwitch)
        val allowDestructiveToolsSwitch = view.findViewById<SwitchCompat>(R.id.allowDestructiveToolsSwitch)
        val openRouterTransformsSwitch = view.findViewById<SwitchCompat>(R.id.openRouterTransformsSwitch)
        val hapticButtonsSwitch = view.findViewById<SwitchCompat>(R.id.hapticButtonsSwitch)
        val hapticRespondingSwitch = view.findViewById<SwitchCompat>(R.id.hapticRespondingSwitch)

        biometricsSwitch.isChecked = prefs.getBiometricEnabled()
        notificationsSwitch.isChecked = prefs.getNotiPreference()
        val memoryCount = prefs.getChatMemoryCount()
        chatMemoryButton.text = if (memoryCount == Int.MAX_VALUE) "All messages" else "$memoryCount messages"
        val savedMode = prefs.getThemeMode()
        when (savedMode) {
            SharedPreferencesHelper.THEME_LIGHT -> themeToggleGroup.check(R.id.btnThemeLight)
            SharedPreferencesHelper.THEME_DARK -> themeToggleGroup.check(R.id.btnThemeDark)
            else -> themeToggleGroup.check(R.id.btnThemeSystem)
        }
        keepScreenOnSwitch.isChecked = prefs.getKeepScreenOnPreference()
        copyOrDismissSwitch.isChecked = prefs.getUseCopyButton2()
        animateBarOnErrorSwitch.isChecked = prefs.getAnimateBarOnError()
        scrollButtonsSwitch.isChecked = viewModel.isScrollersEnabled.value ?: false
        volumeScrollSwitch.isChecked = viewModel.isVolumeScrollEnabled.value ?: false
        expandableInputSwitch.isChecked = viewModel.isExpandableInputEnabled.value ?: false
        presetsExtendedSwitch.isChecked = viewModel.isPresetsExtendedEnabled.value ?: false
        scrollProgressSwitch.isChecked = viewModel.isScrollProgressEnabled.value ?: true
        copyOrOpenSwitch.isChecked = prefs.getUseCopyButton()
        autoDisableWebSearchSwitch.isChecked = prefs.getDisableWebSearchAfterSend()
        openRouterTransformsSwitch.isChecked = prefs.getOpenRouterTransformsEnabled()
        trustSelfSignedLanSwitch.isChecked = prefs.getTrustSelfSignedLan()
        allowDestructiveToolsSwitch.isChecked = prefs.getAllowDestructiveTools()
        showCitationsSwitch.isChecked = prefs.getShowCitations()
        hapticButtonsSwitch.isChecked = prefs.getHapticButtons()
        hapticRespondingSwitch.isChecked = prefs.getHapticResponding()

        fun syncPowerToolsBarSwitch() {
            powerToolsBarSwitch.isChecked = (viewModel.isExtendedDockEnabled.value ?: false) ||
                (viewModel.isExtendedTopBarEnabled.value ?: false)
        }
        syncPowerToolsBarSwitch()

        apiKeyButton.setOnClickListener {
            SaveApiDialogFragment().show(childFragmentManager, "SaveApiDialogFragment")
        }
        toolsButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, ToolsFragment())
                .addToBackStack(null)
                .commit()
        }
        braveApiKeyButton.setOnClickListener {
            SaveBraveApiDialogFragment().show(childFragmentManager, SaveBraveApiDialogFragment.TAG)
        }
        chatMemoryButton.setOnClickListener {
            ChatMemoryDialogFragment().show(childFragmentManager, "ChatMemoryDialogFragment")
        }
        powerToolsBarSwitch.setOnCheckedChangeListener { _, isChecked ->
            val dockEnabled = viewModel.isExtendedDockEnabled.value ?: false
            val topBarEnabled = viewModel.isExtendedTopBarEnabled.value ?: false
            if (dockEnabled != isChecked) viewModel.toggleExtendedDock()
            if (topBarEnabled != isChecked) viewModel.toggleExtendedTopBar()
        }
        viewModel.isExtendedDockEnabled.observe(viewLifecycleOwner) { syncPowerToolsBarSwitch() }
        viewModel.isExtendedTopBarEnabled.observe(viewLifecycleOwner) { syncPowerToolsBarSwitch() }
        copyOrDismissSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.saveUseCopyButton2(isChecked)
        }
        animateBarOnErrorSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.saveAnimateBarOnError(isChecked)
        }
        presetsExtendedSwitch.setOnCheckedChangeListener { _, _ ->
            viewModel.togglePresetsExtended()
        }
        viewModel.isPresetsExtendedEnabled.observe(viewLifecycleOwner) { enabled ->
            presetsExtendedSwitch.isChecked = enabled
        }
        viewModel.isVolumeScrollEnabled.observe(viewLifecycleOwner) { enabled ->
            volumeScrollSwitch.isChecked = enabled
        }
        openRouterTransformsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.saveOpenRouterTransformsEnabled(isChecked)
        }
        themeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    R.id.btnThemeLight -> SharedPreferencesHelper.THEME_LIGHT
                    R.id.btnThemeDark -> SharedPreferencesHelper.THEME_DARK
                    else -> SharedPreferencesHelper.THEME_SYSTEM
                }
                prefs.saveThemeMode(mode)
                val appMode = when (mode) {
                    SharedPreferencesHelper.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    SharedPreferencesHelper.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                if (AppCompatDelegate.getDefaultNightMode() != appMode) {
                    AppCompatDelegate.setDefaultNightMode(appMode)
                    requireActivity().recreate()
                }
            }
        }
        showCitationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.saveShowCitations(isChecked)
        }
        copyOrOpenSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.saveUseCopyButton(isChecked)
        }
        notificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.saveNotiPreference(isChecked)
        }
        autoDisableWebSearchSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.saveDisableWebSearchAfterSend(isChecked)
        }
        expandableInputSwitch.setOnCheckedChangeListener { _, _ ->
            viewModel.toggleExpandableInput()
        }
        inferenceParamsButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, InferenceParametersFragment())
                .addToBackStack(null)
                .commit()
        }
        scrollProgressSwitch.setOnCheckedChangeListener { _, _ -> viewModel.toggleScrollProgress() }
        viewModel.isScrollProgressEnabled.observe(viewLifecycleOwner) { enabled ->
            scrollProgressSwitch.isChecked = enabled
        }
        creditsButton.setOnClickListener {
            if (viewModel.activeChatApiKey.isBlank()) {
                AppToast.makeText(requireContext(), "API Key is not set.", AppToast.LENGTH_SHORT).show()
            } else {
                parentFragmentManager.popBackStack()
                viewModel.checkRemainingCredits()
            }
        }
        scrollButtonsSwitch.setOnCheckedChangeListener { _, _ ->
            viewModel.toggleScrollers()
        }
        volumeScrollSwitch.setOnCheckedChangeListener { _, _ ->
            viewModel.toggleVolumeScroll()
        }
        keepScreenOnSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.saveKeepScreenOnPreference(isChecked)
            val window = requireActivity().window
            if (isChecked) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        helpButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, HelpFragment())
                .addToBackStack(null)
                .commit()
        }
        importHistoryButton.setOnClickListener { importChats() }
        exportHistoryButton.setOnClickListener { exportChats() }
        licensesButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, LicenseListFragment())
                .addToBackStack(null)
                .commit()
        }
        promptsButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, PromptLibraryFragment())
                .addToBackStack(null)
                .commit()
        }
        presetsButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, PresetsListFragment())
                .addToBackStack(null)
                .commit()
        }
        systemMessagesButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, SystemMessageLibraryFragment())
                .addToBackStack(null)
                .commit()
        }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.rpGradationButton).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, RpHubFragment.newInstance())
                .addToBackStack(RpHubFragment.BACK_STACK_TAG)
                .commit()
        }
        advancedReasoningButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, AdvancedReasoningFragment())
                .addToBackStack(null)
                .commit()
        }
        maxTokensButton.setOnClickListener {
            MaxTokensDialogFragment().show(childFragmentManager, "MaxTokensDialogFragment")
        }
        lanButton.setOnClickListener {
            SaveLANDialogFragment().show(childFragmentManager, SaveLANDialogFragment.TAG)
        }
        trustSelfSignedLanSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.saveTrustSelfSignedLan(isChecked)
            viewModel.refreshLanHttpClient()
        }
        allowDestructiveToolsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.saveAllowDestructiveTools(isChecked)
        }
        timeoutButton.setOnClickListener {
            TimeoutDialogFragment().show(childFragmentManager, TimeoutDialogFragment.TAG)
        }
        biometricsSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val bm = BiometricManager.from(requireContext())
                when (bm.canAuthenticate(BIOMETRIC_STRONG)) {
                    BiometricManager.BIOMETRIC_SUCCESS -> prefs.saveBiometricEnabled(true)
                    else -> {
                        biometricsSwitch.isChecked = false
                        AppToast.makeText(requireContext(), "No biometrics available", AppToast.LENGTH_SHORT).show()
                    }
                }
            } else {
                prefs.saveBiometricEnabled(false)
            }
        }
        hapticButtonsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.saveHapticButtons(isChecked)
        }
        hapticRespondingSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.saveHapticResponding(isChecked)
        }

        listOf(
            R.id.scrollButtonsSwitch,
            R.id.volumeScrollSwitch,
            R.id.expandableInputSwitch,
            R.id.scrollProgressSwitch,
            R.id.keepScreenOnSwitch,
            R.id.biometricsSwitch,
            R.id.copyOropenSwitch,
            R.id.powerToolsBarSwitch,
            R.id.presetsExtendedSwitch,
            R.id.notificationsSwitch,
            R.id.autoDisableWebSearchSwitch,
            R.id.showCitationsSwitch,
            R.id.copyOrdismissSwitch,
            R.id.openRouterTransformsSwitch,
            R.id.trustSelfSignedLanSwitch,
            R.id.allowDestructiveToolsSwitch,
            R.id.animateBarOnErrorSwitch,
            R.id.hapticButtonsSwitch,
            R.id.hapticRespondingSwitch
        ).forEach { id ->
            view.findViewById<SwitchCompat>(id)?.applyGrokionSwitchStyle()
        }
    }

    private fun applySectionVisibility(view: View, section: String) {
        val sectionRoots = mapOf(
            SECTION_APPEARANCE to R.id.appearanceSection,
            SECTION_HAPTICS to R.id.hapticsSection,
            SECTION_MODELS to R.id.modelsSection,
            SECTION_ADVANCED to R.id.advancedSection,
            SECTION_DATA to R.id.dataSection
        )
        sectionRoots.forEach { (key, rootId) ->
            view.findViewById<View>(rootId)?.visibility =
                if (key == section) View.VISIBLE else View.GONE
        }
    }

    private fun exportChats() {
        val sessions = savedChatsViewModel.allSessions.value.orEmpty()
        if (sessions.isEmpty()) {
            AppToast.makeText(requireContext(), "No chats to export.", AppToast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "openchat_backup.json")
        }
        exportChatsLauncher.launch(intent)
    }

    private fun importChats() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        importChatsLauncher.launch(intent)
    }

    companion object {
        const val ARG_SECTION = "section"
        const val SECTION_APPEARANCE = "appearance"
        const val SECTION_VOICE = "voice"
        const val SECTION_HAPTICS = "haptics"
        const val SECTION_MODELS = "models"
        const val SECTION_ADVANCED = "advanced"
        const val SECTION_DATA = "data"

        fun newInstance(section: String): SettingsDetailFragment =
            SettingsDetailFragment().apply {
                arguments = bundleOf(ARG_SECTION to section)
            }
    }
}
