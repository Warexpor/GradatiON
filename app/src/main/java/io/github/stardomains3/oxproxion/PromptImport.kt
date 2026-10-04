package io.github.stardomains3.oxproxion

/**
 * How an imported Prompts file merges into the library.
 * The editor rejects a new title that matches an existing one ignore-case; Import used a
 * case-sensitive check, so a file with "foo" beside a saved "Foo" created a second row.
 * Matching titles (ignore case) stay as they are; only new titles are appended.
 */
internal fun planPromptImport(
    imported: List<Prompt>,
    current: List<Prompt>,
): List<Prompt> {
    val next = current.toMutableList()
    for (row in imported) {
        if (next.any { it.title.equals(row.title, ignoreCase = true) }) continue
        next.add(Prompt(row.title, row.prompt))
    }
    return next
}
