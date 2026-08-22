package io.github.stardomains3.oxproxion

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

sealed class ChatImportResult {
    data object Success : ChatImportResult()
    data class Error(val message: String) : ChatImportResult()
}

class SavedChatsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private val json = Json { prettyPrint = true }
        private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
        private const val MAX_IMPORT_MESSAGES = 5000
    }
    private val repository: ChatRepository
    private val rpRepository: RpRepository
    val allSessions: LiveData<List<ChatSession>>

    init {
        val db = AppDatabase.getDatabase(application)
        repository = ChatRepository(db.chatDao())
        rpRepository = RpRepository(db.rpDao())
        allSessions = repository.allSessions
    }

    fun sessionsForMode(mode: ChatMode): LiveData<List<ChatSession>> =
        repository.sessionsByMode(mode)

    fun deleteSession(sessionId: Long) = viewModelScope.launch {
        repository.deleteSession(sessionId)
        val prefs = SharedPreferencesHelper(getApplication())
        listOf(ChatMode.ASK, ChatMode.RP).forEach { mode ->
            if (prefs.getRpDraftSessionId(mode) == sessionId) {
                prefs.saveRpDraftSessionId(mode, null)
            }
        }
        prefs.clearRpSwipeJson(sessionId)
    }

    fun updateSessionTitle(sessionId: Long, newTitle: String) = viewModelScope.launch {
        repository.updateSessionTitle(sessionId, newTitle)
    }
    suspend fun getChatsAsJson(): String {
        val sessionsWithMessages = repository.getAllSessionsWithMessages()
        val exportedSessions = sessionsWithMessages.map { sessionWithMessages ->
            val charId = sessionWithMessages.session.characterId
            val exportKey = charId?.let { rpRepository.getCharacterById(it)?.exportKey }
            ExportedChatSession(
                title = sessionWithMessages.session.title,
                modelUsed = sessionWithMessages.session.modelUsed,
                messages = sessionWithMessages.messages.map { message ->
                    ExportedChatMessage(
                        role = message.role,
                        content = message.content
                    )
                },
                mode = sessionWithMessages.session.mode,
                characterId = charId,
                characterExportKey = exportKey,
                isLlm = sessionWithMessages.session.isLlm
            )
        }
        val backup = ChatBackup(sessions = exportedSessions)
        return json.encodeToString(backup)
    }

    fun importChatsFromJson(jsonText: String, onResult: (ChatImportResult) -> Unit) {
        viewModelScope.launch {
            val result = importChatsFromJsonInternal(jsonText)
            onResult(result)
        }
    }

    private suspend fun importChatsFromJsonInternal(jsonText: String): ChatImportResult {
        if (jsonText.length > MAX_IMPORT_BYTES) {
            return ChatImportResult.Error("Import file too large (max 5 MB)")
        }

        return try {
            val backup = Json.decodeFromString<ChatBackup>(jsonText)
            val totalMessages = backup.sessions.sumOf { it.messages.size }
            if (totalMessages > MAX_IMPORT_MESSAGES) {
                return ChatImportResult.Error("Too many messages (max 5,000)")
            }

            for (exportedSession in backup.sessions) {
                val characterId = when {
                    !exportedSession.characterExportKey.isNullOrBlank() ->
                        rpRepository.getCharacterByExportKey(exportedSession.characterExportKey)?.id
                    exportedSession.characterId != null ->
                        exportedSession.characterId.takeIf { rpRepository.getCharacterById(it) != null }
                    else -> null
                }
                val session = ChatSession(
                    title = exportedSession.title,
                    modelUsed = exportedSession.modelUsed,
                    mode = exportedSession.mode,
                    characterId = characterId,
                    isLlm = exportedSession.isLlm
                )
                val messages = exportedSession.messages.map { exportedMessage ->
                    ChatMessage(
                        sessionId = 0,
                        role = exportedMessage.role,
                        content = exportedMessage.content
                    )
                }
                repository.insertSessionAndMessages(session, messages)
            }
            ChatImportResult.Success
        } catch (e: Exception) {
            ChatImportResult.Error("Invalid backup format")
        }
    }

    suspend fun searchSessions(query: String, mode: ChatMode = ChatMode.ASK): List<ChatSession> {
        return repository.searchSessions(query, mode)
    }
}
