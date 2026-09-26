package io.github.stardomains3.oxproxion.code

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.stardomains3.oxproxion.GlassAlertDialogBuilder
import io.github.stardomains3.oxproxion.R
import kotlinx.coroutines.launch

/** Code mode settings: the tab toggle, machines, default approval mode, setup notes. */
class CodeSettingsFragment : Fragment(R.layout.fragment_code_settings) {

    private lateinit var hub: CodeHub

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        hub = CodeHub.get(requireContext())
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        view.findViewById<MaterialSwitch>(R.id.codeShowTabSwitch).apply {
            isChecked = hub.store.enabled
            setOnCheckedChangeListener { _, on ->
                hub.store.enabled = on
                if (!on) hub.store.lastTabWasCode = false
            }
        }
        bindDefaults(view.findViewById(R.id.codeDefaultsCard))
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.hosts.collect { bindMachines(view.findViewById(R.id.codeMachinesCard), it) }
            }
        }
    }

    private fun bindMachines(card: LinearLayout, hosts: List<CodeHost>) {
        card.removeAllViews()
        hosts.forEach { h ->
            addRow(card, R.drawable.ic_code_machine, h.name,
                if (h.isDemo) getString(R.string.code_host_demo_sub) else h.url, chevron = true) {
                CodeHostDialog.show(this, h)
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
        addRow(card, R.drawable.ic_code_shield, getString(R.string.code_settings_permission),
            getString(CodeComposer.permissionLabel(mode)), chevron = true, valueTrailing = true) {
            val modes = PermissionMode.entries
            val labels = modes.map { getString(CodeComposer.permissionLabel(it)) + "\n" + getString(CodeComposer.permissionSub(it)) }.toTypedArray()
            GlassAlertDialogBuilder(requireContext(), R.style.CustomMaterialAlertDialogTheme)
                .setTitle(R.string.code_settings_permission)
                .setSingleChoiceItems(labels, modes.indexOf(mode)) { d, which ->
                    hub.store.defaultPermissionMode = modes[which]
                    d.dismiss()
                    bindDefaults(card)
                }
                .show()
        }
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
                maxLines = 2
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
