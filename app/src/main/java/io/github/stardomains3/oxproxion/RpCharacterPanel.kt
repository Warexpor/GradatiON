package io.github.stardomains3.oxproxion

import android.graphics.BitmapFactory
import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * The RP character panel: opened from the character chip. A header with who you're talking to,
 * then a 3x3 grid of tiles for everything about the scene (memory, voice, layout, wallpaper,
 * persona, style, lore, the card itself, a fresh chat), so none of it needs a trip through
 * Settings. There is no History tile: the characters list is how you move between chats.
 *
 * Solid on purpose: it holds a lot of small text and sits over a busy transcript, so it is an
 * opaque sheet with tiles one step off it, not glass. The chat behind stays sharp.
 */
object RpCharacterPanel {

    class Tile(
        @StringRes val label: Int,
        /** The drawing on the card; see [RpTileArt]. */
        val art: RpTileArt.Kind,
        val on: Boolean = false,
        /** A short state under the name (the layout's name, the voice, the persona). */
        val preview: String? = null,
        /** A state read out by TalkBack only, for tiles whose drawing already shows it (the layout). */
        val spoken: String? = null,
        /** A photo for the card's picture: the character's wallpaper, or your persona's portrait. */
        val image: java.io.File? = null,
        /** The persona's initial for its portrait. */
        val letter: String? = null,
        val onClick: () -> Unit
    )

    fun show(fragment: Fragment, character: RpCharacter?, title: String, subtitle: String, tiles: List<Tile>): BottomSheetDialog {
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
                // centerCrop keeps the aspect; the frame's oval outline does the rounding. A circular
                // RoundedBitmapDrawable stretched a non-square photo into the square instead.
                BitmapFactory.decodeFile(file.absolutePath)?.let { bmp ->
                    sheet.findViewById<ImageView>(R.id.rpPanelAvatar).apply {
                        setImageBitmap(bmp)
                        visibility = View.VISIBLE
                    }
                }
            }
        }

        val grid = sheet.findViewById<GridLayout>(R.id.rpPanelTiles)
        val ink = ContextCompat.getColor(ctx, R.color.xai_ink)
        val mute = ContextCompat.getColor(ctx, R.color.xai_mute)
        val gap = (5 * d).toInt()
        tiles.forEach { t ->
            // Every card is the same square, so the grid reads as one thing: the name top-left,
            // a one-line state under it, and a drawing of what the tile opens filling the rest.
            val fill = if (t.on) R.color.panel_tile_on else R.color.panel_tile
            val radius = 22 * d
            val card = object : FrameLayout(ctx) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val side = MeasureSpec.getSize(widthMeasureSpec)
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY))
                }
            }.apply {
                background = solidShape(ctx, fill, radiusPx = radius)
                foreground = pressRipple(ctx, radius)
                clipToOutline = true
                isClickable = true
                isFocusable = true
                contentDescription = listOfNotNull(ctx.getString(t.label), t.preview ?: t.spoken).joinToString(", ")
                // Leave the sheet up; ChatFragment parks it only while a destination needs the
                // window (a full-screen page or a popover under this dialog), then restores it.
                setOnClickListener { t.onClick() }
            }
            card.addView(RpTileArt(ctx, t.art, ink, ContextCompat.getColor(ctx, fill)).apply {
                photo = t.image?.let { f ->
                    BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 })
                }
                letter = t.letter
            }, FrameLayout.LayoutParams(-1, -1))
            val labels = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((14 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), 0)
            }
            card.addView(labels, FrameLayout.LayoutParams(-1, -2))
            labels.addView(TextView(ctx).apply {
                setText(t.label)
                setTextColor(ink)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, -2))
            if (!t.preview.isNullOrBlank()) {
                labels.addView(TextView(ctx).apply {
                    text = t.preview
                    setTextColor(mute)
                    textSize = 12.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-1, -2))
            }
            grid.addView(card, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(gap, gap, gap, gap)
            })
        }

        // The sheet runs under the navigation bar so its fill reaches the screen's bottom edge.
        val basePadding = sheet.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { v, insets ->
            v.updatePadding(bottom = basePadding + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
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

    internal fun solidShape(ctx: android.content.Context, color: Int, oval: Boolean = false, radiusPx: Float = 0f) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = if (oval) android.graphics.drawable.GradientDrawable.OVAL else android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(ContextCompat.getColor(ctx, color))
            if (!oval) cornerRadius = radiusPx
        }

    /** A tonal flash on press, drawn over the tile's art. */
    internal fun pressRipple(ctx: android.content.Context, radiusPx: Float): android.graphics.drawable.Drawable {
        val mask = solidShape(ctx, android.R.color.white, radiusPx = radiusPx)
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.popover_row_pressed)), null, mask
        )
    }

    /** The opaque sheet: rounded on top, no edge line, no transparency. */
    internal fun solidSheet(ctx: android.content.Context): android.graphics.drawable.Drawable {
        val r = ctx.resources.getDimension(R.dimen.glass_sheet_radius)
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(ContextCompat.getColor(ctx, R.color.panel_solid))
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }
}
