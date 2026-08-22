package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.launch

class RpCharacterLibraryFragment : Fragment() {

    private val chatViewModel: ChatViewModel by activityViewModels()
    private lateinit var adapter: RpCharacterAdapter
    private lateinit var prefs: SharedPreferencesHelper
    private lateinit var emptyView: View

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
        adapter = RpCharacterAdapter(
            onActivate = { character -> activateCharacter(character) },
            onEdit = { character ->
                parentFragmentManager.beginTransaction()
                    .withGrokStackAnimations()
                    .hide(this)
                    .add(R.id.fragment_container, RpCharacterEditFragment.newInstance(character.id))
                    .addToBackStack(null)
                    .commit()
            },
            onDelete = { character ->
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
                            if (prefs.getRpActiveCharacterId() == character.id) {
                                prefs.saveRpActiveCharacterId(null)
                                chatViewModel.refreshActiveRpCharacter()
                                if (prefs.isRpLlmMode()) {
                                    AppToast.makeText(
                                        requireContext(),
                                        getString(R.string.rp_character_deleted_parked),
                                        AppToast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    if (chatViewModel.isRpMode()) {
                                        chatViewModel.startNewChat()
                                    }
                                    AppToast.makeText(
                                        requireContext(),
                                        getString(R.string.rp_character_deleted_active),
                                        AppToast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                )
            }
        )
        val recycler = view.findViewById<RecyclerView>(R.id.rpCharacterRecyclerView)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        view.findViewById<MaterialButton>(R.id.addRpCharacterButton).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, RpCharacterEditFragment.newInstance(0))
                .addToBackStack(null)
                .commit()
        }
        chatViewModel.getRpRepository().allCharacters.observe(viewLifecycleOwner) { chars ->
            val list = chars ?: emptyList()
            adapter.submit(list, prefs.getRpActiveCharacterId())
            emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun activateCharacter(character: RpCharacter) {
        val proceed = {
            chatViewModel.startRpChatWithCharacter(character)
            popToChat()
        }
        if (chatViewModel.rpStartChatNeedsConfirm()) {
            GrokConfirmDialog.show(
                fragment = this,
                title = getString(R.string.rp_new_chat_title),
                message = getString(R.string.rp_new_chat_body, character.name),
                confirmText = getString(R.string.rp_new_chat_confirm),
                onConfirm = proceed,
                destructive = false
            )
        } else {
            proceed()
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
        fm.fragments.filterIsInstance<ChatFragment>().firstOrNull()?.closeHistoryPanel(animated = false)
    }

    private class RpCharacterAdapter(
        private val onActivate: (RpCharacter) -> Unit,
        private val onEdit: (RpCharacter) -> Unit,
        private val onDelete: (RpCharacter) -> Unit,
        private var activeCharacterId: Long? = null
    ) : RecyclerView.Adapter<RpCharacterAdapter.Holder>() {
        private var items: List<RpCharacter> = emptyList()

        fun submit(list: List<RpCharacter>, activeId: Long?) {
            items = list
            activeCharacterId = activeId
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_rp_character, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], activeCharacterId, onActivate, onEdit, onDelete)
        }

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val name = itemView.findViewById<TextView>(R.id.rpCharacterName)
            private val activeBadge = itemView.findViewById<TextView>(R.id.rpCharacterActive)
            private val subtitle = itemView.findViewById<TextView>(R.id.rpCharacterSubtitle)
            private val avatar = itemView.findViewById<ImageView>(R.id.rpCharacterAvatar)
            private val activateButton = itemView.findViewById<MaterialButton>(R.id.rpCharacterActivateButton)
            private val deleteButton = itemView.findViewById<MaterialButton>(R.id.rpCharacterDeleteButton)

            fun bind(
                character: RpCharacter,
                activeId: Long?,
                onActivate: (RpCharacter) -> Unit,
                onEdit: (RpCharacter) -> Unit,
                onDelete: (RpCharacter) -> Unit
            ) {
                name.text = character.name
                activeBadge.visibility =
                    if (activeId != null && activeId == character.id) View.VISIBLE else View.GONE
                subtitle.text = character.personality.ifBlank { character.greeting }.take(120)
                avatar.setImageResource(R.drawable.ic_gradation_mark)
                val avatarFile = RpAvatarStorage.avatarFile(itemView.context, character.id)
                when {
                    !character.photoUri.isNullOrBlank() -> avatar.load(character.photoUri) {
                        crossfade(true)
                        transformations(CircleCropTransformation())
                        memoryCacheKey("rp-lib-${character.id}-${character.updatedAt}")
                        diskCacheKey("rp-lib-${character.id}-${character.updatedAt}")
                    }
                    avatarFile.exists() -> avatar.load(avatarFile) {
                        crossfade(true)
                        transformations(CircleCropTransformation())
                        memoryCacheKey("rp-lib-file-${character.id}-${avatarFile.lastModified()}")
                        diskCacheKey("rp-lib-file-${character.id}-${avatarFile.lastModified()}")
                    }
                }
                // Row tap opens edit (same as lorebooks); Start chat is explicit to avoid accidental wipe.
                itemView.setOnClickListener { onEdit(character) }
                activateButton.setOnClickListener { onActivate(character) }
                deleteButton.setOnClickListener { onDelete(character) }
            }
        }
    }

    companion object {
        const val BACK_STACK_TAG = "rp_character_library"
        fun newInstance() = RpCharacterLibraryFragment()
    }
}
