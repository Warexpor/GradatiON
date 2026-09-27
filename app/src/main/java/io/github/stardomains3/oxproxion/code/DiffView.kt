package io.github.stardomains3.oxproxion.code

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import io.github.stardomains3.oxproxion.R
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws diff lines directly on the canvas: one view, no child per line, and only the lines inside
 * the clip are drawn, so a 5,000-line diff scrolls as cheaply as a 10-line one.
 *
 * Neutral palette only: added lines sit on a raised fill with a solid bar and ink text; removed
 * lines sink into a recessed fill with muted text. The +/− gutter carries the meaning for anyone
 * who can't tell fills apart.
 *
 * [maxLines] > 0 truncates (transcript card); [wrapWidth] false lets the view grow wide for a
 * HorizontalScrollView (full viewer).
 */
class DiffView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val mono = ResourcesCompat.getFont(context, R.font.atkinsonhyperlegiblemono_regular)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = mono
        textSize = 12.5f * resources.displayMetrics.scaledDensity
        color = ContextCompat.getColor(context, R.color.xai_body)
    }
    private val muted = Paint(text).apply { color = ContextCompat.getColor(context, R.color.xai_mute) }
    private val gutter = Paint(text).apply {
        color = ContextCompat.getColor(context, R.color.code_diff_gutter)
        textAlign = Paint.Align.RIGHT
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val addMark = Paint(text).apply { color = ContextCompat.getColor(context, R.color.code_diff_add_fg) }
    private val delMark = Paint(text).apply { color = ContextCompat.getColor(context, R.color.code_diff_del_fg) }
    private val addFill = Paint().apply { color = ContextCompat.getColor(context, R.color.code_diff_add_bg) }
    private val delFill = Paint().apply { color = ContextCompat.getColor(context, R.color.code_diff_del_bg) }
    private val hunkFill = Paint().apply { color = ContextCompat.getColor(context, R.color.code_diff_hunk_bg) }
    private val addBar = Paint().apply { color = ContextCompat.getColor(context, R.color.code_diff_add_fg) }
    private val delBar = Paint().apply { color = ContextCompat.getColor(context, R.color.code_diff_del_fg) }

    private val lineH = ceil(text.fontSpacing * 1.28f)
    private val baseline = (lineH - (text.descent() + text.ascent())) / 2f
    private val padH = 12 * d
    private var gutterW = 0f
    private val markerW = text.measureText("+") + 8 * d
    private var contentW = 0f
    private val clip = Rect()

    var maxLines: Int = 0
        set(value) { field = value; requestLayout(); invalidate() }
    var wrapWidth: Boolean = true
    var lines: List<DiffLine> = emptyList()
        set(value) {
            field = value
            val maxNo = value.maxOfOrNull { max(it.oldNo ?: 0, it.newNo ?: 0) } ?: 0
            gutterW = gutter.measureText(maxNo.toString().padStart(2, '8')) + 10 * d
            contentW = if (wrapWidth) 0f else value.maxOfOrNull { text.measureText(it.text) } ?: 0f
            requestLayout()
            invalidate()
        }

    val shownCount get() = if (maxLines > 0) min(maxLines, lines.size) else lines.size

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val natural = (padH * 2 + gutterW + markerW + contentW).toInt()
        val w = if (wrapWidth) MeasureSpec.getSize(widthMeasureSpec)
        else max(natural, MeasureSpec.getSize(widthMeasureSpec))
        val h = (shownCount * lineH + 6 * d).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (lines.isEmpty()) return
        if (!canvas.getClipBounds(clip)) clip.set(0, 0, width, height)
        val top = 3 * d
        val first = max(0, ((clip.top - top) / lineH).toInt())
        val last = min(shownCount - 1, ((clip.bottom - top) / lineH).toInt() + 1)
        val w = width.toFloat()
        val xNo = padH + gutterW - 6 * d
        val xMark = padH + gutterW
        val xText = xMark + markerW
        for (i in first..last) {
            val l = lines[i]
            val y = top + i * lineH
            when (l.type) {
                DiffLine.Type.ADD -> {
                    canvas.drawRect(0f, y, w, y + lineH, addFill)
                    canvas.drawRect(0f, y, 2.5f * d, y + lineH, addBar)
                }
                DiffLine.Type.DELETE -> {
                    canvas.drawRect(0f, y, w, y + lineH, delFill)
                    canvas.drawRect(0f, y, 2.5f * d, y + lineH, delBar)
                }
                DiffLine.Type.HUNK -> canvas.drawRect(0f, y, w, y + lineH, hunkFill)
                DiffLine.Type.CONTEXT -> Unit
            }
            val by = y + baseline
            if (l.type == DiffLine.Type.HUNK) {
                canvas.drawText("⋯  ${l.text}", padH, by, muted)
                continue
            }
            (l.newNo ?: l.oldNo)?.let { canvas.drawText(it.toString(), xNo, by, gutter) }
            when (l.type) {
                DiffLine.Type.ADD -> canvas.drawText("+", xMark, by, addMark)
                DiffLine.Type.DELETE -> canvas.drawText("−", xMark, by, delMark)
                else -> Unit
            }
            val p = text
            val s = l.text.replace("\t", "    ")
            if (wrapWidth) {
                val room = w - xText - padH
                val n = p.breakText(s, true, room, null)
                canvas.drawText(if (n < s.length) s.substring(0, max(0, n - 1)) + "…" else s, xText, by, p)
            } else {
                canvas.drawText(s, xText, by, p)
            }
        }
    }
}

/** "+12  −3" with the numbers in the diff colors. */
fun coloredDiffCounts(context: Context, added: Int, removed: Int): CharSequence {
    val plain = context.getString(R.string.code_diff_counts, added, removed)
    val out = android.text.SpannableString(plain)
    val cut = plain.indexOf('−').takeIf { it >= 0 } ?: return plain
    out.setSpan(android.text.style.ForegroundColorSpan(ContextCompat.getColor(context, R.color.code_diff_add_fg)),
        0, cut, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    out.setSpan(android.text.style.ForegroundColorSpan(ContextCompat.getColor(context, R.color.code_diff_del_fg)),
        cut, plain.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return out
}
