package io.github.stardomains3.oxproxion

import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

object GrokConfirmDialog {

    fun show(
        fragment: Fragment,
        title: String,
        message: String,
        confirmText: String,
        onConfirm: () -> Unit,
        destructive: Boolean = true,
        cancelText: String? = null,
        onCancel: (() -> Unit)? = null
    ) {
        val context = fragment.requireContext()
        val dialog = GlassAlertDialogBuilder(
            context,
            R.style.CustomMaterialAlertDialogTheme
        ).create()

        val sheet = LayoutInflater.from(context).inflate(R.layout.dialog_confirm_action, null)
        sheet.findViewById<TextView>(R.id.confirmTitle).text = title
        sheet.findViewById<TextView>(R.id.confirmMessage).text = message

        val actionButton = sheet.findViewById<MaterialButton>(R.id.confirmAction)
        actionButton.text = confirmText
        if (destructive) {
            actionButton.setTextColor(ContextCompat.getColor(context, R.color.delete_action))
            actionButton.background = ContextCompat.getDrawable(context, R.drawable.bg_glass_button)
        }

        val cancelButton = sheet.findViewById<MaterialButton>(R.id.confirmCancel)
        if (cancelText != null) cancelButton.text = cancelText
        cancelButton.setOnClickListener {
            dialog.dismiss()
            onCancel?.invoke()
        }
        actionButton.setOnClickListener {
            dialog.dismiss()
            onConfirm()
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
    }
}
