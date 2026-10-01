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
import io.github.stardomains3.oxproxion.GlassNotice
import io.github.stardomains3.oxproxion.GrokConfirmDialog
import io.github.stardomains3.oxproxion.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full unified diff for one working-tree path (`bridge/diff`). Actions ask the agent
 * to commit or revert via [CodeHub.prompt], not raw git.
 *
 * Parse + counts run on [Dispatchers.Default]; bind on Main. Large diffs are capped
 * at [MAX_LINES] with a truncated subtitle hint so measure/layout stay bounded.
 */
class CodeGitDiffFragment : Fragment(R.layout.fragment_code_git_diff) {

    private val sessionId by lazy { requireArguments().getString(ARG_SESSION)!! }
    private val path by lazy { requireArguments().getString(ARG_PATH)!! }
    /** False for an untracked path: restore-to-HEAD does not apply, so Revert stays hidden. */
    private val tracked by lazy { requireArguments().getBoolean(ARG_TRACKED, true) }
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

        commitBtn.setOnClickListener {
            ask(getString(R.string.code_changes_prompt_commit_file, path))
        }
        revertBtn.isVisible = tracked
        revertBtn.setOnClickListener {
            GrokConfirmDialog.show(
                this,
                getString(R.string.code_changes_revert_file_title),
                getString(R.string.code_changes_revert_file_message, CodeComposer.folderName(path)),
                getString(R.string.code_changes_revert_all_confirm),
                onConfirm = { ask(getString(R.string.code_changes_prompt_revert, path)) },
            )
        }

        hint.text = getString(R.string.code_changes_diff_loading)
        hint.isVisible = true
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
                hint.text = getString(if (tracked) R.string.code_changes_diff_empty else R.string.code_changes_diff_untracked)
                hint.isVisible = true
                actions.isVisible = true
                return@launch
            }
            val parsed = withContext(Dispatchers.Default) {
                val all = Diff.parseUnified(unified)
                val (add, del) = Diff.counts(all)
                val truncated = all.size > MAX_LINES
                val shown = if (truncated) all.take(MAX_LINES) else all
                ParsedDiff(shown, add, del, truncated)
            }
            if (parsed.lines.isEmpty()) {
                hint.text = getString(if (tracked) R.string.code_changes_diff_empty else R.string.code_changes_diff_untracked)
                hint.isVisible = true
                actions.isVisible = true
                return@launch
            }
            val dir = path.substringBeforeLast('/', "").ifBlank { "." }
            val counts = coloredDiffCounts(requireContext(), parsed.add, parsed.del)
            toolbar.subtitle = if (parsed.truncated) {
                android.text.SpannableStringBuilder("$dir  ·  ").append(counts).append("  ·  " + getString(R.string.code_changes_diff_truncated, MAX_LINES))
            } else {
                android.text.SpannableStringBuilder("$dir  ·  ").append(counts)
            }
            diffView.maxLines = MAX_LINES
            diffView.wrapWidth = false
            diffView.lines = parsed.lines
            hint.isVisible = false
            actions.isVisible = true
        }
    }

    private fun ask(prompt: String) {
        if (!hub.prompt(sessionId, prompt)) {
            GlassNotice.show(requireContext(), getString(R.string.code_changes_busy))
            return
        }
        parentFragmentManager.popBackStack(CodeSessionFragment.BACK_STACK_TAG, 0)
    }

    private data class ParsedDiff(
        val lines: List<DiffLine>,
        val add: Int,
        val del: Int,
        val truncated: Boolean
    )

    companion object {
        private const val ARG_SESSION = "session"
        private const val ARG_PATH = "path"
        private const val ARG_TRACKED = "tracked"
        /** Cap rendered lines so Main measure/layout stays bounded for huge working-tree patches. */
        private const val MAX_LINES = 2000

        fun newInstance(sessionId: String, path: String, tracked: Boolean = true) =
            CodeGitDiffFragment().apply {
                arguments = bundleOf(ARG_SESSION to sessionId, ARG_PATH to path, ARG_TRACKED to tracked)
            }
    }
}
