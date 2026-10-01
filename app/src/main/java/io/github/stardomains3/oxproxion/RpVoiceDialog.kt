package io.github.stardomains3.oxproxion

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButtonToggleGroup
import java.util.Locale

/**
 * The voice page: picks the system voice a roleplay character reads aloud with, plus pitch and speed. Voices
 * are the engine's for the phone's language, named "Voice 1…" because engine ids mean nothing
 * to people; tapping one previews it. [tts] is null (or has no voices) when the engine isn't
 * ready, and then only pitch and speed apply to the default voice.
 */
object RpVoiceDialog {

    private val PITCH = floatArrayOf(0.8f, 1f, 1.2f)
    private val RATE = floatArrayOf(0.85f, 1f, 1.2f)

    /** Voices worth offering: the phone's language, not ones flagged as uninstalled. */
    fun voicesFor(tts: TextToSpeech?, locale: Locale = Locale.getDefault()): List<Voice> =
        runCatching { tts?.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == locale.language }
            .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.locale.country != locale.country }, { it.name }))

    /** "Voice 3" for a saved voice name, or null when it's the default. */
    fun label(fragment: Fragment, tts: TextToSpeech?, name: String?): String? {
        if (name == null) return null
        val i = voicesFor(tts).indexOfFirst { it.name == name }
        return if (i >= 0) fragment.getString(R.string.rp_voice_n, i + 1) else null
    }

    /** Wires the voice page's [sheet]; [onChange] gets the whole choice after every tap, and each tap previews it. */
    fun bind(
        fragment: Fragment,
        sheet: View,
        characterName: String,
        tts: TextToSpeech?,
        current: SharedPreferencesHelper.RpVoice,
        onChange: (SharedPreferencesHelper.RpVoice) -> Unit
    ) {
        val ctx = fragment.requireContext()
        var name = current.name
        var pitch = current.pitch
        var rate = current.rate
        val voices = voicesFor(tts)
        val sample = fragment.getString(R.string.rp_voice_sample, characterName)

        fun preview() {
            onChange(SharedPreferencesHelper.RpVoice(name, pitch, rate))
            val t = tts ?: return
            runCatching {
                t.voice = voices.firstOrNull { it.name == name } ?: t.defaultVoice
                t.setPitch(pitch)
                t.setSpeechRate(rate)
                t.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "rp_voice_preview")
            }
        }

        // Bound again once the engine is up, so it starts from an empty list and fresh listeners.
        val list = sheet.findViewById<LinearLayout>(R.id.rpVoiceList)
        list.removeAllViews()
        val rows = ArrayList<Pair<String?, ImageView>>()
        fun refreshChecks() = rows.forEach { (n, check) -> check.visibility = if (n == name) View.VISIBLE else View.INVISIBLE }
        fun addRow(title: String, subtitle: String?, voiceName: String?) {
            val (_, check) = RpPageKit.row(list, title, subtitle, selected = false) {
                name = voiceName
                refreshChecks()
                preview()
            }
            rows += voiceName to check!!
        }
        addRow(fragment.getString(R.string.rp_voice_default), null, null)
        voices.forEachIndexed { i, v ->
            val region = v.locale.getDisplayCountry(Locale.getDefault()).ifBlank { null }
            val kind = fragment.getString(if (v.isNetworkConnectionRequired) R.string.rp_voice_network else R.string.rp_voice_device)
            addRow(fragment.getString(R.string.rp_voice_n, i + 1), listOfNotNull(region, kind).joinToString(" · "), v.name)
        }
        refreshChecks()

        fun bindSteps(groupId: Int, ids: IntArray, steps: FloatArray, value: Float, set: (Float) -> Unit) {
            val group = sheet.findViewById<MaterialButtonToggleGroup>(groupId)
            val idx = steps.indices.minByOrNull { kotlin.math.abs(steps[it] - value) } ?: 1
            // Only this page's own listener is swapped; the group keeps the one that slides its thumb.
            (group.getTag(groupId) as? MaterialButtonToggleGroup.OnButtonCheckedListener)
                ?.let(group::removeOnButtonCheckedListener)
            group.check(ids[idx])
            val listener = MaterialButtonToggleGroup.OnButtonCheckedListener { _, id, checked ->
                if (!checked) return@OnButtonCheckedListener
                set(steps[ids.indexOf(id).coerceAtLeast(0)])
                preview()
            }
            group.setTag(groupId, listener)
            group.addOnButtonCheckedListener(listener)
        }
        bindSteps(R.id.rpVoicePitch, intArrayOf(R.id.rpPitchLow, R.id.rpPitchMid, R.id.rpPitchHigh), PITCH, pitch) { pitch = it }
        bindSteps(R.id.rpVoiceRate, intArrayOf(R.id.rpRateSlow, R.id.rpRateMid, R.id.rpRateFast), RATE, rate) { rate = it }
    }
}
