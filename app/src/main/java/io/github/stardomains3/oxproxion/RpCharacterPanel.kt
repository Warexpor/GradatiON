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
 * The RP character panel: opened from the character chip. A header with who you're talking to,
 * a model line (the one place to change the RP model), then a grid of tiles for everything
 * about the scene (memory, history, persona, style, lore, the card itself), so none of it
 * needs a trip through Settings.
 *
 * Solid on purpose: it holds a lot of small text and sits over a busy transcript, so it is an
 * opaque sheet with an outline and tiles one step off it, not glass. The chat behind stays sharp.
 */
object RpCharacterPanel {

    class Tile(
        @StringRes val label: Int,
        @DrawableRes val icon: Int,
        val on: Boolean = false,
        /** Short live content shown instead of the glyph (the memory itself, the persona's name). */
        val preview: String? = null,
        /** A picture to show in the card (the character's wallpaper). */
        val image: java.io.File? = null,
        /** Round glass button in the header row instead of a card (quick actions). */
        val header: Boolean = false,
        val onClick: () -> Unit
    )

    fun show(
        fragment: Fragment,
        character: RpCharacter?,
        title: String,
        subtitle: String,
        /** The model this chat replies with. The line under the description is the one place to change it. */
        modelName: String,
        onModel: () -> Unit,
        tiles: List<Tile>
    ): BottomSheetDialog {
        val ctx = fragment.requireContext()
        val dialog = BottomSheetDialog(ctx, R.style.ThemeOverlay_Grokion_BottomSheet_Sharp)
        val sheet = LayoutInflater.from(ctx).inflate(R.layout.sheet_rp_character, null)
        val d = ctx.resources.displayMetrics.density
        sheet.background = solidSheet(ctx)

        sheet.findViewById<TextView>(R.id.rpPanelName).text = title
        sheet.findViewById<TextView>(R.id.rpPanelSubtitle).apply {
            text = subtitle
            visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        }
        sheet.findViewById<TextView>(R.id.rpPanelModelName).text = modelName
        sheet.findViewById<View>(R.id.rpPanelModel).apply {
            contentDescription = ctx.getString(R.string.rp_panel_model_a11y, modelName)
            setOnClickListener {
                dialog.dismiss()
                onModel()
            }
        }
        val frame = sheet.findViewById<View>(R.id.rpPanelAvatarFrame)
        frame.background = solidShape(ctx, R.color.panel_tile, oval = true)
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
                background = pressable(ctx, R.color.panel_tile, radiusPx = 24 * d)
                contentDescription = ctx.getString(t.label)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    t.onClick()
                }
            }, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()).apply { marginStart = (6 * d).toInt() })
        }
        val cards = tiles.filterNot { it.header }
        // A short last row would leave a hole; the last card stretches over the empty columns instead.
        val cols = grid.columnCount.coerceAtLeast(1)
        val shortBy = (cols - cards.size % cols) % cols
        val lastSpan = 1 + shortBy
        cards.forEachIndexed { i, t ->
            // A titled glass card (c.ai layout, our glass): name top-left, a quiet preview or a
            // large glyph bottom-right.
            val card = android.widget.FrameLayout(ctx).apply {
                background = pressable(ctx, if (t.on) R.color.panel_tile_on else R.color.panel_tile, radiusPx = 22 * d)
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
            val picture = t.image?.let { f ->
                BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 })
            }
            if (picture != null) {
                card.addView(ImageView(ctx).apply {
                    setImageDrawable(
                        androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(resources, centerCrop(picture, 2.4f))
                            .apply { cornerRadius = 12 * d }
                    )
                    scaleType = ImageView.ScaleType.FIT_XY
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, android.widget.FrameLayout.LayoutParams(-1, (44 * d).toInt(), Gravity.BOTTOM))
            } else if (!t.preview.isNullOrBlank()) {
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
                GridLayout.spec(GridLayout.UNDEFINED, if (i == cards.lastIndex) lastSpan else 1, 1f)
            ).apply {
                width = 0
                height = (104 * d).toInt()
                setMargins(gap, gap, gap, gap)
            })
        }

        dialog.setContentView(sheet)
        // Tall enough for three rows of cards: open fully instead of peeking.
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { container ->
            container.background = null
            container.backgroundTintList = null
        }
        return dialog
    }

    private fun solidShape(ctx: android.content.Context, color: Int, oval: Boolean = false, radiusPx: Float = 0f) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = if (oval) android.graphics.drawable.GradientDrawable.OVAL else android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(ContextCompat.getColor(ctx, color))
            if (!oval) cornerRadius = radiusPx
        }

    /** A solid fill with a tonal flash on press. */
    private fun pressable(ctx: android.content.Context, color: Int, radiusPx: Float): android.graphics.drawable.Drawable {
        val fill = solidShape(ctx, color, radiusPx = radiusPx)
        val mask = solidShape(ctx, android.R.color.white, radiusPx = radiusPx)
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.popover_row_pressed)), fill, mask
        )
    }

    /** The opaque sheet: rounded on top, a hairline edge, no transparency. */
    private fun solidSheet(ctx: android.content.Context): android.graphics.drawable.Drawable {
        val r = ctx.resources.getDimension(R.dimen.glass_sheet_radius)
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(ContextCompat.getColor(ctx, R.color.panel_solid))
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke((1.5f * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1), ContextCompat.getColor(ctx, R.color.panel_edge))
        }
    }

    /** Crop [src] around its center to [aspect] (width / height), so a rounded thumbnail isn't squashed. */
    private fun centerCrop(src: android.graphics.Bitmap, aspect: Float): android.graphics.Bitmap {
        val srcAspect = src.width / src.height.toFloat()
        return if (srcAspect > aspect) {
            val w = (src.height * aspect).toInt().coerceAtLeast(1)
            android.graphics.Bitmap.createBitmap(src, (src.width - w) / 2, 0, w, src.height)
        } else {
            val h = (src.width / aspect).toInt().coerceAtLeast(1)
            android.graphics.Bitmap.createBitmap(src, 0, (src.height - h) / 2, src.width, h)
        }
    }
}
