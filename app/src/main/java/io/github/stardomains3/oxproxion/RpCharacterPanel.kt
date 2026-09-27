package io.github.stardomains3.oxproxion

import android.graphics.BitmapFactory
import android.graphics.Outline
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * The RP character panel: opened from the character pill. A header with who you're talking to,
 * then a grid of glass tiles for everything about the scene (memory, history, persona, style,
 * lore, the card itself), so none of it needs a trip through Settings.
 */
object RpCharacterPanel {

    class Tile(@StringRes val label: Int, @DrawableRes val icon: Int, val on: Boolean = false, val onClick: () -> Unit)

    fun show(fragment: Fragment, character: RpCharacter?, title: String, subtitle: String, tiles: List<Tile>): BottomSheetDialog {
        val ctx = fragment.requireContext()
        val dialog = BottomSheetDialog(ctx, R.style.ThemeOverlay_Grokion_BottomSheet)
        val sheet = LayoutInflater.from(ctx).inflate(R.layout.sheet_rp_character, null)
        sheet.background = GlassDrawable.sheet(ctx, topOnly = true)
        val d = ctx.resources.displayMetrics.density

        sheet.findViewById<TextView>(R.id.rpPanelName).text = title
        sheet.findViewById<TextView>(R.id.rpPanelSubtitle).apply {
            text = subtitle
            visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        }
        val frame = sheet.findViewById<View>(R.id.rpPanelAvatarFrame)
        frame.background = GlassDrawable.control(ctx, 56f)
        frame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        frame.clipToOutline = true
        sheet.findViewById<TextView>(R.id.rpPanelMonogram).text = title.trim().take(1).uppercase().ifEmpty { "?" }
        character?.let { c ->
            val file = RpAvatarStorage.avatarFile(ctx, c.id)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.let { bmp ->
                    sheet.findViewById<ImageView>(R.id.rpPanelAvatar).apply {
                        setImageDrawable(
                            androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(resources, bmp)
                                .apply { isCircular = true }
                        )
                        visibility = View.VISIBLE
                    }
                }
            }
        }

        val grid = sheet.findViewById<GridLayout>(R.id.rpPanelTiles)
        val ink = ContextCompat.getColor(ctx, R.color.xai_ink)
        tiles.forEach { t ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, (6 * d).toInt(), 0, (10 * d).toInt())
                isClickable = true
                isFocusable = true
                contentDescription = ctx.getString(t.label)
                isSelected = t.on
                setOnClickListener {
                    dialog.dismiss()
                    t.onClick()
                }
            }
            val disc = ImageView(ctx).apply {
                setImageResource(t.icon)
                imageTintList = android.content.res.ColorStateList.valueOf(ink)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = (17 * d).toInt()
                setPadding(pad, pad, pad, pad)
                background = GlassDrawable(
                    ctx,
                    ContextCompat.getColor(ctx, R.color.glass_control_tint),
                    18 * d
                ).also {
                    it.interactive = true
                    it.selectedTint = ContextCompat.getColor(ctx, R.color.glass_control_solid_tint)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isDuplicateParentStateEnabled = true
            }
            cell.addView(disc, LinearLayout.LayoutParams((58 * d).toInt(), (58 * d).toInt()))
            cell.addView(TextView(ctx).apply {
                setText(t.label)
                setTextColor(ink)
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6 * d).toInt()
            })
            grid.addView(cell, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0 })
        }

        dialog.setContentView(sheet)
        dialog.show()
        GlassChrome.glassDialog(dialog)
        return dialog
    }
}
