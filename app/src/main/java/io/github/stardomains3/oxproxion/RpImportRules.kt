package io.github.stardomains3.oxproxion

object RpImportRules {
    fun characterOverwriteCount(
        incoming: List<RpCharacterExport>,
        existingExportKeys: Set<String>
    ): Int = incoming.count { ex ->
        ex.exportKey.isNotBlank() && ex.exportKey in existingExportKeys
    }

    fun loreOverwriteCount(
        incoming: List<RpLorebookExport>,
        existingNames: Collection<String>
    ): Int {
        return incoming.count { ex ->
            existingNames.any { it.equals(ex.name, ignoreCase = true) }
        }
    }
}
