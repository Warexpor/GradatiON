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
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class SettingsDetailFragment : Fragment(R.layout.fragment_settings_detail) {

    private val section: String
        get() = requireArguments().getString(ARG_SECTION) ?: SECTION_APPEARANCE

    private val savedChatsViewModel: SavedChatsViewModel by viewModels { AppViewModelFactory(requireActivity().application) }
    private val viewModel: ChatViewModel by activityViewModels { AppViewModelFactory(requireActivity().application) }

    private var onPhotoPicked: ((Boolean) -> Unit)? = null
    private val pickBackgroundPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val ctx = context ?: return@registerForActivityResult
        if (uri == null) { onPhotoPicked?.invoke(false); return@registerForActivityResult }
        BackgroundPhoto.import(ctx, uri) { ok ->
            // The picker can outlive this fragment (rotation, process death), and then the UI
            // callback is gone: the preference is the part that must not be lost with it.
            if (ok) SharedPreferencesHelper(ctx).saveBackgroundStyle(AmbientBackgroundView.Style.PHOTO.key)
            else GlassNotice.show(ctx, ctx.getString(R.string.settings_background_photo_failed))
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
                        GlassNotice.show(requireContext(), getString(R.string.notice_chats_exported))
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
                                        GlassNotice.show(requireContext(), getString(R.string.notice_chats_imported))
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

        inflateSection(view)
        bindValues(view)
        // Every dialog opened from a row edits what the row shows, so refresh when one closes.
        childFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
                if (f is DialogFragment) this@SettingsDetailFragment.view?.let { bindValues(it) }
            }
        }, false)
    }

    override fun onDestroyView() {
        onPhotoPicked = null
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // Back from a sub-screen (tools, presets, LAN models...) that may have changed a value.
        if (!hidden) view?.let { bindValues(it) }
    }

    /**
     * The rows that open a dialog show what is set in it, so the list reads without a tap. Only
     * the open section is bound: the key checks decrypt through the Keystore.
     */
    private fun bindValues(view: View) {
        val ctx = requireContext()
        val prefs = SharedPreferencesHelper(ctx)
        val notSet = getString(R.string.settings_value_not_set)
        val saved = getString(R.string.settings_value_saved)
        fun show(rowId: Int, valueId: Int, value: String) {
            val valueView = view.findViewById<TextView>(valueId) ?: return
            valueView.text = value
            // The value floats over the button, so a screen reader would only hear its label.
            view.findViewById<TextView>(rowId)?.let {
                it.contentDescription = getString(R.string.cd_settings_row_value, it.text, value)
            }
        }
        when (section) {
            SECTION_MODELS -> {
                val endpoint = prefs.getLanEndpoint()?.takeIf { it.isNotBlank() }
                val host = endpoint?.let { runCatching { java.net.URI(it).authority }.getOrNull() ?: it }
                show(R.id.lanButton, R.id.lanValue, host ?: notSet)
                show(R.id.apiKeyButton, R.id.apiKeyValue,
                    if (prefs.getApiKeyFromPrefs("openrouter_api_key").isNotBlank()) saved else notSet)
                show(R.id.braveApiKeyButton, R.id.braveApiKeyValue,
                    if (prefs.getApiKeyFromPrefs("brave_search_api_key").isNotBlank()) saved else notSet)
            }
            SECTION_ADVANCED -> {
                show(R.id.maxTokensButton, R.id.maxTokensValue, prefs.getMaxTokens())
                show(R.id.timeoutButton, R.id.timeoutValue,
                    getString(R.string.settings_value_minutes, prefs.getTimeoutMinutes()))
                view.findViewById<com.google.android.material.button.MaterialButton>(R.id.chatMemoryButton)
                    ?.text = ChatMemoryDialogFragment.label(ctx, prefs.getChatMemoryCount())
            }
        }
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

    /**
     * Inflates only the open section into the container and binds only its views: the sections
     * are separate layouts so the five that are not shown (previews, shader tiles, ~60 rows)
     * are never built.
     */
    private fun inflateSection(view: View) {
        val container = view.findViewById<android.view.ViewGroup>(R.id.settingsSectionContainer)
        val layout = when (section) {
            SECTION_APPEARANCE -> R.layout.fragment_settings_section_appearance
            SECTION_VOICE -> R.layout.fragment_settings_section_voice
            SECTION_HAPTICS -> R.layout.fragment_settings_section_haptics
            SECTION_MODELS -> R.layout.fragment_settings_section_models
            SECTION_ADVANCED -> R.layout.fragment_settings_section_advanced
            SECTION_DATA -> R.layout.fragment_settings_section_data
            else -> return
        }
        layoutInflater.inflate(layout, container, true)
        val prefs = SharedPreferencesHelper(requireContext())
        when (section) {
            SECTION_APPEARANCE -> {
                bindThemePicker(view, prefs)
                bindBackgroundPicker(view, prefs)
                bindChatTextSize(view, prefs)
                bindChatMark(view, prefs)
            }
            SECTION_VOICE -> bindVoice(view, prefs)
            SECTION_HAPTICS -> bindHaptics(view, prefs)
            SECTION_MODELS -> bindModels(view, prefs)
            SECTION_ADVANCED -> bindAdvanced(view, prefs)
            SECTION_DATA -> bindData(view, prefs)
        }
    }

    /** Opens a sub-screen over this one; Back returns here. */
    private fun open(screen: Fragment, backStackTag: String? = null) {
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .hide(this)
            .add(R.id.fragment_container, screen)
            .addToBackStack(backStackTag)
            .commit()
    }

    /** Sets the switch, wires [onChange] if given, then gives it the Grokion look (in that order). */
    private fun bindSwitch(view: View, id: Int, checked: Boolean, onChange: ((Boolean) -> Unit)? = null): SwitchCompat =
        view.findViewById<SwitchCompat>(id).apply {
            isChecked = checked
            if (onChange != null) setOnCheckedChangeListener { _, on -> onChange(on) }
            applyGrokionSwitchStyle()
        }

    private fun bindHaptics(view: View, prefs: SharedPreferencesHelper) {
        bindSwitch(view, R.id.hapticButtonsSwitch, prefs.getHapticButtons()) { prefs.saveHapticButtons(it) }
        bindSwitch(view, R.id.hapticRespondingSwitch, prefs.getHapticResponding()) { prefs.saveHapticResponding(it) }
    }

    private fun bindModels(view: View, prefs: SharedPreferencesHelper) {
        view.findViewById<View>(R.id.apiKeyButton).setOnClickListener {
            SaveApiDialogFragment().show(childFragmentManager, "SaveApiDialogFragment")
        }
        view.findViewById<View>(R.id.braveApiKeyButton).setOnClickListener {
            SaveBraveApiDialogFragment().show(childFragmentManager, SaveBraveApiDialogFragment.TAG)
        }
        view.findViewById<View>(R.id.lanButton).setOnClickListener {
            SaveLANDialogFragment().show(childFragmentManager, SaveLANDialogFragment.TAG)
        }
        view.findViewById<View>(R.id.creditsButton).setOnClickListener {
            if (viewModel.activeChatApiKey.isBlank()) {
                GlassNotice.show(requireContext(), getString(R.string.toast_api_key_missing))
            } else {
                parentFragmentManager.popBackStack()
                viewModel.checkRemainingCredits()
            }
        }
        bindSwitch(view, R.id.trustSelfSignedLanSwitch, prefs.getTrustSelfSignedLan()) {
            prefs.saveTrustSelfSignedLan(it)
            viewModel.refreshLanHttpClient()
        }
    }

    private fun bindAdvanced(view: View, prefs: SharedPreferencesHelper) {
        fun button(id: Int, onClick: () -> Unit) = view.findViewById<View>(id).setOnClickListener { onClick() }
        button(R.id.toolsButton) { open(ToolsFragment()) }
        button(R.id.chatMemoryButton) {
            ChatMemoryDialogFragment().show(childFragmentManager, "ChatMemoryDialogFragment")
        }
        button(R.id.inferenceParamsButton) { open(InferenceParametersFragment()) }
        button(R.id.promptsButton) { open(PromptLibraryFragment()) }
        button(R.id.presetsButton) { open(PresetsListFragment()) }
        button(R.id.systemMessagesButton) { open(SystemMessageLibraryFragment()) }
        button(R.id.rpGradationButton) { open(RpHubFragment.newInstance(), RpHubFragment.BACK_STACK_TAG) }
        button(R.id.advancedReasoningButton) { open(AdvancedReasoningFragment()) }
        button(R.id.maxTokensButton) {
            MaxTokensDialogFragment().show(childFragmentManager, "MaxTokensDialogFragment")
        }
        button(R.id.timeoutButton) {
            TimeoutDialogFragment().show(childFragmentManager, TimeoutDialogFragment.TAG)
        }

        fun powerToolsOn() = (viewModel.isExtendedDockEnabled.value ?: false) ||
            (viewModel.isExtendedTopBarEnabled.value ?: false)
        val powerTools = bindSwitch(view, R.id.powerToolsBarSwitch, powerToolsOn()) { isChecked ->
            val dockEnabled = viewModel.isExtendedDockEnabled.value ?: false
            val topBarEnabled = viewModel.isExtendedTopBarEnabled.value ?: false
            if (dockEnabled != isChecked) viewModel.toggleExtendedDock()
            if (topBarEnabled != isChecked) viewModel.toggleExtendedTopBar()
        }
        viewModel.isExtendedDockEnabled.observe(viewLifecycleOwner) { powerTools.isChecked = powerToolsOn() }
        viewModel.isExtendedTopBarEnabled.observe(viewLifecycleOwner) { powerTools.isChecked = powerToolsOn() }

        // These follow the view model, which flips its own state: the listener only asks for the toggle.
        bindSwitch(view, R.id.expandableInputSwitch, viewModel.isExpandableInputEnabled.value ?: false) {
            viewModel.toggleExpandableInput()
        }
        bindSwitch(view, R.id.scrollButtonsSwitch, viewModel.isScrollersEnabled.value ?: false) {
            viewModel.toggleScrollers()
        }
        val scrollProgress = bindSwitch(view, R.id.scrollProgressSwitch, viewModel.isScrollProgressEnabled.value ?: false) {
            viewModel.toggleScrollProgress()
        }
        viewModel.isScrollProgressEnabled.observe(viewLifecycleOwner) { scrollProgress.isChecked = it }
        val volumeScroll = bindSwitch(view, R.id.volumeScrollSwitch, viewModel.isVolumeScrollEnabled.value ?: false) {
            viewModel.toggleVolumeScroll()
        }
        viewModel.isVolumeScrollEnabled.observe(viewLifecycleOwner) { volumeScroll.isChecked = it }
        val presetsExtended = bindSwitch(view, R.id.presetsExtendedSwitch, viewModel.isPresetsExtendedEnabled.value ?: false) {
            viewModel.togglePresetsExtended()
        }
        viewModel.isPresetsExtendedEnabled.observe(viewLifecycleOwner) { presetsExtended.isChecked = it }

        bindSwitch(view, R.id.animateBarOnErrorSwitch, prefs.getAnimateBarOnError()) { prefs.saveAnimateBarOnError(it) }
        bindSwitch(view, R.id.showCitationsSwitch, prefs.getShowCitations()) { prefs.saveShowCitations(it) }
        bindSwitch(view, R.id.openRouterTransformsSwitch, prefs.getOpenRouterTransformsEnabled()) {
            prefs.saveOpenRouterTransformsEnabled(it)
        }
        bindSwitch(view, R.id.autoDisableWebSearchSwitch, prefs.getDisableWebSearchAfterSend()) {
            prefs.saveDisableWebSearchAfterSend(it)
        }
    }

    private fun bindData(view: View, prefs: SharedPreferencesHelper) {
        bindSwitch(view, R.id.biometricsSwitch, prefs.getBiometricEnabled()) { isChecked ->
            if (isChecked) {
                val bm = BiometricManager.from(requireContext())
                when (bm.canAuthenticate(BIOMETRIC_STRONG)) {
                    BiometricManager.BIOMETRIC_SUCCESS -> prefs.saveBiometricEnabled(true)
                    else -> {
                        view.findViewById<SwitchCompat>(R.id.biometricsSwitch).isChecked = false
                        GlassNotice.show(requireContext(), getString(R.string.notice_no_biometrics))
                    }
                }
            } else {
                prefs.saveBiometricEnabled(false)
            }
        }
        bindSwitch(view, R.id.notificationsSwitch, prefs.getNotiPreference()) { prefs.saveNotiPreference(it) }
        bindSwitch(view, R.id.keepScreenOnSwitch, prefs.getKeepScreenOnPreference()) { isChecked ->
            prefs.saveKeepScreenOnPreference(isChecked)
            val window = requireActivity().window
            if (isChecked) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        bindSwitch(view, R.id.copyOrdismissSwitch, prefs.getUseCopyButton2()) { prefs.saveUseCopyButton2(it) }
        bindSwitch(view, R.id.copyOropenSwitch, prefs.getUseCopyButton()) { prefs.saveUseCopyButton(it) }
        bindSwitch(view, R.id.allowDestructiveToolsSwitch, prefs.getAllowDestructiveTools()) {
            prefs.saveAllowDestructiveTools(it)
        }
        view.findViewById<View>(R.id.importHistoryButton).setOnClickListener { importChats() }
        view.findViewById<View>(R.id.exportHistoryButton).setOnClickListener { exportChats() }
        view.findViewById<View>(R.id.helpButton).setOnClickListener { open(HelpFragment()) }
        view.findViewById<View>(R.id.licensesButton).setOnClickListener { open(LicenseListFragment()) }
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
        // Set with the view, not on the tap: after a rotation the result lands in this new
        // instance, whose picker has to catch up with the saved style.
        onPhotoPicked = { _ -> select(AmbientBackgroundView.Style.fromKey(prefs.getBackgroundStyle())) }
        fun pickPhoto() {
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
                if (key.isNotEmpty() && !prefs.saveApiKeyKeepingOld(SharedPreferencesHelper.XAI_API_KEY_ALIAS, key)) {
                    GlassNotice.show(ctx, getString(R.string.api_key_save_failed))
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
