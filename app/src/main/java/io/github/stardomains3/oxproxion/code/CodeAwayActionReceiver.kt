package io.github.stardomains3.oxproxion.code

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles Allow / Deny actions on Code away-approval notifications without opening the UI.
 * Uses the same [CodeHub.answer] path as the transcript approval buttons.
 */
class CodeAwayActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != CodeAwayNotifier.ACTION_ANSWER) return
        val sessionId = intent.getStringExtra(CodeAwayNotifier.EXTRA_SESSION_ID) ?: return
        val requestId = intent.getStringExtra(CodeAwayNotifier.EXTRA_REQUEST_ID) ?: return
        val optionId = intent.getStringExtra(CodeAwayNotifier.EXTRA_OPTION_ID) ?: return
        val kindName = intent.getStringExtra(CodeAwayNotifier.EXTRA_OPTION_KIND) ?: return
        val label = intent.getStringExtra(CodeAwayNotifier.EXTRA_OPTION_LABEL).orEmpty()
        val kind = runCatching { ApprovalOption.Kind.valueOf(kindName) }.getOrNull() ?: return
        val hub = CodeHub.get(context)
        hub.answer(sessionId, requestId, ApprovalOption(optionId, label, kind))
        hub.awayNotifier.cancelApproval(sessionId, requestId)
    }
}
