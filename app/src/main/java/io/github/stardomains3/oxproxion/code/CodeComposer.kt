package io.github.stardomains3.oxproxion.code

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import io.github.stardomains3.oxproxion.GlassBackdropLayout
import io.github.stardomains3.oxproxion.GlassLinearLayout
import io.github.stardomains3.oxproxion.PickerPopover
import io.github.stardomains3.oxproxion.R

/**
 * Binds `view_code_composer`: the glass capsule with the prompt field, the agent / folder /
 * approvals pills and the send button (which turns into stop while the agent works).
 * Home uses all three pills to start a session; a session shows only the approvals pill.
 */
class CodeComposer(
    val root: GlassLinearLayout,
    private val popoverHost: FrameLayout,
    backdrop: GlassBackdropLayout
) {
    private val context: Context = root.context
    val input: EditText = root.findViewById(R.id.codeComposerInput)
    val agentPill: TextView = root.findViewById(R.id.codeComposerAgent)
    val folderPill: TextView = root.findViewById(R.id.codeComposerFolder)
    val permissionPill: TextView = root.findViewById(R.id.codeComposerPermission)
    private val send: MaterialButton = root.findViewById(R.id.codeComposerSend)
    private var popover: PickerPopover? = null
    private val backdropRef = backdrop

    var onSend: ((String) -> Unit)? = null
    var onStop: (() -> Unit)? = null

    /** While true the button stops the agent instead of sending. */
    var running: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshSend()
        }

    init {
        root.glass.source = backdrop
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshSend()
        })
        send.setOnClickListener {
            // While the agent is running, keep Stop reachable even if the draft has text
            // (do not hide Stop behind Send).
            if (running) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                onStop?.invoke()
                return@setOnClickListener
            }
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            onSend?.invoke(text)
        }
        refreshSend()
    }

    private fun refreshSend() {
        val stop = running
        send.setIconResource(if (stop) R.drawable.ic_stop else R.drawable.ic_send)
        send.contentDescription = context.getString(if (stop) R.string.cd_code_stop else R.string.cd_code_send)
        send.isEnabled = stop || !input.text.isNullOrBlank()
    }

    fun clear() {
        input.setText("")
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
        hint: CharSequence? = null
    ) {
        popover?.dismiss(animated = false)
        hideKeyboard()
        anchor.isSelected = true
        popover = PickerPopover(popoverHost, anchor, backdropRef, edge = root).apply {
            onDismiss = { anchor.isSelected = false }
            show(title, rows, footer, hint)
        }
    }

    fun dismissPopover(): Boolean {
        val p = popover ?: return false
        if (!p.isShowing) return false
        p.dismiss()
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
    }
}
