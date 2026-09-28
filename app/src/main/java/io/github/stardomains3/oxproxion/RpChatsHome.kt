package io.github.stardomains3.oxproxion

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The screen the Roleplay tab opens on: characters to start a chat with along the top, and the
 * chats you already have under them, one row per character (newest first). It replaces landing
 * straight in the last thread, which made moving between characters a hunt through History.
 *
 * Pure view code: [ChatFragment] feeds it data and decides when it shows.
 */
class RpChatsHome(
    private val root: View,
    private val onOpen: (RpChatSummary) -> Unit,
    private val onStart: (RpCharacter) -> Unit,
    private val onBrowse: () -> Unit,
    private val onMenu: (View, RpChatSummary) -> Unit
) {
    private val list = root.findViewById<RecyclerView>(R.id.rpHomeList)
    private val strip = root.findViewById<LinearLayout>(R.id.rpHomeStripRow)
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

    fun submit(summaries: List<RpChatSummary>, characters: List<RpCharacter>) {
        adapter.items = summaries
        adapter.notifyDataSetChanged()
        empty.isVisible = summaries.isEmpty()
        list.isVisible = summaries.isNotEmpty()
        renderStrip(characters)
    }

    private fun renderStrip(characters: List<RpCharacter>) {
        strip.removeAllViews()
        val inflater = LayoutInflater.from(root.context)
        characters.sortedByDescending { it.updatedAt }.take(STRIP_MAX).forEach { c ->
            val item = inflater.inflate(R.layout.item_rp_home_character, strip, false)
            item.findViewById<TextView>(R.id.rpHomeCharName).text = c.name
            RpAvatars.bind(
                item.findViewById<ImageView>(R.id.rpHomeCharAvatar),
                item.findViewById<TextView>(R.id.rpHomeCharMonogram),
                c
            )
            item.contentDescription = c.name
            item.setOnClickListener { onStart(c) }
            strip.addView(item)
        }
        val all = inflater.inflate(R.layout.item_rp_home_character, strip, false)
        all.findViewById<TextView>(R.id.rpHomeCharName).setText(R.string.rp_home_all)
        all.findViewById<View>(R.id.rpHomeCharMonogram).visibility = View.GONE
        all.findViewById<ImageView>(R.id.rpHomeCharGlyph).apply {
            setImageResource(R.drawable.rp_ic_characters)
            visibility = View.VISIBLE
        }
        all.contentDescription = root.context.getString(R.string.rp_characters_title)
        all.setOnClickListener { onBrowse() }
        strip.addView(all)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        var items: List<RpChatSummary> = emptyList()

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_rp_chat, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val avatar = view.findViewById<ImageView>(R.id.rpChatAvatar)
            private val monogram = view.findViewById<TextView>(R.id.rpChatMonogram)
            private val name = view.findViewById<TextView>(R.id.rpChatName)
            private val preview = view.findViewById<TextView>(R.id.rpChatPreview)
            private val whenView = view.findViewById<TextView>(R.id.rpChatWhen)
            private val more = view.findViewById<ImageButton>(R.id.rpChatMore)

            fun bind(row: RpChatSummary) {
                name.text = row.name
                if (row.character != null) RpAvatars.bind(avatar, monogram, row.character)
                else RpAvatars.bindModel(avatar, monogram, null, row.name)
                preview.text = row.preview
                val ago = DateUtils.getRelativeTimeSpanString(
                    row.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
                ).toString()
                whenView.text = if (row.chats > 1) {
                    ago + "\n" + itemView.context.getString(R.string.rp_home_chat_count, row.chats)
                } else ago
                itemView.setOnClickListener { onOpen(row) }
                more.setOnClickListener { onMenu(more, row) }
                itemView.setOnLongClickListener { onMenu(more, row); true }
            }
        }
    }

    private companion object {
        const val STRIP_MAX = 12
    }
}
