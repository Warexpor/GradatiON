package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class RpCharacterLibraryFragment : Fragment() {

    private val chatViewModel: ChatViewModel by activityViewModels { AppViewModelFactory(requireActivity().application) }
    private lateinit var adapter: RpCharacterAdapter
    private lateinit var prefs: SharedPreferencesHelper
    private lateinit var emptyView: View
    private var cardMenu: MessageMenu? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_rp_character_library, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = SharedPreferencesHelper(requireContext())
        emptyView = view.findViewById(R.id.rpCharacterEmpty)
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        val openEditor: (RpCharacter) -> Unit = { character ->
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, RpCharacterEditFragment.newInstance(character.id))
                .addToBackStack(null)
                .commit()
        }
        val confirmDelete: (RpCharacter) -> Unit = { character ->
            GrokConfirmDialog.show(
                fragment = this,
                title = getString(R.string.rp_delete_character_title),
                message = getString(R.string.rp_delete_character_body, character.name),
                confirmText = getString(R.string.grok_history_delete),
                onConfirm = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (character.exportKey.isNotBlank()) {
                            prefs.rememberDeletedRpCharacter(character.exportKey, character.id)
                        }
                        chatViewModel.rememberDeletedCharacterForRematch(character.id)
                        chatViewModel.getRpRepository().deleteCharacter(character.id)
                        if (!isAdded) return@launch
                        RpAvatarStorage.deleteAvatar(requireContext(), character.id)
                        prefs.clearRpCharacterPrefs(character.id)
                        if (prefs.getRpActiveCharacterId() == character.id) {
                            prefs.saveRpActiveCharacterId(null)
                            chatViewModel.refreshActiveRpCharacter()
                            if (prefs.isRpLlmMode()) {
                                GlassNotice.show(requireContext(), getString(R.string.rp_character_deleted_parked))
                            } else {
                                if (chatViewModel.isRpMode()) {
                                    chatViewModel.startNewChat()
                                }
                                GlassNotice.show(requireContext(), getString(R.string.rp_character_deleted_active))
                            }
                        }
                    }
                }
            )
        }
        adapter = RpCharacterAdapter(
            onActivate = { character -> activateCharacter(character) },
            onMenu = { anchor, character -> showCardMenu(anchor, character, openEditor, confirmDelete) },
            userName = { prefs.activeRpPersonaName().ifBlank { getString(R.string.rp_you) } }
        )
        val recycler = view.findViewById<RecyclerView>(R.id.rpCharacterRecyclerView)
        recycler.layoutManager = GridLayoutManager(requireContext(), 2)
        recycler.itemAnimator = null
        recycler.adapter = adapter
        EmptyState.bind(recycler, emptyView)
        val hint = view.findViewById<View>(R.id.rpCharacterHint)
        view.findViewById<MaterialButton>(R.id.addRpCharacterButton).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, RpCharacterEditFragment.newInstance(0))
                .addToBackStack(null)
                .commit()
        }
        chatViewModel.getRpRepository().allCharacters.observe(viewLifecycleOwner) { chars ->
            val list = chars.orEmpty()
            adapter.submit(list, prefs.getRpActiveCharacterId())
            hint.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun activateCharacter(character: RpCharacter) {
        val start = { carry: Boolean ->
            chatViewModel.startRpChatWithCharacter(character, carry)
            popToChat()
        }
        if (chatViewModel.currentRpFacts().isNotBlank()) {
            GrokConfirmDialog.show(
                fragment = this,
                title = getString(R.string.rp_facts_choice_title),
                message = getString(R.string.rp_facts_choice_body),
                confirmText = getString(R.string.rp_facts_carry),
                onConfirm = { start(true) },
                destructive = false,
                cancelText = getString(R.string.rp_facts_fresh),
                onCancel = { start(false) }
            )
        } else if (chatViewModel.rpStartChatNeedsConfirm()) {
            GrokConfirmDialog.show(
                fragment = this,
                title = getString(R.string.rp_new_chat_title),
                message = getString(R.string.rp_new_chat_body, character.name),
                confirmText = getString(R.string.rp_new_chat_confirm),
                onConfirm = { start(false) },
                destructive = false
            )
        } else {
            start(false)
        }
    }

    private fun popToChat() {
        val fm = parentFragmentManager
        // Hub may be opened from chat (long-press chip) or from Settings → Advanced.
        fm.popBackStackImmediate(RpHubFragment.BACK_STACK_TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        // Model-chip opens the library directly (no hub).
        fm.popBackStackImmediate(BACK_STACK_TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        // If Hub sat on Settings, also clear the settings stack so Start chat lands on chat.
        fm.popBackStackImmediate("settings", FragmentManager.POP_BACK_STACK_INCLUSIVE)
        // Leave Code if it was covering chat — Start chat must land on Roleplay, not Code.
        fm.fragments.filterIsInstance<ChatFragment>().firstOrNull()?.uncoverFromHub()
    }

    private fun showCardMenu(anchor: View, character: RpCharacter, onEdit: (RpCharacter) -> Unit, onDelete: (RpCharacter) -> Unit) {
        val root = view as? FrameLayout ?: return
        cardMenu?.dismiss(animated = false)
        // Same glass menu as the chats list, not the platform popup (an opaque system-themed card).
        val items = listOf(
            MessageMenu.Item(getString(R.string.rp_menu_start_chat), R.drawable.ic_new_chat) { activateCharacter(character) },
            MessageMenu.Item(getString(R.string.rp_menu_edit), R.drawable.ic_msg_edit) { onEdit(character) },
            MessageMenu.Item(getString(R.string.rp_menu_delete), R.drawable.ic_msg_delete, destructive = true) { onDelete(character) }
        )
        cardMenu = MessageMenu(root, anchor, root.findViewById(R.id.rpLibraryBackdrop)).also { m ->
            m.onDismiss = { if (cardMenu === m) cardMenu = null }
            m.show(items, viewLifecycleOwner)
        }
    }

    override fun onDestroyView() {
        cardMenu?.dismiss(animated = false)
        cardMenu = null
        super.onDestroyView()
    }

    private class RpCharacterAdapter(
        private val onActivate: (RpCharacter) -> Unit,
        private val onMenu: (View, RpCharacter) -> Unit,
        private val userName: () -> String,
        private var activeCharacterId: Long? = null
    ) : RecyclerView.Adapter<RpCharacterAdapter.Holder>() {
        private var items: List<RpCharacter> = emptyList()

        init {
            setHasStableIds(true)
        }

        fun submit(list: List<RpCharacter>, activeId: Long?) {
            items = list
            activeCharacterId = activeId
            notifyDataSetChanged()
        }

        override fun getItemId(position: Int): Long = items[position].id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_rp_character, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], activeCharacterId, onActivate, onMenu, userName())
        }

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val card = itemView.findViewById<View>(R.id.rpCharacterCard)
            private val name = itemView.findViewById<TextView>(R.id.rpCharacterName)
            private val activeBadge = itemView.findViewById<TextView>(R.id.rpCharacterActive)
            private val subtitle = itemView.findViewById<TextView>(R.id.rpCharacterSubtitle)
            private val avatar = itemView.findViewById<ImageView>(R.id.rpCharacterAvatar)
            private val monogram = itemView.findViewById<TextView>(R.id.rpCharacterMonogram)
            private val more = itemView.findViewById<ImageButton>(R.id.rpCharacterMore)

            fun bind(
                character: RpCharacter,
                activeId: Long?,
                onActivate: (RpCharacter) -> Unit,
                onMenu: (View, RpCharacter) -> Unit,
                you: String
            ) {
                val ctx = itemView.context
                val isActive = activeId != null && activeId == character.id
                name.text = character.name
                activeBadge.visibility = if (isActive) View.VISIBLE else View.GONE
                card.setBackgroundResource(if (isActive) R.drawable.rp_bg_card_active else R.drawable.rp_bg_card)
                subtitle.text = RpChatSummaries.tagline(character, you).ifBlank { ctx.getString(R.string.rp_ui_no_description) }
                RpAvatars.bind(avatar, monogram, character)
                card.contentDescription = ctx.getString(R.string.rp_ui_character_card_a11y, character.name)
                more.contentDescription = ctx.getString(R.string.rp_ui_more_options, character.name)
                // Tap = chat (confirm dialog guards an existing thread); options live behind hold / overflow.
                card.setOnClickListener { onActivate(character) }
                card.setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onMenu(more, character)
                    true
                }
                more.setOnClickListener { onMenu(it, character) }
            }

        }
    }

    companion object {
        const val BACK_STACK_TAG = "rp_character_library"
        fun newInstance() = RpCharacterLibraryFragment()
    }
}
