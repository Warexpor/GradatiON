package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Roleplay's Continue: the character carries on inside its last reply instead of starting a new
 * bubble. The model is asked (with a hidden user turn) to pick up exactly where the reply ends;
 * [join] then sews what it writes onto what was already there.
 */
object RpContinuation {

    /**
     * Characters that hug the text before them: no space goes in front of these. Straight quotes
     * are left out on purpose: a reply that opens with `"` is starting new dialogue.
     * Guillemets and Arabic/Indic closers hug the same way as Latin ones.
     */
    private const val CLOSERS = ",.;:!?)]}%\u2026\u2019\u201D\u00BB\u203A。！？」』؟۔।॥։၊။．》〉｣〞❞❜﹂﹄〕〗❯｠〙⟩❱〛⟫⟭"

    /** Characters that hug the text after them: a reply that stops on one has more to say right after it. Spanish ¿¡ start the next beat the same way. */
    private const val OPENERS = "-\u2013\u2014([{/\u2018\u201C\u00AB\u2039「『¿¡„‚\u00BB\u203A《〈｢〝〟❝❛﹁﹃〔〖❮｟〘⟨❰〚⟪⟬"

    /**
     * Sentence enders, and the markup/quotes that may trail one (`*She smiles.*`, `"Come in."`,
     * 「来て。」, «Привет.», مرحبا؟). Arabic ؟۔, Devanagari ।॥, Armenian ։, Myanmar ၊။,
     * Hebrew ׃, Ethiopic ።፧፨, Greek ;, Tibetan །, Khmer ។, Armenian ՜՞,
     * Syriac ܀܁܂, Mongolian ᠃᠅, doubled ¡¿ marks, fullwidth ．, interrobang ‽,
     * Georgian ჻, Canadian Aboriginal ᙮, Sinhala ෴, Khmer ៕, Thai ฯ, Coptic ⳹⳾,
     * reversed ⸮, Limbu ᥄᥅, Lisu ꓿, Vai ꘎꘏, halfwidth ｡, small ﹒﹗﹖,
     * Arabic ؛, Nko ߹, Ol Chiki ᱾᱿, Bamum ꛳꛷, vertical ︒, vertical ︖︕,
     * Cham ꩝꩞꩟, Balinese ᭞᭟, Lepcha ᰻᰼, Tibetan ༎༏༔, Meetei ꫰꫱,
     * Saurashtra ꣎꣏, Javanese ꧉꧈꧋꧞꧟, Phags-pa ꡶꡷, Rejang ꥟, Buginese ᨞᨟,
     * Batak ᯼᯽᯾᯿, Runic ᛫᛬᛭, Mandaic ࡞, Tifinagh ⵰ and Samaritan ࠹࠾ count too.
     */
    private const val ENDERS = ".!?…。！？؟۔।॥։၊။׃።;།។՜՞܀܁܂᠃‼⁇⁈⁉．‽჻᙮෴៕ฯ⳹⳾⸮᥄᥅꓿꘎꘏｡﹒፧፨؛߹᱾᱿꛳꛷︒﹗﹖︖︕꩝꩞꩟᭞᭟᠅᰻᰼༎༏༔꫰꫱꣎꣏꧉꡶꡷꥟᨞᨟᯼᯽᯾᯿᛫᛬᛭࡞⵰࠹࠾꧈꧋꧞꧟"
    // Guillemets and a space before a closing one (French « … . ») are not part of the sentence end.
    // Left quotes “ ‘ and open guillemets « ‹ trail too: German „…“ closes on “, and Swiss/German
    // »…« closes on «, which are openers the other way. CJK 《》〈〉 and halfwidth ｣ / 〞
    // trail like 「」. Ornamental ❞❜, vertical ﹂﹄ / tortoise 〔〕〖〗, fullwidth ＂＇,
    // heavy angle ❯, white paren ｠, white tortoise 〙, math angle ⟩, heavy ornament ❱,
    // white square 〛, math double angle ⟫ and math white tortoise ⟭ trail too.
    private const val TRAILERS = "*_~\"')]’”」』\u00BB\u203A׳״\u201C\u2018\u00AB\u2039《》〈〉｣〞❞❜﹂﹄〕〗＂＇❯｠〙⟩❱〛⟫⟭"

    /**
     * Continue replaces the reply with the joined text. A picture already on that reply stays
     * unless the new piece brought one of its own. A data URL is not a file we can show.
     * The JPEG stored in the message stays too, so the next save still has the picture.
     */
    fun keepPicture(
        priorUri: String?,
        updated: FlexibleMessage,
        priorContent: JsonElement? = null,
    ): FlexibleMessage {
        val withUri = when {
            priorUri.isNullOrEmpty() || priorUri.startsWith("data:") -> updated
            !updated.imageUri.isNullOrEmpty() -> updated
            else -> updated.copy(imageUri = priorUri)
        }
        val priorImage = priorContent?.let { MessageContent.imageUrl(it) }
            ?.takeIf { it.startsWith("data:image") }
            ?: return withUri
        if (MessageContent.hasImage(withUri.content)) return withUri
        return withUri.copy(content = ScenePhoto.embed(withUri.content, priorImage))
    }

    /**
     * Another version of the same reply. The words change; a picture already on it stays,
     * including the JPEG stored in the message. Swipe used to replace the whole body with
     * text, so the next save forgot that JPEG and a missing file took the picture with it.
     */
    fun withWords(message: FlexibleMessage, text: String): FlexibleMessage =
        message.copy(
            content = ScenePhoto.replaceTextKeepingPicture(message.content, text),
            reasoning = null,
            thinking = null,
        )

    /**
     * One version of a reply. [pictureUri] null means this save does not remember pictures,
     * so the one already on the reply stays. A blank means this version has none, and a
     * link puts that file back. The same link keeps the JPEG stored in the message.
     */
    fun withVersion(message: FlexibleMessage, text: String, pictureUri: String?): FlexibleMessage {
        if (pictureUri == null) return withWords(message, text)
        if (pictureUri.isEmpty()) {
            return message.copy(
                content = JsonPrimitive(text),
                imageUri = null,
                reasoning = null,
                thinking = null,
            )
        }
        if (message.imageUri == pictureUri) return withWords(message, text)
        return message.copy(
            content = JsonPrimitive(text),
            imageUri = pictureUri,
            reasoning = null,
            thinking = null,
        )
    }

    /**
     * [base] followed by [addition], with a separator only where the model left none: a new
     * paragraph after a finished sentence or closed action (including «…», „…“, »…«, 《…》, ｢…｣,
     * 〝…〞, ❝…❞, ﹁…﹂, ＂…＂, ❮…❯, ｟…｠, 〘…〙, ⟨…⟩, ❰…❱, 〚…〛, ⟪…⟫, ⟬…⟭, ؟۔, ।॥, ׃, ።፧, ؛,
     * ߹, ᱾, ꛳, ．, ‽, ៕, ⸮, ｡, ︒, ︖, ꩝, ༎, ꫰, ꣎, ꧉, ꡶, ᯼, ᛫, ࡞, ⵰ and ࠾), a plain
     * space after unfinished text. A sentence in an unspaced script (including Tibetan and
     * Ethiopic) gets no space.
     *
     * Finished ends are checked before openers: German „Hallo.“ closes on “, and Swiss/German
     * »Hallo.« closes on «, which are also openers the other way, so treating openers first
     * left a finished line glued to the next beat. Halfwidth ｢…｣, 〝…〞, ornamental ❝…❞,
     * vertical ﹁…﹂, fullwidth ＂…＂, heavy angle ❮…❯, white paren ｟…｠, white tortoise 〘…〙,
     * math angle ⟨…⟩, heavy ornament ❰…❱, white square 〚…〛, math double angle ⟪…⟫ and math
     * white tortoise ⟬…⟭ trail the same way.
     */
    fun join(base: String, addition: String): String {
        if (addition.isEmpty()) return base
        if (base.isEmpty()) return addition
        val last = base.last()
        val first = addition.first()
        if (last.isWhitespace() || first.isWhitespace()) return base + addition
        if (first in CLOSERS) return base + addition
        // Skip trailers and spaces so «Привет. » and „Hallo.“ still count as finished.
        val end = base.trimEnd { it in TRAILERS || it.isWhitespace() }.lastOrNull()
        if (end != null && end in ENDERS) return "$base\n\n$addition"
        if (last in OPENERS) return base + addition
        // Japanese, Chinese, Korean and the other unspaced scripts do not take a space between words.
        if (unspaced(last) || unspaced(first)) return base + addition
        return "$base $addition"
    }

    private fun unspaced(ch: Char): Boolean {
        val script = Character.UnicodeScript.of(ch.code)
        return script == Character.UnicodeScript.HAN ||
            script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA ||
            script == Character.UnicodeScript.HANGUL ||
            script == Character.UnicodeScript.THAI ||
            script == Character.UnicodeScript.LAO ||
            script == Character.UnicodeScript.KHMER ||
            script == Character.UnicodeScript.MYANMAR ||
            script == Character.UnicodeScript.TIBETAN ||
            script == Character.UnicodeScript.ETHIOPIC
    }
}
