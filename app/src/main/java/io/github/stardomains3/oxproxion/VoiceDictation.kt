package io.github.stardomains3.oxproxion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton

/**
 * Puts [VoiceInput] into a composer: tap the mic, talk, tap again, and the words land in [input]
 * at the cursor. Nothing is ever sent. While listening the mic turns into a steady "done"
 * check, [swapOut] (the model pill, say) gives way to a [VoiceWaveView] that follows the voice,
 * and words still being recognized show in a dimmer gray until they settle.
 *
 * Must be created in onViewCreated or earlier (it registers the mic permission request).
 */
class VoiceDictation(
    private val fragment: Fragment,
    private val input: EditText,
    private val micButton: MaterialButton,
    private val wave: VoiceWaveView,
    /** Asked each time the wave shows or hides, so a view that is hidden on purpose stays hidden. */
    private val swapOut: () -> List<View>,
    transcribe: suspend (ByteArray, String, String) -> Result<String>,
) : VoiceInput.Listener {

    private val context = fragment.requireContext()
    private val prefs = SharedPreferencesHelper(context)
    private val engine = VoiceInput(context, fragment.viewLifecycleOwner.lifecycleScope, transcribe, this)
    private val pendingColor = ContextCompat.getColor(context, R.color.xai_mute)
    private val animate get() = Motion.areAnimationsEnabled(context)

    private val permission = fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            engine.start()
        } else if (!fragment.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Just refused, yet the system won't ask again: only the app's settings can turn it on.
            GrokConfirmDialog.show(
                fragment = fragment,
                title = context.getString(R.string.voice_mic_blocked_title),
                message = context.getString(R.string.voice_mic_blocked_message),
                confirmText = context.getString(R.string.action_open_app_settings),
                onConfirm = { openAppSettings() },
                destructive = false,
            )
        } else {
            GlassNotice.show(context, context.getString(R.string.toast_mic_permission))
        }
    }

    private fun openAppSettings() {
        runCatching {
            fragment.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            )
        }
    }

    // The span of [input] this dictation owns: separator + settled words + words in flight.
    private var regionStart = 0
    private var regionEnd = 0
    private var settled = StringBuilder()
    private var pending = ""
    private var capitalize = false
    /** Last state we were told about; the UI follows this, not the engine's internals. */
    private var state = VoiceInput.State.IDLE

    val isActive: Boolean get() = state != VoiceInput.State.IDLE

    init {
        // Idle starts, listening finishes, and a tap while transcribing gives that up (engine.toggle).
        micButton.setOnClickListener {
            if (state == VoiceInput.State.IDLE) begin() else engine.toggle()
        }
        fragment.viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) = engine.finishNow()
            override fun onDestroy(owner: LifecycleOwner) = engine.release()
        })
        wave.visibility = View.GONE
        refresh()
    }

    /** Re-check whether voice can run (engine setting, recognizer on the phone) and show the mic. */
    fun refresh() {
        micButton.visibility = if (isActive || engine.resolveEngine() != null) View.VISIBLE else View.GONE
    }

    /** Start from outside (assistant launch). Same as tapping the mic. */
    fun begin() {
        if (state != VoiceInput.State.IDLE) return
        if (engine.resolveEngine() == null) {
            onError(context.getString(R.string.voice_unavailable))
            return
        }
        // A missing key or model should say so now, before the mic permission prompt and a recording.
        engine.preflightError()?.let {
            onError(context.getString(it))
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            engine.start()
        }
    }

    /** True while a recording is being turned into text. */
    val isTranscribing: Boolean get() = state == VoiceInput.State.TRANSCRIBING

    /**
     * Keep what was heard and stop now, before sending. Returns true when the caller may go on
     * and send. A Phone dictation has its words immediately, but a recording still has to be
     * transcribed: then this says so and returns false, so the message isn't sent without the
     * words the user just spoke (they land in the composer when the text is ready).
     */
    fun finishNow(): Boolean {
        engine.finishNow()
        if (!isTranscribing) return true
        GlassNotice.show(context, context.getString(R.string.voice_still_transcribing))
        return false
    }

    // ── VoiceInput.Listener ─────────────────────────────────────────────────────────────

    override fun onStateChanged(state: VoiceInput.State) {
        this.state = state
        when (state) {
            VoiceInput.State.LISTENING -> {
                haptic()
                openRegion()
                wave.mode = VoiceWaveView.Mode.LISTENING
                wave.reset()
                showWave(true)
                micButton.isEnabled = true
                micButton.isSelected = true
                micButton.setIconResource(R.drawable.ic_check)
                micButton.contentDescription = context.getString(R.string.cd_voice_done)
            }
            VoiceInput.State.TRANSCRIBING -> {
                haptic()
                wave.mode = VoiceWaveView.Mode.WORKING
                // Transcribing can take minutes on a slow link: the button becomes a way out.
                micButton.isEnabled = true
                micButton.isSelected = false
                micButton.setIconResource(R.drawable.ic_close_x)
                micButton.contentDescription = context.getString(R.string.cd_voice_cancel_transcribing)
                settleMic()
            }
            VoiceInput.State.IDLE -> {
                if (micButton.isSelected) haptic()
                closeRegion()
                showWave(false)
                micButton.isEnabled = true
                micButton.isSelected = false
                micButton.setIconResource(R.drawable.ic_mic)
                micButton.contentDescription = context.getString(R.string.cd_voice_input)
                settleMic()
                refresh()
            }
        }
    }

    override fun onPartial(text: String) {
        pending = text.trim()
        render()
    }

    override fun onCommit(text: String) {
        val late = state == VoiceInput.State.IDLE
        if (late) {
            // Cloud/local result: the region closed while transcribing, so reopen it for the paste.
            openRegion()
        }
        pending = ""
        if (settled.isNotEmpty() && !settled.last().isWhitespace()) settled.append(' ')
        settled.append(text.trim())
        render()
        if (late) closeRegion()
    }

    override fun onLevel(level: Float) {
        // Only the wave follows the voice. The check stays still: it is the button that ends
        // dictation, and a target that swells under the finger is harder to hit.
        wave.setLevel(level)
    }

    override fun onError(message: String) {
        GlassNotice.show(context, message)
    }

    // ── Text ────────────────────────────────────────────────────────────────────────────

    private fun openRegion() {
        val text = input.text
        val caret = if (input.hasFocus()) input.selectionEnd else -1
        regionStart = if (caret in 0..text.length) caret else text.length
        regionEnd = regionStart
        settled = StringBuilder()
        pending = ""
        val before = text.subSequence(0, regionStart).trimEnd()
        capitalize = before.isEmpty() || before.last() in ".!?\n"
    }

    private fun render() {
        val editable = input.text
        val start = regionStart.coerceIn(0, editable.length)
        val end = regionEnd.coerceIn(start, editable.length)
        val out = SpannableStringBuilder()
        if (start > 0 && !editable[start - 1].isWhitespace() && (settled.isNotEmpty() || pending.isNotEmpty())) {
            out.append(' ')
        }
        out.append(settled)
        if (pending.isNotEmpty()) {
            if (settled.isNotEmpty()) out.append(' ')
            val from = out.length
            out.append(pending)
            out.setSpan(ForegroundColorSpan(pendingColor), from, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (capitalize) {
            val i = out.indexOfFirst { !it.isWhitespace() }
            if (i >= 0) out.replace(i, i + 1, out[i].uppercaseChar().toString())
        }
        editable.replace(start, end, out)
        regionStart = start
        regionEnd = start + out.length
        input.setSelection(regionEnd.coerceAtMost(input.text.length))
    }

    /** Settle the gray words and leave the caret after them, ready to type or dictate more. */
    private fun closeRegion() {
        if (pending.isNotEmpty()) {
            settled.append(if (settled.isEmpty()) pending else " $pending")
            pending = ""
        }
        render()
        val editable = input.text
        editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
            .filter { editable.getSpanStart(it) >= regionStart && editable.getSpanEnd(it) <= regionEnd }
            .forEach { editable.removeSpan(it) }
        val end = regionEnd.coerceAtMost(editable.length)
        if (end < editable.length && !editable[end].isWhitespace() && regionEnd > regionStart) {
            editable.insert(end, " ")
        }
        regionStart = end
        regionEnd = end
        settled = StringBuilder()
        capitalize = false
    }

    // ── Chrome ──────────────────────────────────────────────────────────────────────────

    private fun showWave(show: Boolean) {
        val pill = swapOut()
        val outViews = if (show) pill else listOf(wave)
        val inViews = if (show) listOf(wave) else pill
        outViews.forEach { v ->
            v.animate().cancel()
            if (!animate) { v.visibility = View.GONE; return@forEach }
            v.animate().alpha(0f).setStartDelay(0).setDuration(FADE_MS).setInterpolator(Motion.easeOut)
                .withEndAction { v.visibility = View.GONE }.start()
        }
        inViews.forEach { v ->
            v.animate().cancel()
            v.visibility = View.VISIBLE
            if (!animate) { v.alpha = 1f; return@forEach }
            v.alpha = 0f
            v.animate().alpha(1f).setStartDelay(FADE_MS / 2).setDuration(FADE_MS)
                .setInterpolator(Motion.easeOut).start()
        }
    }

    private fun settleMic() {
        if (!animate) {
            micButton.scaleX = 1f
            micButton.scaleY = 1f
            return
        }
        micButton.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(Motion.spring).start()
    }

    private fun haptic() {
        if (prefs.getHapticButtons()) micButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private companion object {
        const val FADE_MS = 160L
    }
}
