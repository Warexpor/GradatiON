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

    class Tile(
        @StringRes val label: Int,
        @DrawableRes val icon: Int,
        val on: Boolean = false,
        /** Short live content shown instead of the glyph (the memory itself, the persona's name). */
        val preview: String? = null,
        /** Round glass button in the header row instead of a card (quick actions). */
        val header: Boolean = false,
        val onClick: () -> Unit
    )

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
        val mute = ContextCompat.getColor(ctx, R.color.xai_mute)
        val gap = (5 * d).toInt()
        val actions = sheet.findViewById<LinearLayout>(R.id.rpPanelActions)
        tiles.filter { it.header }.forEach { t ->
            actions.addView(ImageView(ctx).apply {
                setImageResource(t.icon)
                imageTintList = android.content.res.ColorStateList.valueOf(ink)
                val pad = (12 * d).toInt()
                setPadding(pad, pad, pad, pad)
                background = GlassDrawable.control(ctx, 44f)
                contentDescription = ctx.getString(t.label)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    t.onClick()
                }
            }, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()).apply { marginStart = (6 * d).toInt() })
        }
        tiles.filterNot { it.header }.forEach { t ->
            // A titled glass card (c.ai layout, our glass): name top-left, a quiet preview or a
            // large glyph bottom-right.
            val card = android.widget.FrameLayout(ctx).apply {
                background = GlassDrawable(ctx, ContextCompat.getColor(ctx, R.color.glass_control_tint), 22 * d)
                    .also {
                        it.interactive = true
                        it.selectedTint = ContextCompat.getColor(ctx, R.color.glass_control_solid_tint)
                    }
                isSelected = t.on
                isClickable = true
                isFocusable = true
                contentDescription = listOfNotNull(ctx.getString(t.label), t.preview).joinToString(", ")
                setPadding((14 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
                setOnClickListener {
                    dialog.dismiss()
                    t.onClick()
                }
            }
            card.addView(TextView(ctx).apply {
                setText(t.label)
                setTextColor(ink)
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            if (!t.preview.isNullOrBlank()) {
                card.addView(TextView(ctx).apply {
                    text = t.preview
                    setTextColor(mute)
                    textSize = 13f
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START))
            } else {
                card.addView(ImageView(ctx).apply {
                    setImageResource(t.icon)
                    imageTintList = android.content.res.ColorStateList.valueOf(if (t.on) ink else mute)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, android.widget.FrameLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt(), Gravity.BOTTOM or Gravity.END))
            }
            grid.addView(card, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = (104 * d).toInt()
                setMargins(gap, gap, gap, gap)
            })
        }

        dialog.setContentView(sheet)
        dialog.show()
        GlassChrome.glassDialog(dialog)
        return dialog
    }
}
