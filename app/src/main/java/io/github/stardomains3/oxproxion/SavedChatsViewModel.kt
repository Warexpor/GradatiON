package io.github.stardomains3.oxproxion

import android.app.Application
import android.net.Uri
import android.util.Log
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
        // A backup from a newer version may carry fields this one doesn't know.
        private val importJson = Json { ignoreUnknownKeys = true }
        private const val MAX_IMPORT_MESSAGES = 5000

        /**
         * Test hook. When false, the import leaves the side log in place and does not commit
         * the notes, as a kill after the rows landed would. True in production.
         */
        @androidx.annotation.VisibleForTesting
        internal var commitImportedNotesForTest: Boolean = true
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
        val prefs = SharedPreferencesHelper(getApplication())
        // Before the row goes: the open chat just parked its field under this id.
        prefs.saveAskComposerDrafts(ComposerDrafts.drop(prefs.getAskComposerDrafts(), sessionId))
        val photos = repository.scenePhotoNames(sessionId)
        val swipePhotos = ScenePhoto.fileNamesIn(prefs.getRpSwipeJson(sessionId).orEmpty())
        repository.deleteSession(sessionId)
        val unused = (photos + swipePhotos).distinct().filter {
            !repository.scenePhotoStillUsed(it) && !prefs.rpSwipeNamesPhoto(it, setOf(sessionId))
        }
        ScenePhoto.deleteSceneFiles(getApplication(), unused)
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
        val prefs = SharedPreferencesHelper(getApplication())
        out.append("{\"sessions\":[")
        sessions.forEachIndexed { index, session ->
            if (index > 0) out.append(',')
            try {
                val exportKey = session.characterId?.let { rpRepository.getCharacterById(it)?.exportKey }
                val forkJson = prefs.getChatForkMessagesJson(session.id)
                val forkIndex = prefs.getChatForkIndex(session.id)
                val editing = prefs.isChatForkEditing(session.id)
                ChatBackupWriter.writeSession(
                    out,
                    session,
                    characterExportKey = exportKey,
                    facts = prefs.getRpFacts(session.id).takeIf { it.isNotBlank() },
                    pinned = prefs.isSessionPinned(session.id),
                    forkIndex = forkIndex.takeIf { it >= 0 && !forkJson.isNullOrBlank() },
                    forkAnchor = prefs.getChatForkAnchor(session.id),
                    forkMessages = forkJson?.takeIf { it.isNotBlank() && forkIndex >= 0 },
                    swipeJson = prefs.getRpSwipeJson(session.id)?.takeIf { it.isNotBlank() },
                    draft = ComposerDrafts.text(prefs.getAskComposerDrafts(), session.id).takeIf { it.isNotBlank() },
                    forkEditing = editing.takeIf { it },
                    forkEditDraft = if (editing) prefs.getChatForkEditDraft(session.id).orEmpty() else null,
                ) { emit ->
                    repository.forEachMessage(session.id, emit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SavedChats", "Could not export \"${session.title}\"", e)
                throw e
            }
        }
        out.append("]}")
    }

    /**
     * Writes the backup to a cache file and syncs it, then copies it to [uri]. [uri] is not opened
     * until the cache file is complete. A copy that stops early throws, so the caller does not
     * report success. The destination is opened with mode "wt" so an overwrite truncates; a
     * failed copy can still leave a short file there.
     */
    /** Whether a backup would hold anything. Reads the table: [allSessions] stays empty until observed. */
    suspend fun hasSessions(): Boolean =
        withContext(Dispatchers.IO) { repository.getAllSessionsOnce().isNotEmpty() }

    suspend fun exportChatsTo(uri: Uri) {
        val app = getApplication<Application>()
        val cache = File(app.cacheDir, "chat-export-${System.nanoTime()}.json")
        withContext(Dispatchers.IO) {
            BackupIo.publish(cache, { app.contentResolver.openOutputStream(uri, "wt") }) { stream ->
                stream.writer(Charsets.UTF_8).buffered().use { writeChatsBackup(it) }
            }
        }
    }

    fun importChatsFromJson(jsonText: String, onResult: (ChatImportResult) -> Unit) {
        viewModelScope.launch {
            val result = importChatsFromJsonInternal(jsonText)
            onResult(result)
        }
    }

    internal suspend fun importChatsFromJsonInternal(jsonText: String): ChatImportResult {
        val app = getApplication<Application>()
        // Editors on Windows save a BOM. It is not JSON, and it used to fail an otherwise valid file.
        val text = jsonText.removePrefix("\uFEFF")
        val maxBytes = ImportBounds.MAX_TEXT_BYTES
        // The limit is in bytes; a string's length counts UTF-16 units, which undercounts non-ASCII text.
        if (text.length > maxBytes || text.toByteArray(Charsets.UTF_8).size > maxBytes) {
            return ChatImportResult.Error(
                app.getString(R.string.import_error_too_large, maxBytes / (1024 * 1024))
            )
        }

        // Reading the file and writing the database fail for different reasons, so the user is told which.
        val backup = try {
            importJson.decodeFromString<ChatBackup>(text)
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
                    timestamp = exportedSession.timestamp?.takeIf { it > 0L } ?: System.currentTimeMillis(),
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
            // One transaction. The side file is written before it commits, and it keeps a
            // previous log whose notes have not landed yet. A kill after the commit is finished
            // on the next launch. A log for a row that is not there is dropped.
            val carried = ChatImportSideLog.read(ChatImportSideLog.file(app)).orEmpty()
            repository.insertImportedSessions(batch) { ids ->
                val fresh = ids.mapIndexed { index, id ->
                    val exported = backup.sessions[index]
                    val session = batch[index].first
                    ImportedChatMeta(
                        id = id,
                        facts = exported.facts?.take(RpPromptEngine.MEMORY_MAX_CHARS)?.takeIf { it.isNotBlank() },
                        pinned = exported.pinned,
                        forkIndex = exported.forkIndex,
                        forkAnchor = exported.forkAnchor,
                        forkMessages = exported.forkMessages?.takeIf { it.isNotBlank() },
                        swipeJson = exported.swipe?.takeIf { it.isNotBlank() },
                        title = session.title,
                        timestamp = session.timestamp,
                        messageCount = batch[index].second.size,
                        draft = exported.draft?.take(ComposerDrafts.MAX_CHARS)?.takeIf { it.isNotBlank() },
                        forkEditing = exported.forkEditing,
                        forkEditDraft = exported.forkEditDraft,
                    )
                }
                val freshIds = fresh.map { it.id }.toSet()
                ChatImportSideLog.write(
                    ChatImportSideLog.file(app),
                    carried.filter { it.id !in freshIds } + fresh,
                )
            }
            if (commitImportedNotesForTest) {
                val pending = ChatImportSideLog.read(ChatImportSideLog.file(app)).orEmpty()
                val ok = pending.filter { entry ->
                    ChatImportSideLog.matches(
                        entry,
                        repository.getSessionById(entry.id),
                        repository.messageCount(entry.id),
                    )
                }.map { it.id }.toSet()
                val applied = ChatImportSideLog.resume(app) { it.id in ok }
                if (!applied) {
                    Log.e("SavedChats", "Imported chat notes could not be saved; will retry next launch")
                }
            }
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

    suspend fun lastMessagePrefixes(sessionIds: List<Long>): List<ChatMessage> =
        repository.lastMessagePrefixes(sessionIds)

    suspend fun searchWindows(sessionIds: List<Long>, query: String): List<MessageWindow> =
        repository.searchWindows(sessionIds, query)
}
