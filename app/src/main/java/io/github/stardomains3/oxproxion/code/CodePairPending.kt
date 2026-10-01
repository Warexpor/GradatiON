package io.github.stardomains3.oxproxion.code

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * One-shot queue for a pairing result from a QR scan or `gradation://pair` deep link.
 * [CodeModeHost] observes this (no ChatFragment changes) to enable Code, switch tab, and
 * open [CodeHostDialog] prefilled.
 */
object CodePairPending {

    private val _pending = MutableStateFlow<CodePairing.Result?>(null)
    val pending: StateFlow<CodePairing.Result?> = _pending

    fun offer(pairing: CodePairing.Result) {
        _pending.value = pairing
    }

    /** Take and clear; null if nothing waiting. */
    fun consume(): CodePairing.Result? {
        var taken: CodePairing.Result? = null
        _pending.update { cur ->
            taken = cur
            null
        }
        return taken
    }

    fun peek(): CodePairing.Result? = _pending.value

    private val _error = MutableStateFlow<String?>(null)
    /** Why a scan produced no pairing (bad QR, camera denied), for [CodeModeHost] to show. */
    val error: StateFlow<String?> = _error

    fun offerError(message: String) {
        _error.value = message
    }

    /** Take and clear the scan error; null if none waiting. */
    fun consumeError(): String? {
        var taken: String? = null
        _error.update { cur ->
            taken = cur
            null
        }
        return taken
    }

    fun clear() {
        _pending.value = null
    }
}
