package io.github.stardomains3.oxproxion

import android.graphics.Color import android.view.LayoutInflater import android.view.View import android.view.ViewGroup import android.view.WindowManager import android.widget.ImageView import android.widget.PopupWindow import android.widget.TextView import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable import androidx.recyclerview.widget.RecyclerView
class PresetAdapter( private val onItemClicked: (Preset) -> Unit, private val onItemEdit: (Preset) -> Unit, private val onItemDelete: (Preset) -> Unit ) : RecyclerView.Adapter<PresetAdapter.PresetVH>() {

    private val items = mutableListOf<Preset>()

    fun update(list: List<Preset>) {
        items.clear()
        items.addAll(list)
        // Collapse everything on refresh
        items.forEach { it.isExpanded = false }
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        if (from == to) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    fun getItems(): List<Preset> = items

    class PresetVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.textPresetTitle)
        val subtitleContainer: ViewGroup = itemView.findViewById(R.id.containerPresetSubtitle) // new
        val subtitle: TextView = itemView.findViewById(R.id.textPresetSubtitle)
        val edit: ImageView = itemView.findViewById(R.id.iconEditPreset)
        val expandIcon: ImageView = itemView.findViewById(R.id.iconExpand) // new
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PresetVH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_preset, parent, false)
        return PresetVH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: PresetVH, position: Int) {
        val preset = items[position]
        holder.title.text = preset.title
        holder.subtitle.text = presetSummary(holder.itemView.context, preset)
        holder.subtitleContainer.visibility = if (preset.isExpanded) View.VISIBLE else View.GONE
        holder.expandIcon.rotation = if (preset.isExpanded) 180f else 0f

        // Expand/collapse on chevron click only
        holder.expandIcon.setOnClickListener {
            preset.isExpanded = !preset.isExpanded
            notifyItemChanged(position)
        }

        // Apply preset on list item click (root view)
        holder.itemView.setOnClickListener { onItemClicked(preset) }

        // Edit menu
        holder.edit.setOnClickListener {
            showPresetPopupWindow(holder.edit, preset)
        }
    }

    private fun showPresetPopupWindow(anchorView: View, preset: Preset) {
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
        val editItem = menuView.findViewById<TextView>(R.id.menu_edit)
        val deleteItem = menuView.findViewById<TextView>(R.id.menu_delete)

        editItem.setOnClickListener {
            popupWindow.dismiss()
            onItemEdit(preset)  // Invokes your existing full-screen edit
        }

        deleteItem.setOnClickListener {
            popupWindow.dismiss()
            onItemDelete(preset)  // Invokes your existing delete
        }

        // Smart positioning (below or above icon based on space):
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
        val metrics = wm.maximumWindowMetrics
        val screenHeight = metrics.bounds.height()

        val spaceBelow = screenHeight - anchorY - anchorHeight
        val spaceAbove = anchorY

        val showAbove = spaceBelow < popupHeight && spaceAbove >= popupHeight

        if (showAbove) {
            popupWindow.showAsDropDown(anchorView, 0, -anchorHeight - popupHeight)
        } else {
            popupWindow.showAsDropDown(anchorView)
        }
        MenuDim.behind(popupWindow)
    }
}

/** The expanded row: model, system message, then the switches that are on, named as in the editor. */
internal fun presetSummary(context: android.content.Context, preset: Preset): String {
    val on = listOf(
        preset.streaming to R.string.preset_edit_streaming,
        preset.reasoning to R.string.preset_edit_reasoning,
        preset.conversationMode to R.string.preset_edit_conversation_mode,
        preset.tools to R.string.preset_edit_tools,
        preset.webSearch to R.string.preset_edit_web_search,
    ).filter { it.first }.joinToString(", ") { context.getString(it.second) }
    return listOf(preset.modelIdentifier, preset.systemMessage.title, on)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}