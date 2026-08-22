package io.github.stardomains3.oxproxion

import kotlinx.serialization.Serializable

@Serializable
data class RpCharacterBackup(
    val characters: List<RpCharacterExport>
)

@Serializable
data class RpCharacterExport(
    val name: String,
    val personality: String = "",
    val style: String = "",
    val greeting: String = "",
    val scenario: String = "",
    val examplesJson: String = "[]",
    val prompt: String = "",
    val instruction: String = "",
    /** Stable key used to rematch chats after re-import. */
    val exportKey: String = "",
    /** Embedded JPEG as Base64; preferred over [photoUri] for portable backups. */
    val avatarBase64: String? = null,
    /** Legacy local file:// path — ignored on import when [avatarBase64] is set. */
    val photoUri: String? = null
)

@Serializable
data class RpLorebookBackup(
    val lorebooks: List<RpLorebookExport>
)

@Serializable
data class RpLorebookExport(
    val name: String,
    val content: String = "",
    val isActive: Boolean = false
)
