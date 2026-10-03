package io.github.stardomains3.oxproxion

object RpImportRules {
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
