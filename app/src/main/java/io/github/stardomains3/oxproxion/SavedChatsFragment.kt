package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokFadeAnimations
import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SavedChatsFragment : Fragment() {

    companion object {
        private const val ARG_EMBEDDED = "embedded"

        fun newEmbedded(): SavedChatsFragment = SavedChatsFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_EMBEDDED, true) }
        }
    }

    private val isEmbedded: Boolean
        get() = arguments?.getBoolean(ARG_EMBEDDED) == true

    private val viewModel: ChatViewModel by activityViewModels()
    private val savedChatsViewModel: SavedChatsViewModel by viewModels()
    private lateinit var savedChatsAdapter: SavedChatsAdapter
    private lateinit var searchView: SearchView
    private lateinit var historyEmptyView: TextView
    private lateinit var historyEmptyContainer: View
    private lateinit var historyModeLabel: TextView
    private lateinit var prefs: SharedPreferencesHelper
    private var allSessions: List<ChatSession> = emptyList()
    private var sessionsLiveData: androidx.lifecycle.LiveData<List<ChatSession>>? = null
    private val sessionsObserver = androidx.lifecycle.Observer<List<ChatSession>> { sessions ->
        allSessions = sessions ?: emptyList()
        filterSessions(searchView.query?.toString().orEmpty())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_saved_chats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = SharedPreferencesHelper(requireContext())

        val recyclerView = view.findViewById<RecyclerView>(R.id.savedChatsRecyclerView)
        historyEmptyView = view.findViewById(R.id.historyEmptyView)
        historyEmptyContainer = view.findViewById(R.id.historyEmptyContainer)
        historyModeLabel = view.findViewById(R.id.historyModeLabel)
        searchView = view.findViewById(R.id.historySearchView)

        val closeButton = view.findViewById<ImageButton>(R.id.historyCloseButton)
        val settingsButton = view.findViewById<ImageButton>(R.id.historySettingsButton)

        // Embedded: collapse drawer. Full-screen: pop back.
        closeButton.setOnClickListener {
            if (isEmbedded) {
                (parentFragment as? HistoryPanelHost)?.closeHistoryPanel()
            } else {
                parentFragmentManager.popBackStack()
            }
        }

        settingsButton.setOnClickListener { openSettings() }

        view.findViewById<ImageButton>(R.id.historyNewChatButton).setOnClickListener {
            if (isEmbedded) {
                (parentFragment as? HistoryPanelHost)?.startNewChatFromHistory()
            } else {
                if (viewModel.isRpMode()) {
                    viewModel.startFreshChatForCurrentMode()
                } else {
                    viewModel.startNewChat()
                }
                parentFragmentManager.popBackStack()
            }
        }

        searchView.queryHint = getString(R.string.grok_history_search)
        searchView.findViewById<View>(androidx.appcompat.R.id.search_plate)?.setBackgroundColor(Color.TRANSPARENT)
        searchView.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)?.apply {
            textSize = 16f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.xai_ink))
            setHintTextColor(ContextCompat.getColor(requireContext(), R.color.xai_mute))
        }
        searchView.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)
            ?.setColorFilter(ContextCompat.getColor(requireContext(), R.color.xai_mute))
        var searchJob: Job? = null
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = true
            override fun onQueryTextChange(newText: String?): Boolean {
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    filterSessions(newText ?: "")
                }
                return true
            }
        })

        savedChatsAdapter = SavedChatsAdapter(
            onClick = { session ->
                viewModel.loadChat(session.id)
                if (isEmbedded) {
                    (parentFragment as? HistoryPanelHost)?.closeHistoryPanel()
                } else {
                    parentFragmentManager.popBackStack()
                }
            },
            onOverflowClick = { session, _ -> showOptionsSheet(session) }
        )

        recyclerView.adapter = savedChatsAdapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // R→L swipe closes history (Grok drawer dismiss)
        val closeDetector = (parentFragment as? ChatFragment)?.historyCloseSwipeDetector
        if (closeDetector != null) {
            view.setOnTouchListener { _, event ->
                closeDetector.onTouchEvent(event)
                false
            }
            recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    closeDetector.onTouchEvent(e)
                    return false
                }
            })
        }

        viewModel.chatMode.observe(viewLifecycleOwner) { mode ->
            historyModeLabel.text = getString(
                if (mode == ChatMode.RP) R.string.history_mode_rp else R.string.history_mode_ask
            )
            sessionsLiveData?.removeObserver(sessionsObserver)
            sessionsLiveData = savedChatsViewModel.sessionsForMode(mode)
            sessionsLiveData?.observe(viewLifecycleOwner, sessionsObserver)
        }
    }

    private fun openSettings() {
        if (isEmbedded) {
            (parentFragment as? HistoryPanelHost)?.openSettingsFromHistory()
        } else {
            parentFragmentManager.beginTransaction()
                .withGrokFadeAnimations()
                .hide(this)
                .add(R.id.fragment_container, SettingsFragment())
                .addToBackStack("settings")
                .commit()
        }
    }

    private fun filterSessions(query: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val mode = viewModel.chatMode.value ?: ChatMode.ASK
            val filtered = if (query.isEmpty()) {
                allSessions
            } else {
                savedChatsViewModel.searchSessions(query, mode)
            }
            val pinnedIds = prefs.getPinnedSessionIds()
            val pinned = filtered.filter { it.id in pinnedIds }
                .sortedByDescending { it.timestamp }
            val rest = filtered.filter { it.id !in pinnedIds }
                .sortedByDescending { it.timestamp }

            val items = buildList {
                if (pinned.isNotEmpty()) {
                    add(HistoryListItem.Header(getString(R.string.grok_history_pinned_title)))
                    pinned.forEach { add(HistoryListItem.Session(it, pinned = true)) }
                }
                if (rest.isNotEmpty()) {
                    // Grok parity: single "Conversations" section (not day buckets)
                    add(HistoryListItem.Header(getString(R.string.grok_history_conversations_title)))
                    rest.forEach { add(HistoryListItem.Session(it, pinned = false)) }
                }
            }
            savedChatsAdapter.submitList(items)

            val empty = filtered.isEmpty()
            historyEmptyContainer.isVisible = empty
            view?.findViewById<RecyclerView>(R.id.savedChatsRecyclerView)?.isVisible = !empty
            historyEmptyView.text = if (query.isBlank()) {
                if (mode == ChatMode.RP) {
                    "${getString(R.string.rp_history_empty)}\n\n${getString(R.string.rp_history_empty_text)}"
                } else {
                    "${getString(R.string.grok_history_empty_title)}\n\n${getString(R.string.grok_history_empty_text)}"
                }
            } else {
                "${getString(R.string.grok_history_search_empty_title)}\n\n${getString(R.string.grok_history_search_empty_text)}"
            }
        }
    }

    private fun showOptionsSheet(session: ChatSession) {
        val dialog = BottomSheetDialog(requireContext(), R.style.ThemeOverlay_Grokion_BottomSheet)
        val sheet = layoutInflater.inflate(R.layout.bottom_sheet_history_item, null)
        dialog.setContentView(sheet)

        val pinned = prefs.isSessionPinned(session.id)
        val pinButton = sheet.findViewById<MaterialButton>(R.id.menu_pin)
        pinButton.text = getString(if (pinned) R.string.grok_history_unpin else R.string.grok_history_pin)

        sheet.findViewById<View>(R.id.menu_delete).setOnClickListener {
            dialog.dismiss()
            showDeleteConfirmationDialog(session)
        }
        sheet.findViewById<View>(R.id.menu_edit).setOnClickListener {
            dialog.dismiss()
            showRenameDialog(session)
        }
        pinButton.setOnClickListener {
            dialog.dismiss()
            prefs.setSessionPinned(session.id, !pinned)
            filterSessions(searchView.query?.toString().orEmpty())
        }

        dialog.show()
        GlassChrome.glassDialog(dialog)
    }

    private fun showRenameDialog(session: ChatSession) {
        GrokInputDialog.show(
            fragment = this,
            title = getString(R.string.grok_history_rename),
            hint = getString(R.string.grok_history_rename),
            initialText = session.title,
            confirmText = getString(R.string.grok_history_rename),
            onConfirm = { newTitle ->
                if (newTitle.isNotBlank()) {
                    savedChatsViewModel.updateSessionTitle(session.id, newTitle)
                }
            }
        )
    }

    private fun showDeleteConfirmationDialog(session: ChatSession) {
        GrokConfirmDialog.show(
            fragment = this,
            title = getString(R.string.grok_history_delete_title),
            message = getString(R.string.grok_history_delete_description),
            confirmText = getString(R.string.grok_history_delete),
            onConfirm = {
                prefs.setSessionPinned(session.id, false)
                viewModel.notifySessionDeleted(session.id)
                savedChatsViewModel.deleteSession(session.id)
            }
        )
    }
}
