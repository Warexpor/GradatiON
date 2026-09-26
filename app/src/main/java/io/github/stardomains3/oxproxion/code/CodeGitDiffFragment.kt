package io.github.stardomains3.oxproxion.code

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import io.github.stardomains3.oxproxion.AppToast
import io.github.stardomains3.oxproxion.R
import kotlinx.coroutines.launch

/**
 * Full unified diff for one working-tree path (`bridge/diff`). Actions ask the agent
 * to commit or revert via [CodeHub.prompt], not raw git.
 */
class CodeGitDiffFragment : Fragment(R.layout.fragment_code_git_diff) {

    private val sessionId by lazy { requireArguments().getString(ARG_SESSION)!! }
    private val path by lazy { requireArguments().getString(ARG_PATH)!! }
    private lateinit var hub: CodeHub

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        hub = CodeHub.get(requireContext())
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val diffView = view.findViewById<DiffView>(R.id.codeGitDiffView)
        val hint = view.findViewById<TextView>(R.id.codeGitDiffHint)
        val actions = view.findViewById<View>(R.id.codeGitDiffActions)
        val commitBtn = view.findViewById<MaterialButton>(R.id.codeGitDiffCommit)
        val revertBtn = view.findViewById<MaterialButton>(R.id.codeGitDiffRevert)

        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        toolbar.title = CodeComposer.folderName(path)
        toolbar.subtitle = path.substringBeforeLast('/', "").ifBlank { path }

        commitBtn.setOnClickListener { ask(getString(R.string.code_changes_prompt_commit)) }
        revertBtn.setOnClickListener {
            ask(getString(R.string.code_changes_prompt_revert, path))
        }

        hint.isVisible = false
        actions.isVisible = false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = hub.diffResult(sessionId, path)
            val unified = result.getOrNull()?.unified
            if (result.isFailure || unified == null) {
                hint.text = getString(R.string.code_changes_diff_failed)
                hint.isVisible = true
                return@launch
            }
            if (unified.isBlank()) {
                hint.text = getString(R.string.code_changes_diff_empty)
                hint.isVisible = true
                actions.isVisible = true
                return@launch
            }
            val lines = Diff.parseUnified(unified)
            val (add, del) = Diff.counts(lines)
            toolbar.subtitle = "${path.substringBeforeLast('/', "").ifBlank { "." }}  ·  " +
                getString(R.string.code_diff_counts, add, del)
            diffView.wrapWidth = false
            diffView.lines = lines
            actions.isVisible = true
        }
    }

    private fun ask(prompt: String) {
        if (!hub.prompt(sessionId, prompt)) {
            AppToast.makeText(requireContext(), getString(R.string.code_changes_busy), AppToast.LENGTH_SHORT).show()
            return
        }
        parentFragmentManager.popBackStack(CodeSessionFragment.BACK_STACK_TAG, 0)
    }

    companion object {
        private const val ARG_SESSION = "session"
        private const val ARG_PATH = "path"

        fun newInstance(sessionId: String, path: String) =
            CodeGitDiffFragment().apply {
                arguments = bundleOf(ARG_SESSION to sessionId, ARG_PATH to path)
            }
    }
}
