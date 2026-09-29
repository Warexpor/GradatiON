package io.github.stardomains3.oxproxion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

/**
 * The picture on a character-panel tile: a flat grayscale drawing of what the tile opens
 * (stacked memory blocks, a play disc, a mini chat in the chosen layout, the wallpaper itself).
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

    enum class Kind { MEMORY, VOICE, LAYOUT_CLASSIC, LAYOUT_BUBBLES, LAYOUT_BOOK, WALLPAPER, PERSONA, STYLE, LORE, EDIT, NEW_CHAT }

    /** The character's wallpaper; without one the tile shows an empty frame with a plus. */
    var photo: Bitmap? = null

    /** The persona's initial; without one, a silhouette. */
    var letter: String? = null

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val box = RectF()
    private val photoMatrix = Matrix()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun tint(a: Float) = ColorUtils.setAlphaComponent(ink, (a.coerceIn(0f, 1f) * 255).toInt())

    private fun solid(a: Float) {
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = tint(a)
    }

    private fun fade(top: Float, bottom: Float, aTop: Float, aBottom: Float) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, top, 0f, bottom, tint(aTop), tint(aBottom), Shader.TileMode.CLAMP)
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
            Kind.NEW_CHAT -> newChat(c, s)
        }
        p.shader = null
        p.style = Paint.Style.FILL
    }

    /** Three blocks stepping up to the right, the tallest behind and cut off by the tile's edge. */
    private fun memory(c: Canvas, s: Float) {
        val radius = s * 0.11f
        val lefts = floatArrayOf(0.6f, 0.39f, 0.17f)
        val widths = floatArrayOf(0.56f, 0.36f, 0.34f)
        val tops = floatArrayOf(0.4f, 0.56f, 0.72f)
        val tints = floatArrayOf(0.26f, 0.5f, 0.94f)
        val gap = 2f * d
        for (i in 0..2) {
            val l = s * lefts[i]
            val t = s * tops[i]
            val r = l + s * widths[i]
            if (i > 0) {
                cutout()
                round(l - gap, t - gap, r + gap, s + radius, radius + gap, c)
            }
            fade(t, s, tints[i], tints[i] * 0.78f)
            round(l, t, r, s + radius, radius, c)
        }
    }

    /** A silver play disc with a lit rim. */
    private fun voice(c: Canvas, s: Float) {
        val cx = s * 0.5f
        val cy = s * 0.66f
        val r = s * 0.24f
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.5f, r * 1.7f, tint(0.8f), tint(0.34f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        stroke(tint(0.85f), 1.5f * d)
        c.drawCircle(cx, cy, r - 0.75f * d, p)
        val t = r * 0.3f
        path.reset()
        path.moveTo(cx - t * 0.6f, cy - t)
        path.lineTo(cx - t * 0.6f, cy + t)
        path.lineTo(cx + t * 1.05f, cy)
        path.close()
        solid(1f)
        c.drawPath(path, p)
        stroke(tint(1f), 2f * d)
        c.drawPath(path, p)
    }

    /** A tiny transcript in the layout that is set: a speaker line, two bubbles, or centered prose. */
    private fun layout(c: Canvas, s: Float) {
        val bar = s * 0.075f
        when (kind) {
            Kind.LAYOUT_BUBBLES -> {
                // Their reply comes in from the left edge, yours from the right.
                solid(0.32f)
                round(-s * 0.2f, s * 0.5f, s * 0.64f, s * 0.68f, s * 0.09f, c)
                solid(0.9f)
                round(s * 0.36f, s * 0.75f, s * 1.2f, s * 0.93f, s * 0.09f, c)
            }
            Kind.LAYOUT_BOOK -> {
                val widths = floatArrayOf(0.7f, 0.52f, 0.64f, 0.36f)
                solid(0.6f)
                for (i in widths.indices) {
                    val y = s * 0.52f + i * s * 0.12f
                    val half = s * widths[i] / 2f
                    round(s / 2f - half, y, s / 2f + half, y + bar * 0.8f, bar, c)
                }
            }
            else -> {
                val l = s * 0.14f
                val y = s * 0.6f
                val dot = s * 0.055f
                solid(0.94f)
                c.drawCircle(l + dot, y, dot, p)
                round(l + dot * 2.8f, y - bar / 2f, s * 0.64f, y + bar / 2f, bar, c)
                solid(0.36f)
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
        fade(t, s, 0.2f, 0.12f)
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
        fade(cy - r, cy + r, 0.42f, 0.22f)
        c.drawCircle(cx, cy, r, p)
        val initial = letter?.takeIf { it.isNotBlank() }
        if (initial != null) {
            solid(0.96f)
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
            solid(0.88f)
            c.drawCircle(cx, cy - r * 0.2f, r * 0.34f, p)
            box.set(cx - r * 0.68f, cy + r * 0.28f, cx + r * 0.68f, cy + r * 1.4f)
            c.drawOval(box, p)
            c.restore()
        }
        stroke(tint(0.6f), 1.5f * d)
        c.drawCircle(cx, cy, r - 0.75f * d, p)
    }

    /** An app-icon squircle, one step darker than the tile, with two sliders in it. */
    private fun style(c: Canvas, s: Float) {
        val cx = s * 0.5f
        val cy = s * 0.66f
        val half = s * 0.23f
        val radius = s * 0.13f
        val shade = if (ColorUtils.calculateLuminance(ink) > 0.5) {
            ColorUtils.blendARGB(cut, Color.BLACK, 0.4f)
        } else {
            ColorUtils.blendARGB(cut, Color.WHITE, 0.6f)
        }
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = shade
        round(cx - half, cy - half, cx + half, cy + half, radius, c)
        stroke(tint(0.3f), 1.5f * d)
        round(cx - half, cy - half, cx + half, cy + half, radius, c)
        val span = half * 0.56f
        val knobs = floatArrayOf(0.32f, 0.7f)
        for (i in 0..1) {
            val y = cy + (if (i == 0) -1f else 1f) * half * 0.3f
            val kx = cx - span + 2f * span * knobs[i]
            stroke(tint(0.3f), 2.2f * d)
            c.drawLine(cx - span, y, cx + span, y, p)
            stroke(tint(0.8f), 2.2f * d)
            c.drawLine(cx - span, y, kx, y, p)
            solid(0.96f)
            c.drawCircle(kx, y, half * 0.17f, p)
        }
    }

    /** An open book: the right page lit, the left one in shade, a few lines set in. */
    private fun lore(c: Canvas, s: Float) {
        val cx = s * 0.5f
        val page = s * 0.29f
        val tall = s * 0.34f
        val gap = s * 0.02f
        val top = s * 0.5f
        val lift = s * 0.05f
        val corner = android.graphics.CornerPathEffect(2.5f * d)
        fun side(dir: Float, a: Float, b: Float) {
            path.reset()
            path.moveTo(cx + dir * gap, top + lift)
            path.lineTo(cx + dir * (gap + page), top)
            path.lineTo(cx + dir * (gap + page), top + tall)
            path.lineTo(cx + dir * gap, top + tall + lift)
            path.close()
            fade(top, top + tall + lift, a, b)
            p.pathEffect = corner
            c.drawPath(path, p)
            p.pathEffect = null
        }
        side(-1f, 0.5f, 0.32f)
        side(1f, 0.94f, 0.66f)
        stroke(cut, 1.6f * d)
        for (i in 0..2) {
            val y = top + tall * (0.3f + 0.2f * i)
            val far = if (i == 2) 0.5f else 0.8f
            for (dir in floatArrayOf(-1f, 1f)) {
                val x0 = cx + dir * (gap + page * 0.18f)
                val x1 = cx + dir * (gap + page * far)
                // The line climbs with the page toward its outer edge.
                c.drawLine(x0, y + lift * (1f - 0.18f), x1, y + lift * (1f - far), p)
            }
        }
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

    /** A speech bubble with a plus in it. */
    private fun newChat(c: Canvas, s: Float) {
        val l = s * 0.27f
        val r = s * 0.73f
        val t = s * 0.5f
        val b = s * 0.8f
        fade(t, s * 0.92f, 0.94f, 0.62f)
        round(l, t, r, b, s * 0.12f, c)
        path.reset()
        path.moveTo(l + s * 0.07f, b - 1f)
        path.lineTo(l + s * 0.03f, s * 0.92f)
        path.lineTo(l + s * 0.22f, b - 1f)
        path.close()
        c.drawPath(path, p)
        val mx = (l + r) / 2f
        val my = (t + b) / 2f
        val arm = s * 0.065f
        stroke(cut, 2.4f * d)
        c.drawLine(mx - arm, my, mx + arm, my, p)
        c.drawLine(mx, my - arm, mx, my + arm, p)
    }
}
