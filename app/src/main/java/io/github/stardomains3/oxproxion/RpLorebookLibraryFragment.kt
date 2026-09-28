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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class RpLorebookLibraryFragment : Fragment() {

    private val chatViewModel: ChatViewModel by activityViewModels { AppViewModelFactory(requireActivity().application) }
    private lateinit var emptyView: View
    private val importer = RpImportFlow(this) { chatViewModel }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_rp_lorebook_library, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        emptyView = view.findViewById(R.id.rpLorebookEmpty)
        emptyView.findViewById<android.widget.ImageView>(R.id.rpEmptyIcon).setImageResource(R.drawable.rp_ic_book)
        emptyView.findViewById<TextView>(R.id.rpEmptyTitle).setText(R.string.rp_lorebooks_empty_title)
        emptyView.findViewById<TextView>(R.id.rpEmptyBody).setText(R.string.rp_lorebooks_empty_body)
        emptyView.findViewById<View>(R.id.rpEmptyCreate).setOnClickListener { openEditor(0) }
        emptyView.findViewById<View>(R.id.rpEmptyImport).setOnClickListener { importer.pickLorebooks() }
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.getRpRepository().retireLoreSwitch(SharedPreferencesHelper(requireContext()))
        }
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        val adapter = LoreAdapter(
            onOpen = { book -> openEditor(book.id) },
            onActivate = { book ->
                viewLifecycleOwner.lifecycleScope.launch {
                    chatViewModel.getRpRepository().setActiveLorebook(book.id)
                    if (!isAdded) return@launch
                    GlassNotice.show(requireContext(), getString(R.string.rp_lore_activated, book.name))
                }
            },
            onDelete = { book ->
                GrokConfirmDialog.show(
                    fragment = this,
                    title = getString(R.string.rp_delete_lorebook_title),
                    message = getString(
                        if (book.isActive) R.string.rp_delete_lorebook_body_active else R.string.rp_delete_lorebook_body,
                        book.name
                    ),
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
        val addButton = view.findViewById<MaterialButton>(R.id.addRpLorebookButton)
        chatViewModel.getRpRepository().allLorebooks.observe(viewLifecycleOwner) { books ->
            val list = books.orEmpty()
            adapter.submit(list)
            emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            // The empty state carries its own Create button.
            addButton.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<MaterialButton>(R.id.addRpLorebookButton).setOnClickListener { openEditor(0) }
    }

    private fun openEditor(id: Long) {
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .hide(this)
            .add(R.id.fragment_container, RpLorebookEditFragment.newInstance(id))
            .addToBackStack(null)
            .commit()
    }

    private class LoreAdapter(
        private val onOpen: (RpLorebook) -> Unit,
        private val onActivate: (RpLorebook) -> Unit,
        private val onDelete: (RpLorebook) -> Unit
    ) : ListAdapter<RpLorebook, LoreAdapter.Holder>(Diff) {
        init {
            setHasStableIds(true)
        }

        fun submit(list: List<RpLorebook>) = submitList(list)

        override fun getItemId(position: Int): Long = getItem(position).id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_rp_lorebook, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(getItem(position), onOpen, onActivate, onDelete)
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
                itemView.findViewById<View>(R.id.rpLorebookRow).setOnClickListener { onOpen(book) }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<RpLorebook>() {
        override fun areItemsTheSame(a: RpLorebook, b: RpLorebook) = a.id == b.id
        override fun areContentsTheSame(a: RpLorebook, b: RpLorebook) = a == b
    }

    companion object {
        fun newInstance() = RpLorebookLibraryFragment()
    }
}
