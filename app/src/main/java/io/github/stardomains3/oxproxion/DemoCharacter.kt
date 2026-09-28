package io.github.stardomains3.oxproxion

/**
 * The roleplay card that ships with the app, the character side of [DemoModel]: pick her with
 * the Demo model and a whole scene plays offline, since the scripted replies are her scene.
 * Seeded once; deleting her keeps her gone.
 */
object DemoCharacter {
    /** Stable across installs so backups and re-imports rematch her chats. */
    const val EXPORT_KEY = "gradation-demo-vesna"

    fun character() = RpCharacter(
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
    )

    suspend fun seedOnce(repo: RpRepository, prefs: SharedPreferencesHelper) {
        if (prefs.isDemoCharacterSeeded()) return
        if (repo.getCharacterByExportKey(EXPORT_KEY) == null) repo.saveCharacter(character())
        prefs.markDemoCharacterSeeded()
    }
}
