package io.github.stardomains3.oxproxion

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ChatMemoryDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val prefs = SharedPreferencesHelper(requireContext())
        val currentCount = prefs.getChatMemoryCount()

        val counts = COUNTS
        val options = Array(counts.size) { label(requireContext(), counts[it]) }
        val checkedItem = counts.indexOf(currentCount).let { if (it >= 0) it else DEFAULT_INDEX }

        val ink = ContextCompat.getColor(requireContext(), R.color.xai_ink)
        val adapter = object : ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_list_item_single_choice,
            options
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as TextView).setTextColor(ink)
                return view
            }
        }

        return GlassAlertDialogBuilder(requireContext(), R.style.CustomMaterialAlertDialogTheme)
            .setTitle(R.string.settings_chat_memory)
            .setSingleChoiceItems(adapter, checkedItem) { dialog, which ->
                val count = counts[which]
                prefs.saveChatMemoryCount(count)

                val button = requireActivity().findViewById<MaterialButton>(R.id.chatMemoryButton)
                button?.text = label(requireContext(), count)

                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
    }

    companion object {
        /** What the picker offers, in order; [Int.MAX_VALUE] is "all". */
        private val COUNTS = intArrayOf(2, 4, 6, 8, 10, 12, 16, 20, Int.MAX_VALUE)
        private const val DEFAULT_INDEX = 3

        /** "8 messages" or "All messages", for the picker and the settings row. */
        fun label(context: Context, count: Int): String =
            if (count == Int.MAX_VALUE) context.getString(R.string.chat_memory_all)
            else context.resources.getQuantityString(R.plurals.chat_memory_messages, count, count)
    }
}
