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

    fun characterOverwriteCount(
        incoming: List<RpCharacterExport>,
        existingExportKeys: Set<String>
    ): Int = incoming.count { ex ->
        ex.name.trim().isNotEmpty() && ex.exportKey.isNotBlank() && ex.exportKey in existingExportKeys
    }

    fun loreOverwriteCount(
        incoming: List<RpLorebookExport>,
        existingNames: Collection<String>
    ): Int {
        return incoming.count { ex ->
            val name = ex.name.trim()
            name.isNotEmpty() && existingNames.any { it.trim().equals(name, ignoreCase = true) }
        }
    }
}
