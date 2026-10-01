package io.github.stardomains3.oxproxion

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.recyclerview.widget.RecyclerView

/** Your models: tap to use, long-press for Edit / Open page / Remove. */
class BotModelAdapter(
    private var models: MutableList<LlmModel>,
    private var currentModelId: String?,
    private val onItemClicked: (LlmModel) -> Unit,
    private val onItemOptions: (LlmModel) -> Unit
) : RecyclerView.Adapter<BotModelAdapter.ModelViewHolder>() {

    fun updateCurrentModel(newModelId: String?) {
        currentModelId = newModelId
        notifyDataSetChanged()
    }

    class ModelViewHolder(val row: ModelRowViews) : RecyclerView.ViewHolder(row.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_model, parent, false)
        return ModelViewHolder(ModelRowViews(view))
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        val model = models[position]
        val selected = model.apiIdentifier == currentModelId
        holder.row.bind(model, selected, trailingIcon = if (selected) R.drawable.ic_check else 0)
        val item = holder.itemView
        item.setOnClickListener { onItemClicked(model) }
        item.setOnLongClickListener {
            onItemOptions(model)
            true
        }
        // No visible menu button on the row, so name the long-press for TalkBack.
        ViewCompat.replaceAccessibilityAction(
            item,
            AccessibilityActionCompat.ACTION_LONG_CLICK,
            item.context.getString(R.string.model_options_a11y)
        ) { _, _ -> onItemOptions(model); true }
    }

    override fun getItemCount() = models.size

    fun updateModels(newModels: MutableList<LlmModel>) {
        models = newModels
        notifyDataSetChanged()
    }
}
