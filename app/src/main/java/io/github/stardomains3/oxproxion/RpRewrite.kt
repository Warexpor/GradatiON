package io.github.stardomains3.oxproxion

/** Which replies stream back as a new swipe, and the one line the rewrite dialog quotes. */
object RpRewrite {
    /**
     * The reply after the latest user turn comes back as another swipe (the old one stays a swipe
     * back). The greeting, and any earlier reply, is rewritten in place; that earlier reply keeps
     * its own versions so Undo is a swipe on the bubble. A greeting-only thread has no user turn
     * to regenerate from.
     */
    fun streamsAsNewSwipe(position: Int, lastAssistantIndex: Int, lastUserIndex: Int): Boolean =
        lastUserIndex >= 0 && position == lastAssistantIndex && position > lastUserIndex

    /**
     * What a rewrite still has to match lore against. The reply being changed comes last so a
     * short one is kept whole; the turn before it is there when that beat has already scrolled
     * out of the recent scene.
     */
    fun loreFocus(reply: String, preceding: String = ""): List<String> =
        listOf(preceding, reply).map { it.trim() }.filter { it.isNotEmpty() }

    /** First line of the reply, markdown marks stripped, so the dialog shows which bubble the note is for. */
    fun snippet(reply: String, limit: Int = 90): String {
        val line = reply.lineSequence()
            .map { stripMarks(it.trim()) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (line.length <= limit) return line
        return line.take((limit - 1).coerceAtLeast(1)).trimEnd() + "…"
    }

    /** Markdown emphasis and the quotes a dialogue line may wear, including «guillemets», »Swiss«, „low quotes“, 《angle》, ｢halfwidth｣, 〝primes〞, ❝ornamental❞, ﹁vertical﹂, ＂fullwidth＂, ❮heavy❯, ｟white paren｠, 〘tortoise〙, ⟨math angle⟩, ❰ornament❱, 〚white square〛, ⟪math double⟫, ⟬math tortoise⟭, ⟦math white square⟧, ⦃white curly⦄, ❨flattened paren❩, ❪medium flattened❫, ❬medium angle❭, ❲light tortoise❳, ❴medium curly❵, ⦅white paren⦆ and ⦗black tortoise⦘. */
    private fun stripMarks(line: String): String {
        var s = line
        val marks = charArrayOf('*', '_', '"', '\'', '“', '”', '‘', '’', '«', '»', '「', '」', '『', '』', '‹', '›', '„', '‚', '《', '》', '〈', '〉', '｢', '｣', '〝', '〞', '〟', '❝', '❞', '❛', '❜', '﹁', '﹂', '﹃', '﹄', '〔', '〕', '〖', '〗', '＂', '＇', '❮', '❯', '｟', '｠', '〘', '〙', '⟨', '⟩', '❰', '❱', '〚', '〛', '⟪', '⟫', '⟬', '⟭', '⟦', '⟧', '⦃', '⦄', '❨', '❩', '❪', '❫', '❬', '❭', '❲', '❳', '❴', '❵', '⦅', '⦆', '⦗', '⦘')
        while (s.isNotEmpty() && s.first() in marks) s = s.drop(1).trimStart()
        while (s.isNotEmpty() && s.last() in marks) s = s.dropLast(1).trimEnd()
        return s
    }
}
