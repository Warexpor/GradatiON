package io.github.stardomains3.oxproxion.code

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.stardomains3.oxproxion.code.store.CodeStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import io.github.stardomains3.oxproxion.GlassNotice
import io.github.stardomains3.oxproxion.GlassBackdropLayout
import io.github.stardomains3.oxproxion.GlassFrameLayout
import io.github.stardomains3.oxproxion.GlassIconButton
import io.github.stardomains3.oxproxion.GlassLinearLayout
import io.github.stardomains3.oxproxion.GlassMaterial
import io.github.stardomains3.oxproxion.GlassTextView
import io.github.stardomains3.oxproxion.Motion
import io.github.stardomains3.oxproxion.R
import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

/**
 * Plugs Code mode into the chat screen with as little of ChatFragment as possible: owns the
 * third top tab, the container the Code home lives in, and which chat chrome hides while it is
 * showing. ChatFragment only forwards tab taps and asks [isActive] where Code changes behaviour.
 *
 * Code isn't a ChatMode: the chat view model keeps its Chat/Roleplay mode untouched underneath,
 * so switching back lands exactly where the user left the conversation.
 */
class CodeModeHost(private val fragment: Fragment, private val root: View) {

    /**
     * Prefs only. The hub (encrypted hosts, sessions, backends) is built the first time Code is
     * actually used, so opening the chat does not pay for a mode that is off.
     */
    private val store = CodeStore(fragment.requireContext())
    private val hub by lazy { CodeHub.get(fragment.requireContext()) }
    private var approvalWatch: Job? = null
    val tab: TextView = root.findViewById(R.id.tabCode)
    private val container: ViewGroup = root.findViewById(R.id.codeModeContainer)
    private val topBar: View = root.findViewById(R.id.topBarGlass)
    /** Chat-only chrome hidden while Code shows (composer, its fade, the transcript). */
    private val chatOnly: List<View> = listOfNotNull(
        root.findViewById(R.id.composerDock),
        root.findViewById(R.id.composerFade),
        root.findViewById(R.id.extendedTopBarScroll)
    )
    private val transcript: View? = root.findViewById(R.id.chatFrameView)
    private val chatBackdrop: GlassBackdropLayout? = root.findViewById(R.id.chatBackdrop)
    private val hiddenVisibility = HashMap<View, Int>()

    var isActive = false
        private set

    /** Some session is blocked on an approval. The dot on the Code tab says so from the other tabs. */
    private var needsApproval = false
    private val approvalDot = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(root.context.getColor(R.color.xai_ink))
    }

    /** Called after the tab row changes selection, so ChatFragment can move its indicator. */
    var onTabsChanged: (() -> Unit)? = null

    /**
     * Runs just before Code covers Chat (tab, restore, away notification, or pairing).
     * ChatFragment parks Ask text and a live stage here so paths that skip [enterCodeMode]
     * do not leave a chip only on hidden live fields that leaveCodeMode would wipe.
     */
    var onBeforeActivate: (() -> Unit)? = null

    init {
        tab.contentDescription = fragment.getString(R.string.mode_tab_code_a11y)
        tab.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeApprovalDot() }
        watchApprovals()
        topBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) home()?.topInset = topBar.height
        }
        refresh(restore = true)
        // Deep link / QR scan: enable Code, switch tab, open prefilled host dialog.
        // Observes here so ChatFragment stays untouched.
        fragment.lifecycleScope.launch {
            fragment.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CodePairPending.pending.collect { pairing ->
                    if (pairing == null) return@collect
                    onPairingArrived()
                }
            }
        }
        // A scan that produced no pairing (bad QR, camera denied) says why when the chat is back.
        fragment.lifecycleScope.launch {
            fragment.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CodePairPending.error.collect { message ->
                    if (message == null) return@collect
                    val err = CodePairPending.consumeError() ?: return@collect
                    // offer() may have won after this emit; don't toast a superseded failure.
                    if (CodePairPending.peek() != null) return@collect
                    GlassNotice.show(fragment.requireContext(), err)
                }
            }
        }
        // Away-notification tap: enable Code, switch tab, open the session.
        fragment.lifecycleScope.launch {
            fragment.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CodeSessionPending.pending.collect { sessionId ->
                    if (sessionId == null) return@collect
                    onSessionPendingArrived()
                }
            }
        }
    }

    /** Starts following sessions for the tab dot, once Code is on (and never before, so the hub stays unbuilt). */
    private fun watchApprovals() {
        if (approvalWatch?.isActive == true || !store.enabled) return
        approvalWatch = fragment.lifecycleScope.launch {
            fragment.repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.sessions
                    .map { all -> all.values.any { it.status == SessionStatus.NEEDS_APPROVAL } }
                    .distinctUntilChanged()
                    .collect {
                        needsApproval = it
                        refreshApprovalDot()
                    }
            }
        }
    }

    /** Shows the dot only while Code is not the tab on screen (there, the list already says it). */
    private fun refreshApprovalDot() {
        val show = needsApproval && !isActive
        tab.overlay.remove(approvalDot)
        if (show) {
            tab.overlay.add(approvalDot)
            placeApprovalDot()
        }
        tab.contentDescription = fragment.getString(
            if (show) R.string.code_tab_needs_you_a11y else R.string.mode_tab_code_a11y
        )
    }

    /** A small disc at the top-end of the label; an overlay, so the tab's size and text never move. */
    private fun placeApprovalDot() {
        if (tab.width == 0) return
        val d = tab.resources.displayMetrics.density
        val size = (5 * d).toInt()
        val textW = tab.paint.measureText(tab.text.toString())
        val left = ((tab.width + textW) / 2f + 1.5f * d).toInt()
        val cy = (tab.height - tab.paddingBottom + tab.paddingTop) / 2f
        val top = (cy - tab.textSize * 0.42f - size / 2f).toInt()
        approvalDot.setBounds(left, top, left + size, top + size)
    }

    /** Enable Code, activate tab, open the queued session from an away notification. */
    private fun onSessionPendingArrived() {
        store.enabled = true
        store.lastTabWasCode = true
        if (!tab.isVisible) refresh(restore = false)
        tab.isVisible = true
        if (!isActive) activate(animate = true)
        container.post {
            val id = CodeSessionPending.consume() ?: return@post
            // Stale tap after forget/removeHost: do not push an empty session screen (A5).
            if (hub.sessions.value[id] == null) return@post
            hub.awayNotifier.cancelSession(id)
            val fm = fragment.parentFragmentManager
            val top = fm.fragments.asReversed().filterIsInstance<CodeSessionFragment>().firstOrNull { it.isAdded }
            if (top != null && top.arguments?.getString("session_id") == id) return@post
            // Avoid stacking duplicates of the same session.
            if (top != null) {
                // Pop existing session screens back to chat, then open target.
                fm.popBackStack(CodeSessionFragment.BACK_STACK_TAG, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            }
            fm.beginTransaction()
                .withGrokStackAnimations()
                .add(R.id.fragment_container, CodeSessionFragment.newInstance(id))
                .addToBackStack(CodeSessionFragment.BACK_STACK_TAG)
                .commit()
        }
    }

    /** Enable the Code tab, activate it, and show [CodeHostDialog] if a pending pair remains. */
    private fun onPairingArrived() {
        store.enabled = true
        store.lastTabWasCode = true
        if (!tab.isVisible) refresh(restore = false)
        tab.isVisible = true
        if (!isActive) activate(animate = true)
        // Single consumer: prefer Settings (Scan-from-Settings) over covered Home.
        container.post {
            val taken = CodePairPending.consume() ?: return@post
            val target = topCodeFragment()
            if (target != null && target.isAdded) {
                CodeHostDialog.show(target, null, taken)
            } else {
                // Home missing after commitNow — toast + clear; do not re-offer (no tight loop).
                GlassNotice.show(fragment.requireContext(), fragment.getString(R.string.code_pair_form_failed))
            }
        }
    }

    /**
     * Fragment that should host the pairing dialog: top [CodeSettingsFragment] on the
     * activity back stack if present, otherwise [CodeHomeFragment].
     */
    private fun topCodeFragment(): Fragment? {
        val settings = fragment.parentFragmentManager.fragments
            .asReversed()
            .filterIsInstance<CodeSettingsFragment>()
            .firstOrNull { it.isAdded }
        if (settings != null) return settings
        return home()?.takeIf { it.isAdded }
    }

    /** Re-reads the setting (e.g. back from Settings). Hides the tab and leaves Code if it was turned off. */
    fun refresh(restore: Boolean = false) {
        val enabled = store.enabled
        if (enabled) watchApprovals()
        tab.isVisible = enabled
        if (!enabled && isActive) deactivate()
        if (enabled && restore && store.lastTabWasCode && !isActive) activate(animate = false)
    }

    fun activate(animate: Boolean = true) {
        if (isActive || !store.enabled) return
        onBeforeActivate?.invoke()
        isActive = true
        refreshApprovalDot()
        store.lastTabWasCode = true
        val fm = fragment.childFragmentManager
        if (fm.findFragmentByTag(TAG) == null) {
            fm.beginTransaction().replace(R.id.codeModeContainer, CodeHomeFragment().also { it.topInset = topBar.height }, TAG).commitNow()
        }
        home()?.onScrollEdge = { a -> topBar.background?.let { if (it.alpha != a) it.alpha = a } }
        topBar.background?.alpha = 0
        chatOnly.forEach { v ->
            hiddenVisibility[v] = v.visibility
            v.visibility = View.GONE
        }
        container.visibility = View.VISIBLE
        if (animate && Motion.areAnimationsEnabled(root.context)) {
            container.alpha = 0f
            container.translationX = 24 * root.resources.displayMetrics.density
            container.animate().alpha(1f).translationX(0f).setDuration(280).setInterpolator(Motion.iosOut)
                .withEndAction { transcript?.visibility = View.INVISIBLE }.start()
        } else {
            transcript?.visibility = View.INVISIBLE
        }
        retargetTopGlass(home()?.view?.findViewById(R.id.codeHomeBackdrop))
        selectTabs()
    }

    fun deactivate() {
        if (!isActive) return
        isActive = false
        refreshApprovalDot()
        store.lastTabWasCode = false
        home()?.dismissPopover()
        transcript?.visibility = View.VISIBLE
        chatOnly.forEach { v -> v.visibility = hiddenVisibility.remove(v) ?: View.VISIBLE }
        container.animate().cancel()
        container.visibility = View.GONE
        retargetTopGlass(chatBackdrop)
        topBar.background?.alpha = 0
        tab.isSelected = false
        onTabsChanged?.invoke()
    }

    /** Keep the tab row's selection right: ChatFragment sets Chat/Roleplay; Code overrides both. */
    fun selectTabs() {
        if (!isActive) {
            tab.isSelected = false
            return
        }
        val row = tab.parent as ViewGroup
        for (i in 0 until row.childCount) (row.getChildAt(i) as? TextView)?.isSelected = false
        tab.isSelected = true
        onTabsChanged?.invoke()
    }

    /** Top-bar "new" while Code shows: jump to the composer. */
    fun onNewPressed() {
        home()?.focusComposer()
    }

    /** Top-bar menu while Code shows: machines and Code settings. */
    fun onMenuPressed() {
        fragment.parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .add(R.id.fragment_container, CodeSettingsFragment())
            .addToBackStack("code_settings")
            .commit()
    }

    fun onBackPressed(): Boolean = isActive && home()?.dismissPopover() == true

    private fun home(): CodeHomeFragment? =
        if (fragment.isAdded) fragment.childFragmentManager.findFragmentByTag(TAG) as? CodeHomeFragment else null

    /** The floating top-bar glass should sample whatever is under it now, not the hidden transcript. */
    private fun retargetTopGlass(source: GlassBackdropLayout?) {
        source ?: return
        fun walk(v: View) {
            glassOf(v)?.source = source
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(topBar)
    }

    private fun glassOf(v: View): GlassMaterial? = when (v) {
        is GlassIconButton -> v.glass
        is GlassTextView -> v.glass
        is GlassFrameLayout -> v.glass
        is GlassLinearLayout -> v.glass
        else -> null
    }

    private companion object {
        const val TAG = "code_home"
    }
}
