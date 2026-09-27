package io.github.stardomains3.oxproxion

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
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
        var baseline = LoreEditSnapshot()

        fun currentSnapshot() = LoreEditSnapshot(
            name = nameInput.text?.toString().orEmpty(),
            content = contentInput.text?.toString().orEmpty()
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

        if (lorebookId > 0) {
            saveButton.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val book = chatViewModel.getRpRepository().getLorebookById(lorebookId)
                    if (!isAdded) return@launch
                    if (book == null) {
                        AppToast.makeText(
                            requireContext(),
                            getString(R.string.rp_lorebook_gone),
                            AppToast.LENGTH_SHORT
                        ).show()
                        parentFragmentManager.popBackStack()
                        return@launch
                    }
                    nameInput.setText(book.name)
                    contentInput.setText(book.content)
                    baseline = currentSnapshot()
                } finally {
                    if (isAdded) saveButton.isEnabled = true
                }
            }
        } else {
            baseline = currentSnapshot()
        }
        saveButton.setOnClickListener {
            if (!saveButton.isEnabled) return@setOnClickListener
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                AppToast.makeText(requireContext(), getString(R.string.rp_name_required), AppToast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveButton.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val repo = chatViewModel.getRpRepository()
                    val existing = if (lorebookId > 0) repo.getLorebookById(lorebookId) else null
                    if (lorebookId > 0 && existing == null) {
                        if (isAdded) {
                            AppToast.makeText(
                                requireContext(),
                                getString(R.string.rp_lorebook_gone),
                                AppToast.LENGTH_SHORT
                            ).show()
                            parentFragmentManager.popBackStack()
                        }
                        return@launch
                    }
                    val wasEmpty = lorebookId == 0L && repo.getAllLorebooksOnce().isEmpty()
                    val savedId = repo.saveLorebook(
                        (existing ?: RpLorebook(name = name)).copy(
                            name = name,
                            content = contentInput.text?.toString().orEmpty()
                        )
                    )
                    if (wasEmpty) {
                        repo.setActiveLorebook(savedId)
                        if (isAdded) {
                            val prefs = SharedPreferencesHelper(requireContext())
                            val msg = if (prefs.isRpLoreEnabled()) {
                                getString(R.string.rp_lore_activated)
                            } else {
                                getString(R.string.rp_lore_activated_disabled)
                            }
                            AppToast.makeText(requireContext(), msg, AppToast.LENGTH_LONG).show()
                        }
                    }
                    if (isAdded) parentFragmentManager.popBackStack()
                } catch (_: Exception) {
                    if (isAdded) {
                        saveButton.isEnabled = true
                        AppToast.makeText(
                            requireContext(),
                            getString(R.string.rp_save_failed),
                            AppToast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private data class LoreEditSnapshot(
        val name: String = "",
        val content: String = ""
    )

    companion object {
        private const val ARG_ID = "lorebook_id"
        fun newInstance(id: Long) = RpLorebookEditFragment().apply {
            arguments = Bundle().apply { putLong(ARG_ID, id) }
        }
    }
}
