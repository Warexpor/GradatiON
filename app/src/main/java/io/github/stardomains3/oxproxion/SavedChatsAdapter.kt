package io.github.stardomains3.oxproxion

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            is HistoryListItem.Session -> (holder as ChatSessionViewHolder).bind(item)
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
        private val previewTextView: TextView = itemView.findViewById(R.id.savedChatPreview)
        private val timestampTextView: TextView = itemView.findViewById(R.id.savedChatTimestamp)
        private val pinView: ImageView = itemView.findViewById(R.id.savedChatPin)
        private val overflowButton: ImageButton = itemView.findViewById(R.id.iconEditt)
        private val ink = ContextCompat.getColor(itemView.context, R.color.xai_ink)
        private val mute = ContextCompat.getColor(itemView.context, R.color.xai_mute)
        private val timeFace = timestampTextView.typeface ?: Typeface.DEFAULT
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

        fun bind(item: HistoryListItem.Session) {
            val session = item.session
            currentSession = session
            titleTextView.text = emphasize(TitleMarkdown.render(session.title), item.query)
            pinView.visibility = if (item.pinned) View.VISIBLE else View.GONE
            if (item.open) {
                timestampTextView.setText(R.string.rp_history_current)
                timestampTextView.setTextColor(ink)
                timestampTextView.typeface = Typeface.create(timeFace, 600, false)
            } else {
                timestampTextView.text = formatHistoryTimestamp(session.timestamp)
                timestampTextView.setTextColor(mute)
                timestampTextView.typeface = Typeface.create(timeFace, 400, false)
            }
            if (item.preview.isBlank()) {
                previewTextView.visibility = View.GONE
                previewTextView.text = ""
            } else {
                previewTextView.visibility = View.VISIBLE
                previewTextView.text = emphasize(item.preview, item.query)
            }
        }

        private fun emphasize(text: CharSequence, query: String): CharSequence {
            val hit = HistoryList.emphasis(text.toString(), query) ?: return text
            val end = hit.start + hit.length
            if (hit.start < 0 || end > text.length) return text
            return SpannableString(text).apply {
                setSpan(StyleSpan(Typeface.BOLD), hit.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        private fun formatHistoryTimestamp(timestamp: Long): String {
            val formats = HistoryTimeFormats.get(itemView.context)
            val date = Date(timestamp)
            return when (HistoryList.timestampKind(timestamp, System.currentTimeMillis())) {
                HistoryList.TimestampKind.TIME -> formats.time.format(date)
                HistoryList.TimestampKind.WEEKDAY -> formats.weekday.format(date)
                HistoryList.TimestampKind.MONTH_DAY -> formats.monthDay.format(date)
                HistoryList.TimestampKind.FULL -> formats.full.format(date)
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
