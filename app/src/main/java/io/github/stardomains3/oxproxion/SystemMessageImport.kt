package io.github.stardomains3.oxproxion

/**
 * How an imported System messages file lands on disk.
 * Export always writes Default (with [SystemMessage.isDefault]) plus customs. Import used to
 * skip any row whose title matched Default, so a customized Default never came back, and
 * Export refused when there were no customs — so editing only Default could not be backed up.
 */
internal data class SystemMessageImportPlan(
    /** Non-null when the file carries a Default row to store. */
    val default: SystemMessage?,
    val customs: List<SystemMessage>,
)

/**
 * Rows marked Default update the saved Default (title and prompt). Every other row is a custom
 * with [SystemMessage.isDefault] forced off; titles that already exist as a custom are skipped.
 * A legacy export that stripped `isDefault` still updates Default when the title matches.
 */
internal fun planSystemMessageImport(
    imported: List<SystemMessage>,
    currentDefault: SystemMessage,
    currentCustoms: List<SystemMessage>,
): SystemMessageImportPlan {
    var nextDefault: SystemMessage? = null
    val customs = currentCustoms.toMutableList()
    for (row in imported) {
        val asDefault = row.isDefault || row.title == currentDefault.title
        if (asDefault) {
            // One Default wins: the last Default-like row in the file.
            nextDefault = SystemMessage(row.title, row.prompt, isDefault = true)
            continue
        }
        if (customs.any { it.title == row.title }) continue
        customs.add(SystemMessage(row.title, row.prompt, isDefault = false))
    }
    return SystemMessageImportPlan(nextDefault, customs)
}

/** JSON list written by Export: Default first (flag kept), then customs. */
internal fun systemMessagesExportList(
    defaultMessage: SystemMessage,
    customMessages: List<SystemMessage>,
): List<SystemMessage> = listOf(
    SystemMessage(defaultMessage.title, defaultMessage.prompt, isDefault = true)
) + customMessages.map { SystemMessage(it.title, it.prompt, isDefault = false) }
