package io.github.stardomains3.oxproxion

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class RpLorebookEditFragment : Fragment() {

    private val chatViewModel: ChatViewModel by activityViewModels { AppViewModelFactory(requireActivity().application) }
    private var lorebookId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lorebookId = arguments?.getLong(ARG_ID) ?: 0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_rp_lorebook_edit, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(
            if (lorebookId > 0) R.string.rp_edit_lorebook else R.string.rp_add_lorebook
        )
        val nameInput = view.findViewById<TextInputEditText>(R.id.rpLoreNameInput)
        val contentInput = view.findViewById<TextInputEditText>(R.id.rpLoreContentInput)
        val saveButton = view.findViewById<MaterialButton>(R.id.saveRpLorebookButton)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.rpLoreNameLayout)
        val contentLayout = view.findViewById<TextInputLayout>(R.id.rpLoreContentLayout)
        val activeSwitch = view.findViewById<SwitchCompat>(R.id.rpLoreActiveSwitch)
        val inputs = listOf(nameInput, contentInput)
        var baseline = LoreEditSnapshot()

        // The tap target is the whole row; the switch only shows the state.
        view.findViewById<View>(R.id.rpLoreActiveRow).setOnClickListener {
            if (saveButton.isEnabled) activeSwitch.toggle()
        }
        // Warn, never block: a broken [keys: line still saves, it just reads as plain text.
        fun checkKeyLines() {
            val bad = RpLore.malformedKeyLines(contentInput.text?.toString().orEmpty())
            contentLayout.error = if (bad.isEmpty()) null else getString(
                R.string.rp_lore_bad_keys,
                bad.take(3).joinToString(", ")
            )
        }
        nameInput.doAfterTextChanged { nameLayout.error = null }
        contentInput.doAfterTextChanged { checkKeyLines() }

        fun currentSnapshot() = LoreEditSnapshot(
            name = nameInput.text?.toString().orEmpty(),
            content = contentInput.text?.toString().orEmpty(),
            active = activeSwitch.isChecked
        )
        fun navigateUp() {
            if (currentSnapshot() == baseline) {
                parentFragmentManager.popBackStack()
                return
            }
            GrokConfirmDialog.show(
                fragment = this,
                title = getString(R.string.rp_discard_edits_title),
                message = getString(R.string.rp_discard_edits_body),
                confirmText = getString(R.string.rp_discard_edits_confirm),
                onConfirm = { parentFragmentManager.popBackStack() },
                destructive = true
            )
        }
        toolbar.setNavigationOnClickListener { navigateUp() }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = navigateUp()
            }
        )

        // Inputs stay locked until the book is read, so the baseline is the loaded book and typing
        // can't be overwritten by the load.
        saveButton.isEnabled = false
        inputs.forEach { it.isEnabled = false }
        if (lorebookId > 0) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val book = chatViewModel.getRpRepository().getLorebookById(lorebookId)
                    if (!isAdded) return@launch
                    if (book == null) {
                        GlassNotice.show(requireContext(), getString(R.string.rp_lorebook_gone))
                        parentFragmentManager.popBackStack()
                        return@launch
                    }
                    nameInput.setText(book.name)
                    contentInput.setText(book.content)
                    activeSwitch.isChecked = book.isActive
                    baseline = currentSnapshot()
                } finally {
                    if (isAdded) {
                        inputs.forEach { it.isEnabled = true }
                        saveButton.isEnabled = true
                    }
                }
            }
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // The first book is the one you'll want in use; later ones start off.
                    activeSwitch.isChecked = chatViewModel.getRpRepository().getAllLorebooksOnce().isEmpty()
                    baseline = currentSnapshot()
                } finally {
                    if (isAdded) {
                        inputs.forEach { it.isEnabled = true }
                        saveButton.isEnabled = true
                    }
                }
            }
        }
        saveButton.setOnClickListener {
            if (!saveButton.isEnabled) return@setOnClickListener
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                nameLayout.error = getString(R.string.rp_name_required)
                nameInput.requestFocus()
                return@setOnClickListener
            }
            saveButton.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val repo = chatViewModel.getRpRepository()
                    val existing = if (lorebookId > 0) repo.getLorebookById(lorebookId) else null
                    if (lorebookId > 0 && existing == null) {
                        if (isAdded) {
                            GlassNotice.show(requireContext(), getString(R.string.rp_lorebook_gone))
                            parentFragmentManager.popBackStack()
                        }
                        return@launch
                    }
                    val savedId = repo.saveLorebook(
                        (existing ?: RpLorebook(name = name)).copy(
                            name = name,
                            content = contentInput.text?.toString().orEmpty()
                        )
                    )
                    repo.setLorebookActive(savedId, activeSwitch.isChecked)
                    if (isAdded) parentFragmentManager.popBackStack()
                } catch (_: Exception) {
                    if (isAdded) {
                        saveButton.isEnabled = true
                        GlassNotice.show(requireContext(), getString(R.string.rp_save_failed))
                    }
                }
            }
        }
    }

    private data class LoreEditSnapshot(
        val name: String = "",
        val content: String = "",
        val active: Boolean = false
    )

    companion object {
        private const val ARG_ID = "lorebook_id"
        fun newInstance(id: Long) = RpLorebookEditFragment().apply {
            arguments = Bundle().apply { putLong(ARG_ID, id) }
        }
    }
}
