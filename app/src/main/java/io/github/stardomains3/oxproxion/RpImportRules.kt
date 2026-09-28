package io.github.stardomains3.oxproxion

object RpImportRules {
    fun characterOverwriteCount(
        incoming: List<RpCharacterExport>,
        existingExportKeys: Set<String>
    ): Int = incoming.count { ex ->
        ex.exportKey.isNotBlank() && ex.exportKey in existingExportKeys
    }

    /**
     * The library characters an import would overwrite. A backup card matches by its export key;
     * a card with no key (a Tavern card) matches a character of the same name, so importing it
     * twice asks instead of quietly piling up copies.
     */
    fun matchingCharacters(
        incoming: List<RpCharacterExport>,
        existing: List<RpCharacter>
    ): List<RpCharacter> {
        val hit = LinkedHashSet<Long>()
        incoming.forEach { ex ->
            val match = if (ex.exportKey.isNotBlank()) {
                existing.firstOrNull { it.exportKey == ex.exportKey }
            } else {
                existing.firstOrNull { it.name.equals(ex.name, ignoreCase = true) }
            }
            if (match != null) hit += match.id
        }
        return existing.filter { it.id in hit }
    }

    fun loreOverwriteCount(
        incoming: List<RpLorebookExport>,
        existingNames: Collection<String>
    ): Int {
        return incoming.count { ex ->
            existingNames.any { it.equals(ex.name, ignoreCase = true) }
        }
    }

    /** [name] if it is free, else "name (2)", "name (3)"… so an import never merges into another book. */
    fun uniqueName(name: String, taken: Collection<String>): String {
        fun isTaken(candidate: String) = taken.any { it.equals(candidate, ignoreCase = true) }
        if (!isTaken(name)) return name
        var n = 2
        while (isTaken("$name ($n)")) n++
        return "$name ($n)"
    }
}
