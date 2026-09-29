package io.github.stardomains3.oxproxion

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Every chat with one character, from the character panel's History tile. A portrait header,
 * then the chats grouped by day with their last line, and a fresh chat at the bottom.
 *
 * It only picks: the choice goes back to [ChatFragment] as a fragment result, which owns loading
 * a chat and the new-chat confirmation.
 */
class RpChatHistoryFragment : Fragment() {

    private val chatViewModel: ChatViewModel by activityViewModels { AppViewModelFactory(requireActivity().application) }

    private sealed interface Item {
        object Header : Item
        data class Section(val label: String) : Item
        data class Chat(val id: Long, val whenLabel: String, val preview: String, val messages: Int, val current: Boolean) : Item
    }

    private var character: RpCharacter? = null
    private var chatCount = 0
    private val adapter = Adapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_rp_chat_history, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        view.findViewById<RecyclerView>(R.id.rpHistoryList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@RpChatHistoryFragment.adapter
            itemAnimator = null
        }
        view.findViewById<View>(R.id.rpHistoryNewChat).setOnClickListener { pick(bundleOf(NEW to true)) }

        val characterId = requireArguments().getLong(ARG_CHARACTER, LLM)
        val savedChats = androidx.lifecycle.ViewModelProvider(requireActivity(), AppViewModelFactory(requireActivity().application))[SavedChatsViewModel::class.java]
        savedChats.sessionsForMode(ChatMode.RP).observe(viewLifecycleOwner) { sessions ->
            val mine = sessions.orEmpty()
                .filter { if (characterId == LLM) it.isLlm else !it.isLlm && it.characterId == characterId }
                .sortedByDescending { it.timestamp }
            viewLifecycleOwner.lifecycleScope.launch {
                if (characterId != LLM && character == null) character = chatViewModel.getRpRepository().getCharacterById(characterId)
                adapter.items = build(mine)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private suspend fun build(sessions: List<ChatSession>): List<Item> {
        val dao = AppDatabase.getDatabase(requireContext().applicationContext).chatDao()
        val current = chatViewModel.getCurrentSessionId()
        chatCount = sessions.size
        val items = mutableListOf<Item>(Item.Header)
        var lastSection: String? = null
        for (s in sessions) {
            val section = sectionOf(s.timestamp)
            if (section != lastSection) {
                items += Item.Section(section)
                lastSection = section
            }
            val last = dao.getLastMessage(s.id)
            val text = last?.let { RpChatSummaries.previewOf(it.content) }.orEmpty()
            val preview = when {
                text.isBlank() -> getString(R.string.rp_home_no_preview)
                last?.role == "user" -> getString(R.string.rp_home_you, text)
                else -> text
            }
            items += Item.Chat(s.id, whenOf(s.timestamp), preview, dao.countMessages(s.id), s.id == current)
        }
        return items
    }

    private fun sectionOf(time: Long): String {
        val day = startOfDay(System.currentTimeMillis())
        return getString(when {
            time >= day -> R.string.rp_history_today
            time >= day - DateUtils.DAY_IN_MILLIS -> R.string.rp_history_yesterday
            time >= day - 6 * DateUtils.DAY_IN_MILLIS -> R.string.rp_history_week
            else -> R.string.rp_history_earlier
        })
    }

    /** The time alone today; the weekday this week; the date before that. */
    private fun whenOf(time: Long): String {
        val ctx = requireContext()
        val clock = DateFormat.getTimeFormat(ctx).format(time)
        val day = startOfDay(System.currentTimeMillis())
        return when {
            time >= day - DateUtils.DAY_IN_MILLIS -> clock
            time >= day - 6 * DateUtils.DAY_IN_MILLIS ->
                DateUtils.formatDateTime(ctx, time, DateUtils.FORMAT_SHOW_WEEKDAY) + ", " + clock
            else -> DateUtils.formatDateTime(ctx, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        }
    }

    private fun startOfDay(now: Long) = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun pick(result: Bundle) {
        setFragmentResult(RESULT, result)
        parentFragmentManager.popBackStack()
    }

    private fun card(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(ContextCompat.getColor(requireContext(), color))
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Item> = emptyList()

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = when (items[position]) {
            Item.Header -> 0
            is Item.Section -> 1
            is Item.Chat -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = when (viewType) {
                0 -> inflater.inflate(R.layout.item_rp_history_header, parent, false)
                1 -> TextView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(-1, -2)
                    val d = resources.displayMetrics.density
                    setPadding((6 * d).toInt(), (18 * d).toInt(), 0, (6 * d).toInt())
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(parent.context, R.font.app_sans)
                    setTextColor(ContextCompat.getColor(parent.context, R.color.xai_mute))
                    textSize = 13f
                    isAllCaps = true
                    letterSpacing = 0.06f
                }
                else -> inflater.inflate(R.layout.item_rp_history_chat, parent, false).apply {
                    background = RippleDrawable(
                        ColorStateList.valueOf(ContextCompat.getColor(parent.context, R.color.popover_row_pressed)),
                        card(R.color.panel_tile, 22f), card(android.R.color.white, 22f)
                    )
                    findViewById<View>(R.id.rpHistoryCurrent).background = card(R.color.panel_tile_on, 12f)
                }
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            when (val item = items[position]) {
                Item.Header -> {
                    val name = character?.name ?: getString(R.string.rp_llm_speaker)
                    val avatar = v.findViewById<ImageView>(R.id.rpHistoryAvatar)
                    val monogram = v.findViewById<TextView>(R.id.rpHistoryMonogram)
                    character?.let { RpAvatars.bind(avatar, monogram, it) } ?: RpAvatars.bindModel(avatar, monogram, null, name)
                    v.findViewById<TextView>(R.id.rpHistoryName).text = name
                    v.findViewById<TextView>(R.id.rpHistoryCount).text =
                        resources.getQuantityString(R.plurals.rp_history_count, chatCount, chatCount)
                }
                is Item.Section -> (v as TextView).text = item.label
                is Item.Chat -> {
                    v.findViewById<TextView>(R.id.rpHistoryWhen).text = item.whenLabel
                    v.findViewById<View>(R.id.rpHistoryCurrent).visibility = if (item.current) View.VISIBLE else View.GONE
                    v.findViewById<TextView>(R.id.rpHistoryPreview).text = item.preview
                    v.findViewById<TextView>(R.id.rpHistoryLength).text =
                        resources.getQuantityString(R.plurals.rp_history_messages, item.messages, item.messages)
                    v.contentDescription = listOfNotNull(
                        item.whenLabel, getString(R.string.rp_history_current).takeIf { item.current }, item.preview
                    ).joinToString(", ")
                    v.setOnClickListener {
                        if (item.current) parentFragmentManager.popBackStack() else pick(bundleOf(OPEN to item.id))
                    }
                }
            }
        }
    }

    companion object {
        const val RESULT = "rp_chat_history"
        const val OPEN = "open"
        const val NEW = "new"
        private const val ARG_CHARACTER = "character"
        private const val LLM = -1L

        /** [characterId] null is the plain-LLM partner. */
        fun newInstance(characterId: Long?) = RpChatHistoryFragment().apply {
            arguments = bundleOf(ARG_CHARACTER to (characterId ?: LLM))
        }
    }
}
