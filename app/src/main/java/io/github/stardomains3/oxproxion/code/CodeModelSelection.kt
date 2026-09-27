package io.github.stardomains3.oxproxion.code

/**
 * Pure helpers for the Code-mode model picker (plan §5.4.7).
 * Default is the first harness-reported model; empty list → no picker / null model.
 */
object CodeModelSelection {

    /** First model when the list is non-empty; otherwise null. */
    fun defaultModel(models: List<String>): String? = models.firstOrNull()

    /**
     * Keep [current] when it is still offered; otherwise fall back to [defaultModel].
     * Empty [models] always yields null (hide the picker).
     */
    fun resolveSelection(models: List<String>, current: String?): String? {
        if (models.isEmpty()) return null
        if (current != null && current in models) return current
        return defaultModel(models)
    }

    /**
     * Compact label for the composer pill: last path / provider segment, ellipsized.
     */
    fun pillLabel(model: String, maxLen: Int = 22): String {
        val base = model.substringAfterLast('/').substringAfterLast(':').ifBlank { model }
        return if (base.length <= maxLen) base else base.take(maxLen - 1) + "…"
    }
}
