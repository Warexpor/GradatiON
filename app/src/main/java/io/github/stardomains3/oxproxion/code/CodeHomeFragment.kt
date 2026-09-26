package io.github.stardomains3.oxproxion.code

import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
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

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(CodePromptImages.MAX_COUNT)
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        ingestImages(uris)
    }

    private var harness: HarnessKind = HarnessKind.CLAUDE_CODE
    private var workspace: String = ""
    private var permission: PermissionMode = PermissionMode.ASK
    private var boundHostId: String? = null
    private var starting = false
    private var searchQuery: String = ""
    /** Bound search EditText (RV row); cleared on host switch / IME hide. */
    private var searchField: EditText? = null
    private var lastHost: CodeHost? = null
    private var lastConn: ConnectionState = ConnectionState.DISCONNECTED

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
        list.itemAnimator?.removeDuration = 0
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return 0
                return if (adapter.currentList.getOrNull(pos) is HomeItem.Session) {
                    makeMovementFlags(0, ItemTouchHelper.LEFT)
                } else {
                    0
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                val item = adapter.currentList.getOrNull(pos) as? HomeItem.Session
                if (item == null) {
                    if (pos != RecyclerView.NO_POSITION) adapter.notifyItemChanged(pos)
                    return
                }
                val id = item.s.summary.id
                // Sync drop the row so ItemTouchHelper sees it gone before DiffUtil commits.
                val next = adapter.currentList.filterNot {
                    it is HomeItem.Session && it.s.summary.id == id
                }
                adapter.submitList(next)
                hub.forget(id)
                AppToast.makeText(
                    requireContext(),
                    getString(R.string.code_home_removed),
                    AppToast.LENGTH_SHORT
                ).show()
            }
        }).attachToRecyclerView(list)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val under = rv.computeVerticalScrollOffset().toFloat()
                onScrollEdge?.invoke((255 * (under / (24f * resources.displayMetrics.density)).coerceIn(0f, 1f)).toInt())
            }
        })
        if (topInset > 0) list.updatePadding(top = topInset)

        composer = CodeComposer(view.findViewById<View>(R.id.codeHomeComposer) as GlassLinearLayout, root, backdrop, viewLifecycleOwner)
        composer.root.addOnLayoutChangeListener { v, _, top, _, _, _, oldTop, _, _ ->
            if (top != oldTop) list.updatePadding(bottom = root.height - top + (16 * resources.displayMetrics.density).toInt())
        }
        permission = hub.store.defaultPermissionMode
        composer.setPermission(permission)
        composer.agentPill.setOnClickListener { pickAgent() }
        composer.folderPill.setOnClickListener { pickFolder() }
        composer.permissionPill.setOnClickListener {
            composer.pickPermission(permission) {
                permission = it
                hub.store.defaultPermissionMode = it
                composer.setPermission(it)
            }
        }
        composer.onSend = { text, attachments -> start(text, attachments) }
        composer.onAttachClick = {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                syncPermissionFromStore()
                hub.connect()
                combine(hub.activeHost, hub.connection, hub.sessions) { h, c, _ -> h to c }.collect { (host, conn) ->
                    bindHost(host)
                    render(host, conn)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Code settings is added without hiding us, so we stay STARTED; re-read default here.
        syncPermissionFromStore()
        // Pairing dialog is owned solely by CodeModeHost (see onPairingArrived).
    }

    override fun onPause() {
        hideSearchKeyboard()
        super.onPause()
    }

    override fun onDestroyView() {
        hideSearchKeyboard()
        searchField = null
        super.onDestroyView()
    }

    private fun syncPermissionFromStore() {
        if (!::composer.isInitialized || !::hub.isInitialized) return
        val stored = hub.store.defaultPermissionMode
        if (permission != stored) {
            permission = stored
            composer.setPermission(stored)
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
        if (host == null) {
            if (boundHostId != null) {
                boundHostId = null
                clearSearchQuery()
            }
            return
        }
        if (host.id == boundHostId) return
        boundHostId = host.id
        // U3: do not carry machine A's filter onto machine B.
        clearSearchQuery()
        harness = host.defaultHarness
        workspace = host.recentWorkspaces.firstOrNull() ?: host.defaultWorkspace
        refreshPills()
    }

    /** Drop the in-memory filter and sync/clear the bound search field. */
    private fun clearSearchQuery() {
        searchQuery = ""
        val et = searchField
        if (et != null && et.text?.toString()?.isNotEmpty() == true) {
            et.setText("")
        }
    }

    /** Clear search focus and hide the soft keyboard (U2). */
    private fun hideSearchKeyboard() {
        val et = searchField
        et?.clearFocus()
        val token = et?.windowToken
            ?: (if (::list.isInitialized) list.windowToken else null)
            ?: view?.windowToken
            ?: return
        val imm = context?.getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun refreshPills() {
        composer.agentPill.text = harness.shortName
        composer.folderPill.text = if (workspace.isBlank()) getString(R.string.code_home_pick_workspace) else CodeComposer.folderName(workspace)
        composer.input.hint = getString(R.string.code_home_composer_hint, harness.shortName)
    }

    private fun render(host: CodeHost?, conn: ConnectionState) {
        lastHost = host
        lastConn = conn
        val items = ArrayList<HomeItem>()
        if (host == null) {
            items += HomeItem.Onboard
        } else {
            items += HomeItem.Header(host, conn)
            val sessions = hub.sessionsFor(host.id)
            if (sessions.isEmpty()) {
                searchQuery = ""
                items += HomeItem.Hero(host, harness, workspace)
            } else {
                items += HomeItem.Search
                val filtered = CodeSessionFilter.filterSessions(searchQuery, sessions)
                if (filtered.isEmpty()) {
                    items += HomeItem.FilterEmpty
                } else {
                    val (active, recent) = filtered.partition { it.running || it.status == SessionStatus.NEEDS_APPROVAL }
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
        }
        adapter.submitList(items)
    }

    private fun promptRename(session: CodeSessionState) {
        GrokInputDialog.show(
            this,
            getString(R.string.code_home_rename),
            getString(R.string.code_home_rename),
            session.summary.title,
            getString(R.string.code_host_save)
        ) { newTitle ->
            if (newTitle.isNotBlank()) hub.rename(session.summary.id, newTitle)
        }
    }

    // ── pickers ───────────────────────────────────────────────────────────────────────────

    private fun pickAgent() {
        viewLifecycleOwner.lifecycleScope.launch {
            val remote = hub.harnesses()
            val rows = if (remote.isNotEmpty()) {
                remote.map { info ->
                    val subtitle = when {
                        !info.available -> getString(R.string.code_home_harness_unavailable)
                        info.models.isNotEmpty() -> info.models.take(3).joinToString(", ")
                        else -> null
                    }
                    PickerPopover.Row(
                        info.name,
                        subtitle = subtitle,
                        iconRes = R.drawable.ic_code_terminal,
                        selected = info.kind == harness
                    ) {
                        // Still allow picking an unavailable harness so the user can set a default
                        // before installing; the bridge will reject session/new if it can't launch.
                        harness = info.kind
                        refreshPills()
                    }
                }
            } else {
                HarnessKind.entries.filter { it != HarnessKind.CUSTOM }.map { k ->
                    PickerPopover.Row(k.displayName, iconRes = R.drawable.ic_code_terminal, selected = k == harness) {
                        harness = k
                        refreshPills()
                    }
                }
            }
            composer.pick(composer.agentPill, getString(R.string.code_home_pick_agent), rows)
        }
    }

    private fun pickFolder() {
        val host = hub.activeHost.value ?: return
        viewLifecycleOwner.lifecycleScope.launch { showFolderRecents(host) }
    }

    /** Recent / known workspaces; footer opens multi-level browse or a typed path. */
    private suspend fun showFolderRecents(host: CodeHost) {
        val folders = hub.workspaces(harness).ifEmpty { listOfNotNull(host.defaultWorkspace.ifBlank { null }) }
        composer.pick(
            composer.folderPill,
            getString(R.string.code_home_folder_prompt, host.name),
            folders.map { path ->
                PickerPopover.Row(
                    CodeComposer.folderName(path),
                    subtitle = path,
                    iconRes = R.drawable.ic_code_folder,
                    selected = path == workspace
                ) {
                    workspace = path
                    refreshPills()
                }
            },
            footer = listOf(
                PickerPopover.Row(getString(R.string.code_home_browse_folders), iconRes = R.drawable.ic_code_folder) {
                    val start = BrowsePaths.normalize(
                        workspace.ifBlank { host.defaultWorkspace }.ifBlank { "~" }
                    )
                    viewLifecycleOwner.lifecycleScope.launch { showFolderBrowse(host, start) }
                },
                typePathRow()
            )
        )
    }

    /**
     * Multi-level folder browser on the existing popover: path title as breadcrumbs, Up/parent,
     * drill into dirs via [hub.browseResult], and an explicit "Use this folder" footer.
     */
    private suspend fun showFolderBrowse(host: CodeHost, path: String) {
        val current = BrowsePaths.normalize(path)
        val parent = BrowsePaths.parentOf(current)
        val browseResult = hub.browseResult(current)
        val children = browseResult.getOrNull().orEmpty().filter { it.dir }
        val browseHint = when {
            browseResult.isFailure -> getString(R.string.code_home_folder_browse_failed)
            children.isEmpty() -> getString(R.string.code_home_folder_empty)
            else -> null
        }
        val rows = ArrayList<PickerPopover.Row>()
        if (parent != null) {
            rows += PickerPopover.Row(
                getString(R.string.code_home_folder_up),
                subtitle = parent,
                iconRes = R.drawable.ic_chevron_left
            ) {
                viewLifecycleOwner.lifecycleScope.launch { showFolderBrowse(host, parent) }
            }
        } else {
            rows += PickerPopover.Row(
                getString(R.string.code_home_folder_recents),
                iconRes = R.drawable.ic_chevron_left
            ) {
                viewLifecycleOwner.lifecycleScope.launch { showFolderRecents(host) }
            }
        }
        children.forEach { entry ->
            val child = BrowsePaths.child(current, entry.name)
            rows += PickerPopover.Row(
                entry.name,
                subtitle = child,
                iconRes = R.drawable.ic_code_folder,
                selected = child == workspace
            ) {
                viewLifecycleOwner.lifecycleScope.launch { showFolderBrowse(host, child) }
            }
        }
        composer.pick(
            composer.folderPill,
            BrowsePaths.breadcrumbTitle(current),
            rows,
            footer = listOf(
                PickerPopover.Row(
                    getString(R.string.code_home_use_folder),
                    subtitle = current,
                    iconRes = R.drawable.ic_code_check,
                    selected = current == workspace
                ) {
                    workspace = current
                    refreshPills()
                },
                typePathRow()
            ),
            hint = browseHint
        )
    }

    private fun typePathRow() = PickerPopover.Row(
        getString(R.string.code_home_new_folder),
        iconRes = R.drawable.ic_code_plus
    ) {
        GrokInputDialog.show(
            this@CodeHomeFragment,
            getString(R.string.code_home_pick_workspace),
            "~/code/project",
            workspace,
            getString(R.string.code_host_save)
        ) {
            if (it.isNotBlank()) {
                workspace = it.trim()
                refreshPills()
            }
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

    private fun start(prompt: String, attachments: List<PromptAttachment> = emptyList()) {
        val host = hub.activeHost.value ?: return
        if (workspace.isBlank()) {
            AppToast.makeText(requireContext(), getString(R.string.code_home_need_folder), AppToast.LENGTH_SHORT).show()
            pickFolder()
            return
        }
        if (starting) return
        starting = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                syncPermissionFromStore()
                val result = hub.startSession(
                    NewSessionRequest(host.id, harness, workspace, prompt, permission, attachments = attachments)
                )
                result.onSuccess { id ->
                    composer.clear()
                    composer.hideKeyboard()
                    openSession(id)
                }.onFailure {
                    AppToast.makeText(requireContext(), getString(R.string.code_home_start_failed, it.message ?: "?"), AppToast.LENGTH_LONG).show()
                }
            } finally {
                // View teardown cancels this job; clear the guard so a later start isn't stuck.
                starting = false
            }
        }
    }

    private fun ingestImages(uris: List<Uri>) {
        if (!::composer.isInitialized) return
        viewLifecycleOwner.lifecycleScope.launch {
            for (uri in uris) {
                if (composer.attachmentCount >= CodePromptImages.MAX_COUNT) {
                    AppToast.makeText(
                        requireContext(),
                        getString(R.string.code_attach_limit, CodePromptImages.MAX_COUNT),
                        AppToast.LENGTH_SHORT
                    ).show()
                    break
                }
                val mime = requireContext().contentResolver.getType(uri)?.lowercase()
                if (mime != null && mime !in setOf("image/jpeg", "image/png", "image/webp")) {
                    AppToast.makeText(requireContext(), getString(R.string.code_attach_unsupported), AppToast.LENGTH_SHORT).show()
                    continue
                }
                val att = withContext(Dispatchers.IO) { CodePromptImages.fromUri(requireContext(), uri) }
                if (!isAdded) return@launch
                if (att == null) {
                    AppToast.makeText(requireContext(), getString(R.string.code_attach_failed), AppToast.LENGTH_SHORT).show()
                    continue
                }
                if (!composer.addAttachment(att)) {
                    AppToast.makeText(
                        requireContext(),
                        getString(R.string.code_attach_limit, CodePromptImages.MAX_COUNT),
                        AppToast.LENGTH_SHORT
                    ).show()
                    break
                }
            }
        }
    }

    private fun openSession(id: String) {
        hideSearchKeyboard()
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
        object Search : HomeItem("search")
        object FilterEmpty : HomeItem("filter_empty")
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
            HomeItem.Search -> 5
            HomeItem.FilterEmpty -> 6
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val res = when (viewType) {
                0 -> R.layout.item_code_home_header
                1 -> R.layout.item_code_home_hero
                2 -> R.layout.item_code_home_onboard
                3 -> R.layout.item_code_section
                5 -> R.layout.item_code_home_search
                6 -> R.layout.item_code_home_filter_empty
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
                HomeItem.Search -> bindSearch(v)
                HomeItem.FilterEmpty -> Unit
            }
        }

        private fun bindSearch(v: View) {
            val et = v.findViewById<EditText>(R.id.codeHomeSearch)
            searchField = et
            if (et.getTag(R.id.codeHomeSearch) != true) {
                et.setTag(R.id.codeHomeSearch, true)
                et.doAfterTextChanged { editable ->
                    val q = editable?.toString().orEmpty()
                    if (q == searchQuery) return@doAfterTextChanged
                    searchQuery = q
                    render(lastHost, lastConn)
                }
                et.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        hideSearchKeyboard()
                        true
                    } else {
                        false
                    }
                }
            }
            if (et.text?.toString() != searchQuery) {
                et.setText(searchQuery)
                et.setSelection(searchQuery.length)
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
            val card = v.findViewById<View>(R.id.codeSessionCard)
            card.setOnClickListener { openSession(sum.id) }
            card.setOnLongClickListener {
                promptRename(s)
                true
            }
        }
    }
}
