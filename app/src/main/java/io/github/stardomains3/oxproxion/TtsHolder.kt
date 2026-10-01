package io.github.stardomains3.oxproxion

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One app-wide [TextToSpeech] for read-aloud, WAV saves and the Roleplay voice page, instead of an
 * engine bound per screen. Binding an engine costs a service connection and a second or so, so it
 * starts on first use ([whenReady]) and is let go once nothing [hold]s it for a while, or when the
 * app is hidden and not speaking.
 *
 * Callers never keep the engine: ask again each time, because it can be shut down and rebuilt
 * between two uses. Everything runs on the main thread except the utterance callbacks, which
 * arrive on a binder thread and go to every listener added with [addListener]. Utterance ids tell
 * the screens' speech apart, so a listener must ignore ids that aren't its own.
 */
object TtsHolder {
    private const val IDLE_SHUTDOWN_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var generation = 0
    private val waiting = ArrayList<(TextToSpeech?) -> Unit>()
    private val holders: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
    private val listeners = CopyOnWriteArrayList<UtteranceProgressListener>()
    private var callbacksRegistered = false

    private val idleShutdown = Runnable { shutdownIfIdle() }

    private val dispatcher = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = listeners.forEach { it.onStart(utteranceId) }
        override fun onDone(utteranceId: String?) = listeners.forEach { it.onDone(utteranceId) }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = listeners.forEach { it.onError(utteranceId) }
    }

    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) shutdownIfIdle(whileHeld = true)
        }
        override fun onLowMemory() = shutdownIfIdle(whileHeld = true)
        override fun onConfigurationChanged(newConfig: Configuration) {}
    }

    /** The engine if it is up right now, without starting it. */
    fun ready(): TextToSpeech? = engine.takeIf { ready }

    /**
     * Runs [onReady] on the main thread with the engine, or with null when the phone's engine
     * failed to start. Immediate when it is already up; the first call starts it.
     */
    fun whenReady(context: Context, onReady: (TextToSpeech?) -> Unit) {
        val app = context.applicationContext
        if (!callbacksRegistered) {
            callbacksRegistered = true
            app.registerComponentCallbacks(trimCallbacks)
        }
        main.removeCallbacks(idleShutdown)
        val up = ready()
        if (up != null) {
            scheduleIdleShutdown()
            onReady(up)
            return
        }
        waiting += onReady
        if (engine == null) start(app)
    }

    /** Keeps the engine from idling out while a screen that may speak is open; [release] when it closes. */
    fun hold(owner: Any) {
        holders += owner
        main.removeCallbacks(idleShutdown)
    }

    fun release(owner: Any) {
        holders -= owner
        scheduleIdleShutdown()
    }

    fun addListener(listener: UtteranceProgressListener) {
        if (listener !in listeners) listeners += listener
    }

    fun removeListener(listener: UtteranceProgressListener) {
        listeners -= listener
    }

    private fun start(app: Context) {
        val gen = ++generation
        var created: TextToSpeech? = null
        created = TextToSpeech(app) { status ->
            // Hop to main: this can arrive on the binder thread, and the field below is assigned
            // only after the constructor returns.
            main.post { onInit(gen, created, status) }
        }
        engine = created
    }

    private fun onInit(gen: Int, tts: TextToSpeech?, status: Int) {
        if (gen != generation || tts == null) {
            // Shut down while it was starting: nobody owns this engine any more.
            runCatching { tts?.shutdown() }
            return
        }
        if (status != TextToSpeech.SUCCESS) {
            engine = null
            runCatching { tts.shutdown() }
            flush(null)
            return
        }
        tts.setOnUtteranceProgressListener(dispatcher)
        ready = true
        flush(tts)
        scheduleIdleShutdown()
    }

    private fun flush(tts: TextToSpeech?) {
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it(tts) }
    }

    private fun scheduleIdleShutdown() {
        main.removeCallbacks(idleShutdown)
        if (holders.isEmpty() && engine != null) main.postDelayed(idleShutdown, IDLE_SHUTDOWN_MS)
    }

    /** Lets the engine go unless it is speaking, a screen is waiting on it, or (when not [whileHeld]) a screen holds it. */
    private fun shutdownIfIdle(whileHeld: Boolean = false) {
        val tts = engine ?: return
        if (waiting.isNotEmpty() || (!whileHeld && holders.isNotEmpty())) return
        if (ready && runCatching { tts.isSpeaking }.getOrDefault(false)) {
            // Still talking: look again later rather than cutting the reply off.
            if (holders.isEmpty()) main.postDelayed(idleShutdown, IDLE_SHUTDOWN_MS)
            return
        }
        generation++
        engine = null
        ready = false
        runCatching { tts.stop(); tts.shutdown() }
    }
}
