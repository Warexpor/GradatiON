package io.github.stardomains3.oxproxion.code

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import io.github.stardomains3.oxproxion.GlassNotice
import io.github.stardomains3.oxproxion.GrokConfirmDialog
import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations
import io.github.stardomains3.oxproxion.R
import kotlinx.coroutines.launch

/**
 * Per-session working-tree changes from `bridge/gitStatus`. Tap a file for `bridge/diff`.
 * Commit / revert are agent prompts, not raw git. Revert covers tracked paths only.
 */
class CodeChangesFragment : Fragment(R.layout.fragment_code_changes) {

    private val sessionId by lazy { requireArguments().getString(ARG_SESSION)!! }
    private lateinit var hub: CodeHub
    private lateinit var list: RecyclerView
    private lateinit var hint: TextView
    private lateinit var filter: EditText
    private lateinit var toolbar: MaterialToolbar
    /** Full status from the bridge. The list shows [rows], which is this set after the filter. */
    private val allFiles = ArrayList<GitFileStatus>()
    private val rows = ArrayList<GitFileStatus>()
    /** Bumped on each load so a slow reply cannot paint over a newer tap. */
    private var loadGen = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        hub = CodeHub.get(requireContext())
        toolbar = view.findViewById(R.id.toolbar)
        list = view.findViewById(R.id.codeChangesList)
        hint = view.findViewById(R.id.codeChangesHint)
        filter = view.findViewById(R.id.codeChangesFilter)
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        toolbar.inflateMenu(R.menu.menu_code_changes)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_ask_commit -> {
                    askCommit()
                    true
                }
                R.id.action_ask_revert_all -> {
                    askRevertAll()
                    true
                }
                else -> false
            }
        }
        filter.doAfterTextChanged { publishRows() }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = Adapter()
        reload()
    }

    private fun reload() {
        val gen = ++loadGen
        hint.setOnClickListener(null)
        hint.isClickable = false
        hint.text = getString(R.string.code_changes_loading)
        hint.isVisible = true
        filter.isVisible = false
        setActionsEnabled(commit = false, revert = false)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = hub.gitStatusResult(sessionId)
            if (gen != loadGen || !isAdded) return@launch
            val status = result.getOrNull()
            if (result.isFailure || status == null) {
                allFiles.clear()
                rows.clear()
                list.adapter?.notifyDataSetChanged()
                hint.text = getString(R.string.code_changes_failed) + "\n" + getString(R.string.code_changes_tap_retry)
                hint.isVisible = true
                hint.isClickable = true
                hint.setOnClickListener { reload() }
                filter.isVisible = false
                toolbar.subtitle = null
                setActionsEnabled(commit = false, revert = false)
                return@launch
            }
            toolbar.subtitle = buildSubtitle(status).ifBlank { null }
            allFiles.clear()
            allFiles.addAll(status.files)
            publishRows()
        }
    }

    /** Branch, ahead/behind, then how many paths a revert can actually restore. */
    private fun buildSubtitle(status: GitStatusResult): String {
        val parts = ArrayList<String>(5)
        if (status.branch.isNotBlank()) parts += status.branch
        if (status.ahead > 0) parts += getString(R.string.code_changes_ahead, status.ahead)
        if (status.behind > 0) parts += getString(R.string.code_changes_behind, status.behind)
        val tracked = GitChanges.trackedCount(status.files)
        val untracked = GitChanges.untrackedCount(status.files)
        if (tracked > 0) parts += resources.getQuantityString(R.plurals.code_changes_tracked_count, tracked, tracked)
        if (untracked > 0) parts += resources.getQuantityString(R.plurals.code_changes_untracked_count, untracked, untracked)
        return parts.joinToString("  ·  ")
    }

    /**
     * The filter narrows the list only. Commit and revert still refer to the whole status:
     * a search must not make "revert all" restore just the rows on screen.
     */
    private fun publishRows() {
        val query = filter.text?.toString().orEmpty()
        rows.clear()
        rows.addAll(GitChanges.filter(allFiles, query))
        list.adapter?.notifyDataSetChanged()
        val tracked = GitChanges.trackedCount(allFiles)
        setActionsEnabled(commit = allFiles.isNotEmpty(), revert = tracked > 0)
        filter.isVisible = allFiles.isNotEmpty()
        when {
            allFiles.isEmpty() -> {
                hint.text = getString(R.string.code_changes_empty)
                hint.isVisible = true
            }
            rows.isEmpty() -> {
                hint.text = getString(R.string.code_changes_filter_empty)
                hint.isVisible = true
            }
            else -> hint.isVisible = false
        }
    }

    private fun openDiff(file: GitFileStatus) {
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .add(
                R.id.fragment_container,
                CodeGitDiffFragment.newInstance(
                    sessionId,
                    GitChanges.changePath(file).diffPath,
                    tracked = GitChanges.isTrackedChange(file.status),
                ),
            )
            .addToBackStack(null)
            .commit()
    }

    private fun copyPath(path: String) {
        requireContext().getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("path", path))
        GlassNotice.show(requireContext(), getString(R.string.code_session_copied))
    }

    private fun setActionsEnabled(commit: Boolean, revert: Boolean) {
        toolbar.menu.findItem(R.id.action_ask_commit)?.isEnabled = commit
        toolbar.menu.findItem(R.id.action_ask_revert_all)?.isEnabled = revert
    }

    private fun askCommit() {
        val prompt = getString(R.string.code_changes_prompt_commit)
        if (!hub.prompt(sessionId, prompt)) {
            GlassNotice.show(requireContext(), getString(R.string.code_changes_busy))
            return
        }
        parentFragmentManager.popBackStack(CodeSessionFragment.BACK_STACK_TAG, 0)
    }

    /** Asks the agent to restore every tracked path. Untracked files are left alone. */
    private fun askRevertAll() {
        val tracked = GitChanges.trackedCount(allFiles)
        if (tracked == 0) return
        GrokConfirmDialog.show(
            this,
            getString(R.string.code_changes_revert_all_title),
            getString(R.string.code_changes_revert_all_message),
            getString(R.string.code_changes_revert_all_confirm),
            onConfirm = {
                val prompt = resources.getQuantityString(R.plurals.code_changes_prompt_revert_all, tracked, tracked)
                if (hub.prompt(sessionId, prompt)) {
                    parentFragmentManager.popBackStack(CodeSessionFragment.BACK_STACK_TAG, 0)
                } else {
                    GlassNotice.show(requireContext(), getString(R.string.code_changes_busy))
                }
            },
        )
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val status: TextView = v.findViewById(R.id.codeChangeStatus)
            val path: TextView = v.findViewById(R.id.codeChangePath)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_code_change, parent, false)
            return VH(v)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = rows[position]
            val shown = GitChanges.changePath(f)
            holder.status.text = GitBridgeJson.statusLetter(f.status)
            val from = shown.renamedFrom
            holder.path.text = if (from.isNullOrEmpty()) shown.diffPath
            else holder.itemView.context.getString(R.string.code_changes_renamed, shown.diffPath, from)
            holder.itemView.setOnClickListener { openDiff(f) }
            holder.itemView.setOnLongClickListener {
                copyPath(shown.diffPath)
                true
            }
        }
    }

    companion object {
        private const val ARG_SESSION = "session"

        fun newInstance(sessionId: String) =
            CodeChangesFragment().apply { arguments = bundleOf(ARG_SESSION to sessionId) }
    }
}
