package io.github.stardomains3.oxproxion

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView

class LanModelsAdapter(
    private var models: List<LlmModel>,
    private val isLlamaCppProvider: Boolean,
    private val isModelInLibrary: (String) -> Boolean,
    private val isModelSelected: (String) -> Boolean = { false },
    private val onItemClicked: (LlmModel) -> Unit,
    private val onEjectClicked: ((LlmModel) -> Unit)? = null,
    private val onLoadClicked: ((LlmModel) -> Unit)? = null
) : RecyclerView.Adapter<LanModelsAdapter.ModelViewHolder>() {

    private val loadingModels = mutableSetOf<String>()

    fun updateModels(newModels: List<LlmModel>) {
        models = newModels
        notifyDataSetChanged()
    }

    fun currentCount() = models.size

    fun refreshAddedStates() {
        notifyDataSetChanged()
    }

    fun setLoadingState(modelId: String, isLoading: Boolean) {
        if (isLoading) {
            loadingModels.add(modelId)
        } else {
            loadingModels.remove(modelId)
        }
        notifyDataSetChanged()
    }

    fun animateAdded(modelId: String) {
        val position = models.indexOfFirst { it.apiIdentifier == modelId }
        if (position >= 0) {
            notifyItemChanged(position, PAYLOAD_ADDED_ANIM)
        }
    }

    class ModelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val modelId: TextView = view.findViewById(R.id.textModelName)
        val modelName: TextView = view.findViewById(R.id.textModelDisplayName)
        val modelIcon: ImageView = view.findViewById(R.id.iconModelType)
        val ejectButton: ImageButton = view.findViewById(R.id.btnEject)
        val loadButton: ImageButton = view.findViewById(R.id.btnLoad)
        val progressBar: ProgressBar = view.findViewById(R.id.progressLoading)
        val iconAdded: ImageView = view.findViewById(R.id.iconAdded)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_lan_model, parent, false)
        return ModelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        bindViewHolder(holder, models[position], emptyList())
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_ADDED_ANIM)) {
            playAddedIconPop(holder.iconAdded)
            return
        }
        bindViewHolder(holder, models[position], payloads)
    }

    private fun bindViewHolder(holder: ModelViewHolder, model: LlmModel, @Suppress("UNUSED_PARAMETER") payloads: List<Any>) {
        holder.modelId.text = model.apiIdentifier
        // The model in use says so in words; the check alone only means "added".
        val inUse = isModelSelected(model.apiIdentifier)
        val inUseLabel = holder.itemView.context.getString(R.string.lan_model_in_use)
        holder.modelName.text = when {
            !inUse -> model.displayName
            model.displayName == model.apiIdentifier -> inUseLabel
            else -> "$inUseLabel · ${model.displayName}"
        }
        holder.modelIcon.setImageResource(ModelBrands.of(model)?.icon ?: R.drawable.ic_local_network)

        val isActivelyLoading = loadingModels.contains(model.apiIdentifier)
        val isAdded = isModelInLibrary(model.apiIdentifier)

        holder.progressBar.isVisible = isActivelyLoading
        holder.ejectButton.isVisible = isLlamaCppProvider && model.isLoaded && !isActivelyLoading && onEjectClicked != null
        holder.loadButton.isVisible = isLlamaCppProvider && !model.isLoaded && !isActivelyLoading && onLoadClicked != null
        holder.iconAdded.isVisible = isAdded && !isActivelyLoading
        if (isAdded) {
            holder.iconAdded.scaleX = 1f
            holder.iconAdded.scaleY = 1f
        }

        holder.ejectButton.setOnClickListener { onEjectClicked?.invoke(model) }
        holder.loadButton.setOnClickListener { onLoadClicked?.invoke(model) }

        holder.itemView.setOnClickListener {
            playTapFeedback(holder.itemView)
            if (isModelInLibrary(model.apiIdentifier)) {
                playAddedIconPop(holder.iconAdded)
            }
            onItemClicked(model)
        }
    }

    override fun getItemCount() = models.size

    private fun playTapFeedback(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        view.animate().cancel()
        view.animate()
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(70)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(110)
                    .start()
            }
            .start()
    }

    private fun playAddedIconPop(icon: ImageView) {
        icon.isVisible = true
        icon.animate().cancel()
        icon.scaleX = 0.4f
        icon.scaleY = 0.4f
        icon.alpha = 0f
        icon.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(180)
            .start()
    }

    companion object {
        private const val PAYLOAD_ADDED_ANIM = "added_anim"
    }
}
