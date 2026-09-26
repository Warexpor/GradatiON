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

    fun clear() {
        _pending.value = null
    }
}
