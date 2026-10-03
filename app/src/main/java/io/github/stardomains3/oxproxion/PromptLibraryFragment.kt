package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import java.util.Collections
import kotlin.collections.remove

class PromptLibraryFragment : Fragment() {

    private lateinit var promptAdapter: PromptAdapter
    private lateinit var sharedPreferencesHelper: SharedPreferencesHelper
    private val prompts = mutableListOf<Prompt>()
    private lateinit var searchView: SearchView
    private val allPrompts = mutableListOf<Prompt>()

    private val exportPromptsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val app = requireContext().applicationContext
                    try {
                        withContext(Dispatchers.IO) {
                            val promptsList = sharedPreferencesHelper.getCustomPrompts()
                            val json = Json.encodeToString(promptsList)
                            val cache = File(app.cacheDir, "prompts-${System.nanoTime()}.json")
                            BackupIo.publish(cache, { app.contentResolver.openOutputStream(uri, "wt") }) { stream ->
                                stream.write(json.toByteArray(Charsets.UTF_8))
                            }
                        }
                        // The picker closes onto this same screen, so silence would read as a no-op.
                        GlassNotice.show(requireContext(), getString(R.string.notice_prompts_exported))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        GlassNotice.show(requireContext(), getString(R.string.notice_export_prompts_failed))
                    }
                }
            }
        }
    }

    private val importPromptsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val app = requireContext().applicationContext
                    try {
                        val jsonString = withContext(Dispatchers.IO) {
                            app.contentResolver.openInputStream(uri)?.use {
                                ImportBounds.readUtf8(it)
                            }
                        }
                        if (jsonString != null) {
                            val importedPrompts = LibraryBackup.prompts(jsonString)
                            val currentPrompts = sharedPreferencesHelper.getCustomPrompts().toMutableList()

                            importedPrompts.forEach { importedPrompt ->
                                val isDuplicate = currentPrompts.any { it.title == importedPrompt.title }
                                if (!isDuplicate) {
                                    currentPrompts.add(importedPrompt)
                                }
                            }
                            sharedPreferencesHelper.saveCustomPrompts(currentPrompts)
                            loadPrompts()
                            GlassNotice.show(requireContext(), getString(R.string.notice_prompts_imported))
                        } else {
                            throw Exception("Failed to read file content.")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ImportBounds.TooLarge) {
                        GlassNotice.show(
                            requireContext(),
                            getString(R.string.import_error_too_large, e.limitBytes / (1024 * 1024))
                        )
                    } catch (e: Exception) {
                        GlassNotice.show(requireContext(), getString(R.string.notice_import_failed_format))
                    }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_prompt_library, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedPreferencesHelper = SharedPreferencesHelper(requireContext())

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }

        val searchItem = toolbar.menu.findItem(R.id.action_search)
        searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.search_prompts_hint)
        searchView.styleLibrarySearch()
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = true
            override fun onQueryTextChange(newText: String?): Boolean {
                filterPrompts(newText ?: "")
                return true
            }
        })

        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_import -> {
                    importPrompts()
                    true
                }
                R.id.action_export -> {
                    exportPrompts()
                    true
                }
                else -> false
            }
        }

        setupRecyclerView(view)
        loadPrompts()

        view.findViewById<MaterialButton>(R.id.fab_add_prompt).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this@PromptLibraryFragment)
                .add(R.id.fragment_container, AddEditPromptFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    private fun exportPrompts() {
        if (sharedPreferencesHelper.getCustomPrompts().isEmpty()) {
            GlassNotice.show(requireContext(), getString(R.string.notice_no_prompts_export))
            return
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_TITLE, "prompts.json")
        }
        exportPromptsLauncher.launch(intent)
    }

    private fun importPrompts() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        importPromptsLauncher.launch(intent)
    }

    private fun setupRecyclerView(view: View) {
        val recyclerView = view.findViewById<RecyclerView>(R.id.prompt_recycler_view)
        promptAdapter = PromptAdapter(
            prompts,
            onItemClick = { prompt ->
                // Send to ChatFragment - set fragment result and clear back stack
                parentFragmentManager.setFragmentResult("prompt_request", Bundle().apply {
                    putString("prompt", prompt.prompt)
                })

                // Clear all fragments and go back to ChatFragment
                clearAllFragmentsAndGoToChat()
            },
            onCopyClick = { prompt ->
                // Copy to clipboard
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Prompt", prompt.prompt)
                clipboard.setPrimaryClip(clip)
                // Android 12 has no system clipboard confirmation, and the screen closes right after.
                GlassNotice.show(requireContext(), getString(R.string.notice_prompt_copied, prompt.title))

                // Disappear fragments back to ChatFragment
                clearAllFragmentsAndGoToChat()
            }
            ,
            onMenuClick = { anchorView, prompt ->
                showPopupMenu(anchorView, prompt)
            }
        )

        recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = promptAdapter
            EmptyState.bind(this, view.findViewById(R.id.promptsEmptyView))

            // Drag-and-drop reordering (all items)
            val callback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    val fromPos = viewHolder.bindingAdapterPosition
                    val toPos = target.bindingAdapterPosition
                    Collections.swap(prompts, fromPos, toPos)
                    promptAdapter.notifyItemMoved(fromPos, toPos)
                    sharedPreferencesHelper.saveCustomPrompts(prompts)
                    return true
                }
                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
                override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                    ItemTouchHelper.Callback.makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            }
            ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
        }
    }

    private fun clearAllFragmentsAndGoToChat() {
        // Pop back to ChatFragment in one go
        parentFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)

        // Show ChatFragment if it was hidden
        val chatFragment = parentFragmentManager.findFragmentByTag("ChatFragment")
            ?: parentFragmentManager.fragments.find { it is ChatFragment }

        chatFragment?.let {
            parentFragmentManager.beginTransaction()
                .show(it)
                .commit()
        }
    }

    private fun showPopupMenu(anchorView: View, prompt: Prompt) {
        val inflater = LayoutInflater.from(anchorView.context)
        val menuView = inflater.inflate(R.layout.menu_popup_layout, null)
        val popupWindow = PopupWindow(menuView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popupWindow.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        popupWindow.isOutsideTouchable = true

        val editItem = menuView.findViewById<TextView>(R.id.menu_edit)
        val deleteItem = menuView.findViewById<TextView>(R.id.menu_delete)

        editItem.setOnClickListener {
            popupWindow.dismiss()
            navigateToEditScreen(prompt)
        }

        deleteItem.setOnClickListener {
            popupWindow.dismiss()
            showDeleteConfirmationDialog(prompt)
        }

        // Smart positioning (same as system)
        menuView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupHeight = menuView.measuredHeight
        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        val anchorY = location[1]
        val anchorHeight = anchorView.height
        val windowMetrics = requireActivity().windowManager.currentWindowMetrics
        val screenHeight = windowMetrics.bounds.height()
        val spaceBelow = screenHeight - anchorY - anchorHeight
        val spaceAbove = anchorY
        val showAbove = spaceBelow < popupHeight && spaceAbove >= popupHeight

        if (showAbove) {
            popupWindow.showAsDropDown(anchorView, 0, -anchorHeight - popupHeight)
        } else {
            popupWindow.showAsDropDown(anchorView)
        }
        MenuDim.behind(popupWindow)
    }

    private fun navigateToEditScreen(prompt: Prompt) {
        val fragment = AddEditPromptFragment().apply {
            arguments = Bundle().apply {
                putString("ARG_TITLE", prompt.title)
                putString("ARG_PROMPT", prompt.prompt)
            }
        }
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .hide(this@PromptLibraryFragment)
            .add(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun loadPrompts() {
        prompts.clear()
        allPrompts.clear()
        val customPrompts = sharedPreferencesHelper.getCustomPrompts()

        customPrompts.forEach { it.isExpanded = false }

        prompts.addAll(customPrompts)
        allPrompts.addAll(customPrompts)

        if (::searchView.isInitialized) {
            filterPrompts(searchView.query.toString())
        } else {
            promptAdapter.notifyDataSetChanged()
        }
    }

    private fun filterPrompts(query: String) {
        if (allPrompts.isNotEmpty()) {
            val filtered = if (query.isEmpty()) allPrompts else allPrompts.filter {
                it.title.contains(query, ignoreCase = true) || it.prompt.contains(query, ignoreCase = true)
            }
            prompts.clear()
            prompts.addAll(filtered)
            prompts.forEach { it.isExpanded = false }
        }
        promptAdapter.notifyDataSetChanged()
    }

    private fun showDeleteConfirmationDialog(prompt: Prompt) {
        GrokConfirmDialog.show(
            fragment = this,
            title = getString(R.string.delete_prompt_title),
            message = getString(R.string.delete_prompt_body),
            confirmText = getString(R.string.delete_message_confirm),
            onConfirm = { deletePrompt(prompt) }
        )
    }

    private fun deletePrompt(prompt: Prompt) {
        val customPrompts = sharedPreferencesHelper.getCustomPrompts().toMutableList()
        if (customPrompts.remove(prompt)) {
            sharedPreferencesHelper.saveCustomPrompts(customPrompts)
            loadPrompts()
        }
    }

    override fun onResume() {
        super.onResume()
        loadPrompts()
    }

    /** Back from the editor: this screen was only hidden under it, so it never left onResume. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) loadPrompts()
    }
}
