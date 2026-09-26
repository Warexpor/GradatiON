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
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import io.github.stardomains3.oxproxion.GlassBackdropLayout
import io.github.stardomains3.oxproxion.GlassLinearLayout
import io.github.stardomains3.oxproxion.PickerPopover
import io.github.stardomains3.oxproxion.R

/**
 * Binds `view_code_composer`: the glass capsule with the prompt field, optional image attach
 * chips, the agent / folder / approvals pills and the send button (which turns into stop while
 * the agent works). Home uses all three pills to start a session; a session shows only the
 * approvals pill.
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
    val permissionPill: TextView = root.findViewById(R.id.codeComposerPermission)
    private val send: MaterialButton = root.findViewById(R.id.codeComposerSend)
    private val attach: MaterialButton = root.findViewById(R.id.codeComposerAttach)
    private val attachStrip: HorizontalScrollView = root.findViewById(R.id.codeComposerAttachStrip)
    private val attachChips: LinearLayout = root.findViewById(R.id.codeComposerAttachChips)
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
        val short = agentPill.isVisible || folderPill.isVisible
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

    fun showPills(agent: Boolean, folder: Boolean, permission: Boolean) {
        agentPill.isVisible = agent
        folderPill.isVisible = folder
        permissionPill.isVisible = permission
        (permissionPill.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.marginStart =
            if (agent || folder) (4 * context.resources.displayMetrics.density).toInt() else 0
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
}
