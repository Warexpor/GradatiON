package io.github.stardomains3.oxproxion

import kotlinx.serialization.Serializable

@Serializable
data class RpPersonaPreset(
    /** The preset's label in the list. */
    val name: String,
    val description: String,
    /** The persona name saved with it. Null on presets from before the Name field: their label was the name. */
    val userName: String? = null
) {
    /** What the Name field shows after loading this preset. */
    val personaName: String get() = userName ?: name
}
