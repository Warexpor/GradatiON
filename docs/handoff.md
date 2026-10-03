# Handoff (2026-10-03, Settings wave 29)

On `gradation/w29-settings` (PR into `gradation/app-pass`). Settings follow-ups after #121. Not a redo of pref-tap commits, the Power tools switch, exact text-size rings, Voice chips while off, or reasoning effort vs the token budget. Not a redo of #123 (persona Save commits).
- Settings home shows the stored Voice engine. `VoiceInput.resolve` still hides or redirects the mic when the phone has no recognizer; the row no longer calls that Off or Cloud.
- Inference temperature / top-p / min-p / repetition / presence accept a comma decimal and refuse non-finite or out-of-range numbers (presence may be negative, down to -2). Top K stays a positive whole number (`40,0` counts, 0 does not). Those writes commit.
- Timeout reads and saves are clamped to 1–45 minutes. Max tokens that are blank, 0, or not a number read as 12000. The chat-memory dialog checks a row only on an exact preset.

Phone: on a phone with no speech recognizer, set Voice to Phone and open Settings (the row should say Phone, not Off). Set temperature to 0,8 (comma) and send (the request should carry 0.8). Type 9 as temperature (it should not replace 0.8). Set timeout, force-stop, relaunch (the minutes should stick, and a leftover 0 should behave as 1).

# Handoff (2026-10-03, Notifications/Away wave 29)

On `gradation/w29-notif` (PR into `gradation/app-pass`). Notif/away follow-ups after turn-done shade hold and the speaking flag (#120). Not a redo of that pass:
- `clearTurnDoneDedup` parks the shade id under a `hold:` key in the notif-id prefs in the same commit that drops the live row. A kill between the old two commits left the row, and process-death seeding suppressed the next finished turn. A legacy `code_away_shade_hold` row is not a dedup seed, so that next turn still alerts and reuses the id. Backup and device transfer exclude that file.
- Swiping away the last alert for a session clears the one-shot open token. A second approval for that session keeps it (longest session id wins when ids contain ':').
- In-chat Speak stops shade TTS and flips the answer shade back to Speak (or drops it in the foreground). Leaving Stop up with the speaking flag already clear made the next shade tap start speech again.

Phone: Notify when away on, finish a turn, send another prompt, kill the app before the clear finishes if you can leave both the dedup row and the old hold file (the next finished turn should still show, one entry). Swipe the only approval away, then replay that notification's open (session should not open). Leave a second approval up and swipe the first (the other still opens). Background Chat, Speak from the shade, open the chat and tap Speak on the message (shade should say Speak, not start a second reading when you tap Stop).

# Handoff (2026-10-03, Chat wave 29)

On `gradation/w29-chat` (PR into `gradation/app-pass`). Chat/History follow-up after w27 promote (#110) and w28 caption promote (#116). Not a redo of those: the parked caption and caption+Photo search stay as they are.
- `ComposerStaged.evicted` no longer treats a promote/rekey as a drop. First save under Code moves the parked JPEG onto the new id, then the fragment deletes whatever eviction lists — key-only comparison listed that same file, so the chip was gone while History still showed Photo.
- A same-key replace (new photo, or audio parked over a photo) still evicts the previous uri. Re-parking the same uri does not. If another chat still holds that uri, the file stays.

Phone: stage a photo on a new Chat, open Code, let the first save mint an id, leave Code (chip returns; the scene file is still there). History search Photo still finds that row. Stage a second photo on a saved chat (or park audio over the photo) and the previous JPEG is gone. A photo that simply falls off the oldest parked slots is still deleted.

# Handoff (2026-10-03, Import wave 29)

On `gradation/w29-import` (PR into `gradation/app-pass`). Import/export follow-ups after #122. Not a redo of note matching after a later save, wallpaper prepare, or torn portrait export:
- A character side log that names one id twice (the same export key in one file) keeps the last copy. The earlier copy used to stay in the log and overwrite Memory and pictures on the next launch.
- A lorebook backup with nothing marked active clears that flag on the books it updates. An empty library still activates the first book. Names that differ only by spaces merge. A pin matches a trimmed name, and a name longer than 200 characters, instead of waiting forever.
- Cold start runs the same pin link a lore import does, so a kill after the lore rows commit still attaches the book.

Phone: import a character file that contains the same character twice with different Memory (the second Memory should stick after relaunch). Import a lore backup where no book is active onto a phone that had one active (it should turn off). Import a character whose lore book name has a trailing space, or is very long, then import or reopen after that book exists (the pin should attach).

# Handoff (2026-10-03, RP wave 29)

On `gradation/w29-rp` (PR into `gradation/app-pass`). Roleplay follow-ups after #117 (not a redo of Continue swipe alts / portrait side-file delete / regen lore focus):
- Torn `photoUri` no longer wins over monogram: `RpAvatars.photoModel`, speaker header, character edit and picker already used `hasAvatar` for the file path; URI short-circuit is gone.
- Continue pins the bubble it extends (and the preceding turn) in lore focus, matching rewrite/regen for keys at the start of a long reply.
- Persona Save commits name / about / photo (enabled already committed).

Phone: kill mid-portrait replace so the JPEG is torn with no `.bak` (library / panel / speaker show monogram, not a broken image). Continue after a long reply whose early keys would fall out of the recent scan (lore for those keys should still fire). Save a persona, force-stop immediately, relaunch (name/about/photo still there).

# Handoff (2026-10-03, Code wave 29)

On `gradation/w29-code` (PR into `gradation/app-pass`). Code-mode follow-ups after w28 double-coercion and browser cards (#115). Not a redo of that pass:
- `HarnessKind.fromId` folds case and underscores (`Claude_Code`, `CURSOR-AGENT`, `grok_build`) onto the canonical harness. `agent` stays custom so a permission-mode word is not Cursor. Outbound frames still send the canonical id.
- `bridge/listSessions` `permissionMode` / `mode` uses the same aliases as a live `current_mode_update` (`acceptEdits`, `bypassPermissions`, `dontAsk`, `agent`, `default`, `full_auto`). An unknown or omitted mode stays Ask, so merge still treats a missing mode as omitted.
- Cursor `BrowserMouseClickXy` and `BrowserCdp` map to the execute card. A click with `x`/`y` (including `10.0`) shows `10, 20`. Detail lines also read `tabId` / `tab_id`, `action`, `method`, and a `reviewers` / `team_reviewers` array.

Phone: a session listed as `acceptEdits` or `agent` should not show Ask. A harness id `claude_code` should show Claude. On Cursor Agent, BrowserMouseClickXy should show the execute icon and `10, 20`; RequestPullRequestReviewers should list the reviewers under the title.

# Handoff (2026-10-02, Hub/Pair/Glass wave 28)

On `gradation/w28-hub` (PR into `gradation/app-pass`). Hub/Pair/Glass follow-ups after #106/#112, unit-tested (`CodePairPendingTest`, `GlassDrawableOutlineTest`):
- Hub Continue / Start chat pin the Roleplay tab for the Code deactivate callback (not a sticky flag) so an async setChatMode(RP) cannot select Chat or spring the underline mid-flip.
- `CodePairPending` offer / offerError / clear / consume / consumeError share one lock so interleaved QR and deep-link results cannot leave both pending and error set.
- Bottom-sheet `GlassDrawable` topOnly outlines are empty below API 30 (skip fake rounded-bottom elevation); `clearDuplicateSheetGlass` also drops glass one wrapper deep; History and model-options sheets call `glassDialog` before `show`.

Phone: on Code, History → Roleplay → Hub Continue from Ask (Roleplay tab/underline should not flash Chat). Fail a pair QR while a deep link succeeds (or the reverse under load): only the host form or only the error toast, never both. Bottom sheets on API 29: no rounded-bottom shadow; History ⋮ sheet opens without a double-tint flash.

# Handoff (2026-10-02, Import wave 28)

On `gradation/w28-import` (PR into `gradation/app-pass`). Import/export IO follow-ups after #114:
- Chat import side log still matches after a later save refreshes timestamp or grows the message count, when the title still names that chat (stamp+count alone used to drop waiting notes).
- `RpWallpaperBackup.apply` leaves when `BackgroundPhoto.prepare` fails (no raw-stub fallback), matching the CharacterImportSideLog path.
- Character portrait export (`encodeAvatarBase64`) recovers then skips a torn JPEG, matching wallpaper encode.

Phone: import chats with pins/facts, kill before notes land, open and send in one imported chat (or leave so it autosaves), relaunch (pins/facts should still apply). Export a character whose portrait write was killed mid-way (backup should omit the portrait, not embed the stub).

# Handoff (2026-10-03, Settings wave 28)

On `gradation/w28-settings` (PR into `gradation/app-pass`). Settings follow-ups after Voice/switch honesty (#104) and Code/theme/persona commits (#108):
- Settings toggles that still used `apply()` (haptics, data, trust-self-signed, app icon, photo options, Advanced LiveData mirrors, streaming, Style, Thoughts, chat mode, Code Thinking, chat text size, LAN cert pins) commit before return. Settings home skips a no-op `isChecked` write. Power tools is one switch; LiveData is ignored while that tap is still writing both halves.
- Appearance chat-text tiles ring a preset only when the stored scale is exactly 90/100/115/130. An in-chat +/- step no longer highlights the nearest tile and swallows the tap that would snap back.
- Picking a Voice engine while the master switch is off remembers that chip without turning Voice on.
- Advanced reasoning effort buttons stay off when the master switch is off or a positive token budget is set (that budget already replaces effort on the wire). Those writes commit.

Phone: step chat text to 120% with +/-, open Appearance (no tile ringed), tap L (becomes 115% and stays). Turn Voice off, tap Grok, leave and come back (Grok still the chip; mic stays off until the master switch). Set a reasoning token budget (effort presets grey out) and turn advanced reasoning off (they stay grey).

# Handoff (2026-10-02, Notifications/Away wave 28)

On `gradation/w28-notif` (PR into `gradation/app-pass`). Notif/away follow-ups after turn-done prefs clear (#113) and Speak/swipe (#105):
- `cancelSession` also cancels keys that remain only in the in-memory allocation map after `clearTurnDoneDedup` (posted + prefs dropped; shade may still be up until forget / open-in-app / next TurnDone).
- That clear also parks the shade id in `code_away_shade_hold` (not a dedup seed). After process death, forget / open-in-app still cancels the surviving entry, and the next finished turn reuses that id instead of stacking a second shade.
- Answer-ready Speak persists a `speaking` flag with shade meta so a cold Stop after process death does not restart TTS; Dismiss/Copy/`stopTts`/Chat resume (shade dropped, no live service) clear it.

Phone: Notify when away on → finish a turn → send another prompt → open the session (or forget it) without tapping the shade (turn-finished entry should clear). Kill the app after that second prompt and open/forget again (the old turn-finished entry should still clear, not stack). Background Chat, Speak from the answer shade, force-stop mid-utterance, tap Stop on the surviving shade (speech must not restart; Speak returns). Open Chat instead of tapping Stop (later Speak must not act as Stop).

# Handoff (2026-10-03, Stability wave 28)

On `gradation/w28-stability` (PR into `gradation/app-pass`). Persistence follow-ups after w27 move temps / encrypt_ok discard (#111). Not a redo of that pass:
- Backup rules and data-extraction rules name `.partial` / `.ready` / `.bak` for `pre_sqlcipher` / `encrypting` / `encrypt_ok` `-wal`/`-shm`/`-journal` (mains were already listed), and `.kept-1` / `.kept-2` of disposable `pre_sqlcipher` / `encrypting` mains and sidecars, so Auto Backup before the next open cannot upload a torn sidecar move temp or a uniqueKept plaintext rename.
- After `encrypt_ok` confirms the live open, `discardPlaintextBackupIfConfirmed` drops leftover `encrypting` sets and orphan sidecars (not only `pre_sqlcipher`) in the vault, at the databases root, and under `chat_db_hold` before the marker is removed. `.kept-*` renames of those disposable names go too, including a stacked `name.kept-1.kept-1` and a directory that cannot be deleted (that failure keeps the marker). Recovered / unreadable `.kept-*` stay. Clearing the marker first would let the next `drainHold` put a hold plaintext copy back into the vault.
- A new plaintext-to-SQLCipher export deletes leftover `encrypting` / `pre_sqlcipher` wal/shm/journal before ATTACH. Deleting only the main left a crashed export's `-wal` to be replayed into the new ciphertext or paired with the replacement snapshot. Failure paths still keep the plaintext snapshot and drop only the in-progress ciphertext sidecars.

Phone: leave `chat_database.pre_sqlcipher-wal.partial` or `chat_database.pre_sqlcipher.kept-1` beside the live DB and force a cloud backup before relaunch (rules should skip them). Leave vault `encrypt_ok` + `encrypting` (+ `-wal` or `.kept-1`) (after relaunch the leftovers and the marker should be gone). A plaintext name that cannot be deleted should still show `encrypt_ok` on the next launch.

# Handoff (2026-10-02, Chat wave 28)

On `gradation/w28-chat` (PR into `gradation/app-pass`). Chat/History follow-ups after w27 discard/promote (#110):
- First save / promote of an unsaved Chat whose caption was parked (Code, or a rebuild that left the field empty) moves that prefs entry onto the new id (`ComposerDrafts.promote`); blank-live rekey no longer drops the line. A blank field the user just edited (cleared) drops it instead.
- The Ask mode snapshot mirrors that moved caption, not the empty field. Chat puts it back on a blank unedited composer. Leaving Code (tab or Settings disabling Code) does the same, and does not replace a line still being edited.
- History search still finds that caption (and "hello photo" across caption+Photo) after the promote. A long draft row clips the gap to one line and keeps both ends bold.

Phone: type a caption + stage a photo on a new Chat, open Code, wait for first save to mint an id (or rotate under Code so the field is empty at promote), leave Code (caption and chip return). Clear the caption before that save and it stays gone. History search "hello photo" still finds the row; a very long caption still shows both words on one line.

# Handoff (2026-10-02, Code wave 28)

On `gradation/w28-code` (PR into `gradation/app-pass`). Code-mode fixes, unit-tested (`CodeProtocolTest`, `GitBridgeJsonTest`, `ListSessionsJsonTest`):
- A bridge `gitStatus` `path` or `branch` written as a whole-number double (`5.0` / `"5.0"`) still matches as `"5"` (same as browse names). A `listSessions` `harness` or `branch` written the same way still matches. A live `sessionStatus` `branch` does too, so it does not replace that list value with `"5.0"`; `""` still clears. A tool diff `path` (classic or v2 `changes`) and a tool location `path` written the same way still match, so the card and the Changes row agree.
- Cursor Agent tool names `BrowserTabList`, `BrowserTabNew`, `BrowserTabSelect`, `BrowserTabClose`, `BrowserInstall`, `BrowserTakeScreenshot`, `BrowserGetText`, and `BrowserGetTitle` map to search / execute / read cards (icon and log clipping). `BrowserNavigateForward`, `BrowserReload`, and `BrowserHighlight` use the execute card; `BrowserSearch` uses the search card.
- Tool detail lines join a `labels` / `assignees` JSON array (CreateIssue / UpdateIssue), and read Cursor's native rawInput keys `sub_issue_id`, `after_id` / `before_id`, `commit_id`, `tree_sha`, `category_id`, `from_branch`, `author`, `check_run_id`, `release_id`, `artifact_id`, `thread_id`, `team_slug`, `login`, `selector`, `language`, `commit_title`, `state_reason`, `default_branch`, `role_name`, and `dataset` / `time_range` (a whole-number double shows as `5`).

Phone: on Cursor Agent, a BrowserTabList, BrowserSearch, or BrowserGetText card should show the search or read icon. BrowserNavigateForward, BrowserReload, and BrowserHighlight should show the execute icon. A CreateIssue card with `labels: ["bug","help wanted"]` should show that list under the title. A Changes file whose path is `5.0`, and a tool diff or location path written the same way, should still resolve as `"5"`. A session whose branch arrives as `5.0` on session status should stay `"5"`.

---

# Handoff (2026-10-02, Import wave 27)

On `gradation/w27-import` (PR into `gradation/app-pass`). Import/export IO fixes, unit-tested (`RpLibraryImportTest`, `ChatImportSideLogTest`):
- Undecodeable / torn wallpaper (SOI/EOI or missing EOI) is left rather than written or retried every launch.
- Chat import side log matches after a rename when timestamp and message count still fingerprint the row (title alone no longer drops waiting notes).
- Chat / character / lore / prompt / system exports open the SAF destination with `"wt"` so an overwrite truncates.

Phone: import a character pack with a junk wallpaper (Memory should apply; wallpaper tile stays empty or keeps the old one). Rename an imported chat before notes land (pins/facts should still apply). Export chats over an existing longer JSON file (re-import must parse).

---

# Handoff (2026-10-02, Stability wave 27)

On `gradation/w27-stability` (PR into `gradation/app-pass`). Persistence follow-ups after w26 encrypt_ok / code_mode backup:
- Backup rules and data-extraction rules name `.partial` / `.ready` / `.bak` for the live `chat_database` set and for `pre_sqlcipher` / `encrypting` / `encrypt_ok`, so Auto Backup before the next open cannot upload a torn move temp.
- When the vault already has `encrypt_ok`, leftover `pre_sqlcipher` / `encrypting` at the databases root or under `chat_db_hold` are discarded instead of being drained back into the vault (the marker means the plaintext snapshot was confirmed disposable).
- Torn move temps under hold are discarded after the park pass (`.kept-*` stay so a failed `parkDbSet` cannot drop a previous hold main).
- Keystore-wrapped API keys commit on save (same durability as the chat-database passphrase and Code host tokens).

Phone: leave `chat_database.partial` or `chat_database.pre_sqlcipher.partial` beside the live DB and force a cloud backup before relaunch (rules should skip it). Leave `chat_database.encrypt_ok` in the vault with a root or hold `pre_sqlcipher` (after relaunch the plaintext leftover should be gone, not re-vaulted). Save an OpenRouter or xAI key and kill mid-save (key should still decrypt after relaunch).

# Handoff (2026-10-02, Notifications/Away wave 27)

On `gradation/w27-notif` (PR into `gradation/app-pass`). Notif/away follow-ups after Speak/swipe (#105) and prefs dedup seed (#102):
- `clearTurnDoneDedup` drops the turn-done prefs row (keeps in-memory id) so process-death seed cannot re-suppress the next finished turn.
- Answer-ready skips post when the Answers channel is IMPORTANCE_NONE / notifications disabled / no POST_NOTIFICATIONS; `rememberAnswerMeta` commits; TTS init failure and Dismiss/Copy clear a queued Speak so Stop does not stick.

Phone: Notify when away on, finish a turn (shade), send another prompt, kill the process, finish the next turn (shade should return). Background Chat with answer notifications on, block the Answers channel in system settings (no silent post). Cold Speak after a kill should still show Stop.

# Handoff (2026-10-02, Chat wave 27)

On `gradation/w27-chat` (PR into `gradation/app-pass`). Chat/History follow-ups after w26:
- History Discard draft on the open Chat while Code is showing deletes the parked scene JPEG (live was cleared into the map; `clearStagedAttachment` alone missed it).
- First save / promote of an unsaved Chat whose stage Code already parked moves that map entry onto the new id (`ComposerStaged.promote`); empty-live rekey no longer evicts the photo.
- History search no longer finds Photo for a draft Discard removed under Code.

Phone: stage a photo in Chat, open Code, History → Discard draft (JPEG gone; search "Photo" misses that row). Stage a photo on a new Chat, open Code, wait for first save to mint an id (or send then stage before promote), leave Code (chip returns on the saved thread).

# Handoff (2026-10-02, Code wave 27)

On `gradation/w27-code` (PR into `gradation/app-pass`). Three Code-mode fixes, unit-tested (`CodeProtocolTest`, `CodeBridgeBackendTest`, `ListSessionsJsonTest`):
- A bridge `listSessions` `model` or `cwd` written as a whole-number double (`5.0` / `"5.0"`) still matches as `"5"` (same as listHarnesses models / listWorkspaces). A `bridge/browse` entry name written the same way still matches.
- Cursor Agent tool names `BrowserFileUpload`, `BrowserClose`, `BrowserNavigateBack`, `BrowserPdfSave`, `BrowserIsVisible`, `BrowserIsEnabled`, `BrowserIsChecked`, `BrowserGetBoundingBox`, `BrowserLock`, `BrowserUnlock`, and `BrowserWaitFor` map to execute / read / think cards (icon and log clipping).
- Tool detail lines read Cursor's native rawInput keys `search`, `labels` / `label_name`, `assignee` / `assignees`, `milestone` / `milestone_title`, `state`, `parent_id`, `todo_id`, `status_id`, `type_name`, `old_path` / `new_path`, `link_type`, `merge_method`, `expected_head_sha` / `head_sha`, `reply_to_id`, `color` / `new_name`, `check_name`, `actor` / `event`, `affiliation`, `verdict`, `job_status`, `health_status`, `weight`, `due_date` / `start_date`, `key` / `keys` / `index`, `zone_id`, `environment`, `element` / `attribute`, `is_project`, and `commit_message` (a whole-number double shows as `5`).

Phone: on Cursor Agent, a BrowserFileUpload or BrowserIsVisible card should show the shell or read icon. A CreateIssue card with `labels` should show that label under the title. A listSessions row whose model is `5.0` should still resolve as `"5"`.

# Handoff (2026-10-02, Notifications/Away wave 26)

On `gradation/w26-notif` (PR into `gradation/app-pass`). Notif/away follow-ups after cold-start away ids (#92), away/pair park (#97), and prefs dedup seed (#102):
- Answer-ready Speak defers until TTS `onInit`, and persists shade title/text so a cold Speak (notification posted without a live service) can flip to Stop. `clearLegacyRunningNotification` no longer `stopService()`s on every resume (that killed mid-utterance Speak).
- Code away shade swipe-dismiss clears dedup and the prefs allocation so a still-pending approval can re-alert (including after a later cold start that would otherwise re-seed); a channel set to IMPORTANCE_NONE skips posting.

Phone: background Chat with answer notifications on, tap Speak on the shade (speech should start; Stop appears), open the app briefly (speech should continue). Enable Notify when away, swipe an approval alert away while the request is still pending, then trigger the same approval again (shade should return). Block the Code away channel in system settings (no silent "posts").

# Handoff (2026-10-02, Import wave 26)

On `gradation/w26-import` (PR into `gradation/app-pass`). Import/export IO fixes, unit-tested (`RpLibraryImportTest`, `ImportBoundsTest`):
- Character import side log matches by `exportKey` when present, so a rename while wallpaper/portrait is still waiting no longer drops those pictures.
- Resume drops only finished or rejected snapshot rows (keeps notes a concurrent import wrote).
- An undecodeable / tiny SOI-EOI portrait is left rather than retried every launch. UTF-32 BE backups are rejected like UTF-32 LE.

Phone: import a character pack, kill before pictures land, rename the character, relaunch (Memory and portrait should still apply). Import while another import's pictures are still applying (second character's notes must not vanish).

# Handoff (2026-10-02, Stability wave 26)

On `gradation/w26-stability` (PR into `gradation/app-pass`). Persistence follow-ups after w24:
- Backup rules and data-extraction rules name `code_mode.xml` (hosts JSON can still hold a pairing token when a Keystore vault write failed) and `chat_database.encrypt_ok` `-wal`/`-shm`/`-journal`, so Auto Backup before the next open cannot upload them.
- A leftover `encrypt_ok` (and unexpected sidecars) at the databases root is discarded when the vault already has the marker, instead of `uniqueKept` into the vault.
- Code away-notification dedup after process death seeds from prefs-held keys (in-memory `posted` is empty), so a reconnect cannot re-alert a shade entry that survived the kill. `clearTurnDoneDedup` still clears memory only so a later finished turn can post again.

Phone: leave `chat_database.encrypt_ok` beside the live DB while the vault already has one, force a cloud backup before relaunch (rules should skip it; after relaunch the root copy is gone). Save a Code host when Keystore is unavailable (token may sit in `code_mode`; backup must not upload it). Post a Code away approval, kill the process, reconnect the same pending approval (shade should not get a second alert).

# Handoff (2026-10-02, Code wave 26)

On `gradation/w26-code` (PR into `gradation/app-pass`). Three Code-mode fixes, unit-tested (`CodeProtocolTest`, `CodeBridgeBackendTest`):
- A bridge `listHarnesses` model id written as a whole-number double (`5.0` / `"5.0"`) still matches as `"5"`. Digit-string `model` values go out as JSON numbers on `session/new`, same as the request id / sessionId / methodId / modeId.
- Cursor Agent tool names `ListPullRequestReviewComments`, `BrowserFillForm`, `BrowserGetAttribute`, `BrowserGetInputValue`, `ForkRepository`, `LinkWorkItems`, `ManagePipeline`, `AddCommit`, `SaveNote`, `SavePipeline`, `SaveMergeRequestReview`, `ListProjectMembers`, `ListRepositoryTree`, `SearchLabels`, `GetSavedViewWorkItems`, `GetWorkItemTypes`, `GetMergeRequestNotes`, `WorkersBuildsGetBuildLogs`, `WorkersGetWorkerCode`, `ObservabilityKeys`, `ObservabilityValues`, and `MigratePagesToWorkersGuide` map to search / execute / read / edit / fetch cards (icon and log clipping).
- Tool detail lines read Cursor's native rawInput keys `artifact_path`, `saved_view_id`, `discussion_id`, `full_path`, `milestone_id`, `author_username` / `assignee_username` / `reviewer_username`, `source_branch` / `target_branch`, `ref_name`, `namespace_path`, `q`, `scope`, and `iid` (a whole-number double shows as `5`).

Phone: on Cursor Agent, a ListPullRequestReviewComments or BrowserFillForm card should show the search or shell icon. A GetArtifactFile card with `artifact_path` should show that path under the title. A listHarnesses model whose id is `5.0` should still resolve as `"5"`.

# Handoff (2026-10-02, Chat wave 26)

On `gradation/w26-chat` (PR into `gradation/app-pass`). Chat/History follow-ups after w25:
- Switching chats while Code is showing no longer remembers an empty live stage for the thread you leave (that wiped a chip Code had already parked). `parkStagedAttachment` uses `liveToPark` / `parkLive`; apply onto live is skipped until `leaveCodeMode`, same as a rebuild under Code.
- History search still finds Photo / Audio / files for the chat you left after that switch.

Phone: stage a photo in Chat A, open Code, History → open Chat B (A's chip stays parked); leave Code on B (B restores if it had a stage). History search "Photo" still finds A. Switch back to A under Code, leave (chip returns).

# Handoff (2026-10-02, Code wave 25)

On `gradation/w25-code` (PR into `gradation/app-pass`). Three Code-mode fixes, unit-tested (`CodeProtocolTest`, `CodeBridgeBackendTest`):
- A bridge `listHarnesses` id (and `listWorkspaces` path) written as a whole-number double (`5.0` / `"5.0"`) still matches as `"5"`. Digit-string `modeId` values go out as JSON numbers on `session/set_mode`, same as the request id / sessionId / methodId.
- Cursor Agent tool names `GetMergeRequest`, `GetProject`, `GetWorkItem`, `GetArtifactFile`, `GetPipeline`, `GetRepositoryFile`, `GetUser`, `ListMergeRequests`, `ListPipelines`, `ListWorkItems`, `ListGroups`, `ListProjects`, `SaveMergeRequest`, `AcceptMergeRequest`, `AddBranch`, `SaveWorkItem`, `WorkersList`, `WorkersGetWorker`, `WorkersBuildsGetBuild`, `WorkersBuildsListBuilds`, `SearchCloudflareDocumentation`, and `QueryWorkerObservability` map to fetch / search / edit cards (icon and log clipping).
- Tool detail lines read Cursor's native rawInput keys `merge_request_iid`, `pipeline_id`, `work_item_iid`, `project_id`, `group_id`, `buildUUID`, `scriptName`, `worker_id`, and `account_id` (a whole-number double shows as `5`).

Phone: on Cursor Agent, a GetMergeRequest or WorkersList card should show the fetch or search icon. A GetMergeRequest card with `merge_request_iid` should show that number under the title. A listHarnesses row whose id is `5.0` should still resolve as `"5"`.

# Handoff (2026-10-02, Chat wave 25)

On `gradation/w25-chat` (PR into `gradation/app-pass`). Chat/History follow-ups after w24:
- Code activate paths that skip `enterCodeMode` (away notification, pairing, last-tab restore) now park Ask caption and a live stage via `onBeforeActivate`, so `leaveCodeMode` / Settings disabling Code restore the chip instead of clearing an unparked live stage. Rebuild while Code is showing leaves the chip in the park map until leave.
- History search still finds Photo / Audio / files for that parked Ask draft.

Phone: stage a photo (or audio) in Chat, tap a Code away notification (or pair) without using the Code tab; leave Code (chip returns). Same after a rotation that restores the Code tab. History search "Photo" / "Audio" still finds the row.

# Handoff (2026-10-02, Chat wave 24)

On `gradation/w24-chat` (PR into `gradation/app-pass`). Chat/History follow-ups after w23:
- Ask→Roleplay via Hub Continue / Start chat / Settings parks Ask caption and staged audio/files before Roleplay chrome clears them (photos already did), then restores that Ask thread's parked text on return. Tab swipe already parked text; Hub paths do too now.
- History Discard draft falls back to the park map when the open composer is dirty but the live stage is empty (Code or a mode flip cleared it).

Phone: type a caption + stage audio (or a file) in Chat, Hub → Continue into Roleplay (no chip / no audio in RP); back to Chat (caption and chip return). Same via Settings disabling Roleplay. Stage a photo, open Code, History → Discard draft (parked JPEG goes).

# Handoff (2026-10-02, Code wave 24)

On `gradation/w24-code` (PR into `gradation/app-pass`). Three Code-mode fixes, unit-tested (`CodeProtocolTest`):
- Digit-string `methodId` values go out as JSON numbers on `authenticate`, same as the request id / sessionId, so a proxy that rewrote `5.0` still gets a numeric method on the wire. An auth method id written as a whole-number double (`5.0` / `"5.0"`) still matches as `"5"`.
- Cursor Agent tool names `DeleteDiscussionComment`, `DeleteLabel`, `DeletePendingPullRequestReview`, `GetCommitCombinedStatus`, `GetLabel`, `GetReleaseByTag`, `ListDiscussionCategories`, `ListWorkflowRunJobs`, `ListNamespaces`, `ListRepositories`, `GrepContents`, `MarkDiscussionCommentAsAnswer`, `UpdateDiscussionComment`, `UpdateLabel`, `CreatePullRequestComment`, `CreateRepository`, `DismissPullRequestReview`, and `RequestPullRequestReviewers` map to delete / fetch / search / edit cards (icon and log clipping).
- Tool detail lines read Cursor's native rawInput keys `tag` / `tag_name`, `job_id`, `comment_id`, `review_id`, `username`, `org` / `organization`, `namespace`, and `category` (a whole-number double shows as `5`).

Phone: on Cursor Agent, a GetLabel or CreateRepository card should show the fetch or edit icon. A GetReleaseByTag card with `tag` should show that tag under the title. An authenticate whose methodId is `5` should send JSON number `5`.

# Handoff (2026-10-02, Stability wave 24)

On `gradation/w24-stability` (PR into `gradation/app-pass`). Persistence follow-ups after w22 plaintext sidecars:
- Backup rules and data-extraction rules name `chat_database.pre_sqlcipher` / `encrypting` `-wal`/`-shm`/`-journal` (not only the main files), so Auto Backup before the next open cannot upload a leftover sidecar the relocate pass would have moved.
- Code away-notification id allocation after process death treats prefs-held ids as taken (in-memory maps are empty), so a new alert whose preferred hash matches a surviving shade entry probes instead of colliding.

Phone: leave `chat_database.pre_sqlcipher-wal` beside the live DB and force a cloud backup before relaunch (rules should skip it). Post a Code away approval, kill the process, then post a second session whose preferred notif id collides (shade should keep both entries).

# Handoff (2026-10-02, Code wave 23)

On `gradation/w23-code` (PR into `gradation/app-pass`). Three Code-mode fixes, unit-tested (`CodeProtocolTest`):
- Digit-string `sessionId` values go out as JSON numbers on `session/load`, `session/prompt`, `session/cancel`, `session/set_mode`, `bridge/gitStatus`, and `bridge/diff`, same as the request id, so a proxy that rewrote `5.0` still gets a numeric session on the wire.
- Cursor Agent tool names `GetIssue`, `UpdateIssue`, `ListIssues`, `ListPullRequests`, `UpdatePullRequest`, `GetPullRequestDiff`, `ListPullRequestFiles`, `GetRepository`, `SearchRepositories`, `GetCommit`, `ListCommits`, `ListBranches`, `CreateLabel`, `AddDiscussionComment`, `GetDiscussion`, `CreatePullRequestReview`, `BrowserTabs`, and `BrowserEvaluate` map to fetch / edit / search / read / execute cards (icon and log clipping).
- Tool detail lines read Cursor's native rawInput keys `issue_number`, `pull_number`, `number`, `sha` / `commit_sha`, `ref` / `branch` / `head` / `base`, `discussion_number`, `label`, and `workflow_id` / `run_id` (a whole-number double shows as `5`).

Phone: on Cursor Agent, a GetIssue or UpdatePullRequest card should show the fetch or edit icon. A GetIssue card with `issue_number` should show that number under the title. A session whose id is `5` should send load/prompt with JSON number `5`.

# Handoff (2026-10-02, Chat wave 23)

On `gradation/w23-chat` (PR into `gradation/app-pass`). Chat/History follow-ups after w22:
- Late audio / file / PDF picks after an Ask→RP or Code flip park for the Ask thread (merging onto any entry already parked), matching late gallery/camera photos. Roleplay shows the attachments-disabled notice; Code restores the chip on return.
- History draft presence for the open Chat falls back to the ViewModel park map when the live stage is empty (Code parked it), so search and the row still show Photo / Audio / files.

Phone: stage a photo, open Code, History search "Photo" (row still shows it). Start an audio or file pick in Chat, flip to Roleplay before it lands (notice; chip back on Chat). Same with Code (chip returns on leave).

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
