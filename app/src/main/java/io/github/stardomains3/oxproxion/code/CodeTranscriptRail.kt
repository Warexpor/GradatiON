package io.github.stardomains3.oxproxion.code

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.stardomains3.oxproxion.R

/** How a transcript row meets the rail. Decided from the row's data, so offscreen rows count too. */
enum class Rail {
    /** Not on the rail: the line breaks here. */
    NONE,
    /** A prompt: the rail hangs from it and runs down. */
    START,
    /** A bead ([R.id.codeRailNode]) the rail stops short of on both sides. */
    NODE,
    /** Agent prose: a dot beside the first line. */
    TEXT,
    /** The live "Working" footer: a quieter dot beside its line. */
    WORKING,
    /** Runs straight through (opaque cards cover it, notices sit beside it). */
    PASS,
    /** The turn's end: the rail comes in and stops at the ring. */
    END,
}

/**
 * The thread a turn hangs on: one hairline down the gutter of the transcript, from the prompt
 * through every step to the turn's end, so a turn reads as one trace instead of a pile of
 * bubbles. Drawn under the rows in a single pass with no views of its own. It stops short of
 * each bead rather than running behind it, so translucent beads over a photo background stay clean.
 */
class CodeTranscriptRail(context: Context, private val railAt: (Int) -> Rail) : RecyclerView.ItemDecoration() {

    private val d = context.resources.displayMetrics.density
    /** Centre of the rail column; every row puts its glyph on it (12dp row pad + half a 20dp gutter). */
    private val railX = 22 * d
    private val gap = 3 * d
    private val dotR = 3 * d
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.code_rail)
        strokeWidth = 1 * d
        strokeCap = Paint.Cap.BUTT
    }
    private val inkDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.xai_body) }
    private val muteDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.xai_mute) }
    private val lineAlpha = line.alpha
    private val inkAlpha = inkDot.alpha
    private val muteAlpha = muteDot.alpha

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val x = if (parent.layoutDirection == View.LAYOUT_DIRECTION_RTL) parent.width - railX else railX
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            val kind = railAt(pos)
            if (kind == Rail.NONE) continue
            // Rows fade in as they arrive; the rail beside them fades with them.
            val a = child.alpha
            line.alpha = (lineAlpha * a).toInt()
            val top = child.top + child.translationY
            val bottom = child.bottom + child.translationY
            when (kind) {
                Rail.START -> if (reachesDown(pos)) segment(c, x, (top + bottom) / 2f, bottom)
                Rail.PASS -> if (reachesUp(pos) && reachesDown(pos)) segment(c, x, top, bottom)
                Rail.NODE, Rail.END -> {
                    val node = child.findViewById<View>(R.id.codeRailNode)
                    if (node == null || !shownIn(node, child)) {
                        // An answered approval folds to a bead; until then it is a card the rail passes behind.
                        if (reachesUp(pos) && reachesDown(pos)) segment(c, x, top, bottom)
                        continue
                    }
                    val cy = top + offsetIn(node, child) + node.height / 2f
                    val r = node.height / 2f + gap
                    if (reachesUp(pos)) segment(c, x, top, cy - r)
                    if (kind == Rail.NODE && reachesDown(pos)) segment(c, x, cy + r, bottom)
                }
                Rail.TEXT, Rail.WORKING -> {
                    val tv = (if (kind == Rail.WORKING) child else child.findViewById(R.id.codeAgentText)) as? TextView
                    val cy = top + firstLineCentre(tv, child)
                    val r = dotR + gap
                    if (reachesUp(pos)) segment(c, x, top, cy - r)
                    if (reachesDown(pos)) segment(c, x, cy + r, bottom)
                    val dot = if (kind == Rail.WORKING) muteDot else inkDot
                    dot.alpha = ((if (kind == Rail.WORKING) muteAlpha else inkAlpha) * a).toInt()
                    c.drawCircle(x, cy, dotR, dot)
                }
                Rail.NONE -> Unit
            }
        }
    }

    private fun segment(c: Canvas, x: Float, from: Float, to: Float) {
        if (to > from) c.drawLine(x, from, x, to, line)
    }

    /** True when something above [pos] (past any pass-through rows) sends the rail down to it. */
    private fun reachesUp(pos: Int): Boolean {
        var q = pos - 1
        while (q >= 0) {
            when (railAt(q)) {
                Rail.PASS -> q--
                Rail.START, Rail.NODE, Rail.TEXT, Rail.WORKING -> return true
                Rail.END, Rail.NONE -> return false
            }
        }
        return false
    }

    /** True when something below [pos] (past any pass-through rows) takes the rail in, so it never dangles. */
    private fun reachesDown(pos: Int): Boolean {
        var q = pos + 1
        while (true) {
            when (railAt(q)) {
                Rail.PASS -> q++
                Rail.NODE, Rail.TEXT, Rail.WORKING, Rail.END -> return true
                Rail.START, Rail.NONE -> return false
            }
        }
    }

    /** Middle of the first line's lowercase letters, from [row]'s top; a fixed drop when there is no text. */
    private fun firstLineCentre(tv: TextView?, row: View): Float {
        val layout = tv?.layout
        if (tv == null || layout == null || layout.lineCount == 0 || !shownIn(tv, row)) return row.paddingTop + 12 * d
        return offsetIn(tv, row) + tv.totalPaddingTop + layout.getLineBaseline(0) - tv.textSize * 0.3f
    }

    private fun offsetIn(view: View, ancestor: View): Float {
        var y = 0f
        var v: View = view
        while (v !== ancestor) {
            y += v.top + v.translationY
            v = v.parent as? View ?: break
        }
        return y
    }

    private fun shownIn(view: View, ancestor: View): Boolean {
        var v: View = view
        while (v !== ancestor) {
            if (v.visibility != View.VISIBLE) return false
            v = v.parent as? View ?: return false
        }
        return true
    }
}
