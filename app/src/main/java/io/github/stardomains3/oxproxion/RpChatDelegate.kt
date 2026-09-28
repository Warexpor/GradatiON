package io.github.stardomains3.oxproxion

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RpChatDelegate(
    private val rpRepository: RpRepository,
    private val prefs: SharedPreferencesHelper
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getActiveCharacter(): RpCharacter? {
        // LLM mode is GradatiON-as-self — never treat a leftover character id as active.
        if (prefs.isRpLlmMode()) return null
        val id = prefs.getRpActiveCharacterId() ?: return null
        return rpRepository.getCharacterById(id)
    }

    suspend fun buildSystemPrompt(
        character: RpCharacter?,
        extraInstruction: String? = null,
        loreScan: String = "",
        definitionCap: Int? = null,
        facts: String = ""
    ): String {
        val isLlm = prefs.isRpLlmMode()
        val lore = resolveLore(isLlm, character, loreScan)
        val charInstruction = if (isLlm) "" else character?.instruction.orEmpty()
        val pending = prefs.getRpPendingInstruct()
        val instruction = RpPromptEngine.combineInstruction(
            charInstruction,
            extraInstruction,
            pending
        )
        return RpPromptEngine.buildSystemPrompt(
            character = if (isLlm) null else character,
            persona = prefs.getRpPersona(),
            lore = lore,
            instruction = instruction,
            thirdPerson = prefs.isRpThirdPerson(),
            showThoughts = prefs.isRpShowThoughts(),
            isLlm = isLlm,
            memory = prefs.getRpMemory(if (isLlm) null else character?.id),
            facts = if (prefs.isRpAutoMemory()) facts else "",
            userName = prefs.getRpPersonaName(),
            definitionCap = definitionCap
        )
    }

    /**
     * A character can pin its own book. Otherwise the global active book is used.
     * A pin that points at a deleted book falls back to the active one.
     */
    private suspend fun resolveLore(isLlm: Boolean, character: RpCharacter?, scan: String): String {
        if (!prefs.isRpLoreEnabled()) return ""
        val pinned = if (!isLlm && character != null) prefs.getRpLorebookId(character.id) else null
        val book = if (pinned != null) {
            rpRepository.getLorebookById(pinned) ?: rpRepository.getActiveLorebook()
        } else {
            rpRepository.getActiveLorebook()
        }
        return RpLore.select(book?.content.orEmpty(), scan)
    }

    fun cleanReply(text: String): String = RpReplyCleaner.clean(text)

    fun parseSendText(raw: String): RpReminderParser.Parsed = RpReminderParser.parse(raw)

    suspend fun activateCharacter(character: RpCharacter) {
        prefs.saveRpActiveCharacterId(character.id)
    }

    fun greetingMessage(character: RpCharacter): String =
        RpPromptEngine.expandMacros(
            character.greeting.ifBlank { prefs.string(R.string.rp_default_greeting, character.name) },
            character.name,
            prefs.getRpPersonaName().ifBlank { prefs.string(R.string.rp_you) }
        )

    fun sessionTitle(character: RpCharacter?): String {
        if (prefs.isRpLlmMode()) return prefs.string(R.string.rp_session_llm)
        val fallback = prefs.string(R.string.rp_session_default)
        return character?.name?.ifBlank { fallback } ?: fallback
    }

    fun encodeExamples(examples: List<RpExampleDialog>): String =
        json.encodeToString(examples)

    fun formatExamplesForEdit(examplesJson: String): String =
        RpPromptEngine.formatExamplesForEdit(examplesJson)

    fun parseExamplesFromEdit(text: String): List<RpExampleDialog> =
        RpPromptEngine.parseExamplesFromEdit(text)
}
