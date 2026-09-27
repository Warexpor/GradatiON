package io.github.stardomains3.oxproxion.code

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.TextView
import androidx.constraintlayout.helper.widget.Flow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Glass card to add or edit a machine (bridge address, pairing token, fingerprint, default agent). */
object CodeHostDialog {

    /** How long to wait for CONNECTED / FAILED after save before treating as unreachable. */
    private const val TEST_TIMEOUT_MS = 10_000L
    /** Brief pause on success so the user sees "Connected" before dismiss. */
    private const val SUCCESS_DISMISS_MS = 900L

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
        val status = sheet.findViewById<TextView>(R.id.codeHostStatus)
        val saveBtn = sheet.findViewById<MaterialButton>(R.id.codeHostSave)

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
        val agents = sheet.findViewById<ConstraintLayout>(R.id.codeHostAgents)
        val flow = sheet.findViewById<Flow>(R.id.codeHostAgentsFlow)

        fun bindPills(options: List<Pair<String, HarnessKind>>) {
            for (i in agents.childCount - 1 downTo 0) {
                if (agents.getChildAt(i).id != R.id.codeHostAgentsFlow) agents.removeViewAt(i)
            }
            val pills = options.map { (label, k) ->
                (LayoutInflater.from(context).inflate(R.layout.item_code_pill, agents, false) as TextView).apply {
                    id = android.view.View.generateViewId()
                    text = label
                    isSelected = k == agent
                    agents.addView(this)
                } to k
            }
            flow.referencedIds = pills.map { it.first.id }.toIntArray()
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

        var testJob: Job? = null
        var awaitingDone = false

        fun setStatus(text: CharSequence, connected: Boolean) {
            status.isVisible = true
            status.text = text
            status.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (connected) R.color.code_status_on else R.color.xai_mute,
                ),
            )
        }

        fun armSave() {
            awaitingDone = false
            saveBtn.setText(R.string.code_host_save)
            saveBtn.isEnabled = true
        }

        fun armDone() {
            awaitingDone = true
            saveBtn.setText(R.string.cd_done)
            saveBtn.isEnabled = true
        }

        // Editing after a failed test restores Save so the user can retry.
        val editWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!awaitingDone) return
                armSave()
                status.isVisible = false
            }
        }
        name.addTextChangedListener(editWatcher)
        url.addTextChangedListener(editWatcher)
        token.addTextChangedListener(editWatcher)
        fingerprint.addTextChangedListener(editWatcher)

        sheet.findViewById<MaterialButton>(R.id.codeHostCancel).setOnClickListener {
            testJob?.cancel()
            dialog.dismiss()
        }

        saveBtn.setOnClickListener {
            if (awaitingDone) {
                testJob?.cancel()
                dialog.dismiss()
                return@setOnClickListener
            }
            val u = url.text?.toString()?.trim().orEmpty()
            if (!isDemo && !(u.startsWith("ws://", ignoreCase = true) || u.startsWith("wss://", ignoreCase = true))) {
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
            // Fingerprint pinning needs TLS; reject ws:// + pin (no silent false security).
            if (!isDemo && BridgeTls.pinRequiresWss(fpTyped, u)) {
                fingerprintLayout.error = context.getString(R.string.code_pair_pin_requires_wss)
                urlLayout.error = context.getString(R.string.code_pair_pin_requires_wss)
                return@setOnClickListener
            }
            fingerprintLayout.error = null
            urlLayout.error = null
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

            // Demo hosts: skip the live connect test.
            if (isDemo || host.isDemo) {
                dialog.dismiss()
                return@setOnClickListener
            }

            // Post-save pairing test: set active, connect, show inline result.
            hub.selectHost(host.id)
            saveBtn.isEnabled = false
            setStatus(context.getString(R.string.code_status_connecting), connected = false)

            testJob?.cancel()
            testJob = fragment.lifecycleScope.launch {
                val terminal = withTimeoutOrNull(TEST_TIMEOUT_MS) {
                    hub.connection.first {
                        it == ConnectionState.CONNECTED || it == ConnectionState.FAILED
                    }
                }
                if (!dialog.isShowing) return@launch

                val timedOut = terminal == null
                val outcome = CodePairConnect.outcome(
                    state = terminal,
                    lastError = hub.lastError(),
                    timedOut = timedOut,
                )
                when (outcome) {
                    CodePairConnect.Outcome.CONNECTED -> {
                        setStatus(context.getString(R.string.code_status_connected), connected = true)
                        delay(SUCCESS_DISMISS_MS)
                        if (dialog.isShowing) dialog.dismiss()
                    }
                    CodePairConnect.Outcome.WRONG_TOKEN -> {
                        setStatus(context.getString(R.string.code_host_test_wrong_token), connected = false)
                        armDone()
                    }
                    CodePairConnect.Outcome.UNREACHABLE, CodePairConnect.Outcome.CONNECTING -> {
                        setStatus(
                            context.getString(R.string.code_host_test_unreachable),
                            connected = false,
                        )
                        armDone()
                    }
                }
            }
        }

        fun refreshPlainWsWarning() {
            val u = url.text?.toString()?.trim().orEmpty()
            val fp = fingerprint.text?.toString()?.trim().orEmpty()
            urlLayout.helperText = when {
                // Pin + cleartext is rejected on save; no "pin active" implication here.
                BridgeTls.pinRequiresWss(fp, u) ->
                    context.getString(R.string.code_pair_pin_requires_wss)
                BridgeTls.isCleartextWs(u) ->
                    context.getString(R.string.code_host_plain_ws_warning)
                else -> null
            }
        }
        url.setOnFocusChangeListener { _, has ->
            if (has) return@setOnFocusChangeListener
            refreshPlainWsWarning()
        }
        fingerprint.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshPlainWsWarning()
        })
        url.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshPlainWsWarning()
        })
        refreshPlainWsWarning()

        sheet.findViewById<MaterialButton>(R.id.codeHostRemove).apply {
            isVisible = existing != null
            setOnClickListener {
                testJob?.cancel()
                dialog.dismiss()
                GrokConfirmDialog.show(fragment, context.getString(R.string.code_host_remove),
                    context.getString(R.string.code_host_remove_confirm, existing!!.name),
                    context.getString(R.string.code_host_remove), { hub.removeHost(existing.id) })
            }
        }

        dialog.setOnDismissListener { testJob?.cancel() }
        dialog.setView(sheet)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.let { GlassDialogs.frost(it) }
        dialog.show()
        if (existing == null && prefill == null) name.requestFocus()
    }
}
