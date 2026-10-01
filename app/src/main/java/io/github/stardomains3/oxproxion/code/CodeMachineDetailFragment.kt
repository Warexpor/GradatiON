package io.github.stardomains3.oxproxion.code

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import io.github.stardomains3.oxproxion.GrokConfirmDialog
import io.github.stardomains3.oxproxion.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Machine detail (§5.4.8): status, bridge version from initialize handshake, harnesses from
 * `listHarnesses`, and local revoke via [CodeHub.removeHost]. No remote device/revoke RPC.
 */
class CodeMachineDetailFragment : Fragment(R.layout.fragment_code_machine_detail) {

    private val hostId by lazy { requireArguments().getString(ARG_HOST)!! }
    private lateinit var hub: CodeHub

    private lateinit var toolbar: MaterialToolbar
    private lateinit var nameView: TextView
    private lateinit var urlView: TextView
    private lateinit var transportView: TextView
    private lateinit var fingerprintView: TextView
    private lateinit var statusDot: View
    private lateinit var statusView: TextView
    private lateinit var lastErrorView: TextView
    private lateinit var versionView: TextView
    private lateinit var harnessesCard: LinearLayout
    private lateinit var harnessesHint: TextView
    private lateinit var editBtn: MaterialButton
    private lateinit var revokeBtn: MaterialButton
    private lateinit var revokeHint: TextView
    private lateinit var versionLabel: TextView
    private lateinit var versionDivider: View
    /** Single-flight harness refresh; cancel + generation gate so a stale empty result cannot wipe rows (Z2). */
    private var harnessJob: Job? = null
    private var harnessGen: Int = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        hub = CodeHub.get(requireContext())
        toolbar = view.findViewById(R.id.toolbar)
        nameView = view.findViewById(R.id.codeMachineName)
        urlView = view.findViewById(R.id.codeMachineUrl)
        transportView = view.findViewById(R.id.codeMachineTransport)
        fingerprintView = view.findViewById(R.id.codeMachineFingerprint)
        statusDot = view.findViewById(R.id.codeMachineStatusDot)
        statusView = view.findViewById(R.id.codeMachineStatus)
        lastErrorView = view.findViewById(R.id.codeMachineLastError)
        versionView = view.findViewById(R.id.codeMachineBridgeVersion)
        versionLabel = view.findViewById(R.id.codeMachineVersionLabel)
        versionDivider = view.findViewById(R.id.codeMachineVersionDivider)
        harnessesCard = view.findViewById(R.id.codeMachineHarnessesCard)
        harnessesHint = view.findViewById(R.id.codeMachineHarnessesHint)
        editBtn = view.findViewById(R.id.codeMachineEdit)
        revokeBtn = view.findViewById(R.id.codeMachineRevoke)
        revokeHint = view.findViewById(R.id.codeMachineRevokeHint)

        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        editBtn.setOnClickListener {
            val host = hub.hosts.value.find { it.id == hostId } ?: return@setOnClickListener
            CodeHostDialog.show(this, host)
        }
        revokeBtn.setOnClickListener { confirmRevoke() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(hub.hosts, hub.connections) { hosts, _ -> hosts }.collect { hosts ->
                    val host = hosts.find { it.id == hostId }
                    if (host == null) {
                        if (isAdded) parentFragmentManager.popBackStack()
                        return@collect
                    }
                    bindHost(host)
                    refreshHarnesses(host)
                }
            }
        }
    }

    private fun bindHost(host: CodeHost) {
        toolbar.title = host.name
        nameView.text = host.name
        if (host.isDemo) {
            urlView.text = getString(R.string.code_host_demo_sub)
            transportView.setText(R.string.code_machine_transport_demo)
            fingerprintView.isVisible = false
            revokeHint.setText(R.string.code_machine_revoke_hint_demo)
            // Demo has no bridge handshake — hide version chrome (Z4).
            versionDivider.isVisible = false
            versionLabel.isVisible = false
            versionView.isVisible = false
        } else {
            urlView.text = CodeMachineDetail.redactUrl(host.url).ifBlank { host.url }
            transportView.setText(R.string.code_machine_transport_bridge)
            val short = CodeMachineDetail.fingerprintShort(host.fingerprint)
            if (short != null) {
                fingerprintView.isVisible = true
                fingerprintView.text = getString(R.string.code_machine_fingerprint, short)
            } else {
                fingerprintView.isVisible = false
            }
            versionDivider.isVisible = true
            versionLabel.isVisible = true
            versionView.isVisible = true
            revokeHint.setText(R.string.code_machine_revoke_hint)
        }

        val conn = hub.connectionOf(host.id)
        val kind = CodeMachineDetail.statusKind(host.isDemo, conn)
        val (labelRes, on) = when (kind) {
            CodeMachineDetail.StatusKind.DEMO -> R.string.code_status_demo to true
            CodeMachineDetail.StatusKind.CONNECTED -> R.string.code_status_connected to true
            CodeMachineDetail.StatusKind.CONNECTING -> R.string.code_status_connecting to false
            CodeMachineDetail.StatusKind.ERROR -> R.string.code_machine_status_error to false
            CodeMachineDetail.StatusKind.OFFLINE -> R.string.code_status_offline to false
        }
        statusView.setText(labelRes)
        statusDot.background.mutate().setTint(
            requireContext().getColor(if (on) R.color.code_status_on else R.color.code_status_off)
        )
        val err = hub.lastErrorOf(host.id)
        if (kind == CodeMachineDetail.StatusKind.ERROR && !err.isNullOrBlank()) {
            lastErrorView.isVisible = true
            lastErrorView.text = err
        } else {
            lastErrorView.isVisible = false
        }

        applyBridgeVersion(host)
    }

    /** Re-read [CodeHub.bridgeVersionOf] into the status card (initialize may finish after CONNECTED). */
    private fun applyBridgeVersion(host: CodeHost) {
        if (host.isDemo) return
        val version = hub.bridgeVersionOf(host.id)
        versionView.text = version?.takeIf { it.isNotBlank() }
            ?: getString(R.string.code_machine_version_unknown)
    }

    private fun refreshHarnesses(host: CodeHost) {
        // Z2: cancel prior job and bump generation. Generation is required because
        // CodeHub.harnessesFor uses runCatching and can swallow CancellationException.
        harnessJob?.cancel()
        val gen = ++harnessGen
        harnessJob = viewLifecycleOwner.lifecycleScope.launch {
            val live = hub.harnessesFor(host)
            if (!isAdded || gen != harnessGen) return@launch
            // Host may have been removed while suspending.
            if (hub.hosts.value.none { it.id == host.id }) return@launch
            // Z1: always refresh version after harnessesFor returns (even on empty early-return).
            // listHarnesses → ensureReady waits for initialize, so version is set by now when connected.
            applyBridgeVersion(host)
            harnessesCard.removeAllViews()
            if (live.isEmpty()) {
                val offline = !host.isDemo && hub.connectionOf(host.id) != ConnectionState.CONNECTED
                harnessesHint.setText(
                    if (offline) R.string.code_machine_harnesses_offline
                    else R.string.code_machine_harnesses_empty
                )
                harnessesHint.isVisible = true
                return@launch
            }
            harnessesHint.isVisible = false
            live.forEachIndexed { index, info -> addHarnessRow(info, showDivider = index > 0) }
        }
    }

    private fun addHarnessRow(info: HarnessInfo, showDivider: Boolean) {
        if (showDivider) {
            harnessesCard.addView(View(requireContext()).apply {
                setBackgroundColor(requireContext().getColor(R.color.xai_hairline))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = (16 * resources.displayMetrics.density).toInt()
            })
        }
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_rp_hub_row, harnessesCard, false)
        row.isClickable = false
        row.isFocusable = false
        row.findViewById<View>(R.id.rpHubRowIcon).isVisible = false
        row.findViewById<View>(R.id.rpHubRowChevron).isVisible = false
        val title = row.findViewById<TextView>(R.id.rpHubRowTitle)
        val avail = getString(
            if (info.available) R.string.code_machine_harness_available
            else R.string.code_machine_harness_unavailable
        )
        val models = CodeMachineDetail.harnessModelCountLabel(info.models.size)
        val sub = listOfNotNull(avail, models).joinToString(" · ")
        val s = android.text.SpannableStringBuilder(info.label).append('\n')
        val start = s.length
        s.append(sub)
        s.setSpan(
            android.text.style.ForegroundColorSpan(requireContext().getColor(R.color.xai_mute)),
            start, s.length, 0
        )
        s.setSpan(android.text.style.RelativeSizeSpan(0.82f), start, s.length, 0)
        title.text = s
        title.maxLines = 2
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        title.setPadding(
            0,
            (8 * resources.displayMetrics.density).toInt(),
            0,
            (8 * resources.displayMetrics.density).toInt()
        )
        // Collapse icon column: title already has start margin from the row layout.
        (title.layoutParams as? LinearLayout.LayoutParams)?.marginStart =
            (0 * resources.displayMetrics.density).toInt()
        row.findViewById<TextView>(R.id.rpHubRowValue).isVisible = false
        harnessesCard.addView(row)
    }

    private fun confirmRevoke() {
        val host = hub.hosts.value.find { it.id == hostId } ?: return
        val body = if (host.isDemo) {
            getString(R.string.code_machine_revoke_confirm_demo, host.name)
        } else {
            getString(R.string.code_machine_revoke_confirm, host.name)
        }
        GrokConfirmDialog.show(
            this,
            getString(R.string.code_machine_revoke),
            body,
            getString(R.string.code_machine_revoke),
            onConfirm = {
                hub.removeHost(host.id)
                // hosts collector pops when the host disappears.
            },
        )
    }

    companion object {
        private const val ARG_HOST = "hostId"

        fun newInstance(hostId: String) = CodeMachineDetailFragment().apply {
            arguments = bundleOf(ARG_HOST to hostId)
        }
    }
}
