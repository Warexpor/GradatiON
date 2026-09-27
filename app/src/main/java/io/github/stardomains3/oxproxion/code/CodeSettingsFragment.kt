package io.github.stardomains3.oxproxion.code

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import androidx.appcompat.widget.SwitchCompat
import io.github.stardomains3.oxproxion.GlassAlertDialogBuilder
import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations
import io.github.stardomains3.oxproxion.R
import kotlinx.coroutines.launch

/** Code mode settings: the tab toggle, away notifications, machines, default approval mode, setup notes. */
class CodeSettingsFragment : Fragment(R.layout.fragment_code_settings) {

    private lateinit var hub: CodeHub

    /** Request POST_NOTIFICATIONS only when the user opts into "Notify when away" (API 33+). */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!::hub.isInitialized) return@registerForActivityResult
            val sw = view?.findViewById<SwitchCompat>(R.id.codeNotifyAwaySwitch)
            if (granted) {
                hub.store.notifyWhenAway = true
                sw?.isChecked = true
            } else {
                hub.store.notifyWhenAway = false
                sw?.isChecked = false
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        hub = CodeHub.get(requireContext())
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        view.findViewById<SwitchCompat>(R.id.codeShowTabSwitch).apply {
            isChecked = hub.store.enabled
            setOnCheckedChangeListener { _, on ->
                hub.store.enabled = on
                if (!on) hub.store.lastTabWasCode = false
            }
        }
        view.findViewById<SwitchCompat>(R.id.codeNotifyAwaySwitch).apply {
            isChecked = hub.store.notifyWhenAway
            setOnCheckedChangeListener { _, on ->
                if (on) {
                    if (!ensureNotificationPermission()) {
                        // Permission pending or denied — keep pref off until granted.
                        isChecked = false
                        return@setOnCheckedChangeListener
                    }
                    hub.store.notifyWhenAway = true
                } else {
                    hub.store.notifyWhenAway = false
                }
            }
        }
        bindDefaults(view.findViewById(R.id.codeDefaultsCard))
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.hosts.collect { bindMachines(view.findViewById(R.id.codeMachinesCard), it) }
            }
        }
    }

    /**
     * @return true when notifications may be posted (permission granted or not required).
     * Launches the runtime prompt on API 33+ when not yet granted.
     */
    private fun ensureNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return false
    }

    private fun bindMachines(card: LinearLayout, hosts: List<CodeHost>) {
        card.removeAllViews()
        hosts.forEach { h ->
            addRow(card, R.drawable.ic_code_machine, h.name,
                if (h.isDemo) getString(R.string.code_host_demo_sub) else CodeMachineDetail.redactUrl(h.url).ifBlank { h.url },
                chevron = true) {
                openMachineDetail(h.id)
            }
        }
        addRow(card, R.drawable.ic_code_plus, getString(R.string.code_settings_add_machine), null, chevron = false) {
            CodeHostDialog.show(this, null)
        }
        if (hosts.none { it.isDemo }) {
            addRow(card, R.drawable.ic_code_terminal, getString(R.string.code_settings_try_demo), null, chevron = false) {
                hub.addDemoHost()
            }
        }
    }

    private fun bindDefaults(card: LinearLayout) {
        card.removeAllViews()
        val mode = hub.store.defaultPermissionMode
        addRow(card, CodeComposer.permissionIcon(mode), getString(R.string.code_settings_permission),
            getString(CodeComposer.permissionLabel(mode)), chevron = true, valueTrailing = true) {
            val modes = PermissionMode.entries
            // Same rows as the composer's approvals popover: icon, name, what it means, a check.
            val rows = object : android.widget.ArrayAdapter<PermissionMode>(requireContext(), R.layout.item_popover_row, modes) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val v = convertView ?: layoutInflater.inflate(R.layout.item_popover_row, parent, false)
                    val m = modes[position]
                    v.findViewById<android.widget.ImageView>(R.id.popoverRowIcon).setImageResource(CodeComposer.permissionIcon(m))
                    v.findViewById<TextView>(R.id.popoverRowTitle).setText(CodeComposer.permissionLabel(m))
                    v.findViewById<TextView>(R.id.popoverRowSubtitle).setText(CodeComposer.permissionSub(m))
                    v.findViewById<View>(R.id.popoverRowCheck).visibility = if (m == mode) View.VISIBLE else View.GONE
                    v.isSelected = m == mode
                    // The dialog's list takes the tap, not the row.
                    v.isClickable = false
                    v.isFocusable = false
                    return v
                }
            }
            GlassAlertDialogBuilder(requireContext(), R.style.CustomMaterialAlertDialogTheme)
                .setTitle(R.string.code_settings_permission)
                .setAdapter(rows) { d, which ->
                    hub.store.defaultPermissionMode = modes[which]
                    d.dismiss()
                    bindDefaults(card)
                }
                .show()
        }
    }

    private fun openMachineDetail(hostId: String) {
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .add(R.id.fragment_container, CodeMachineDetailFragment.newInstance(hostId))
            .addToBackStack(null)
            .commit()
    }

    /** Grouped-card row (the RP hub's row): icon, title, optional subtitle or trailing value, chevron. */
    private fun addRow(
        card: LinearLayout, icon: Int, title: String, sub: String?, chevron: Boolean,
        valueTrailing: Boolean = false, onClick: () -> Unit
    ) {
        if (card.childCount > 0) {
            card.addView(View(requireContext()).apply {
                setBackgroundColor(requireContext().getColor(R.color.xai_hairline))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = (54 * resources.displayMetrics.density).toInt()
            })
        }
        val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_rp_hub_row, card, false)
        row.findViewById<ImageView>(R.id.rpHubRowIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.rpHubRowTitle).apply {
            text = if (sub != null && !valueTrailing) "$title\n" else title
            if (sub != null && !valueTrailing) {
                val s = android.text.SpannableStringBuilder(title).append('\n')
                val start = s.length
                s.append(sub)
                s.setSpan(android.text.style.ForegroundColorSpan(requireContext().getColor(R.color.xai_mute)), start, s.length, 0)
                s.setSpan(android.text.style.RelativeSizeSpan(0.82f), start, s.length, 0)
                text = s
                // Title plus the subtitle. Two lines was eating "nothing leaves the phone".
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
            }
        }
        row.findViewById<TextView>(R.id.rpHubRowValue).apply {
            text = if (valueTrailing) sub else null
            isVisible = valueTrailing
        }
        row.findViewById<View>(R.id.rpHubRowChevron).isVisible = chevron
        row.setOnClickListener { onClick() }
        card.addView(row)
    }
}
