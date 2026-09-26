package io.github.stardomains3.oxproxion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.SpannableBuilder
import io.noties.markwon.core.MarkwonTheme
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node

/**
 * Chat-specific markdown look, shared by the transcript and streaming:
 * code blocks become rounded cards with a language header you tap to copy, inline code
 * becomes a soft pill, headings lose their underline and get a calmer scale, and the empty
 * line between blocks is shortened so paragraphs breathe without drifting apart.
 *
 * The rounded shapes are painted by [ChatTextView] from the marker spans set here, so text
 * wrapping and selection stay native.
 */
object ChatMarkdown {

    /** Covers a whole code card: header line, code lines and bottom pad line. */
    class CodeBlockMarker(val code: String, val language: String)

    /** Covers an inline code run. */
    class InlineCodeMarker

    /** Tapping the code header copies the block. */
    class CopyCodeSpan(private val code: String) : ClickableSpan() {
        override fun onClick(widget: View) {
            val clipboard = widget.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
            AppToast.makeText(widget.context, widget.context.getString(R.string.toast_code_copied), AppToast.LENGTH_SHORT).show()
        }

        override fun updateDrawState(ds: TextPaint) {
            // Keep the header's own muted color; no link underline.
        }
    }

    private class FontSpan(private val typeface: Typeface) : MetricAffectingSpan() {
        override fun updateMeasureState(tp: TextPaint) {
            tp.typeface = typeface
        }

        override fun updateDrawState(tp: TextPaint) {
            tp.typeface = typeface
        }
    }

    fun plugin(context: Context): AbstractMarkwonPlugin = Plugin(context.applicationContext)

    /**
     * Shrinks the blank separator line between blocks (outside code cards). Idempotent enough
     * to run on every streaming frame: it only adds spans.
     */
    fun polish(text: Spannable, gapScale: Float = GAP_SCALE) {
        val codeRanges = text.getSpans(0, text.length, CodeBlockMarker::class.java)
            .map { text.getSpanStart(it) to text.getSpanEnd(it) }
        var i = text.indexOf("\n\n")
        while (i >= 0 && i + 1 < text.length) {
            val gap = i + 1
            val inCode = codeRanges.any { (s, e) -> gap in s until e }
            if (!inCode && text.getSpans(gap, gap + 1, GapSpan::class.java).isEmpty()) {
                text.setSpan(GapSpan(gapScale), gap, gap + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            i = text.indexOf("\n\n", gap)
        }
    }

    fun polished(text: CharSequence): CharSequence {
        val out = text as? Spannable ?: SpannableStringBuilder(text)
        polish(out)
        return out
    }

    private class GapSpan(scale: Float) : RelativeSizeSpan(scale)

    private const val GAP_SCALE = 0.5f

    private class Plugin(private val context: Context) : AbstractMarkwonPlugin() {
        private val density = context.resources.displayMetrics.density
        private val scaled = context.resources.displayMetrics.scaledDensity
        private val mute = ContextCompat.getColor(context, R.color.xai_mute)
        private val codeText = ContextCompat.getColor(context, R.color.markwon_code_text)
        private val quote = ContextCompat.getColor(context, R.color.markwon_blockquote)
        private val hairline = ContextCompat.getColor(context, R.color.xai_hairline)
        private val ink = ContextCompat.getColor(context, R.color.xai_ink)
        private val mono: Typeface = runCatching {
            ResourcesCompat.getFont(context, R.font.atkinsonhyperlegiblemono_regular)
        }.getOrNull() ?: Typeface.MONOSPACE
        private val semibold: Typeface = runCatching {
            Typeface.create(ResourcesCompat.getFont(context, R.font.app_sans), 600, false)
        }.getOrNull() ?: Typeface.DEFAULT_BOLD
        private val medium: Typeface = runCatching {
            Typeface.create(ResourcesCompat.getFont(context, R.font.app_sans), 500, false)
        }.getOrNull() ?: Typeface.DEFAULT

        private fun dp(v: Float) = (v * density).toInt()

        override fun configureTheme(builder: MarkwonTheme.Builder) {
            builder
                .headingBreakHeight(0)
                .headingTypeface(semibold)
                .headingTextSizeMultipliers(floatArrayOf(1.36f, 1.2f, 1.08f, 1f, 0.94f, 0.88f))
                .blockMargin(dp(22f))
                .blockQuoteWidth(dp(3f))
                .blockQuoteColor(quote)
                .bulletWidth(dp(5.5f))
                .listItemColor(mute)
                .codeTypeface(mono)
                .codeBlockTypeface(mono)
                .codeTextColor(codeText)
                .thematicBreakColor(hairline)
                .thematicBreakHeight(dp(1f).coerceAtLeast(1))
                .linkColor(ink)
                .isLinkUnderlined(true)
        }

        override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
            builder.setFactory(Code::class.java) { _, _ ->
                arrayOf<Any>(
                    InlineCodeMarker(),
                    FontSpan(mono),
                    RelativeSizeSpan(0.88f),
                    ForegroundColorSpan(codeText)
                )
            }
        }

        override fun configureVisitor(builder: MarkwonVisitor.Builder) {
            builder.on(FencedCodeBlock::class.java) { visitor, node ->
                val info = node.info?.trim()?.substringBefore(' ').orEmpty()
                codeCard(visitor, node, info, node.literal.orEmpty())
            }
            builder.on(IndentedCodeBlock::class.java) { visitor, node ->
                codeCard(visitor, node, "", node.literal.orEmpty())
            }
        }

        private fun codeCard(visitor: MarkwonVisitor, node: Node, info: String, literal: String) {
            val code = literal.trimEnd('\n')
            visitor.blockStart(node)
            val b = visitor.builder()
            val start = b.length

            // Header: language label (tap anywhere on the line to copy).
            val label = info.ifBlank { "code" }.lowercase()
            b.append(label)
            SpannableBuilder.setSpans(
                b,
                arrayOf<Any>(
                    FontSpan(medium),
                    AbsoluteSizeSpan((12.5f * scaled).toInt()),
                    ForegroundColorSpan(mute),
                    CopyCodeSpan(code)
                ),
                start,
                b.length
            )
            b.append('\n')

            val codeStart = b.length
            b.append(visitor.configuration().syntaxHighlight().highlight(info.ifBlank { null }, code))
            SpannableBuilder.setSpans(
                b,
                arrayOf<Any>(FontSpan(mono), RelativeSizeSpan(0.86f)),
                codeStart,
                b.length
            )
            b.append('\n')

            // Short bottom pad line so the card doesn't hug the last code line.
            val padStart = b.length
            b.append(' ')
            SpannableBuilder.setSpans(b, AbsoluteSizeSpan(dp(4f)), padStart, b.length)

            SpannableBuilder.setSpans(
                b,
                arrayOf<Any>(CodeBlockMarker(code, label), LeadingMarginSpan.Standard(dp(14f))),
                start,
                b.length
            )
            visitor.blockEnd(node)
        }
    }
}
