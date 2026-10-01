package io.github.stardomains3.oxproxion

/** Pure helpers for RP swipe eligibility (unit-tested). */
object RpSwipeRules {
    /**
     * Swipe alts apply only to an assistant reply that follows a user turn —
     * never to a greeting-only thread.
     */
    fun isSwipeableTranscript(
        hasUserTurn: Boolean,
        lastUserIndex: Int,
        lastAssistantIndex: Int
    ): Boolean =
        hasUserTurn && lastUserIndex >= 0 && lastAssistantIndex > lastUserIndex

    fun <T> isSwipeableMessages(
        messages: List<T>,
        isUser: (T) -> Boolean,
        isAssistant: (T) -> Boolean
    ): Boolean {
        val lastUserIndex = messages.indexOfLast(isUser)
        val lastAssistantIndex = messages.indexOfLast(isAssistant)
        return isSwipeableTranscript(
            hasUserTurn = lastUserIndex >= 0,
            lastUserIndex = lastUserIndex,
            lastAssistantIndex = lastAssistantIndex
        )
    }

    /**
     * After truncating/deleting a suffix, keep alts only if the remaining last reply
     * is still one of them; otherwise reseed to that single reply.
     */
    fun reconcileAltsAfterTruncate(
        alts: List<String>,
        lastAssistantText: String
    ): Pair<List<String>, Int> {
        val matchIndex = alts.indexOf(lastAssistantText)
        return if (matchIndex >= 0) {
            alts to matchIndex
        } else {
            listOf(lastAssistantText) to 0
        }
    }

    /**
     * Before regen, ensure [currentText] is in [alts] without duplicating the already-selected seed.
     * Still appends when the visible bubble differs from the selected alt.
     * Returns the updated alts and the index to keep selected (stay on seed when not appending).
     */
    fun stashCurrentAlt(
        alts: List<String>,
        selectedIndex: Int,
        currentText: String
    ): Pair<List<String>, Int> {
        if (alts.getOrNull(selectedIndex) == currentText) return alts to selectedIndex
        if (alts.lastOrNull() == currentText) return alts to alts.lastIndex
        val next = alts + currentText
        return next to next.lastIndex
    }

    /** A file link worth remembering. A data URL is not one, and a blank is no picture. */
    fun pictureUriOf(imageUri: String?): String {
        val uri = imageUri?.trim().orEmpty()
        if (uri.isEmpty() || uri.startsWith("data:", ignoreCase = true)) return ""
        return uri
    }

    /** True once every version has a picture slot, including a blank one. */
    fun picturesTracked(pictureUris: List<String>, altCount: Int): Boolean =
        altCount > 0 && pictureUris.size == altCount

    /**
     * The file for [index], or null when this save does not remember pictures and the one
     * already on the reply should stay. A blank string is a version with no picture.
     */
    fun pictureForAlt(pictureUris: List<String>, altCount: Int, index: Int): String? {
        if (!picturesTracked(pictureUris, altCount)) return null
        return pictureUris.getOrElse(index) { "" }
    }

    /**
     * Remember [currentPicture] on the version being stashed. An older save that never
     * stored pictures, and whose reply has no file, is left alone so swiping still keeps
     * the JPEG already on that reply. Once a file is known, every existing version shares
     * it: that is what those versions were already showing.
     */
    fun stashAlt(
        alts: List<String>,
        pictureUris: List<String>,
        selectedIndex: Int,
        currentText: String,
        currentPicture: String,
    ): Triple<List<String>, List<String>, Int> {
        if (!picturesTracked(pictureUris, alts.size) && currentPicture.isEmpty()) {
            val (nextAlts, nextIndex) = stashCurrentAlt(alts, selectedIndex, currentText)
            return Triple(nextAlts, pictureUris, nextIndex)
        }
        val base = if (picturesTracked(pictureUris, alts.size)) {
            pictureUris.toMutableList()
        } else {
            MutableList(alts.size) { currentPicture }
        }
        if (alts.getOrNull(selectedIndex) == currentText) {
            if (selectedIndex in base.indices) base[selectedIndex] = currentPicture
            return Triple(alts, base, selectedIndex)
        }
        if (alts.lastOrNull() == currentText) {
            base[base.lastIndex] = currentPicture
            return Triple(alts, base, alts.lastIndex)
        }
        return Triple(alts + currentText, base + currentPicture, alts.size)
    }

    /**
     * The new version starts with no picture. The file is filled in when the reply lands.
     * An older save that is not tracking pictures stays that way.
     */
    fun appendAlt(
        alts: List<String>,
        pictureUris: List<String>,
        text: String,
    ): Triple<List<String>, List<String>, Int> {
        if (alts.isNotEmpty() && !picturesTracked(pictureUris, alts.size)) {
            val next = alts + text
            return Triple(next, pictureUris, next.lastIndex)
        }
        val next = alts + text
        return Triple(next, pictureUris + "", next.lastIndex)
    }

    /**
     * The reply on screen was just given a durable file at [healedUri]. Remember that file
     * on the selected version, and on any other version that still names the link this one
     * had: those versions were already showing that same file.
     * A blank slot stays blank. A reply that is not this version is left alone, so a picture
     * from a different turn is not pasted onto it.
     */
    fun adoptVisibleFile(
        alts: List<String>,
        pictureUris: List<String>,
        index: Int,
        visibleText: String,
        healedUri: String,
    ): List<String> {
        if (!picturesTracked(pictureUris, alts.size)) return pictureUris
        if (healedUri.isEmpty()) return pictureUris
        val at = index.coerceIn(0, alts.lastIndex)
        if (alts[at] != visibleText) return pictureUris
        val previous = pictureUris[at]
        if (previous.isEmpty() || previous == healedUri) return pictureUris
        return remapPicture(pictureUris, previous, healedUri)
    }

    /** Every version that still names [from] now names [to]. The same list when nothing changes. */
    fun remapPicture(pictureUris: List<String>, from: String, to: String): List<String> {
        if (from.isEmpty() || to.isEmpty() || from == to || from !in pictureUris) return pictureUris
        return pictureUris.map { if (it == from) to else it }
    }

    /** Put [picture] on the selected version. A blank does not wipe a file a partial reply lacks. */
    fun notePicture(
        alts: List<String>,
        pictureUris: List<String>,
        index: Int,
        picture: String,
    ): List<String> {
        if (alts.isEmpty() || picture.isEmpty()) return pictureUris
        val at = index.coerceIn(0, alts.lastIndex)
        if (picturesTracked(pictureUris, alts.size)) {
            if (pictureUris[at] == picture) return pictureUris
            return pictureUris.toMutableList().also { it[at] = picture }
        }
        if (alts.size != 1) return pictureUris
        return listOf(picture)
    }
}
