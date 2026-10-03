package io.github.stardomains3.oxproxion

object RpImportRules {
    /**
     * The last copy of this character in the file is the one that was saved.
     * An earlier greeting used to count as a change after that copy had put the old
     * greeting back, so a rewritten opening was replaced.
     */
    fun greetingChanged(
        currentGreeting: String,
        exportKey: String,
        incoming: List<RpCharacterExport>,
    ): Boolean {
        if (exportKey.isBlank()) return false
        val winning = incoming.lastOrNull { it.exportKey == exportKey } ?: return false
        return RpGreetingSync.greetingTextChanged(currentGreeting, winning.greeting)
    }

    /**
     * How many characters this file will write. The same export key twice is one character.
     * Rows with no key are one character per name, including a second copy of that name.
     */
    fun characterFileCount(incoming: List<RpCharacterExport>): Int {
        val keys = LinkedHashSet<String>()
        val blankNames = LinkedHashSet<String>()
        for (ex in incoming) {
            val name = ex.name.trim()
            if (name.isEmpty()) continue
            if (ex.exportKey.isNotBlank()) keys += ex.exportKey else blankNames += name
        }
        return keys.size + blankNames.size
    }

    /**
     * Characters this file will update. A repeated export key counts once. A row with no key
     * counts when a character of that name is already here and this file does not also name
     * that character by key (that copy is its own row).
     * [existingNamedKeys] is the library's name to export key. Empty means only keys are known.
     */
    fun characterOverwriteCount(
        incoming: List<RpCharacterExport>,
        existingExportKeys: Set<String>,
        existingNamedKeys: List<Pair<String, String>> = emptyList(),
    ): Int {
        val keysInFile = incoming.mapNotNull { it.exportKey.takeIf { key -> key.isNotBlank() } }.toSet()
        val countedKeys = HashSet<String>()
        val countedBlankNames = HashSet<String>()
        var count = 0
        for (ex in incoming) {
            val name = ex.name.trim()
            if (name.isEmpty()) continue
            if (ex.exportKey.isNotBlank()) {
                if (ex.exportKey in existingExportKeys && countedKeys.add(ex.exportKey)) count++
            } else if (countedBlankNames.add(name)) {
                val matches = existingNamedKeys.count { (existingName, key) ->
                    existingName.trim() == name && key !in keysInFile
                }
                if (matches > 0) count++
            }
        }
        return count
    }

    /** Distinct ids. A file that lists one character twice still imported one character. */
    fun importedCharacterCount(rows: List<ImportedCharacter>): Int =
        rows.map { it.id }.distinct().count()

    /** Names that will become a book. Blank names are not books. Repeated names are one book. */
    fun loreFileCount(incoming: List<RpLorebookExport>): Int = distinctLoreNames(incoming).size

    fun loreOverwriteCount(
        incoming: List<RpLorebookExport>,
        existingNames: Collection<String>
    ): Int {
        val existing = existingNames.map { it.trim() }.filter { it.isNotEmpty() }
        return distinctLoreNames(incoming).count { name ->
            existing.any { it.equals(name, ignoreCase = true) }
        }
    }

    private fun distinctLoreNames(incoming: List<RpLorebookExport>): List<String> {
        val seen = ArrayList<String>()
        for (ex in incoming) {
            val name = ex.name.trim()
            if (name.isEmpty()) continue
            if (seen.none { it.equals(name, ignoreCase = true) }) seen += name
        }
        return seen
    }
}
