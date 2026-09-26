package io.github.stardomains3.oxproxion.code

import android.content.ClipData
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
import io.github.stardomains3.oxproxion.GrokConfirmDialog
import io.github.stardomains3.oxproxion.Motion
import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations
import io.github.stardomains3.oxproxion.PickerPopover
import io.github.stardomains3.oxproxion.R
import io.github.stardomains3.oxproxion.SwipeNavLayout
import kotlinx.coroutines.flow.combine
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        hub = CodeHub.get(requireContext())
        val backdrop = view.findViewById<GlassBackdropLayout>(R.id.codeSessionBackdrop)
        val frame = view.findViewById<FrameLayout>(R.id.codeSessionFrame)
        list = view.findViewById(R.id.codeTranscript)
        adapter = CodeTranscriptAdapter(requireContext(),
            onApproval = { e, opt ->
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                hub.answer(sessionId, e.requestId, opt)
            },
            onOpenDiff = { e -> openDiff(e) })
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

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateTopEdge()
        })

        composer = CodeComposer(
            view.findViewById<View>(R.id.codeSessionComposer) as GlassLinearLayout, frame, backdrop
        )
        composer.showPills(agent = false, folder = false, permission = true)
        composer.onSend = { text ->
            follow = true
            if (hub.prompt(sessionId, text)) composer.clear()
        }
        composer.onStop = { hub.cancel(sessionId) }
        // Keep the last event clear of the composer, whatever its height.
        val dock = view.findViewById<View>(R.id.codeSessionDock)
        dock.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight,
                bottom - top + (16 * resources.displayMetrics.density).toInt())
            if (follow && adapter.itemCount > 0) list.post { followEdge(adapter.itemCount - 1) }
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
                            CodeComposer.folderName(s.summary.workspace).ifEmpty { null },
                            s.summary.branch
                        ).joinToString("  ·  ")
                        composer.running = s.running
                        composer.setPermission(s.summary.permissionMode)
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
        adapter.submitList(rows) {
            if (follow && rows.isNotEmpty()) list.post { followEdge(rows.size - 1) }
            list.post { updateTopEdge() }
        }
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

    private fun openChanges() {
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .add(R.id.fragment_container, CodeChangesFragment.newInstance(sessionId))
            .addToBackStack(null)
            .commit()
    }

    private fun close() {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        composer.hideKeyboard()
        parentFragmentManager.popBackStack()
    }

    companion object {
        private const val ARG_ID = "session_id"
        const val BACK_STACK_TAG = "code_session"

        fun newInstance(sessionId: String) = CodeSessionFragment().apply { arguments = bundleOf(ARG_ID to sessionId) }
    }
}
