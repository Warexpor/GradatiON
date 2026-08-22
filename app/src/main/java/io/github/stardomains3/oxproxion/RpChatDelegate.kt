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
        extraInstruction: String? = null
    ): String {
        val isLlm = prefs.isRpLlmMode()
        val lore = if (prefs.isRpLoreEnabled()) {
            rpRepository.getActiveLorebook()?.content.orEmpty()
        } else {
            ""
        }
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
            lang = prefs.getRpLang(),
            lore = lore,
            instruction = instruction,
            thirdPerson = prefs.isRpThirdPerson(),
            showThoughts = prefs.isRpShowThoughts(),
            isLlm = isLlm
        )
    }

    fun cleanReply(text: String): String = RpReplyCleaner.clean(text)

    fun parseSendText(raw: String): RpReminderParser.Parsed = RpReminderParser.parse(raw)

    suspend fun activateCharacter(character: RpCharacter) {
        prefs.saveRpActiveCharacterId(character.id)
    }

    fun greetingMessage(character: RpCharacter): String =
        character.greeting.ifBlank { prefs.string(R.string.rp_default_greeting, character.name) }

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
