package io.github.stardomains3.oxproxion

import android.app.Dialog
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

        val options = arrayOf(
            "2 messages", "4 messages", "6 messages", "8 messages",
            "10 messages", "12 messages", "16 messages", "20 messages", "All messages"
        )

        val checkedItem = when (currentCount) {
            Int.MAX_VALUE -> 8
            else -> {
                val index = options.indexOfFirst {
                    it.startsWith(currentCount.toString()) &&
                        (it.length == currentCount.toString().length || it[currentCount.toString().length] == ' ')
                }
                if (index >= 0) index else 3
            }
        }

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
            .setTitle("Chat Memory")
            .setSingleChoiceItems(adapter, checkedItem) { dialog, which ->
                val selectedText = options[which]
                val count = if (selectedText == "All messages") {
                    Int.MAX_VALUE
                } else {
                    selectedText.split(" ")[0].toInt()
                }

                prefs.saveChatMemoryCount(count)

                val button = requireActivity().findViewById<MaterialButton>(R.id.chatMemoryButton)
                button?.text = if (count == Int.MAX_VALUE) "All messages" else "$count messages"

                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}
