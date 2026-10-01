package io.github.stardomains3.oxproxion

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Who you are in roleplay: a portrait, a name and a description, with every saved persona listed
 * underneath so switching is one tap. Saving a named persona keeps it in that list; there is no
 * separate preset step.
 */
class RpPersonaFragment : Fragment() {

    private val chatViewModel: ChatViewModel by activityViewModels { AppViewModelFactory(requireActivity().application) }
    private lateinit var prefs: SharedPreferencesHelper
    private var greetingGen = 0
    private lateinit var nameInput: TextInputEditText
    private lateinit var aboutInput: TextInputEditText
    private lateinit var list: LinearLayout
    private val rows = mutableListOf<Pair<RpPersonaPreset, View>>()

    private val name get() = nameInput.text?.toString()?.trim().orEmpty()
    private val about get() = aboutInput.text?.toString().orEmpty()
    /** Portrait file name in the editor; written to storage on pick, kept on Save. */
    private var photo: String? = null

    private val pickImage = AvatarPicker(this) { uri ->
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { RpAvatarStorage.savePersonaFromUri(ctx, uri) }
            if (saved == null) {
                if (isAdded) GlassNotice.show(requireContext(), getString(R.string.rp_avatar_save_failed))
            } else {
                photo = saved
                showPortrait()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_rp_persona, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = SharedPreferencesHelper(requireContext())
        nameInput = view.findViewById(R.id.rpPersonaNameInput)
        aboutInput = view.findViewById(R.id.rpPersonaInput)
        list = view.findViewById(R.id.rpPersonaList)
        var baseline = RpPersonaPreset(prefs.getRpPersonaName(), prefs.getRpPersona(), prefs.getRpPersonaPhoto())
        nameInput.setText(baseline.name)
        aboutInput.setText(baseline.description)
        photo = baseline.photo

        renderList()
        val enabledCard = view.findViewById<View>(R.id.rpPersonaEnabledCard)
        val enabledSwitch = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.rpPersonaEnabledSwitch)
        // Turning it off only makes sense once there is one to turn off.
        val hasPersona = baseline.name.isNotBlank() || baseline.description.isNotBlank() || prefs.getRpPersonaPresets().isNotEmpty()
        enabledCard.visibility = if (hasPersona) View.VISIBLE else View.GONE
        enabledSwitch.isChecked = prefs.isRpPersonaEnabled()
        enabledSwitch.setOnCheckedChangeListener { _, on ->
            val before = idleGreeting()
            prefs.setRpPersonaEnabled(on)
            refreshIdleGreeting(before)
        }
        nameInput.doAfterTextChanged { showPortrait() }
        aboutInput.doAfterTextChanged { markInUse() }
        showPortrait()

        val frame = view.findViewById<android.widget.FrameLayout>(R.id.rpPersonaAvatarFrame)
        val pick = View.OnClickListener { pickImage.launch(frame) }
        frame.setOnClickListener(pick)
        TouchTargets.expand(frame, view.findViewById(R.id.rpPersonaCameraBadge))
        view.findViewById<View>(R.id.rpPersonaPickPhoto).setOnClickListener(pick)
        view.findViewById<View>(R.id.rpPersonaRemovePhoto).setOnClickListener {
            photo = null
            showPortrait()
        }

        fun navigateUp() {
            if (current() == baseline.copy(name = baseline.name.trim())) {
                parentFragmentManager.popBackStack()
                return
            }
            GrokConfirmDialog.show(
                fragment = this,
                title = getString(R.string.rp_discard_edits_title),
                message = getString(R.string.rp_discard_edits_body),
                confirmText = getString(R.string.rp_discard_edits_confirm),
                onConfirm = {
                    // A portrait picked in this session is written to storage on pick; drop it with the edits.
                    photo = baseline.photo
                    prune()
                    parentFragmentManager.popBackStack()
                },
                destructive = true
            )
        }
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { navigateUp() }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = navigateUp()
            }
        )

        view.findViewById<MaterialButton>(R.id.saveRpPersonaButton).setOnClickListener {
            val before = idleGreeting()
            val persona = current()
            prefs.saveRpPersona(persona.description)
            prefs.saveRpPersonaName(persona.name)
            prefs.saveRpPersonaPhoto(persona.photo)
            // Saving a persona is choosing to be it, so it comes back on if it was off.
            prefs.setRpPersonaEnabled(true)
            baseline = persona
            val droppedOldest = if (persona.name.isNotEmpty()) keep(persona) else false
            prune()
            // The page closes on Save, so a plain "Saved" adds nothing; losing a persona to the cap does.
            if (droppedOldest) GlassNotice.show(requireContext(), getString(R.string.rp_persona_preset_cap))
            refreshIdleGreeting(before)
            parentFragmentManager.popBackStack()
        }
    }

    private fun current() = RpPersonaPreset(name, about, photo)

    /** The open greeting expanded with the persona as it is right now, before a change is saved. */
    private fun idleGreeting(): String? {
        if (!chatViewModel.isRpMode() || prefs.isRpLlmMode()) return null
        val character = chatViewModel.activeRpCharacter.value ?: return null
        return RpChatDelegate(chatViewModel.getRpRepository(), prefs).greetingMessage(character)
    }

    /**
     * An idle thread whose bubble is still the card's line picks up the new name. A rewritten
     * opening stays. A later change cancels the effect of an earlier one.
     */
    private fun refreshIdleGreeting(before: String?) {
        val captured = before ?: return
        val gen = ++greetingGen
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.syncActiveCharacterGreetingIfIdle(
                RpGreetingSync.Refresh(captured, templateChanged = false)
            ) { gen == greetingGen }
        }
    }

    /** The photo when there is one, else the name's initial, else a silhouette; the list's check follows. */
    private fun showPortrait() {
        val v = view ?: return
        val image = v.findViewById<ImageView>(R.id.rpPersonaAvatar)
        val monogram = v.findViewById<TextView>(R.id.rpPersonaMonogram)
        val file = photo?.let { RpAvatarStorage.personaFile(requireContext(), it) }?.takeIf { it.isFile }
        RpAvatars.bindModel(image, monogram, file, name)
        v.findViewById<View>(R.id.rpPersonaSilhouette).visibility =
            if (file == null && RpAvatars.initial(name).isEmpty()) View.VISIBLE else View.GONE
        v.findViewById<MaterialButton>(R.id.rpPersonaPickPhoto)
            .setText(if (file != null) R.string.rp_ui_change_photo else R.string.rp_ui_add_photo)
        v.findViewById<View>(R.id.rpPersonaRemovePhoto).visibility = if (file != null) View.VISIBLE else View.GONE
        markInUse()
    }

    /** Updates the saved persona with this name in place, or adds it on top. True when the cap dropped one. */
    private fun keep(persona: RpPersonaPreset): Boolean {
        val presets = prefs.getRpPersonaPresets().toMutableList()
        val at = presets.indexOfFirst { it.name.equals(persona.name, ignoreCase = true) }
        if (at >= 0) {
            presets[at] = persona
            prefs.saveRpPersonaPresets(presets)
            return false
        }
        presets.add(0, persona)
        prefs.saveRpPersonaPresets(presets.take(MAX))
        return presets.size > MAX
    }

    /** Drops portraits that neither you, any saved persona, nor the editor uses any more. */
    private fun prune() {
        val keep = (prefs.getRpPersonaPresets().mapNotNull { it.photo } + listOfNotNull(prefs.getRpPersonaPhoto(), photo)).toSet()
        RpAvatarStorage.prunePersonas(requireContext(), keep)
    }

    private fun renderList() {
        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)
        val d = resources.displayMetrics.density
        list.removeAllViews()
        fun divider(startDp: Int) = list.addView(View(ctx).apply {
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.xai_hairline))
        }, LinearLayout.LayoutParams(-1, (1 * d).toInt().coerceAtLeast(1)).apply { marginStart = (startDp * d).toInt() })

        rows.clear()
        for (preset in prefs.getRpPersonaPresets()) {
            val row = inflater.inflate(R.layout.item_rp_persona, list, false)
            val file = preset.photo?.let { RpAvatarStorage.personaFile(ctx, it) }?.takeIf { it.isFile }
            RpAvatars.bindModel(
                row.findViewById(R.id.rpPersonaRowPhoto), row.findViewById(R.id.rpPersonaRowInitial), file, preset.name
            )
            row.findViewById<TextView>(R.id.rpPersonaRowName).text = preset.name
            val firstLine = preset.description.trim().lineSequence().firstOrNull().orEmpty()
            row.findViewById<TextView>(R.id.rpPersonaRowAbout).apply {
                text = firstLine
                visibility = if (firstLine.isEmpty()) View.GONE else View.VISIBLE
            }
            rows += preset to row
            row.setOnClickListener {
                photo = preset.photo
                nameInput.setText(preset.name)
                aboutInput.setText(preset.description)
                showPortrait()
            }
            row.findViewById<View>(R.id.rpPersonaRowDelete).setOnClickListener { confirmDelete(preset) }
            list.addView(row)
            divider(68)
        }

        val add = inflater.inflate(R.layout.item_rp_persona_new, list, false)
        add.setOnClickListener {
            photo = null
            nameInput.setText("")
            aboutInput.setText("")
            showPortrait()
            nameInput.requestFocus()
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(nameInput, InputMethodManager.SHOW_IMPLICIT)
        }
        list.addView(add)
        // With nothing saved the fields are already the new persona, so the section would be noise.
        val any = if (rows.isEmpty()) View.GONE else View.VISIBLE
        list.visibility = any
        view?.findViewById<View>(R.id.rpPersonaListHeader)?.visibility = any
        markInUse()
    }

    /** Checks the saved persona that matches the editor, so you can see which one you are. */
    private fun markInUse() {
        for ((preset, row) in rows) {
            val inUse = preset.name.trim() == name && preset.description.trim() == about.trim() && preset.photo == photo
            row.findViewById<View>(R.id.rpPersonaRowCheck).visibility = if (inUse) View.VISIBLE else View.GONE
            row.contentDescription = listOfNotNull(
                preset.name,
                getString(R.string.rp_persona_in_use).takeIf { inUse },
                row.findViewById<TextView>(R.id.rpPersonaRowAbout).text.toString().ifEmpty { null }
            ).joinToString(", ")
        }
    }

    private fun confirmDelete(target: RpPersonaPreset) {
        GrokConfirmDialog.show(
            fragment = this,
            title = getString(R.string.rp_persona_delete_preset),
            message = target.name,
            confirmText = getString(R.string.rp_menu_delete),
            onConfirm = {
                prefs.saveRpPersonaPresets(prefs.getRpPersonaPresets().filterNot { it.name == target.name })
                prune()
                renderList()
            },
            destructive = true
        )
    }

    companion object {
        private const val MAX = 12

        fun newInstance() = RpPersonaFragment()
    }
}
