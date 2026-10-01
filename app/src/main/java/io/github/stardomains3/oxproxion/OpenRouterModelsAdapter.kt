package io.github.stardomains3.oxproxion

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.recyclerview.widget.RecyclerView

/** Catalog rows: a plus until the model is in your list, then a check. */
class OpenRouterModelsAdapter(
    private var models: List<LlmModel>,
    private val isAdded: (LlmModel) -> Boolean,
    private val onItemClicked: (LlmModel) -> Unit
) : RecyclerView.Adapter<OpenRouterModelsAdapter.ModelViewHolder>() {

    fun updateModels(newModels: List<LlmModel>) {
        models = newModels
        notifyDataSetChanged()
    }

    fun markAdded(apiIdentifier: String) {
        val i = models.indexOfFirst { it.apiIdentifier == apiIdentifier }
        if (i >= 0) notifyItemChanged(i)
    }

    class ModelViewHolder(val row: ModelRowViews) : RecyclerView.ViewHolder(row.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_model, parent, false)
        return ModelViewHolder(ModelRowViews(view))
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        val model = models[position]
        val added = isAdded(model)
        holder.row.bind(model, selected = false, trailingIcon = if (added) R.drawable.ic_check else R.drawable.ic_code_plus)
        holder.row.trailing.alpha = if (added) 1f else 0.7f
        holder.row.trailing.contentDescription = holder.itemView.context.getString(
            if (added) R.string.cd_model_added else R.string.model_picker_add
        )
        holder.itemView.setOnClickListener { onItemClicked(model) }
        holder.itemView.setOnLongClickListener {
            val ctx = holder.itemView.context
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://openrouter.ai/${model.apiIdentifier}".toUri()))
            } catch (_: Exception) {
                GlassNotice.show(ctx, ctx.getString(R.string.toast_open_browser_failed))
            }
            true
        }
    }

    override fun getItemCount() = models.size
}
