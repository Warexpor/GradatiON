package io.github.stardomains3.oxproxion

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val photoUri: String? = null,
    /**
     * Story memory. Null means a backup from before this field existed, which must not wipe
     * memory already on the phone. An empty string is a memory the user cleared.
     */
    val memory: String? = null,
    /** [SharedPreferencesHelper.RP_LAYOUT_CLASSIC] and the other layouts. Null leaves the local one. */
    val layout: String? = null,
    /** Null with no pitch or rate means the backup does not carry a voice. */
    val voiceName: String? = null,
    val voicePitch: Float? = null,
    val voiceRate: Float? = null,
    /**
     * Lorebook pinned to this character, by name. Null leaves the local pin. Empty clears it.
     * A name with no matching book is kept until that book is imported.
     */
    val lorebookName: String? = null
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

/** Writes one character or lorebook at a time so the backup is not also held as one string. */
internal object RpBackupWriter {
    private val json = Json { ignoreUnknownKeys = true }

    fun writeCharacters(out: Appendable, characters: List<RpCharacterExport>) {
        out.append("{\"characters\":[")
        characters.forEachIndexed { index, character ->
            if (index > 0) out.append(',')
            out.append(json.encodeToString(RpCharacterExport.serializer(), character))
        }
        out.append("]}")
    }

    fun writeLorebooks(out: Appendable, lorebooks: List<RpLorebookExport>) {
        out.append("{\"lorebooks\":[")
        lorebooks.forEachIndexed { index, book ->
            if (index > 0) out.append(',')
            out.append(json.encodeToString(RpLorebookExport.serializer(), book))
        }
        out.append("]}")
    }
}

/**
 * Memory, layout, voice and the lorebook pin live in preferences, keyed by the character's
 * Room id, so a backup of the card alone used to drop them on the next phone.
 */
internal object RpCharacterPrefsBackup {
    private val layouts = setOf(
        SharedPreferencesHelper.RP_LAYOUT_CLASSIC,
        SharedPreferencesHelper.RP_LAYOUT_BUBBLES,
        SharedPreferencesHelper.RP_LAYOUT_BOOK,
    )

    fun apply(
        prefs: SharedPreferencesHelper,
        characterId: Long,
        exported: RpCharacterExport,
        lorebooks: List<RpLorebook>,
    ) {
        exported.memory?.let {
            prefs.saveRpMemory(characterId, it.take(RpPromptEngine.MEMORY_MAX_CHARS))
        }
        exported.layout?.takeIf { it in layouts }?.let { prefs.saveRpLayout(characterId, it) }
        // Pitch or rate present means this backup carries a voice. A missing name is the default
        // voice, not "leave whatever is already set".
        if (exported.voicePitch != null || exported.voiceRate != null) {
            val current = prefs.getRpVoice(characterId)
            prefs.saveRpVoice(
                characterId,
                SharedPreferencesHelper.RpVoice(
                    name = exported.voiceName?.takeIf { it.isNotBlank() },
                    pitch = exported.voicePitch?.takeIf { it.isFinite() && it in 0.25f..4f } ?: current.pitch,
                    rate = exported.voiceRate?.takeIf { it.isFinite() && it in 0.25f..4f } ?: current.rate,
                )
            )
        }
        when {
            exported.lorebookName == null -> Unit
            exported.lorebookName.isBlank() -> {
                prefs.saveRpLorebookId(characterId, null)
                prefs.savePendingRpLorebookName(characterId, null)
            }
            else -> bindLorebook(prefs, characterId, exported.lorebookName, lorebooks)
        }
    }

    /** After a lorebook import, attach pins that were waiting for a book that was not here yet. */
    fun bindPending(prefs: SharedPreferencesHelper, lorebooks: List<RpLorebook>) {
        for ((characterId, name) in prefs.pendingRpLorebookNames()) {
            bindLorebook(prefs, characterId, name, lorebooks)
        }
    }

    private fun bindLorebook(
        prefs: SharedPreferencesHelper,
        characterId: Long,
        name: String,
        lorebooks: List<RpLorebook>,
    ) {
        val match = lorebooks.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (match != null) {
            prefs.saveRpLorebookId(characterId, match.id)
            prefs.savePendingRpLorebookName(characterId, null)
        } else {
            prefs.savePendingRpLorebookName(characterId, name.take(200))
        }
    }
}
