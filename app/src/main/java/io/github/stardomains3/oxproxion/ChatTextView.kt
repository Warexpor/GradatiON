package io.github.stardomains3.oxproxion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.Layout
import android.text.Spanned
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * Message text view that paints the rounded shapes behind [ChatMarkdown] code: a card with a
 * header row (language label on the left, a bare copy icon on the right, a hairline
 * under both), and a pill behind inline code. Everything else is a plain TextView, so
 * selection and links behave exactly as before.
 */
class ChatTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val cardRadius = 12f * density
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
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = density.coerceAtLeast(1f)
        color = ContextCompat.getColor(context, R.color.xai_hairline)
    }
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.markwon_inline_code_bg)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.xai_mute)
        textSize = 12.5f * scaled
        typeface = runCatching { ResourcesCompat.getFont(context, R.font.jakarta_medium) }.getOrNull()
    }
    private val copyIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_msg_copy)?.mutate()?.apply {
        setTint(ContextCompat.getColor(context, R.color.xai_body))
    }
    private val checkIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_msg_check)?.mutate()?.apply {
        setTint(ContextCompat.getColor(context, R.color.xai_ink))
    }
    private val copyPillPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.popover_row_pressed)
    }
    private val capBounds = Rect()

    /** Copy icons' tap areas from the last draw (text-layout coordinates), with the code each copies. */
    private class CopyChip {
        val rect = RectF()
        var code: String = ""
    }
    private val chips = ArrayList<CopyChip>(4)
    private var chipCount = 0
    private var pressedChip = -1
    private var copiedCode: String? = null
    private var copiedUntil = 0L

    /** The copy icon turns into a check for a moment; called by the icon and the header span. */
    fun showCopied(code: String) {
        copiedCode = code
        copiedUntil = SystemClock.uptimeMillis() + COPIED_MS
        invalidate()
        postDelayed({ invalidate() }, COPIED_MS + 16)
    }

    override fun onDraw(canvas: Canvas) {
        val spanned = text as? Spanned
        val layout = layout
        chipCount = 0
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x - totalPaddingLeft
        val y = event.y - totalPaddingTop + scrollY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedChip = indexOfChip(x, y)
                if (pressedChip >= 0) { invalidate(); return true }
            }
            MotionEvent.ACTION_MOVE -> if (pressedChip >= 0) {
                if (!hit(chipAt(pressedChip)?.rect, x, y)) { pressedChip = -1; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP -> if (pressedChip >= 0) {
                val chip = chipAt(pressedChip)
                pressedChip = -1
                if (chip != null && hit(chip.rect, x, y)) {
                    ChatMarkdown.copyCode(this, chip.code)
                    showCopied(chip.code)
                } else invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (pressedChip >= 0) { pressedChip = -1; invalidate(); return true }
        }
        return super.onTouchEvent(event)
    }

    private fun chipAt(index: Int): CopyChip? = if (index in 0 until chipCount) chips[index] else null

    private fun indexOfChip(x: Float, y: Float): Int {
        for (i in 0 until chipCount) if (hit(chips[i].rect, x, y)) return i
        return -1
    }

    private fun takeChip(src: RectF, code: String): Int {
        val chip = if (chipCount < chips.size) chips[chipCount] else CopyChip().also { chips.add(it) }
        chip.rect.set(src)
        chip.code = code
        return chipCount.also { chipCount++ }
    }

    /** Chips are small; take a few dp of slop around them. */
    private fun hit(r: RectF?, x: Float, y: Float): Boolean {
        r ?: return false
        val slop = 8f * density
        return x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop
    }

    private fun drawCard(canvas: Canvas, layout: Layout, text: Spanned, marker: ChatMarkdown.CodeBlockMarker) {
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
        // Flat card: one fill and a hairline edge, no sheen or lit rim.
        canvas.drawRoundRect(rect, cardRadius, cardRadius, cardFill)
        val inset = cardStroke.strokeWidth / 2f
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, cardRadius, cardRadius, cardStroke)

        // Header row: the whole first line, spacing included, so label and icon sit centered.
        val rowTop = layout.getLineTop(first).toFloat()
        val rowBottom = layout.getLineBottom(first).toFloat()
        val cy = (rowTop + rowBottom) / 2f
        canvas.drawLine(0f, rowBottom, width, rowBottom, dividerPaint)
        labelPaint.getTextBounds("H", 0, 1, capBounds)
        canvas.drawText(marker.language, 14f * density, cy + capBounds.height() / 2f, labelPaint)

        // Copy: just the icon, a check for a moment once copied; a soft disc while pressed.
        val copied = copiedCode == marker.code && SystemClock.uptimeMillis() < copiedUntil
        val icon = if (copied) checkIcon else copyIcon
        val iconSize = 18f * density
        val cx = width - 22f * density
        val half = 15f * density
        rect.set(cx - half, cy - half, cx + half, cy + half)
        val index = takeChip(rect, marker.code)
        if (pressedChip == index) canvas.drawOval(rect, copyPillPressed)
        icon?.let {
            val l = (cx - iconSize / 2f).toInt()
            val t = (cy - iconSize / 2f).toInt()
            it.setBounds(l, t, l + iconSize.toInt(), t + iconSize.toInt())
            it.draw(canvas)
        }
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

    private companion object {
        const val COPIED_MS = 1400L
    }
}
