package io.github.stardomains3.oxproxion

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible

/** One model row (`list_item_model`), shared by Your models and the catalogs. */
class ModelRowViews(val root: View) {
    val icon: ImageView = root.findViewById(R.id.iconModelType)
    val monogram: TextView = root.findViewById(R.id.textModelMonogram)
    val name: TextView = root.findViewById(R.id.textModelName)
    val subtitle: TextView = root.findViewById(R.id.textModelSubtitle)
    val trailing: ImageView = root.findViewById(R.id.iconSelected)

    /** [trailingIcon] 0 hides the trailing mark. */
    fun bind(model: LlmModel, selected: Boolean, @DrawableRes trailingIcon: Int = 0) {
        val ctx = root.context
        name.text = ModelNames.withoutProvider(model.displayName, model.apiIdentifier)
        subtitle.text = ModelRow.subtitle(ctx, model)
        ModelRow.bindMark(icon, monogram, model)
        root.isSelected = selected
        trailing.isVisible = trailingIcon != 0
        if (trailingIcon != 0) trailing.setImageResource(trailingIcon)
    }
}

object ModelRow {

    /** The maker's mark, or the maker's first letter when we have no mark for it. */
    fun bindMark(icon: ImageView, monogram: TextView, model: LlmModel) {
        val mark = ModelBrands.of(model)?.icon ?: if (model.isLANModel) R.drawable.ic_local_network else 0
        if (mark != 0) {
            icon.setImageResource(mark)
            icon.isVisible = true
            monogram.isVisible = false
        } else {
            icon.isVisible = false
            monogram.isVisible = true
            monogram.text = monogramFor(model)
        }
    }

    fun monogramFor(model: LlmModel): String {
        val source = ModelNames.providerOf(model.apiIdentifier)
            ?: ModelNames.withoutProvider(model.displayName, model.apiIdentifier)
        return source.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    }

    /** "Anthropic · Vision · Free": who makes it, then only what sets it apart. */
    fun subtitle(ctx: Context, model: LlmModel): String {
        val parts = ArrayList<String>(4)
        parts += when {
            model.isLANModel -> ctx.getString(R.string.popover_model_local)
            else -> ModelBrands.of(model)?.name ?: providerLabel(model)
                ?: ctx.getString(R.string.popover_model_cloud)
        }
        if (model.isTranscription) parts += ctx.getString(R.string.popover_cap_audio)
        else if (model.isImageGenerationCapable) parts += ctx.getString(R.string.popover_cap_image)
        else if (model.isVisionCapable) parts += ctx.getString(R.string.popover_cap_vision)
        if (model.isFree && !model.isLANModel) parts += ctx.getString(R.string.popover_cap_free)
        return parts.joinToString(" · ")
    }

    /** "Anthropic: Claude" → "Anthropic"; else the id's provider slug. */
    private fun providerLabel(model: LlmModel): String? {
        val colon = model.displayName.indexOf(':')
        if (colon > 0) return model.displayName.substring(0, colon).trim().ifBlank { null }
        return ModelNames.providerOf(model.apiIdentifier)
            ?.replaceFirstChar { it.uppercase() }
    }
}

/** The one filter row the model lists share; a single choice keeps it to one line. */
enum class ModelFilter(val label: Int) {
    ALL(R.string.model_picker_filter_all),
    FREE(R.string.model_picker_filter_free),
    VISION(R.string.model_picker_filter_vision),
    IMAGE(R.string.model_picker_filter_image),
    AUDIO(R.string.model_picker_filter_transcribe),
    LOCAL(R.string.model_picker_filter_local);

    fun matches(m: LlmModel) = when (this) {
        ALL -> true
        LOCAL -> m.isLANModel
        FREE -> m.isFree
        VISION -> m.isVisionCapable
        IMAGE -> m.isImageGenerationCapable
        AUDIO -> m.isTranscription
    }

    companion object {
        /** Reads the older type + price prefs, which stored the two separately. */
        fun fromPrefs(type: String?, cost: String?): ModelFilter = when {
            cost == "FREE" -> FREE
            type == "LOCAL" -> LOCAL
            type == "VISION" -> VISION
            type == "IMAGE_GEN" -> IMAGE
            type == "TRANSCRIPTION" -> AUDIO
            else -> ALL
        }

        fun typePref(f: ModelFilter) = when (f) {
            VISION -> "VISION"
            IMAGE -> "IMAGE_GEN"
            AUDIO -> "TRANSCRIPTION"
            LOCAL -> "LOCAL"
            else -> "ALL"
        }

        fun costPref(f: ModelFilter) = if (f == FREE) "FREE" else "ALL"
    }
}

/**
 * Sort chip, a hairline, then the filter chips, in one scrolling line. Glass capsules that
 * light up when chosen, the same material as the composer's model pill.
 */
class ModelFilterChips(
    private val container: LinearLayout,
    newestFirst: Boolean,
    selected: ModelFilter,
    /** Which filters this list offers; the OpenRouter catalog has no local models. */
    filters: List<ModelFilter> = ModelFilter.entries,
    private val onSort: (newestFirst: Boolean) -> Unit,
    private val onFilter: (ModelFilter) -> Unit,
) {
    private val ctx = container.context
    private val density = ctx.resources.displayMetrics.density
    private var newest = newestFirst
    private var current = selected
    private val chips = LinkedHashMap<ModelFilter, TextView>()
    private val sortChip: TextView

    init {
        container.removeAllViews()
        sortChip = chip(sortLabel(), R.drawable.ic_sort).apply {
            contentDescription = ctx.getString(R.string.model_sort_a11y, sortLabel())
            setOnClickListener {
                newest = !newest
                text = sortLabel()
                contentDescription = ctx.getString(R.string.model_sort_a11y, sortLabel())
                onSort(newest)
            }
        }
        container.addView(sortChip)
        container.addView(View(ctx).apply {
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.xai_hairline))
        }, LinearLayout.LayoutParams((1 * density).toInt().coerceAtLeast(1), (20 * density).toInt()).apply {
            marginStart = (4 * density).toInt()
            marginEnd = (10 * density).toInt()
            gravity = android.view.Gravity.CENTER_VERTICAL
        })
        for (f in filters) {
            val c = chip(ctx.getString(f.label), 0)
            c.setOnClickListener {
                if (current == f) return@setOnClickListener
                current = f
                refresh()
                onFilter(f)
            }
            chips[f] = c
            container.addView(c)
        }
        refresh()
    }

    fun select(f: ModelFilter) {
        current = f
        refresh()
    }

    private fun sortLabel() = ctx.getString(if (newest) R.string.model_picker_sort_newest else R.string.model_picker_sort_az)

    private fun refresh() {
        val ink = ContextCompat.getColor(ctx, R.color.xai_ink)
        val body = ContextCompat.getColor(ctx, R.color.xai_body)
        chips.forEach { (f, c) ->
            val on = f == current
            c.isSelected = on
            c.setTextColor(if (on) ink else body)
        }
    }

    private fun chip(label: CharSequence, @DrawableRes icon: Int): TextView = TextView(ctx).apply {
        text = label
        textSize = 14f
        setTextColor(ContextCompat.getColor(ctx, R.color.xai_ink))
        gravity = android.view.Gravity.CENTER
        minWidth = (52 * density).toInt()
        includeFontPadding = false
        val padH = (14 * density).toInt()
        setPadding(if (icon != 0) (11 * density).toInt() else padH, 0, padH, 0)
        background = ContextCompat.getDrawable(ctx, R.drawable.bg_model_pill)
        stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(ctx, R.animator.glass_press)
        isClickable = true
        isFocusable = true
        if (icon != 0) {
            val d = ContextCompat.getDrawable(ctx, icon)?.mutate()
            val s = (16 * density).toInt()
            d?.setBounds(0, 0, s, s)
            d?.setTint(ContextCompat.getColor(ctx, R.color.xai_ink))
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = (6 * density).toInt()
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (36 * density).toInt()).apply {
            marginEnd = (8 * density).toInt()
        }
    }
}
