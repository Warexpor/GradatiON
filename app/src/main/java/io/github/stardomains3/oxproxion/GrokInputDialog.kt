package io.github.stardomains3.oxproxion

import android.view.LayoutInflater
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

object GrokInputDialog {

    fun show(
        fragment: Fragment,
        title: String,
        hint: String,
        initialText: String,
        confirmText: String,
        multiline: Boolean = false,
        onConfirm: (String) -> Unit
    ) {
        val context = fragment.requireContext()
        val dialog = GlassAlertDialogBuilder(
            context,
            R.style.CustomMaterialAlertDialogTheme
        ).create()

        val sheet = LayoutInflater.from(context).inflate(R.layout.dialog_confirm_input, null)
        sheet.findViewById<TextView>(R.id.inputTitle).text = title
        val inputLayout = sheet.findViewById<TextInputLayout>(R.id.inputLayout)
        val inputField = sheet.findViewById<TextInputEditText>(R.id.inputField)
        inputLayout.hint = hint
        if (multiline) {
            inputField.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            inputField.minLines = 4
            inputField.maxLines = 10
            inputField.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        inputField.setText(initialText)
        inputField.setSelection(initialText.length)

        val confirmButton = sheet.findViewById<MaterialButton>(R.id.inputConfirm)
        confirmButton.text = confirmText

        sheet.findViewById<MaterialButton>(R.id.inputCancel).setOnClickListener {
            dialog.dismiss()
        }
        confirmButton.setOnClickListener {
            dialog.dismiss()
            onConfirm(inputField.text?.toString().orEmpty())
        }

        dialog.setView(sheet)
        // Own card on a transparent window, same as the Save API / max-tokens DialogFragments:
        // without sizeCard the window wraps content (too narrow) or runs edge to edge.
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.let {
            GlassDialogs.frost(it)
            GlassDialogs.sizeCard(it)
        }
        inputField.requestFocus()
    }
}
