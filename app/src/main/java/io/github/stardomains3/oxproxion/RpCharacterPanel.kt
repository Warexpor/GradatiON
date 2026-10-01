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
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import com.google.android.material.bottomsheet.BottomSheetBehavior

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

    /**
     * The panel's content, not yet on screen; [Host] puts it there. It is a view in the chat's own
     * tree, not a dialog window, so a full-screen page can slide over it and leave it open.
     */
    fun content(fragment: Fragment, character: RpCharacter?, title: String, subtitle: String, tiles: List<Tile>): View {
        val ctx = fragment.requireContext()
        val sheet = LayoutInflater.from(ctx).inflate(R.layout.sheet_rp_character, null)
        val d = ctx.resources.displayMetrics.density

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
                val avatarView = sheet.findViewById<ImageView>(R.id.rpPanelAvatar)
                val edgePx = (56 * d).toInt().coerceAtLeast(1)
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) { decodeAvatarThumb(file.absolutePath, edgePx) }
                    if (!fragment.isAdded || bmp == null) return@launch
                    avatarView.setImageBitmap(bmp)
                    avatarView.visibility = View.VISIBLE
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
                // The sheet stays up under whatever page this opens.
                setOnClickListener { t.onClick() }
            }
            val art = RpTileArt(ctx, t.art, ink, ContextCompat.getColor(ctx, fill)).apply { letter = t.letter }
            // A wallpaper is a full-size photo: decoding it here would hold the panel's opening on the main thread.
            t.image?.let { f ->
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 })
                    }
                    if (fragment.isAdded) art.photo = bmp
                }
            }
            card.addView(art, FrameLayout.LayoutParams(-1, -1))
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
                    textSize = 13f
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

        return sheet
    }

    /** Shows [content] as a bottom sheet over [root]: a scrim, drag to dismiss, back handled by the caller. */
    class Host(private val root: FrameLayout) {
        private var overlay: CoordinatorLayout? = null
        private var box: FrameLayout? = null
        private var behavior: BottomSheetBehavior<FrameLayout>? = null
        private var onGone: (() -> Unit)? = null

        val isShowing get() = overlay != null

        /** Puts [content] up; while the sheet is already open it is swapped in place, with no slide. */
        fun show(content: View, onGone: () -> Unit) {
            this.onGone = onGone
            val open = box
            if (open != null) {
                open.removeAllViews()
                open.addView(content)
                return
            }
            val ctx = root.context
            val d = ctx.resources.displayMetrics.density
            val scrim = View(ctx).apply {
                setBackgroundColor(0x66000000)
                alpha = 0f
                setOnClickListener { dismiss() }
            }
            val nav = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            val sheet = FrameLayout(ctx).apply {
                background = solidSheet(ctx)
                // Under the navigation bar so the fill reaches the screen's bottom edge.
                updatePadding(bottom = nav)
                isClickable = true
                addView(content)
            }
            val b = BottomSheetBehavior<FrameLayout>().apply {
                isHideable = true
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_HIDDEN
                addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                    override fun onStateChanged(v: View, newState: Int) {
                        if (newState == BottomSheetBehavior.STATE_HIDDEN) remove()
                    }
                    override fun onSlide(v: View, slideOffset: Float) {
                        scrim.alpha = (1f + slideOffset).coerceIn(0f, 1f)
                    }
                })
            }
            val host = CoordinatorLayout(ctx).apply {
                elevation = 24 * d
                addView(scrim, CoordinatorLayout.LayoutParams(-1, -1))
                // No bottom gravity: the behavior offsets the sheet from the top on every layout, so a
                // gravity would count that offset twice as soon as the content was swapped in place.
                addView(sheet, CoordinatorLayout.LayoutParams(-1, -2).apply { behavior = b })
            }
            root.addView(host, FrameLayout.LayoutParams(-1, -1))
            overlay = host
            box = sheet
            behavior = b
            host.post { if (overlay === host) b.state = BottomSheetBehavior.STATE_EXPANDED }
        }

        fun dismiss(animated: Boolean = true) {
            val b = behavior ?: return
            if (animated && b.state != BottomSheetBehavior.STATE_HIDDEN) b.state = BottomSheetBehavior.STATE_HIDDEN else remove()
        }

        private fun remove() {
            val o = overlay ?: return
            overlay = null
            box = null
            behavior = null
            root.removeView(o)
            onGone?.invoke()
            onGone = null
        }
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

    private fun decodeAvatarThumb(path: String, maxEdge: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxEdge) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
