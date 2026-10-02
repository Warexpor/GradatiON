package io.github.stardomains3.oxproxion.code

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles Allow / Deny actions on Code away-approval notifications without opening the UI,
 * and shade swipe-dismiss so a still-pending approval can re-alert.
 * Uses the same [CodeHub.answer] path as the transcript approval buttons.
 *
 * A2 / AWAY-01: [goAsync] keeps the process alive until the answer finishes (connect +
 * ACP ready + send, or timeout); the shade entry is cancelled only after send is accepted.
 */
class CodeAwayActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            CodeAwayNotifier.ACTION_DISMISS -> {
                val key = intent.getStringExtra(CodeAwayNotifier.EXTRA_DEDUP_KEY) ?: return
                CodeHub.get(context).awayNotifier.onUserDismissed(key)
            }
            CodeAwayNotifier.ACTION_ANSWER -> {
                val sessionId = intent.getStringExtra(CodeAwayNotifier.EXTRA_SESSION_ID) ?: return
                val requestId = intent.getStringExtra(CodeAwayNotifier.EXTRA_REQUEST_ID) ?: return
                val optionId = intent.getStringExtra(CodeAwayNotifier.EXTRA_OPTION_ID) ?: return
                val kindName = intent.getStringExtra(CodeAwayNotifier.EXTRA_OPTION_KIND) ?: return
                val label = intent.getStringExtra(CodeAwayNotifier.EXTRA_OPTION_LABEL).orEmpty()
                val kind = runCatching { ApprovalOption.Kind.valueOf(kindName) }.getOrNull() ?: return
                val pending = goAsync()
                val hub = CodeHub.get(context)
                hub.answerFromAway(sessionId, requestId, ApprovalOption(optionId, label, kind)) { success ->
                    if (success) {
                        hub.awayNotifier.cancelApproval(sessionId, requestId)
                    }
                    pending.finish()
                }
            }
            else -> return
        }
    }
}
