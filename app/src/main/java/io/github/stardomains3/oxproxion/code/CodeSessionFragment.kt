package io.github.stardomains3.oxproxion.code

import android.content.ClipData
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipboardManager
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.stardomains3.oxproxion.AppToast
import io.github.stardomains3.oxproxion.GlassBackdropLayout
import io.github.stardomains3.oxproxion.GlassLinearLayout
import io.github.stardomains3.oxproxion.GlassTextView
import io.github.stardomains3.oxproxion.GrokConfirmDialog
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
    private lateinit var composer: CodeComposer
    /** Follow the growing edge until the user drags away (same rule as chat). */
    private var follow = true
    private lateinit var approvalBar: GlassTextView
    private lateinit var prefs: SharedPreferencesHelper
    /** Last pending approval requestId we hapticked for (arrival only once per request). */
    private var haptickedApprovalId: String? = null
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
        adapter = CodeTranscriptAdapter(requireContext(),
            onApproval = { e, opt ->
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                hub.answer(sessionId, e.requestId, opt)
            },
            onOpenDiff = { e -> openDiff(e) },
            onOpenToolOutput = { e -> openToolOutput(e) })
        list.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = false }
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
                updateTopEdge()
                updateApprovalBar()
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
        composer.onSend = { text, attachments ->
            follow = true
            if (hub.prompt(sessionId, text, attachments)) composer.clear()
        }
        composer.onStop = { hub.cancel(sessionId) }
        composer.onAttachClick = {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        // Keep the last event clear of the composer, whatever its height.
        val dock = view.findViewById<View>(R.id.codeSessionDock)
        dock.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight,
                bottom - top + (16 * resources.displayMetrics.density).toInt())
            if (follow && adapter.itemCount > 0) list.post { followEdge(adapter.itemCount - 1) }
            list.post { updateApprovalBar() }
        }
        composer.permissionPill.setOnClickListener {
            val cur = hub.sessions.value[sessionId]?.summary?.permissionMode ?: PermissionMode.ASK
            composer.pickPermission(cur) { hub.setPermissionMode(sessionId, it) }
        }

        view.findViewById<View>(R.id.codeSessionBack).setOnClickListener { close() }
        view.findViewById<View>(R.id.codeSessionMore).setOnClickListener { showOptions(it) }
        view.findViewById<View>(R.id.codeSessionTop).background?.mutate()?.alpha = 0

        (view as SwipeNavLayout).listener = object : SwipeNavLayout.Listener {
            override fun canStart(x: Float, y: Float) = x < 40 * resources.displayMetrics.density
            override fun onDrag(dx: Float) {
                frame.translationX = dx.coerceAtLeast(0f) * 0.9f
            }
            override fun onCommit(direction: Int) {
                if (direction > 0) close() else onCancel()
            }
            override fun onCancel() {
                frame.animate().translationX(0f).setDuration(380).setInterpolator(Motion.spring).start()
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

    private fun observe(view: View) {
        val title = view.findViewById<TextView>(R.id.codeSessionTitle)
        val subtitle = view.findViewById<TextView>(R.id.codeSessionSubtitle)
        val banner = view.findViewById<TextView>(R.id.codeSessionBanner)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    hub.sessions.map { it[sessionId] }.distinctUntilChanged().collect { s ->
                        if (s == null) { close(); return@collect }
                        title.text = s.summary.title
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
                        render(s)
                    }
                }
                launch {
                    combine(hub.connection, hub.activeHost) { c, h -> c to h }.collect { (c, h) ->
                        if (h == null || h.isDemo ||
                            (c != ConnectionState.FAILED &&
                                c != ConnectionState.DISCONNECTED &&
                                c != ConnectionState.CONNECTING)
                        ) {
                            banner.isVisible = false
                        } else {
                            banner.isVisible = true
                            banner.text = if (c == ConnectionState.CONNECTING) {
                                getString(R.string.code_status_connecting)
                            } else {
                                getString(R.string.code_session_offline_banner, h.name)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun render(s: CodeSessionState) {
        val rows = ArrayList<TranscriptRow>(s.events.size + 1)
        s.events.mapTo(rows) { TranscriptRow.Event(it) }
        val streamingText = s.events.lastOrNull() is CodeEvent.AgentText && (s.events.last() as CodeEvent.AgentText).streaming
        val waiting = s.status == SessionStatus.NEEDS_APPROVAL
        if (s.running && !streamingText && !waiting) rows += TranscriptRow.Working
        val pending = CodeApprovalBar.findPending(s.events)
        maybeHapticApprovalArrival(pending)
        adapter.submitList(rows) {
            if (follow && rows.isNotEmpty()) list.post { followEdge(rows.size - 1) }
            list.post {
                updateTopEdge()
                updateApprovalBar(s)
            }
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

    private fun maybeHapticApprovalArrival(pending: CodeEvent.Approval?) {
        val id = pending?.requestId
        if (id == null) {
            haptickedApprovalId = null
            return
        }
        if (id == haptickedApprovalId) return
        haptickedApprovalId = id
        if (!prefs.getHapticResponding()) return
        view?.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
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

    private fun updateTopEdge() {
        val fade = view?.findViewById<View>(R.id.codeSessionTop)?.background ?: return
        val under = list.computeVerticalScrollOffset().toFloat()
        val a = (255 * (under / (24f * resources.displayMetrics.density)).coerceIn(0f, 1f)).toInt()
        if (fade.alpha != a) fade.alpha = a
    }

    private fun showOptions(anchor: View) {
        val s = hub.sessions.value[sessionId] ?: return
        val rows = ArrayList<PickerPopover.Row>()
        if (s.running) rows += PickerPopover.Row(getString(R.string.code_session_stop), iconRes = R.drawable.ic_stop) { hub.cancel(sessionId) }
        rows += PickerPopover.Row(getString(R.string.code_session_changes), iconRes = R.drawable.ic_code_branch) {
            openChanges()
        }
        rows += PickerPopover.Row(getString(R.string.code_session_copy_id), subtitle = sessionId, iconRes = R.drawable.ic_copi) {
            requireContext().getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("session", sessionId))
            AppToast.makeText(requireContext(), getString(R.string.code_session_copied), AppToast.LENGTH_SHORT).show()
        }
        val started = DateUtils.getRelativeTimeSpanString(s.summary.createdAt).toString()
        rows += PickerPopover.Row(getString(R.string.code_session_forget), subtitle = started, iconRes = R.drawable.ic_code_trash) {
            GrokConfirmDialog.show(this, getString(R.string.code_session_forget), s.summary.title, getString(R.string.code_host_remove), {
                hub.forget(sessionId)
            })
        }
        composer.pick(anchor, null, rows)
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
                val encoded = withContext(Dispatchers.IO) { CodePromptImages.fromUri(requireContext(), uri) }
                if (!isAdded) return@launch
                val att = when (encoded) {
                    is CodePromptImages.Result.Ok -> encoded.attachment
                    is CodePromptImages.Result.TooLarge -> {
                        AppToast.makeText(requireContext(), getString(R.string.code_attach_too_large), AppToast.LENGTH_SHORT).show()
                        continue
                    }
                    is CodePromptImages.Result.Failed -> {
                        AppToast.makeText(requireContext(), getString(R.string.code_attach_failed), AppToast.LENGTH_SHORT).show()
                        continue
                    }
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

    companion object {
        private const val ARG_ID = "session_id"
        const val BACK_STACK_TAG = "code_session"

        fun newInstance(sessionId: String) = CodeSessionFragment().apply { arguments = bundleOf(ARG_ID to sessionId) }
    }
}
