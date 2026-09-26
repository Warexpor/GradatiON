package io.github.stardomains3.oxproxion

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView

class BotModelAdapter(
    private var models: MutableList<LlmModel>,
    private var currentModelId: String?,
    private val onItemClicked: (LlmModel) -> Unit,
    private val onItemEdit: (LlmModel) -> Unit,
    private val onItemDelete: (LlmModel) -> Unit
) : RecyclerView.Adapter<BotModelAdapter.ModelViewHolder>() {

    fun updateCurrentModel(newModelId: String?) {
        currentModelId = newModelId
        notifyDataSetChanged()
    }

    class ModelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rowRoot: FrameLayout = view.findViewById(R.id.modelRowRoot)
        val modelName: TextView = view.findViewById(R.id.textModelName)
        val modelSubtitle: TextView = view.findViewById(R.id.textModelSubtitle)
        val modelBadges: TextView = view.findViewById(R.id.textModelBadges)
        val modelIcon: ImageView = view.findViewById(R.id.iconModelType)
        val sourceIcon: ImageView = view.findViewById(R.id.iconModelSource)
        val iconSelected: ImageView = view.findViewById(R.id.iconSelected)
        val editIcon: ImageView = view.findViewById(R.id.iconEdit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_model, parent, false)
        return ModelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        val model = models[position]
        val context = holder.itemView.context
        val ink = ContextCompat.getColor(context, R.color.xai_ink)
        val mute = ContextCompat.getColor(context, R.color.xai_mute)
        val isSelected = model.apiIdentifier == currentModelId

        holder.modelName.text = model.displayName
        holder.modelSubtitle.text = model.apiIdentifier
        holder.modelName.setTextColor(ink)

        val iconRes = when {
            model.isTranscription -> R.drawable.ic_mic
            model.isImageGenerationCapable -> R.drawable.ic_palette
            model.isVisionCapable -> R.drawable.ic_vision
            else -> R.drawable.ic_person
        }
        holder.modelIcon.setImageResource(iconRes)
        holder.modelIcon.imageTintList = ColorStateList.valueOf(ink)

        val sourceIconRes = if (model.isLANModel) R.drawable.ic_lan2 else R.drawable.ic_cloudnew2
        holder.sourceIcon.setImageResource(sourceIconRes)
        holder.sourceIcon.imageTintList = ColorStateList.valueOf(mute)

        val badges = buildBadgeLabels(context, model)
        if (badges.isEmpty()) {
            holder.modelBadges.isVisible = false
        } else {
            holder.modelBadges.isVisible = true
            holder.modelBadges.text = badges.joinToString(separator = " · ")
        }

        holder.rowRoot.setBackgroundResource(
            if (isSelected) R.drawable.bg_model_picker_row_selected else R.drawable.bg_model_picker_row
        )
        holder.iconSelected.isVisible = isSelected

        holder.itemView.setOnClickListener { onItemClicked(model) }
        holder.itemView.setOnLongClickListener {
            val apiIdentifier = model.apiIdentifier
            if (apiIdentifier.startsWith("@preset")) {
                AppToast.makeText(context, "Presets don't have a web page", AppToast.LENGTH_SHORT).show()
            } else {
                val url = "https://openrouter.ai/$apiIdentifier"
                val intent = Intent(Intent.ACTION_VIEW).setData(url.toUri())
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    AppToast.makeText(context, "Could not open browser.", AppToast.LENGTH_SHORT).show()
                }
            }
            true
        }

        holder.editIcon.setOnClickListener {
            if (model.apiIdentifier == "openrouter/free") {
                AppToast.makeText(
                    context,
                    "This is the permanent default model and cannot be edited or deleted.",
                    AppToast.LENGTH_SHORT
                ).show()
            } else {
                showModelPopupWindow(holder.editIcon, model)
            }
        }
    }

    private fun buildBadgeLabels(context: android.content.Context, model: LlmModel): List<String> {
        val labels = mutableListOf<String>()
        if (model.isFree) {
            labels.add(context.getString(R.string.model_picker_filter_free))
        }
        val capability = when {
            model.isTranscription -> context.getString(R.string.model_picker_filter_transcribe)
            model.isImageGenerationCapable -> context.getString(R.string.model_picker_filter_image)
            model.isVisionCapable -> context.getString(R.string.model_picker_filter_vision)
            model.isReasoningCapable -> context.getString(R.string.model_picker_badge_reasoning)
            else -> null
        }
        if (capability != null && labels.size < 2) {
            labels.add(capability)
        }
        return labels.take(2)
    }

    override fun getItemCount() = models.size

    private fun showModelPopupWindow(anchorView: View, model: LlmModel) {
        val inflater = LayoutInflater.from(anchorView.context)
        val menuView = inflater.inflate(R.layout.menu_popup_layout, null)

        val popupWindow = PopupWindow(
            menuView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        popupWindow.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        popupWindow.isOutsideTouchable = true
        val context = anchorView.context
        val rootView = (context as android.app.Activity).window.decorView
            .findViewById<ViewGroup>(android.R.id.content)
        val dimView = View(context).apply {
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        rootView.addView(dimView)

        popupWindow.setOnDismissListener {
            rootView.removeView(dimView)
        }
        val editItem = menuView.findViewById<TextView>(R.id.menu_edit)
        val deleteItem = menuView.findViewById<TextView>(R.id.menu_delete)

        editItem.setOnClickListener {
            popupWindow.dismiss()
            onItemEdit(model)
        }

        deleteItem.setOnClickListener {
            popupWindow.dismiss()
            onItemDelete(model)
        }

        menuView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupHeight = menuView.measuredHeight

        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        val anchorY = location[1]
        val anchorHeight = anchorView.height

        val wm = context.getSystemService(WindowManager::class.java)
        val screenHeight = wm.maximumWindowMetrics.bounds.height()

        val spaceBelow = screenHeight - anchorY - anchorHeight
        val spaceAbove = anchorY

        val showAbove = spaceBelow < popupHeight && spaceAbove >= popupHeight

        if (showAbove) {
            popupWindow.showAsDropDown(anchorView, 0, -anchorHeight - popupHeight)
        } else {
            popupWindow.showAsDropDown(anchorView)
        }
    }

    fun updateModels(newModels: MutableList<LlmModel>) {
        models = newModels
        notifyDataSetChanged()
    }
}
