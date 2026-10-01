package io.github.stardomains3.oxproxion

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Where dictation goes to become text. Stored under [SharedPreferencesHelper.getVoiceInputProvider]. */
enum class VoiceEngine(val key: String) {
    /** Android's own recognizer, on-device when the phone has it: free, private, live words. */
    DEVICE("device"),
    /** Record, then OpenRouter's transcription endpoint (costs credits). */
    CLOUD("cloud"),
    /** Record, then xAI Grok Speech-to-Text (`POST /v1/stt`). */
    GROK("grok"),
    /** Record, then the local server's `/v1/audio/transcriptions` (whisper and friends). */
    LAN("lan"),
    OFF("off");

    companion object {
        /** Latest Grok STT model from https://docs.x.ai/developers/model-capabilities/audio/speech-to-text */
        const val GROK_STT_MODEL = "grok-voice-transcribe-2.0"

        fun fromKey(key: String?): VoiceEngine = entries.firstOrNull { it.key == key } ?: DEVICE
    }
}

/** The engine's name as Settings shows it. */
val VoiceEngine.labelRes: Int
    get() = when (this) {
        VoiceEngine.DEVICE -> R.string.voice_engine_phone
        VoiceEngine.CLOUD -> R.string.voice_engine_cloud
        VoiceEngine.GROK -> R.string.voice_engine_grok
        VoiceEngine.LAN -> R.string.voice_engine_local
        VoiceEngine.OFF -> R.string.settings_value_off
    }

/**
 * Tap-to-start, tap-to-finish dictation. Never sends anything by itself: finished text goes to
 * [Listener.onCommit] and the caller pastes it into its input.
 *
 * With [VoiceEngine.DEVICE] the phone's recognizer runs in-app (no Google popup). It ends a
 * session on every pause, so we quietly restart it until the user taps again, which gives one
 * continuous dictation. Cloud and Local record Opus and transcribe once on finish.
 */
class VoiceInput(
    private val context: Context,
    private val scope: CoroutineScope,
    private val transcribe: suspend (bytes: ByteArray, format: String, fileName: String) -> Result<String>,
    private val listener: Listener,
) {
    interface Listener {
        fun onStateChanged(state: State)
        /** Words heard so far in the current phrase; replaces the previous partial. */
        fun onPartial(text: String)
        /** A finished phrase to keep. */
        fun onCommit(text: String)
        /** Mic loudness, 0 (silence) to 1 (loud), several times a second. */
        fun onLevel(level: Float)
        fun onError(message: String)
    }

    enum class State { IDLE, LISTENING, TRANSCRIBING }

    var state: State = State.IDLE
        private set(value) {
            if (field == value) return
            field = value
            listener.onStateChanged(value)
        }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = SharedPreferencesHelper(context)

    // Device engine
    private var recognizer: SpeechRecognizer? = null
    private var stopping = false
    private var lastPartial = ""
    private val stopTimeout = Runnable { finishDevice() }
    /** Sessions in a row that heard nothing, busy retries in a row, and when words last arrived. */
    private var silentRestarts = 0
    private var busyRetries = 0
    private var lastHeardAt = 0L

    // Recording engines
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordEngine: VoiceEngine = VoiceEngine.CLOUD
    private var transcribeJob: Job? = null
    private val amplitudeTick = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
            listener.onLevel((amp / 12_000f).coerceIn(0f, 1f))
            main.postDelayed(this, LEVEL_TICK_MS)
        }
    }

    /** The engine a tap would use right now, or null when voice input can't work on this phone. */
    fun resolveEngine(): VoiceEngine? = resolve(context, prefs)

    fun toggle() {
        when (state) {
            State.IDLE -> start()
            State.LISTENING -> finish()
            // A tap while words are being transcribed gives up on them.
            State.TRANSCRIBING -> cancel()
        }
    }

    /**
     * What stops the picked engine from working at all (no key, no model, no server), as a
     * string resource, or null when it is ready. Checked before recording so a missing key is
     * reported up front instead of after the audio has been recorded and uploaded.
     */
    @StringRes
    fun preflightError(): Int? = resolveEngine()?.let { preflight(it, prefs) }

    fun start() {
        if (state != State.IDLE) return
        val engine = resolveEngine()
        if (engine == null) {
            listener.onError(context.getString(R.string.voice_unavailable))
            return
        }
        preflight(engine, prefs)?.let {
            listener.onError(context.getString(it))
            return
        }
        when (engine) {
            VoiceEngine.DEVICE -> startDevice()
            VoiceEngine.CLOUD, VoiceEngine.GROK, VoiceEngine.LAN -> startRecording(engine)
            VoiceEngine.OFF -> listener.onError(context.getString(R.string.voice_unavailable))
        }
    }

    /** Stop listening and keep what was said (cloud and local transcribe first). */
    fun finish() {
        disarmDurationCap()
        if (state != State.LISTENING) return
        if (recognizer != null) {
            stopping = true
            recognizer?.stopListening()
            main.postDelayed(stopTimeout, STOP_GRACE_MS)
        } else {
            stopRecording(transcribeIt = true)
        }
    }

    /**
     * Stop right now and keep what was said: the device recognizer commits the words it has,
     * a recording is sent off to be transcribed (state becomes [State.TRANSCRIBING]). Used when
     * the user sends or leaves mid-dictation, so their words are never thrown away.
     */
    fun finishNow() {
        disarmDurationCap()
        if (recognizer != null) {
            finishDevice()
        } else if (recorder != null) {
            stopRecording(transcribeIt = true)
        }
    }

    /** Give up on this dictation: stop listening or drop a transcription in flight, keep nothing. */
    fun cancel() {
        disarmDurationCap()
        main.removeCallbacks(busyRetry)
        if (recognizer != null) {
            discardDevice()
        } else if (recorder != null) {
            stopRecording(transcribeIt = false)
        }
        transcribeJob?.cancel()
        transcribeJob = null
        listener.onLevel(0f)
        state = State.IDLE
    }

    /** The screen is going away; nothing should outlive it. */
    fun release() = cancel()

    // ── Device recognizer ───────────────────────────────────────────────────────────────

    private fun startDevice() {
        val r = createRecognizer(context) ?: run {
            listener.onError(context.getString(R.string.voice_unavailable))
            return
        }
        recognizer = r
        stopping = false
        lastPartial = ""
        heard()
        r.setRecognitionListener(deviceListener)
        state = State.LISTENING
        armDurationCap()
        // Some recognizer services throw (service gone, permission revoked) instead of calling onError.
        runCatching { r.startListening(recognizerIntent()) }.onFailure {
            discardDevice()
            state = State.IDLE
            listener.onError(context.getString(R.string.voice_error_generic))
        }
    }

    /** Something was heard, so the silence counters start over. */
    private fun heard() {
        lastHeardAt = SystemClock.uptimeMillis()
        silentRestarts = 0
        busyRetries = 0
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Hint only; many engines cap it. The restart loop covers the rest.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
    }

    /**
     * Start another session after a pause ended the last one. A silent room would loop this
     * forever (and a recognizer that errors instantly would spin), so it stops after a few empty
     * sessions in a row or a long stretch with nothing heard.
     */
    private fun restartDevice() {
        val r = recognizer ?: return
        if (++silentRestarts > MAX_SILENT_RESTARTS || SystemClock.uptimeMillis() - lastHeardAt > IDLE_CEILING_MS) {
            finishDevice()
            listener.onError(context.getString(R.string.voice_error_idle))
            return
        }
        lastPartial = ""
        runCatching { r.startListening(recognizerIntent()) }.onFailure { finishDevice() }
    }

    /** The recognizer was still busy: try again shortly, a few times, then give up. */
    private val busyRetry = Runnable {
        val r = recognizer ?: return@Runnable
        lastPartial = ""
        runCatching { r.startListening(recognizerIntent()) }.onFailure {
            finishDevice()
            listener.onError(context.getString(R.string.voice_error_generic))
        }
    }

    /** End the device session without keeping the words heard so far. */
    private fun discardDevice() {
        main.removeCallbacks(stopTimeout)
        main.removeCallbacks(busyRetry)
        val r = recognizer ?: return
        recognizer = null
        lastPartial = ""
        runCatching { r.cancel() }
        runCatching { r.destroy() }
        stopping = false
        listener.onPartial("")
        listener.onLevel(0f)
    }

    private fun commitPartial() {
        val text = lastPartial.trim()
        lastPartial = ""
        if (text.isNotEmpty()) listener.onCommit(text) else listener.onPartial("")
    }

    private fun finishDevice() {
        main.removeCallbacks(stopTimeout)
        main.removeCallbacks(busyRetry)
        val r = recognizer ?: return
        recognizer = null
        commitPartial()
        runCatching { r.cancel() }
        runCatching { r.destroy() }
        stopping = false
        listener.onLevel(0f)
        state = State.IDLE
    }

    /** A result delivered after the recognizer was destroyed must not paste the phrase a second time. */
    private fun deviceLive(): Boolean = recognizer != null

    private val deviceListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() {
            if (deviceLive()) heard()
        }
        override fun onRmsChanged(rmsdB: Float) {
            if (!deviceLive()) return
            // Recognizers report roughly -2 dB (silence) to 10 dB (loud speech).
            listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) {
            if (!deviceLive()) return
            val text = partialResults?.bestResult() ?: return
            if (text.isBlank()) return
            heard()
            lastPartial = text
            listener.onPartial(text)
        }
        override fun onResults(results: Bundle?) {
            if (!deviceLive()) return
            results?.bestResult()?.takeIf { it.isNotBlank() }?.let {
                heard()
                lastPartial = it
            }
            commitPartial()
            if (stopping) finishDevice() else restartDevice()
        }
        override fun onError(error: Int) {
            if (!deviceLive()) return
            when {
                stopping -> finishDevice()
                // A pause ended the session: keep listening until the user taps.
                error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    commitPartial()
                    restartDevice()
                }
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    if (++busyRetries > MAX_BUSY_RETRIES) {
                        finishDevice()
                        listener.onError(context.getString(R.string.voice_error_generic))
                    } else {
                        main.removeCallbacks(busyRetry)
                        main.postDelayed(busyRetry, BUSY_RETRY_MS * busyRetries)
                    }
                }
                else -> {
                    finishDevice()
                    listener.onError(context.getString(errorText(error)))
                }
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun Bundle.bestResult(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    // ── Recording (cloud / local) ───────────────────────────────────────────────────────

    private fun startRecording(engine: VoiceEngine) {
        val file = File(context.cacheDir, "voice_input_${System.currentTimeMillis()}.ogg")
        val rec = runCatching {
            MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(32_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        }.getOrElse {
            file.delete()
            listener.onError(context.getString(R.string.voice_error_mic))
            return
        }
        recorder = rec
        recordFile = file
        recordEngine = engine
        state = State.LISTENING
        armDurationCap()
        main.post(amplitudeTick)
    }

    private val durationCap = Runnable {
        if (state != State.LISTENING) return@Runnable
        listener.onError(context.getString(R.string.voice_recording_cap))
        finish()
    }

    private fun armDurationCap() {
        main.removeCallbacks(durationCap)
        main.postDelayed(durationCap, VoiceClip.MAX_DURATION_MS)
    }

    private fun disarmDurationCap() {
        main.removeCallbacks(durationCap)
    }

    private fun stopRecording(transcribeIt: Boolean) {
        disarmDurationCap()
        main.removeCallbacks(amplitudeTick)
        val rec = recorder ?: return
        recorder = null
        val stopped = runCatching { rec.stop() }.isSuccess
        rec.release()
        listener.onLevel(0f)
        val file = recordFile
        recordFile = null
        if (!transcribeIt || !stopped || file == null || file.length() == 0L) {
            file?.delete()
            if (transcribeIt) listener.onError(context.getString(R.string.voice_error_empty))
            state = State.IDLE
            return
        }
        if (!VoiceClip.readable(file.length())) {
            file.delete()
            listener.onError(context.getString(R.string.voice_error_too_long))
            state = State.IDLE
            return
        }
        state = State.TRANSCRIBING
        transcribeJob = scope.launch {
            val bytes = try {
                withContext(Dispatchers.IO) {
                    try {
                        file.readBytes()
                    } finally {
                        file.delete()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                if (!isActive) return@launch
                transcribeJob = null
                state = State.IDLE
                listener.onError(context.getString(R.string.voice_error_too_long))
                return@launch
            }
            val result = transcribe(bytes, "ogg", file.name)
            // Cancelled while the request was in flight: cancel() already reset the state.
            if (!isActive) return@launch
            transcribeJob = null
            state = State.IDLE
            result.onSuccess { if (it.isNotBlank()) listener.onCommit(it.trim()) }
                .onFailure { listener.onError(it.message ?: context.getString(R.string.voice_error_generic)) }
        }
    }

    companion object {
        private const val LEVEL_TICK_MS = 66L
        private const val STOP_GRACE_MS = 1_500L
        /** Empty device sessions tolerated in a row; each lasts a few seconds of silence. */
        private const val MAX_SILENT_RESTARTS = 8
        /** Stop after this long without a word, however the sessions ended. */
        private const val IDLE_CEILING_MS = 90_000L
        private const val MAX_BUSY_RETRIES = 5
        private const val BUSY_RETRY_MS = 120L

        /**
         * Why [engine] can't start yet, or null. Recording engines need their credentials and
         * a model up front; the phone's recognizer needs nothing.
         */
        @StringRes
        fun preflight(engine: VoiceEngine, prefs: SharedPreferencesHelper): Int? = when (engine) {
            VoiceEngine.CLOUD -> when {
                prefs.getVoiceInputModel().isBlank() -> R.string.voice_need_model
                prefs.getApiKeyFromPrefs("openrouter_api_key").isBlank() -> R.string.voice_need_openrouter_key
                else -> null
            }
            VoiceEngine.GROK ->
                if (prefs.getApiKeyFromPrefs(SharedPreferencesHelper.XAI_API_KEY_ALIAS).isBlank()) R.string.voice_need_xai_key else null
            VoiceEngine.LAN -> when {
                prefs.getVoiceInputModel().isBlank() -> R.string.voice_need_model
                prefs.getLanEndpoint().isNullOrBlank() -> R.string.voice_need_lan_endpoint
                else -> null
            }
            VoiceEngine.DEVICE, VoiceEngine.OFF -> null
        }

        /** Tests only: pretend the phone does (true) or doesn't (false) have a recognizer. */
        @androidx.annotation.VisibleForTesting
        var deviceAvailableOverride: Boolean? = null

        fun deviceAvailable(context: Context): Boolean = deviceAvailableOverride
            ?: (SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context))

        fun onDeviceAvailable(context: Context): Boolean = deviceAvailableOverride
            ?: SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        /** Prefer the private on-device model; fall back to the system recognizer service. */
        private fun createRecognizer(context: Context): SpeechRecognizer? = runCatching {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else if (SpeechRecognizer.isRecognitionAvailable(context)) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else null
        }.getOrNull()

        /**
         * The picked engine, or the fallback when the phone has no recognizer: Cloud if a voice
         * model is set (the OpenRouter path), else nothing and the mic hides.
         */
        fun resolve(context: Context, prefs: SharedPreferencesHelper): VoiceEngine? =
            when (val picked = VoiceEngine.fromKey(prefs.getVoiceInputProvider())) {
                VoiceEngine.OFF -> null
                VoiceEngine.DEVICE -> when {
                    deviceAvailable(context) -> VoiceEngine.DEVICE
                    prefs.getVoiceInputModel().isNotBlank() -> VoiceEngine.CLOUD
                    else -> null
                }
                else -> picked
            }

        private fun errorText(error: Int): Int = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_error_permission
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> R.string.voice_error_network
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.voice_error_language
            SpeechRecognizer.ERROR_AUDIO -> R.string.voice_error_mic
            else -> R.string.voice_error_generic
        }
    }
}

/**
 * A recording the phone can turn into text. Longer than this is not loaded into memory:
 * the upload is the raw bytes, then a base64 copy, and an unbounded clip used to exhaust the heap.
 */
internal object VoiceClip {
    const val MAX_BYTES = 8L * 1024 * 1024
    const val MAX_DURATION_MS = 5 * 60 * 1000L
    fun readable(lengthBytes: Long): Boolean = lengthBytes in 1..MAX_BYTES
}
