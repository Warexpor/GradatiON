package io.github.stardomains3.oxproxion.code

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * In-process stand-in for a bridge, so Code mode can be seen and tested with no machine set up.
 * Plays a scripted but realistic turn: thinking, reads, a search, a plan, streamed prose, an edit
 * that asks for approval, a diff, a test run with output, and a summary. Clearly labelled
 * "Demo" everywhere it appears. Remove from release builds once real hosts are common (plan).
 */
class DemoBackend(
    override val host: CodeHost,
    private val scope: CoroutineScope
) : CodeBackend {

    override val connection: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.CONNECTED)
    override val lastError: String? = null
    private val _updates = MutableSharedFlow<SessionUpdate>(extraBufferCapacity = 256)
    override val updates: SharedFlow<SessionUpdate> = _updates

    private val approvals = HashMap<String, CompletableDeferred<ApprovalOption?>>()
    private val turns = HashMap<String, Job>()
    private var seq = 0

    override fun connect() = Unit

    override suspend fun listSessions(): List<CodeSessionSummary> {
        val now = System.currentTimeMillis()
        return listOf(
            CodeSessionSummary(
                id = "demo-seed-1", hostId = host.id, harness = HarnessKind.CODEX,
                workspace = "~/code/gradation-bridge", title = "Add reconnect backoff to the bridge",
                createdAt = now - 3 * HOUR, updatedAt = now - 2 * HOUR,
                preview = "Backoff is capped at 30 s and resets after a clean hour.", branch = "main"
            ),
            CodeSessionSummary(
                id = "demo-seed-2", hostId = host.id, harness = HarnessKind.OPENCODE,
                workspace = "~/code/site", title = "Fix the broken footer links",
                createdAt = now - 26 * HOUR, updatedAt = now - 25 * HOUR,
                preview = "All 14 links resolve now.", branch = "fix/footer"
            )
        )
    }

    override suspend fun listWorkspaces(harness: HarnessKind) =
        listOf("~/code/GradatiON", "~/code/gradation-bridge", "~/code/site", "~/notes")

    override suspend fun listHarnesses(): List<HarnessInfo> = listOf(
        HarnessInfo("claude-code", "Claude Code", available = true),
        HarnessInfo("codex", "Codex CLI", available = true),
        HarnessInfo("opencode", "OpenCode", available = true),
        HarnessInfo("grok-build", "Grok Build", available = true),
        HarnessInfo("cursor-cli", "Cursor CLI", available = false),
        HarnessInfo("pi", "Pi", available = false)
    )

    override suspend fun browse(path: String): List<BrowseEntry> {
        // Scripted tree so the folder picker can be exercised without a bridge.
        val normalized = path.trimEnd('/')
        return when {
            normalized.isEmpty() || normalized == "~" -> listOf(
                BrowseEntry("code", true),
                BrowseEntry("notes", true),
                BrowseEntry(".bashrc", false)
            )
            normalized == "~/code" || normalized.endsWith("/code") -> listOf(
                BrowseEntry("GradatiON", true),
                BrowseEntry("gradation-bridge", true),
                BrowseEntry("site", true),
                BrowseEntry("README.md", false)
            )
            else -> listOf(
                BrowseEntry("src", true),
                BrowseEntry("README.md", false)
            )
        }
    }

    override suspend fun gitStatus(sessionId: String): GitStatusResult = GitStatusResult(
        branch = "main",
        ahead = 1,
        behind = 0,
        files = listOf(
            GitFileStatus("src/server/ws.ts", " M"),
            GitFileStatus("README.md", "M "),
            GitFileStatus("docs/notes.md", "??")
        )
    )

    override suspend fun diff(sessionId: String, path: String): GitDiffResult {
        val name = path.substringAfterLast('/')
        return GitDiffResult(
            unified = """diff --git a/$path b/$path
--- a/$path
+++ b/$path
@@ -1,3 +1,4 @@
 context line
-old line in $name
+new line in $name
 more context
+added line
""".trimIndent()
        )
    }

    override suspend fun startSession(request: NewSessionRequest): CodeSessionSummary {
        val now = System.currentTimeMillis()
        val id = "demo-${now}"
        scope.launch { prompt(id, request.prompt) }
        return CodeSessionSummary(
            id = id, hostId = host.id, harness = request.harness, workspace = request.workspace,
            title = request.prompt.lineSequence().first().take(60), createdAt = now, updatedAt = now,
            permissionMode = request.permissionMode, branch = "main"
        )
    }

    override suspend fun attach(session: CodeSessionSummary) {
        if (!session.id.startsWith("demo-seed")) return
        val t = session.updatedAt
        fun up(u: CodeUpdate) = _updates.tryEmit(SessionUpdate(session.id, u))
        up(CodeUpdate.AvailableCommands(DEMO_SLASH_COMMANDS))
        up(CodeUpdate.Upsert(CodeEvent.UserPrompt("u0", t - 60_000, session.title)))
        up(CodeUpdate.Upsert(CodeEvent.ToolCall("tool:s1", t - 50_000, "s1", ToolKind.SEARCH, "Search", "rg -n \"reconnect\" src/", ToolStatus.COMPLETED, "src/ws.ts:41: // TODO reconnect\nsrc/ws.ts:88: function reconnect()")))
        up(CodeUpdate.Upsert(CodeEvent.AgentText("a0", t - 40_000, session.preview, streaming = false)))
        up(CodeUpdate.TurnDone("end_turn", "2 files changed · 41 s"))
    }

    override suspend fun prompt(sessionId: String, text: String, attachments: List<PromptAttachment>) {
        turns[sessionId]?.cancel()
        turns[sessionId] = scope.launch { playTurn(sessionId, text, attachments.size) }
    }

    override suspend fun answer(sessionId: String, requestId: String, option: ApprovalOption?) {
        _updates.emit(SessionUpdate(sessionId, CodeUpdate.ApprovalAnswered(requestId, option?.kind ?: ApprovalOption.Kind.REJECT_ONCE)))
        approvals.remove(requestId)?.complete(option)
    }

    override suspend fun cancel(sessionId: String) {
        turns.remove(sessionId)?.cancel()
        emit(sessionId, CodeUpdate.Upsert(CodeEvent.Notice(key(), now(), "Stopped", NoticeLevel.WARNING)))
        emit(sessionId, CodeUpdate.TurnDone("cancelled"))
    }

    override suspend fun setPermissionMode(sessionId: String, mode: PermissionMode) = Unit

    override fun close() {
        turns.values.forEach { it.cancel() }
        turns.clear()
    }

    // ── the script ────────────────────────────────────────────────────────────────────────

    private suspend fun playTurn(sid: String, text: String, attachmentCount: Int = 0) {
        emit(sid, CodeUpdate.AvailableCommands(DEMO_SLASH_COMMANDS))
        emit(sid, CodeUpdate.Upsert(CodeEvent.UserPrompt(key(), now(), text, attachmentCount = attachmentCount)))
        delay(500)
        val thought = key()
        stream(sid, thought, "The user wants a change in the settings flow. I should find where the theme preference lives before touching anything.", thought = true)

        val read = "r${seq++}"
        emit(sid, CodeUpdate.Upsert(CodeEvent.ToolCall("tool:$read", now(), read, ToolKind.READ, "Read SettingsRepository.kt", "app/src/main/java/app/SettingsRepository.kt", ToolStatus.RUNNING)))
        delay(700)
        emit(sid, CodeUpdate.ToolPatch(read, ToolStatus.COMPLETED))

        val search = "s${seq++}"
        emit(sid, CodeUpdate.Upsert(CodeEvent.ToolCall("tool:$search", now(), search, ToolKind.SEARCH, "Search", "rg -n \"darkMode\" app/src", ToolStatus.RUNNING)))
        delay(600)
        emit(sid, CodeUpdate.ToolPatch(search, ToolStatus.COMPLETED, output = "SettingsRepository.kt:18:  val darkMode = prefs.getBoolean(\"dark\", true)\nThemeController.kt:9:  fun apply(darkMode: Boolean)\nSettingsFragment.kt:52:  darkSwitch.isChecked = repo.darkMode"))

        val plan = listOf(
            PlanEntry("Find where the theme is stored", PlanStatus.COMPLETED),
            PlanEntry("Add a follow-system option", PlanStatus.IN_PROGRESS),
            PlanEntry("Run the unit tests", PlanStatus.PENDING)
        )
        emit(sid, CodeUpdate.Upsert(CodeEvent.Plan("plan:$sid", now(), plan)))
        delay(300)

        stream(sid, key(), "The theme lives in `SettingsRepository` as a plain boolean. I'll replace it with a three-way `ThemeMode` and keep the old key readable so nobody's setting resets.")

        val edit = "e${seq++}"
        val path = "app/src/main/java/app/SettingsRepository.kt"
        emit(sid, CodeUpdate.Upsert(CodeEvent.ToolCall("tool:$edit", now(), edit, ToolKind.EDIT, "Edit SettingsRepository.kt", path, ToolStatus.PENDING)))
        val reqId = "req${seq++}"
        val allow = ApprovalOption("allow", "Allow", ApprovalOption.Kind.ALLOW_ONCE)
        val always = ApprovalOption("always", "Always allow edits", ApprovalOption.Kind.ALLOW_ALWAYS)
        val reject = ApprovalOption("reject", "Deny", ApprovalOption.Kind.REJECT_ONCE)
        val waiter = CompletableDeferred<ApprovalOption?>()
        approvals[reqId] = waiter
        val oldText = OLD_FILE
        val newText = NEW_FILE
        val lines = Diff.between(oldText, newText)
        val (add, del) = Diff.counts(lines)
        emit(sid, CodeUpdate.Upsert(CodeEvent.FileDiff("diff:$edit", now(), edit, path, lines, add, del)))
        emit(sid, CodeUpdate.Upsert(CodeEvent.Approval("approval:$reqId", now(), reqId, edit, "Edit SettingsRepository.kt", path, ToolKind.EDIT, listOf(allow, always, reject))))
        val choice = waiter.await()
        if (choice == null || choice.kind == ApprovalOption.Kind.REJECT_ONCE || choice.kind == ApprovalOption.Kind.REJECT_ALWAYS) {
            emit(sid, CodeUpdate.ToolPatch(edit, ToolStatus.FAILED, output = "Denied by you"))
            stream(sid, key(), "Okay, I left the file alone. Tell me what you'd rather do and I'll take it from there.")
            emit(sid, CodeUpdate.TurnDone("end_turn", "No changes"))
            return
        }
        emit(sid, CodeUpdate.ToolPatch(edit, ToolStatus.COMPLETED))
        emit(sid, CodeUpdate.Upsert(CodeEvent.Plan("plan:$sid", now(), listOf(
            plan[0], plan[1].copy(status = PlanStatus.COMPLETED), plan[2].copy(status = PlanStatus.IN_PROGRESS)
        ))))

        val test = "t${seq++}"
        emit(sid, CodeUpdate.Upsert(CodeEvent.ToolCall("tool:$test", now(), test, ToolKind.EXECUTE, "Run tests", "./gradlew :app:testDebugUnitTest", ToolStatus.RUNNING)))
        val log = StringBuilder()
        for (l in TEST_LOG) {
            delay(260)
            log.appendLine(l)
            emit(sid, CodeUpdate.ToolPatch(test, output = log.toString().trimEnd()))
        }
        emit(sid, CodeUpdate.ToolPatch(test, ToolStatus.COMPLETED))
        emit(sid, CodeUpdate.Upsert(CodeEvent.Plan("plan:$sid", now(), plan.map { it.copy(status = PlanStatus.COMPLETED) })))
        stream(sid, key(), "Done. `ThemeMode` now has **System**, **Light** and **Dark**; the old boolean migrates on first read. All 48 tests pass.")
        emit(sid, CodeUpdate.TurnDone("end_turn", "1 file changed · 38 s"))
    }

    private suspend fun stream(sid: String, key: String, text: String, thought: Boolean = false) {
        val words = text.split(' ')
        for ((i, w) in words.withIndex()) {
            emit(sid, CodeUpdate.TextChunk(key, if (i == 0) w else " $w", thought))
            delay(if (thought) 18 else 34)
        }
    }

    private suspend fun emit(sid: String, u: CodeUpdate) = _updates.emit(SessionUpdate(sid, u))
    private fun key() = "demo:${seq++}"
    private fun now() = System.currentTimeMillis()

    private companion object {
        val DEMO_SLASH_COMMANDS = listOf(
            AvailableCommand("compact", "Compact conversation context"),
            AvailableCommand("clear", "Clear session context for a fresh start"),
            AvailableCommand(
                "plan",
                "Switch into plan mode and outline the approach",
                inputHint = "what to plan",
            ),
            AvailableCommand(
                "help",
                "Show help for a topic",
                inputHint = "topic",
            ),
        )

        const val HOUR = 3_600_000L

        val OLD_FILE = """
            package app

            class SettingsRepository(private val prefs: Prefs) {
                val darkMode: Boolean
                    get() = prefs.getBoolean("dark", true)

                fun setDarkMode(on: Boolean) {
                    prefs.putBoolean("dark", on)
                }
            }
        """.trimIndent()

        val NEW_FILE = """
            package app

            enum class ThemeMode { SYSTEM, LIGHT, DARK }

            class SettingsRepository(private val prefs: Prefs) {
                val themeMode: ThemeMode
                    get() = prefs.getString("theme_mode")?.let { ThemeMode.valueOf(it) }
                        ?: if (prefs.getBoolean("dark", true)) ThemeMode.DARK else ThemeMode.LIGHT

                fun setThemeMode(mode: ThemeMode) {
                    prefs.putString("theme_mode", mode.name)
                }
            }
        """.trimIndent()

        val TEST_LOG = listOf(
            "> Task :app:compileDebugKotlin",
            "> Task :app:testDebugUnitTest",
            "SettingsRepositoryTest > migratesLegacyDarkFlag PASSED",
            "SettingsRepositoryTest > defaultsToSystem PASSED",
            "ThemeControllerTest > appliesMode PASSED",
            "BUILD SUCCESSFUL in 21s",
            "48 tests completed, 0 failed"
        )
    }
}
