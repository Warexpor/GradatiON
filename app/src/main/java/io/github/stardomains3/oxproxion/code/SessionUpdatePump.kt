package io.github.stardomains3.oxproxion.code

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Coalesces [SessionUpdate]s into bounded Main-thread drains without dropping any.
 *
 * Unlike chat's [io.github.stardomains3.oxproxion.StreamUiPump] (CONFLATED latest message),
 * Code mode must apply every update in order — TextChunks, TurnDone, approvals, etc. A single
 * consumer receives the first pending update, then [Channel.tryReceive]s up to [maxBatch] more,
 * hands the batch to [onDrain], and only yields [FRAME_MS] when the channel is empty afterward.
 * While a backlog remains, the next drain runs immediately so Main stays responsive and the
 * transport's DROP_OLDEST window is not stretched by one huge fold.
 */
internal class SessionUpdatePump(
    scope: CoroutineScope,
    private val onDrain: (List<SessionUpdate>) -> Unit,
    private val frameMs: Long = FRAME_MS,
    private val maxBatch: Int = MAX_BATCH,
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val channel = Channel<SessionUpdate>(Channel.UNLIMITED)
    private val job: Job = scope.launch(dispatcher) {
        while (true) {
            val first = channel.receiveCatching().getOrNull() ?: break
            // Let same-dispatcher producers finish a burst of offers before draining.
            yield()
            val batch = ArrayList<SessionUpdate>(minOf(8, maxBatch).coerceAtLeast(1))
            batch.add(first)
            while (batch.size < maxBatch) {
                val next = channel.tryReceive().getOrNull() ?: break
                batch.add(next)
            }
            onDrain(batch)
            // Pace UI only when caught up; keep draining backlog without a frame delay.
            if (channel.isEmpty) delayMs(frameMs)
        }
    }

    fun offer(update: SessionUpdate) {
        channel.trySend(update)
    }

    /** Drop anything pending and stop. Safe to call more than once. */
    fun cancel() {
        channel.cancel()
        job.cancel()
    }

    companion object {
        const val FRAME_MS = 16L
        /** Cap per drain so a huge backlog cannot monopolize Main for one fold. */
        const val MAX_BATCH = 48
    }
}

/**
 * Pure fold of one [CodeUpdate] onto a [CodeSessionState] (events + running + summary).
 * Used by [CodeHub] drains and unit tests so coalesced batches share one code path.
 */
internal object CodeSessionFolder {

    /** Markdown around a list preview. An underscore inside an identifier stays. */
    private val MD_STARS = Regex("\\*+")
    private val MD_TICKS = Regex("`+")
    private val MD_EDGE_UNDERSCORE = Regex("(?<![A-Za-z0-9])_|_(?![A-Za-z0-9])")
    private val MD_HEADING = Regex("^#+\\s*")
    private const val PREVIEW_CHARS = 140

    fun apply(
        state: CodeSessionState,
        update: CodeUpdate,
        now: Long = System.currentTimeMillis(),
        liveSeq: Long? = null,
        suppressRunningFromChunks: Boolean = false,
        /** True when hub.prompt accepted after a local cancel — ignore that cancel's TurnDone. */
        ignoreStaleCancelTurnDone: Boolean = false,
        /** User renamed this session; bridge titles must not replace it. */
        keepLocalTitle: Boolean = false,
    ): CodeSessionState {
        // Slash-command list is session UI state, not transcript; keep fold side-effect free.
        if (update is CodeUpdate.AvailableCommands) {
            return state.copy(availableCommands = update.commands)
        }
        val events = bounded(TranscriptReducer.apply(state.events, update, now), update)
        val running = when (update) {
            // B1/H1: only a *stale* local-cancel TurnDone (superseded by a newer prompt) keeps
            // running. Natural ACP stopReason=="cancelled" (no ignore stamp) clears running.
            is CodeUpdate.TurnDone -> when {
                update.stopReason == "cancelled" && ignoreStaleCancelTurnDone -> state.running
                else -> false
            }
            // G4 / E2: history replay must not force Stop chrome. Hub.prompt and
            // SessionInfo.RUNNING / NEEDS_APPROVAL drive live turns; chunks / tool patches /
            // UserPrompt only preserve the current flag.
            is CodeUpdate.TextChunk, is CodeUpdate.ImageChunk, is CodeUpdate.ToolPatch ->
                state.running
            is CodeUpdate.Upsert -> state.running
            is CodeUpdate.SessionInfo -> when (update.status) {
                SessionStatus.RUNNING, SessionStatus.NEEDS_APPROVAL -> true
                SessionStatus.IDLE, SessionStatus.ERROR -> false
                else -> state.running
            }
            else -> state.running
        }
        val fromActivity = listPreview(events)
        val summary = when (update) {
            is CodeUpdate.SessionInfo -> state.summary.copy(
                updatedAt = now,
                title = incomingTitle(state.summary.title, update.title, keepLocalTitle),
                preview = update.preview ?: fromActivity ?: state.summary.preview,
                branch = update.branch ?: state.summary.branch,
                permissionMode = update.permissionMode ?: state.summary.permissionMode,
                lastSeq = liveSeq ?: state.summary.lastSeq
            )
            is CodeUpdate.Title -> state.summary.copy(
                updatedAt = now,
                title = incomingTitle(state.summary.title, update.title, keepLocalTitle),
                lastSeq = liveSeq ?: state.summary.lastSeq
            )
            else -> state.summary.copy(
                updatedAt = now,
                preview = fromActivity ?: state.summary.preview,
                lastSeq = liveSeq ?: state.summary.lastSeq
            )
        }
        return state.copy(events = events, running = running, summary = summary)
    }

    fun needsPersist(update: CodeUpdate): Boolean =
        update is CodeUpdate.TurnDone || update is CodeUpdate.SessionInfo

    /**
     * One line for the session list. A tool that is still running (and is later than the
     * last reply) is what the agent is doing now. Otherwise the last reply, with the
     * marks taken off and `snake_case` left intact.
     */
    private fun listPreview(events: List<CodeEvent>): String? {
        val lastLiveTool = events.indexOfLast {
            it is CodeEvent.ToolCall &&
                (it.status == ToolStatus.RUNNING || it.status == ToolStatus.PENDING)
        }
        val lastText = events.indexOfLast { it is CodeEvent.AgentText }
        if (lastLiveTool >= 0 && lastLiveTool > lastText) {
            return toolActivity(events[lastLiveTool] as CodeEvent.ToolCall)
        }
        val text = (events.getOrNull(lastText) as? CodeEvent.AgentText)?.text ?: return null
        val line = text.lineSequence().lastOrNull { it.isNotBlank() } ?: return null
        return prosePreview(line).ifEmpty { null }
    }

    private fun toolActivity(tool: CodeEvent.ToolCall): String? {
        val title = tool.title.trim()
        val detail = tool.detail?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val line = when {
            title.isNotEmpty() && detail.isNotEmpty() -> "$title · $detail"
            title.isNotEmpty() -> title
            else -> detail
        }
        return line.take(PREVIEW_CHARS).ifEmpty { null }
    }

    private fun prosePreview(line: String): String {
        val stripped = MD_EDGE_UNDERSCORE.replace(
            MD_TICKS.replace(MD_STARS.replace(line.trim(), ""), ""),
            "",
        )
        return MD_HEADING.replace(stripped, "").trim().take(PREVIEW_CHARS)
    }

    /** Blank wire titles are ignored. A pinned local title stays. */
    private fun incomingTitle(current: String, incoming: String?, keepLocal: Boolean): String {
        val next = incoming?.trim()?.takeIf { it.isNotEmpty() } ?: return current
        return if (keepLocal) current else next
    }

    /** Transcripts live in memory only, so a long session must not grow without bound. */
    const val MAX_EVENTS = 1500
    /** After hitting [MAX_EVENTS], cut back to this so the next trim is many updates away. */
    private const val KEEP_EVENTS = 1200
    /** Newest inline agent images that keep their base64; older ones show as gaps, not megabytes. */
    const val MAX_LIVE_IMAGES = 6

    /** Trims the oldest events past [MAX_EVENTS] and sheds the base64 of all but the newest images. */
    internal fun bounded(events: List<CodeEvent>, update: CodeUpdate): List<CodeEvent> {
        var out = events
        if (out.size > MAX_EVENTS) out = out.subList(out.size - KEEP_EVENTS, out.size).toList()
        if (update !is CodeUpdate.ImageChunk) return out
        var seen = 0
        var result: MutableList<CodeEvent>? = null
        for (i in out.indices.reversed()) {
            val e = out[i] as? CodeEvent.AgentText ?: continue
            if (e.images.isEmpty()) continue
            val kept = e.images.asReversed().map { img ->
                seen++
                if (seen > MAX_LIVE_IMAGES && img.data.isNotEmpty()) img.copy(data = "") else img
            }.asReversed()
            if (kept != e.images) {
                val m = result ?: out.toMutableList().also { result = it }
                m[i] = e.copy(images = kept)
            }
        }
        return result ?: out
    }
}

/** Result of folding a drain batch onto the sessions map. [sessions] is null when nothing matched. */
internal data class SessionFoldResult(
    val sessions: Map<String, CodeSessionState>?,
    val needsPersist: Boolean,
)

/**
 * Fold [batch] in order onto [sessions]. Multi-session drains produce one combined map covering
 * every touched id; unknown sessionIds are skipped (same as live [CodeHub] apply).
 */
internal fun foldSessionUpdates(
    sessions: Map<String, CodeSessionState>,
    batch: List<SessionUpdate>,
    now: Long = System.currentTimeMillis(),
    liveSeqOf: (CodeSessionState, String) -> Long? = { _, _ -> null },
    suppressRunningFromChunks: Set<String> = emptySet(),
    ignoreStaleCancelTurnDone: Set<String> = emptySet(),
    pinnedTitles: Set<String> = emptySet(),
): SessionFoldResult {
    if (batch.isEmpty()) return SessionFoldResult(null, false)
    val touched = HashMap<String, CodeSessionState>()
    var needsPersist = false
    for (u in batch) {
        val cur = touched[u.sessionId] ?: sessions[u.sessionId] ?: continue
        touched[u.sessionId] = CodeSessionFolder.apply(
            cur,
            u.update,
            now,
            liveSeqOf(cur, u.sessionId),
            suppressRunningFromChunks = u.sessionId in suppressRunningFromChunks,
            ignoreStaleCancelTurnDone = u.sessionId in ignoreStaleCancelTurnDone,
            keepLocalTitle = u.sessionId in pinnedTitles,
        )
        if (CodeSessionFolder.needsPersist(u.update)) needsPersist = true
    }
    if (touched.isEmpty()) return SessionFoldResult(null, false)
    return SessionFoldResult(sessions + touched, needsPersist)
}

/**
 * Prefs→Room merge: insert legacy rows missing from Room; when both exist, keep Room fields
 * but take the higher [CodeSessionSummary.lastSeq]. Returns only rows that need upserting
 * (empty when Room already covers everything). Callers mark migrated only after a successful
 * upsert of this list (or when the list is empty and nothing remains to import).
 */
internal fun mergeLegacySessionRows(
    existing: List<CodeSessionEntity>,
    legacy: List<CodeSessionSummary>,
): List<CodeSessionEntity> {
    if (legacy.isEmpty()) return emptyList()
    if (existing.isEmpty()) return legacy.map { CodeSessionEntity.from(it) }
    val byId = existing.associateBy { it.id }
    val toUpsert = ArrayList<CodeSessionEntity>(legacy.size)
    for (leg in legacy) {
        val room = byId[leg.id]
        if (room == null) {
            toUpsert += CodeSessionEntity.from(leg)
        } else {
            val legSeq = leg.lastSeq
            val roomSeq = room.lastSeq
            if (legSeq != null && (roomSeq == null || legSeq > roomSeq)) {
                toUpsert += room.copy(lastSeq = legSeq)
            }
        }
    }
    return toUpsert
}
