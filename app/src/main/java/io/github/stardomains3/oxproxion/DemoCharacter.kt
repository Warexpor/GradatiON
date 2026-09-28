package io.github.stardomains3.oxproxion

import android.content.Context

/**
 * The roleplay card that ships with the app, the character side of [DemoModel]: pick her with
 * the Demo model and a whole scene plays offline, since the scripted replies are her scene.
 * Seeded once; deleting her keeps her gone. Stock copy refreshes when her personality still
 * matches a known previous seed (user edits are left alone). Stock avatar installs once per
 * revision and is not rewritten after the user changes it.
 */
object DemoCharacter {
    /** Stable across installs so backups and re-imports rematch her chats. */
    const val EXPORT_KEY = "gradation-demo-vesna"

    /** Bump when the packaged stock avatar changes and should overwrite older seed copies once. */
    const val STOCK_AVATAR_REVISION = 1

    /** Older stock personality strings — used to soft-upgrade unedited installs. */
    private val LEGACY_STOCK_PERSONALITIES = setOf(
        "A river cartographer who maps what the charts leave out. Dry, patient, " +
            "quietly brave; she notices everything and says half of it. Keeps her promises and " +
            "expects the same.",
    )

    fun character() = RpCharacter(
        exportKey = EXPORT_KEY,
        name = "Vesna",
        personality = "A woman half-held by mist. Soft-spoken, patient; she watches more than " +
            "she explains. Warm when she chooses to be, never loud.",
        style = "Short spoken lines in quotes, actions in italics. Weather and small gestures " +
            "carry the mood. She asks one question at a time. Leave some things unsaid.",
        scenario = "A quiet room at the edge of weather. Rain on the glass. She has been waiting " +
            "for {{user}}, and will only speak plainly once they sit.",
        greeting = "*Rain softens the window. Vesna sits in the darker half of the room, hair a " +
            "shadow against the glass. She does not turn fully toward you.*\n\n" +
            "\"You came. Sit with me a moment.\"",
        instruction = "Stay in the mood. Keep replies short. Prefer suggestion over plot.",
    )

    suspend fun seedOnce(repo: RpRepository, prefs: SharedPreferencesHelper, context: Context) {
        val existing = repo.getCharacterByExportKey(EXPORT_KEY)
        val id = if (existing == null) {
            if (prefs.isDemoCharacterSeeded()) return
            val savedId = repo.saveCharacter(character())
            prefs.markDemoCharacterSeeded()
            savedId
        } else {
            if (existing.personality in LEGACY_STOCK_PERSONALITIES) {
                repo.saveCharacter(character().copy(id = existing.id))
            }
            existing.id
        }
        if (prefs.demoCharacterAvatarRevision() < STOCK_AVATAR_REVISION) {
            RpAvatarStorage.saveFromResource(context, R.drawable.vesna_avatar, id)
            prefs.setDemoCharacterAvatarRevision(STOCK_AVATAR_REVISION)
        }
    }
}
