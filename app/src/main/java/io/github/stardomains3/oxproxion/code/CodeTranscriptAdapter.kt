package io.github.stardomains3.oxproxion.code

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import android.text.Spannable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import io.github.stardomains3.oxproxion.ChatMarkdown
import io.github.stardomains3.oxproxion.IncrementalMarkdown
import io.github.stardomains3.oxproxion.R
import io.github.stardomains3.oxproxion.ShimmerText
import io.github.stardomains3.oxproxion.StreamCursorSpan
import io.github.stardomains3.oxproxion.StreamFadeSpan
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin

/** Rows of a session transcript: the events plus a live "Working" footer while a turn runs. */
sealed class TranscriptRow {
    abstract val key: String
    data class Event(val event: CodeEvent) : TranscriptRow() { override val key get() = event.key }
    object Working : TranscriptRow() { override val key = "working" }
}

/**
 * Renders a Code session. Every event type has its own compact cell; state that the user toggles
 * (expanded tool output, open thoughts) lives here by event key so rebinds keep it.
 */
class CodeTranscriptAdapter(
    context: Context,
    private val decodeScope: CoroutineScope,
    private val onApproval: (CodeEvent.Approval, ApprovalOption) -> Unit,
    private val onOpenDiff: (CodeEvent.FileDiff) -> Unit,
    private val onOpenToolOutput: (CodeEvent.ToolCall) -> Unit = {}
) : ListAdapter<TranscriptRow, RecyclerView.ViewHolder>(DIFF) {

    private val markwon: Markwon = Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(SoftBreakAddsNewLinePlugin.create())
        .usePlugin(ChatMarkdown.plugin(context))
        .build()
    /** Per-message incremental markdown + fade clocks for the streaming agent-text cell. */
    private class StreamState(val markdown: IncrementalMarkdown) {
        val fadeStarts = ArrayList<Int>()
        val fadeTimes = ArrayList<Long>()
        var lastRenderedLen = 0
        /** Last open-tail base; grows when IncrementalMarkdown closes a stable block. */
        var fadeBase = 0
    }

    private val streams = HashMap<String, StreamState>()
    private val expanded = HashSet<String>()

    /** Normal (false): thoughts and tool output fold to one line. Thinking (true): all open. */
    var verbose: Boolean = false
        @android.annotation.SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field == value) return
            field = value
            expanded.clear()
            notifyDataSetChanged()
        }
    /**
     * Soft cache of decoded agent inline images (cacheKey → bitmap).
     * Sized by approximate KB footprint (~6MB budget), not entry count.
     */
    private val inlineBitmaps = object : LruCache<String, Bitmap>(INLINE_CACHE_MAX_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)

        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?,
        ) {
            if (!evicted) return
            // Only recycle when no ImageView still displays this key.
            if (key in displayedImageKeys) return
            if (!oldValue.isRecycled) oldValue.recycle()
        }
    }
    /** Cache keys currently set on bound ImageViews (prevents recycle-while-displayed). */
    private val displayedImageKeys = HashSet<String>()

    /** Chat-style: bound TextHolder currently painting the live stream (skip DiffUtil while set). */
    private var streamBoundHolder: TextHolder? = null
    private var revealKey: String? = null
    private val reveal = io.github.stardomains3.oxproxion.StreamRevealAnimator(
        onFrame = { displayed, _ -> revealKey?.let { renderStreaming(it, displayed) } },
        onCaughtUp = {}
    )

    /** Paint the paced text into the bound holder: markdown, per-run fades, no cursor. */
    private fun renderStreaming(key: String, displayed: String) {
        val holder = streamBoundHolder ?: return
        if (streamBoundKey != key) return
        val tv = holder.textView
        val state = streams.getOrPut(key) { StreamState(IncrementalMarkdown(markwon)) }
        try {
            val spanned = state.markdown.render(displayed)
            ChatMarkdown.polish(spanned)
            applyStreamFades(state, spanned, SystemClock.uptimeMillis())
            tv.setText(spanned, TextView.BufferType.SPANNABLE)
            holder.ensureFadeTicker()
        } catch (_: Exception) {
            holder.stopFadeTicker()
            tv.text = displayed
        }
    }
    private var streamBoundKey: String? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).key.hashCode().toLong()

    override fun getItemViewType(position: Int): Int = when (val r = getItem(position)) {
        TranscriptRow.Working -> T_WORKING
        is TranscriptRow.Event -> when (r.event) {
            is CodeEvent.UserPrompt -> T_USER
            is CodeEvent.AgentText -> T_TEXT
            is CodeEvent.Thought -> T_THOUGHT
            is CodeEvent.ToolCall -> T_TOOL
            is CodeEvent.FileDiff -> T_DIFF
            is CodeEvent.Approval -> T_APPROVAL
            is CodeEvent.Plan -> T_PLAN
            is CodeEvent.Notice -> T_NOTICE
            is CodeEvent.TurnEnd -> T_TURN
        }
    }

    /**
     * V2: while the streaming AgentText cell is on-screen, paint new tokens in place
     * (chat [streamRevealBoundHolder] pattern) and skip AsyncListDiffer + full rebind.
     */
    override fun submitList(list: List<TranscriptRow>?) {
        submitList(list, null)
    }

    override fun submitList(list: List<TranscriptRow>?, commitCallback: Runnable?) {
        val next = list ?: emptyList()
        if (tryInPlaceStream(currentList, next)) {
            commitCallback?.run()
            return
        }
        super.submitList(list, commitCallback)
    }

    override fun onCurrentListChanged(previousList: MutableList<TranscriptRow>, currentList: MutableList<TranscriptRow>) {
        pruneStreams(currentList)
    }

    /** V1: drop StreamState for keys absent from the list or no longer streaming. */
    private fun pruneStreams(list: List<TranscriptRow>) {
        if (streams.isEmpty()) return
        val keep = HashSet<String>()
        for (row in list) {
            val e = (row as? TranscriptRow.Event)?.event
            if (e is CodeEvent.AgentText && e.streaming) keep.add(e.key)
        }
        val it = streams.entries.iterator()
        while (it.hasNext()) {
            val (key, state) = it.next()
            if (key !in keep) {
                state.markdown.reset()
                it.remove()
            }
        }
    }

    /**
     * True when [next] differs from [prev] only in the text of one still-streaming AgentText
     * and that row's [TextHolder] is bound — paint via [bindText] and skip DiffUtil.
     */
    private fun tryInPlaceStream(prev: List<TranscriptRow>, next: List<TranscriptRow>): Boolean {
        if (prev.size != next.size || next.isEmpty()) return false
        var changedIdx = -1
        for (i in prev.indices) {
            val a = prev[i]
            val b = next[i]
            if (a.key != b.key) return false
            if (a == b) continue
            if (changedIdx >= 0) return false
            val ae = (a as? TranscriptRow.Event)?.event as? CodeEvent.AgentText ?: return false
            val be = (b as? TranscriptRow.Event)?.event as? CodeEvent.AgentText ?: return false
            if (!ae.streaming || !be.streaming || ae.key != be.key) return false
            changedIdx = i
        }
        if (changedIdx < 0) return false
        val e = (next[changedIdx] as TranscriptRow.Event).event as CodeEvent.AgentText
        val holder = streamBoundHolder
        if (holder == null || streamBoundKey != e.key) return false
        if (!holder.textView.isAttachedToWindow) {
            clearStreamBound(holder)
            return false
        }
        bindText(holder, e)
        return true
    }

    private fun clearStreamBound(holder: TextHolder?) {
        if (holder == null || streamBoundHolder === holder) {
            streamBoundHolder = null
            streamBoundKey = null
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        fun v(res: Int) = inf.inflate(res, parent, false)
        return when (viewType) {
            T_USER -> Simple(v(R.layout.item_code_user))
            T_TEXT -> TextHolder(v(R.layout.item_code_text)).also {
                it.textView.movementMethod = LinkMovementMethod.getInstance()
            }
            T_THOUGHT -> Simple(v(R.layout.item_code_thought))
            T_TOOL -> Simple(v(R.layout.item_code_tool))
            T_DIFF -> Simple(v(R.layout.item_code_diff))
            T_APPROVAL -> Simple(v(R.layout.item_code_approval))
            T_PLAN -> Simple(v(R.layout.item_code_plan))
            T_NOTICE -> Simple(v(R.layout.item_code_notice))
            T_TURN -> Simple(v(R.layout.item_code_turn_end))
            else -> Simple(v(R.layout.item_code_working))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
        when (val row = getItem(position)) {
            TranscriptRow.Working -> (v as TextView).let { ShimmerText.start(it, ShimmerText.highlightFor(it)) }
            is TranscriptRow.Event -> when (val e = row.event) {
                is CodeEvent.UserPrompt -> {
                    val tv = v.findViewById<TextView>(R.id.codeUserText)
                    tv.text = when {
                        e.attachmentCount <= 0 -> e.text
                        e.text.isBlank() -> v.context.getString(R.string.code_user_with_images, e.attachmentCount)
                        else -> e.text + v.context.getString(R.string.code_user_images_suffix, e.attachmentCount)
                    }
                }
                is CodeEvent.AgentText -> bindText(holder as TextHolder, e)
                is CodeEvent.Thought -> bindThought(v, e)
                is CodeEvent.ToolCall -> bindTool(v, e)
                is CodeEvent.FileDiff -> bindDiff(v, e)
                is CodeEvent.Approval -> bindApproval(v, e)
                is CodeEvent.Plan -> bindPlan(v, e)
                is CodeEvent.Notice -> (v as TextView).apply {
                    text = e.text
                    setTextColor(context.getColor(if (e.level == NoticeLevel.ERROR) R.color.xai_ink else R.color.xai_mute))
                }
                is CodeEvent.TurnEnd -> v.findViewById<TextView>(R.id.codeTurnText).text =
                    e.summary ?: if (e.stopReason == "cancelled") v.context.getString(R.string.code_session_stopped) else ""
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is TextHolder -> {
                clearStreamBound(holder)
                holder.stopFadeTicker()
                clearAgentImages(holder)
            }
            else -> (holder.itemView as? TextView)?.let {
                if (holder.itemViewType == T_WORKING) ShimmerText.stop(it)
            }
        }
    }

    /**
     * Streaming agent text: same pacing and soft per-word fade as [io.github.stardomains3.oxproxion.ChatAdapter] (no cursor).
     * Thoughts/tools stay plain — chat only fades the assistant reply body.
     */
    private fun bindText(holder: TextHolder, e: CodeEvent.AgentText) {
        val tv = holder.textView
        if (e.streaming) {
            streamBoundHolder = holder
            streamBoundKey = e.key
            // Agents send text in large bursts; pace it into a steady flow like chat does.
            if (revealKey != e.key) {
                reveal.reset()
                revealKey = e.key
            }
            reveal.setTarget(e.text)
            renderStreaming(e.key, reveal.displayed())
        } else {
            if (revealKey == e.key) { reveal.reset(); revealKey = null }
            clearStreamBound(holder)
            streams.remove(e.key)?.markdown?.reset()
            holder.stopFadeTicker()
            tv.text = if (e.text.isEmpty()) "" else ChatMarkdown.polished(markwon.toMarkdown(e.text))
        }
        tv.isVisible = e.text.isNotEmpty() || e.streaming || e.images.isEmpty()
        bindAgentImages(holder, e.images)
    }

    /**
     * Show [images] under agent text. Decode is always off Main ([decodeScope] + Default);
     * skip row rebuild when the bound fingerprint is unchanged (TextChunk stream path).
     */
    private fun bindAgentImages(holder: TextHolder, images: List<AgentInlineImage>) {
        val keys = images.map { it.cacheKey }
        // I3: identity gate — do not removeAllViews / re-query on every TextChunk.
        if (holder.boundImageKeys == keys) return

        clearAgentImages(holder)
        holder.boundImageKeys = keys
        val gen = holder.decodeGeneration
        val row = holder.imagesRow
        val scroll = holder.imagesScroll
        if (images.isEmpty()) {
            scroll.isVisible = false
            return
        }
        val ctx = row.context
        val d = ctx.resources.displayMetrics.density
        val maxH = (160 * d).toInt()
        val gap = (8 * d).toInt()
        // Decode near display size (≈160dp × 2), not prompt-encode 1536px.
        val maxEdge = (160 * d * 2f).toInt().coerceIn(160, CodePromptImages.TRANSCRIPT_EDGE_PX)
        for ((shown, img) in images.withIndex()) {
            val key = keys[shown]
            val iv = ImageView(ctx).apply {
                tag = key
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_START
                setMaxHeight(maxH)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { lp ->
                    if (shown > 0) lp.marginStart = gap
                }
                contentDescription = ctx.getString(R.string.cd_code_agent_image)
            }
            row.addView(iv)
            val cached = inlineBitmaps.get(key)
            if (cached != null && !cached.isRecycled) {
                iv.setImageBitmap(cached)
                displayedImageKeys.add(key)
            } else {
                // Placeholder until async decode posts the bitmap.
                requestInlineDecode(holder, gen, key, img, maxEdge)
            }
        }
        scroll.isVisible = true
    }

    private fun requestInlineDecode(
        holder: TextHolder,
        gen: Int,
        key: String,
        img: AgentInlineImage,
        maxEdge: Int,
    ) {
        // Coalesce in-flight decodes for the same key on this holder.
        val existing = holder.decodeJobs[key]
        if (existing != null && existing.isActive) return
        val job = decodeScope.launch(Dispatchers.Default) {
            val bmp = CodePromptImages.decodeInline(img.data, img.mimeType, maxEdge)
            withContext(Dispatchers.Main.immediate) {
                holder.decodeJobs.remove(key)
                if (bmp == null || bmp.isRecycled) return@withContext
                // Prefer an already-cached live bitmap; recycle our duplicate decode.
                val cached = inlineBitmaps.get(key)
                val use = if (cached != null && !cached.isRecycled) {
                    if (cached !== bmp) bmp.recycle()
                    cached
                } else {
                    inlineBitmaps.put(key, bmp)
                    bmp
                }
                if (holder.decodeGeneration != gen) return@withContext
                if (holder.boundImageKeys?.contains(key) != true) return@withContext
                val row = holder.imagesRow
                for (i in 0 until row.childCount) {
                    val child = row.getChildAt(i) as? ImageView ?: continue
                    if (child.tag == key) {
                        child.setImageBitmap(use)
                        displayedImageKeys.add(key)
                        break
                    }
                }
            }
        }
        holder.decodeJobs[key] = job
    }

    /** Clear image row, cancel pending decodes, drop displayed-key refs. */
    private fun clearAgentImages(holder: TextHolder) {
        holder.decodeJobs.values.forEach { it.cancel() }
        holder.decodeJobs.clear()
        holder.decodeGeneration++
        val row = holder.imagesRow
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i) as? ImageView ?: continue
            val key = child.tag as? String
            if (key != null) displayedImageKeys.remove(key)
            child.setImageBitmap(null)
        }
        row.removeAllViews()
        holder.imagesScroll.isVisible = false
        holder.boundImageKeys = null
    }

    /**
     * Track newly appended open-tail ranges and attach a [StreamFadeSpan] per run (chat pattern).
     * V3: on shrink / IncrementalMarkdown reset / stable-boundary advance, clear fade clocks and
     * only fade past [IncrementalMarkdown.openTailStart] so closed blocks never re-enter a fade.
     */
    private fun applyStreamFades(state: StreamState, text: SpannableStringBuilder, now: Long) {
        val len = text.length
        val fadeBase = state.markdown.openTailStart.coerceIn(0, len)
        if (state.markdown.didReset || len < state.lastRenderedLen || fadeBase > state.fadeBase) {
            state.fadeStarts.clear()
            state.fadeTimes.clear()
            state.lastRenderedLen = fadeBase
        }
        state.fadeBase = fadeBase
        if (state.lastRenderedLen < fadeBase) {
            state.lastRenderedLen = fadeBase
        }
        if (len > state.lastRenderedLen) {
            state.fadeStarts.add(state.lastRenderedLen)
            state.fadeTimes.add(now)
        }
        state.lastRenderedLen = len
        while (state.fadeTimes.isNotEmpty() && now - state.fadeTimes[0] >= StreamFadeSpan.DURATION_MS) {
            state.fadeStarts.removeAt(0)
            state.fadeTimes.removeAt(0)
        }
        for (i in state.fadeStarts.indices) {
            val start = state.fadeStarts[i].coerceIn(fadeBase, len)
            val end = (if (i + 1 < state.fadeStarts.size) state.fadeStarts[i + 1] else len).coerceAtMost(len)
            if (end > start) {
                text.setSpan(
                    StreamFadeSpan(state.fadeTimes[i]),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun bindThought(v: View, e: CodeEvent.Thought) {
        val body = v.findViewById<TextView>(R.id.codeThoughtText)
        val chevron = v.findViewById<View>(R.id.codeThoughtChevron)
        // Thinking verbosity opens every thought; a tap flips just this one either way.
        val open = (e.key in expanded) != verbose
        body.text = e.text
        body.isVisible = open
        chevron.rotation = if (open) 90f else 0f
        v.findViewById<View>(R.id.codeThoughtHeader).setOnClickListener {
            if (!expanded.add(e.key)) expanded.remove(e.key)
            notifyItemChanged(currentList.indexOfFirst { it.key == e.key })
        }
    }

    private fun bindTool(v: View, e: CodeEvent.ToolCall) {
        v.findViewById<ImageView>(R.id.codeToolIcon).setImageResource(iconFor(e.kind))
        v.findViewById<TextView>(R.id.codeToolTitle).text = e.title
        v.findViewById<TextView>(R.id.codeToolDetail).apply {
            text = e.detail
            isVisible = !e.detail.isNullOrBlank()
        }
        val spinner = v.findViewById<CircularProgressIndicator>(R.id.codeToolSpinner)
        val status = v.findViewById<ImageView>(R.id.codeToolStatus)
        val running = e.status == ToolStatus.RUNNING || e.status == ToolStatus.PENDING
        spinner.isVisible = running
        // Quiet when it worked; only a failure earns a mark.
        status.isVisible = e.status == ToolStatus.FAILED
        status.setImageResource(R.drawable.ic_code_cross)
        val hasOutput = !e.output.isNullOrBlank()
        // Commands show their output live while they run; everything else opens on tap.
        val open = hasOutput && (((e.key in expanded) != verbose) || (e.kind == ToolKind.EXECUTE && e.status == ToolStatus.RUNNING))
        val raw = e.output.orEmpty()
        v.findViewById<View>(R.id.codeToolOutputScroll).isVisible = open
        val full = v.findViewById<TextView>(R.id.codeToolFull)
        full.isVisible = open
        if (open) {
            v.findViewById<TextView>(R.id.codeToolOutput).text = ToolOutputText.cardPreview(raw, OUTPUT_LINES)
            val hidden = (raw.lines().size - OUTPUT_LINES).coerceAtLeast(0)
            full.text = if (hidden > 0) {
                v.context.getString(R.string.code_session_more_lines, hidden)
            } else {
                v.context.getString(R.string.code_tool_full_output)
            }
            full.setOnClickListener { onOpenToolOutput(e) }
        } else {
            full.setOnClickListener(null)
        }
        v.findViewById<View>(R.id.codeToolRow).apply {
            isClickable = hasOutput
            setOnClickListener {
                if (!expanded.add(e.key)) expanded.remove(e.key)
                notifyItemChanged(currentList.indexOfFirst { it.key == e.key })
            }
            setOnLongClickListener {
                if (!hasOutput) return@setOnLongClickListener false
                onOpenToolOutput(e)
                true
            }
        }
    }

    private fun bindDiff(v: View, e: CodeEvent.FileDiff) {
        val ctx = v.context
        v.findViewById<TextView>(R.id.codeDiffPath).text = e.path
        v.findViewById<TextView>(R.id.codeDiffCounts).text =
            if (e.isNewFile) ctx.getString(R.string.code_session_new_file) else coloredDiffCounts(ctx, e.added, e.removed)
        val dv = v.findViewById<DiffView>(R.id.codeDiffLines)
        dv.maxLines = CARD_LINES
        dv.lines = e.lines
        val more = v.findViewById<TextView>(R.id.codeDiffMore)
        val hidden = e.lines.size - CARD_LINES
        more.isVisible = true
        more.text = if (hidden > 0) ctx.getString(R.string.code_session_more_lines, hidden) else ctx.getString(R.string.code_session_show_full_diff)
        v.findViewById<View>(R.id.codeDiffCard).setOnClickListener { onOpenDiff(e) }
    }

    private fun bindApproval(v: View, e: CodeEvent.Approval) {
        val ctx = v.context
        val card = v.findViewById<View>(R.id.codeApprovalCard)
        val done = v.findViewById<View>(R.id.codeApprovalDone)
        if (e.chosen != null) {
            card.isVisible = false
            done.isVisible = true
            val allowed = e.chosen == ApprovalOption.Kind.ALLOW_ONCE || e.chosen == ApprovalOption.Kind.ALLOW_ALWAYS
            v.findViewById<ImageView>(R.id.codeApprovalDoneIcon).setImageResource(if (allowed) R.drawable.ic_code_check else R.drawable.ic_code_cross)
            val verdict = ctx.getString(when (e.chosen) {
                ApprovalOption.Kind.ALLOW_ALWAYS -> R.string.code_approval_allowed_always
                ApprovalOption.Kind.ALLOW_ONCE -> R.string.code_approval_allowed
                else -> R.string.code_approval_denied
            })
            v.findViewById<TextView>(R.id.codeApprovalDoneText).text = "$verdict · ${e.title}"
            return
        }
        card.isVisible = true
        done.isVisible = false
        v.findViewById<TextView>(R.id.codeApprovalTitle).text = ctx.getString(when (e.kind) {
            ToolKind.EDIT, ToolKind.DELETE, ToolKind.MOVE -> R.string.code_approval_edit
            ToolKind.EXECUTE -> R.string.code_approval_run
            ToolKind.FETCH -> R.string.code_approval_fetch
            else -> R.string.code_approval_generic
        })
        v.findViewById<TextView>(R.id.codeApprovalWhat).text = e.title
        v.findViewById<TextView>(R.id.codeApprovalDetail).apply {
            text = e.detail
            isVisible = !e.detail.isNullOrBlank()
        }
        val box = v.findViewById<LinearLayout>(R.id.codeApprovalButtons)
        box.removeAllViews()
        val d = ctx.resources.displayMetrics.density
        // Reject on the left, the expected action last (thumb side), like iOS alerts.
        val ordered = e.options.sortedBy {
            when (it.kind) {
                ApprovalOption.Kind.REJECT_ALWAYS -> 0
                ApprovalOption.Kind.REJECT_ONCE -> 1
                ApprovalOption.Kind.ALLOW_ALWAYS -> 2
                ApprovalOption.Kind.ALLOW_ONCE -> 3
            }
        }
        ordered.forEachIndexed { i, opt ->
            val lead = opt.kind == ApprovalOption.Kind.ALLOW_ONCE
            val b = LayoutInflater.from(ctx).inflate(
                if (lead) R.layout.item_code_button_lead else R.layout.item_code_button, box, false
            ) as MaterialButton
            b.text = opt.label
            b.setOnClickListener { onApproval(e, opt) }
            box.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (40 * d).toInt()).apply {
                if (i > 0) marginStart = (8 * d).toInt()
            })
        }
    }

    private fun bindPlan(v: View, e: CodeEvent.Plan) {
        val box = v.findViewById<LinearLayout>(R.id.codePlanRows)
        box.removeAllViews()
        val ctx = v.context
        val d = ctx.resources.displayMetrics.density
        for (entry in e.entries) {
            val row = TextView(ctx).apply {
                text = entry.content
                textSize = 15f
                setTextColor(ctx.getColor(when (entry.status) {
                    PlanStatus.COMPLETED -> R.color.xai_mute
                    PlanStatus.IN_PROGRESS -> R.color.xai_ink
                    PlanStatus.PENDING -> R.color.xai_body
                }))
                if (entry.status == PlanStatus.IN_PROGRESS) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setCompoundDrawablesRelativeWithIntrinsicBounds(when (entry.status) {
                    PlanStatus.COMPLETED -> R.drawable.ic_code_plan_done
                    PlanStatus.IN_PROGRESS -> R.drawable.ic_code_plan_progress
                    PlanStatus.PENDING -> R.drawable.ic_code_plan_pending
                }, 0, 0, 0)
                compoundDrawablePadding = (12 * d).toInt()
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            }
            box.addView(row)
        }
    }

    private class TextHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(R.id.codeAgentText)
        val imagesScroll: View = itemView.findViewById(R.id.codeAgentImagesScroll)
        val imagesRow: LinearLayout = itemView.findViewById(R.id.codeAgentImages)
        /** Last bound image cache keys — skip rebuild when unchanged (stream TextChunks). */
        var boundImageKeys: List<String>? = null
        /** Bumped on clear/rebind so in-flight decode results ignore stale holders. */
        var decodeGeneration: Int = 0
        val decodeJobs = HashMap<String, Job>()
        private var fadeTicker: Choreographer.FrameCallback? = null

        fun ensureFadeTicker() {
            if (fadeTicker != null) return
            val ticker = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNs: Long) {
                    if (!textView.isAttachedToWindow) {
                        fadeTicker = null
                        return
                    }
                    val text = textView.text
                    val fades = if (text is Spanned) {
                        text.getSpans(0, text.length, StreamFadeSpan::class.java)
                    } else emptyArray()
                    val cursors = if (text is Spanned) {
                        text.getSpans(0, text.length, StreamCursorSpan::class.java)
                    } else emptyArray()
                    val fadesDone = fades.isEmpty() || fades.all { it.isDone() }
                    if (fadesDone && cursors.isEmpty()) {
                        fadeTicker = null
                        if (text is Spannable) {
                            fades.forEach { text.removeSpan(it) }
                        }
                        return
                    }
                    if (fadesDone && text is Spannable) {
                        fades.forEach { text.removeSpan(it) }
                    }
                    textView.invalidate()
                    // Word fades need every frame; the breathing cursor alone is fine at
                    // ~15fps and saves a full text redraw per frame for the whole stream.
                    if (fadesDone) {
                        Choreographer.getInstance().postFrameCallbackDelayed(this, CURSOR_FRAME_MS)
                    } else {
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                }
            }
            fadeTicker = ticker
            Choreographer.getInstance().postFrameCallback(ticker)
        }

        fun stopFadeTicker() {
            fadeTicker?.let { Choreographer.getInstance().removeFrameCallback(it) }
            fadeTicker = null
        }
    }

    private class Simple(v: View) : RecyclerView.ViewHolder(v)

    companion object {
        private const val CURSOR_FRAME_MS = 66L
        private const val T_USER = 1
        private const val T_TEXT = 2
        private const val T_THOUGHT = 3
        private const val T_TOOL = 4
        private const val T_DIFF = 5
        private const val T_APPROVAL = 6
        private const val T_PLAN = 7
        private const val T_NOTICE = 8
        private const val T_TURN = 9
        private const val T_WORKING = 10
        private const val CARD_LINES = 14
        private const val OUTPUT_LINES = ToolOutputText.CARD_LINES
        /** Inline bitmap LruCache budget in KB (~6MB). */
        private const val INLINE_CACHE_MAX_KB = 6 * 1024

        fun iconFor(kind: ToolKind) = when (kind) {
            ToolKind.READ -> R.drawable.ic_code_file
            ToolKind.EDIT -> R.drawable.ic_code_pencil
            ToolKind.EXECUTE -> R.drawable.ic_code_terminal
            ToolKind.SEARCH -> R.drawable.ic_search_stroke
            ToolKind.FETCH -> R.drawable.ic_code_globe
            ToolKind.THINK -> R.drawable.ic_code_bulb
            ToolKind.DELETE -> R.drawable.ic_code_trash
            ToolKind.MOVE -> R.drawable.ic_code_move
            ToolKind.OTHER -> R.drawable.ic_code_link
        }

        private val DIFF = object : DiffUtil.ItemCallback<TranscriptRow>() {
            override fun areItemsTheSame(a: TranscriptRow, b: TranscriptRow) = a.key == b.key
            override fun areContentsTheSame(a: TranscriptRow, b: TranscriptRow) = a == b
        }
    }
}
