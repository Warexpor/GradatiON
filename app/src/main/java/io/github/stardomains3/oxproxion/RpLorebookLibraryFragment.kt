package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class RpLorebookLibraryFragment : Fragment() {

    private val chatViewModel: ChatViewModel by activityViewModels { AppViewModelFactory(requireActivity().application) }
    private lateinit var emptyView: View

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_rp_lorebook_library, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        emptyView = view.findViewById(R.id.rpLorebookEmpty)
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        val adapter = LoreAdapter(
            onOpen = { book ->
                parentFragmentManager.beginTransaction()
                    .withGrokStackAnimations()
                    .hide(this)
                    .add(R.id.fragment_container, RpLorebookEditFragment.newInstance(book.id))
                    .addToBackStack(null)
                    .commit()
            },
            onActivate = { book ->
                viewLifecycleOwner.lifecycleScope.launch {
                    chatViewModel.getRpRepository().setActiveLorebook(book.id)
                    if (!isAdded) return@launch
                    val prefs = SharedPreferencesHelper(requireContext())
                    val msg = if (prefs.isRpLoreEnabled()) {
                        getString(R.string.rp_lore_activated)
                    } else {
                        getString(R.string.rp_lore_activated_disabled)
                    }
                    GlassNotice.show(requireContext(), msg)
                }
            },
            onDelete = { book ->
                GrokConfirmDialog.show(
                    fragment = this,
                    title = getString(R.string.rp_delete_lorebook_title),
                    message = getString(R.string.rp_delete_lorebook_body, book.name),
                    confirmText = getString(R.string.grok_history_delete),
                    onConfirm = {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val wasActive = book.isActive
                            chatViewModel.getRpRepository().deleteLorebook(book.id)
                            if (!isAdded) return@launch
                            if (wasActive) {
                                GlassNotice.show(requireContext(), getString(R.string.rp_lore_deleted_active))
                            }
                        }
                    }
                )
            }
        )
        val recycler = view.findViewById<RecyclerView>(R.id.rpLorebookRecyclerView)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        chatViewModel.getRpRepository().allLorebooks.observe(viewLifecycleOwner) { books ->
            val list = books.orEmpty()
            adapter.submit(list)
            emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<MaterialButton>(R.id.addRpLorebookButton).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, RpLorebookEditFragment.newInstance(0))
                .addToBackStack(null)
                .commit()
        }
    }

    private class LoreAdapter(
        private val onOpen: (RpLorebook) -> Unit,
        private val onActivate: (RpLorebook) -> Unit,
        private val onDelete: (RpLorebook) -> Unit
    ) : RecyclerView.Adapter<LoreAdapter.Holder>() {
        private var items: List<RpLorebook> = emptyList()

        fun submit(list: List<RpLorebook>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_rp_lorebook, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], onOpen, onActivate, onDelete)
        }

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val name = itemView.findViewById<TextView>(R.id.rpLorebookName)
            private val activeBadge = itemView.findViewById<TextView>(R.id.rpLorebookActive)
            private val activateButton = itemView.findViewById<MaterialButton>(R.id.rpLorebookActivateButton)
            private val deleteButton = itemView.findViewById<MaterialButton>(R.id.rpLorebookDeleteButton)

            fun bind(
                book: RpLorebook,
                onOpen: (RpLorebook) -> Unit,
                onActivate: (RpLorebook) -> Unit,
                onDelete: (RpLorebook) -> Unit
            ) {
                name.text = book.name
                activeBadge.visibility = if (book.isActive) View.VISIBLE else View.GONE
                activateButton.visibility = if (book.isActive) View.GONE else View.VISIBLE
                activateButton.setOnClickListener { onActivate(book) }
                deleteButton.setOnClickListener { onDelete(book) }
                itemView.setOnClickListener { onOpen(book) }
            }
        }
    }

    companion object {
        fun newInstance() = RpLorebookLibraryFragment()
    }
}
