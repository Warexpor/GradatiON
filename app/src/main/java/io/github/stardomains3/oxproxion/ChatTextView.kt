package io.github.stardomains3.oxproxion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * Message text view that paints the rounded shapes behind [ChatMarkdown] code: a card with a
 * hairline and header divider for code blocks (plus a right-aligned "Copy" hint), and a pill
 * behind inline code. Everything else is a plain TextView, so selection and links behave
 * exactly as before.
 */
class ChatTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val cardRadius = 14f * density
    private val pillRadius = 6f * density
    private val pillPadX = 3.5f * density
    private val rect = RectF()

    private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.markwon_code_bg)
    }
    private val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density.coerceAtLeast(1f)
        color = ContextCompat.getColor(context, R.color.xai_hairline)
    }
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.markwon_inline_code_bg)
    }
    private val copyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.xai_mute)
        textSize = 12.5f * resources.displayMetrics.scaledDensity
        typeface = runCatching { ResourcesCompat.getFont(context, R.font.inter_medium) }.getOrNull()
        textAlign = Paint.Align.RIGHT
    }
    private val copyLabel = context.getString(R.string.action_copy)

    override fun onDraw(canvas: Canvas) {
        val spanned = text as? Spanned
        val layout = layout
        if (spanned != null && layout != null) {
            val blocks = spanned.getSpans(0, spanned.length, ChatMarkdown.CodeBlockMarker::class.java)
            val inlines = spanned.getSpans(0, spanned.length, ChatMarkdown.InlineCodeMarker::class.java)
            if (blocks.isNotEmpty() || inlines.isNotEmpty()) {
                canvas.save()
                canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
                blocks.forEach { drawCard(canvas, layout, spanned, it) }
                inlines.forEach { drawPill(canvas, layout, spanned, it) }
                canvas.restore()
            }
        }
        super.onDraw(canvas)
    }

    private fun drawCard(canvas: Canvas, layout: Layout, text: Spanned, marker: Any) {
        val start = text.getSpanStart(marker)
        val end = text.getSpanEnd(marker)
        if (start < 0 || end <= start) return
        val first = layout.getLineForOffset(start)
        val last = layout.getLineForOffset((end - 1).coerceAtLeast(start))
        val top = layout.getLineTop(first).toFloat()
        val bottom = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            layout.getLineBottom(last, false).toFloat()
        } else {
            // getLineBottom(line, false) is API 34; strip the line-spacing extra by hand.
            val lineTop = layout.getLineTop(last).toFloat()
            lineTop + (layout.getLineBottom(last) - lineTop) / lineSpacingMultiplier.coerceAtLeast(1f)
        }
        val width = layout.width.toFloat()
        rect.set(0f, top, width, bottom)
        canvas.drawRoundRect(rect, cardRadius, cardRadius, cardFill)
        val inset = cardStroke.strokeWidth / 2f
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, cardRadius, cardRadius, cardStroke)

        // Copy hint on the language line.
        canvas.drawText(copyLabel, width - 14f * density, layout.getLineBaseline(first).toFloat(), copyPaint)
    }

    private fun drawPill(canvas: Canvas, layout: Layout, text: Spanned, marker: Any) {
        val start = text.getSpanStart(marker)
        val end = text.getSpanEnd(marker)
        if (start < 0 || end <= start) return
        val firstLine = layout.getLineForOffset(start)
        val lastLine = layout.getLineForOffset(end)
        for (line in firstLine..lastLine) {
            val lineStart = maxOf(start, layout.getLineStart(line))
            val lineEnd = minOf(end, layout.getLineVisibleEnd(line))
            if (lineEnd <= lineStart) continue
            val x1 = layout.getPrimaryHorizontal(lineStart)
            val x2 = if (lineEnd >= layout.getLineVisibleEnd(line)) layout.getLineRight(line)
            else layout.getPrimaryHorizontal(lineEnd)
            val baseline = layout.getLineBaseline(line).toFloat()
            val size = textSize * 0.88f
            rect.set(
                minOf(x1, x2) - pillPadX,
                baseline - size * 0.98f,
                maxOf(x1, x2) + pillPadX,
                baseline + size * 0.34f
            )
            canvas.drawRoundRect(rect, pillRadius, pillRadius, pillFill)
        }
    }
}
