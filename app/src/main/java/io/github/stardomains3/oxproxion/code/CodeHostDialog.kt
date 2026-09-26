package io.github.stardomains3.oxproxion.code

import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.stardomains3.oxproxion.GlassAlertDialogBuilder
import io.github.stardomains3.oxproxion.GlassDialogs
import io.github.stardomains3.oxproxion.GrokConfirmDialog
import io.github.stardomains3.oxproxion.R
import kotlinx.coroutines.launch

/** Glass card to add or edit a machine (bridge address, pairing token, fingerprint, default agent). */
object CodeHostDialog {

    fun show(
        fragment: Fragment,
        existing: CodeHost?,
        prefill: CodePairing.Result? = null,
    ) {
        val context = fragment.requireContext()
        val hub = CodeHub.get(context)
        val dialog = GlassAlertDialogBuilder(context, R.style.CustomMaterialAlertDialogTheme).create()
        val sheet = LayoutInflater.from(context).inflate(R.layout.dialog_code_host, null)
        sheet.findViewById<TextView>(R.id.codeHostDialogTitle).setText(
            if (existing == null) R.string.code_host_add_title else R.string.code_host_edit_title
        )
        val name = sheet.findViewById<TextInputEditText>(R.id.codeHostName)
        val url = sheet.findViewById<TextInputEditText>(R.id.codeHostUrl)
        val urlLayout = sheet.findViewById<TextInputLayout>(R.id.codeHostUrlLayout)
        val token = sheet.findViewById<TextInputEditText>(R.id.codeHostToken)
        val fingerprint = sheet.findViewById<TextInputEditText>(R.id.codeHostFingerprint)
        val fingerprintLayout = sheet.findViewById<TextInputLayout>(R.id.codeHostFingerprintLayout)

        val initialName = when {
            existing != null -> existing.name
            prefill != null -> prefill.nameHint
            else -> ""
        }
        val initialUrl = prefill?.url ?: existing?.url.orEmpty()
        val initialToken = prefill?.token ?: existing?.token.orEmpty()
        val initialFp = prefill?.fingerprint
            ?: existing?.fingerprint.orEmpty()

        name.setText(initialName)
        url.setText(initialUrl)
        token.setText(initialToken)
        fingerprint.setText(initialFp)

        val isDemo = existing?.isDemo == true
        sheet.findViewById<TextInputLayout>(R.id.codeHostTokenLayout).isVisible = !isDemo
        urlLayout.isVisible = !isDemo
        fingerprintLayout.isVisible = !isDemo

        val isAdd = existing == null
        sheet.findViewById<MaterialButton>(R.id.codeHostScanQr).apply {
            isVisible = isAdd && !isDemo
            setOnClickListener {
                dialog.dismiss()
                fragment.startActivity(CodePairScanActivity.intent(context))
            }
        }
        sheet.findViewById<TextView>(R.id.codeHostManualLabel).isVisible = isAdd && !isDemo

        var agent = existing?.defaultHarness ?: HarnessKind.CLAUDE_CODE
        val agents = sheet.findViewById<LinearLayout>(R.id.codeHostAgents)
        val d = context.resources.displayMetrics.density

        fun bindPills(options: List<Pair<String, HarnessKind>>) {
            agents.removeAllViews()
            val pills = options.map { (label, k) ->
                (LayoutInflater.from(context).inflate(R.layout.item_code_pill, agents, false) as TextView).apply {
                    text = label
                    isSelected = k == agent
                    agents.addView(this, (layoutParams as LinearLayout.LayoutParams).apply {
                        if (agents.childCount > 0) marginStart = (6 * d).toInt()
                    })
                } to k
            }
            pills.forEach { (pill, k) ->
                pill.setOnClickListener {
                    agent = k
                    pills.forEach { (p, pk) -> p.isSelected = pk == k }
                }
            }
        }

        // Static catalog first so the dialog paints immediately when offline/unpaired.
        bindPills(AgentPillOptions.resolve(emptyList(), agent))

        // Prefer live bridge/listHarnesses when this host already has a connected backend.
        if (existing != null) {
            fragment.lifecycleScope.launch {
                val live = hub.harnessesFor(existing)
                if (!dialog.isShowing || live.isEmpty()) return@launch
                bindPills(AgentPillOptions.resolve(live, agent))
            }
        }

        sheet.findViewById<MaterialButton>(R.id.codeHostCancel).setOnClickListener { dialog.dismiss() }
        sheet.findViewById<MaterialButton>(R.id.codeHostSave).setOnClickListener {
            val u = url.text?.toString()?.trim().orEmpty()
            if (!isDemo && !(u.startsWith("ws://") || u.startsWith("wss://"))) {
                urlLayout.error = context.getString(R.string.code_host_bad_url)
                return@setOnClickListener
            }
            val fpTyped = fingerprint.text?.toString()?.trim().orEmpty()
            val fpStored = when {
                isDemo -> ""
                fpTyped.isEmpty() -> ""
                else -> BridgeTls.normalizePin(fpTyped) ?: fpTyped
            }
            if (fpTyped.isNotEmpty() && BridgeTls.normalizePin(fpTyped) == null) {
                fingerprintLayout.error = context.getString(R.string.code_pair_bad_fingerprint)
                return@setOnClickListener
            }
            fingerprintLayout.error = null
            val host = (existing ?: CodeHost(id = hub.newHostId(), name = "")).copy(
                name = name.text?.toString()?.trim().orEmpty().ifEmpty {
                    u.substringAfter("://").substringBefore(':').substringBefore('/')
                },
                url = u,
                token = token.text?.toString()?.trim().orEmpty(),
                fingerprint = fpStored,
                defaultHarness = agent
            )
            hub.saveHost(host)
            dialog.dismiss()
        }
        url.setOnFocusChangeListener { _, has ->
            if (has) return@setOnFocusChangeListener
            val u = url.text?.toString()?.trim().orEmpty()
            urlLayout.helperText = if (u.startsWith("ws://")) context.getString(R.string.code_host_plain_ws_warning) else null
        }
        sheet.findViewById<MaterialButton>(R.id.codeHostRemove).apply {
            isVisible = existing != null
            setOnClickListener {
                dialog.dismiss()
                GrokConfirmDialog.show(fragment, context.getString(R.string.code_host_remove),
                    context.getString(R.string.code_host_remove_confirm, existing!!.name),
                    context.getString(R.string.code_host_remove), { hub.removeHost(existing.id) })
            }
        }

        dialog.setView(sheet)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.let { GlassDialogs.frost(it) }
        dialog.show()
        if (existing == null && prefill == null) name.requestFocus()
    }
}
