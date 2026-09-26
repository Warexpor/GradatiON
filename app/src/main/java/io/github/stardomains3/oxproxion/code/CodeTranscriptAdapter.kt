package io.github.stardomains3.oxproxion.code

import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
    private val onApproval: (CodeEvent.Approval, ApprovalOption) -> Unit,
    private val onOpenDiff: (CodeEvent.FileDiff) -> Unit
) : ListAdapter<TranscriptRow, RecyclerView.ViewHolder>(DIFF) {

    private val markwon: Markwon = Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(SoftBreakAddsNewLinePlugin.create())
        .usePlugin(ChatMarkdown.plugin(context))
        .build()
    private val streams = HashMap<String, IncrementalMarkdown>()
    private val expanded = HashSet<String>()

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        fun v(res: Int) = inf.inflate(res, parent, false)
        return when (viewType) {
            T_USER -> Simple(v(R.layout.item_code_user))
            T_TEXT -> Simple(v(R.layout.item_code_text)).also {
                (it.itemView as TextView).movementMethod = LinkMovementMethod.getInstance()
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
                is CodeEvent.UserPrompt -> v.findViewById<TextView>(R.id.codeUserText).text = e.text
                is CodeEvent.AgentText -> bindText(v as TextView, e)
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
        (holder.itemView as? TextView)?.let { if (holder.itemViewType == T_WORKING) ShimmerText.stop(it) }
    }

    private fun bindText(tv: TextView, e: CodeEvent.AgentText) {
        if (e.streaming) {
            val inc = streams.getOrPut(e.key) { IncrementalMarkdown(markwon) }
            tv.text = inc.render(e.text)
        } else {
            streams.remove(e.key)
            tv.text = ChatMarkdown.polished(markwon.toMarkdown(e.text))
        }
    }

    private fun bindThought(v: View, e: CodeEvent.Thought) {
        val body = v.findViewById<TextView>(R.id.codeThoughtText)
        val chevron = v.findViewById<View>(R.id.codeThoughtChevron)
        val open = e.key in expanded
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
        status.isVisible = !running
        status.setImageResource(if (e.status == ToolStatus.FAILED) R.drawable.ic_code_cross else R.drawable.ic_code_check)
        val hasOutput = !e.output.isNullOrBlank()
        // Commands show their output live while they run; everything else opens on tap.
        val open = hasOutput && (e.key in expanded || (e.kind == ToolKind.EXECUTE && e.status == ToolStatus.RUNNING))
        v.findViewById<View>(R.id.codeToolOutputScroll).isVisible = open
        if (open) v.findViewById<TextView>(R.id.codeToolOutput).text = e.output!!.lines().takeLast(OUTPUT_LINES).joinToString("\n")
        v.findViewById<View>(R.id.codeToolRow).apply {
            isClickable = hasOutput
            setOnClickListener {
                if (!expanded.add(e.key)) expanded.remove(e.key)
                notifyItemChanged(currentList.indexOfFirst { it.key == e.key })
            }
        }
    }

    private fun bindDiff(v: View, e: CodeEvent.FileDiff) {
        val ctx = v.context
        v.findViewById<TextView>(R.id.codeDiffPath).text = e.path
        v.findViewById<TextView>(R.id.codeDiffCounts).text =
            if (e.isNewFile) ctx.getString(R.string.code_session_new_file) else ctx.getString(R.string.code_diff_counts, e.added, e.removed)
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

    private class Simple(v: View) : RecyclerView.ViewHolder(v)

    companion object {
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
        private const val OUTPUT_LINES = 40

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
