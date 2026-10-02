package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokFadeAnimations
import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.graphics.Color
import android.os.Bundle
import android.util.Log
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
import kotlin.coroutines.cancellation.CancellationException

class SavedChatsFragment : Fragment() {

    companion object {
        private const val ARG_EMBEDDED = "embedded"

        fun newEmbedded(): SavedChatsFragment = SavedChatsFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_EMBEDDED, true) }
        }
    }

    private val isEmbedded: Boolean
        get() = arguments?.getBoolean(ARG_EMBEDDED) == true

    private val viewModel: ChatViewModel by activityViewModels { AppViewModelFactory(requireActivity().application) }
    private val savedChatsViewModel: SavedChatsViewModel by viewModels { AppViewModelFactory(requireActivity().application) }
    private lateinit var savedChatsAdapter: SavedChatsAdapter
    private lateinit var savedChatsList: RecyclerView
    private lateinit var searchView: SearchView
    /** Scroll to the open chat the next time the drawer is shown and the list has rows. */
    private var scrollToOpen = true
    private var scrolledQuery: String? = null
    private lateinit var historyEmptyView: TextView
    private lateinit var historyEmptyContainer: View
    private lateinit var prefs: SharedPreferencesHelper
    private var allSessions: List<ChatSession> = emptyList()
    private var sessionsLiveData: androidx.lifecycle.LiveData<List<ChatSession>>? = null
    private val sessionsObserver = androidx.lifecycle.Observer<List<ChatSession>> { sessions ->
        allSessions = sessions
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
        savedChatsList = recyclerView
        historyEmptyView = view.findViewById(R.id.historyEmptyView)
        historyEmptyContainer = view.findViewById(R.id.historyEmptyContainer)
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

        val host = (parentFragment as? HistoryPanelHost)
            ?: parentFragmentManager.fragments.filterIsInstance<HistoryPanelHost>().firstOrNull()
        val nav = view.findViewById<View>(R.id.historyNav)
        nav.isVisible = host != null
        refreshModeRows()
        mapOf(
            R.id.historyNavCode to HistoryPanelHost.Destination.CODE,
            R.id.historyNavRoleplay to HistoryPanelHost.Destination.ROLEPLAY,
            R.id.historyNavModels to HistoryPanelHost.Destination.MODELS,
            R.id.historyNavPrompts to HistoryPanelHost.Destination.PROMPTS,
            R.id.historyNavPresets to HistoryPanelHost.Destination.PRESETS
        ).forEach { (id, dest) ->
            view.findViewById<View>(id).setOnClickListener { host?.openFromHistory(dest) }
        }

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
            override fun onQueryTextSubmit(query: String?): Boolean {
                // Returning true used to leave the keyboard up over the results.
                searchView.clearFocus()
                return true
            }
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

    /** The search in flight; a newer query, or a list change, replaces it so a slow result can't land late. */
    private var filterJob: Job? = null
    private var filterGeneration = 0

    private fun filterSessions(rawQuery: String) {
        // Fold spaces the same way drafts and the bold span do, so "see  you" still hits.
        val query = HistoryList.normalizeQuery(rawQuery)
        val generation = ++filterGeneration
        filterJob?.cancel()
        filterJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
            val mode = viewModel.chatMode.value ?: ChatMode.ASK
            val prefsDrafts = if (mode == ChatMode.ASK) prefs.getAskComposerDrafts() else emptyMap()
            val host = (parentFragment as? HistoryPanelHost)
                ?: parentFragmentManager.fragments.filterIsInstance<HistoryPanelHost>().firstOrNull()
            // Host preview covers staged Photo/Audio/files; prefs alone miss those.
            val drafts = if (mode == ChatMode.ASK && host != null && query.isNotEmpty()) {
                HistoryList.draftTextsForSearch(allSessions, prefsDrafts) { host.unsentDraftPreview(it) }
            } else {
                prefsDrafts
            }
            val filtered = if (query.isEmpty()) {
                allSessions
            } else {
                HistoryList.withDraftMatches(
                    matched = savedChatsViewModel.searchSessions(query, mode),
                    all = allSessions,
                    drafts = drafts,
                    query = query,
                )
            }
            val ids = filtered.map { it.id }
            val prefixes = savedChatsViewModel.lastMessagePrefixes(ids)
            val photo = getString(R.string.history_preview_photo)
            val you = getString(R.string.history_preview_you)
            val youLabel = { text: String -> you.replace("%1\$s", text) }
            val draftLabel = { text: String -> getString(R.string.history_preview_draft, text) }
            val previews = prefixes.associate { message ->
                message.sessionId to HistoryList.preview(
                    role = message.role,
                    storedPrefix = message.content,
                    youLabel = youLabel,
                    photoLabel = photo,
                )
            }.toMutableMap()
            // A hit in an earlier message replaces the last line, which may not contain the words.
            if (query.isNotEmpty()) {
                for (hit in savedChatsViewModel.searchWindows(ids, query)) {
                    val line = HistoryList.searchLine(hit.role, hit.content, query, youLabel, photo)
                    if (line.isNotEmpty()) previews[hit.sessionId] = line
                }
            }
            if (mode == ChatMode.ASK) {
                for (session in filtered) {
                    val draft = host?.unsentDraftPreview(session.id)
                        ?: drafts[ComposerDrafts.key(session.id)].orEmpty()
                    previews[session.id] = HistoryList.rowPreview(
                        messageLine = previews[session.id].orEmpty(),
                        draft = draft,
                        query = query,
                        draftLabel = draftLabel,
                    )
                }
            }
            val items = HistoryList.present(
                HistoryList.build(
                    sessions = filtered,
                    pinnedIds = prefs.getPinnedSessionIds(),
                    previews = previews,
                    now = System.currentTimeMillis(),
                    labels = HistoryList.Labels(
                        pinned = getString(R.string.grok_history_pinned_title),
                        today = getString(R.string.history_section_today),
                        yesterday = getString(R.string.history_section_yesterday),
                        week = getString(R.string.history_section_week),
                        earlier = getString(R.string.history_section_earlier),
                    ),
                ),
                openId = viewModel.getCurrentSessionId(),
                query = query,
            )
            if (generation != filterGeneration) return@launch
            val queryNow = query
            savedChatsAdapter.submitList(items) {
                if (view == null) return@submitList
                if (queryNow.isNotEmpty()) {
                    if (scrolledQuery != queryNow) {
                        savedChatsList.scrollToPosition(0)
                        scrolledQuery = queryNow
                    }
                } else {
                    scrolledQuery = null
                    scrollOpenRow()
                }
            }

            val empty = filtered.isEmpty()
            historyEmptyContainer.isVisible = empty
            view?.findViewById<RecyclerView>(R.id.savedChatsRecyclerView)?.isVisible = !empty
            historyEmptyView.setText(when {
                query.isNotBlank() -> R.string.grok_history_search_empty_title
                mode == ChatMode.RP -> R.string.rp_history_empty
                else -> R.string.grok_history_empty_title
            })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SavedChats", "History list failed", e)
                if (generation != filterGeneration || !isAdded) return@launch
                GlassNotice.show(requireContext(), getString(R.string.notice_history_load_failed))
            }
        }
    }

    /** The drawer just opened. Bring the chat that is on screen into view. */
    fun onDrawerOpened() {
        scrollToOpen = true
        // A first open is still waiting on the database. Filtering now would cancel that
        // load and publish an empty list over it. Once rows exist, rebuild so a draft
        // parked as the drawer opened is on the row.
        if (allSessions.isNotEmpty() && ::searchView.isInitialized) {
            filterSessions(searchView.query?.toString().orEmpty())
        }
        scrollOpenRow()
    }

    private fun scrollOpenRow() {
        if (!scrollToOpen || !::savedChatsList.isInitialized || !::savedChatsAdapter.isInitialized) return
        val items = savedChatsAdapter.currentList
        if (items.isEmpty()) return
        val index = HistoryList.openAnchor(items)
        if (index >= 0) {
            (savedChatsList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, 0)
                ?: savedChatsList.scrollToPosition(index)
        }
        scrollToOpen = false
    }

    /** Code and Roleplay rows follow Settings > Modes; the host calls this each time it opens. */
    fun refreshModeRows() {
        val v = view ?: return
        val rpOn = SharedPreferencesHelper(requireContext()).isRoleplayEnabled()
        val codeOn = io.github.stardomains3.oxproxion.code.CodeHub.get(requireContext()).store.enabled
        v.findViewById<View>(R.id.historyNavRoleplay)?.isVisible = rpOn
        v.findViewById<View>(R.id.historyNavRoleplayDivider)?.isVisible = rpOn
        v.findViewById<View>(R.id.historyNavCode)?.isVisible = codeOn
        v.findViewById<View>(R.id.historyNavCodeDivider)?.isVisible = codeOn
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
        val discard = sheet.findViewById<View>(R.id.menu_discard_draft)
        val ask = (viewModel.chatMode.value ?: ChatMode.ASK) != ChatMode.RP
        val host = (parentFragment as? HistoryPanelHost)
            ?: parentFragmentManager.fragments.filterIsInstance<HistoryPanelHost>().firstOrNull()
        val unsent = if (ask) {
            host?.hasUnsentDraft(session.id)
                ?: ComposerDrafts.text(prefs.getAskComposerDrafts(), session.id).isNotBlank()
        } else {
            false
        }
        discard.isVisible = unsent
        discard.setOnClickListener {
            dialog.dismiss()
            if (host != null) host.forgetUnsentDraft(session.id)
            else prefs.saveAskComposerDrafts(ComposerDrafts.drop(prefs.getAskComposerDrafts(), session.id))
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
        val host = (parentFragment as? HistoryPanelHost)
            ?: parentFragmentManager.fragments.filterIsInstance<HistoryPanelHost>().firstOrNull()
        GrokConfirmDialog.show(
            fragment = this,
            title = getString(R.string.grok_history_delete_title),
            message = getString(R.string.grok_history_delete_description),
            confirmText = getString(R.string.grok_history_delete),
            onConfirm = {
                prefs.setSessionPinned(session.id, false)
                // Drop a parked photo before the row goes, or the JPEG stays until eviction.
                host?.forgetUnsentDraft(session.id)
                viewModel.notifySessionDeleted(session.id)
                savedChatsViewModel.deleteSession(session.id)
            }
        )
    }
}
