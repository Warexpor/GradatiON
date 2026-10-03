package io.github.stardomains3.oxproxion.code

import android.content.ClipData
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipboardManager
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.stardomains3.oxproxion.GlassNotice
import io.github.stardomains3.oxproxion.GlassBackdropLayout
import io.github.stardomains3.oxproxion.GlassFrameLayout
import io.github.stardomains3.oxproxion.GlassLinearLayout
import io.github.stardomains3.oxproxion.GlassTextView
import io.github.stardomains3.oxproxion.GrokConfirmDialog
import io.github.stardomains3.oxproxion.GrokInputDialog
import io.github.stardomains3.oxproxion.Motion
import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations
import io.github.stardomains3.oxproxion.PickerPopover
import io.github.stardomains3.oxproxion.R
import io.github.stardomains3.oxproxion.SharedPreferencesHelper
import io.github.stardomains3.oxproxion.SwipeNavLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One agent session: live transcript (text, tool calls, diffs, approvals, plan) under floating
 * glass chrome, with a composer that replies or stops. Swipe right from the left edge to go back.
 */
class CodeSessionFragment : Fragment(R.layout.fragment_code_session) {

    private val sessionId by lazy { requireArguments().getString(ARG_ID)!! }
    private lateinit var hub: CodeHub
    private lateinit var adapter: CodeTranscriptAdapter
    private lateinit var list: RecyclerView
    private lateinit var sheet: GlassFrameLayout
    private lateinit var composer: CodeComposer
    /** Follow the growing edge until the user drags away (same rule as chat). */
    private var follow = true
    private lateinit var approvalBar: GlassTextView
    private lateinit var prefs: SharedPreferencesHelper
    /** Pending approval requestIds already announced with an arrival haptic (once per request). */
    private val haptickedApprovalIds = mutableSetOf<String>()
    /** Pending approval we are currently pinning, if any. */
    private var pinnedApprovalId: String? = null
    /** Change-gates for [updateApprovalBar] — avoid per-frame text/visibility churn on scroll. */
    private var barBoundVisible: Boolean = false
    private var barBoundRequestId: String? = null
    private var barBoundTitle: String? = null
    private var barBoundHarness: String? = null

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(CodePromptImages.MAX_COUNT)
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        ingestImages(uris)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        hub = CodeHub.get(requireContext())
        // User is looking at this session — drop any away notifications for it.
        hub.awayNotifier.cancelSession(sessionId)
        val backdrop = view.findViewById<GlassBackdropLayout>(R.id.codeSessionBackdrop)
        val frame = view.findViewById<FrameLayout>(R.id.codeSessionFrame)
        list = view.findViewById(R.id.codeTranscript)
        sheet = view.findViewById(R.id.codeSessionSheet)
        // The sheet sits inside the backdrop the chrome blurs, so it can't find its own source.
        sheet.glass.source = view.findViewById(R.id.codeSessionAmbientBackdrop)
        sheet.clipToOutline = true
        // Hold the ambient background still while the list scrolls (it re-samples the glass).
        val ambient = view.findViewById<io.github.stardomains3.oxproxion.AmbientBackgroundView>(R.id.codeAmbient)
        list.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                ambient?.setScrolling(newState != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE)
            }
        })
        adapter = CodeTranscriptAdapter(
            requireContext(),
            decodeScope = viewLifecycleOwner.lifecycleScope,
            onApproval = { e, opt ->
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                hub.answer(sessionId, e.requestId, opt) {
                    // Nothing went out: the card gets its buttons back, and the person hears why.
                    if (::adapter.isInitialized) adapter.approvalFailed(e.requestId)
                    context?.let { GlassNotice.show(it, getString(R.string.code_approval_send_failed)) }
                }
            },
            onOpenDiff = { e -> openDiff(e) },
            onOpenToolOutput = { e -> openToolOutput(e) },
        )
        list.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = false }
        adapter.verbose = hub.store.showThinking
        list.setHasFixedSize(true)
        list.setItemViewCacheSize(8)
        list.adapter = adapter
        list.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            supportsChangeAnimations = false
            addDuration = 220
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) follow = false
                if (newState == RecyclerView.SCROLL_STATE_IDLE && !rv.canScrollVertically(1)) follow = true
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val state = hub.sessions.value[sessionId]
                if (state !== approvalScanState) {
                    approvalScanState = state
                    approvalScanPending = state?.events?.any { it is CodeEvent.Approval && it.pending } == true
                }
                if (approvalScanPending) updateApprovalBar(state)
            }
        })

        prefs = SharedPreferencesHelper(requireContext())
        approvalBar = view.findViewById(R.id.codeSessionApprovalBar)
        approvalBar.glass.source = backdrop
        approvalBar.setOnClickListener { scrollToPinnedApproval() }

        composer = CodeComposer(
            view.findViewById<View>(R.id.codeSessionComposer) as GlassLinearLayout, frame, backdrop,
            viewLifecycleOwner,
        )
        composer.showPills(agent = false, folder = false, permission = true)
        composer.enableVoice(this)
        val draft = hub.sessionDraft(sessionId)
        if (draft != null) {
            if (draft.text.isNotEmpty()) {
                composer.input.setText(draft.text)
                composer.input.setSelection(draft.text.length)
            }
            draft.attachments.forEach { composer.addAttachment(it) }
        }
        composer.onSend = { text, attachments ->
            follow = true
            if (hub.prompt(sessionId, text, attachments)) {
                hub.clearSessionDraft(sessionId)
                composer.clear()
            }
        }
        composer.onStop = { hub.cancel(sessionId) }
        composer.onAttachClick = {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        // The sheet hangs from under the header to just below the composer, which floats inside
        // it; the last event stays clear of the composer whatever its height.
        val dock = view.findViewById<View>(R.id.codeSessionDock)
        val top = view.findViewById<View>(R.id.codeSessionTop)
        dock.addOnLayoutChangeListener { _, _, t, _, b, _, oldT, _, oldB ->
            if (t != oldT || b != oldB) fitTranscript(dock)
            if (follow && adapter.itemCount > 0) list.post { followEdge(adapter.itemCount - 1) }
            list.post { updateApprovalBar() }
        }
        top.addOnLayoutChangeListener { _, _, _, _, b, _, _, _, oldB -> if (b != oldB) fitSheetTop(b) }
        sheet.addOnLayoutChangeListener { _, _, t, _, b, _, oldT, _, oldB -> if (t != oldT || b != oldB) fitTranscript(dock) }
        // Edge to edge like chat: the backdrop runs under the system bars; only the chrome and
        // the sheet are inset.
        val topPad = top.paddingTop
        val dockPad = dock.paddingBottom
        val sheetSide = (sheet.layoutParams as ViewGroup.MarginLayoutParams).marginStart
        val sheetBottom = (sheet.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(bars.bottom, ime.bottom)
            val rtl = frame.layoutDirection == View.LAYOUT_DIRECTION_RTL
            top.setPadding(bars.left, topPad + bars.top, bars.right, top.paddingBottom)
            dock.setPadding(bars.left, dock.paddingTop, bars.right, dockPad + bottomInset)
            sheet.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart = sheetSide + if (rtl) bars.right else bars.left
                marginEnd = sheetSide + if (rtl) bars.left else bars.right
                bottomMargin = sheetBottom + bottomInset
            }
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(frame)
        composer.permissionPill.setOnClickListener {
            val cur = hub.sessions.value[sessionId]?.summary?.permissionMode ?: PermissionMode.ASK
            composer.pickPermission(cur) { hub.setPermissionMode(sessionId, it) }
        }

        view.findViewById<View>(R.id.codeSessionBack).setOnClickListener { close() }
        view.findViewById<View>(R.id.codeSessionMore).setOnClickListener { showOptions(it) }

        (view as SwipeNavLayout).listener = object : SwipeNavLayout.Listener {
            override fun canStart(x: Float, y: Float) = x < 40 * resources.displayMetrics.density
            override fun onDrag(dx: Float) {
                frame.translationX = dx.coerceAtLeast(0f) * 0.9f
            }
            override fun onCommit(direction: Int) {
                if (direction > 0) close() else onCancel()
            }
            override fun onCancel() {
                val fling = Motion.flingX(frame, 0f, (view as SwipeNavLayout).releaseVelocity, response = 0.38f)
                frame.animate().translationX(0f).setDuration(fling.duration).setInterpolator(fling).start()
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!composer.dismissPopover()) close()
            }
        })

        hub.attach(sessionId)
        observe(view)
    }

    override fun onDestroyView() {
        if (::hub.isInitialized && ::composer.isInitialized) {
            hub.parkSessionDraft(
                sessionId,
                composer.input.text?.toString().orEmpty(),
                composer.attachmentsSnapshot(),
            )
        }
        // An idle session stops being tracked on the machine link once its screen is gone.
        if (::hub.isInitialized) hub.release(sessionId)
        // Recycle the rows so their per-row work (the "Working" sweep, stream fades) stops.
        list.adapter = null
        stateArtHarness = null
        super.onDestroyView()
    }

    private fun observe(view: View) {
        val title = view.findViewById<TextView>(R.id.codeSessionTitle)
        val subtitle = view.findViewById<TextView>(R.id.codeSessionSubtitle)
        val mark = view.findViewById<android.widget.ImageView>(R.id.codeSessionMark)
        val led = view.findViewById<View>(R.id.codeSessionHeaderLed)
        val banner = view.findViewById<TextView>(R.id.codeSessionBanner)
        val stateView = view.findViewById<TextView>(R.id.codeSessionState)
        // Tapping the banner (or the failed-load message) connects again instead of waiting out the backoff.
        banner.setOnClickListener { hub.hosts.value.find { it.id == hub.sessions.value[sessionId]?.summary?.hostId }?.let { hub.connect(it) } }
        stateView.setOnClickListener {
            val host = hub.hosts.value.find { it.id == hub.sessions.value[sessionId]?.summary?.hostId } ?: return@setOnClickListener
            hub.connect(host)
            hub.attach(sessionId)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    hub.sessions.map { it[sessionId] }.distinctUntilChanged().collect { s ->
                        if (s == null) { close(); return@collect }
                        title.text = s.summary.title
                        mark.setImageResource(s.summary.harness.iconRes)
                        mark.isActivated = s.status == SessionStatus.RUNNING || s.status == SessionStatus.NEEDS_APPROVAL
                        val ledRes = when (s.status) {
                            SessionStatus.NEEDS_APPROVAL -> R.drawable.bg_code_led_on
                            SessionStatus.RUNNING -> R.drawable.bg_code_led_wait
                            else -> 0
                        }
                        led.isVisible = ledRes != 0
                        if (ledRes != 0) led.setBackgroundResource(ledRes)
                        subtitle.text = listOfNotNull(
                            s.summary.harness.displayName,
                            s.summary.model?.let { CodeModelSelection.pillLabel(it) },
                            CodeComposer.folderName(s.summary.workspace).ifEmpty { null },
                            s.summary.branch
                        ).joinToString("  ·  ")
                        composer.running = s.running
                        // Y4: setModel before setPermission so short permission label uses modelPill visibility.
                        // No session/set_model on the wire yet — show read-only current model when known.
                        val model = s.summary.model
                        if (!model.isNullOrBlank()) {
                            composer.setModel(model, listOf(model), editable = false)
                        } else {
                            composer.setModel(null, emptyList())
                        }
                        composer.setPermission(s.summary.permissionMode)
                        composer.availableCommands = s.availableCommands
                        composer.input.hint = getString(R.string.code_session_reply_hint, s.summary.harness.shortName)
                        bindStateView(stateView, s)
                        render(s)
                    }
                }
                launch {
                    // M1: banner must track this session's host, not whatever machine is active
                    // on home (away-open / host switch under an open session).
                    combine(hub.sessions, hub.connections, hub.hosts) { sessions, conns, hosts ->
                        val hostId = sessions[sessionId]?.summary?.hostId
                        val host = hosts.find { it.id == hostId }
                        val conn = hostId?.let { conns[it] } ?: ConnectionState.DISCONNECTED
                        host to conn
                    }.distinctUntilChanged().collect { (h, c) ->
                        if (h == null || h.isDemo ||
                            (c != ConnectionState.FAILED &&
                                c != ConnectionState.DISCONNECTED &&
                                c != ConnectionState.CONNECTING)
                        ) {
                            banner.isVisible = false
                        } else {
                            banner.isVisible = true
                            banner.setCompoundDrawablesRelativeWithIntrinsicBounds(
                                if (c == ConnectionState.CONNECTING) R.drawable.ic_code_led_wait else R.drawable.ic_code_led_off,
                                0, 0, 0,
                            )
                            banner.text = if (c == ConnectionState.CONNECTING) {
                                getString(R.string.code_status_connecting)
                            } else {
                                val err = hub.lastErrorOf(h.id)
                                buildString {
                                    append(getString(R.string.code_session_offline_banner, h.name))
                                    append('\n').append(getString(R.string.code_session_reconnect))
                                    if (!err.isNullOrBlank()) append('\n').append(err)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * What the empty transcript says: loading, waiting for the agent's first word, or that the
     * history could not be loaded (tap to retry). Gone as soon as there is anything to show.
     */
    private fun bindStateView(view: TextView, s: CodeSessionState) {
        val text = when {
            s.events.isNotEmpty() -> null
            s.attachError != null -> getString(R.string.code_session_attach_failed)
            s.attaching || !s.attached -> getString(R.string.code_session_loading)
            !s.running -> getString(R.string.code_session_empty)
            else -> null
        }
        view.isVisible = text != null
        if (text != null) {
            view.text = text
            if (stateArtHarness != s.summary.harness) {
                stateArtHarness = s.summary.harness
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(null, stateArt(s.summary.harness), null, null)
            }
        }
        view.isClickable = s.events.isEmpty() && s.attachError != null
        // The retry line reads as something to tap; loading and waiting stay quiet.
        view.setTextColor(requireContext().getColor(if (view.isClickable) R.color.xai_ink else R.color.xai_mute))
    }

    private var stateArtHarness: HarnessKind? = null

    /** The agent's mark inside the hairline orbits, above the loading, waiting or retry line. */
    private fun stateArt(harness: HarnessKind): android.graphics.drawable.Drawable? {
        val ctx = requireContext()
        val rings = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_code_rings) ?: return null
        val glyph = androidx.core.content.ContextCompat.getDrawable(ctx, harness.iconRes)?.mutate() ?: return rings
        glyph.setTint(ctx.getColor(R.color.xai_ink))
        val size = (28 * resources.displayMetrics.density).toInt()
        return android.graphics.drawable.LayerDrawable(arrayOf(rings, glyph)).apply {
            setLayerSize(1, size, size)
            setLayerGravity(1, android.view.Gravity.CENTER)
        }
    }

    private fun render(s: CodeSessionState) {
        val rows = ArrayList<TranscriptRow>(s.events.size + 1)
        s.events.mapTo(rows) { TranscriptRow.Event(it) }
        val streamingText = s.events.lastOrNull() is CodeEvent.AgentText && (s.events.last() as CodeEvent.AgentText).streaming
        val waiting = s.status == SessionStatus.NEEDS_APPROVAL
        if (s.running && !streamingText && !waiting) rows += TranscriptRow.Working
        maybeHapticApprovalArrival(s.events)
        adapter.submitList(rows) {
            if (follow && rows.isNotEmpty()) list.post { followEdge(rows.size - 1) }
            list.post { updateApprovalBar(s) }
        }
    }

    private fun updateApprovalBar(state: CodeSessionState? = hub.sessions.value[sessionId]) {
        if (!::approvalBar.isInitialized) return
        if (state == null) {
            pinnedApprovalId = null
            applyApprovalBarVisibility(false)
            clearApprovalBarTextGate()
            return
        }
        val rows = adapter.currentList
        val lm = list.layoutManager as? LinearLayoutManager
        val clearTop = list.paddingTop
        val clearBottom = list.height - list.paddingBottom
        fun isOffClearViewport(index: Int): Boolean {
            if (lm == null) return CodeApprovalBar.shouldShowBar(index, null, null, clearTop, clearBottom)
            val child = lm.findViewByPosition(index)
            val top = child?.let { lm.getDecoratedTop(it) }
            val bottom = child?.let { lm.getDecoratedBottom(it) }
            return CodeApprovalBar.shouldShowBar(index, top, bottom, clearTop, clearBottom)
        }
        // Pin first unanswered that is outside the clear viewport (X1/X3); null ⇒ hide.
        val pinned = CodeApprovalBar.findPinnedPending(state.events, rows, ::isOffClearViewport)
        pinnedApprovalId = pinned?.requestId
        if (pinned == null) {
            applyApprovalBarVisibility(false)
            clearApprovalBarTextGate()
            return
        }
        val harness = state.summary.harness.shortName
        if (pinned.requestId != barBoundRequestId ||
            pinned.title != barBoundTitle ||
            harness != barBoundHarness
        ) {
            approvalBar.text = getString(R.string.code_approval_bar, harness, pinned.title)
            approvalBar.contentDescription = getString(R.string.cd_code_approval_bar)
            barBoundRequestId = pinned.requestId
            barBoundTitle = pinned.title
            barBoundHarness = harness
        }
        applyApprovalBarVisibility(true)
    }

    private fun applyApprovalBarVisibility(show: Boolean) {
        if (barBoundVisible == show) return
        barBoundVisible = show
        approvalBar.isVisible = show
    }

    private fun clearApprovalBarTextGate() {
        barBoundRequestId = null
        barBoundTitle = null
        barBoundHarness = null
    }

    private fun maybeHapticApprovalArrival(events: List<CodeEvent>) {
        val pendingIds = CodeApprovalBar.pendingRequestIds(events)
        val fresh = CodeApprovalBar.announceNewPendingIds(pendingIds, haptickedApprovalIds)
        if (fresh.isEmpty()) return
        if (!prefs.getHapticResponding()) return
        // One arrival haptic per newly pending request (not only the first unanswered).
        repeat(fresh.size) {
            view?.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        }
    }

    private fun scrollToPinnedApproval() {
        val id = pinnedApprovalId ?: return
        val index = CodeApprovalBar.indexOfApproval(adapter.currentList, id)
        if (index < 0) return
        follow = false
        list.smoothScrollToPosition(index)
        pulseApprovalRow(index)
    }

    private fun pulseApprovalRow(position: Int) {
        list.postDelayed({
            if (!isAdded) return@postDelayed
            val card = list.findViewHolderForAdapterPosition(position)
                ?.itemView?.findViewById<View>(R.id.codeApprovalCard)
                ?: return@postDelayed
            card.animate().cancel()
            card.alpha = 1f
            card.animate()
                .alpha(0.4f)
                .setDuration(140)
                .setInterpolator(Motion.easeOut)
                .withEndAction {
                    card.animate()
                        .alpha(1f)
                        .setDuration(280)
                        .setInterpolator(Motion.spring)
                        .start()
                }
                .start()
        }, 320)
    }

    private fun followEdge(last: Int) {
        val lm = list.layoutManager as LinearLayoutManager
        if (lm.findLastVisibleItemPosition() < last - 1) {
            list.scrollToPosition(last)
            return
        }
        val remaining = list.computeVerticalScrollRange() - list.computeVerticalScrollOffset() - list.computeVerticalScrollExtent()
        if (remaining > 0) list.smoothScrollBy(0, remaining)
    }

    private var approvalScanState: CodeSessionState? = null
    private var approvalScanPending = true

    /** The sheet starts where the header ends, so the title row never sits on the transcript. */
    private fun fitSheetTop(headerBottom: Int) {
        if ((sheet.layoutParams as ViewGroup.MarginLayoutParams).topMargin == headerBottom) return
        sheet.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = headerBottom }
    }

    /** Clear room at the list's foot for the part of the dock (composer, pins) floating over the sheet. */
    private fun fitTranscript(dock: View) {
        if (sheet.height == 0 || dock.height == 0) return
        val pad = (sheet.bottom - dock.top).coerceAtLeast(0) + (16 * resources.displayMetrics.density).toInt()
        if (pad != list.paddingBottom) list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, pad)
    }

    private fun showOptions(anchor: View) {
        val s = hub.sessions.value[sessionId] ?: return
        val rows = ArrayList<PickerPopover.Row>()
        if (s.running) rows += PickerPopover.Row(getString(R.string.code_session_stop), iconRes = R.drawable.ic_stop) { hub.cancel(sessionId) }
        val thinking = hub.store.showThinking
        rows += PickerPopover.Row(
            getString(if (thinking) R.string.code_verbosity_normal else R.string.code_verbosity_thinking),
            subtitle = getString(if (thinking) R.string.code_verbosity_normal_hint else R.string.code_verbosity_thinking_hint),
            iconRes = R.drawable.ic_code_bulb
        ) {
            hub.store.showThinking = !thinking
            adapter.verbose = !thinking
        }
        rows += PickerPopover.Row(getString(R.string.code_session_changes), iconRes = R.drawable.ic_code_branch) {
            openChanges()
        }
        rows += PickerPopover.Row(getString(R.string.code_home_rename), iconRes = R.drawable.ic_code_pencil) {
            promptRename(s)
        }
        rows += PickerPopover.Row(getString(R.string.code_session_copy_id), subtitle = sessionId, iconRes = R.drawable.ic_copi) {
            requireContext().getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("session", sessionId))
            GlassNotice.show(requireContext(), getString(R.string.code_session_copied))
        }
        val started = DateUtils.getRelativeTimeSpanString(s.summary.createdAt).toString()
        rows += PickerPopover.Row(getString(R.string.code_session_forget), subtitle = started, iconRes = R.drawable.ic_code_trash) {
            GrokConfirmDialog.show(this, getString(R.string.code_session_forget), s.summary.title, getString(R.string.code_host_remove), {
                hub.forget(sessionId)
            })
        }
        composer.pick(anchor, null, rows)
    }

    private fun promptRename(s: CodeSessionState) {
        GrokInputDialog.show(
            this,
            getString(R.string.code_home_rename),
            getString(R.string.code_home_rename),
            s.summary.title,
            getString(R.string.code_host_save),
        ) { newTitle ->
            if (newTitle.isNotBlank()) hub.rename(sessionId, newTitle)
        }
    }

    private fun openDiff(e: CodeEvent.FileDiff) {
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .add(R.id.fragment_container, CodeDiffFragment.newInstance(sessionId, e.key))
            .addToBackStack(null)
            .commit()
    }

    private fun openToolOutput(e: CodeEvent.ToolCall) {
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .add(R.id.fragment_container, CodeToolOutputFragment.newInstance(sessionId, e.key))
            .addToBackStack(null)
            .commit()
    }

    private fun openChanges() {
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .add(R.id.fragment_container, CodeChangesFragment.newInstance(sessionId))
            .addToBackStack(null)
            .commit()
    }

    private fun close() {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        composer.dismissPopover()
        composer.hideKeyboard()
        parentFragmentManager.popBackStack()
    }


    private fun ingestImages(uris: List<Uri>) {
        if (!::composer.isInitialized) return
        viewLifecycleOwner.lifecycleScope.launch {
            for (uri in uris) {
                if (composer.attachmentCount >= CodePromptImages.MAX_COUNT) {
                    GlassNotice.show(requireContext(), getString(R.string.code_attach_limit, CodePromptImages.MAX_COUNT))
                    break
                }
                val mime = requireContext().contentResolver.getType(uri)?.lowercase()
                if (mime != null && mime !in setOf("image/jpeg", "image/png", "image/webp")) {
                    GlassNotice.show(requireContext(), getString(R.string.code_attach_unsupported))
                    continue
                }
                val encoded = withContext(Dispatchers.IO) { CodePromptImages.fromUri(requireContext(), uri) }
                if (!isAdded) return@launch
                val att = when (encoded) {
                    is CodePromptImages.Result.Ok -> encoded.attachment
                    is CodePromptImages.Result.TooLarge -> {
                        GlassNotice.show(requireContext(), getString(R.string.code_attach_too_large))
                        continue
                    }
                    is CodePromptImages.Result.Failed -> {
                        GlassNotice.show(requireContext(), getString(R.string.code_attach_failed))
                        continue
                    }
                }
                if (!composer.addAttachment(att)) {
                    GlassNotice.show(requireContext(), getString(R.string.code_attach_limit, CodePromptImages.MAX_COUNT))
                    break
                }
            }
        }
    }

    companion object {
        private const val ARG_ID = "session_id"
        const val BACK_STACK_TAG = "code_session"

        fun newInstance(sessionId: String) = CodeSessionFragment().apply { arguments = bundleOf(ARG_ID to sessionId) }
    }
}
