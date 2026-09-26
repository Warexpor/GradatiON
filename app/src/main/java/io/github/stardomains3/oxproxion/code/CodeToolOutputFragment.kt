package io.github.stardomains3.oxproxion.code

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import io.github.stardomains3.oxproxion.AppToast
import io.github.stardomains3.oxproxion.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Full tool-call output: mono body, glass find field (line filter), Copy.
 * Reads live from [CodeHub.sessions] so ToolPatch updates refresh while open.
 * Shows the phone-side stored output only (capped at [AcpAdapter.MAX_OUTPUT]);
 * no bridge full-log RPC exists yet.
 */
class CodeToolOutputFragment : Fragment(R.layout.fragment_code_tool_output) {

    private val sessionId by lazy { requireArguments().getString(ARG_SESSION)!! }
    private val eventKey by lazy { requireArguments().getString(ARG_KEY)!! }

    private lateinit var hub: CodeHub
    private lateinit var toolbar: MaterialToolbar
    private lateinit var find: EditText
    private lateinit var body: TextView
    private lateinit var truncated: TextView

    /** Latest stored output from the session event (unfiltered). */
    private var stored: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        hub = CodeHub.get(requireContext())
        toolbar = view.findViewById(R.id.toolbar)
        find = view.findViewById(R.id.codeToolOutputFind)
        body = view.findViewById(R.id.codeToolOutputBody)
        truncated = view.findViewById(R.id.codeToolOutputTruncated)

        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        toolbar.inflateMenu(R.menu.menu_code_tool_output)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_copy -> {
                    copyStored()
                    true
                }
                else -> false
            }
        }
        find.doAfterTextChanged { renderBody() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.sessions
                    .map { sessions ->
                        sessions[sessionId]?.events
                            ?.firstOrNull { it.key == eventKey } as? CodeEvent.ToolCall
                    }
                    .distinctUntilChanged()
                    .collect { tool ->
                        if (tool == null) {
                            parentFragmentManager.popBackStack()
                            return@collect
                        }
                        bind(tool)
                    }
            }
        }
    }

    private fun bind(e: CodeEvent.ToolCall) {
        toolbar.title = e.title
        toolbar.subtitle = e.detail?.takeIf { it.isNotBlank() }
        stored = e.output.orEmpty()
        truncated.isVisible = ToolOutputText.isPhoneTailTruncated(stored)
        renderBody()
    }

    private fun renderBody() {
        body.text = ToolOutputText.filterLines(stored, find.text?.toString().orEmpty())
    }

    private fun copyStored() {
        if (stored.isBlank()) {
            AppToast.makeText(requireContext(), getString(R.string.toast_nothing_to_copy), AppToast.LENGTH_SHORT).show()
            return
        }
        requireContext().getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("tool-output", stored))
        AppToast.makeText(requireContext(), getString(R.string.code_session_copied), AppToast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ARG_SESSION = "session"
        private const val ARG_KEY = "key"

        fun newInstance(sessionId: String, eventKey: String) =
            CodeToolOutputFragment().apply {
                arguments = bundleOf(ARG_SESSION to sessionId, ARG_KEY to eventKey)
            }
    }
}
