package io.github.stardomains3.oxproxion

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A tiny chat in one theme's palette, for the Appearance theme picker: the canvas, a user
 * bubble, two reply lines and the composer. Light and Dark read their colors from a context
 * forced into that night mode, so each tile shows its theme whatever the app is in now.
 * System splits the tile on the diagonal, light over dark. Static; drawn only on layout.
 */
class ThemePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { SYSTEM, LIGHT, DARK }

    var mode: Mode = Mode.SYSTEM
        set(value) { field = value; invalidate() }

    private class Palette(val canvas: Int, val bubble: Int, val line: Int, val composer: Int, val rim: Int)

    private val light = palette(night = false)
    private val dark = palette(night = true)
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 0.75f
    }
    private val rect = RectF()
    private val split = Path()

    private fun palette(night: Boolean): Palette {
        val config = Configuration(resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val c = context.createConfigurationContext(config)
        return Palette(
            canvas = c.getColor(R.color.xai_canvas),
            bubble = c.getColor(R.color.grad_bubble_top),
            line = c.getColor(R.color.xai_mute),
            composer = c.getColor(R.color.xai_canvas_card),
            rim = c.getColor(R.color.xai_hairline)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        split.rewind()
        split.moveTo(w.toFloat(), 0f)
        split.lineTo(w.toFloat(), h.toFloat())
        split.lineTo(0f, h.toFloat())
        split.close()
    }

    override fun onDraw(canvas: Canvas) {
        when (mode) {
            Mode.LIGHT -> drawChat(canvas, light)
            Mode.DARK -> drawChat(canvas, dark)
            Mode.SYSTEM -> {
                drawChat(canvas, light)
                canvas.save()
                canvas.clipPath(split)
                drawChat(canvas, dark)
                canvas.restore()
            }
        }
    }

    private fun drawChat(canvas: Canvas, p: Palette) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = density
        val pad = 9f * d
        canvas.drawColor(p.canvas)

        // The user's bubble, right-aligned, with the tail corner toward the sender.
        fill.color = p.bubble
        rect.set(w * 0.38f, pad + 2f * d, w - pad, pad + 16f * d)
        val r = rect.height() / 2f
        canvas.drawRoundRect(rect, r, r, fill)

        // The reply: two lines of body text.
        fill.color = p.line
        fill.alpha = 150
        val lineH = 4f * d
        var top = rect.bottom + 9f * d
        for (share in floatArrayOf(0.78f, 0.52f)) {
            rect.set(pad, top, pad + (w - 2 * pad) * share, top + lineH)
            canvas.drawRoundRect(rect, lineH / 2f, lineH / 2f, fill)
            top += lineH + 5f * d
        }
        fill.alpha = 255

        // The composer along the bottom.
        rect.set(pad, h - pad - 14f * d, w - pad, h - pad)
        val cr = rect.height() / 2f
        fill.color = p.composer
        canvas.drawRoundRect(rect, cr, cr, fill)
        stroke.color = p.rim
        canvas.drawRoundRect(rect, cr, cr, stroke)
    }
}
