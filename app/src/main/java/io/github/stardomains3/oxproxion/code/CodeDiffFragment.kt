package io.github.stardomains3.oxproxion.code

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import io.github.stardomains3.oxproxion.R

/** Full-screen diff for one file change in a session. Reads the event from [CodeHub] by key. */
class CodeDiffFragment : Fragment(R.layout.fragment_code_diff) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val args = requireArguments()
        val hub = CodeHub.get(requireContext())
        val event = hub.sessions.value[args.getString(ARG_SESSION)]?.events
            ?.firstOrNull { it.key == args.getString(ARG_KEY) } as? CodeEvent.FileDiff
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        if (event == null) {
            parentFragmentManager.popBackStack()
            return
        }
        toolbar.title = CodeComposer.folderName(event.path)
        toolbar.subtitle = "${event.path.substringBeforeLast('/', "")}  ·  " +
            if (event.isNewFile) getString(R.string.code_session_new_file)
            else getString(R.string.code_diff_counts, event.added, event.removed)
        view.findViewById<DiffView>(R.id.codeDiffFull).apply {
            wrapWidth = false
            lines = event.lines
        }
    }

    companion object {
        private const val ARG_SESSION = "session"
        private const val ARG_KEY = "key"

        fun newInstance(sessionId: String, key: String) =
            CodeDiffFragment().apply { arguments = bundleOf(ARG_SESSION to sessionId, ARG_KEY to key) }
    }
}
