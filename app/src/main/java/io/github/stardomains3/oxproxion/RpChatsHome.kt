package io.github.stardomains3.oxproxion

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The screen the Roleplay tab opens on: every character, one row each. The ones you are
 * mid-story with come first (newest chat on top); the rest sit below and start a chat on a tap.
 * It replaces landing straight in the last thread and the History drawer, which made moving
 * between characters a hunt.
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
        // The top-bar button does the same, but nothing says so on an empty screen.
        root.findViewById<View>(R.id.rpHomeEmptyAdd).setOnClickListener {
            (root.context as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager?.fragments
                ?.filterIsInstance<ChatFragment>()?.firstOrNull()?.openRpCharacterLibrary()
        }
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
        adapter.items = summaries
        adapter.notifyDataSetChanged()
        empty.isVisible = summaries.isEmpty()
        list.isVisible = summaries.isNotEmpty()
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
            private val more = view.findViewById<ImageButton>(R.id.rpChatMore)

            fun bind(row: RpChatSummary) {
                name.text = row.name
                if (row.character != null) RpAvatars.bind(avatar, monogram, row.character)
                else RpAvatars.bindModel(avatar, monogram, null, row.name)
                preview.text = row.preview
                preview.alpha = if (row.sessionId == null) 0.75f else 1f
                itemView.setOnClickListener { onOpen(row) }
                more.contentDescription = itemView.context.getString(R.string.rp_ui_more_options, row.name)
                more.setOnClickListener { onMenu(more, row) }
                itemView.setOnLongClickListener { onMenu(more, row); true }
            }
        }
    }
}
