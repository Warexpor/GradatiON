package io.github.stardomains3.oxproxion

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

sealed class ChatImportResult {
    data object Success : ChatImportResult()
    data class Error(val message: String) : ChatImportResult()
}

class SavedChatsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private val json = Json { prettyPrint = true }
        // A backup from a newer version may carry fields this one doesn't know.
        private val importJson = Json { ignoreUnknownKeys = true }
        private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
        private const val MAX_IMPORT_MESSAGES = 5000
    }
    // Lazy, so building the ViewModel never opens the encrypted database on the main thread; the
    // chat screen's ViewModel has already started that on IO by the time a history screen exists.
    private val repository: ChatRepository by lazy {
        ChatRepository(AppDatabase.getDatabase(getApplication()).chatDao())
    }
    private val rpRepository: RpRepository by lazy {
        RpRepository(AppDatabase.getDatabase(getApplication()).rpDao())
    }
    val allSessions: LiveData<List<ChatSession>> by lazy { repository.allSessions }

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
        // Fork, swipe alternates and memory facts live in prefs, keyed by the session id.
        prefs.clearSessionPrefs(sessionId)
    }

    fun updateSessionTitle(sessionId: Long, newTitle: String) = viewModelScope.launch {
        repository.updateSessionTitle(sessionId, newTitle)
    }
    suspend fun getChatsAsJson(): String = buildString { writeChatsBackup(this) }

    /**
     * Writes the backup one chat at a time. The whole history used to be loaded, then copied into
     * one string, which was enough to kill the process on a long chat list.
     */
    suspend fun writeChatsBackup(out: Appendable) {
        val sessions = repository.getAllSessionsOnce()
        out.append("{\"sessions\":[")
        sessions.forEachIndexed { index, session ->
            if (index > 0) out.append(',')
            out.append(json.encodeToString(exportedSession(session)))
        }
        out.append("]}")
    }

    /**
     * Writes the backup to a cache file first, then copies it to [uri]. A failure leaves the
     * destination untouched instead of a truncated JSON file.
     */
    suspend fun exportChatsTo(uri: Uri) {
        val app = getApplication<Application>()
        val cache = File(app.cacheDir, "chat-export-${System.nanoTime()}.json")
        try {
            withContext(Dispatchers.IO) {
                cache.outputStream().buffered().use { stream ->
                    stream.writer(Charsets.UTF_8).buffered().use { writeChatsBackup(it) }
                }
                app.contentResolver.openOutputStream(uri)?.use { dest ->
                    cache.inputStream().buffered().use { src -> src.copyTo(dest) }
                } ?: error("Could not open the export file")
            }
        } finally {
            cache.delete()
        }
    }

    private suspend fun exportedSession(session: ChatSession): ExportedChatSession {
        val messages = repository.getMessagesForSession(session.id)
        val exportKey = session.characterId?.let { rpRepository.getCharacterById(it)?.exportKey }
        return ExportedChatSession(
            title = session.title,
            modelUsed = session.modelUsed,
            messages = messages.map { message ->
                ExportedChatMessage(role = message.role, content = message.content)
            },
            mode = session.mode,
            characterId = session.characterId,
            characterExportKey = exportKey,
            isLlm = session.isLlm
        )
    }

    fun importChatsFromJson(jsonText: String, onResult: (ChatImportResult) -> Unit) {
        viewModelScope.launch {
            val result = importChatsFromJsonInternal(jsonText)
            onResult(result)
        }
    }

    internal suspend fun importChatsFromJsonInternal(jsonText: String): ChatImportResult {
        val app = getApplication<Application>()
        // The limit is in bytes; a string's length counts UTF-16 units, which undercounts non-ASCII text.
        if (jsonText.length > MAX_IMPORT_BYTES || jsonText.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_BYTES) {
            return ChatImportResult.Error(app.getString(R.string.import_error_too_large))
        }

        // Reading the file and writing the database fail for different reasons, so the user is told which.
        val backup = try {
            importJson.decodeFromString<ChatBackup>(jsonText)
        } catch (e: SerializationException) {
            return ChatImportResult.Error(app.getString(R.string.import_error_format))
        } catch (e: IllegalArgumentException) {
            return ChatImportResult.Error(app.getString(R.string.import_error_format))
        }
        val totalMessages = backup.sessions.sumOf { it.messages.size }
        if (totalMessages > MAX_IMPORT_MESSAGES) {
            return ChatImportResult.Error(app.getString(R.string.import_error_too_many))
        }

        return try {
            val batch = backup.sessions.map { exportedSession ->
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
                session to messages
            }
            // One transaction: a failure part-way leaves the chat list as it was.
            val newIds = repository.insertImportedSessions(batch)
            // A new row can take an id a deleted chat used to have; drop that chat's leftovers.
            val prefs = SharedPreferencesHelper(app)
            newIds.forEach { prefs.clearSessionPrefs(it) }
            ChatImportResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChatImportResult.Error(app.getString(R.string.import_error_database))
        }
    }

    suspend fun searchSessions(query: String, mode: ChatMode = ChatMode.ASK): List<ChatSession> {
        return repository.searchSessions(query, mode)
    }
}
