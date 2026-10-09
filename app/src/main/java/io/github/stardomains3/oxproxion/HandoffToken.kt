package io.github.stardomains3.oxproxion

import android.content.Intent
import java.util.UUID

/**
 * MainActivity is exported, so any app can start it with extras. The autosend, preset and
 * clear-chat hand-offs act on the user's key and settings, so they are honoured only when one of
 * our own forwarding screens stamped the intent with this per-process secret.
 */
object HandoffToken {
    private const val EXTRA = "gradation_handoff_token"
    private val value: String = UUID.randomUUID().toString()

    fun stamp(intent: Intent): Intent = intent.putExtra(EXTRA, value)

    fun isOurs(intent: Intent): Boolean = intent.getStringExtra(EXTRA) == value
}
