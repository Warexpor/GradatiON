# Handoff (2026-10-01, code tool logs and changed paths)

On `cursor/code-tool-logs-paths-f82e`, targeting `gradation/app-pass`. Code mode only:
- A shell or search log drops terminal color, a window title, and a progress line that rewrites itself with a carriage return (including a clear-to-end). The card and the full output show the words. A file read keeps an escape that was in the file.
- A tool status of `in-progress`, `in progress`, or `running` is running, so the spinner is not stuck on waiting. A plan step spelled `in-progress` is the current step.
- Changes: a rename (`old -> new`, quotes included) opens and copies the new path, and the row names the old one. A path git quoted, including octal UTF-8, is unquoted before the diff request.

Phone: run a command that prints color (the card should be the words). Open Changes on a rename and on a path with a space (the diff should be that file).

# Handoff (2026-10-01, RP wallpaper backup and photo files)

On `cursor/rp-photos-wallpaper-files-01f9`, targeting `gradation/app-pass`.
- A character backup carries that character's wallpaper. Empty means there isn't one. A backup from before this field leaves the picture already on the phone. A file that is too large to carry is left as it is.
- Scene photos are stored in app files, not the cache. Opening a chat copies a cache file, a Downloads picture, or the JPEG stored in the message into that folder when the old link is dead. A picture the character generated is kept the same way, and it is still saved to Downloads. The next request does not send that picture back. Continue, a rewrite, and a failed Continue leave it on the reply. A note that an older photo was shown is not written into Facts.
- Phone: set a wallpaper, export characters, import on a fresh library (the picture is behind that chat). Export again after removing the wallpaper (the next import has none). Send a photo, then a generated picture if you can, leave the chat, come back. Continue a reply that has a picture.

# Handoff (2026-10-01, fresh database does not inherit notes)

On `cursor/fresh-db-pref-quarantine-67e8`, targeting `gradation/app-pass`.
- A failed open starts a new chat database, and Room ids start at 1 again. Facts, pins, forks, unsent lines, and a character's Memory, layout, voice, lore pin, portrait, and wallpaper used to stay under the old ids, so the next chat inherited them. They are renamed (`aside.<stamp>.…`) and the pictures move next to the set-aside database. The GradatiON memory note (not a Room row) and the app background stay. A stamp that already has an archive uses the next one. Fact notes, Memory, and pins are `commit()`ed. Deleting a chat drops its pin in that same write.
- Phone: install over a build that already has chats (they should still open, with the same facts and pin). The failed-open path is not something to force on a phone; the unit tests cover it.

# Handoff (2026-10-01, chat reply text)

On `cursor/chat-reply-text-276b`, targeting `gradation/app-pass`.
- Copy and read-aloud use the words of the reply. A code card's language name and the padding that gives the header its height are left out (`ChatMarkdown.readable`). Long-press copy still includes the markdown, and the thoughts when there are any, without a blank line at the start.
- A long message you sent keeps the window when the only space is near the start (a short word, then a link). A trailing newline is not an extra line, so a three-line note is not folded.
- Phone: copy a reply that has a code block (the language name should not be in the paste). Read that reply aloud. Send a three-line note that ends with enter (no Show more). Send "See" plus a long link (the folded line still shows the start of the link).

# Handoff (2026-10-01, code diffs, tool cards, deny)

On `cursor/code-diff-hunks-tools-ed98`, targeting `gradation/app-pass`. Code mode only:
- A unified diff no longer drops a change whose text starts with `-- ` or `++ `. Those lines look like `--- ` / `+++ ` file headers. The header is still dropped before a hunk, after the hunk's declared line counts are used up, and when the path is git's (`a/`, `b/`, `/dev/null`, or a tab and a timestamp). The next file in the same patch still starts cleanly.
- A shell tool whose `command` is the program and whose `args` are a list shows the whole line. An argument with a space is quoted. `rawInput` sent as a JSON string is read the same way. A Read that only names `target_file` (or `filePath`) still shows the path.
- A `tool_call_update` with no earlier `tool_call` still makes a card. A later update that names a kind sets the icon; a status-only update leaves the kind alone.
- An approval option whose kind is `reject` or `deny`, or whose name starts with Deny or Reject, is a deny. The away-notification Allow action will not pick it.

Phone: open a diff that deletes a `-- comment` line (the line should be there, in red). On a Bash card whose command was `git` plus args, the line under the title should be the full command. Deny a request whose button says Deny.

# Handoff (2026-10-01, RP photos, Continue, and the panel)

On `cursor/rp-photos-scene-menus-c189`, targeting `gradation/app-pass`.
- A scene photo and a generated picture are written with the chat. Reopening restores the JPEG and the file, and the request does not send that file URI. Only the two newest pictures stay attached; older ones keep their caption plus a note that a photo was shown. A blank caption is not left next to the scene line.
- Continue that fails (including HTTP 4xx/5xx) keeps the reply and uses a notice. Swipe versions stay until the new text arrives. A model that echoes the rewrite note, including the multi-line `(OOC:)` this app sends, has that note removed.
- "Keep Facts up to date" only stops the quiet rewrite. Facts still go out, and they name you the same way the prompt does (`the user` when no persona). A captioned photo is still a photo in that summary. A caption-less photo is a lore beat. The panel subtitle expands `{{char}}` and `{{user}}`. The Lore tile is on when lore is on and a pin or the active book exists.
- Phone: send a photo, leave the chat, come back (the picture is there). Send three photos, then a line (the story should still answer). Continue, then force a failure if you can (the reply stays). Rewrite a reply. Turn Facts off after writing a note and send (the character should still know it). Open the panel with an active lorebook and no pin (the Lore tile is on). A card line `{{char}} waits for {{user}}` shows the names under the title.

# Handoff (2026-10-01, database copies stay out of backup)

On `cursor/db-backup-vault-4a6e`, targeting `gradation/app-pass`.
- Auto Backup included every file in the databases directory except `chat_database` and its wal/shm. The plaintext pre-SQLCipher copy, the encrypt temp, and a database set aside after a failed open lived there under other names, so a backup could upload the history in the clear. Those copies now go to `no_backup/chat-db`. A recovered database (used when the original file cannot be moved) is created there too; the preference stays the short name `chat_database.recovered-<stamp>`. While a copy is still in the databases directory, that file is the one opened, so a failed move does not start an empty database. The backup rules also exclude the journal, the old plaintext name, and `code_mode_secrets`.
- Phone: install over a build that already has chats and confirm they still open. Export chats, delete one, import, and confirm the pin and the fact notes.

# Handoff (2026-10-01, chat search lines and photo taps)

On `cursor/chat-search-lines-and-photo-0fbc`, targeting `gradation/app-pass`.
- History search keeps the matching words on the one-line row. A hit late in the message is pulled forward. A line with no spaces still shows (a link, or Chinese, Japanese or Korean). `snake_case` stays `snake_case`, so the row can mark it. A slice of a photo's data is still not a line.
- A sent or generated picture is tappable only after the file has drawn. If that file is gone, the JPEG stored in the message is shown and the dead link is not tappable. If neither loads, the empty frame is removed.
- Edit says so when the photo cannot be put back. The full-screen composer keeps the keyboard inset instead of clearing it. Typing past the sixth line keeps the caret in view.
- Phone: search History for a word near the end of a long message, and for a line with no spaces. Open a chat whose photo file is gone (the picture stays, and it does not offer to open the dead link). Turn on expandable input, open the keyboard, and confirm the buttons sit above the keys.

# Handoff (2026-10-01, code reconnect, tools, session)

On `cursor/code-reconnect-tools-session-0d3f`, targeting `gradation/app-pass`. Code mode only:
- A `tool_call_update` that omits `kind` still clips a file read from the start and a shell log from the end. A path fills an empty card. A folder-only update still does not replace the command. `rawOutput` (a string, or `stdout`) is shown when there is no content block. A unified patch of a new file is marked new. A deletion is not. Status `R100` shows R.
- A file or terminal request forwarded to the phone is answered again if the first send does not go out. The reply is dropped when the socket drops, so it cannot land on the next link. `authenticate` that answers "Unknown method" or "not implemented" still continues. "Sign in required" does not.
- The session menu has Rename (the bridge cannot replace that name). Leaving the session keeps the unsent line and pictures until you send or clear them. Changes, and a file diff, say to tap when the load fails and that tap tries again.

Phone: drop the link during a tool call and come back. On a long Read, confirm the start of the file is on the card after the tool finishes. Open Changes with the bridge down and tap the message. Type in a session, leave, come back (the line is there). Rename from the session menu.

# Handoff (2026-10-01, chat search and bubbles)

On `cursor/chat-search-and-bubbles-ab1f`, targeting `gradation/app-pass`.
- History search ignores a photo's bytes and the JSON keys around a caption, and trims the query. A message that is just the word "text" still matches. The preview window is taken from that same stripped text, so it is not a slice of base64.
- A file URI is tried first. When that load fails, or there is no file (a reopened chat), the bubble draws the JPEG stored in the message and drops the tap that would have opened the dead link.
- A long message you sent has a Show more / Show less label under the bubble. Two copies of the same long message fold separately.
- Ask leaves the composer alone when a send is refused. A photo send hides the chip immediately but keeps the file URI until the message takes it, and puts the line back if the send is refused.
- Phone: search History for "image" (photo chats with no caption should stay out; a caption that says image should show). Open a chat you sent a photo in (the picture is in the bubble). Send a long note and tap Show more.

# Handoff (2026-10-01, encrypt crash and draft archive)

On `cursor/stability-data-guards-3c8a`, targeting `gradation/app-pass`.
- A kill during the plaintext-to-SQLCipher step used to let the next launch create an empty database and delete `chat_database.pre_sqlcipher`. The copy is restored when the main file is missing or empty, and it is deleted only after the encrypted file has opened (`chat_database.encrypt_ok`). A failed passphrase archive no longer mints a new key over the only blob.
- Imported pins and fact notes are one commit. A composer-draft blob this version cannot read is copied to `ask_composer_drafts.unreadable` before a save replaces it. A short slice of a long message fails the read instead of skipping characters. A database error while saving a chat, or while History is building its rows, shows a notice instead of closing the app.
- Phone: install over a build that already has chats (they should still open). Export chats, delete one, import, and confirm the pin and the fact notes.

# Handoff (2026-10-01, code handshake, tools, diffs)

On `cursor/code-handshake-tools-diffs-0462`, targeting `gradation/app-pass`. Code mode only:
- After the socket opens, an agent login advertised by `initialize` is completed with `authenticate`. "Method not found" still continues (the pairing token already authenticated). A newer protocol version, or a login that needs a terminal, stops instead of retrying. A file or terminal request that reaches the phone is answered so the agent is not stuck.
- A tool update that only repeats the working folder keeps the command. Long reads keep the start; long shell output keeps the end. A unified `diff` block is shown. CRLF file text is not a rewrite of every line.
- Changes: committing one file names that file. Reverting one file asks first.

Phone: drop the link and come back. On a shell card, confirm the command stays after the tool finishes. Open a long file read (the start is on the card) and a long test log (the end is). On Changes, open one tracked file and cancel Revert.

# Handoff (2026-10-01, RP photos, rewrite, and names)

On `cursor/rp-photos-rewrite-greeting-737a`, targeting `gradation/app-pass`.
- A photo with no caption is still only the picture in the chat. Every request, including the next turn, Continue, and a rewrite, adds the scene line so a provider that rejects an image with no words still answers. Rewriting that turn no longer copies the data URL onto the next message. Continue keeps a picture already on the reply. Edit puts the photo back in the composer, and a bubble still shows the picture when the file is gone.
- `{{char}}` and `{{user}}` keep a name that contains `$` or `\`. Lore and the fact-note transcript expand those placeholders in the scene, so a caption written that way still matches.
- Phone: send a photo with no caption, then another line (the character should still see the picture). Rewrite the reply after that photo, then send text (the text should not carry the photo). Continue a reply that has a generated picture. Edit a photo message and send it again. A character named with a `$`, greeting `Hello {{user}}`, rewrite the greeting, save without changing the greeting (the rewrite stays).

# Handoff (2026-10-01, history shows unsent lines)

On `cursor/chat-drafts-history-9b5f`, targeting `gradation/app-pass`.
- History (Ask) shows an unsent composer line as "Draft: …" instead of the last message, and search finds chats by that text. Long-press has Discard draft. Deleting the chat drops the line. Opening the drawer parks the open field first so the current line is in the list.
- A cold start no longer puts the last Ask↔Roleplay snapshot back into the composer when this thread's draft is different, including after a send. The snapshot is updated to the thread on screen whenever a draft is parked or restored.
- Phone: type in one chat, open History (the row says Draft), search a word from that line, discard it (the composer clears if that chat is open). Leave the app on a different chat's unsent line, kill it, and confirm that line is the one restored.

# Handoff (2026-10-01, stream and voice bounds)

On `cursor/stream-voice-stability-d087`, targeting `gradation/app-pass`.
- A message part that is not a string no longer crashes chat open, History, Roleplay's last line, or PDF and HTML export (`MessageContent`).
- One stream event past 4 MB fails the turn. A reply past 1.5 million characters stops and keeps the text. Tool arguments stop at 2 MB. Generated audio past 12 MB is not saved.
- Dictation stops after five minutes and keeps the words. A clip past 8 MB is not loaded. A recognizer result that arrives after the session ended does not paste twice.
- Phone: dictate for a short clip and confirm the words land once. Open a chat that has a photo plus a caption. A very long reply should stop with the max-tokens notice rather than grow forever.

# Handoff (2026-10-01, RP lore keys and the scene)

On `cursor/rp-lore-keys-and-scene-1966`, targeting `gradation/app-pass`.
- A lore key written as `{{char}}` or `{{user}}` matches that person, and an entry can pull the next one when the link is a name. A trailing period on a key, and a fullwidth `[keys:]` header, still match. Speech style is in the scan with the scenario and the personality (`RpPromptEngine.loreCardFields`).
- Continue starts a new paragraph after 。！？ and after 「」, and does not put a space into Japanese, Chinese or Korean. `_(Reminder :)_` and a fullwidth colon still parse. A reply wrapped in a plain code fence is unwrapped, and a scene note echoed at the start of a reply is dropped when the story goes on. Character lists expand `{{char}}` and `{{user}}` in the tagline.
- Phone: a lorebook keyed on `{{char}}`, Continue on a Japanese line that ends in 。, and a card whose personality is `{{char}} waits for {{user}}` on the characters list.

# Handoff (2026-10-01, code changes review)

On `cursor/code-changes-review-7437`, targeting `gradation/app-pass`. Code mode only:
- Changes filters by path. The filter does not change what Ask to revert all covers. That count is tracked files only (` M`, `M `, renames); `??` and ignored paths stay out, and the action is off when nothing is tracked. An untracked file's diff hides Restore-to-HEAD. Long-press a row to copy the path.
- Unified diffs drop git preamble (rename, mode, index) and a binary patch body. `+++ heading` stays an added line. CRLF hunks still parse.
- Phone: open Changes on a repo with a mix of edits and a new file. Filter to the new file (revert all still offered). Open it (no revert button). Open a tracked file (revert is there).

# Handoff (2026-10-01, backup carries prefs)

On `cursor/export-prefs-stability-1a11`, targeting `gradation/app-pass`.
- A chat backup now keeps the date, the pin, and that chat's fact notes. A character backup keeps Memory, layout, read-aloud voice, and the lorebook pin (by name). A pin whose book is not imported yet is applied when the lorebook backup comes in. An older backup still imports and does not wipe notes it does not contain.
- Export writes one message at a time. `getAllSessionsWithMessages` uses the sliced reader, so a long attachment is not loaded with `SELECT *`. The chat database passphrase is `commit()`ed. Code prefs and host-token prefs go through `TolerantPrefs`.
- Phone: export chats, delete one, import, and confirm the date, the pin, and the fact notes. Export a character, import on a fresh library, and confirm Memory, layout, and voice. Import the lorebook after the character and confirm the pin attaches.

# Handoff (2026-10-01, chat composer drafts)

On `cursor/chat-composer-drafts-2822`, targeting `gradation/app-pass`.
- Chat (Ask) keeps unsent composer text per thread (`ComposerDrafts`, `AskComposerDraft`). Leaving for another chat or a new one parks the text and restores that thread's. A first save moves the unsaved slot onto the new id without clearing what you just started typing. Send forgets that thread's draft. A staged photo or file is dropped on the switch so it cannot go out with the next chat. Roleplay still uses the mode draft and is not swapped by this.
- History scrolls to the open chat (section header included) when the drawer opens. Search submit clears focus so the keyboard closes. A long user message of many short lines folds at three lines.
- Phone: type in one chat, open another, come back (the text is there). Send it and come back (it is gone). Stage a photo, open another chat (the photo is not still attached). Open History (the open chat is in view) and submit a search (the keyboard closes).

# Handoff (2026-10-01, RP lore budget, rewrite focus, fact copies)

On `cursor/rp-lore-rewrite-memory-b423`, targeting `gradation/app-pass`.
- A long always-on lore block is cut so the entry that matched still fits, and a short always-on block stays beside a long match (`RpLore.fitPicked`). Memory and this chat's facts share the pin, so a long Memory note cannot hide a fact. The card's personality, greeting and description are in that pin too, after the name and scenario.
- Rewriting a reply scans that reply (and, for an in-place rewrite, the turn before it) even when the latest reply was removed to stream a new swipe (`RpRewrite.loreFocus`).
- Fact notes expand `{{char}}` and `{{user}}` before dropping a copied Memory line, and a dash or a final period does not make the copy look new. `_(reminder:)_` matches in any case. Keys may be separated with `|` or a fullwidth comma, and quotes around a key are ignored.
- Phone: on a long chat, mention a lore key only in Facts (not the recent scene) and confirm the entry arrives; rewrite the greeting after the chat is long and confirm lore from that greeting still applies.

# Handoff (2026-10-01, code tool detail and changes)

On `cursor/code-tool-detail-search-7caa`, targeting `gradation/app-pass`. Code mode only:
- A shell tool call keeps its command (a string or an argument list) when the only location is the working folder. A file location includes its line, and extra paths past three are counted.
- An agent message that is a link or an embedded file is shown. A picture-only prompt from another device still makes a bubble. A diff whose old or new text is not a string no longer drops the tool call.
- Session search also matches the folder path, branch, model, and harness. Changes can ask the agent to revert every tracked file after a confirmation; untracked files stay.

Phone: open a real session, confirm a Bash card shows the command and a Read card shows `file:line`. Search the session list by folder. On Changes, Ask to revert all and cancel, then confirm on a scratch repo.

# Handoff (2026-10-01, RP scene, lore, memory)

On `cursor/rp-scene-lore-memory-7ec9`, targeting `gradation/app-pass`.
- Lore keys keep matching the character's name and scenario, the persona's name, the Memory note and this chat's facts after the recent scene is all that fits (`RpLore.sceneScan`). Entries pull each other until the chain stops, not just one hop. Keys may be separated with a semicolon or an ideographic comma.
- A `_(Reminder:)_` is a scene note, not the user's line. Every reminder in the message is kept (a second one used to be deleted), parentheses inside it are kept, and it is part of the lore scan, as is a rewrite note. The demo model adds one beat naming the note; a reply with no note is the same script as before.
- A trailing space or newline on the greeting is not a new greeting, so it does not replace a rewrite. Fact notes skip the Continue prompt and a bare scene note. A reply that goes on after echoing the note still counts.
- Phone: write two reminders in one send (the second should matter), mention a lore key only in Memory on a long chat, rewrite the greeting and save the character with a trailing newline on the greeting (the rewrite stays).

# Handoff (2026-10-01, cursor window, wrong-type prefs, stuck recovery)

On `cursor/stability-db-prefs-81e2`, targeting `gradation/app-pass`.
- A message longer than the cursor window is read with SQLite `substr` slices (`ChatMessageText`, `ChatDao.getMessagesForSession` / `getLastMessage`). Opening a chat catches a database error and shows `notice_chat_open_failed`.
- `TolerantPrefs` turns a ClassCastException on a preference into the default and leaves the stored value. A corrupt chat fork is no longer deleted (`ForkLoad`). Tool-list saves archive an unreadable blob. If recovery cannot move `chat_database` aside, the next open uses `chat_database.recovered-<stamp>` (`chat_db_file`).
- Imports accept a UTF-16 BOM. Prompt and system-message imports ignore unknown JSON fields.
- Phone: open a chat that has a large text attachment, export chats, and import a Notepad "Unicode" backup.

# Handoff (2026-10-01, chat history and message list)

On `cursor/chat-history-message-polish-4044`, targeting `gradation/app-pass`.
- History search rides the keyboard: the drawer is a sibling of the chat root, which consumes insets, so the drawer is padded from that same listener (`HistoryChrome`). The fragment root no longer pads itself.
- A search hit in an earlier message replaces the last-line preview with a window around the match. The open chat says "Open now", and a pinned row shows a pin. Matching words are bold.
- A long message you sent folds on a word (or after three lines) with a 44dp Show more under the bubble, not inside the hidden action row.

Phone: open History, focus search (the field clears the keyboard), search a word from an earlier message, and expand a long message you sent.

# Handoff (2026-10-01, code session and reconnect polish)

On `cursor/code-session-bridge-ux-164d` (PR into `gradation/app-pass`). Code mode only:
- A failed socket open during automatic reconnect schedules the next try (it used to stop). Coming back to the app opens immediately instead of finishing a backoff that started while away.
- ACP `session_info_update` sets the session title. A phone rename is pinned and is not replaced by the bridge; an unpinned row takes the list title on refresh.
- Tool cards show embedded resource text and an inlined terminal `output`.
- Away notifications: each alert has its own tap target, and the open token is reused until it is consumed so an earlier alert still opens.

Phone: drop the link, leave the app, come back (should reconnect without a long wait). Rename a session and confirm a bridge title does not replace it. Tap an away notification for the session that is not the latest one posted.

# Handoff (2026-10-01, backup and prefs hardening)

On `cursor/backup-prefs-hardening-186c`, targeting `gradation/app-pass`.
- Launch seeding no longer rewrites an unreadable `custom_models` or `custom_system_messages` blob, and the Maverick scrub waits too. A later save copies the blob to `key.unreadable` first (models, prompts, system messages, presets, personas, deleted-character remap, OpenRouter cache). Code host JSON is the same: token migration does not mark itself done, and a save archives the old text.
- Imports stop at 5 MB (16 MB for roleplay backups). Chat import strips a UTF-8 BOM. Exports go through `BackupIo.publish` (sync the cache file, then copy, and a short copy is an error). Character and lore imports are one Room transaction (`RpImportGuard` fails a test at a row). A bad portrait is skipped. Import still refreshes an idle greeting only when the card's greeting text changed.
- Phone: import a chat backup that is larger than a few megabytes, and a character backup, and confirm a failed export does not show the success notice. Rewrite a greeting, import the character without changing that greeting, and confirm the rewrite stays.

# Handoff (2026-10-01, code approval reconnect)

On `cursor/code-approval-reconnect-79a7` (PR into `gradation/app-pass`). Code mode only:
- Allow/Deny stays queued across a dropped socket and is sent once on reconnect. A second tap replaces the queued choice. Give-up is only when the socket stays up and still refuses the frame. Stop, detach, and turn end drop it.
- ACP `current_mode_update` moves the approval pill (`acceptEdits`, `bypassPermissions`, and this app's own mode ids). Unknown ids are ignored.
- Turn end line: token limit, too many steps, refusal, plus usage from `session/prompt` (`usage` or `_meta.usage`).

Phone: tap Allow, toggle airplane mode, come back (one answer, card shows allowed). Change mode on the computer and see the pill move. A turn that hits the token limit should say so.

# Handoff (2026-10-01, RP greeting and memory)

On `cursor/rp-greeting-memory-scene-fa48` (PR into `gradation/app-pass`). A rewritten greeting is no longer replaced when the character is saved, the persona changes, or a backup is imported, unless the greeting text on the card changed. A bubble that is still the card's line still follows a rename or a new persona name (`RpGreetingSync`). An in-place rewrite holds the turn so Stop cancels it. Lore scans start on a word. Fact notes drop scratchpads and lines copied from Memory, and a caption-less photo is a beat in that summary. The demo rewrite follows the last ask in a multi-line note. Phone: rewrite the greeting, save the character without touching the greeting (the rewrite stays), then edit the greeting text (the chat follows); Stop during a greeting rewrite.

# Handoff (2026-10-01, chat photo and keyboard)

A staged photo (or audio clip) with an empty composer now enables Send. New chat, remove, and a model that can't see photos all drop the staged attachment, so it can't ride into the next thread. Composer thumbnails keep the picture's aspect (EXIF quarter-turns included) inside a 156dp cap; the remove disc is a real 44dp hit target. Sent and generated photos use that same rounded frame, and a caption stays on the 16dp text inset. Keyboard follow no longer scrolls a short thread by the full keyboard travel: only a message the composer would cover moves, and a message pinned to the composer rides back down when it closes (`KeyboardFollow`, `ChatPhoto`). Phone: send a photo with no caption, open the keyboard on a short chat and on a long one, and check a portrait from the camera isn't sideways in the chip.

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
- Add-machine dialog wraps agent pills (Grok Build, Cursor CLI, Pi were off the card) and shows the
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
