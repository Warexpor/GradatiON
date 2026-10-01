package io.github.stardomains3.oxproxion

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

sealed class HistoryListItem {
    data class Header(val title: String) : HistoryListItem()
    data class Session(val session: ChatSession, val pinned: Boolean) : HistoryListItem()
}

class SavedChatsAdapter(
    private val onClick: (ChatSession) -> Unit,
    private val onOverflowClick: (ChatSession, View) -> Unit
) : ListAdapter<HistoryListItem, RecyclerView.ViewHolder>(HistoryDiffCallback()) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SESSION = 1
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is HistoryListItem.Header -> TYPE_HEADER
        is HistoryListItem.Session -> TYPE_SESSION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_history_section_header, parent, false))
        } else {
            ChatSessionViewHolder(
                inflater.inflate(R.layout.item_saved_chat, parent, false),
                onClick,
                onOverflowClick
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is HistoryListItem.Header -> (holder as HeaderViewHolder).bind(item.title)
            is HistoryListItem.Session -> (holder as ChatSessionViewHolder).bind(item.session)
        }
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.historySectionHeader)
        fun bind(title: String) {
            titleView.text = title
        }
    }

    class ChatSessionViewHolder(
        itemView: View,
        val onClick: (ChatSession) -> Unit,
        val onOverflowClick: (ChatSession, View) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.savedChatTitle)
        private val timestampTextView: TextView = itemView.findViewById(R.id.savedChatTimestamp)
        private val overflowButton: ImageButton = itemView.findViewById(R.id.iconEditt)
        private var currentSession: ChatSession? = null

        init {
            itemView.setOnClickListener {
                currentSession?.let(onClick)
            }
            itemView.setOnLongClickListener {
                val session = currentSession ?: return@setOnLongClickListener false
                onOverflowClick(session, overflowButton)
                true
            }
            // The row has no visible menu button, so name the long-press for TalkBack.
            androidx.core.view.ViewCompat.replaceAccessibilityAction(
                itemView,
                androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                itemView.context.getString(R.string.grok_history_more),
                null
            )
            overflowButton.setOnClickListener {
                val session = currentSession ?: return@setOnClickListener
                onOverflowClick(session, overflowButton)
            }
        }

        fun bind(session: ChatSession) {
            currentSession = session
            titleTextView.text = TitleMarkdown.render(session.title)
            timestampTextView.text = formatHistoryTimestamp(session.timestamp)
        }

        private fun formatHistoryTimestamp(timestamp: Long): String {
            val formats = HistoryTimeFormats.get(itemView.context)
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = timestamp }
            val date = Date(timestamp)
            return when {
                now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                    now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> formats.time.format(date)
                now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                    now.get(Calendar.WEEK_OF_YEAR) == then.get(Calendar.WEEK_OF_YEAR) -> formats.weekday.format(date)
                now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> formats.monthDay.format(date)
                else -> formats.full.format(date)
            }
        }
    }

}

/** The row timestamp formats, built once per locale and 12/24-hour setting instead of on every bind. */
private class HistoryTimeFormats(val locale: Locale, val use24h: Boolean) {
    val time = SimpleDateFormat(if (use24h) "HH:mm" else "h:mm a", locale)
    val weekday = SimpleDateFormat("EEEE", locale)
    val monthDay = SimpleDateFormat("MMM d", locale)
    val full = SimpleDateFormat("MMM d, yyyy", locale)

    companion object {
        private var cached: HistoryTimeFormats? = null

        /** Main thread only, like every bind. */
        fun get(context: android.content.Context): HistoryTimeFormats {
            val locale = Locale.getDefault()
            val use24h = android.text.format.DateFormat.is24HourFormat(context)
            cached?.let { if (it.locale == locale && it.use24h == use24h) return it }
            return HistoryTimeFormats(locale, use24h).also { cached = it }
        }
    }
}

class HistoryDiffCallback : DiffUtil.ItemCallback<HistoryListItem>() {
    override fun areItemsTheSame(oldItem: HistoryListItem, newItem: HistoryListItem): Boolean {
        return when {
            oldItem is HistoryListItem.Header && newItem is HistoryListItem.Header ->
                oldItem.title == newItem.title
            oldItem is HistoryListItem.Session && newItem is HistoryListItem.Session ->
                oldItem.session.id == newItem.session.id
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: HistoryListItem, newItem: HistoryListItem): Boolean {
        return oldItem == newItem
    }
}
