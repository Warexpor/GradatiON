package io.github.stardomains3.oxproxion

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class SaveApiDialogFragment : DialogFragment() {

    private val viewModel: ChatViewModel by activityViewModels { AppViewModelFactory(requireActivity().application) }

    companion object {
        const val TAG = "SaveApiDialogFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_save_api, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog?.window?.let { GlassDialogs.frost(it) }
        val sharedPreferencesHelper = SharedPreferencesHelper(requireContext())
        val editTextApiKey = view.findViewById<TextInputEditText>(R.id.edit_text_title)
        val buttonSave = view.findViewById<MaterialButton>(R.id.button_saveapi)
        val buttonCancel = view.findViewById<MaterialButton>(R.id.button_cancelapi)

        buttonSave.setOnClickListener {
            val apiKey = editTextApiKey.text.toString().trim()
            if (apiKey.isNotBlank()) {
                val saved = sharedPreferencesHelper.saveApiKey("openrouter_api_key", apiKey)
                if (saved) {
                    viewModel.refreshApiKey()
                    AppToast.makeText(requireContext(), getString(R.string.api_key_saved), AppToast.LENGTH_SHORT).show()
                    dismiss()
                } else {
                    // Toasts are silent and the dialog stays open, so say why on the field itself.
                    editTextApiKey.error = getString(R.string.api_key_save_failed)
                }
            } else {
                editTextApiKey.error = getString(R.string.api_key_empty)
            }
        }

        buttonCancel.setOnClickListener {
            dismiss()
        }

        // Request focus and show keyboard automatically
        editTextApiKey.requestFocus()
      //  dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }
}
