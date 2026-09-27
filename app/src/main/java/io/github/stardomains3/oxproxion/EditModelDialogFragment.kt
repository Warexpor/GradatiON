package io.github.stardomains3.oxproxion

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.core.graphics.toColorInt
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.widget.SwitchCompat
import kotlin.toString

class EditModelDialogFragment : DialogFragment() {

    var onModelAdded: ((LlmModel) -> Unit)? = null
    var onModelUpdated: ((LlmModel, LlmModel) -> Unit)? = null

    private var existingModel: LlmModel? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = GlassAlertDialogBuilder(requireActivity())
        val view = layoutInflater.inflate(R.layout.dialog_add_model, null)

        val editName      = view.findViewById<EditText>(R.id.editModelName)
        val editApiId     = view.findViewById<EditText>(R.id.editApiIdentifier)
        val switchVision  = view.findViewById<SwitchCompat>(R.id.switchVisionCapable)
        val switchReason  = view.findViewById<SwitchCompat>(R.id.switchReasoningCapable)
        val switchLan     = view.findViewById<SwitchCompat>(R.id.switchLanModel)
        val switchImage   = view.findViewById<SwitchCompat>(R.id.switchImageGen)
        val switchTranscription = view.findViewById<SwitchCompat>(R.id.switchTranscription) // NEW
        val switchIsFree  = view.findViewById<SwitchCompat>(R.id.switchIsFree)

        listOf(switchVision, switchReason, switchLan, switchImage, switchTranscription, switchIsFree).forEach {
            it.applyGrokionSwitchStyle()
        }

        /* ----------  restore existing model  ---------- */
        existingModel = arguments?.let { args ->
            val dn = args.getString("displayName")
            val id = args.getString("apiIdentifier")
            val created = args.getLong("created", 0L)
            val isFree = args.getBoolean("isFree", false)
            val isImageGen = args.getBoolean("isImageGenerationCapable", false)
            val isTranscription = args.getBoolean("isTranscription", false) // NEW
            if (!dn.isNullOrBlank() && !id.isNullOrBlank()) {
                LlmModel(
                    displayName  = dn,
                    apiIdentifier = id,
                    isVisionCapable = args.getBoolean("isVisionCapable", false),
                    isReasoningCapable = args.getBoolean("isReasoningCapable", false),
                    isImageGenerationCapable = isImageGen,
                    isTranscription = isTranscription, // NEW
                    created = created,
                    isLANModel = args.getBoolean("isLANModel", false),
                    isFree = isFree
                )
            } else null
        }

        existingModel?.let { m ->
            editName.setText(m.displayName)
            editApiId.setText(m.apiIdentifier)
            switchVision.isChecked = m.isVisionCapable
            switchReason.isChecked = m.isReasoningCapable
            switchLan.isChecked    = m.isLANModel
            switchImage.isChecked  = m.isImageGenerationCapable
            switchTranscription.isChecked = m.isTranscription // NEW
            switchIsFree.isChecked = m.isFree
            builder.setTitle(R.string.edit_model_title)
        } ?: builder.setTitle(R.string.add_model_title)

        /* ----------  buttons  ---------- */
        builder.setView(view)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = editName.text.toString().trim()
                val id   = editApiId.text.toString().trim()

                if (name.isBlank() || id.isBlank()) return@setPositiveButton

                val vision    = switchVision.isChecked
                val reasoning = switchReason.isChecked
                val isLan     = switchLan.isChecked
                val isImage   = switchImage.isChecked
                val isTranscription = switchTranscription.isChecked // NEW
                val isFree    = switchIsFree.isChecked

                val createdTimestamp = existingModel?.created ?: (System.currentTimeMillis() / 1000)

                val newModel = LlmModel(
                    displayName  = name,
                    apiIdentifier= id,
                    isVisionCapable     = vision,
                    isImageGenerationCapable = isImage,
                    isReasoningCapable  = reasoning,
                    isTranscription = isTranscription, // NEW
                    created = createdTimestamp,
                    isLANModel         = isLan,
                    isFree = isFree
                )

                existingModel?.let { old -> onModelUpdated?.invoke(old, newModel) }
                    ?: onModelAdded?.invoke(newModel)
            }
            .setNegativeButton(R.string.action_cancel, null)

        return builder.create()
    }
}
