# Handoff (2026-10-02, Stability wave 22)

On `gradation/w22-stability` (PR into `gradation/app-pass`). Persistence follow-ups after w20 incomplete DB sets (sat out w21):
- Legacy `pre_sqlcipher` / `encrypting` leftovers move as a set (main + wal/shm/journal) into the no-backup vault. When the vault already has that short name, the databases-root set is discarded (not `uniqueKept`, which left sidecars at the root). A move failure still parks under `chat_db_hold`. Sidecar-only leftovers are parked then discarded. Hold drains those names when the vault is free, and drops a hold duplicate when the vault already has the restore copy.
- Code away-notification id allocations `commit` when posted and cleared (open tokens already did in wave 20), so a kill cannot leave the shade entry uncancelable or keep a stale id. `code_away_open_tokens` / `code_away_notif_ids` are excluded from Auto Backup and device transfer.

Phone: park `chat_database.pre_sqlcipher` + `-wal` beside the live DB (or only a `-wal`); after relaunch both should leave the databases root. Force a vault name collision on `pre_sqlcipher` (root clears; vault copy stays). Post a Code away approval, kill mid-post, relaunch and dismiss (shade should clear).

# Handoff (2026-10-02, Code wave 22)

On `gradation/w22-code` (PR into `gradation/app-pass`). Three Code-mode fixes, unit-tested (`CodeProtocolTest`, `ListSessionsJsonTest`):
- ACP permission and Cursor ask answers send digit-string `optionId` / `questionId` / selected option ids (and accepted todo ids) as JSON numbers, same as the request id, so a proxy that rewrote `5.0` still gets a numeric Allow.
- A `sessionId` written as a whole-number double (`5.0` / `"5.0"`) still matches as `"5"` on live updates, listSessions, and newSession.
- Cursor Agent tool names `BrowserClick`, `BrowserType`, `BrowserSnapshot`, `BrowserWait`, `TakeScreenshot`, `KillShell`, `ListShells`, `CreateIssue`, `CreateBranch`, `MergePullRequest`, `AddIssueComment`, `GetPullRequest`, `GetFileContents`, and `SearchCode` map to execute / read / think / edit / fetch / search cards. Tool detail lines read `fileId`, `draftId`, `folderId`, `destination_path` / `target_path`, `owner`, and `repo` / `repository`.

Phone: on Cursor Agent, a BrowserClick or CreateIssue card should show the shell or edit icon. A DownloadFile card with `fileId` should show that id under the title. An approval whose optionId is `5` should Allow with a JSON number `5`.

# Handoff (2026-10-02, Chat wave 22)

On `gradation/w22-chat` (PR into `gradation/app-pass`). Chat/History follow-ups after w21:
- Restoring a parked Chat stage after Ask←RP uses the Ask session id remembered on the way out, because `askComposer` still names the Roleplay session during the mode observer (Hub Continue / tab / Settings).
- A late gallery/camera encode after an Ask→RP or Code flip parks the JPEG for that Ask thread instead of putting the chip on Roleplay (or losing it under Code).
- History draft `rowPreview` clips around gapped word-order hits (like `searchLine`), so a long "hello … Photo" draft searched as "hello photo" still shows Photo for the bold span.

Phone: stage a photo on a saved Chat, Hub → Continue into Roleplay (no chip), back to Chat (chip returns). Start a gallery pick in Chat, flip to Roleplay before it lands (notice; chip back on Chat). Caption a long draft + photo, History search "hello photo" (row keeps Photo in view, bold span lands).

# Handoff (2026-10-02, Code wave 21)

On `gradation/w21-code` (PR into `gradation/app-pass`). Three Code-mode fixes, unit-tested (`CodeProtocolTest`):
- ACP permission `optionId` written as a whole-number double (`5.0` / `"5.0"`) still matches as `"5"`, so Allow answers with the digit string the agent expects. Tool detail lines coerce stringified whole-number doubles the same way (a `"5.0"` shell_id shows as `5`).
- Cursor Agent tool names `PatchEdit`, `ReadTodos`, `RunTerminalCommandV2`, `Gotodef`, `NotebookRead`, `Sleep`, `Wait`, `WakeParent`, `SendToUser`, `BrowserNavigate`, `OpenBrowser`, and `CreatePullRequest` map to edit / think / execute / search / read / fetch cards (icon and log clipping).
- Tool detail lines read Cursor's native rawInput keys `computer_path` / `box_path`, `sourcePath`, `connection`, `chars`, and `machineId` (a whole-number double shows as `5`).

Phone: on Cursor Agent, a PatchEdit or ReadTodos card should show the edit or think icon. A CopyToBox card with `computer_path` should show that path under the title. An approval whose optionId a proxy rewrote as `5.0` should still Allow as `"5"`.

# Handoff (2026-10-02, Chat wave 21)

On `gradation/w21-chat` (PR into `gradation/app-pass`). Chat/History follow-ups after w20:
- Ask↔RP mode flips that bypass the tab pager (Hub Continue / Start chat, Settings disabling Roleplay) park a live Chat stage and restore it on return, so Roleplay no longer inherits the chip (and a Roleplay-only stage is still dropped).
- History draft search matches query words in order with gaps, same idea as sent-message LIKE, so "hello photo" finds "hello there" + Photo and the bold span can cover that hit.

Phone: stage a photo in Chat, open History → Roleplay → Continue (no chip in RP); swipe back to Chat (chip returns). Caption "hello there" + photo, History search "hello photo" (row shows Draft: hello there Photo with the phrase bold).


# Handoff (2026-10-02, Code wave 20)

On `gradation/w20-code` (PR into `gradation/app-pass`). Three Code-mode fixes, unit-tested (`CodeProtocolTest`):
- Cursor ask/plan `toolCallId`, question/option ids, and todo ids written as a whole-number double (`5.0` / `"5.0"`) still match as `"5"`, so the approval links the tool card and merge replaces the todo row.
- Cursor Agent tool names `ListMachines`, `CopyToBox`, `CopyFromBox`, `UploadFile`, `DownloadFile`, `BackgroundComposerFollowup`, `CloudAgent`, `CreateAgent`, `SendToAgent`, `CheckSubagent`, `GetMcpServerStatus`, `SearchPlugins`, `RecordScreen`, `Mcp`, and `DraftExternalMessage` map to search / move / fetch / think / execute / edit cards (icon and log clipping).
- Tool detail lines read Cursor's native rawInput keys `directory_path`, `working_directory` / `cwd`, `task_description` / `description` / `prompt`, `pr_url`, and `shell_id` (a whole-number double shows as `5`).

Phone: on Cursor Agent, a ListMachines or CopyToBox card should show the search or move icon. A ListDir card with `directory_path` should show that path under the title. An ask_question whose toolCallId a proxy rewrote as `5.0` should still link the tool card.
# Handoff (2026-10-02, Stability wave 20)

On `gradation/w20-stability` (PR into `gradation/app-pass`). Persistence follow-ups after w19 orphan hold sidecars:
- Drain no longer promotes orphan `-wal`/`-shm` (no main) from `chat_db_hold` into an empty vault. Those leftovers are discarded, matching the vault-has-main case from wave 19.
- Vault orphan sidecars are cleared so a complete hold set can move in. Sidecar-only recovered/unreadable files at the databases root are parked under hold and discarded in the same pass (Auto Backup has no wildcards for those names).
- Code away-notification open tokens `commit` when issued and when consumed, so a kill cannot drop the nonce the shade still carries (or leave it for a replayed Intent).

Phone: park only `chat_database.recovered-<stamp>-wal` under `databases/chat_db_hold/` with an empty vault; after relaunch that sidecar should be gone and History should stay the live DB. Park a hold main while the vault has only a `-wal` for that stamp; History should open the drained vault copy. Tap a Code away notification after a kill mid-post (open token should still unlock).

# Handoff (2026-10-02, Chat wave 20)

On `gradation/w20-chat` (PR into `gradation/app-pass`). Chat/History follow-ups after w19:
- Staged Chat attachments park on the activity ViewModel, so a rotation restores photo/audio/files (not only the pending JPEG URI), and leaving Chat for Code parks and restores them the same way Ask↔Roleplay already did.
- Soft-park on pause / History open skips an empty live stage so a photo parked for Code is not wiped.
- History search for a multi-word query spanning caption + "Photo" (e.g. "hello photo") shows both on the row so the hit can stay bold.

Phone: stage a photo (or audio), rotate (chip returns). Stage a photo, open Code, come back (chip returns). Caption + photo, History search "hello photo" (row shows Draft: hello Photo with the phrase bold).

# Handoff (2026-10-02, Stability wave 19)

On `gradation/w19-stability` (PR into `gradation/app-pass`). Persistence follow-ups after w18 hold parking:
- Room opens a hold-parked recovered database only when the main file is there. An orphan `-wal`/`-shm` in `chat_db_hold` no longer hides the vault copy (Room would have created an empty main beside it). Drain removes those orphans when the vault already has the main; a hold set that still has its main still beats a stale vault copy.
- Code host pairing tokens commit before hosts JSON is scrubbed, and the prefs→Room session migration flag commits after Room import, so a kill cannot drop the only token or re-import sessions.

Phone: force a recovery that leaves only a `-wal` under `databases/chat_db_hold/` while the vault has `chat_database.recovered-<stamp>`; History should open the vault copy. Save a Code host with a pairing token, kill mid-save (token should still unlock after relaunch).

# Handoff (2026-10-02, Chat wave 19)

On `gradation/w19-chat` (PR into `gradation/app-pass`). Chat/History follow-ups after w18:
- A staged Chat photo whose URI survives a view rebuild reloads the JPEG bytes and preview chip, so Send still includes the picture.
- Opening History mirrors the live stage into the park map without clearing the chip.
- History search for "Photo" / "Audio" / files on a captioned draft shows (and bolds) that label on the row; idle rows keep the caption alone.

Phone: stage a photo, rotate (chip returns; Send includes it). Stage a caption + photo, search History for "Photo" (row shows Draft: … Photo with Photo bold).

# Handoff (2026-10-02, Code wave 19)

On `gradation/w19-code` (PR into `gradation/app-pass`). Three Code-mode fixes, unit-tested (`TurnEndFormatTest`, `CodeProtocolTest`):
- Turn usage token counts written as a whole-number double string (`"1200.0"`) still parse, so the finished-turn line keeps its in/out counts after a proxy stringifies numbers.
- Cursor Agent tool names `WriteShellStdin`, `TodoRead`, `SearchSymbols`, `RipgrepSearch`, `RipgrepRawSearch`, `FixLints`, `GoToDefinition`, `FetchPullRequest`, `ApplyAgentDiff`, `TaskV2`, `CreateDiagram`, `ComputerUse`, `KnowledgeBase`, `ReadProject`, `UpdateProject`, `SemanticSearchFull`, and `ReadSemsearchFiles` map to execute / think / search / edit / fetch / read cards (icon and log clipping). `FixLints` is edit (it writes), not search.
- Tool detail lines read Cursor's MCP rawInput keys `toolName` / `tool_name`, `server`, and `uri`.

Phone: on Cursor Agent, a WriteShellStdin or FixLints card should show the shell or edit icon. A CallMcpTool card should show the tool name under the title. After a proxy stringifies usage tokens as `"1200.0"`, the turn-end line should still show 1.2k in.

# Handoff (2026-10-02, Stability wave 18)

On `gradation/w18-stability` (PR into `gradation/app-pass`). Persistence follow-ups after w16 recovered-name skips:
- Set-aside (`unreadable`) copies that cannot enter the vault park under `chat_db_hold` as one set (main + wal/shm), matching recovered names. They are no longer `uniqueKept` into the vault (which could split a main file from its wal).
- Move temps (`.partial` / `.ready` / `.bak`) and `.kept-*` leftovers of `chat_database*` at the databases root are parked under that hold folder too; Auto Backup has no wildcards for those names.
- Clearing the one-time recovery notice commits the preference edit.

Phone: force a recovery while the vault already holds `chat_database.unreadable-<stamp>`, and confirm the new set-aside lands under `databases/chat_db_hold/` with its wal. Kill mid-move and relaunch (temps should leave the databases root).

# Handoff (2026-10-02, Chat wave 18)

On `gradation/w18-chat` (PR into `gradation/app-pass`). Chat/History follow-ups after w17:
- Ask↔Roleplay mode switches park and restore staged Chat attachments, so a Chat photo does not ride into Roleplay (and a Roleplay photo does not land on Chat).
- History search merges caption + attachment label, so "Photo" still finds a draft that also has typed text; the row preview still shows the caption alone.

Phone: stage a photo in Chat, swipe to Roleplay (no chip), swipe back (chip returns). Stage a caption + photo, search History for "Photo".

# Handoff (2026-10-02, Code wave 18)

On `gradation/w18-code` (PR into `gradation/app-pass`). Three Code-mode fixes, unit-tested (`ListSessionsJsonTest`, `GitBridgeJsonTest`, `CodeProtocolTest`):
- A bridge `listSessions` `lastSeq` (and `createdAt` / `updatedAt`) written as a whole-number double (`42.0`) still parses, so reconnect resume does not fall back to a full reload. Git `ahead` / `behind` counts written the same way still show on Changes.
- Cursor Agent tool names `GenerateImage`, `LS`, `ApplyPatch`, `AskQuestion`, `WriteTodos`, `GetDiagnostics`, and `MoveFile` map to edit / search / think / move cards (icon and log clipping). `ApplyPatch` was already aliased as `apply_patch`; the camelCase form now matches too.
- Tool detail lines read Cursor's native rawInput keys: `glob_pattern`, `target_directory`, and `search_term` (and the camelCase spellings).

Phone: on Cursor Agent, a GenerateImage or LS card should show the edit or search icon. A Glob card should show the pattern under the title. After a proxy rewrites listSessions lastSeq as a double, reopen a session and confirm it resumes instead of replaying from the start.


# Handoff (2026-10-02, Chat wave 17)

On `gradation/w17-chat` (PR into `gradation/app-pass`). History draft follow-ups after w16:
- Deleting a chat calls `forgetUnsentDraft` first, so a parked staged photo is deleted instead of waiting for the per-thread cap.
- Search merges host draft previews (Photo / Audio / files) into draft matching, so a chat with only a staged attachment is found by that label.
- Discard draft and the row preview prefer the open composer's live text (and a cleared dirty field) over a store that has not been parked yet.

Phone: stage a photo on chat A, open another chat, delete A from History (the JPEG should go). Stage a photo with no caption, search "Photo". Type in the open chat, open History, Discard draft.

# Handoff (2026-10-02, Code wave 17)

On `gradation/w17-code` (PR into `gradation/app-pass`). Three Code-mode fixes, unit-tested (`CodeProtocolTest`):
- A tool `toolCallId` written as a whole-number double (`5.0`) still matches a later update with `5`, so the card status and detail stay on one row.
- Cursor Agent tool names `AwaitShell`, `TodoWrite`, `Task`, `SwitchMode`, `MultiEdit`, `CallMcpTool`, `GetMcpTools`, `CallDynamicTool`, and `Subagent` map to think / edit / fetch / search cards (icon and log clipping).
- A shell command string with a scalar `args` whole-number double (`5.0`) shows as `5` on the tool detail line.

Phone: on Cursor Agent, an AwaitShell or CallMcpTool card should show the think or fetch icon. A tool whose id a proxy rewrote as a double should still update in place.


# Handoff (2026-10-02, Stability wave 16)

On `gradation/w16-stability` (PR into `gradation/app-pass`). Persistence fixes after w15 hold stamps:
- `firstFreeStamp` also skips stamps that already name a `chat_database.recovered-*` file (vault, databases root, or `chat_db_hold`), so a set-aside copy and its passphrase archive do not reuse that stamp.
- When recovery cannot move the corrupt file and `recoveredFileName` walks past the first stamp, pending quarantine and the passphrase archive follow the stamp in the recovered name.
- A character import that fails to write the wallpaper or portrait keeps those rows in the side log for the next launch (prefs stay applied).
- Archiving an unreadable JSON preference blob (including Code hosts) uses a committed edit.

Phone: force a recovery while a recovered name is already parked under `chat_db_hold`, and re-import a character with a wallpaper after interrupting storage.

# Handoff (2026-10-02, Code wave 16)

On `gradation/w16-code` (PR into `gradation/app-pass`). Three Code-mode fixes, unit-tested (`CodeProtocolTest`):
- A bridge `permissionResolved` whose `requestId` is a whole-number double (`9.0`) still matches the approval card stored as `"9"`, so Allow from another device (and the away alert cancel) clear the right card.
- Cursor Agent tool names `EditFile`, `SearchReplace`, `ReadFileV2`, `ListDirV2`, `GlobFileSearch`, `ReadLints`, `Await`, `FetchMcpResource`, and `Reapply` map to edit / search / think / fetch cards (icon and log clipping).
- A shell argv piece written as a whole-number double (`5.0`) shows as `5` on the tool detail line.

Phone: on Cursor Agent, an EditFile or SearchReplace card should show the edit icon. A permission resolved from another client after a proxy rewrites ids as doubles should clear the card and the away notification.

# Handoff (2026-10-02, Stability wave 15)

On `gradation/w15-stability` (PR into `gradation/app-pass`). Recovery naming respects `chat_db_hold`:
- `recoveredFileName` and `firstFreeStamp` skip names already parked under the hold folder (and the databases root), so a fresh recovered file is not opened as the parked history.
- Passphrase archive under a stamp that already holds one is refused, and recovery walks to a free stamp first.
- Interrupted recovery treats a hold-parked `unreadable-<stamp>` as present for note quarantine.

Phone: after a failed open that left files in `databases/chat_db_hold/`, force another recovery (or install over a broken DB) and confirm History stays the fresh empty one, not the parked copy.

# Handoff (2026-10-02, Code wave 15)

On `gradation/w15-code` (PR into `gradation/app-pass`). Three Code-mode fixes, unit-tested (`CodeProtocolTest`):
- A JSON-RPC id written as a whole-number double (`9.0`) still completes the pending call and an Allow still answers with a number id (Cursor questions too). Handshake treats protocolVersion `2.0` as unsupported, same as `2`.
- Cursor Agent tool names `WriteFile`, `DeleteFile`, and `RunTerminalCmd` map to edit / delete / execute cards (icon and log clipping).
- Location lines sent as whole-number doubles still show on the tool detail line.

Phone: on Cursor Agent, a WriteFile or DeleteFile card should show the right icon. A permission Allow after a proxy that rewrites ids as doubles should unblock the agent.

# Handoff (2026-10-02, Code wave 14)
On `gradation/w14-code` (PR into `gradation/app-pass`). Three Code-mode fixes, unit-tested (`CodeComposerDraftsTest`, `CodeProtocolTest`):
- Code home parks the unsent composer line and pictures per machine when the view is torn down or the host changes; sending clears that draft.
- Cursor Agent tool names `WebSearch`, `ListDir`, and `EditNotebook` map to fetch / search / edit cards (icon and log clipping).
- Bridge `_meta.seq` values written as whole-number doubles (e.g. `2.0`) still advance the resume cursor.

Phone: type on Code home, open Settings or rotate, come back (the line is there). Switch machines and switch back. On Cursor Agent, a WebSearch or ListDir card should show the right icon.

# Handoff (2026-10-01, Cursor's app pass merged)

`gradation/app-pass` (about 50 Cursor PRs, 67 commits) is merged into `liquid-glass-redesign`. The per-branch notes
are in that branch's history (`git show origin/gradation/app-pass:docs/handoff.md`); CHANGELOG.md Unreleased lists
the changes. In short: chat saves keep their snapshot through a leave and never overwrite a newer one; forks, swipe
versions, pins, facts and unsent lines travel with saves, exports and imports; database recovery keeps the old file
and its key in a no-backup vault; RP photos live as app files with the JPEG in the message as a fallback; History
groups by day and shows drafts; Code mode reconnects without doubling, keeps approvals, and handles Cursor Agent.

Fixed at merge (review): leaving a chat mid-reply saved the "thinking..." bubble; Regenerate or Edit in Chat
deleted the photo the other branch still named; leaving an RP greeting nobody answered added a History row.
`ScreenshotHarness.settle()` now steps in 50 ms, since a mode switch lands the leaving chat's save first.

Phone, most important first:
- Install over a build that has chats: they still open, with pins and facts.
- Leave a chat while a reply is pending (no stuck thinking bubble). Open an RP character and leave without writing (no new History row).
- Chat: send a photo, Edit or Regenerate it, switch back to the old branch (the picture still opens).
- Export chats, delete a pinned one with facts and another branch, import (all of it is back).
- RP photos: send one, leave and come back, swipe versions, Rewrite, Edit. Rewrite with Undo.
- Unsent lines: type in one chat, open another, come back; kill the app and relaunch.
- Tab switches feel instant (each one now waits for the leaving chat's save).
- Code: drop the link mid-reply and during an approval; on Cursor Agent answer a question, then approve a command.

# Handoff (2026-10-01)

## Code reconnect reliability (2026-10-01)
On `cursor/code-reconnect-reliability-05bf` (PR into `gradation/app-pass`). Four fixes, unit-tested (`CodeProtocolTest`, `CodeBridgeBackendTest`, `WebSocketTransportGenerationTest`):
- Already-applied bridge seqs are dropped, including the boundary seq of a gap refill, so a replay cannot append a text chunk twice.
- Outbox flush is single-flight, so resume and the connected-queue retry cannot deliver one queued prompt twice.
- Optimistic user bubbles match the bridge echo on trimmed text.
- WebSocket open/send/close share one lock: a second connect during open does not leak a socket, and send after retire returns false.

Phone still has the polish-wave checklist below. Not on a device: drop Wi-Fi mid prompt, and a prompt with leading spaces, to see one bubble and one turn.

## Stability pass (2026-10-01, database recovery)
On `cursor/db-recovery-hardening-700c`, targeting `gradation/app-pass`.
- Recovery used to delete the only wrapped SQLCipher passphrase after moving `chat_database` aside, so the backup could not be opened. The wrapped blob is now copied under `chat_db_passphrase_unreadable_<stamp>` before the active one is replaced. A pending-recovery stamp covers a process death between the move and the fresh database. A taken stamp uses the next free one, and a failed move puts the files back.
- Plaintext-to-SQLCipher keeps `chat_database.pre_sqlcipher` until the encrypted file has opened. If that open fails, the plaintext copy is restored and tried once before a fresh database is minted.
- A corrupt Code prefs session list used to decode as "nothing" and was then deleted. It now stays for the next launch, and Room rows still load.
- Chat export writes one session at a time to a cache file, then copies it. The destination is not opened until the cache file is complete.

## Polish wave 2 (2026-10-01)
Clears every wave-1 leftover. Suite (`-Pfull`) and `assembleDev` pass. Also: Code composer buttons 40dp like Chat; Code strings use plurals and "Thinking view"; lint is clean (unused resources, dead commented-out menu code and the `ktor-client-android` engine removed); the History and RP home lists open the DB on IO; Chat memory's value in Advanced matches the other row values. Schemas 1-3 are hand-reconstructed (history is squashed), so the migration test proves the SQL against them, not against byte-exact old installs. Phone: the wave-1 checklist below, plus a self-signed LAN server (first request pins; toggle Trust off/on after a cert change) and each Settings section.
- Chat shell: `item_message_ai.xml` is flat (root + `messageContainer`, no `aiGroup`). Grid pass: AI row padding 8/12/8/8, user row end 8, both bubbles 16/12, composer margin 16, composer buttons and model pill 40dp (hit area still 44 via `TouchTargets`), History divider inset 56 with the drawer icons at 24dp so it still meets the text, `GlassNotice` 16/12. Print export CSS is neutral grays; its user/reply selectors key on the inline padding, not the old blue/green hues.
- One app-level TTS: `TtsHolder` (lazy on first `whenReady`, `hold`/`release` by screen, 60 s idle shutdown, shutdown on UI-hidden trim unless speaking). `ChatFragment` and `RpVoiceFragment` never keep the engine, they ask again each use. Utterance ids tell screens apart: chat reads/saves use `tts_utterance` / `TTS_SAVE_*`, the voice page uses `rp_voice_preview`. Phone: first read-aloud after a cold start (small delay before the stop icon), voice page after backgrounding, WAV save.
- DESIGN.md truths: History wordmark is Iceland (unused Michroma font removed), user bubbles are a flat gradient, help.md says "swipe right".
- Settings (audit-glass 17): `fragment_settings_detail.xml` is now just toolbar + scroll + `settingsSectionContainer`; each section is `fragment_settings_section_{appearance,voice,haptics,models,advanced,data}.xml` (same ids), and `SettingsDetailFragment.inflateSection` inflates and binds only the open one (`bindHaptics/Models/Advanced/Data` split out of the old `bindAllControls`). Anything looking up a settings view id by `findViewById` must expect null when that section is closed (`ChatMemoryDialogFragment` already does).
- Background photo: `onPhotoPicked` is now set when the Appearance view binds (not on tap) and cleared in `onDestroyView`, so a result that lands after a rotation refreshes the new picker.
- Data (audit-core 7, 14, 17): session ids come from the database only (`ChatDao.insertSessionAndMessages` returns the generated id, `overwriteIfExists` is an `@Update` that writes nothing when the chat was deleted; no MAX+1, no REPLACE; `ChatSessionSaver.save` returns the id or null). `AppDatabase.build` retries once, then moves `chat_database` (+ -wal/-shm/-journal) to `chat_database.unreadable-<ms>` (never deleted), mints a new passphrase (`resetChatDbPassphrase`) and sets a flag that `ChatViewModel` turns into a one-time notice. `ChatViewModel`/`SavedChatsViewModel` open the DB lazily (warm-up on IO), no longer in init on Main. `exportSchema = true`, schemas in `app/schemas` (1-3 written by hand from the migration SQL, 4 comes from the build; commit it), `DatabaseMigrationTest` walks every migration.
- LAN "trust self-signed" is trust on first use: `LanCertPins` / `LanCertPinInterceptor` pin the leaf SHA-256 per host:port in prefs, mismatch fails with `error_lan_cert_changed`; turning the setting off clears the pins. The handshake still accepts any cert (needed for first use), the network interceptor refuses before any request byte is sent. A CA-signed LAN cert that renews will also trip it (toggle off/on).
- HTML/EPUB export CSS in `ChatViewModel` is neutral grays (copy button too, emoji labels dropped); "No response received." and "Your answer is ready." live in `strings_chat_core.xml`.

## Polish wave 1 (2026-10-01)
Six parallel audits (chat shell, chat core, Roleplay, Code, glass/voice/settings, resources/docs/build), then six workers on disjoint file sets, then one build. Everything is in CHANGELOG.md Unreleased. 574 tests pass (`-Pfull`), `assembleDev` builds. Nothing verified on a phone yet. Phone checklist, most important first:
- Streaming: a slow model past 5 minutes; kill Wi-Fi mid-reply (partial stays, notice says it may be incomplete); Stop mid tool run then send again (no 400).
- Voice: dictate, tap send before the transcript lands (send waits, notice), the X while transcribing, phone recognizer idle stop at 90 s, "Open settings" when the mic permission is denied for good.
- GlassNotice everywhere a toast used to be: missing key on send, save with an empty character name, export/import, copy on a reply (check + haptic), "Open folder" action after a tool writes a file.
- Biometric lock: one bad read keeps the prompt; 30 s away re-locks; camera/gallery trip does not.
- Roleplay: History page delete (including the open chat), Memory back with edits, wallpaper remove confirm, rotation mid-edit in the character editor, library row ⋮ menu, empty home CTA.
- Code: approval buttons lock on tap, expire on Stop; session screen states and banner tap; dot on the Code tab; swipe-away undo; cold start from an away notification.
- Settings rows show values; dialogs show field errors; theme switch keeps the liquid mark alive; animations-off in developer settings snaps everything.
- Background photo from a portrait camera shot is upright; power saver flips glass to solid and stops the loops.
Leftovers from this list were all done in wave 2.

## Handoff (2026-09-29)

## Unreleased (after 3.0.0)
`main` is merged into `liquid-glass-redesign` (only the cert fingerprint commit differed). Work since then, all unverified on a phone:
- RP panel pages pass (2026-09-30, evening). The sheet dropped by its own height after back from a page: `CoordinatorLayout` params had `gravity = BOTTOM`, and `BottomSheetBehavior` adds its offset from the top on every layout, so any in-place content swap doubled it; the gravity is gone and `rpPanelPagesDark` walks every tile page and back asserting the sheet's top. Pages share `RpPageKit` (tile drawing as a 96dp hero with a caption, section labels, `rp_bg_card` cards of 56dp rows with hairlines, footnotes, pinned Save that rides the keyboard). Layout picks from three drawings with a ring on the chosen one; Wallpaper shows a phone-shaped preview; Voice lists Default at once and adds engine voices when TTS is up (rebind swaps only its own segmented listener; `GlassSegmentedGroup` keeps its thumb listener); Style (`RpSettingsFragment`, titled by `newInstance(title)`) is Writing / Story / Mode cards with footnotes. Avatar photos: `AvatarPicker.launch(anchor)` shows a `PickerPopover` under the portrait with Gallery (`ACTION_PICK` on MediaStore images, falls back to the picker) and Photo picker; `sheet_avatar_source` and the Files option are gone. Phone: Gallery opening the phone's own app, the card position under the portrait, keyboard on Memory with the pinned Save.
- UI weak-spot pass (2026-09-30). Streaming: `StreamRevealPacing` (new) paces the reveal off an EMA of the arrival rate with a small lag behind the buffer that fades out after 150-450 ms without new text, so slow streams no longer drain, stop and lurch; the animator idles 250 ms instead of stopping; word snap only on big backlogs or finishing; fade 220 ms; no one-char first paint. Icons: ~50 legacy Material (viewport 960) icons redrawn as 24dp round strokes in place (file names kept), `ic_licenses` new, 30 unused drawables deleted (brand marks kept, `ModelBrandsTest` needs 30+). Settings rows share one text edge and divider inset; LAN row uses `ic_local_network`; Controls grid packs visible tiles (`reflowControlTiles`) and hides New chat instead of ghosting it. RP: `AvatarPicker` samples big photos properly and recycles; Memory/Voice drafts survive rotation; RP pages pad for the IME; wallpaper and panel avatar decode off main; RP bubble binds skip unchanged layout params; glass press glow caches its shader; empty-home copy points at the characters button. Code home badge is 13sp translucent under a two-line title. Full suite and `assembleDev` pass. Phone: slow-stream feel (try a slow model or `DemoModel.pace` high), IME on RP pages, crop on a 12MP photo, icon weight next to the composer's filled-ring icons.
- Persona tile: the portrait was drawn through the base tint's alpha (washed out); paint is now opaque before the shader. The tile shows no name any more. Panel header avatar uses centerCrop under the frame's oval outline (a circular `RoundedBitmapDrawable` squashed non-square photos).
- Persona off switch: pref `rp_persona_enabled` (`isRpPersonaEnabled`, `activeRpPersona`, `activeRpPersonaName`); prompts, the `{{user}}` macro, auto-memory and the tile use the active getters, the editor keeps the raw ones. Switch card shows once a persona or preset exists; Save turns it back on. Hub row says Off.
- `AvatarPicker` (Photos / Gallery or other app / Files, then `AvatarCropView` pan-zoom in a full-screen dialog) replaces the bare `OpenDocument` in the persona and character editors. It hands back a 512px square JPEG in the cache; the editors' own save paths store it. Tests: `AvatarAndPersonaTest`.
- End-of-stream pause (third attempt): `ChatAdapter.prepareFinal` parses the finished reply and precomputes its text layout (`PrecomputedTextCompat`, skipped for tables and images) off the main thread; `swapFinal` waits for it instead of parsing on main; `updateLastMessage` no longer drops the cache when the message is unchanged (that late no-op update was the likely reason the earlier fixes did not hold); Markwon's `textSetter` uses the precomputed layout when the view's params match. That was not the cause: the pause was the deliberate 340 ms fade wait in `onCaughtUp` before the swap. The swap now posts immediately (last words may snap out of their fade). Confirmed fixed on the phone.

## 3.0.0
Version 3.0.0 (versionCode 30000, derived from `appVersionMajor/Minor/Patch`) is on `main` (ff of `liquid-glass-redesign`). No release APK
yet: the signing key does not exist. The owner runs `scripts/make-release-key.sh` once on their machine, commits the
fingerprint to `docs/release-cert.sha256`, then `scripts/release.sh` (see `docs/RELEASING.md`), tags `v3.0.0`, attaches
the APK. `.github/workflows/release.yml` can do the same from repo secrets (untested). README, AGENTS and the changelog
were reworked; the 12 showcase shots in `screenshots/` (8 in fastlane) were re-rendered from the tests, History seeded
with markdown chat names. Phone still has to check: the swipe race fix (fast Chat/RP/Code swipes), the list-to-chat slide,
the reply menu placement, scroll-to-bottom, markdown chat names, then install the release over a previous one and open an old chat.


## One avatar placeholder
`ic_avatar_placeholder` (flat head and shoulders, `xai_mute`, clipped to a circle, drawn full-size over the gray frame) is the only no-photo, no-name portrait: persona screen, character editor, empty character library. `RpTileArt.persona` draws the same proportions by hand. Named people keep the initial. The test seed avatar is flat too (no gradient).

## Persona screen
`RpPersonaFragment` is now: a portrait you can tap to pick a photo (camera badge, Change/Remove like the character editor; files in `rp_persona_avatars/`, never reused names, pruned on save/delete; pref `rp_persona_photo`, `RpPersonaPreset.photo`; the panel's Persona tile draws it; the camera badge `rp_bg_badge` is a flat `panel_tile_on` dot, no glass, in both persona and character editors, user call), a Name field plus About field, then Your personas as tappable rows (initial, name, first line, check on the one matching the fields, trash to delete) and a New persona row. Tap loads a persona into the fields; Save stores it and upserts it into the list by name (cap 12). The spinner and "Save current as preset" dialog are gone. Name is its own pref (`rp_persona_name`, `saveRpPersonaName`); `getRpPersonaName` falls back to the old preset match when it was never set. The list section hides when nothing is saved. `rpPersonaSwitchesWithOneTap` and `rp_persona_dark` cover it; full suite passes.

## Panel History tile
Panel order: History, Memory, Lore / Edit, Voice, Persona / Wallpaper, Layout, Style. Lore is an open book (written pages on a cover, ribbon), Style is "Aa". History (`RpTileArt.Kind.HISTORY`, date pills) opens `RpChatHistoryFragment`: full-screen page with a portrait header, chats grouped Today/Yesterday/This week/Earlier as cards (time, last line, message count, "Open now" on the current one) and a New chat button; the pick goes back to `ChatFragment.onRpHistoryPicked` as a fragment result. No delete on that page yet. Tile art is one flat palette (`BASE/LOW/MID/HI`, no gradients); Layout tile has no state line (TalkBack still reads it via `Tile.spoken`). `rpPanelHistoryDark` covers it; full suite passes. Phone: open History from the panel and switch chats.

## RP list top-left is Settings
On the characters list the top-left button is a gear (`ic_gear`) that opens app Settings; it no longer opens the History panel, which is Chat's. Inside an RP chat it stays the back chevron; Chat and Code keep the menu icon and History. RP's own settings stay in the character library on the right. `rpHomeDark` covers it; checked on the A25.

## Panel tiles, second pass
Tiles are square (card measures height = width) and `RpTileArt` fills the whole card, clipped by it, so drawings sit low and run off an edge like the owner's reference: stepped memory blocks, silver play disc, layout mini chat, wallpaper frame off the bottom, ringed persona, app-icon squircle for Style, book, pencil off the corner, bubble with plus. The sheet has no edge line (`panel_edge` removed), draws under the nav bar and pads itself, and turns off the nav contrast scrim. Full suite passes; dev build checked on the A25.

## RP home and panel pass
Roleplay opens on a characters list (every character; mid-story ones first, the rest by name; tap starts a chat). Roleplay has no History: the panel's History tile is gone, the left-edge drawer swipe is off in RP, and the top-left button is a back chevron inside a chat (the app menu on the list). Top-right on the list opens the character library. Swiping Chat to Roleplay now slides the list with the pages (`rpHome` is in `allModePages`) and lands where Roleplay was left (`rpResumeAtHome`). Panel is a uniform 3x3 (Memory, Voice, Layout, Wallpaper, Persona, Style, Lore, Edit, New chat). Full suite passes; dev build installed on the A25 and the resume swipe and panel checked there. Phone still has to check: the mid-swipe slide of the list, and the panel tile feel.

Follow-up: panel tiles are drawn pictures (`RpTileArt`: memory pills, play disc, mini chat in the chosen layout, wallpaper or empty frame with a plus, persona initial, sliders, book, card with pencil, bubble with plus) under the name and a one-line state. The header's Switch button is gone. In RP the composer's sliders button is the character menu (`ic_rp_scene`, same as tapping the speaker line); the old controls card is a "Controls" row in the RP + menu.

# Handoff (2026-09-28)

## RP redesign pass (latest)
Dim crimson `delete_action`; `MessageMenu` replaces the reply ⋮ popover; RP home chats list (`RpChatsHome`, `RpChatSummaries`); character panel opens from the reply speaker line (composer pill hidden in RP); solid character panel; Continue is a hidden turn that extends the last reply in place (`continuationBase`, `RpContinuation`, adapter `continuingFrom` seeds the reveal). Full suite passed (485 tests). Phone still has to check: menu/chip feel, home swipe from Ask, Continue seam and fade, panel over a photo background.

Read `CLAUDE.md`, then `AGENTS.md` and `docs/backlog.md`. Branch `liquid-glass-redesign`.

## Codebase pass (2026-09-28, stopped)
Same-job copies in the chat path were folded. The three chat files stayed one file each. Fast unit tests passed (`testDebugUnitTest -Pfast --offline`). Not a release build, and the screenshot suite was not re-run.

- One SSE reader and one non-stream delivery. Sampling is `ChatRequest.withSampling()`. Citations stay on a tool-call reply. A fatal finish reason returns before image download.
- OpenAI-compatible LAN model lists share `fetchOpenAiModelList`. Ollama stays separate.
- Send and resend share the endpoint choice and the network turn. Read and open share one workspace lookup. Text and image downloads share one MediaStore write each.
- An old OpenRouter cache with no reasoning flags refetches once (`open_router_reasoning_migrated`). A later list whose first model is not reasoning stays.
- Dead code removed along the way: old EPUB writer, unused Brave search, disabled speech-to-text leftovers, unused prefs, and the one-line stream wrappers.

`ChatFragment`, `ChatViewModel`, and `ChatToolRuntime` are still long. Shrinking them further means a split. `ChatStreamHost` and `ChatToolHost` still forward one-line calls; that is the boundary of the existing split.

## Previous handoff (2026-09-27)

The full suite (426 tests) passed at b9789d9. Dev APK `GradatiON-dev-b9789d9.apk` was sent to the user.

## Done this session
- K1: Code transcript tool calls are single quiet lines. The session menu toggles Normal/Thinking
  view (`CodeStore.showThinking`, `CodeTranscriptAdapter.verbose`).
- R1: `RpPromptEngine` got per-character Memory (`SharedPreferencesHelper.getRpMemory`),
  `{{char}}`/`{{user}}` macros (the persona preset name is the user's name), scene-craft rules,
  and example dialogs on their own lines.
- R3: an empty RP composer turns send into Continue (`ChatViewModel.continueRpStory`, a "…" user
  beat plus `CONTINUE_DIRECTION`). The character pill opens `RpCharacterPanel`: titled glass cards
  (Memory, History, Persona, Style, Lore, Edit), with New chat and Switch in the header.
- P1: new `screenshots/` and fastlane shots, plus rewrites of README, `DESIGN.md` (the Grok token
  dump and `SHELL.md` are gone, still in history) and the store descriptions. `AGENTS.md` is new.

## Next
- Lorebooks match the scene (`RpLore`). Text above the first `[keys: …]` line is always included. A block under that line is included only when the recent chat, the character's name, or the scenario mentions a key. A character can pin its own book from the Lore card.
- Drop order: history goes first. Once it no longer fits, a long card keeps its first 4,000 characters (`RpApiMemory.definitionCap`).
- Memory is the note you write and is not rewritten. Facts are a separate per-chat note (you, the character, and other people). The RP setting turns Facts off. A new chat asks to carry them or start fresh. Example lines use a stand-in name, not yours. `RpAutoMemoryTest` and `RpPromptEngineTest` passed.
- RP auto memory is done: after a reply, once the chat nears the API window (or 60 messages when
  memory is "All"), a quiet non-streamed call (`LlmService.completeOnce`) rewrites the character's
  Memory, at most every 6 messages. Switch in RP settings. The demo model answers it too. Voice/Layout/Wallpaper cards are done (RpVoiceDialog, ChatAdapter.rpLayout,
  AmbientBackgroundView.photoSlot with BackgroundPhoto slots "char_<id>").
- The old scroll-progress bar (the full-width line under the tabs) is off by default and fades
  when idle if someone turns it on.
- The phone still needs to check: Continue feel, the panel's blur and lens, the Thinking toggle, and
  all motion.

## UI clip pass (2026-09-27, fresh screenshots, not the old checklist)
Clipped labels, verified with the screenshot tests that cover each screen:
- RP settings Facts switch wrapped so "Your Memory note is left alone." is fully visible.
- Add-machine dialog wraps agent pills (Grok Build, Cursor Agent, Pi were off the card) and shows the
  full bridge example `wss://studio.tailnet.ts.net:7878/v1` under the address field.
- Code settings Demo machine row shows "nothing leaves the phone."
- Lorebook editor shows "Always-on lore, then [keys: word, phrase] blocks" under the field. It was a
  one-line hint cut at "phra…", then a helper line the text box covered.
`assembleDev` succeeded. The minified APK is `app/build/outputs/apk/dev/app-dev.apk`.
Phone still has to check motion, glass blur, and the Thinking toggle.

## UI bug pass (2026-09-27)
UI audit + fixes (112a867..27bf705): glass Snackbars (theme inverse colors), PDF Snackbar crash on a
recycled row, Markdown viewer follows the palette, shimmer pauses offscreen, shared Coil loader,
masked API-key fields, one-shot system message pick, visible checkbox checks, failures raise
GlassNotice (toasts stay silent), Code session edge to edge, 44dp hit areas (`TouchTargets`), glass
settings cards, strings moved to resources. 443 tests pass. `rpAutoMemoryUpdatesAfterReply` was stale
after the Facts split; it now checks Facts (and that Memory is untouched), and DemoModel answers the
"fact notes" prompt. Skipped: HTML/PDF export colors, GlassNotice under dialog dims, CodeHostDialog 36dp pill.
Phone: touch edges of send/mic, Code session insets + IME, Snackbar look, settings cards on a photo bg.

## User bug list (2026-09-27, evening)
All 19 items from the owner's list, 452 tests pass, `assembleDev` built.
- History panel: the chat is pinned to the panel's edge (`historyChatOffset`), not a 0.25 parallax.
  The parallax showed both screens' look-alike chrome at once ("the UI doubles").
- Mode pager: a cancelled swipe left the chat pages a page off screen (page animators ended after
  the reset); `restModePages` cancels them first. Swipes can't start on the bottom bar band
  (`bottomBarTop`, includes Code's composer).
- Tab underline: `BotModelPickerFragment` used `replace` for LAN/OpenRouter, which rebuilt the chat
  view; the new view kept the old underline target and sat at x=0. Both fixed.
- Roleplay/Code switches apply at once: `ChatFragment.watchModeSwitches` (prefs listeners,
  `CodeStore.addEnabledListener`). Settings opened from history never hid the chat.
- Controls panel is a bottom sheet now (moved into `rootLayout` above the composer, dim over all).
- `PickerPopover`: second tap on its control folds it (`isOpenOn`), it follows its edge while the
  keyboard drops (`refit`), and modal cards frost what's behind (`BackdropBlur`). The four list
  PopupWindows blur behind their window (`BackdropBlur.behind`).
- Messages: new `ic_msg_*` stroke set, bare user action row, thinking header without a chip
  (thoughts hang off a hairline), reply actions hidden while streaming (`ChatAdapter.replyInFlight`),
  code card header row with a Copy chip that has an icon and turns into "Copied".
- Code: gear on Code home removed (top-left opens the same screen). Approval modes have icons
  (`CodeComposer.permissionIcon`) in the popover, the pill and Code settings.
- Voice: the mic check no longer swells; `VoiceWaveView` runs ~30fps while listening and glides.
- Motion pass: gesture settles carry the release velocity (`Motion.Fling`), shared per group of
  layers. Mode pager, history open/close/cancel (chat no longer drifts off the panel edge on
  cancel), sheet drag-back and Code session swipe-back use it. Swipe commit is projection
  based with a flick-back cancel. Toggle pill spring-follows, groove fill follows the pill.
  Phone: feel of all of these; the pager's first-frame snapshot may still hitch on old phones.
- Switches: every screen is a SwitchCompat (MaterialSwitch sized differently). Redesigned after
  iOS 26 (the owner picked "clear lens" from five mockups): 60x28 groove, 36x24 clear pill
  that redraws the groove magnified inside itself (the track clips the pill out so fills don't
  stack). It swells and magnifies harder while held, dragged or sliding (the thumb watches its
  own bounds move, since SwitchCompat cancels pressed on drag). Geometry lives in the track's
  side padding (`GlassSwitchTrackDrawable.getPadding`).
- Photo background blur is about 2.4x softer.
Phone: every motion above, the Controls sheet under the nav bar, blur cost on an older phone.
