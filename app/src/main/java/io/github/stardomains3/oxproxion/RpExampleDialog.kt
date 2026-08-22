package io.github.stardomains3.oxproxion

import kotlinx.serialization.Serializable

@Serializable
data class RpExampleDialog(
    val user: String = "",
    val char: String = ""
)
