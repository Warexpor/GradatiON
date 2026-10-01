package io.github.stardomains3.oxproxion.code

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
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
 * Commit / revert are agent prompts, not raw git.
 */
class CodeChangesFragment : Fragment(R.layout.fragment_code_changes) {

    private val sessionId by lazy { requireArguments().getString(ARG_SESSION)!! }
    private lateinit var hub: CodeHub
    private lateinit var list: RecyclerView
    private lateinit var hint: TextView
    private lateinit var toolbar: MaterialToolbar
    private val rows = ArrayList<GitFileStatus>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        hub = CodeHub.get(requireContext())
        toolbar = view.findViewById(R.id.toolbar)
        list = view.findViewById(R.id.codeChangesList)
        hint = view.findViewById(R.id.codeChangesHint)
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
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = Adapter()
        reload()
    }

    private fun reload() {
        hint.text = getString(R.string.code_changes_loading)
        hint.isVisible = true
        setActionsEnabled(false)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = hub.gitStatusResult(sessionId)
            val status = result.getOrNull()
            if (result.isFailure || status == null) {
                rows.clear()
                list.adapter?.notifyDataSetChanged()
                hint.text = getString(R.string.code_changes_failed)
                hint.isVisible = true
                toolbar.subtitle = null
                setActionsEnabled(false)
                return@launch
            }
            toolbar.subtitle = buildSubtitle(status).ifBlank { null }
            rows.clear()
            rows.addAll(status.files)
            list.adapter?.notifyDataSetChanged()
            setActionsEnabled(rows.isNotEmpty())
            if (rows.isEmpty()) {
                hint.text = getString(R.string.code_changes_empty)
                hint.isVisible = true
            } else {
                hint.isVisible = false
            }
        }
    }

    private fun buildSubtitle(status: GitStatusResult): String {
        val parts = ArrayList<String>(3)
        if (status.branch.isNotBlank()) parts += status.branch
        if (status.ahead > 0) parts += getString(R.string.code_changes_ahead, status.ahead)
        if (status.behind > 0) parts += getString(R.string.code_changes_behind, status.behind)
        return parts.joinToString("  ·  ")
    }

    private fun openDiff(file: GitFileStatus) {
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .add(R.id.fragment_container, CodeGitDiffFragment.newInstance(sessionId, file.path))
            .addToBackStack(null)
            .commit()
    }

    private fun setActionsEnabled(enabled: Boolean) {
        toolbar.menu.findItem(R.id.action_ask_commit)?.isEnabled = enabled
        toolbar.menu.findItem(R.id.action_ask_revert_all)?.isEnabled = enabled
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
        if (rows.isEmpty()) return
        GrokConfirmDialog.show(
            this,
            getString(R.string.code_changes_revert_all_title),
            getString(R.string.code_changes_revert_all_message),
            getString(R.string.code_changes_revert_all_confirm),
            onConfirm = {
                val prompt = resources.getQuantityString(R.plurals.code_changes_prompt_revert_all, rows.size, rows.size)
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
            holder.status.text = GitBridgeJson.statusLetter(f.status)
            holder.path.text = f.path
            holder.itemView.setOnClickListener { openDiff(f) }
        }
    }

    companion object {
        private const val ARG_SESSION = "session"

        fun newInstance(sessionId: String) =
            CodeChangesFragment().apply { arguments = bundleOf(ARG_SESSION to sessionId) }
    }
}
