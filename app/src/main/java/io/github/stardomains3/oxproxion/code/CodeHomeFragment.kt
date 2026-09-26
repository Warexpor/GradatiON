package io.github.stardomains3.oxproxion.code

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.stardomains3.oxproxion.AppToast
import io.github.stardomains3.oxproxion.GlassBackdropLayout
import io.github.stardomains3.oxproxion.GlassLinearLayout
import io.github.stardomains3.oxproxion.GrokInputDialog
import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations
import io.github.stardomains3.oxproxion.PickerPopover
import io.github.stardomains3.oxproxion.R
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The Code tab. Shows which machine you're driving, its sessions (the ones waiting on you
 * first), and a composer that starts a new session with a chosen agent, folder and approval
 * mode. With no machine yet, it explains the idea and offers the demo.
 *
 * Hosted inside the chat screen by [CodeModeHost] (child fragment), under the shared top bar.
 */
class CodeHomeFragment : Fragment(R.layout.fragment_code_home) {

    private lateinit var hub: CodeHub
    private lateinit var composer: CodeComposer
    private lateinit var list: RecyclerView
    private val adapter = HomeAdapter()

    private var harness: HarnessKind = HarnessKind.CLAUDE_CODE
    private var workspace: String = ""
    private var permission: PermissionMode = PermissionMode.ASK
    private var boundHostId: String? = null
    private var starting = false

    /** Space the chat screen's floating top bar takes; set by [CodeModeHost]. */
    var topInset: Int = 0
        set(value) {
            field = value
            if (::list.isInitialized) list.updatePadding(top = value)
        }

    /** 0..255: how much of the shared top bar's scroll-edge fade to show. */
    var onScrollEdge: ((Int) -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        hub = CodeHub.get(requireContext())
        val root = view as FrameLayout
        val backdrop = view.findViewById<GlassBackdropLayout>(R.id.codeHomeBackdrop)
        list = view.findViewById(R.id.codeHomeList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.itemAnimator?.changeDuration = 0
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val under = rv.computeVerticalScrollOffset().toFloat()
                onScrollEdge?.invoke((255 * (under / (24f * resources.displayMetrics.density)).coerceIn(0f, 1f)).toInt())
            }
        })
        if (topInset > 0) list.updatePadding(top = topInset)

        composer = CodeComposer(view.findViewById<View>(R.id.codeHomeComposer) as GlassLinearLayout, root, backdrop)
        composer.root.addOnLayoutChangeListener { v, _, top, _, _, _, oldTop, _, _ ->
            if (top != oldTop) list.updatePadding(bottom = root.height - top + (16 * resources.displayMetrics.density).toInt())
        }
        permission = hub.store.defaultPermissionMode
        composer.setPermission(permission)
        composer.agentPill.setOnClickListener { pickAgent() }
        composer.folderPill.setOnClickListener { pickFolder() }
        composer.permissionPill.setOnClickListener {
            composer.pickPermission(permission) { permission = it; composer.setPermission(it) }
        }
        composer.onSend = { start(it) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.connect()
                combine(hub.activeHost, hub.connection, hub.sessions) { h, c, _ -> h to c }.collect { (host, conn) ->
                    bindHost(host)
                    render(host, conn)
                }
            }
        }
    }

    fun dismissPopover(): Boolean = ::composer.isInitialized && composer.dismissPopover()

    /** Top-bar "new" in Code mode: start fresh from the composer. */
    fun focusComposer() {
        if (!::composer.isInitialized) return
        list.smoothScrollToPosition(0)
        composer.input.requestFocus()
        requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.showSoftInput(composer.input, 0)
    }

    private fun bindHost(host: CodeHost?) {
        composer.root.isVisible = host != null
        if (host == null || host.id == boundHostId) return
        boundHostId = host.id
        harness = host.defaultHarness
        workspace = host.recentWorkspaces.firstOrNull() ?: host.defaultWorkspace
        refreshPills()
    }

    private fun refreshPills() {
        composer.agentPill.text = harness.shortName
        composer.folderPill.text = if (workspace.isBlank()) getString(R.string.code_home_pick_workspace) else CodeComposer.folderName(workspace)
        composer.input.hint = getString(R.string.code_home_composer_hint, harness.shortName)
    }

    private fun render(host: CodeHost?, conn: ConnectionState) {
        val items = ArrayList<HomeItem>()
        if (host == null) {
            items += HomeItem.Onboard
        } else {
            items += HomeItem.Header(host, conn)
            val sessions = hub.sessionsFor(host.id)
            if (sessions.isEmpty()) {
                items += HomeItem.Hero(host, harness, workspace)
            } else {
                val (active, recent) = sessions.partition { it.running || it.status == SessionStatus.NEEDS_APPROVAL }
                if (active.isNotEmpty()) {
                    items += HomeItem.Section(getString(R.string.code_home_section_active))
                    active.sortedByDescending { it.status == SessionStatus.NEEDS_APPROVAL }.forEach { items += HomeItem.Session(it) }
                }
                if (recent.isNotEmpty()) {
                    items += HomeItem.Section(getString(R.string.code_home_section_recent))
                    recent.forEach { items += HomeItem.Session(it) }
                }
            }
        }
        adapter.submitList(items)
    }

    // ── pickers ───────────────────────────────────────────────────────────────────────────

    private fun pickAgent() {
        composer.pick(composer.agentPill, getString(R.string.code_home_pick_agent), HarnessKind.entries.filter { it != HarnessKind.CUSTOM }.map { k ->
            PickerPopover.Row(k.displayName, iconRes = R.drawable.ic_code_terminal, selected = k == harness) {
                harness = k
                refreshPills()
            }
        })
    }

    private fun pickFolder() {
        val host = hub.activeHost.value ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val folders = hub.workspaces(harness).ifEmpty { listOfNotNull(host.defaultWorkspace.ifBlank { null }) }
            composer.pick(
                composer.folderPill,
                getString(R.string.code_home_folder_prompt, host.name),
                folders.map { path ->
                    PickerPopover.Row(CodeComposer.folderName(path), subtitle = path, iconRes = R.drawable.ic_code_folder, selected = path == workspace) {
                        workspace = path
                        refreshPills()
                    }
                },
                footer = listOf(PickerPopover.Row(getString(R.string.code_home_new_folder), iconRes = R.drawable.ic_code_plus) {
                    GrokInputDialog.show(this@CodeHomeFragment, getString(R.string.code_home_pick_workspace), "~/code/project", workspace, getString(R.string.code_host_save)) {
                        if (it.isNotBlank()) { workspace = it.trim(); refreshPills() }
                    }
                })
            )
        }
    }

    private fun pickMachine(anchor: View) {
        val hosts = hub.hosts.value
        val active = hub.activeHost.value
        composer.pick(anchor, getString(R.string.code_home_pick_machine), hosts.map { h ->
            PickerPopover.Row(h.name, subtitle = if (h.isDemo) getString(R.string.code_status_demo) else h.url,
                iconRes = R.drawable.ic_code_machine, selected = h.id == active?.id) { hub.selectHost(h.id) }
        }, footer = listOf(PickerPopover.Row(getString(R.string.code_home_manage_machines), iconRes = R.drawable.ic_settings_stroke) { openSettings() }))
    }

    // ── actions ───────────────────────────────────────────────────────────────────────────

    private fun start(prompt: String) {
        val host = hub.activeHost.value ?: return
        if (workspace.isBlank()) {
            AppToast.makeText(requireContext(), getString(R.string.code_home_need_folder), AppToast.LENGTH_SHORT).show()
            pickFolder()
            return
        }
        if (starting) return
        starting = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = hub.startSession(NewSessionRequest(host.id, harness, workspace, prompt, permission))
            starting = false
            result.onSuccess { id ->
                composer.clear()
                composer.hideKeyboard()
                openSession(id)
            }.onFailure {
                AppToast.makeText(requireContext(), getString(R.string.code_home_start_failed, it.message ?: "?"), AppToast.LENGTH_LONG).show()
            }
        }
    }

    private fun openSession(id: String) {
        // The session is a full screen above the chat screen, like the RP hub.
        requireParentFragment().parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .add(R.id.fragment_container, CodeSessionFragment.newInstance(id))
            .addToBackStack(CodeSessionFragment.BACK_STACK_TAG)
            .commit()
    }

    private fun openSettings() {
        requireParentFragment().parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .add(R.id.fragment_container, CodeSettingsFragment())
            .addToBackStack("code_settings")
            .commit()
    }

    // ── list ──────────────────────────────────────────────────────────────────────────────

    private sealed class HomeItem(val key: String) {
        data class Header(val host: CodeHost, val conn: ConnectionState) : HomeItem("header")
        data class Hero(val host: CodeHost, val harness: HarnessKind, val workspace: String) : HomeItem("hero")
        object Onboard : HomeItem("onboard")
        data class Section(val title: String) : HomeItem("section:$title")
        data class Session(val s: CodeSessionState) : HomeItem("s:${s.summary.id}") {
            // Only what the row shows, so streaming tokens don't rebind the whole list.
            val shown = listOf(s.summary.title, s.summary.preview, s.status, s.summary.updatedAt / 60_000)
            override fun equals(other: Any?) = other is Session && other.shown == shown
            override fun hashCode() = shown.hashCode()
        }
    }

    private inner class HomeAdapter : ListAdapter<HomeItem, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<HomeItem>() {
        override fun areItemsTheSame(a: HomeItem, b: HomeItem) = a.key == b.key
        override fun areContentsTheSame(a: HomeItem, b: HomeItem) = a == b
    }) {
        override fun getItemViewType(position: Int) = when (getItem(position)) {
            is HomeItem.Header -> 0
            is HomeItem.Hero -> 1
            HomeItem.Onboard -> 2
            is HomeItem.Section -> 3
            is HomeItem.Session -> 4
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val res = when (viewType) {
                0 -> R.layout.item_code_home_header
                1 -> R.layout.item_code_home_hero
                2 -> R.layout.item_code_home_onboard
                3 -> R.layout.item_code_section
                else -> R.layout.item_code_session
            }
            return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(res, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            when (val item = getItem(position)) {
                is HomeItem.Header -> {
                    v.findViewById<TextView>(R.id.codeMachineName).text = item.host.name
                    val (label, on) = when {
                        item.host.isDemo -> R.string.code_status_demo to true
                        item.conn == ConnectionState.CONNECTED -> R.string.code_status_connected to true
                        item.conn == ConnectionState.CONNECTING -> R.string.code_status_connecting to false
                        item.conn == ConnectionState.FAILED -> R.string.code_status_failed to false
                        else -> R.string.code_status_offline to false
                    }
                    v.findViewById<TextView>(R.id.codeMachineStatus).setText(label)
                    v.findViewById<View>(R.id.codeMachineDot).background.mutate().setTint(
                        requireContext().getColor(if (on) R.color.code_status_on else R.color.code_status_off))
                    v.findViewById<View>(R.id.codeMachinePill).setOnClickListener { pickMachine(it) }
                    v.findViewById<View>(R.id.codeHomeSettings).setOnClickListener { openSettings() }
                }
                is HomeItem.Hero -> v.findViewById<TextView>(R.id.codeHeroSubtitle).text = getString(
                    R.string.code_home_subtitle, item.harness.displayName,
                    CodeComposer.folderName(item.workspace).ifBlank { "…" }, item.host.name
                )
                HomeItem.Onboard -> {
                    v.findViewById<View>(R.id.codeOnboardDemo).setOnClickListener { hub.addDemoHost() }
                    v.findViewById<View>(R.id.codeOnboardAdd).setOnClickListener {
                        CodeHostDialog.show(this@CodeHomeFragment, null)
                    }
                }
                is HomeItem.Section -> (v as TextView).text = item.title
                is HomeItem.Session -> bindSession(v, item.s)
            }
        }

        private fun bindSession(v: View, s: CodeSessionState) {
            val sum = s.summary
            v.findViewById<TextView>(R.id.codeSessionGlyph).text = sum.harness.shortName.take(1)
            v.findViewById<TextView>(R.id.codeSessionRowTitle).text = sum.title
            val now = System.currentTimeMillis()
            val ago = if (now - sum.updatedAt < DateUtils.MINUTE_IN_MILLIS) getString(R.string.code_just_now)
            else DateUtils.getRelativeTimeSpanString(sum.updatedAt, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
            v.findViewById<TextView>(R.id.codeSessionRowMeta).text =
                listOf(sum.harness.displayName, CodeComposer.folderName(sum.workspace), ago).joinToString("  ·  ")
            v.findViewById<TextView>(R.id.codeSessionRowPreview).apply {
                text = sum.preview
                isVisible = sum.preview.isNotBlank()
            }
            val badge = v.findViewById<TextView>(R.id.codeSessionBadge)
            when (s.status) {
                SessionStatus.NEEDS_APPROVAL -> {
                    badge.isVisible = true
                    badge.setText(R.string.code_badge_needs_you)
                    badge.setBackgroundResource(R.drawable.bg_code_badge)
                    badge.setTextColor(requireContext().getColor(R.color.code_badge_fg))
                }
                SessionStatus.RUNNING -> {
                    badge.isVisible = true
                    badge.setText(R.string.code_badge_working)
                    badge.setBackgroundResource(R.drawable.bg_code_badge_quiet)
                    badge.setTextColor(requireContext().getColor(R.color.xai_ink))
                }
                else -> badge.isVisible = false
            }
            v.findViewById<View>(R.id.codeSessionCard).setOnClickListener { openSession(sum.id) }
        }
    }
}
