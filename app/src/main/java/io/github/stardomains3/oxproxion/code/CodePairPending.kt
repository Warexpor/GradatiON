package io.github.stardomains3.oxproxion.code

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One-shot queue for a pairing result from a QR scan or `gradation://pair` deep link.
 * [CodeModeHost] observes this (no ChatFragment changes) to enable Code, switch tab, and
 * open [CodeHostDialog] prefilled.
 *
 * Pending and error are mutually exclusive under one lock: a later success must not leave
 * a prior scan error queued (and a failed rescan must not keep an older pairing), or the
 * host would toast the stale failure while also opening the pair form. Separate StateFlow
 * writes without a lock could briefly leave both set when offer and offerError interleave.
 */
object CodePairPending {

    private val lock = Any()

    private val _pending = MutableStateFlow<CodePairing.Result?>(null)
    val pending: StateFlow<CodePairing.Result?> = _pending

    fun offer(pairing: CodePairing.Result) {
        synchronized(lock) {
            _error.value = null
            _pending.value = pairing
        }
    }

    /** Take and clear; null if nothing waiting. */
    fun consume(): CodePairing.Result? = synchronized(lock) {
        val taken = _pending.value
        if (taken != null) _pending.value = null
        taken
    }

    fun peek(): CodePairing.Result? = synchronized(lock) { _pending.value }

    private val _error = MutableStateFlow<String?>(null)
    /** Why a scan produced no pairing (bad QR, camera denied), for [CodeModeHost] to show. */
    val error: StateFlow<String?> = _error

    fun offerError(message: String) {
        synchronized(lock) {
            _pending.value = null
            _error.value = message
        }
    }

    /**
     * Take and clear the scan error; null if none waiting or a pairing is already queued
     * (that success superseded the failure before we left the lock).
     */
    fun consumeError(): String? = synchronized(lock) {
        if (_pending.value != null) {
            _error.value = null
            return null
        }
        val taken = _error.value
        if (taken != null) _error.value = null
        taken
    }

    fun peekError(): String? = synchronized(lock) { _error.value }

    fun clear() {
        synchronized(lock) {
            _pending.value = null
            _error.value = null
        }
    }

    /** True when a pairing and a scan error are not both queued. For tests and the host. */
    internal fun isMutuallyExclusive(): Boolean = synchronized(lock) {
        _pending.value == null || _error.value == null
    }
}
