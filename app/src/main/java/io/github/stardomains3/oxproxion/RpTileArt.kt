package io.github.stardomains3.oxproxion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

/**
 * The picture on a character-panel tile: a flat grayscale drawing of what the tile opens
 * (days of chats, a stack of notes, an open book, a voice wave, a mini chat in the chosen
 * layout, the wallpaper itself, a type specimen).
 *
 * Fills the whole tile and is clipped by the card, so a drawing can sit low and run off an edge
 * while the name stays clear in the top-left. Only tints of one ink color plus the tile's own
 * color for cut-outs, so it follows the theme and never brings a hue. Drawn by hand instead of
 * shipped as vectors so it can show live state: the layout you use, your wallpaper, your
 * persona's initial.
 */
class RpTileArt(
    context: Context,
    private val kind: Kind,
    private val ink: Int,
    /** The tile's own fill, for cut-outs such as the gaps between memory blocks. */
    private val cut: Int
) : View(context) {

    private companion object {
        /** The whole palette, as ink alphas: a dim base shape, a quiet mid, a dark-ish low, the lit accent. */
        const val BASE = 0.2f
        const val LOW = 0.34f
        const val MID = 0.5f
        const val HI = 0.94f
    }

    enum class Kind { MEMORY, VOICE, LAYOUT_CLASSIC, LAYOUT_BUBBLES, LAYOUT_BOOK, WALLPAPER, PERSONA, STYLE, LORE, EDIT, HISTORY }

    /** The character's wallpaper; without one the tile shows an empty frame with a plus. */
    var photo: Bitmap? = null

    /** The persona's initial; without one, a silhouette. */
    var letter: String? = null

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val box = RectF()
    private val photoMatrix = Matrix()
    private val month by lazy { java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault()) }
    private val small by lazy { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.jakarta_semibold) }
    private val big by lazy { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.jakarta_bold) }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun tint(a: Float) = ColorUtils.setAlphaComponent(ink, (a.coerceIn(0f, 1f) * 255).toInt())

    private fun solid(a: Float) {
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = tint(a)
    }

    private fun cutout() {
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = cut
    }

    private fun stroke(color: Int, width: Float) {
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = width
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = color
    }

    private fun round(l: Float, t: Float, r: Float, b: Float, radius: Float, c: Canvas) {
        box.set(l, t, r, b)
        c.drawRoundRect(box, radius, radius, p)
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        when (kind) {
            Kind.MEMORY -> memory(c, s)
            Kind.VOICE -> voice(c, s)
            Kind.LAYOUT_CLASSIC, Kind.LAYOUT_BUBBLES, Kind.LAYOUT_BOOK -> layout(c, s)
            Kind.WALLPAPER -> wallpaper(c, s)
            Kind.PERSONA -> persona(c, s)
            Kind.STYLE -> style(c, s)
            Kind.LORE -> lore(c, s)
            Kind.EDIT -> edit(c, s)
            Kind.HISTORY -> history(c, s)
        }
        p.shader = null
        p.style = Paint.Style.FILL
    }

    /** A stack of three notes, the front one written on, running off the bottom. */
    private fun memory(c: Canvas, s: Float) {
        val radius = s * 0.1f
        val insets = floatArrayOf(0.26f, 0.2f, 0.14f)
        val tops = floatArrayOf(0.46f, 0.54f, 0.62f)
        val tints = floatArrayOf(BASE, LOW, HI)
        val gap = 2f * d
        for (i in 0..2) {
            val l = s * insets[i]
            val r = s - l
            val t = s * tops[i]
            if (i > 0) {
                cutout()
                round(l - gap, t - gap, r + gap, s + radius, radius + gap, c)
            }
            solid(tints[i])
            round(l, t, r, s + radius, radius, c)
        }
        cutout()
        val bar = s * 0.06f
        round(s * 0.24f, s * 0.72f, s * 0.64f, s * 0.72f + bar, bar, c)
        round(s * 0.24f, s * 0.84f, s * 0.5f, s * 0.84f + bar, bar, c)
    }

    /** A voice wave: five bars, tallest in the middle. */
    private fun voice(c: Canvas, s: Float) {
        val heights = floatArrayOf(0.12f, 0.26f, 0.4f, 0.26f, 0.12f)
        val tints = floatArrayOf(LOW, HI, HI, HI, LOW)
        val bw = s * 0.075f
        val step = s * 0.125f
        val cy = s * 0.66f
        for (i in heights.indices) {
            val x = s / 2f + (i - 2) * step
            val half = s * heights[i] / 2f
            solid(tints[i])
            round(x - bw / 2f, cy - half, x + bw / 2f, cy + half, bw / 2f, c)
        }
    }

    /** A tiny transcript in the layout that is set: a speaker line, two bubbles, or centered prose. */
    private fun layout(c: Canvas, s: Float) {
        val bar = s * 0.075f
        when (kind) {
            Kind.LAYOUT_BUBBLES -> {
                // Their reply comes in from the left edge, yours from the right.
                solid(LOW)
                round(-s * 0.2f, s * 0.46f, s * 0.64f, s * 0.64f, s * 0.09f, c)
                solid(HI)
                round(s * 0.36f, s * 0.7f, s * 1.2f, s * 0.88f, s * 0.09f, c)
            }
            Kind.LAYOUT_BOOK -> {
                val widths = floatArrayOf(0.7f, 0.52f, 0.64f, 0.36f)
                solid(MID)
                for (i in widths.indices) {
                    val y = s * 0.46f + i * s * 0.12f
                    val half = s * widths[i] / 2f
                    round(s / 2f - half, y, s / 2f + half, y + bar * 0.8f, bar, c)
                }
            }
            else -> {
                val l = s * 0.14f
                val y = s * 0.5f
                val dot = s * 0.055f
                solid(HI)
                c.drawCircle(l + dot, y, dot, p)
                round(l + dot * 2.8f, y - bar / 2f, s * 0.64f, y + bar / 2f, bar, c)
                solid(LOW)
                round(l, y + s * 0.15f, s + bar, y + s * 0.15f + bar, bar, c)
                round(l, y + s * 0.29f, s * 0.7f, y + s * 0.29f + bar, bar, c)
            }
        }
    }

    /** The wallpaper itself, or an empty picture frame with a plus, running off the bottom. */
    private fun wallpaper(c: Canvas, s: Float) {
        val l = s * 0.14f
        val r = s * 0.86f
        val t = s * 0.48f
        val b = s * 1.12f
        val radius = s * 0.1f
        val shot = photo
        if (shot != null) {
            val fw = r - l
            val fh = s - t
            val scale = max(fw / shot.width, fh / shot.height)
            photoMatrix.reset()
            photoMatrix.setScale(scale, scale)
            photoMatrix.postTranslate(l + (fw - shot.width * scale) / 2f, t + (fh - shot.height * scale) / 2f)
            val shader = BitmapShader(shot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(photoMatrix)
            p.style = Paint.Style.FILL
            p.shader = shader
            round(l, t, r, b, radius, c)
            return
        }
        solid(BASE)
        round(l, t, r, b, radius, c)
        c.save()
        path.reset()
        path.addRoundRect(l, t, r, b, radius, radius, Path.Direction.CW)
        c.clipPath(path)
        val fw = r - l
        solid(0.14f)
        path.reset()
        path.moveTo(l, s * 0.92f)
        path.lineTo(l + fw * 0.34f, s * 0.72f)
        path.lineTo(l + fw * 0.58f, s * 0.86f)
        path.lineTo(l + fw * 0.78f, s * 0.77f)
        path.lineTo(r, s * 0.88f)
        path.lineTo(r, b)
        path.lineTo(l, b)
        path.close()
        c.drawPath(path, p)
        solid(0.3f)
        c.drawCircle(r - fw * 0.17f, t + s * 0.1f, s * 0.05f, p)
        c.restore()
        val py = s * 0.76f
        val arm = s * 0.07f
        stroke(tint(0.92f), 2.2f * d)
        c.drawLine(s / 2f - arm, py, s / 2f + arm, py, p)
        c.drawLine(s / 2f, py - arm, s / 2f, py + arm, p)
    }

    /** A round portrait: the persona's initial, or a silhouette while there is no name. */
    private fun persona(c: Canvas, s: Float) {
        val cx = s * 0.5f
        val cy = s * 0.66f
        val r = s * 0.24f
        solid(BASE)
        c.drawCircle(cx, cy, r, p)
        val initial = letter?.takeIf { it.isNotBlank() }
        if (initial != null) {
            solid(HI)
            p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            p.textSize = r * 0.95f
            p.textAlign = Paint.Align.CENTER
            c.drawText(initial.take(1).uppercase(), cx, cy - (p.descent() + p.ascent()) / 2f, p)
            p.textAlign = Paint.Align.LEFT
        } else {
            c.save()
            path.reset()
            path.addCircle(cx, cy, r, Path.Direction.CW)
            c.clipPath(path)
            solid(MID)
            c.drawCircle(cx, cy - r * 0.2f, r * 0.34f, p)
            box.set(cx - r * 0.68f, cy + r * 0.28f, cx + r * 0.68f, cy + r * 1.4f)
            c.drawOval(box, p)
            c.restore()
        }
    }

    /** How the story is written: a type specimen, the capital lit and the small letter quieter. */
    private fun style(c: Canvas, s: Float) {
        p.shader = null
        p.style = Paint.Style.FILL
        p.typeface = big
        p.textSize = s * 0.46f
        p.textAlign = Paint.Align.LEFT
        val capital = p.measureText("A")
        val lower = p.measureText("a")
        val gap = s * 0.02f
        val x = (s - capital - gap - lower) / 2f
        val baseline = s * 0.82f
        p.color = tint(HI)
        c.drawText("A", x, baseline, p)
        p.color = tint(MID)
        c.drawText("a", x + capital + gap, baseline, p)
    }

    /** The world's book: open on a cover, written pages dipping into the spine, a ribbon hanging out. */
    private fun lore(c: Canvas, s: Float) {
        val cx = s * 0.5f
        val w = s * 0.33f
        val gap = 1.5f * d
        val outer = s * 0.44f
        val spine = s * 0.51f
        val bottom = s * 0.84f
        val lip = s * 0.035f
        p.pathEffect = android.graphics.CornerPathEffect(s * 0.04f)
        solid(LOW)
        path.reset()
        path.moveTo(cx, spine + lip)
        path.lineTo(cx - w - lip, outer + lip)
        path.lineTo(cx - w - lip, bottom + lip)
        path.lineTo(cx, bottom + lip * 2.2f)
        path.lineTo(cx + w + lip, bottom + lip)
        path.lineTo(cx + w + lip, outer + lip)
        path.close()
        c.drawPath(path, p)
        solid(HI)
        for (side in floatArrayOf(-1f, 1f)) {
            path.reset()
            path.moveTo(cx + side * gap, spine)
            path.lineTo(cx + side * w, outer)
            path.lineTo(cx + side * w, bottom)
            path.lineTo(cx + side * gap, bottom + lip)
            path.close()
            c.drawPath(path, p)
        }
        p.pathEffect = null
        // Lines of text follow each page's slope into the spine.
        val slope = (spine - outer) / w
        stroke(ColorUtils.setAlphaComponent(cut, 110), s * 0.028f)
        val near = s * 0.07f
        val far = w - s * 0.06f
        val widths = floatArrayOf(1f, 0.8f, 1f, 0.55f)
        for (side in floatArrayOf(-1f, 1f)) {
            for (i in widths.indices) {
                val drop = s * 0.075f + i * s * 0.055f
                val end = near + (far - near) * widths[i]
                c.drawLine(
                    cx + side * near, spine - slope * near + drop,
                    cx + side * end, spine - slope * end + drop, p
                )
            }
        }
        solid(MID)
        val rw = s * 0.05f
        val rl = cx + s * 0.1f
        path.reset()
        path.moveTo(rl, bottom)
        path.lineTo(rl + rw, bottom)
        path.lineTo(rl + rw, s * 0.92f)
        path.lineTo(rl + rw / 2f, s * 0.89f)
        path.lineTo(rl, s * 0.92f)
        path.close()
        c.drawPath(path, p)
    }

    /** A pencil laid across the corner, its end off the tile's edge. */
    private fun edit(c: Canvas, s: Float) {
        val hw = s * 0.085f
        val tip = s * 0.17f
        val band = s * 0.62f
        c.save()
        c.translate(s * 0.3f, s * 0.88f)
        c.rotate(-40f)
        solid(0.5f)
        path.reset()
        path.moveTo(0f, 0f)
        path.lineTo(tip, -hw)
        path.lineTo(tip, hw)
        path.close()
        c.drawPath(path, p)
        solid(0.96f)
        path.reset()
        path.moveTo(0f, 0f)
        path.lineTo(tip * 0.34f, -hw * 0.34f)
        path.lineTo(tip * 0.34f, hw * 0.34f)
        path.close()
        c.drawPath(path, p)
        // Two faces on the body read as a hexagonal barrel.
        solid(0.94f)
        box.set(tip, -hw, band, 0f)
        c.drawRect(box, p)
        solid(0.7f)
        box.set(tip, 0f, band, hw)
        c.drawRect(box, p)
        solid(0.42f)
        box.set(band, -hw, band + s * 0.09f, hw)
        c.drawRect(box, p)
        solid(0.62f)
        box.set(band + s * 0.09f + 1.5f * d, -hw, s * 1.4f, hw)
        c.drawRect(box, p)
        c.restore()
    }

    /** A strip of days, today lit in the middle, the ones around it running off both edges. */
    private fun history(c: Canvas, s: Float) {
        val pw = s * 0.2f
        val ph = s * 0.34f
        val step = s * 0.24f
        val cy = s * 0.66f
        val day = java.util.Calendar.getInstance()
        day.add(java.util.Calendar.DAY_OF_MONTH, -2)
        p.textAlign = Paint.Align.CENTER
        for (i in -2..2) {
            val x = s / 2f + i * step
            val today = i == 0
            solid(if (today) HI else BASE)
            round(x - pw / 2f, cy - ph / 2f, x + pw / 2f, cy + ph / 2f, pw * 0.5f, c)
            p.shader = null
            p.style = Paint.Style.FILL
            p.color = if (today) cut else tint(MID)
            p.typeface = small
            p.textSize = s * 0.07f
            c.drawText(month.format(day.time), x, cy - ph * 0.12f, p)
            p.typeface = big
            p.textSize = s * 0.105f
            c.drawText(day.get(java.util.Calendar.DAY_OF_MONTH).toString(), x, cy + ph * 0.26f, p)
            day.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        p.textAlign = Paint.Align.LEFT
    }
}
