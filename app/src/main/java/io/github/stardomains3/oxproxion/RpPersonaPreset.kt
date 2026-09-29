package io.github.stardomains3.oxproxion

import kotlinx.serialization.Serializable

@Serializable
data class RpPersonaPreset(
    val name: String,
    val description: String,
    /** Portrait file name in [RpAvatarStorage.personaFile], or null for the initial. */
    val photo: String? = null
)
