package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations


import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.widget.SwitchCompat


class PresetsListFragment : Fragment() {

    private lateinit var viewModel: ChatViewModel
    private lateinit var repository: PresetRepository
    private lateinit var adapter: PresetAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_presets_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerViewPresets)
        val fab = view.findViewById<MaterialButton>(R.id.fabAddPreset)
        setupClearChatSwitch()
        toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        viewModel = ViewModelProvider(requireActivity(), AppViewModelFactory(requireActivity().application))[ChatViewModel::class.java]
        repository = PresetRepository(requireContext())

        adapter = PresetAdapter(
            onItemClicked = { preset ->
                if (applyPresetSafely(preset)) {
                    parentFragmentManager.popBackStack()
                }
            },
            onItemEdit = { preset ->
                val dialog = PresetEditFragment.newInstance(preset)
                parentFragmentManager.beginTransaction()
                    .withGrokStackAnimations()
                    .replace(R.id.fragment_container, dialog)
                    .addToBackStack(null)
                    .commit()
            },
            onItemDelete = { preset ->
                GrokConfirmDialog.show(
                    fragment = this@PresetsListFragment,
                    title = getString(R.string.delete_preset_title),
                    message = getString(R.string.delete_preset_body, preset.title),
                    confirmText = getString(R.string.delete_message_confirm),
                    onConfirm = {
                        repository.deleteById(preset.id)
                        refresh()
                        AppToast.makeText(requireContext(), "Preset deleted", AppToast.LENGTH_SHORT).show()
                    }
                )
            }
        )

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        EmptyState.bind(recycler, view.findViewById(R.id.presetsEmptyView))

        // Add drag and drop functionality
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false

                adapter.move(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // No-op
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Persist the new order when drag is released
                repository.saveAll(adapter.getItems())
            }
        }
        val itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(recycler)

        fab.setOnClickListener {
            val dialog = PresetEditFragment.newInstance(null)
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .replace(R.id.fragment_container, dialog)
                .addToBackStack(null)
                .commit()
        }

        refresh()
    }

    private fun refresh() {
        // Load presets in saved order (no sorting)
        adapter.update(repository.getAll())
    }
    private fun setupClearChatSwitch() {
        val switch = view?.findViewById<SwitchCompat>(R.id.switchClearChat)  // Updated type
        switch?.apply {
            applyGrokionSwitchStyle()
            // Restore state from SharedPreferences
            val sharedPrefs = SharedPreferencesHelper(requireContext())
            isChecked = sharedPrefs.getClearChatDefault2()

            // One-tap toggle: Saves state immediately
            setOnCheckedChangeListener { _, isChecked ->
                sharedPrefs.saveClearChatDefault2(isChecked)
                // Optional: Toast feedback
                // AppToast.makeText(requireContext(), if (isChecked) "Will clear chat" else "Won't clear", AppToast.LENGTH_SHORT).show()
            }
        }
    }
    private fun applyPresetSafely(preset: Preset): Boolean {
        val allModels = (viewModel.getBuiltInModels() + SharedPreferencesHelper(requireContext()).getCustomModels()).distinctBy { it.apiIdentifier.lowercase() }
        val selectedModel = allModels.find { it.apiIdentifier.equals(preset.modelIdentifier, ignoreCase = true) }
        if (selectedModel == null) {
            AppToast.makeText(requireContext(), "Preset not applied: Model \"${preset.modelIdentifier}\" no longer exists.", AppToast.LENGTH_LONG).show()
            return false
        }

        val allMessages = listOf(SharedPreferencesHelper(requireContext()).getDefaultSystemMessage()) + SharedPreferencesHelper(requireContext()).getCustomSystemMessages()
        val existsMessage = allMessages.any { it.title == preset.systemMessage.title && it.prompt == preset.systemMessage.prompt }
        if (!existsMessage) {
            AppToast.makeText(requireContext(), "Preset not applied: Saved system message not found.", AppToast.LENGTH_LONG).show()
            return false
        }

        PresetManager.applyPreset(requireContext(), viewModel, preset)
        val sharedPrefs = SharedPreferencesHelper(requireContext())
        if (sharedPrefs.getClearChatDefault2()) {
            viewModel.startNewChat()
        }
       /* if (ForegroundService.isRunningForeground && SharedPreferencesHelper(requireContext()).getNotiPreference()) {
            val displayName = viewModel.getModelDisplayName(preset.title)
            ForegroundService.updateNotificationStatusSilently(displayName, "Preset Applied")
        }*/

       // AppToast.makeText(requireContext(), "Preset applied: ${preset.title}", AppToast.LENGTH_SHORT).show()
        return true
    }
}