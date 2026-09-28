package io.github.stardomains3.oxproxion

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * The screen the Roleplay tab opens on: the chats you already have, one row per character
 * (newest first). New chats start from the top bar's button. It replaces landing
 * straight in the last thread, which made moving between characters a hunt through History.
 *
 * Pure view code: [ChatFragment] feeds it data and decides when it shows.
 */
class RpChatsHome(
    private val root: View,
    private val onOpen: (RpChatSummary) -> Unit,
    private val onMenu: (View, RpChatSummary) -> Unit
) {
    private val list = root.findViewById<RecyclerView>(R.id.rpHomeList)
    private val empty = root.findViewById<View>(R.id.rpHomeEmpty)
    private val content = root.findViewById<View>(R.id.rpHomeContent)
    private val adapter = Adapter()

    init {
        list.layoutManager = LinearLayoutManager(root.context)
        list.adapter = adapter
        list.itemAnimator = null
    }

    val isShown get() = root.isVisible

    fun show(visible: Boolean) {
        root.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Keep the first row clear of the floating top bar. */
    fun setTopInset(px: Int) {
        if (content.paddingTop != px) content.setPadding(content.paddingLeft, px, content.paddingRight, content.paddingBottom)
    }

    fun submit(summaries: List<RpChatSummary>) {
        adapter.submitList(summaries) {
            // The relative times move on even when nothing else did: refresh just those.
            if (summaries.isNotEmpty()) adapter.notifyItemRangeChanged(0, summaries.size, PAYLOAD_TIME)
        }
        empty.isVisible = summaries.isEmpty()
        list.isVisible = summaries.isNotEmpty()
    }

    private inner class Adapter : ListAdapter<RpChatSummary, Adapter.Holder>(Diff) {
        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int) = idOf(getItem(position))

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_rp_chat, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_TIME)) holder.bindTime(getItem(position)) else super.onBindViewHolder(holder, position, payloads)
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val avatar = view.findViewById<ImageView>(R.id.rpChatAvatar)
            private val monogram = view.findViewById<TextView>(R.id.rpChatMonogram)
            private val name = view.findViewById<TextView>(R.id.rpChatName)
            private val count = view.findViewById<TextView>(R.id.rpChatCount)
            private val preview = view.findViewById<TextView>(R.id.rpChatPreview)
            private val whenView = view.findViewById<TextView>(R.id.rpChatWhen)
            private val more = view.findViewById<ImageButton>(R.id.rpChatMore)

            fun bind(row: RpChatSummary) {
                name.text = row.name
                count.isVisible = row.chats > 1
                count.text = itemView.context.getString(R.string.rp_home_chat_count, row.chats)
                if (row.character != null) RpAvatars.bind(avatar, monogram, row.character)
                else RpAvatars.bindModel(avatar, monogram, null, row.name)
                preview.text = styled(row.preview)
                bindTime(row)
                itemView.setOnClickListener { onOpen(row) }
                more.setOnClickListener { onMenu(more, row) }
                itemView.setOnLongClickListener { onMenu(more, row); true }
            }

            fun bindTime(row: RpChatSummary) {
                whenView.text = DateUtils.getRelativeTimeSpanString(
                    row.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
                ).toString()
            }
        }
    }

    /** Actions in the last line are italic, so "sighs and grabs a wrench" doesn't run into "Fine.". */
    private fun styled(preview: String): CharSequence {
        val parts = RpChatSummaries.styledPreview(preview)
        if (parts.actions.isEmpty()) return parts.text
        return SpannableString(parts.text).apply {
            parts.actions.forEach { r ->
                setSpan(StyleSpan(Typeface.ITALIC), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<RpChatSummary>() {
        override fun areItemsTheSame(a: RpChatSummary, b: RpChatSummary) = idOf(a) == idOf(b)
        // The time is refreshed separately (see submit), so it can't be part of "changed".
        override fun areContentsTheSame(a: RpChatSummary, b: RpChatSummary) = a == b
    }

    private companion object {
        val PAYLOAD_TIME = Any()

        /** One row per character; LLM chats and orphaned chats have no character id, so they key on themselves. */
        fun idOf(row: RpChatSummary): Long = when {
            row.isLlm -> -1L
            row.character != null -> row.character.id
            else -> -1000L - row.sessionId
        }
    }
}
