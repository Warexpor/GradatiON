package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.app.Activity
import android.content.Intent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import java.util.Collections

class SystemMessageLibraryFragment : Fragment() {

    private lateinit var systemMessageAdapter: SystemMessageAdapter
    private lateinit var sharedPreferencesHelper: SharedPreferencesHelper
    private var picked = false
    private val systemMessages = mutableListOf<SystemMessage>()
    private lateinit var searchView: SearchView  // NEW: Reference to SearchView
    private val allSystemMessages = mutableListOf<SystemMessage>()  // NEW: Store full list for filtering

    private val exportSystemMessagesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val app = requireContext().applicationContext
                    try {
                        withContext(Dispatchers.IO) {
                            val customMessages = sharedPreferencesHelper.getCustomSystemMessages()
                            val defaultMessage = sharedPreferencesHelper.getDefaultSystemMessage()
                            val allMessages = mutableListOf<SystemMessage>().apply {
                                add(SystemMessage(defaultMessage.title, defaultMessage.prompt))
                                addAll(customMessages)
                            }
                            val json = Json.encodeToString(allMessages)
                            val cache = File(app.cacheDir, "system-messages-${System.nanoTime()}.json")
                            BackupIo.publish(cache, { app.contentResolver.openOutputStream(uri, "wt") }) { stream ->
                                stream.write(json.toByteArray(Charsets.UTF_8))
                            }
                        }
                        GlassNotice.show(requireContext(), getString(R.string.notice_system_messages_exported))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        GlassNotice.show(requireContext(), getString(R.string.notice_export_system_messages_failed))
                    }
                }
            }
        }
    }


    private val importSystemMessagesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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
                            val importedMessages = LibraryBackup.systemMessages(jsonString)
                            val currentMessages = sharedPreferencesHelper.getCustomSystemMessages().toMutableList()
                            // Fetch the single default message for duplicate checking
                            val defaultMessage = sharedPreferencesHelper.getDefaultSystemMessage()

                            importedMessages.forEach { importedMessage ->
                                // Check for duplicates against current custom messages AND the single default message
                                val isDuplicateInCustoms = currentMessages.any { it.title == importedMessage.title }
                                val isDuplicateInDefault = (importedMessage.title == defaultMessage.title)

                                if (!isDuplicateInCustoms && !isDuplicateInDefault) {
                                    currentMessages.add(importedMessage)
                                }
                                // Optional: Log skipped duplicates
                                // else { Log.d("Import", "Skipped duplicate: ${importedMessage.title}") }
                            }
                            sharedPreferencesHelper.saveCustomSystemMessages(currentMessages)
                            loadSystemMessages()
                            GlassNotice.show(requireContext(), getString(R.string.notice_system_messages_imported))
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
                    } catch (e: SerializationException) {
                        // Log.e("Import", "Import failed due to JSON format", e)
                        GlassNotice.show(requireContext(), getString(R.string.notice_import_failed_format))
                    } catch (e: Exception) {
                        //Log.e("Import", "Import failed", e)
                        GlassNotice.show(requireContext(), getString(R.string.notice_import_failed))
                    }
                }
            }
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_system_message_library, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        picked = false
        sharedPreferencesHelper = SharedPreferencesHelper(requireContext())

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        val searchItem = toolbar.menu.findItem(R.id.action_search)
        if (searchItem != null) {
            searchView = searchItem.actionView as SearchView
            searchView.queryHint = getString(R.string.search_system_messages_hint)
        searchView.styleLibrarySearch()
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    filterSystemMessages(newText ?: "")  // NEW: Filter on every keystroke (no debounce)
                    return true
                }
            })
        } else {
            GlassNotice.show(requireContext(), getString(R.string.notice_search_unavailable))
        }
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_import -> {
                    importSystemMessages()
                    true
                }
                R.id.action_export -> {
                    exportSystemMessages()
                    true
                }
                else -> false
            }
        }

        setupRecyclerView(view)
        loadSystemMessages()

        view.findViewById<MaterialButton>(R.id.fab_add_system_message).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this@SystemMessageLibraryFragment)
                .add(R.id.fragment_container, AddEditSystemMessageFragment())
                .addToBackStack(null)
                .commit()
        }
    }


    private fun exportSystemMessages() {
        if (sharedPreferencesHelper.getCustomSystemMessages().isEmpty()) {
            GlassNotice.show(requireContext(), getString(R.string.notice_no_system_messages_export))
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, getString(R.string.system_messages_export_filename))
        }
        exportSystemMessagesLauncher.launch(intent)
    }

    private fun importSystemMessages() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        importSystemMessagesLauncher.launch(intent)
    }

    private fun setupRecyclerView(view: View) {
        val selectedMessage = sharedPreferencesHelper.getSelectedSystemMessage()
        systemMessageAdapter = SystemMessageAdapter(systemMessages, selectedMessage,
            onItemClick = { systemMessage ->
                // One pick per visit: a second tap (or Back) inside the beat must not pop twice.
                if (!picked) {
                    picked = true
                    sharedPreferencesHelper.saveSelectedSystemMessage(systemMessage)
                    // Brief beat so the new check registers; cancelled if the view goes first.
                    viewLifecycleOwner.lifecycleScope.launch {
                        delay(200)
                        if (!isStateSaved) parentFragmentManager.popBackStack() else picked = false
                    }
                }
            },
            onMenuClick = { anchorView, systemMessage ->
                showPopupMenu(anchorView, systemMessage)
            }
        )
        val recyclerView = view.findViewById<RecyclerView>(R.id.system_message_recycler_view)
        recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = systemMessageAdapter
            EmptyState.bind(this, view.findViewById(R.id.systemMessagesEmptyView))

            // Drag-and-drop reordering (customs only; default fixed at top).
            // A search shows a subset: saving drop(1) of that used to delete the first hit and every
            // non-matching custom. Drag is off while searching.
            val callback = object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,  // Vertical drag only
                0  // No swipe
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    if (!libraryDragAllowed(if (::searchView.isInitialized) searchView.query else null)) return false
                    val fromPos = viewHolder.bindingAdapterPosition
                    val toPos = target.bindingAdapterPosition
                    // Only allow reordering if both are custom (index > 0 and not Default)
                    if (fromPos > 0 && toPos > 0) {
                        // Refuse before swapping when the visible list is not the full library.
                        if (customSystemMessagesAfterReorder(systemMessages) == null) return false
                        Collections.swap(systemMessages, fromPos, toPos)
                        Collections.swap(allSystemMessages, fromPos, toPos)
                        systemMessageAdapter.notifyItemMoved(fromPos, toPos)
                        sharedPreferencesHelper.saveCustomSystemMessages(
                            customSystemMessagesAfterReorder(systemMessages) ?: return false
                        )
                        return true
                    }
                    return false
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    // No swipe
                }

                override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                    if (!libraryDragAllowed(if (::searchView.isInitialized) searchView.query else null)) return 0
                    val pos = viewHolder.bindingAdapterPosition
                    val row = systemMessages.getOrNull(pos)
                    // No drag on Default, and not while a search has reshuffled positions
                    return if (pos > 0 && row != null && !row.isDefault) {
                        makeMovementFlags(
                            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                            0
                        )
                    } else {
                        0
                    }
                }
            }
            val itemTouchHelper = ItemTouchHelper(callback)
            itemTouchHelper.attachToRecyclerView(recyclerView)
        }
    }

    private fun showPopupMenu(anchorView: View, systemMessage: SystemMessage) {
        val inflater = LayoutInflater.from(anchorView.context)
        val menuView = inflater.inflate(R.layout.menu_popup_layout, null)

        val popupWindow = PopupWindow(
            menuView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        // Setup background - Important for dismissing when touching outside
        popupWindow.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        popupWindow.isOutsideTouchable = true
        val editItem = menuView.findViewById<TextView>(R.id.menu_edit)
        val deleteItem = menuView.findViewById<TextView>(R.id.menu_delete)

        // Enable edit option for default messages, disable delete
        if (systemMessage.isDefault) {
            deleteItem.visibility = View.GONE
        }

        editItem.setOnClickListener {
            popupWindow.dismiss()
            navigateToEditScreen(systemMessage)
        }

        deleteItem.setOnClickListener {
            popupWindow.dismiss()
            if (systemMessage.isDefault) {
                GlassNotice.show(requireContext(), getString(R.string.notice_default_system_message_undeletable))
            } else {
                showDeleteConfirmationDialog(systemMessage)
            }
        }

        // --- SMART POSITIONING LOGIC ---

        // Measure the popup content to get its height
        menuView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupHeight = menuView.measuredHeight

        // Get the location of the anchor view on screen
        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        val anchorY = location[1]
        val anchorHeight = anchorView.height

        // Get screen height using WindowMetrics (replaces deprecated DisplayMetrics)
        val windowMetrics = (context as Activity).windowManager.currentWindowMetrics
        val screenHeight = windowMetrics.bounds.height()

        // Calculate available space below and above the anchor
        val spaceBelow = screenHeight - anchorY - anchorHeight
        val spaceAbove = anchorY

        // Decide whether to show above or below based on available space
        val showAbove = spaceBelow < popupHeight && spaceAbove >= popupHeight

        // Show the popup in the correct position
        if (showAbove) {
            // Show above the anchor view
            popupWindow.showAsDropDown(anchorView, 0, -anchorHeight - popupHeight)
        } else {
            // Show below the anchor view (default behavior)
            popupWindow.showAsDropDown(anchorView)
        }
        MenuDim.behind(popupWindow)
    }



    private fun navigateToEditScreen(systemMessage: SystemMessage) {
        val fragment = AddEditSystemMessageFragment().apply {
            arguments = Bundle().apply {
                putString(AddEditSystemMessageFragment.ARG_TITLE, systemMessage.title)
                putString(AddEditSystemMessageFragment.ARG_PROMPT, systemMessage.prompt)
                putBoolean(AddEditSystemMessageFragment.ARG_IS_DEFAULT, systemMessage.isDefault)
            }
        }
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .hide(this@SystemMessageLibraryFragment)
            .add(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }
    override fun onResume() {
        super.onResume()
        reload()
    }

    /** Back from the editor: this screen was only hidden under it, so it never left onResume. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) reload()
    }

    private fun reload() {
        if (::systemMessageAdapter.isInitialized) {
            systemMessageAdapter.selectedMessage = sharedPreferencesHelper.getSelectedSystemMessage()
            loadSystemMessages()
        }
    }
    private fun loadSystemMessages() {
        systemMessages.clear()
        allSystemMessages.clear()  // NEW: Clear the full list too
        val defaultMessage = sharedPreferencesHelper.getDefaultSystemMessage()
        val customMessages = sharedPreferencesHelper.getCustomSystemMessages()

        // Reset expand states (compact by default)
        defaultMessage.isExpanded = false
        customMessages.forEach { it.isExpanded = false }

        systemMessages.add(defaultMessage)
        systemMessages.addAll(customMessages)

        allSystemMessages.add(defaultMessage)  // NEW: Populate the full list
        allSystemMessages.addAll(customMessages)

        // Apply search filter only if searchView is initialized
        if (::searchView.isInitialized) {
            filterSystemMessages(searchView.query.toString())
        } else {
            systemMessageAdapter.notifyDataSetChanged()  // Fallback for non-search calls
        }
    }


    private fun filterSystemMessages(query: String) {
        if (allSystemMessages.isNotEmpty()) {
            // Use the full list for filtering
            val filteredMessages = if (query.isEmpty()) {
                allSystemMessages
            } else {
                allSystemMessages.filter { message ->
                    message.title.contains(query, ignoreCase = true) ||
                            message.prompt.contains(query, ignoreCase = true)
                }
            }
            systemMessages.clear()
            systemMessages.addAll(filteredMessages)
            // Collapse all filtered items
            systemMessages.forEach { it.isExpanded = false }
        }
        systemMessageAdapter.notifyDataSetChanged()
    }




    private fun showDeleteConfirmationDialog(systemMessage: SystemMessage) {
        GrokConfirmDialog.show(
            fragment = this,
            title = getString(R.string.delete_system_message_title),
            message = getString(R.string.delete_system_message_body),
            confirmText = getString(R.string.delete_message_confirm),
            onConfirm = { deleteSystemMessage(systemMessage) }
        )
    }
    private fun deleteSystemMessage(systemMessage: SystemMessage) {
        val customMessages = sharedPreferencesHelper.getCustomSystemMessages().toMutableList()

        // Find the item matching title and prompt, ignoring UI states like isExpanded
        val indexToRemove = customMessages.indexOfFirst {
            it.title == systemMessage.title && it.prompt == systemMessage.prompt
        }

        if (indexToRemove != -1) {
            customMessages.removeAt(indexToRemove)
            sharedPreferencesHelper.saveCustomSystemMessages(customMessages)

            // Also check if the currently selected message is the one being deleted
            val currentSelected = sharedPreferencesHelper.getSelectedSystemMessage()
            if (currentSelected.title == systemMessage.title && currentSelected.prompt == systemMessage.prompt) {
                val defaultMessage = sharedPreferencesHelper.getDefaultSystemMessage()
                sharedPreferencesHelper.saveSelectedSystemMessage(defaultMessage)
                systemMessageAdapter.selectedMessage = defaultMessage
            }

            // This will now reliably trigger and refresh the UI!
            loadSystemMessages()
        }
    }

}