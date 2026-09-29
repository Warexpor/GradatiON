package io.github.stardomains3.oxproxion

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Inline markdown for one-line chat names: bold, italic, strikethrough and code. A title never
 * needs blocks, so a leading heading or list marker is dropped rather than rendered, and
 * markers that don't pair up stay as typed.
 */
object TitleMarkdown {
    private val LEADING_BLOCK = Regex("""^\s*(#{1,6}\s+|[-*+]\s+|>\s+)""")
    private val INLINE = Regex(
        """\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~|`([^`]+)`""" +
            """|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)"""
    )

    fun render(raw: String): CharSequence {
        val text = raw.trim().replace(LEADING_BLOCK, "")
        if ('*' !in text && '_' !in text && '~' !in text && '`' !in text) return text
        return SpannableStringBuilder().also { append(it, text) }
    }

    private fun append(out: SpannableStringBuilder, text: String) {
        var at = 0
        for (m in INLINE.findAll(text)) {
            out.append(text, at, m.range.first)
            val g = m.groups
            val start = out.length
            when {
                g[1] != null || g[2] != null -> {
                    append(out, (g[1] ?: g[2])!!.value)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[3] != null -> {
                    append(out, g[3]!!.value)
                    out.setSpan(StrikethroughSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[4] != null -> {
                    out.append(g[4]!!.value)
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    append(out, (g[5] ?: g[6])!!.value)
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            at = m.range.last + 1
        }
        out.append(text, at, text.length)
    }
}
