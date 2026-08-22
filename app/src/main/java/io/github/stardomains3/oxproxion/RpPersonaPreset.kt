package io.github.stardomains3.oxproxion

import kotlinx.serialization.Serializable

@Serializable
data class RpPersonaPreset(
    val name: String,
    val description: String
)
