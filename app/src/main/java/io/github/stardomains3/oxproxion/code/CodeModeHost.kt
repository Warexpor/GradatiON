package io.github.stardomains3.oxproxion.code

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import io.github.stardomains3.oxproxion.AppToast
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

    private val hub = CodeHub.get(fragment.requireContext())
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

    /** Called after the tab row changes selection, so ChatFragment can move its indicator. */
    var onTabsChanged: (() -> Unit)? = null

    init {
        tab.contentDescription = fragment.getString(R.string.mode_tab_code_a11y)
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
    }

    /** Enable the Code tab, activate it, and show [CodeHostDialog] if a pending pair remains. */
    private fun onPairingArrived() {
        hub.store.enabled = true
        hub.store.lastTabWasCode = true
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
                AppToast.makeText(
                    fragment.requireContext(),
                    "Could not open pairing form",
                    AppToast.LENGTH_SHORT
                ).show()
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
        val enabled = hub.store.enabled
        tab.isVisible = enabled
        if (!enabled && isActive) deactivate()
        if (enabled && restore && hub.store.lastTabWasCode && !isActive) activate(animate = false)
    }

    fun activate(animate: Boolean = true) {
        if (isActive || !hub.store.enabled) return
        isActive = true
        hub.store.lastTabWasCode = true
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
        hub.store.lastTabWasCode = false
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
