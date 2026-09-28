package io.github.stardomains3.oxproxion

/**
 * Labels shown in model lists. The provider already lives on the id line or the
 * row subtitle, so the name itself is only the model.
 */
object ModelNames {

    /** "Anthropic: Claude Sonnet 4" with id "anthropic/claude-sonnet-4" → "Claude Sonnet 4". */
    fun withoutProvider(displayName: String, apiIdentifier: String): String {
        val colon = displayName.indexOf(':')
        if (colon <= 0) return displayName
        val head = displayName.substring(0, colon).trim()
        val tail = displayName.substring(colon + 1).trim()
        if (head.isEmpty() || tail.isEmpty()) return displayName
        val provider = apiIdentifier.substringBefore('/').substringBefore(':')
        if (provider.isEmpty() || provider == apiIdentifier) return displayName
        if (!sameProvider(head, provider)) return displayName
        return tail
    }

    /**
     * Drop one leading provider segment.
     * "anthropic/claude-sonnet-4" → "claude-sonnet-4"; "provider:opus" → "opus".
     */
    fun idWithoutProvider(id: String): String {
        val slash = id.indexOf('/')
        if (slash in 1 until id.lastIndex) return id.substring(slash + 1)
        val colon = id.indexOf(':')
        if (colon in 1 until id.lastIndex) return id.substring(colon + 1)
        return id
    }

    /** The leading provider segment, when [idWithoutProvider] actually removed one. */
    fun providerOf(id: String): String? {
        val name = idWithoutProvider(id)
        if (name == id) return null
        val prefix = id.removeSuffix(name).trimEnd('/', ':')
        return prefix.ifBlank { null }
    }

    private fun sameProvider(label: String, slug: String): Boolean {
        val a = norm(label)
        val b = norm(slug)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || b.startsWith(a) || a.startsWith(b)
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
