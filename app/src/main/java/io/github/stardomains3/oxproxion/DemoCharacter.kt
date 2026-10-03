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

    /**
     * Older stock cards. A field that still equals that seed is refreshed; a field the user
     * changed (including a cleared one, and the portrait uri) stays.
     */
    private val LEGACY_STOCK = listOf(
        RpCharacter(
            exportKey = EXPORT_KEY,
            name = "Vesna",
            personality = "A river cartographer who maps what the charts leave out. Dry, patient, " +
                "quietly brave; she notices everything and says half of it. Keeps her promises and " +
                "expects the same.",
            style = "Short spoken lines in quotes, actions in italics. Weather and small gestures " +
                "carry the mood. She asks one question at a time.",
            scenario = "A rain-soaked inn at the edge of a river town. Something happened upstream " +
                "at a spot circled on her map, and she has asked {{user}} to come with her.",
            greeting = "*The inn door bangs open on the wind. Vesna doesn't look up from the map " +
                "spread across the table, weighted down by a lantern and two cold cups of tea.*\n\n" +
                "\"Sit. You'll want to see this before you decide anything.\"",
            instruction = "Stay in the scene. Keep replies to a few short paragraphs.",
        ),
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
            // The row is here, so a lost seed flag must be repaired before a later delete.
            // Otherwise the next launch treats her as never seeded and puts her back.
            if (!prefs.isDemoCharacterSeeded()) prefs.markDemoCharacterSeeded()
            val refreshed = refreshStock(existing)
            if (refreshed != existing) repo.saveCharacter(refreshed)
            existing.id
        }
        if (prefs.demoCharacterAvatarRevision() < STOCK_AVATAR_REVISION) {
            RpAvatarStorage.saveFromResource(context, R.drawable.vesna_avatar, id)
            prefs.setDemoCharacterAvatarRevision(STOCK_AVATAR_REVISION)
        }
    }

    /** Replace only the fields that are still the previous stock. Edits and the portrait stay. */
    private fun refreshStock(existing: RpCharacter): RpCharacter {
        val was = LEGACY_STOCK.firstOrNull { it.personality == existing.personality } ?: return existing
        val now = character()
        fun field(current: String, previous: String, updated: String): String =
            if (current == previous) updated else current
        return existing.copy(
            name = field(existing.name, was.name, now.name),
            personality = now.personality,
            style = field(existing.style, was.style, now.style),
            greeting = field(existing.greeting, was.greeting, now.greeting),
            scenario = field(existing.scenario, was.scenario, now.scenario),
            instruction = field(existing.instruction, was.instruction, now.instruction),
            prompt = field(existing.prompt, was.prompt, now.prompt),
            examplesJson = field(existing.examplesJson, was.examplesJson, now.examplesJson),
        )
    }
}
