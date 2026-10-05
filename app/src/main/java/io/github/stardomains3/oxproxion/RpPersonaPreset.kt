package io.github.stardomains3.oxproxion

import kotlinx.serialization.Serializable

@Serializable
data class RpPersonaPreset(
    val name: String,
    val description: String,
    /** Portrait file name in [RpAvatarStorage.personaFile], or null for the initial. */
    val photo: String? = null
)

/** The persona one character sees: who you are with them, and whether it is on. */
@Serializable
data class RpPersonaChoice(
    val name: String = "",
    val description: String = "",
    val photo: String? = null,
    val enabled: Boolean = true,
)
