package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
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

    private val savedChatsViewModel: SavedChatsViewModel by viewModels { AppViewModelFactory(requireActivity().application) }

    private var onPhotoPicked: ((Boolean) -> Unit)? = null
    private val pickBackgroundPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val ctx = context ?: return@registerForActivityResult
        if (uri == null) { onPhotoPicked?.invoke(false); return@registerForActivityResult }
        BackgroundPhoto.import(ctx, uri) { ok ->
            if (!ok) GlassNotice.show(ctx, getString(R.string.settings_background_photo_failed))
            onPhotoPicked?.invoke(ok)
        }
    }

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
                        GlassNotice.show(requireContext(), getString(R.string.notice_export_chats_failed))
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
                                        GlassNotice.show(requireContext(), importResult.message)
                                }
                            }
                        } else {
                            throw Exception("Failed to read file content.")
                        }
                    } catch (_: Exception) {
                        GlassNotice.show(requireContext(), getString(R.string.notice_import_failed_format))
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
        val viewModel: ChatViewModel by activityViewModels { AppViewModelFactory(requireActivity().application) }

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
        keepScreenOnSwitch.isChecked = prefs.getKeepScreenOnPreference()
        copyOrDismissSwitch.isChecked = prefs.getUseCopyButton2()
        animateBarOnErrorSwitch.isChecked = prefs.getAnimateBarOnError()
        scrollButtonsSwitch.isChecked = viewModel.isScrollersEnabled.value ?: false
        volumeScrollSwitch.isChecked = viewModel.isVolumeScrollEnabled.value ?: false
        expandableInputSwitch.isChecked = viewModel.isExpandableInputEnabled.value ?: false
        presetsExtendedSwitch.isChecked = viewModel.isPresetsExtendedEnabled.value ?: false
        scrollProgressSwitch.isChecked = viewModel.isScrollProgressEnabled.value ?: false
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
                GlassNotice.show(requireContext(), getString(R.string.toast_api_key_missing))
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
                        GlassNotice.show(requireContext(), getString(R.string.notice_no_biometrics))
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
        bindThemePicker(view, prefs)
        bindBackgroundPicker(view, prefs)
        bindVoice(view, prefs)
        bindChatTextSize(view, prefs)
        bindChatMark(view, prefs)

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

    /**
     * Background picker: one live swatch per style (only the chosen one animates, the rest
     * hold a static frame). Every AmbientBackgroundView follows the preference on its own.
     */
    private fun bindBackgroundPicker(view: View, prefs: SharedPreferencesHelper) {
        val picker = view.findViewById<android.widget.LinearLayout>(R.id.backgroundStylePicker) ?: return
        val summary = view.findViewById<android.widget.TextView>(R.id.backgroundStyleSummary)
        val photoOptions = view.findViewById<View>(R.id.backgroundPhotoOptions)
        val chooseButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.backgroundPhotoChoose)
        val ctx = requireContext()
        val choices = listOf(
            AmbientBackgroundView.Style.OFF to (R.string.settings_background_off to R.string.settings_background_off_summary),
            AmbientBackgroundView.Style.DRIFT to (R.string.settings_background_drift to R.string.settings_background_drift_summary),
            AmbientBackgroundView.Style.FLOW to (R.string.settings_background_flow to R.string.settings_background_flow_summary),
            AmbientBackgroundView.Style.ADAPTIVE to (R.string.settings_background_adaptive to R.string.settings_background_adaptive_summary),
            AmbientBackgroundView.Style.PHOTO to (R.string.settings_background_photo to R.string.settings_background_photo_summary)
        )
        val inflater = layoutInflater
        val tiles = choices.map { (style, text) ->
            val tile = inflater.inflate(R.layout.item_background_style, picker, false)
            tile.findViewById<android.widget.TextView>(R.id.backgroundStyleLabel).setText(text.first)
            tile.findViewById<AmbientBackgroundView>(R.id.backgroundStylePreview).styleOverride = style
            tile.contentDescription = getString(R.string.cd_background_style, getString(text.first))
            if (style == AmbientBackgroundView.Style.PHOTO) {
                // Placeholder glyph until a picture is chosen.
                val swatch = tile.findViewById<android.widget.FrameLayout>(R.id.backgroundStyleSwatch)
                val d = resources.displayMetrics.density
                swatch.addView(android.widget.ImageView(ctx).apply {
                    id = R.id.backgroundPhotoPlaceholder
                    setImageResource(R.drawable.ic_imgup)
                    imageTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.xai_mute))
                }, android.widget.FrameLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt(), android.view.Gravity.CENTER))
            }
            picker.addView(tile)
            style to tile
        }
        fun select(style: AmbientBackgroundView.Style) {
            tiles.forEach { (s, tile) ->
                tile.isSelected = s == style
                tile.findViewById<AmbientBackgroundView>(R.id.backgroundStylePreview).animated = s == style
            }
            val photo = style == AmbientBackgroundView.Style.PHOTO
            val has = BackgroundPhoto.hasPhoto(ctx)
            summary?.setText(
                if (photo && !has) R.string.settings_background_photo_empty_summary
                else choices.first { it.first == style }.second.second
            )
            photoOptions?.isVisible = photo
            picker.findViewById<View>(R.id.backgroundPhotoPlaceholder)?.isVisible = !has
            chooseButton?.setText(if (has) R.string.settings_background_photo_change else R.string.settings_background_photo_choose)
        }
        fun pickPhoto() {
            onPhotoPicked = { ok ->
                if (ok) prefs.saveBackgroundStyle(AmbientBackgroundView.Style.PHOTO.key)
                select(AmbientBackgroundView.Style.fromKey(prefs.getBackgroundStyle()))
            }
            pickBackgroundPhoto.launch(
                androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        select(AmbientBackgroundView.Style.fromKey(prefs.getBackgroundStyle()))
        tiles.forEach { (style, tile) ->
            tile.setOnClickListener {
                // Photo with nothing picked yet goes straight to the picker.
                if (style == AmbientBackgroundView.Style.PHOTO && !BackgroundPhoto.hasPhoto(ctx)) {
                    pickPhoto()
                    return@setOnClickListener
                }
                prefs.saveBackgroundStyle(style.key)
                select(style)
            }
        }
        chooseButton?.setOnClickListener { pickPhoto() }
        val mainPrefs = ctx.getSharedPreferences(SharedPreferencesHelper.MAIN_PREFS, android.content.Context.MODE_PRIVATE)
        val opts = BackgroundPhoto.readOptions(mainPrefs)
        listOf(
            R.id.backgroundPhotoBlur to (BackgroundPhoto.KEY_BLUR to opts.blur),
            R.id.backgroundPhotoDim to (BackgroundPhoto.KEY_DIM to opts.dim),
            R.id.backgroundPhotoTint to (BackgroundPhoto.KEY_TINT to opts.tint),
            R.id.backgroundPhotoLiquid to (BackgroundPhoto.KEY_LIQUID to opts.liquid),
            R.id.backgroundPhotoColor to (BackgroundPhoto.KEY_COLOR to opts.color),
        ).forEach { (id, pref) ->
            view.findViewById<SwitchCompat>(id)?.apply {
                applyGrokionSwitchStyle()
                isChecked = pref.second
                setOnCheckedChangeListener { _, on -> BackgroundPhoto.setOption(ctx, pref.first, on) }
            }
        }
    }

    /** One option in a tile picker: its view id, its name, and what drawing it takes. */
    private class Choice<T>(val id: Int, val label: Int, val value: T)

    /**
     * Fills [picker] with one tile per choice, the same tiles as the background picker: [draw]
     * puts a picture of the choice in each [swatchDp]-tall swatch, and the selected tile is
     * ringed. Tapping a tile selects it and calls [onPick]. Returns a function that selects a
     * value without calling [onPick].
     */
    private fun <T> bindTilePicker(
        picker: android.widget.LinearLayout,
        section: Int,
        choices: List<Choice<T>>,
        swatchDp: Int,
        draw: (Choice<T>, android.widget.FrameLayout) -> Unit,
        onSelected: (Choice<T>, Boolean) -> Unit = { _, _ -> },
        onPick: (Choice<T>, View) -> Unit
    ): (T) -> Unit {
        val d = resources.displayMetrics.density
        val tiles = choices.map { choice ->
            layoutInflater.inflate(R.layout.item_choice_tile, picker, false).apply {
                id = choice.id
                findViewById<TextView>(R.id.choiceLabel).setText(choice.label)
                contentDescription = getString(R.string.cd_choice, getString(section), getString(choice.label))
                val swatch = findViewById<android.widget.FrameLayout>(R.id.choiceSwatch)
                swatch.layoutParams.height = (swatchDp * d).toInt()
                draw(choice, swatch)
                picker.addView(this)
            }
        }
        fun select(value: T) = choices.forEachIndexed { i, c ->
            tiles[i].isSelected = c.value == value
            onSelected(c, c.value == value)
        }
        choices.forEachIndexed { i, c ->
            tiles[i].setOnClickListener {
                if (tiles[i].isSelected) return@setOnClickListener
                select(c.value)
                onPick(c, tiles[i])
            }
        }
        return ::select
    }

    /** Appearance > Theme: System, Light or Dark, each tile a tiny chat in that theme. */
    private fun bindThemePicker(view: View, prefs: SharedPreferencesHelper) {
        val picker = view.findViewById<android.widget.LinearLayout>(R.id.themePicker) ?: return
        val choices = listOf(
            Choice(R.id.btnThemeSystem, R.string.settings_theme_system, SharedPreferencesHelper.THEME_SYSTEM),
            Choice(R.id.btnThemeLight, R.string.settings_theme_light, SharedPreferencesHelper.THEME_LIGHT),
            Choice(R.id.btnThemeDark, R.string.settings_theme_dark, SharedPreferencesHelper.THEME_DARK)
        )
        val select = bindTilePicker(picker, R.string.settings_theme, choices, 104,
            draw = { c, swatch ->
                swatch.addView(ThemePreviewView(requireContext()).apply {
                    mode = when (c.value) {
                        SharedPreferencesHelper.THEME_LIGHT -> ThemePreviewView.Mode.LIGHT
                        SharedPreferencesHelper.THEME_DARK -> ThemePreviewView.Mode.DARK
                        else -> ThemePreviewView.Mode.SYSTEM
                    }
                })
            }
        ) { c, tile ->
            prefs.saveThemeMode(c.value)
            val appMode = when (c.value) {
                SharedPreferencesHelper.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                SharedPreferencesHelper.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            // Reveal the new appearance from the tapped tile.
            if (AppCompatDelegate.getDefaultNightMode() != appMode) ThemeTransition.apply(requireActivity(), tile, appMode)
        }
        val saved = prefs.getThemeMode()
        select(if (choices.any { it.value == saved }) saved else SharedPreferencesHelper.THEME_SYSTEM)
    }

    /**
     * Appearance > App icon: off, the flat vector, or the liquid glass mark. The Liquid tile runs
     * the real glass shader, but only while it is the selected one.
     */
    private fun bindChatMark(view: View, prefs: SharedPreferencesHelper) {
        val picker = view.findViewById<android.widget.LinearLayout>(R.id.chatMarkPicker) ?: return
        val choices = listOf(
            Choice(R.id.chatMarkOff, R.string.settings_chat_mark_off, SharedPreferencesHelper.CHAT_MARK_OFF),
            Choice(R.id.chatMarkPlain, R.string.settings_chat_mark_plain, SharedPreferencesHelper.CHAT_MARK_PLAIN),
            Choice(R.id.chatMarkLiquid, R.string.settings_chat_mark_liquid, SharedPreferencesHelper.CHAT_MARK_LIQUID)
        )
        val marks = HashMap<String, LiquidMarkView>()
        val d = resources.displayMetrics.density
        val select = bindTilePicker(picker, R.string.settings_chat_mark, choices, 76,
            draw = { c, swatch ->
                val mark = LiquidMarkView(requireContext()).apply {
                    setImageResource(R.drawable.ic_gradation_mark)
                    androidx.core.widget.ImageViewCompat.setImageTintList(
                        this, android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.xai_mute))
                    )
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    if (c.value == SharedPreferencesHelper.CHAT_MARK_LIQUID) {
                        animated = false
                    } else {
                        markStyle = LiquidMarkView.MarkStyle.PLAIN
                        // Off shows the mark as a faint ghost: the spot it would fill stays empty.
                        if (c.value == SharedPreferencesHelper.CHAT_MARK_OFF) alpha = 0.16f
                    }
                }
                marks[c.value] = mark
                swatch.addView(mark, android.widget.FrameLayout.LayoutParams(
                    (40 * d).toInt(), (40 * d).toInt(), android.view.Gravity.CENTER
                ))
            },
            onSelected = { c, on -> if (c.value == SharedPreferencesHelper.CHAT_MARK_LIQUID) marks[c.value]?.animated = on }
        ) { c, _ -> prefs.saveChatMarkStyle(c.value) }
        val saved = prefs.getChatMarkStyle()
        select(if (choices.any { it.value == saved }) saved else SharedPreferencesHelper.CHAT_MARK_LIQUID)
    }

    /** Appearance > Chat text: four presets over the chat scale, each tile an "Aa" at that size. */
    private fun bindChatTextSize(view: View, prefs: SharedPreferencesHelper) {
        val picker = view.findViewById<android.widget.LinearLayout>(R.id.chatTextSizePicker) ?: return
        val choices = listOf(
            Choice(R.id.chatTextS, R.string.text_size_s, 90),
            Choice(R.id.chatTextM, R.string.text_size_m, 100),
            Choice(R.id.chatTextL, R.string.text_size_l, 115),
            Choice(R.id.chatTextXL, R.string.text_size_xl, 130)
        )
        val select = bindTilePicker(picker, R.string.settings_chat_text_size, choices, 64,
            draw = { c, swatch ->
                swatch.addView(TextView(requireContext()).apply {
                    setText(R.string.text_size_sample)
                    textSize = SAMPLE_TEXT_SP * c.value / 100f
                    setTextColor(requireContext().getColor(R.color.xai_ink))
                    gravity = android.view.Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
            }
        ) { c, _ -> prefs.saveFontSizeCh(c.value) }
        val current = prefs.getFontSizeCh()
        select(choices.minByOrNull { kotlin.math.abs(it.value - current) }!!.value)
    }

    /** Settings > Voice: on/off, which engine turns speech into text, and the model/key for Cloud/Grok/Local. */
    private fun bindVoice(view: View, prefs: SharedPreferencesHelper) {
        val ctx = requireContext()
        val enabled = view.findViewById<SwitchCompat>(R.id.voiceEnabledSwitch)
        val toggle = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.voiceEngineToggle)
        val engineGroup = view.findViewById<View>(R.id.voiceEngineGroup)
        val summary = view.findViewById<TextView>(R.id.voiceEngineSummary)
        val modelGroup = view.findViewById<View>(R.id.voiceModelGroup)
        val modelValue = view.findViewById<TextView>(R.id.voiceModelValue)
        val grokKeyGroup = view.findViewById<View>(R.id.voiceGrokKeyGroup)
        val grokKeyValue = view.findViewById<TextView>(R.id.voiceGrokKeyValue)
        val ids = mapOf(
            VoiceEngine.DEVICE to R.id.voiceEnginePhone,
            VoiceEngine.CLOUD to R.id.voiceEngineCloud,
            VoiceEngine.GROK to R.id.voiceEngineGrok,
            VoiceEngine.LAN to R.id.voiceEngineLocal,
        )
        val deviceOk = VoiceInput.deviceAvailable(ctx)

        fun render() {
            val engine = VoiceEngine.fromKey(prefs.getVoiceInputProvider())
            val on = engine != VoiceEngine.OFF
            engineGroup.isVisible = on
            summary.isVisible = on
            val shown = if (on) engine else VoiceEngine.fromKey(null)
            summary.setText(
                when (shown) {
                    VoiceEngine.CLOUD -> R.string.voice_engine_cloud_hint
                    VoiceEngine.GROK -> R.string.voice_engine_grok_hint
                    VoiceEngine.LAN -> R.string.voice_engine_local_hint
                    else -> when {
                        !deviceOk -> R.string.voice_engine_phone_missing
                        VoiceInput.onDeviceAvailable(ctx) -> R.string.voice_engine_phone_ondevice
                        else -> R.string.voice_engine_phone_hint
                    }
                }
            )
            grokKeyGroup.isVisible = on && engine == VoiceEngine.GROK
            val hasXai = prefs.getApiKeyFromPrefs(SharedPreferencesHelper.XAI_API_KEY_ALIAS).isNotBlank()
            grokKeyValue.text = getString(
                if (hasXai) R.string.voice_grok_key_saved else R.string.voice_grok_key_unset
            )
            // OpenRouter/Local model; Phone without a recognizer also needs a Cloud model.
            modelGroup.isVisible = on && (
                engine == VoiceEngine.CLOUD ||
                    engine == VoiceEngine.LAN ||
                    (engine == VoiceEngine.DEVICE && !deviceOk)
                )
            modelValue.text = prefs.getVoiceInputModel().ifBlank { getString(R.string.voice_model_unset) }
        }

        val current = VoiceEngine.fromKey(prefs.getVoiceInputProvider())
        enabled.isChecked = current != VoiceEngine.OFF
        toggle.check(ids[current] ?: R.id.voiceEnginePhone)
        render()

        fun pickedEngine() = ids.entries.firstOrNull { it.value == toggle.checkedButtonId }?.key ?: VoiceEngine.DEVICE
        enabled.setOnCheckedChangeListener { _, on ->
            prefs.setVoiceInputProvider(if (on) pickedEngine().key else VoiceEngine.OFF.key)
            render()
        }
        toggle.addOnButtonCheckedListener { _, _, isChecked ->
            if (!isChecked || !enabled.isChecked) return@addOnButtonCheckedListener
            prefs.setVoiceInputProvider(pickedEngine().key)
            render()
        }
        view.findViewById<View>(R.id.voiceGrokKeyRow).setOnClickListener {
            GrokInputDialog.show(
                fragment = this,
                title = getString(R.string.voice_grok_key),
                hint = getString(R.string.voice_grok_key_hint),
                initialText = "",
                confirmText = getString(R.string.action_save),
            ) { text ->
                val key = text.trim()
                if (key.isNotEmpty()) {
                    prefs.saveApiKey(SharedPreferencesHelper.XAI_API_KEY_ALIAS, key)
                }
                render()
            }
        }
        view.findViewById<View>(R.id.voiceModelRow).setOnClickListener {
            GrokInputDialog.show(
                fragment = this,
                title = getString(R.string.voice_model),
                hint = getString(R.string.voice_model_hint),
                initialText = prefs.getVoiceInputModel(),
                confirmText = getString(R.string.action_save),
            ) { text ->
                prefs.setVoiceInputModel(text.trim())
                render()
            }
        }
    }

    private fun applySectionVisibility(view: View, section: String) {
        val sectionRoots = mapOf(
            SECTION_APPEARANCE to R.id.appearanceSection,
            SECTION_VOICE to R.id.voiceSection,
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
            GlassNotice.show(requireContext(), getString(R.string.notice_no_chats_export))
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
        /** Size of the Chat text tiles' "Aa" at 100%; the tiles scale it like the chat. */
        private const val SAMPLE_TEXT_SP = 18f
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
