package io.github.stardomains3.oxproxion

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.text.toIntOrNull
import kotlin.text.trim

class TimeoutDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "TimeoutDialogFragment"
        private const val MIN_MINUTES = SETTINGS_TIMEOUT_MIN_MINUTES
        private const val MAX_MINUTES = SETTINGS_TIMEOUT_MAX_MINUTES
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.dialog_timeout, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { GlassDialogs.sizeCard(it) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // dim + rounded background like MaxTokensDialogFragment
        dialog?.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog?.window?.let { GlassDialogs.frost(it) }

        val prefs = SharedPreferencesHelper(requireContext())
        val editText = view.findViewById<TextInputEditText>(R.id.edit_text_timeout)
        val btnSave = view.findViewById<MaterialButton>(R.id.button_save_timeout)
        val btnCancel = view.findViewById<MaterialButton>(R.id.button_cancel_timeout)

        // pre‑fill with current value
        editText.setText(prefs.getTimeoutMinutes().toString())

        val layout = view.findViewById<TextInputLayout>(R.id.edit_text_layout_timeout)
        layout.clearErrorOnEdit()

        btnSave.setOnClickListener {
            val txt = editText.text?.toString()?.trim() ?: ""
            val minutes = txt.toIntOrNull()
            if (minutes != null && minutes in MIN_MINUTES..MAX_MINUTES) {
                prefs.saveTimeoutMinutes(minutes)
                dismiss()
            } else {
                layout.error = getString(R.string.timeout_error_range, MIN_MINUTES, MAX_MINUTES)
            }
        }

        btnCancel.setOnClickListener { dismiss() }

        // show numeric keypad automatically
        editText.requestFocus()
        // optional: force soft‑keyboard
        // dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }
}