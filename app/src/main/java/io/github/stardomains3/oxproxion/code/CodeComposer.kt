package io.github.stardomains3.oxproxion.code

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import io.github.stardomains3.oxproxion.AppViewModelFactory
import io.github.stardomains3.oxproxion.ChatViewModel
import io.github.stardomains3.oxproxion.GlassBackdropLayout
import io.github.stardomains3.oxproxion.GlassLinearLayout
import io.github.stardomains3.oxproxion.PickerPopover
import io.github.stardomains3.oxproxion.R
import io.github.stardomains3.oxproxion.VoiceDictation

/**
 * Binds `view_code_composer`: the glass capsule with the prompt field, optional image attach
 * chips, the agent / folder / model / approvals pills and the send button (which turns into stop
 * while the agent works). Home uses agent/folder/(optional model)/approvals to start a session;
 * a session shows approvals and an optional read-only model pill.
 */
class CodeComposer(
    val root: GlassLinearLayout,
    private val popoverHost: FrameLayout,
    backdrop: GlassBackdropLayout,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val context: Context = root.context
    val input: EditText = root.findViewById(R.id.codeComposerInput)
    val agentPill: TextView = root.findViewById(R.id.codeComposerAgent)
    val folderPill: TextView = root.findViewById(R.id.codeComposerFolder)
    val modelPill: TextView = root.findViewById(R.id.codeComposerModel)
    val permissionPill: TextView = root.findViewById(R.id.codeComposerPermission)
    private val send: MaterialButton = root.findViewById(R.id.codeComposerSend)
    private val attach: MaterialButton = root.findViewById(R.id.codeComposerAttach)
    private val attachStrip: HorizontalScrollView = root.findViewById(R.id.codeComposerAttachStrip)
    private val attachChips: LinearLayout = root.findViewById(R.id.codeComposerAttachChips)
    private val mic: MaterialButton = root.findViewById(R.id.codeComposerMic)
    private var dictation: VoiceDictation? = null
    private var popover: PickerPopover? = null
    private val backdropRef = backdrop

    private val pendingAttachments = ArrayList<PromptAttachment>()

    /** Text + image attachments. Cleared by the caller via [clear] after a successful send. */
    var onSend: ((text: String, attachments: List<PromptAttachment>) -> Unit)? = null
    var onStop: (() -> Unit)? = null
    /** Opens the image picker (registered on the hosting Fragment). */
    var onAttachClick: (() -> Unit)? = null

    /** While true the button stops the agent instead of sending. */
    var running: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshSend()
        }

    /** Slash commands from the session's latest `available_commands_update`. */
    var availableCommands: List<AvailableCommand> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            refreshSlashPicker()
        }

    private var slashPopover: PickerPopover? = null

    init {
        root.glass.source = backdrop
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                refreshSend()
                refreshSlashPicker()
            }
        })
        attach.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            onAttachClick?.invoke()
        }
        send.setOnClickListener {
            // While the agent is running, keep Stop reachable even if the draft has text
            // (do not hide Stop behind Send).
            if (running) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                onStop?.invoke()
                return@setOnClickListener
            }
            dictation?.finishNow()
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty() && pendingAttachments.isEmpty()) return@setOnClickListener
            dismissSlashPopover()
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            onSend?.invoke(text, pendingAttachments.toList())
        }
        refreshSend()
        refreshAttachStrip()
    }

    private fun refreshSend() {
        val stop = running
        send.setIconResource(if (stop) R.drawable.ic_stop else R.drawable.ic_send)
        send.contentDescription = context.getString(if (stop) R.string.cd_code_stop else R.string.cd_code_send)
        send.isEnabled = stop || !input.text.isNullOrBlank() || pendingAttachments.isNotEmpty()
        attach.isEnabled = !stop && pendingAttachments.size < CodePromptImages.MAX_COUNT
    }

    /**
     * Adds an encoded attachment for the next send. Returns false when the cap is reached.
     */
    fun addAttachment(attachment: PromptAttachment): Boolean {
        if (pendingAttachments.size >= CodePromptImages.MAX_COUNT) return false
        pendingAttachments += attachment
        refreshAttachStrip()
        refreshSend()
        return true
    }

    val attachmentCount: Int get() = pendingAttachments.size

    fun clear() {
        input.setText("")
        pendingAttachments.clear()
        refreshAttachStrip()
        refreshSend()
    }

    private fun refreshAttachStrip() {
        attachChips.removeAllViews()
        attachStrip.isVisible = pendingAttachments.isNotEmpty()
        val d = context.resources.displayMetrics.density
        val chip = (56 * d).toInt()
        val gap = (6 * d).toInt()
        pendingAttachments.forEachIndexed { index, att ->
            val wrap = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(chip, chip).also {
                    if (index > 0) it.marginStart = gap
                }
            }
            val img = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = context.getString(R.string.cd_code_attach)
                // Prefer IO-decoded chip thumb; never setImageURI full-res on Main.
                val thumb = att.previewBitmap
                when {
                    thumb != null && !thumb.isRecycled -> setImageBitmap(thumb)
                    else -> setImageResource(R.drawable.ic_attach_plus)
                }
                background = context.getDrawable(R.drawable.bg_circle_soft)
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
            val remove = ImageButton(context).apply {
                val sz = (22 * d).toInt()
                layoutParams = FrameLayout.LayoutParams(sz, sz, Gravity.TOP or Gravity.END).also {
                    it.topMargin = (2 * d).toInt()
                    it.marginEnd = (2 * d).toInt()
                }
                setImageResource(R.drawable.ic_close_x)
                setBackgroundResource(R.drawable.bg_circle_soft)
                contentDescription = context.getString(R.string.cd_code_remove_attachment)
                setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
                setOnClickListener {
                    pendingAttachments.remove(att)
                    refreshAttachStrip()
                    refreshSend()
                }
            }
            wrap.addView(img)
            wrap.addView(remove)
            attachChips.addView(wrap)
        }
    }

    fun hideKeyboard() {
        input.clearFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    fun setPermission(mode: PermissionMode) {
        // Home (agent/folder visible) uses a short label so the row fits; session keeps the full name.
        val short = agentPill.isVisible || folderPill.isVisible || modelPill.isVisible
        permissionPill.text = context.getString(if (short) permissionPillLabel(mode) else permissionLabel(mode))
    }

    /** Opens the anchored glass popover above [anchor] (Grok's model-pill popover). */
    fun pick(
        anchor: View,
        title: CharSequence?,
        rows: List<PickerPopover.Row>,
        footer: List<PickerPopover.Row> = emptyList(),
        hint: CharSequence? = null,
    ) {
        dismissSlashPopover()
        popover?.dismiss(animated = false)
        hideKeyboard()
        anchor.isSelected = true
        popover = PickerPopover(popoverHost, anchor, backdropRef, edge = root).apply {
            onDismiss = { anchor.isSelected = false }
            show(title, rows, footer, hint, lifecycleOwner = lifecycleOwner, modal = true)
        }
    }

    fun dismissPopover(): Boolean {
        if (dismissSlashPopover()) return true
        val p = popover ?: return false
        if (!p.isShowing) return false
        p.dismiss()
        return true
    }

    /**
     * When the draft is a bare slash token (`/`, `/com`, …), show filtered [availableCommands]
     * in a glass popover above the composer. Picking replaces the token with `/name `.
     * Non-modal (no scrim) so the EditText/send stay touchable; rows update in place while typing.
     */
    private fun refreshSlashPicker() {
        val text = input.text?.toString().orEmpty()
        if (!isSlashDraft(text) || availableCommands.isEmpty()) {
            dismissSlashPopover()
            return
        }
        val prefix = slashPrefix(text)
        val matched = filterCommands(availableCommands, prefix)
        if (matched.isEmpty()) {
            dismissSlashPopover()
            return
        }
        val rows = matched.map { cmd ->
            val subtitle = when {
                cmd.description.isNotBlank() -> cmd.description
                !cmd.inputHint.isNullOrBlank() -> cmd.inputHint
                else -> null
            }
            PickerPopover.Row(
                title = "/${cmd.name}",
                subtitle = subtitle,
                onClick = { applySlashCommand(cmd) }
            )
        }
        val existing = slashPopover
        if (existing != null && existing.isShowing) {
            existing.updateRows(rows)
            return
        }
        popover?.dismiss(animated = false)
        slashPopover = PickerPopover(popoverHost, input, backdropRef, edge = root).apply {
            onDismiss = { slashPopover = null }
            show(
                title = null,
                rows = rows,
                lifecycleOwner = lifecycleOwner,
                animated = true,
                modal = false,
            )
        }
    }

    private fun applySlashCommand(cmd: AvailableCommand) {
        val (newText, caret) = insertSlashCommand(input.text?.toString().orEmpty(), cmd)
        dismissSlashPopover()
        input.setText(newText)
        input.setSelection(caret.coerceIn(0, newText.length))
        // Keep IME up so the user can type args after `/cmd `.
        input.requestFocus()
    }

    private fun dismissSlashPopover(): Boolean {
        val p = slashPopover ?: return false
        slashPopover = null
        if (p.isShowing) p.dismiss(animated = false)
        return true
    }

    fun pickPermission(current: PermissionMode, onPick: (PermissionMode) -> Unit) {
        pick(permissionPill, context.getString(R.string.code_settings_permission), PermissionMode.entries.map { m ->
            PickerPopover.Row(
                title = context.getString(permissionLabel(m)),
                subtitle = context.getString(permissionSub(m)),
                selected = m == current,
                onClick = { onPick(m) }
            )
        })
    }

    /** Model list popover when the harness reports [models] (hidden when empty). */
    fun pickModel(models: List<String>, current: String?, onPick: (String) -> Unit) {
        if (models.isEmpty()) return
        pick(modelPill, context.getString(R.string.code_home_pick_model), models.map { m ->
            PickerPopover.Row(
                title = m,
                selected = m == current,
                onClick = { onPick(m) }
            )
        })
    }

    /**
     * Updates the model pill label/visibility. Hidden when [models] is empty.
     * [editable]=false drops the expand chevron and clickability (in-session read-only;
     * ACP has session/new model `_meta` but no session/set_model yet).
     */
    fun setModel(model: String?, models: List<String>, editable: Boolean = true) {
        val show = models.isNotEmpty()
        modelPill.isVisible = show
        if (show) {
            modelPill.text = if (!model.isNullOrBlank()) CodeModelSelection.pillLabel(model)
            else context.getString(R.string.code_home_pick_model)
            // TalkBack prefers contentDescription over visible text — include the selected id.
            modelPill.contentDescription = if (!model.isNullOrBlank()) {
                context.getString(R.string.cd_code_model_selected, model)
            } else {
                context.getString(R.string.cd_code_model)
            }
            modelPill.isClickable = editable
            modelPill.isFocusable = editable
            val end = if (editable) R.drawable.ic_expand_more else 0
            modelPill.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_nav_models, 0, end, 0
            )
        } else {
            modelPill.contentDescription = context.getString(R.string.cd_code_model)
        }
        val gap = (4 * context.resources.displayMetrics.density).toInt()
        (permissionPill.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.marginStart =
            if (agentPill.isVisible || folderPill.isVisible || show) gap else 0
    }

    fun showPills(agent: Boolean, folder: Boolean, permission: Boolean, model: Boolean = false) {
        agentPill.isVisible = agent
        folderPill.isVisible = folder
        if (!model) modelPill.isVisible = false
        permissionPill.isVisible = permission
        val gap = (4 * context.resources.displayMetrics.density).toInt()
        (folderPill.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.marginStart =
            if (agent) gap else 0
        (modelPill.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.marginStart = gap
        (permissionPill.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.marginStart =
            if (agent || folder || modelPill.isVisible) gap else 0
    }

    companion object {
        fun permissionLabel(m: PermissionMode) = when (m) {
            PermissionMode.ASK -> R.string.code_perm_ask
            PermissionMode.AUTO_EDIT -> R.string.code_perm_auto_edit
            PermissionMode.PLAN -> R.string.code_perm_plan
            PermissionMode.FULL_AUTO -> R.string.code_perm_full
        }

        fun permissionPillLabel(m: PermissionMode) = when (m) {
            PermissionMode.ASK -> R.string.code_perm_ask_pill
            PermissionMode.AUTO_EDIT -> R.string.code_perm_auto_edit_pill
            PermissionMode.PLAN -> R.string.code_perm_plan_pill
            PermissionMode.FULL_AUTO -> R.string.code_perm_full_pill
        }

        fun permissionSub(m: PermissionMode) = when (m) {
            PermissionMode.ASK -> R.string.code_perm_ask_sub
            PermissionMode.AUTO_EDIT -> R.string.code_perm_auto_edit_sub
            PermissionMode.PLAN -> R.string.code_perm_plan_sub
            PermissionMode.FULL_AUTO -> R.string.code_perm_full_sub
        }

        /** Last path segment, for pills: "~/code/GradatiON" -> "GradatiON". */
        fun folderName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

        /** Draft is only a slash token (`/` or `/name`) — no whitespace yet. */
        fun isSlashDraft(text: String): Boolean =
            text.startsWith('/') && text.none { it.isWhitespace() }

        fun slashPrefix(text: String): String =
            if (isSlashDraft(text)) text.drop(1) else ""

        fun filterCommands(commands: List<AvailableCommand>, prefix: String): List<AvailableCommand> =
            if (prefix.isEmpty()) commands
            else commands.filter { it.name.startsWith(prefix, ignoreCase = true) }

        /** Replace the leading slash token with `/name `; caret after the inserted command. */
        fun insertSlashCommand(current: String, cmd: AvailableCommand): Pair<String, Int> {
            val rest = current.replaceFirst(Regex("^/\\S*"), "").trimStart()
            val insert = "/${cmd.name} "
            return (insert + rest) to insert.length
        }
    }

    /**
     * Dictation into the prompt (same engine and look as chat). Call from the host fragment's
     * onViewCreated; the mic hides when Settings > Voice is off or the phone can't recognize speech.
     */
    fun enableVoice(fragment: Fragment) {
        val vm = ViewModelProvider(fragment.requireActivity(), AppViewModelFactory(fragment.requireActivity().application))[ChatViewModel::class.java]
        dictation = VoiceDictation(
            fragment = fragment,
            input = input,
            micButton = mic,
            wave = root.findViewById(R.id.codeComposerVoiceWave),
            swapOut = listOf(root.findViewById(R.id.codeComposerPills)),
        ) { bytes, format, name -> vm.transcribeAudioForInput(bytes, format, name) }
    }

    fun refreshVoice() {
        dictation?.refresh()
    }
}
