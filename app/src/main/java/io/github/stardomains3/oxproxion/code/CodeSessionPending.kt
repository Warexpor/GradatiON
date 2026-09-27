package io.github.stardomains3.oxproxion.code

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * One-shot queue to open a Code session from a local away-notification tap
 * (or future deep link). [CodeModeHost] observes this — no ChatFragment changes.
 */
object CodeSessionPending {

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    fun offer(sessionId: String) {
        if (sessionId.isBlank()) return
        _pending.value = sessionId
    }

    /** Take and clear; null if nothing waiting. */
    fun consume(): String? {
        var taken: String? = null
        _pending.update { cur ->
            taken = cur
            null
        }
        return taken
    }

    fun peek(): String? = _pending.value

    fun clear() {
        _pending.value = null
    }
}
