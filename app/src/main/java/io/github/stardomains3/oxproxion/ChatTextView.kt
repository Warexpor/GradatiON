package io.github.stardomains3.oxproxion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
 * header row (language label on the left, a Copy chip with an icon on the right, a hairline
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
    private val copyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.xai_body)
        // The chip is tappable, so it keeps the 13sp floor.
        textSize = 13f * scaled
        typeface = runCatching { ResourcesCompat.getFont(context, R.font.jakarta_medium) }.getOrNull()
    }
    private val copyLabel = context.getString(R.string.action_copy)
    private val copiedLabel = context.getString(R.string.code_copied_chip)
    private val copyIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_msg_copy)?.mutate()?.apply {
        setTint(ContextCompat.getColor(context, R.color.xai_body))
    }
    private val checkIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_msg_check)?.mutate()?.apply {
        setTint(ContextCompat.getColor(context, R.color.xai_ink))
    }
    private val sheenColor = ContextCompat.getColor(context, R.color.glass_sheen)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density.coerceAtLeast(1f)
    }
    private val copyPillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.glass_chat_action_tint)
    }
    private val copyPillPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.popover_row_pressed)
    }
    private val copyPillRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density.coerceAtLeast(1f)
        color = ContextCompat.getColor(context, R.color.xai_hairline)
    }
    private val capBounds = Rect()
    private var shaderTop = Float.NaN
    private var shaderWidth = -1f

    /** Copy chips from the last draw (text-layout coordinates), with the code each copies. */
    private val chips = ArrayList<Pair<RectF, String>>()
    private var pressedChip = -1
    private var copiedCode: String? = null
    private var copiedUntil = 0L

    /** Chip shows "Copied" with a check for a moment; called by the chip and the header span. */
    fun showCopied(code: String) {
        copiedCode = code
        copiedUntil = SystemClock.uptimeMillis() + COPIED_MS
        invalidate()
        postDelayed({ invalidate() }, COPIED_MS + 16)
    }

    override fun onDraw(canvas: Canvas) {
        val spanned = text as? Spanned
        val layout = layout
        chips.clear()
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
                pressedChip = chips.indexOfFirst { (r, _) -> hit(r, x, y) }
                if (pressedChip >= 0) { invalidate(); return true }
            }
            MotionEvent.ACTION_MOVE -> if (pressedChip >= 0) {
                if (!hit(chips.getOrNull(pressedChip)?.first, x, y)) { pressedChip = -1; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP -> if (pressedChip >= 0) {
                val chip = chips.getOrNull(pressedChip)
                pressedChip = -1
                if (chip != null && hit(chip.first, x, y)) {
                    ChatMarkdown.copyCode(this, chip.second)
                    showCopied(chip.second)
                } else invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (pressedChip >= 0) { pressedChip = -1; invalidate(); return true }
        }
        return super.onTouchEvent(event)
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
        canvas.drawRoundRect(rect, cardRadius, cardRadius, cardFill)
        // Glass: a soft sheen across the top and a rim lit from above.
        if (shaderTop != top || shaderWidth != width) {
            shaderTop = top
            shaderWidth = width
            sheenPaint.shader = LinearGradient(0f, top, 0f, top + 34f * density,
                sheenColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            rimPaint.shader = LinearGradient(0f, top, width * 0.35f, top + 60f * density,
                Color.argb(Color.alpha(sheenColor) * 3 / 2, 255, 255, 255), cardStroke.color, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(rect, cardRadius, cardRadius, sheenPaint)
        val inset = cardStroke.strokeWidth / 2f
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, cardRadius, cardRadius, rimPaint)

        // Header row: the whole first line, spacing included, so label and chip sit centered.
        val rowTop = layout.getLineTop(first).toFloat()
        val rowBottom = layout.getLineBottom(first).toFloat()
        val cy = (rowTop + rowBottom) / 2f
        canvas.drawLine(0f, rowBottom, width, rowBottom, dividerPaint)
        labelPaint.getTextBounds("H", 0, 1, capBounds)
        canvas.drawText(marker.language, 14f * density, cy + capBounds.height() / 2f, labelPaint)

        // Copy chip: glass capsule, icon then label, optically centered.
        val copied = copiedCode == marker.code && SystemClock.uptimeMillis() < copiedUntil
        val label = if (copied) copiedLabel else copyLabel
        val icon = if (copied) checkIcon else copyIcon
        copyPaint.getTextBounds("H", 0, 1, capBounds)
        val iconSize = 14f * density
        val gap = 5f * density
        val padX = 10f * density
        val h = 26f * density
        val chipW = padX + iconSize + gap + copyPaint.measureText(label) + padX
        val right = width - 8f * density
        rect.set(right - chipW, cy - h / 2f, right, cy + h / 2f)
        val index = chips.size
        canvas.drawRoundRect(rect, h / 2f, h / 2f, if (pressedChip == index) copyPillPressed else copyPillFill)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, copyPillRim)
        chips += RectF(rect) to marker.code
        icon?.let {
            val l = (rect.left + padX).toInt()
            val t = (cy - iconSize / 2f).toInt()
            it.setBounds(l, t, l + iconSize.toInt(), t + iconSize.toInt())
            it.draw(canvas)
        }
        canvas.drawText(label, rect.left + padX + iconSize + gap, cy + capBounds.height() / 2f, copyPaint)
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
