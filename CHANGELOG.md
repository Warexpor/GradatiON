# Changelog

## Unreleased

### Added
- History shows the line you have not sent yet, as "Draft: …", in place of the last message. Search finds that text even when it is not in a sent message. Long-press offers Discard draft. Deleting the chat drops the unsent line too.
- Code: the Changes screen filters the file list by path. The line under the title says how many of those files are already tracked and how many are new.
- Each Chat thread keeps the message you were typing. Open another chat, or start a new one, and that text is there when you come back. A photo or file staged on the composer stays with the thread you attached it to (and comes back with it), instead of being sent into the next one. A chat that is saved for the first time keeps the line you have already started.
- Roleplay lore also matches the character's personality, greeting and description. Rewriting a reply still matches lore from that reply, and from the turn just before it, after the chat has moved on.
- Code: the Changes screen can ask the agent to revert every tracked file, after a confirmation. Untracked files are left alone. Search on the session list also matches the folder path, branch, model, and harness.
- History search shows the line that matched, including a hit in an earlier message, and marks those words. The chat you have open reads "Open now", and a pinned chat keeps a pin on its row.
- Roleplay lore stays with the story as the chat grows. The character's name and scenario, your name, the Memory note and this chat's facts keep matching keys after the recent scene is all that fits. A lore entry can pull the next one, and that one the next. A scene reminder is kept even when you write two, it may contain parentheses, and the model is told it is a note rather than your next line. The demo model follows that note.
- History groups chats by day (Today, Yesterday, This week, Earlier), with pinned chats still first, and shows the last line under the title. A photo with no caption reads as "Photo".
- Code: the approval pill follows the harness when it changes mode, including the mode ids Claude Code and Codex use. A finished turn says when it stopped at the token limit, after too many steps, or because the agent refused, and shows token and cost counts when the bridge sends them.
- Roleplay rewrite quotes the reply you are changing and offers Shorter, Longer and More dialogue. The demo model accepts a scene photo and answers it. A photo with no caption still tells the character the picture is in the scene.
- RP character panel pages: every tile (History, Memory, Lore, Edit, Voice, Persona, Wallpaper, Layout, Style) opens its own full-screen page with a drawn hero, cards and a Save pinned above the keyboard. The sheet stays open under the page and refreshes when you come back. History lists that character's chats by day (current one marked "Open now") with a New chat button. Layout shows three drawings with a ring on the chosen one; Wallpaper previews the picture in a phone shape; Voice lists Default at once and adds the engine's voices when speech is ready; Style is Writing, Story and Mode cards.
- Persona can be switched off once one exists: a "Use persona in chats" switch on the Persona screen. Off keeps it saved but characters stop seeing your name and description; saving a persona turns it back on. The persona screen now has a tappable portrait, a name and About field, and your saved personas as rows (up to 12).
- Choosing an avatar photo (characters and personas) opens a small menu under the portrait: Gallery (your gallery app) or the system Photo picker. A frame step then lets you drag and pinch to choose what part of the photo shows, and saves a 512px square.
- Streaming reveal pacing (`StreamRevealPacing`): text eases in at a pace set by how fast it is arriving, so a slow stream no longer drains, stops and lurches. Chat and Code share it.
- Help is rewritten for the current app (Chat, Roleplay and Code, History, the composer, voice, models and reasoning, backgrounds, Settings) and now lives in `res/raw/help.md`.
- Every refusal and failure now says so. Toasts had been silenced app-wide, and about 165 messages with them (missing key, save failed, name required, export results, "nothing to copy", tool errors, LAN load failures, the reply cut at max tokens). They now show as a `GlassNotice` under the top bar, or as the field's error text inside a dialog. The notice is announced to TalkBack, stays longer for longer text, and can carry an action ("Open folder"), which replaced the two Material snackbars.
- Settings rows show their current value: theme, voice engine, local server, saved or missing API keys, max tokens, timeout, chat memory.
- Roleplay: the character History page has ⋮ / long-press delete with a confirmation and an empty state; Memory and Voice ask before discarding edits; removing a wallpaper confirms; the empty home has an "Add a character" button; the Memory and Facts fields show a 0/4000 counter; the character library's row menu is the glass `MessageMenu`.
- Code: unanswered approvals expire when the turn ends ("Expired"); Allow/Deny lock while the answer is in flight and say so on failure; the session screen has loading, waiting and failed-attach states with tap-to-retry, and the offline banner shows the reason; a dot on the Code tab while a session waits for approval on another tab; swiping a running session away asks first, and any other removal has Undo; a dropped-frame gap reloads the session from the last seen event.
- Voice: a recording still being transcribed is kept when you tap send or leave (it used to be thrown away), the send waits for it, and the mic turns into a cancel while transcribing. A missing key, model or server is reported before recording starts. The phone recognizer stops after 90 s of silence or repeated empty results instead of cycling forever.
- Chat: streaming, fades, the copy check and the glass switch all honour the system "animations off" setting; message copies confirm with the check animation and a haptic; the haptics preference applies to every tap (new `Haptics.tap`).

### Changed
- The demo model's rewrite follows the last ask in the note, so "don't shorten it, make it longer" comes out longer, and a note of more than one line is read in full.
- Roleplay characters list: the top-left button is a gear that opens Settings (inside a chat it stays the back chevron).
- Character panel: square tiles with flat drawn art that fills each card; no edge line; the header's Switch button and the New chat tile are gone (New chat lives on the History page).
- About 50 older icons were redrawn as 24dp round strokes to match the rest, and `ic_licenses` was added. 30 unused drawables, plus about 100 unused layouts, menus, colors, styles and strings, were removed.
- Settings rows share one text edge and divider inset; the local-network row has its own icon. The Controls grid packs the tiles that are visible and hides New chat instead of leaving a gap.
- Labels and notices use sentence case throughout (Settings, dialogs, tool names), with `…` and straight apostrophes, and no trailing period on short notices. The tool manager, notification channel and share-sheet entries now read from string resources.
- "Local network" is the one name for models on your own server (it was sometimes "LAN").
- Build: one `buildFeatures` and one `configurations.all` block, view binding off, Gradle build cache and parallel on, lint checks for unused resources, hard-coded text, missing descriptions and small touch targets, and the project is named GradatiON.

### Fixed
- Roleplay list lines keep an underscore between letters that are not ASCII (`déjà_vu`, `карта_реки`). The character list, a character's History, and the library card used to drop it. `snake_case` and a `#` `~` or `>` that belongs to the words still stay, and emphasis underscores still come off.
- An example's sample name is not the character. When the card is named Jordan and you are Alex, `{{user}}` and `{{random_user_1}}` used to come out as Jordan, so both sides of the sample were the character.
- A `<START>` or `END_OF_DIALOG` line that ends the example text is not saved as the reply, and `END_OF_DIALOG` between exchanges starts a new one. `Bot:` is the character side. A `<START>` inside a sentence still stays in the reply.
- Settings > Models: an IPv4 local server with one trailing dot (`http://10.0.0.23.:11434`) saves. Java reports that form as having no host, so Save used to refuse it, and the Models row printed the whole URL. A link-local address with a zone id (`fe80::1%wlan0`, or `%25`) is refused, and so is an IPv4-mapped address with a leading zero (`::ffff:192.168.001.001`). Those used to save, then every request failed because the HTTP client will not open them. The same addresses without the zone or the leading zero still save, and a public literal is still refused.
- Code away: an approval whose request id contains ':' is not the same shade as a longer session id that shares that text. `ab` waiting on `cd:r2` used to replace `ab:cd` waiting on `r2`, and answering one cleared the other's open token.
- Code away: a shade the system drops without a swipe loses its open token when a later alert is handled, not only after the process dies. The alert that was just posted again keeps the token already on that shade. A second shade for the same session still keeps it.
- Answer shade: Speak puts Speak back when the reply strips to nothing. The engine never starts, and it never calls back, so the shade used to stay on Stop.
- A 0-byte recovered database no longer takes the hold name from the copy that still has the history. Parking that empty file used to rename the hold copy to a name Room does not open, so the next launch read a stale file or an empty one. An empty file in the no-backup folder no longer blocks the hold copy from moving in.
- Deleting a portrait or a wallpaper removes the side file before the live file. A kill between those used to leave the side file, and the next open put the picture back.
- A sliced message stops when the next step would not fit in the character index. That addition used to wrap, and the read appended the tail again.
- History preview keeps a line that is only a hash or another long token. It used to be treated as a slice of a photo, so the row went blank. A data URL is still not a line. A picture reply whose words are stored in `body` still shows those words when the history read is cut inside that string.
- A text file keeps the indent on its first line. Trimming the whole file used to send a snippet or a patch as a different file. A file that is only whitespace is still empty, and a code fence inside the file is still wrapped in a longer fence.
- Sending a staged photo uses the file from the tap, and does not start the turn if another chat opened while the photo was encoded. The line stays on the chat that was on screen.
- A character import that dies after its notes are written and before the card is saved no longer applies Memory, the lore pin, or the pictures on the next launch. The card that was already there used to take them. A file that lists the same character twice uses the last copy when deciding whether the greeting changed. A portrait or wallpaper that is too big to embed is scaled into the backup; it used to be left out, so the other phone kept the picture it already had.
- Settings > Models: a local server password that contains `@`, or a space, saves when the host is an IPv6 address (`http://user:p@ss@[fd00::1]:11434`). Java's URL parser throws on the brackets after that `@`, so Save used to say the URL was invalid. The Models row still shows the host and port. An unbracketed IPv6 address is still refused, and so is cleartext to a public address.
- Settings > Tools: Get location asks for precise and approximate location in one request. Asking only for precise is ignored on Android 12 and newer, so the dialog never appeared. Approximate location reads the network provider and keeps that fix. It used to ask GPS, which throws without precise location, and then wait for a 10 m reading that approximate location cannot produce.
- Roleplay example dialogs pasted from a card keep every exchange. A `<START>` line between them used to be saved as part of the first reply, so the later lines never became their own example. `{{user}}`, `{{char}}`, `{{bot}}`, and the older `<USER>` / `<BOT>` tags are read as the two sides.
- `{{bot}}` is the character's name in the prompt, the greeting, and a lore key, the same as `{{char}}` and `<BOT>`. It used to be left as the raw token, so a key written that way never matched.
- An example line's `{{random_user_N}}` is someone else in that sample, not the person in this chat. When your name was Alex or Jordan, `{{random_user_1}}` used to come out as you.
- The Roleplay hub and the character list skip a first line that is only a divider (`---`, `===`, spaced dashes, or hashes with no heading text). That line used to be the description, so the sentence under it never showed. A long first line no longer ends on half an emoji. `C#` on its own line still shows.
- A fully encoded pairing address decodes a form space. `URLEncoder` writes a space as `+`, and that used to be saved as a plus, so the bridge address was not the one that was encoded. A plus in the token itself stays a plus. A `#note` after a bridge address that already contains `#` is dropped; it used to stick to the token or the fingerprint.
- Code: `session/new` and `session/load` take the approval mode from the `configOptions` mode select when that is how the agent reports it, and a later `config_option_update` moves the pill the same way. A model row is not a mode. An id this phone does not show still does not fall back to the mode just requested.
- Code: a browser click or hover that names the element as `target` shows that ref. A drag uses `startTarget` and `endTarget`. A type, select, or scroll that also sends `target` still shows the typed text, the choice, or `down 500`, and a later update that only repeats `target` leaves that line. Filling a form shows the values, a dialog shows the prompt text or accept/dismiss, and a resize shows the window size.
- A recovered chat database that is 0 bytes is not the one that opens. Room leaves that name when an open dies before the header is written, and it used to hide the copy under hold or in the no-backup folder that still has the history. A file that still has bytes at the databases root is unchanged.
- A finished picture side file replaces the picture when it was written in the same timestamp tick. The old file used to stay, because the check required a newer stamp and the clock had not moved. A side log is written to a new file before it replaces the previous side file, so a kill during that write no longer drops the notes the next launch was going to apply. When the stamps match, that side file is still the one that is read.
- A sliced message read keeps only the characters that step asked for. A slice longer than the step used to be appended whole, and the next step wrote the overlap again.
- History preview keeps a sentence that starts with "data:" or mentions "base64,". Those used to be treated as the photo payload, so the row went blank or said Photo. A search hit already on the first line is no longer cut off the front. An underscore inside a word stays when the letters are not ASCII (`déjà_vu`).
- A text file whose body contains ``` is wrapped in a longer fence, so the rest of the file is not sent as the message. A line break in the file name stays on the header line.
- Sending a staged photo no longer deletes the JPEG the message still shows. The file used to be dropped as soon as the composer forgot it, and a new chat is not saved until the reply lands. The caption and any attached files are parked before the photo is encoded, so leaving during that send does not lose them.
- A character backup that names a lorebook keeps the pin on the copy of that book already chosen. It used to move to the newest row with the same name, so the character read the other text. A pin that was still waiting attaches to the active copy, which is the book chats were already using, not a newer inactive one. Clearing a wallpaper retries when the file is still there; the import note used to be dropped and the picture stayed. A character name is stored trimmed, and a blank name is not imported.
- Code: a browser type, click, or scroll that also names an element ref shows the typed text, the control, or `down 500`, not the ref. A later update that only repeats the ref leaves that line. A session listed as Ask, `default`, or Codex `read-only` replaces a local Full auto on refresh; a missing mode still does not. `session/new` and `session/load` take the mode the agent reports, including when that is Ask.
- Code away: a shade cleared without a swipe no longer blocks the next alert or keeps its one-shot open token. The system cancel does not run the DeleteIntent, so the prefs row still looked posted. The next approval or finished turn alerts again on the same id. A shade that is still up keeps its own token.
- Code away: the 64-entry cap remembers which shade was posted first. After a restart the prefs map is not that order, so the cap could drop a newer shade and leave the oldest. The oldest is still the one that goes.
- Answer shade: each Speak uses a new utterance id. A late end for the previous reading used the same id, so it put Speak back and stopped the engine while the next reading was still going.
- Settings > Models: a local server hostname with an underscore (`my_nas.local`), or a password that contains `@`, saves. Java's URL parser reports those as having no host, so Save used to refuse them, and the Models row printed the raw address including the password. The port has to be from 1 to 65535. A `?query` or `#fragment` on the address no longer swallows the path the app appends (`/v1/models` and the rest); the query stays, and the fragment is dropped.
- Settings > Tools: Get location stays available when the user allows approximate location. That grant is coarse only, and the switch used to require fine, then say location was denied. The folder URI and the local server address and server type commit with the tap.
- The Roleplay hub hero uses the same first line as the character list. It was folding the whole card field, so `{{char}}` stayed as a placeholder and a later paragraph showed under the name.
- A pairing link whose bridge address is written with its own query keeps that query's key case and percent-encoding. `Session` and `hello%20world` used to be stored as `session` and a space, so the saved address was not the one in the QR. A fully encoded address is still decoded once.
- Roleplay example dialogs keep both sides when the label has a space before the colon (`User : hi`) or the character speaks first. A `User:` line inside the reply stays on the character side.
- A lore header written as `Keys` or `KEYS`, or saved with a Windows line ending, still splits. Those blocks used to stay in the prompt even when the chat never mentioned the key.
- The Roleplay character list, a character's History, and the library card keep a `#`, `~` or `>` that is part of the words (`C#`, `~/Downloads`, `a > b`). A heading, a blockquote and strikethrough still come off. A first line that is only markdown marks no longer hides the sentence under it.
- Code away: Allow, Deny, or a cancelled turn drops the one-shot open token when that was the session's last shade. Those cancel the shade in code, so the swipe handler never runs, and a replayed tap could still open the session. Another alert for that session keeps the token. A shorter session id still does not clear a longer one.
- Code away: the 64-entry cap counts a turn-finished shade that is still up after the next prompt clears dedup. Those shades used to sit outside the cap, so a run of new prompts stacked past 64. The oldest one is cancelled, and its open token goes with it. After a kill, a parked shade id still counts.
- Answer shade: Stop from the chat puts Speak back after the speak service has died, and drops the shade when the chat is in front. Copy or Dismiss, then the utterance ending, does not post the shade again. Speech that is interrupted (the engine reports stop, not done) returns the shade to Speak.
- A character backup that names a lorebook this phone does not have yet drops the book already pinned to that character. Chats were still using the old book, and the next export wrote that book's name, so the waiting pin never left. A portrait that could not be written over the one already there stays in the import log and is tried again; a backup picture that is not a JPEG is still left alone. Two lorebooks with the same name export as the newest text, and stay on if either copy was on. Import updates every copy of that name, so a pin on the older row is not left with the old text or left on after the backup turned that book off.
- Code: a Codex session listed or switched as `auto` shows Auto-edit, `full-access` shows Full auto, and `read-only` shows Ask. OpenCode's `build` agent shows Auto-edit. Those ids used to be ignored on a live change and stored as Ask on the session list. A Cursor question with `allow_multiple` is skipped, instead of being answered as one choice. `BrowserProfileStart` and `BrowserProfileStop` use the execute card. A type, fill, or select with no element description shows the text, value, or values; a scroll shows `down 500`; an element drag shows `start → end`.
- Settings > Tools: a folder, location, or sound tool that is already on can be turned off when that permission is missing. The switch used to stay disabled, so the model kept the tool. Turning one on still needs the grant. Create file is unchanged.
- Settings > Models: HTTP to a local server accepts CGNAT (100.64/10, including Tailscale), IPv6 link-local (fe80::/10), and IPv6 unique-local (fc00::/7), including an IPv4-mapped form of a private address. Public literals are still refused. The local-server row shows host and port, not a user name or password pasted into the address.
- A recovered chat database that is still in the databases folder is the one that opens. A failed move into the hold folder used to open the older copy parked there, and left the current file where a backup could upload it. Setting history aside also moves a portrait or wallpaper `.bak` / `.partial`, so the next character does not get the picture that a killed save left behind. A long message that contains an emoji fails the read when a slice comes back short, instead of skipping ahead; the length check used to count UTF-16 units, and two units is one emoji.
- The Roleplay character list and a character's History keep underscores inside a word, so a line with `snake_case` still shows it. A photo with no caption reads as Photo instead of "No messages yet"; a caption still shows, and "You:" stays on that caption. The library card uses the same first line as the list, and a markdown mark no longer leaves a space in front of it.
- Example dialogs keep the User line when the block starts on a blank line. A `---` between blocks still splits when the break is a Windows line ending or the dashes have spaces around them. `User：` and `Char：` with a fullwidth colon still count.
- A lore key wrapped in parentheses, fullwidth parentheses, or black lenticular brackets still matches the word inside. A fullwidth semicolon or bar between keys still separates them, the same way a fullwidth comma already did.
- History preview keeps a `#`, `~` or `>` that is part of the words. `C#`, `~/Downloads` and `a > b` used to lose those marks, so the row no longer showed the text a search had matched. A heading, a blockquote and strikethrough still come off.
- Attaching several text files counts each one against the total already staged, and a file past 1 MB stops being read. Picking them together used to measure that total before any file was added, so the 3 MB cap never saw the rest, and a larger file was loaded in full and then refused.
- The Roleplay character list's top buttons follow the list, not the view that is still on screen while a thread slides over it. The back chevron was opening Settings, and the new-chat icon was opening the character library, for that whole slide. Long-pressing Manage characters no longer starts a new chat and closes the list.
- A pairing link keeps a bridge address that contains `#`, or a query parameter named like a pairing field (`token`, `auth`, `ws`, `fp`), when the real token and fingerprint follow. A fingerprint written with spaces is accepted. Those used to drop the token, cut the address, or reject the link.
- History groups a chat by calendar day, not by a 24-hour step. After a daylight-saving fallback the first hour of yesterday stayed in This week and showed a weekday; after a spring-forward the last hour of the day before yesterday showed as Yesterday. The six-day week uses the same calendar edges.
- Removing every attached file also drops that list from the parked composer. History and Code had already stored it, and an empty live stage skipped the next park, so the files came back on the row and in the composer.
- Opening an Ask chat from History while the Roleplay character list is showing no longer forgets that list. Coming back to Roleplay shows it again. A history open that does not land (a reply is still streaming, or the chat is already gone) no longer hides the list the next time Roleplay opens.
- A pairing link whose bridge address has its own query (`wss://…?a=1&b=2`) keeps that query when the `&` was not percent-encoded. It used to be cut at the first `&`, so the address was wrong even though the token still read.
- A failed pair test no longer treats a port or a timeout that merely contains 401 or 403 (port 4010, "4012 ms") as a rejected token. HTTP 401 and 403 still do.
- Code away: forgetting a session, or a finished turn clearing its approvals, no longer drops a longer session id that shares the prefix (`ab` vs `ab:cd`). Swiping away the shorter session's last alert clears its open token even while the longer one is still up. Dropping the oldest shade past the 64-entry cap clears that session's open token too (cancel does not run the swipe handler). Two once-reject choices are not a single Deny.
- Answer shade: a streamed turn that hands off to tools no longer replaces the finished answer's Speak/Copy line with the preamble, and an error no longer replaces it with "Error!" while the previous shade is still up. The speak line is committed, so a kill after the shade is posted cannot leave Speak reading the previous reply.
- Settings > Tools: turning off List files or Read file also clears the older `list_grokion_files` / `list_oxproxion_files` and `read_grokion_file` / `read_oxproxion_file` names. Those still counted as on, so the switch came back and the model could still call the tool. Create file is no longer locked behind the workspace folder grant; it writes through MediaStore and does not open that tree.
- Settings > Models: a local server address is stored and read without a trailing slash or a pasted `/v1`. Requests append `/v1` themselves, so `http://10.0.0.23:11434/` and `http://10.0.0.23:11434/v1` no longer call `//v1` or `/v1/v1`.
- Auto memory drops a scene-note or rewrite echo wrapped in the same brackets the reply cleaner already strips (fullwidth parens and the rest), instead of saving that note as a fact. A reply that continues after the echo keeps the story. An OOC line that is not the rewrite note stays.
- Deleting Vesna stays deleted when her row was already on disk but the seed flag never landed. While she exists, that flag is repaired. A stock refresh updates only fields that are still the previous seed; a renamed card, a rewritten greeting, a changed scenario, and the portrait uri stay.
- The active character id commits, so a kill just after opening a character cannot reopen the previous one. The demo seed flag and stock-avatar revision commit for the same reason.
- A lorebook listed twice in one backup keeps the last copy, including when that copy is not active. The earlier copy used to leave the book on, and it cleared a different book that was already active. Choosing a lore pin (or "use whichever is active") drops the book name a character backup was still waiting to attach, so the next launch cannot put that book back. A character with no portrait exports an empty picture, and import removes the old one; a torn file still exports nothing and leaves the portrait already on the phone. A backup from before this field is unchanged.
- SQLCipher export copies `PRAGMA user_version` onto the attached database. `sqlcipher_export` leaves it at 0, so Room treated an older plaintext file as new and skipped migrations (missing columns, then the history was set aside). A plaintext `-wal`/`-shm`/`-journal` that cannot be deleted is moved into the no-backup vault before the encrypted file is opened; if it cannot be moved, the plaintext snapshot is put back. `name.stuck-N` renames of those disposable files are removed once `encrypt_ok` is set, including at the databases root. Not a redo of the passphrase bind or the pre-ATTACH wal park (#130), or of sidecar temps after `encrypt_ok` (#118).
- Code: a Cursor todo marked `cancelled` (or `canceled`) stays cancelled on the plan card and, when the update is a request, is echoed as `cancelled` instead of `pending`. `success` still counts as done. A `create_plan` whose todos are only under `phases` still fills the card. Older `_cursor/ask_question` (and the other `_cursor/` methods) are handled as `cursor/`. `cursor/task` and `cursor/generate_image` sent with an id are acknowledged instead of "Method not found". Cursor `BrowserMouseMoveXy`, `BrowserMouseDragXy`, `BrowserMouseDown`, `BrowserMouseUp`, and `BrowserMouseWheel` use the execute card; a drag shows `start → end`, a wheel shows `deltaX, deltaY`, and a button press shows `button`.
- SQLCipher plaintext export binds the same 32-byte passphrase Room passes to `sqlite3_key` (`ATTACH DATABASE ? AS encrypted KEY ?`). The old `KEY "x'hex'"` form stored a raw key, so the migrated file could not be opened and recovery set the history aside. A leftover `encrypting` wal/shm/journal that cannot be deleted (including a non-empty directory) is moved off that name before ATTACH; if it cannot be moved, the plaintext snapshot stays and the export does not run. Auto Backup / device transfer exclude `ForegroundServiceAnswer` (answer shade title and the speaking flag) so a restore cannot revive Stop. `code_away_shade_hold` stays excluded by the notifications pass.
- Hub Continue / Start chat stay on the Roleplay thread: closing the character list is not undone when async setChatMode(RP) lands, including when Roleplay was last left on that list. Start chat while already in Roleplay does not leave the list suppressed for the next return from Ask.
- Turning Roleplay off during a reply still flips back to Chat once the reply finishes (the tab hid immediately, but the mode flip only ran while a reply was not in flight).
- Bottom-sheet glass survives the first layout. BottomSheetBehavior replaces the container background with its MaterialShapeDrawable on that pass, after the content glass was already cleared, so the sheet drew with no glass.

- Settings home names the Voice engine that is saved. A phone with no recognizer used to show Off (or Cloud, once a model was set) while Voice was still on and the Phone chip was selected.
- Inference fields accept a comma decimal (0,8) and store the dot form. Non-finite values, and numbers outside the sampler's range, are not saved, so the request keeps the previous value instead of dropping the knob or sending NaN. Presence penalty can be negative.
- Advanced timeout and max tokens stay inside the range those dialogs allow (1–45 minutes, 1–999999 tokens). A stored 0 no longer makes every request time out, and a blank or non-numeric token cap reads as 12000. Chat memory only checks a row when the stored count is that preset, so a count that is not in the list is not silently replaced by 8.

- Code away: clearing turn-done dedup parks the shade id in the same prefs commit that drops the live row. Two commits could die in between and leave the row, so the next finished turn stayed suppressed. A leftover legacy hold file is not a dedup seed (the next turn still alerts and reuses that shade id). Auto Backup and device transfer exclude `code_away_shade_hold.xml`.
- Code away: swiping away the last alert for a session clears its one-shot open token, so a replayed tap cannot open it. Another alert for that session keeps the token. A session id that contains ':' is not claimed by a shorter id.
- Answer-ready: stopping speech from the chat no longer leaves the shade saying Stop. That label started TTS again on the next tap, because the speaking flag was already clear.
- First save no longer deletes the staged scene JPEG it just moved onto the new chat id. Eviction compared map keys only, so a promote/rekey looked like a drop and History still said Photo for a file that was gone. The same file kept under the new id (including a copied entry) stays. Replacing that photo, or parking audio over it, still drops the previous file; another chat that still holds the uri keeps it.
- A character backup that lists the same character twice keeps the last copy. The earlier copy's Memory and pictures used to come back on the next launch.
- A lorebook backup where nothing is marked active turns those books off. Names that differ only by spaces count as the same book. A character pin still attaches when the book name is padded or longer than the saved pin.
- Opening the app finishes a character pin that was waiting on a lorebook, including after a kill between the lore import and that link.
- Character portrait `photoUri` no longer bypasses the torn-JPEG check: picker, panel, edit, speaker header and `RpAvatars.photoModel` treat an incomplete file as missing (same rule as #117's file-path path).
- Continue keeps the reply being extended (and the turn before it) in lore focus, so keys near the start of a long bubble still match when the recent window is tight.
- Persona Save commits name, about and photo (enabled already did), so a kill right after Save cannot drop them.
- Code: harness ids ignore case and underscores (`Claude_Code`, `cursor_agent`); a session list `permissionMode` of `acceptEdits`, `bypassPermissions`, `agent`, or `full_auto` keeps that pill instead of Ask. Cursor `BrowserMouseClickXy` and `BrowserCdp` use the execute card; a click shows `x, y`, and a reviewer request shows the `reviewers` list.
- Hub Continue / Start chat pin the Roleplay tab after uncovering Code: setChatMode(RP) is async, so deactivate's onTabsChanged could briefly select Chat before Roleplay lands.
- Pair activate: offer / offerError / clear / consume share one lock so interleaved QR and deep-link results cannot leave both pending and error set. consumeError drops a failure that a pairing already superseded.
- Bottom-sheet glass: topOnly outlines stay empty below API 30 (no fake rounded-bottom elevation); clearDuplicateSheetGlass also drops a nested content GlassDrawable; History and model-options sheets glass before show so the first frame is not double-stacked.
- Continue that fails after the first streamed chunk keeps swipe versions of that reply (alts used to clear on the first join, then a restored base left no way back). Success or Stop still drops them when the bubble grew.
- Character portrait delete also removes `.bak` / `.partial` side files so a later open cannot resurrect a removed avatar; a removed persona portrait's side files go with it. Export and UI (picker, edit, panel, speaker header, persona card) treat a torn portrait as missing (same completeness rule as wallpaper).
- Rewriting the latest reply (regen path) keeps the turn before it in lore focus, matching an earlier-reply Rewrite, so keys that live only on that beat still match after the reply is truncated out of the transcript.
- Chat import side notes still match after a later save refreshes the timestamp or adds messages, when the title still names that chat (stamp+count alone used to drop them). A character wallpaper apply no longer falls back to writing a raw stub when prepare fails. Character portrait export skips a torn JPEG (recover + completeJpeg), matching wallpaper encode.
- Haptics, Data (biometrics / notifications / keep screen on / destructive tools), Models (trust self-signed LAN), Appearance (app icon / photo options), Advanced LiveData switches, streaming, Style (lore / third person / auto memory / show thoughts), Thoughts tile, chat mode, and Code Thinking preference writes commit before return, so a kill right after the Settings tap cannot drop them. Settings home skips a no-op `isChecked` write so it cannot rewrite the pref.
- Advanced Power tools is one switch over the dock and the top bar. LiveData is ignored while that tap is still writing both halves, so turning the switch off cannot flip the dock back on.
- Appearance chat text size commits, and a LAN certificate pin (and clearing pins) commits, so a kill cannot drop the size or forget the first-use pin and let the next certificate win.
- Appearance chat text only rings a preset tile when the stored scale is exactly that preset. In-chat +/- (50–300, step 5) no longer looks selected at the nearest of 90/100/115/130, which swallowed the tap that would snap back to it.
- A Voice engine chip picked while the master switch is off is remembered, and does not turn the mic back on. Turning Voice on still restores that chip.
- Advanced reasoning effort buttons follow the master switch and a positive token budget: a budget disables the presets (the request already sends max tokens instead), and turning advanced reasoning off cannot leave those buttons on. The master switch, effort, include-thoughts, and budget commit.
- Code away: after `clearTurnDoneDedup`, `cancelSession` (forget / open-in-app) still cancels the turn-done shade by consulting the in-memory key→id map (posted + prefs no longer list that key).
- Code away: that same clear parks the shade id outside the dedup prefs, so after process death forget / open-in-app still cancels the surviving entry and the next finished turn updates it instead of stacking a second one.
- Answer-ready: Speak chrome persists a `speaking` flag with the shade meta, so a cold Stop tap after process death clears Stop instead of restarting TTS; Dismiss/Copy/`stopTts`/opening Chat (shade dropped) clear the flag.
- Auto Backup / device transfer exclude sidecar move temps (`.partial` / `.ready` / `.bak`) of `pre_sqlcipher` / `encrypting` / `encrypt_ok` `-wal`/`-shm`/`-journal`, and `.kept-1` / `.kept-2` of disposable `pre_sqlcipher` / `encrypting` mains and sidecars, so a torn wal copy or uniqueKept plaintext rename beside the live database cannot upload before the next open. After `encrypt_ok` confirms the live open, a leftover `encrypting` set (including orphan sidecars) is discarded with the plaintext snapshot, and `.kept-*` renames of those disposable names are dropped in the vault, at the databases root, and under `chat_db_hold` before the marker is removed (recovered `.kept-*` stay). Removing the marker first let the next open drain a hold copy back into the vault. The marker stays when a delete fails, including a non-empty directory left under a disposable name, so the next open retries. A new plaintext export deletes leftover `encrypting` / `pre_sqlcipher` wal/shm/journal before ATTACH, so a crashed export's `-wal` is not replayed into the new ciphertext or paired with the replacement snapshot.
- Code: a bridge `gitStatus` `path` or `branch` written as a whole-number double still matches as `"5"` (same as browse names). A `listSessions` `harness` or `branch`, and a live `sessionStatus` `branch`, written the same way still match (a blank branch still clears). Cursor Agent tools named BrowserTabList, BrowserTabNew, BrowserTabSelect, BrowserTabClose, BrowserInstall, BrowserTakeScreenshot, BrowserGetText, or BrowserGetTitle get the search, execute, or read card. CreateIssue / UpdateIssue detail lines join a `labels` or `assignees` JSON array; AddSubIssue / CreatePullRequestReview / GetGitTree / CreateBranch / ListCommits / GetJob / GetReleaseByTag / BrowserClick / SearchCode / UpdateIssue also read `sub_issue_id`, `commit_id`, `tree_sha`, `from_branch`, `author`, `check_run_id`, `release_id`, `selector`, `language`, and `state_reason`. A tool diff or location `path` written the same way still matches as `"5"`. BrowserNavigateForward, BrowserReload, and BrowserHighlight use the execute card; BrowserSearch uses the search card.
- Auto Backup / device transfer exclude move temps (`.partial` / `.ready` / `.bak`) of the live `chat_database` set and of `pre_sqlcipher` / `encrypting` / `encrypt_ok`, so a torn copy beside the live database cannot upload before the next open. When the vault already has `encrypt_ok`, leftover `pre_sqlcipher` / `encrypting` at the databases root or under `chat_db_hold` are discarded (not drained back into the vault). Torn move temps parked under hold are discarded; `.kept-*` parks stay. Keystore-wrapped API keys (OpenRouter / xAI / LAN) `commit` on save like the chat-database passphrase and Code host tokens.
- Hub Continue / Start chat deactivate Code without restoring an Ask chip mid-flip: setChatMode(RP) is async, so leaveCodeMode would briefly apply a parked Ask stage before Roleplay lands.
- A bad `gradation://pair` deep link queues via CodePairPending.offerError (clears a stale successful pending; CodeModeHost toasts once Chat is up) instead of a GlassNotice that left pending intact. CodeModeHost also skips toasting an error that a later successful offer already superseded.
- Bottom-sheet glass: after glassing the Material container, drop a duplicate GlassDrawable on the content layout (bg_bottom_sheet) so tint and outline are not stacked.
- Code away: clearing turn-done dedup on a new user prompt also drops the prefs allocation row, so after process death the next finished turn can still alert (memory-only clear was re-seeded from prefs).
- Answer-ready: a user-blocked Answers channel (or notifications off / no POST_NOTIFICATIONS) skips posting and does not remember Speak meta; shade title/text commit so a cold Speak after a kill can still flip to Stop; TTS init failure and Dismiss/Copy clear a queued Speak so Stop does not stick.
- Continue starts a new paragraph after z-image ⦇…⦈, z-binding ⦉…⦊ and curled angle ⧼…⧽ dialogue, and after Syloti Nagri ꠨꠩꠪꠫, Tibetan ༉༊༐༴, Mongolian ᠂᠄᠇, Balinese ᭜᭝᭠ and stenographic ⸼⸽. Opening ⦇⦉⧼ hug the next word like other openers (and ❴⦅⦗ do too — they were trailers only).
- Lore keys wrapped in ⦇z-image⦈ / ⦉z-binding⦊ / ⧼curled angle⧽, and keys trailed by ꠨༉᠂᭜⸼, still match.
- The rewrite dialog quote drops ⦇z-image⦈, ⦉z-binding⦊ and ⧼curled angle⧽ quotes around the first line.
- A rewrite echo wrapped in `⦇OOC：…⦈`, `⦉OOC：…⦊` or `⧼OOC：…⧽` is stripped the same way as the paren note. A `❴Scene note…❵`, `⦅Scene note…⦆` or `⦗Scene note…⦘` echo is dropped like the ASCII one.
- History Discard draft while Code covers the open Chat still deletes the parked JPEG (live fields are empty; only the park map named the file).
- First save of an unsaved Chat that Code already parked moves the staged photo onto the new id instead of rekeying empty (which evicted the JPEG).
- First save under Code (or after a rebuild that left the composer empty) moves a parked Ask caption onto the new id instead of rekeying blank. A blank field the user just cleared drops that caption. The Ask snapshot keeps the moved line, so a later mode re-emit does not paint it away. Chat shows it on a blank unedited field; Code waits until you leave (or Settings turns Code off), and does not replace a line you are still editing.
- History search still finds that caption (and caption+Photo word-order hits) after the promote. A long draft row whose match is the gap between those words stays one line and still bolds both ends.
- Code: a bridge `listSessions` model or cwd written as a whole-number double still matches as `"5"` (same as listHarnesses models / listWorkspaces). A `bridge/browse` entry name written the same way still matches. Cursor Agent tools named BrowserFileUpload, BrowserClose, BrowserNavigateBack, BrowserPdfSave, BrowserIsVisible, BrowserIsEnabled, BrowserIsChecked, BrowserGetBoundingBox, BrowserLock, BrowserUnlock, or BrowserWaitFor get the execute, read, or think card. Search / CreateIssue / UpdateIssue / SaveMergeRequest / ListMergeRequests / LinkWorkItems / SaveMergeRequestReview / MergePullRequest / UpdatePullRequestBranch / ObservabilityKeys / BrowserGetAttribute detail lines read `search`, `labels`, `assignee`, `milestone`, `state`, `parent_id`, `old_path`, `merge_method`, `expected_head_sha`, `key`, `element`, and `verdict`.
- An undecodeable or torn wallpaper in a character backup is left (not written or retried every launch), matching the portrait SOI/EOI rule. Chat import side notes still match after a rename when timestamp and message count agree. Chat/character/lore/prompt/system exports open the destination with mode "wt" so overwriting a longer file truncates instead of leaving trailing bytes.
- Hub Continue and Start chat leave Code when it was covering Chat, so Roleplay is actually visible after the mode flip (Code is not a ChatMode, so setChatMode alone left the overlay up).
- Pair activate: a successful QR / deep-link after a failed scan no longer leaves the stale error queued (and a failed rescan drops an older pairing), so CodeModeHost does not toast the old failure while also opening the host form.
- Bottom-sheet glass outlines use the real top-round path instead of a full round-rect, so elevation no longer rounds the square bottom corners.
- Answer-ready Speak waits until Text-to-speech is ready (a cold tap used to no-op), and remembers the shade title so Stop can redraw after a post without a live service. Clearing legacy sticky "Running" chrome no longer stops that service mid-utterance on every resume.
- Code away: swiping an approval alert out of the shade clears dedup and the prefs allocation so a still-pending request can re-alert (including after a later cold start); a user-blocked Code away channel no longer counts as a successful away post.
- Settings > Voice remembers Cloud, Grok or Local after the master switch is turned off, so turning Voice back on restores the same engine instead of Phone.
- Advanced settings switches that mirror LiveData (scroll progress, volume scroll, presets on chat) no longer flip twice when an observer writes `isChecked`.
- Roleplay and RP LLM-mode preference writes commit before return, so a kill right after the Settings/Style tap cannot drop the change.
- Settings home and RP Style switches use the same Grokion track as the rest of Settings.
- Code mode, theme, background style, persona-on, and voice model preference writes commit before return, so a kill right after the Settings tap cannot drop them. Code default approval and Notify when away commit the same way.
- Code settings and Persona switches use the same Grokion track as the rest of Settings. Notify when away and biometrics no longer rewrite the pref when a permission snap-back sets `isChecked`.
- Auto Backup / device transfer exclude `code_mode.xml` (hosts JSON can still hold a pairing token when a Keystore vault write failed) and `chat_database.encrypt_ok` sidecars. A leftover `encrypt_ok` at the databases root is discarded when the vault already has the marker (not `uniqueKept`). Code away-notification dedup after process death seeds from prefs-held keys, so a reconnect cannot re-alert a shade entry that survived the kill.
- Code: a bridge `listHarnesses` model id written as a whole-number double still matches as `"5"`. Digit-string `model` values go out as JSON numbers on `session/new` (same as the request id / sessionId / methodId / modeId). Cursor Agent tools named ListPullRequestReviewComments, BrowserFillForm, BrowserGetAttribute, BrowserGetInputValue, ForkRepository, LinkWorkItems, ManagePipeline, AddCommit, SaveNote, SavePipeline, SaveMergeRequestReview, ListProjectMembers, ListRepositoryTree, SearchLabels, GetSavedViewWorkItems, GetWorkItemTypes, GetMergeRequestNotes, WorkersBuildsGetBuildLogs, WorkersGetWorkerCode, ObservabilityKeys, ObservabilityValues, or MigratePagesToWorkersGuide get the search, execute, read, edit, or fetch card. GetArtifactFile / GetSavedViewWorkItems / SaveNote / SearchLabels / SaveMergeRequest / ListMergeRequests / AddBranch / SemanticSearch / GetWorkItem detail lines read `artifact_path`, `saved_view_id`, `discussion_id`, `full_path`, `milestone_id`, `author_username`, `source_branch`, `q`, and `iid`.
- Continue starts a new paragraph after medium curly ❴…❵, white paren ⦅…⦆ and black tortoise ⦗…⦘ dialogue, and after Meetei ꯫, Bamum ꛲꛴꛵꛶, Khmer ៖៙, Mongolian ᠀᠁᠆, Tibetan ༈, Balinese ᭚᭛ and Javanese ꧌꧍. Opening ❴⦅⦗ hug the next word like other openers.
- Lore keys wrapped in ❴medium curly❵ / ⦅white paren⦆ / ⦗black tortoise⦘, and keys trailed by ꯫꛲៖᠀༈᭚꧌, still match.
- The rewrite dialog quote drops ❴medium curly❵, ⦅white paren⦆ and ⦗black tortoise⦘ quotes around the first line.
- A rewrite echo wrapped in `❴OOC：…❵`, `⦅OOC：…⦆` or `⦗OOC：…⦘` is stripped the same way as the paren note. A `❪Scene note…❫`, `❬Scene note…❭` or `❲Scene note…❳` echo is dropped like the ASCII one.
- Switching Chat threads while Code is showing no longer wipes a parked Ask photo/audio/files: an empty live stage skips the park write (Code already cleared the chip into the map), and the chip stays parked until leave instead of landing on hidden live fields.
- History still finds Photo / Audio / files for the chat you left after that Code-side switch.
- A character import side log matches by exportKey when one is present, so a rename while wallpaper or portrait is still waiting no longer drops those pictures. Resume drops only the rows it finished or rejected, so a concurrent import's notes are not wiped. An undecodeable portrait (SOI/EOI only) is left rather than retried every launch. UTF-32 BE backups are rejected like UTF-32 LE.
- Code activate from an away notification, pairing, or last-tab restore parks Ask text and a live Chat stage the same way the Code tab does, so leaving Code no longer wipes a chip that never reached the park map. A view rebuild while Code is showing leaves the chip parked until leave.
- History still finds Photo / Audio / files for that parked Ask draft after an away-style Code flip.
- Continue starts a new paragraph after medium flattened ❪…❫, medium angle ❬…❭ and light tortoise ❲…❳ dialogue, and after Hanunoo ᜵᜶, Thai ๚๛, Khmer ៚, Tibetan ༑༒, Coptic ⳺⳻⳼⳽ and Mongolian ᠉. Opening ❪❬❲ hug the next word like other openers.
- Lore keys wrapped in ❪medium flattened❫ / ❬medium angle❭ / ❲light tortoise❳, and keys trailed by ᜵๚៚༑⳺᠉, still match.
- The rewrite dialog quote drops ❪medium flattened❫, ❬medium angle❭ and ❲light tortoise❳ quotes around the first line.
- A rewrite echo wrapped in `❪OOC：…❫`, `❬OOC：…❭` or `❲OOC：…❳` is stripped the same way as the paren note. A `⟦Scene note…⟧`, `⦃Scene note…⦄` or `❨Scene note…❩` echo is dropped like the ASCII one.
- Ask→Roleplay via Hub Continue / Start chat / Settings parks the Ask caption and staged audio/files before Roleplay chrome clears them (photos already parked), so coming back still has the draft and History can show it. Returning to Ask restores that thread's parked text, not only the mode snapshot.
- History Discard draft still sees a parked Photo / Audio / files when the open composer is dirty but the live stage was cleared (Code or a mode flip).
- Code: a bridge `listHarnesses` id (and `listWorkspaces` path) written as a whole-number double still matches as `"5"`. Digit-string `modeId` values go out as JSON numbers on `session/set_mode` (same as the request id / sessionId / methodId). Cursor Agent tools named GetMergeRequest, GetProject, GetWorkItem, GetArtifactFile, GetPipeline, GetRepositoryFile, GetUser, ListMergeRequests, ListPipelines, ListWorkItems, ListGroups, ListProjects, SaveMergeRequest, AcceptMergeRequest, AddBranch, SaveWorkItem, WorkersList, WorkersGetWorker, WorkersBuildsGetBuild, WorkersBuildsListBuilds, SearchCloudflareDocumentation, or QueryWorkerObservability get the fetch, search, or edit card. GetMergeRequest / GetPipeline / GetWorkItem / ListGroups / WorkersBuildsGetBuild / WorkersGetWorker detail lines read `merge_request_iid`, `pipeline_id`, `work_item_iid`, `group_id`, `buildUUID`, and `scriptName`.
- Code: digit-string `methodId` values go out as JSON numbers on `authenticate` (same as the request id / sessionId). An auth method id written as a whole-number double (`5.0` / `"5.0"`) still matches as `"5"`. Cursor Agent tools named DeleteDiscussionComment, DeleteLabel, DeletePendingPullRequestReview, GetCommitCombinedStatus, GetLabel, GetReleaseByTag, ListDiscussionCategories, ListWorkflowRunJobs, ListNamespaces, ListRepositories, GrepContents, MarkDiscussionCommentAsAnswer, UpdateDiscussionComment, UpdateLabel, CreatePullRequestComment, CreateRepository, DismissPullRequestReview, or RequestPullRequestReviewers get the delete, fetch, search, or edit card. GetReleaseByTag / ListWorkflowRunJobs / UpdateDiscussionComment / DismissPullRequestReview / ListNamespaces detail lines read `tag`, `job_id`, `comment_id`, `review_id`, `username`, and `org`.
- Continue starts a new paragraph after math white square ⟦…⟧, white curly ⦃…⦄ and flattened paren ❨…❩ dialogue, and after Sundanese ᳀᳁᳂᳃᳄᳅᳆᳇, Tai Tham ᪨᪩᪪᪫᪬᪭ and Kayah Li ꤮꤯. Opening ⟦⦃❨ hug the next word like other openers.
- Lore keys wrapped in ⟦math white square⟧ / ⦃white curly⦄ / ❨flattened paren❩, and keys trailed by ᳀᪨꤮, still match.
- The rewrite dialog quote drops ⟦math white square⟧, ⦃white curly⦄ and ❨flattened paren❩ quotes around the first line.
- A rewrite echo wrapped in `⟦OOC：…⟧`, `⦃OOC：…⦄` or `❨OOC：…❩` is stripped the same way as the paren note. A `〚Scene note…〛`, `⟪Scene note…⟫` or `⟬Scene note…⟭` echo is dropped like the ASCII one.
- Auto Backup / device transfer exclude `chat_database.pre_sqlcipher` and `encrypting` sidecars (`-wal`/`-shm`/`-journal`) as well as the main files, so a leftover beside the live database cannot upload before the next open relocates it. Code away-notification id allocation after process death treats prefs-held ids as taken, so a new alert cannot reuse a shade entry that survived the kill.
- Code: digit-string `sessionId` values go out as JSON numbers on load / prompt / cancel / set_mode / gitStatus / diff (same as the request id). Cursor Agent tools named GetIssue, UpdateIssue, ListIssues, ListPullRequests, UpdatePullRequest, GetPullRequestDiff, ListPullRequestFiles, GetRepository, SearchRepositories, GetCommit, ListCommits, ListBranches, CreateLabel, AddDiscussionComment, GetDiscussion, CreatePullRequestReview, BrowserTabs, or BrowserEvaluate get the fetch, edit, search, read, or execute card. GetIssue / UpdatePullRequest / GetCommit / GetDiscussion / GetWorkflowRun detail lines read `issue_number`, `pull_number`, `sha`, `ref`, `discussion_number`, `label`, and `run_id`.
- A late audio, file or PDF pick that finishes after an Ask→Roleplay or Code flip parks for that Ask thread (like a late gallery photo), instead of being dropped on Roleplay or wiped when leaving Code.
- History still shows Photo / Audio / files for the open Chat when the stage is only in the park map (Code cleared the live chip), so search and the draft row can bold those labels.
- Continue starts a new paragraph after white square 〚…〛, math double angle ⟪…⟫ and math white tortoise ⟬…⟭ dialogue, and after Batak ᯼᯽᯾᯿, Runic ᛫᛬᛭, Mandaic ࡞, Tifinagh ⵰, Samaritan ࠹࠾ and Javanese ꧈꧋꧞꧟. Opening 〚⟪⟬ hug the next word like other openers.
- Lore keys wrapped in 〚white square〛 / ⟪math double⟫ / ⟬math tortoise⟭, and keys trailed by ᯼᛫࡞⵰࠾꧈, still match.
- The rewrite dialog quote drops 〚white square〛, ⟪math double⟫ and ⟬math tortoise⟭ quotes around the first line.
- A rewrite echo wrapped in `〚OOC：…〛`, `⟪OOC：…⟫` or `⟬OOC：…⟭` is stripped the same way as the paren note. A `〘Scene note…〙`, `⟨Scene note…⟩` or `❰Scene note…❱` echo is dropped like the ASCII one.
- Legacy `chat_database.pre_sqlcipher` / `encrypting` copies move as a set with their `-wal`/`-shm` (or are discarded / parked under `chat_db_hold` when the vault name is taken), so Auto Backup cannot upload a leftover sidecar the rules did not list. Code away-notification id allocations commit when posted and cleared, and open-token / notif-id prefs are excluded from Auto Backup and device transfer.
- Continue starts a new paragraph after white tortoise 〘…〙, math angle ⟨…⟩ and heavy ornament ❰…❱ dialogue, and after Tibetan ༎༏༔, Meetei ꫰꫱, Saurashtra ꣎꣏, Javanese ꧉, Phags-pa ꡶꡷, Rejang ꥟ and Buginese ᨞᨟. Opening 〘⟨❰ hug the next word like other openers.
- Lore keys wrapped in 〘tortoise〙 / ⟨math angle⟩ / ❰ornament❱, and keys trailed by ༎꫰꣎꧉꡶꥟᨞, still match.
- The rewrite dialog quote drops 〘tortoise〙, ⟨math angle⟩ and ❰ornament❱ quotes around the first line.
- A rewrite echo wrapped in `〘OOC：…〙`, `⟨OOC：…⟩` or `❰OOC：…❱` is stripped the same way as the paren note. A `〈Scene note…〉`, `《Scene note…》` or `｟Scene note…｠` echo is dropped like the ASCII one.
- Code: ACP permission and Cursor ask answers send digit-string `optionId` / `questionId` / selected option ids as JSON numbers (same as the request id). A `sessionId` written as a whole-number double still matches live events as `"5"`. Cursor Agent tools named BrowserClick, BrowserType, BrowserSnapshot, BrowserWait, TakeScreenshot, KillShell, ListShells, CreateIssue, CreateBranch, MergePullRequest, AddIssueComment, GetPullRequest, GetFileContents, or SearchCode get the execute, read, think, edit, fetch, or search card. DownloadFile / UploadFile / CopyToBox / GetPullRequest detail lines read `fileId`, `draftId`, `destination_path`, `owner`, and `repo`.
- Coming back from Roleplay restores a parked Chat photo using the Ask thread id remembered on the way out (askComposer may still name the Roleplay session during the flip), so Hub Continue / tab swipe / Settings no longer leave the chip missing on a saved Chat.
- A gallery or camera pick that finishes after an Ask→Roleplay or Code flip parks the JPEG for that Ask thread instead of staging it on Roleplay (or dropping it while Code is showing).
- History draft rows clip around a gapped multi-word hit (e.g. "hello photo" across a long "hello … Photo" draft) so the bold span still lands; contiguous hits are unchanged.
- Code: ACP permission `optionId` written as a whole-number double like `5.0` / `"5.0"` still matches as `"5"`. Tool detail lines coerce stringified whole-number doubles the same way (a `"5.0"` shell_id shows as `5`). Cursor Agent tools named PatchEdit, ReadTodos, RunTerminalCommandV2, Gotodef, NotebookRead, Sleep, Wait, WakeParent, SendToUser, BrowserNavigate, OpenBrowser, or CreatePullRequest get the edit, think, execute, search, read, or fetch card. CopyToBox / UploadFile / WriteShellStdin / ListMachines detail lines read `computer_path`, `box_path`, `sourcePath`, `connection`, `chars`, and `machineId`.
- Hub Continue / Start chat (and Settings disabling Roleplay) park and restore staged Chat photos the same way a tab swipe already did, so Roleplay no longer inherits the chip and coming back still has it.
- History draft search matches query words in order with gaps (like sent-message LIKE), so "hello photo" still finds a caption of "hello there" plus a staged Photo, and the row can bold that span.
- Continue starts a new paragraph after fullwidth ＂…＂＇…＇, heavy angle ❮…❯ and white paren ｟…｠ dialogue, and after vertical ︖︕, Cham ꩝꩞꩟, Balinese ᭞᭟, Mongolian ᠅ and Lepcha ᰻᰼. Opening ❮｟ hug the next word like other openers.
- Lore keys wrapped in ＂fullwidth＂ / ❮heavy❯ / ｟white paren｠, and keys trailed by ︖꩝᭞᠅᰻, still match.
- The rewrite dialog quote drops ＂fullwidth＂, ❮heavy❯ and ｟white paren｠ quotes around the first line.
- A rewrite echo wrapped in `〈OOC：…〉`, `《OOC：…》` or `｟OOC：…｠` is stripped the same way as the paren note. A `〔Scene note…〕` or `〖Scene note…〗` echo is dropped like the ASCII one.
- Code: Cursor ask/plan `toolCallId`, question/option ids, and todo ids written as a whole-number double like `5.0` still match as `"5"`. Cursor Agent tools named ListMachines, CopyToBox, CopyFromBox, UploadFile, DownloadFile, BackgroundComposerFollowup, CloudAgent, CreateAgent, SendToAgent, CheckSubagent, GetMcpServerStatus, SearchPlugins, RecordScreen, Mcp, or DraftExternalMessage get the search, move, fetch, think, execute, or edit card. ListDir / Task / FetchPullRequest / WriteShellStdin / GenerateImage detail lines read `directory_path`, `task_description`, `pr_url`, `shell_id`, and `prompt` (numeric shell_id shows as `5`, not `5.0`).
- Orphan `-wal`/`-shm` leftovers (no main file) are discarded instead of being moved into the no-backup vault, so Room cannot mint an empty database beside them. A vault orphan no longer blocks a complete hold set from draining. Code away-notification open tokens commit to disk when issued and cleared, so a kill cannot drop the nonce the shade Intent still carries (or leave it for a replay).
- Staged Chat photos, audio and files park on the activity ViewModel, so a rotation (or leaving Chat for Code) keeps them with that thread and restores the chip when you come back. Opening History or pausing still soft-parks a live stage without clearing the composer.
- History search for a multi-word query that spans a caption and "Photo" / "Audio" / files (for example "hello photo") shows both on the row so the bold span can mark the hit. Idle rows and caption-only hits still prefer the caption alone.
- Continue starts a new paragraph after ornamental ❝…❞❛…❜ and vertical ﹁…﹂﹃…﹄ dialogue, and after Ethiopic ፧፨, Arabic ؛, Nko ߹, Ol Chiki ᱾᱿, Bamum ꛳꛷, vertical ︒ and small ﹗﹖. Opening ❝❛﹁﹃〔〖 hug the next word like other openers.
- Lore keys wrapped in ❝ornamental❞ / ﹁vertical﹂ / 〔tortoise〕〖lenticular〗, and keys trailed by ፧߹᱾꛳︒﹗, still match.
- The rewrite dialog quote drops ❝ornamental❞, ﹁vertical﹂ and 〔tortoise〕 quotes around the first line.
- A rewrite echo wrapped in `〔OOC：…〕` or `〖OOC：…〗` is stripped the same way as the paren note. A `{Scene note…}` or `｛Scene note…｝` echo is dropped like the ASCII one.
- An orphan `-wal`/`-shm` left under `chat_db_hold` no longer makes Room open that path when the vault already has the recovered main file (that used to mint an empty database beside the sidecar). Drain drops those orphans; a hold set that still has its main still wins over a stale vault copy. Code host pairing tokens and the prefs→Room session migration flag commit to disk before the next step, so a kill after scrubbing plaintext hosts (or after Room import) cannot lose the only copy.
- A staged Chat photo whose URI survives a view rebuild (rotation) puts the JPEG bytes and preview chip back, so Send still includes the picture instead of sending the caption alone or deleting the file. Opening History also mirrors that live stage into the park map without clearing the chip.
- History search for "Photo" / "Audio" / files on a draft that also has a caption shows those words on the row (and bolds them). Idle rows still prefer the caption alone.
- Continue starts a new paragraph after halfwidth ｢…｣ and 〝…〞 dialogue, and after reversed ⸮, Limbu ᥄᥅, Lisu ꓿, Vai ꘎꘏, halfwidth ｡ and small ﹒. Opening ｢〝〟 hug the next word like other openers.
- Lore keys wrapped in ｢halfwidth｣ or 〝primes〞, and keys trailed by ⸮꓿｡ and the new enders, still match.
- The rewrite dialog quote drops ｢halfwidth｣ and 〝prime〞 quotes around the first line.
- A rewrite echo wrapped in `{OOC: …}` or `｛OOC：…｝` braces is stripped the same way as the paren note. A `[Scene note…]` or `【Scene note…】` echo is dropped like the ASCII one.
- Code: turn usage token counts written as a whole-number double string like `"1200.0"` still show on the finished-turn line. Cursor Agent tools named WriteShellStdin, TodoRead, SearchSymbols, RipgrepSearch, RipgrepRawSearch, FixLints, GoToDefinition, FetchPullRequest, ApplyAgentDiff, TaskV2, CreateDiagram, ComputerUse, KnowledgeBase, ReadProject, UpdateProject, SemanticSearchFull, or ReadSemsearchFiles get the shell, think, search, edit, fetch, or read card. CallMcpTool / FetchMcpResource detail lines read `toolName` / `tool_name`, `server`, and `uri`.
- A set-aside (unreadable) chat database that cannot enter the no-backup vault is parked under `chat_db_hold` as one set with its wal/shm, instead of being renamed aside inside the vault. Cross-directory move leftovers (`.partial` / `.ready` / `.bak` / `.kept-*`) next to the live database are parked there too, so Auto Backup cannot upload them. Clearing the one-time recovery notice commits so a kill does not show it again.
- Leaving Chat for Roleplay parks a staged photo with that thread (and Roleplay no longer inherits it); coming back restores it. A staged Roleplay photo is dropped on the way back to Chat so it does not land on the wrong composer.
- History search for "Photo" / "Audio" / files still finds a chat when a caption is also waiting (the row still shows the caption).
- Continue starts a new paragraph after Swiss/German »…« and ›…‹ dialogue (the closing « ‹ no longer looks like an unfinished opener), and after CJK 《…》〈…〉 quotes. Opening » › 《 〈 hug the next word like other openers.
- Continue also starts a new paragraph after Khmer ៕, Thai ฯ and Coptic ⳹⳾.
- Lore keys wrapped in 《angle quotes》〈〉, and keys trailed by ៕ฯ⳹⳾, still match.
- The rewrite dialog quote drops 《angle》 and 〈corner〉 quotes around the first line.
- A rewrite echo wrapped in `[OOC: …]` or `【OOC：…】` brackets is stripped the same way as the paren note. A fullwidth `（Scene note…）` echo is dropped like the ASCII one.
- Deleting a chat from History also drops its parked staged photo, so the JPEG is not left until the per-thread cap evicts it. History search finds a chat whose only unsent line is "Photo" / "Audio" / files. The open composer's live text (including a cleared field) is what Discard draft and the row preview use before the next park.
- Continue starts a new paragraph after German „…“ dialogue (the closing “ no longer looks like an unfinished opener), and after fullwidth ．, interrobang ‽, Georgian ჻, Canadian Aboriginal ᙮ and Sinhala ෴. Low German „‚ hug the next word like other openers.
- Lore keys wrapped in ‘curly singles’, and keys trailed by ．‽჻᙮෴, Syriac ܀܁܂, Mongolian ᠃ or doubled !!?? marks, still match.
- The rewrite dialog quote drops German „low quotes“ and curly single quotes around the first line.
- A rewrite echo wrapped in fullwidth （OOC：…） parentheses is stripped the same way as the ASCII note.
- Code: a bridge `listSessions` `lastSeq` (and created/updated times) written as a whole number like `42.0` still resumes from that point. Git ahead/behind counts written the same way still show on Changes. Cursor Agent tools named GenerateImage, LS, ApplyPatch, AskQuestion, WriteTodos, GetDiagnostics, or MoveFile get the edit, search, think, or move card. Glob / LS / WebSearch detail lines read `glob_pattern`, `target_directory`, and `search_term`.
- Code: a toolCallId written as a whole number like `5.0` still matches later updates keyed as `5`. Cursor Agent tools named AwaitShell, TodoWrite, Task, SwitchMode, MultiEdit, CallMcpTool, GetMcpTools, CallDynamicTool, or Subagent get the think, edit, fetch, or search card. A shell command or args piece written as `5.0` (not only argv list items) shows as `5`.
- History shows "Draft: Photo" (or Audio / files) when the only thing waiting is a staged attachment, matching Discard draft for a picture with no caption. A rebuilt chat view puts that staged preview chip back. Parking past the per-thread cap deletes the oldest scene JPEGs that fell off.
- Roleplay Bubbles shrink around a pictured reply the same way a user bubble does. Recycling a text bubble onto a pictured one no longer keeps the 16dp text inset. Classic stays a flat 4dp rim.
- A torn wallpaper file (a kill mid-write) no longer counts as a picture: the Wallpaper tile stays off, and a character backup leaves the phone's copy alone instead of encoding the torn bytes.
- Continue starts a new paragraph after Armenian ՜՞, Syriac ܀܁܂, Mongolian ᠃ and doubled !!?? marks. Unfinished Tibetan and Ethiopic no longer gain a Latin space between beats.
- Lore keys wrapped in 『』 or low/angle quotes, and keys trailed by Arabic ؟۔ or other script ends, still match. Tibetan keys match inside running text the same way other unspaced scripts do.
- The rewrite dialog's quote drops «guillemets» and curly quotes around the first line, the same way markdown marks already did.
- A chat-database recovery stamp that already names a recovered file (including one parked under `chat_db_hold`) is skipped, so an unreadable copy and its passphrase archive do not collide with that recovered database. When the corrupt file cannot be moved and the recovered name walks to a later stamp, quarantine and the passphrase archive use that stamp. A character import that cannot write the wallpaper or portrait leaves those rows in the side log so the next launch retries. Saving over an unreadable preference blob (models, prompts, Code hosts, and the rest) commits the archive before replacing it.
- Code: a bridge `permissionResolved` whose `requestId` is a whole number like `9.0` still clears the matching approval card (and the away alert). Cursor Agent tools named EditFile, SearchReplace, ReadFileV2, ListDirV2, GlobFileSearch, ReadLints, Await, FetchMcpResource, or Reapply get the edit, search, think, or fetch card. A shell argv piece written as `5.0` shows as `5`.
- Replacing an idle roleplay greeting with the card's line drops rewrite versions of that opening, so a later turn does not show a version navigator for text that is gone.
- Continue starts a new paragraph after Hebrew ׃, Ethiopic ።, Greek ;, Tibetan ། and Khmer ។ sentence ends. Hebrew ׳״ trail those ends like quotes, and Spanish ¿¡ hug the next word like other openers.
- History search treats extra spaces and line breaks the same way draft rows already did: "see you" finds a sent line that has a newline between those words, and the hit stays bold. Discard draft also clears a parked photo on a chat that is not open, and offers Discard when only a picture is staged. A refused send that finished after you had already opened another chat keeps that photo on the thread you sent from.
- A later chat-database recovery no longer reuses a stamp or recovered name that is only parked under `chat_db_hold`, so Room does not open the parked copy as the fresh file, and an earlier set-aside database keeps the passphrase archived for it.
- Code: a JSON-RPC id written as a whole number like `9.0` still matches the pending call and an Allow still answers with a number. Handshake refuses protocol `2.0` the same way as `2`. Cursor Agent tools named WriteFile, DeleteFile, or RunTerminalCmd get the edit, delete, or shell card.
- Code: the home composer keeps the line you were typing (and any pictures) when the screen is rebuilt or you switch machines. Sending clears that draft. Cursor Agent tools named WebSearch, ListDir, or EditNotebook get the fetch, search, or edit card. A bridge `_meta.seq` written as a whole number like `2.0` still moves the resume cursor.
- Continue starts a new paragraph after Russian or French dialogue in «guillemets», and after Arabic ؟۔, Devanagari ।॥, Armenian ։ and Myanmar ၊။ sentence ends. A space before a closing guillemet still counts as finished.
- Rewriting an earlier roleplay reply that already had other versions starts tracking its picture once a file is known, so swiping those versions keeps the photo.
- A recovered or set-aside chat database that could not enter the no-backup vault is parked under `chat_db_hold` instead of sitting at the databases root. Auto Backup and device transfer skip that folder, so those copies are no longer uploaded. Room opens a parked recovered file by absolute path, and a later launch moves it into the vault when that name is free.
- History search still bolds the matching words when the query has extra spaces (the draft path already folded those spaces to find the chat).
- A photo or file staged on a Chat thread comes back when you open that thread again. Leaving used to clear it so it did not ride into the next chat, and deleted the JPEG.
- A chat import writes its notes before the database commit. A log for a chat that is not there is dropped, so a failed import cannot attach those notes to the next chat. A character import does the same for Memory, layout, voice, the lore pin, and the pictures. A chat backup keeps the unsent line, and Cancel's line when an edit was open. A save of a chat that already has a row writes the fork, the other reply versions, and the edit mark that belong to the transcript it stored, even when a newer save is waiting. A picture replace that is killed after the new file is finished puts that file in place even when the old picture is still there. A relaunch keeps the unsent Roleplay line.
- Code: a frame that arrives past a gap in the bridge's sequence no longer moves the resume point with it. The line stays on screen, and a reload that fails still asks for the missing ones when the link comes back. They are not added a second time. A reload that finishes does move the point forward, including when the bridge skips numbers.
- History search no longer treats the "You:" or "Draft:" label as the line that matched. A search for "you" shows the unsent draft that contains that word, instead of the last message. A draft typed on more than one line is found by the words the row shows. After a reply finishes, Edit and Delete no longer run Stop's cleanup, so a model list that is still loading is not cancelled.
- Opening a roleplay chat no longer drops a swipe version's picture. The chat copies a cache, Downloads, or half-written file into app storage, then the version list was still pointing at the old link, so the reply was saved without the JPEG and the new file was left unused. Other versions of that reply are copied the same way. Swiping, or starting another version, copies the picture before the bubble drops the bytes.
- A chat save that is overtaken by a newer one still writes the facts, the other branch and the other reply versions for the transcript it already stored. A kill before the newer save runs no longer leaves that transcript without them. Copying a database file no longer writes the bytes under the real name, so a kill cannot leave a half-written file that the next launch treats as the database. A picture replace that is killed is put back from the finished side file. A chat import writes its pins, fact notes and branches before it commits them, and the next launch finishes that step. A character backup's Memory, layout, voice and lore pin are one commit.
- Swiping to another version of a roleplay reply shows the picture that version had. Regenerating used to keep only the words, so Stop brought the old line back without its picture, and the new line kept the previous picture. A version with no picture no longer shows the other one's. An older chat that never stored those links still keeps the picture already on the reply.
- Sending a message while another chat is still opening keeps that line in the composer. It used to clear the field, close an open edit, and then add the line to whichever chat finished loading. Stop could not cancel it, because the turn had not started.
- Code: a Cursor question or plan no longer keeps its JSON-RPC id after the next permission uses that same id. Allow on the permission is sent as a permission answer, so the agent is not left waiting on a question that already finished. A send that failed can still retry the question until then. Closing the session drops the question too.
- Code: a tool that streams its log one piece at a time keeps those pieces, and a later full update still replaces them. A diff sent as a list of file changes plus a git patch is shown, including a new file that has no patch text. An approval that names the command, or only a title, shows that instead of a generic prompt. Cursor Agent's agent mode moves the approval pill to full auto. A question with one choice is answered from the card; a question this screen cannot ask is skipped so the turn is not left waiting. A plan asks you to accept or reject it, and the to-do list updates in place.
- A fresh chat database no longer keeps the previous chat's notes when the old file could not be moved aside, or when the process died before those notes were renamed. The next launch tries again, and it does not clear that job if the rename did not commit. Replacing a picture no longer truncates the one already there when the new file cannot take its name. An unsent Roleplay line is still there after the app is killed.
- Editing or deleting a message, or regenerating an earlier reply, while a reply is still arriving stops that reply first. The rest of it no longer lands on the shortened transcript, and the save from Stop no longer writes the transcript from before the cut. Tapping Stop and then Send no longer lets the stopped turn remove the new reply's placeholder.
- Editing a roleplay message that has a photo puts that photo back in the composer. The save that cuts the turn out no longer deletes the picture while it is being read.
- Editing a message you sent no longer drops the rest of that turn with no way back. The composer says Editing, and Cancel puts the message back, along with the line that was already in the field. The replacement still sends as before, and the older turn stays available from the reply's version switch. That unsent edit stays with the chat when it is saved for the first time after you have already left.
- The other branch of a chat, and the other versions of a roleplay reply, stay with that chat when it is saved for the first time and you have already left. Switching away before that save finishes reopens that chat, not the one before it. A chat backup keeps both, including a fork this version cannot read yet. An older backup still imports, and it does not keep a fork that belonged to an id now reused.
- A character portrait, a persona portrait, and a wallpaper are replaced only after the new JPEG is finished. A file that is not a finished picture leaves the one already there. Moving a chat database aside no longer leaves a half-written copy that the next launch treats as the real file.
- A character's layout, read-aloud voice, and lorebook pin are written through before the app continues, as are which chat a mode will reopen and the notes cleared when a character is deleted.
- Code: a tool the agent calls Bash, grep, or write uses the shell, search, or edit card, so the icon, the line under the title, and which end of a long log is kept all match the tool. A status of error, done, or cancelled ends the spinner; cancelled is not drawn as a failure. A log sent as stderr, as a list of lines, or as one content block is shown. The session list names the tool while it is still running, and a preview keeps snake_case.
- Swiping to another version of a roleplay reply keeps a picture that reply already has, including the copy stored in the message. Opening that chat again no longer drops the copy either. A detailed photo that does not fit the size cap is scaled down and sent, instead of being reported as a format the app cannot read. A character wallpaper (and a background photo) is read once from the picker, stored upright, and capped so a large backup is not decoded at full size behind the chat.
- Leaving a chat, or opening another, no longer drops a reply that was still being saved. Two saves of the same chat cannot land out of order, so an earlier snapshot cannot wipe a later one. A chat fork and the other versions of a roleplay reply are written through before the app continues.
- A picture file that was only half written is not treated as the photo. The copy stored in the message is put back, and a new picture is written to a side file and synced before it takes the real name.
- Sending a photo in Roleplay keeps the file on that message, so the picture can be opened from the bubble. The composer used to clear the link before the message was saved. A photo you remove, or leave behind by starting another chat, is deleted. Deleting a chat deletes its pictures too, unless another chat still uses the same file. A character or persona portrait taken sideways is stored upright.
- Tapping a link in a message you sent opens the link and leaves the action row as it was. Press and hold that message to copy all of it, including when Show more has folded it. Two copies of the same message open their own action rows.
- Code: a shell or search log no longer shows terminal color codes, and a progress line that rewrites itself shows the last line. A file the agent opened still keeps those bytes. A tool reported as `in-progress` or `running` shows as running, and a plan step spelled that way does too. On Changes, a renamed file opens and copies the new path, and a path git quoted (a space, or a name outside ASCII) is the real path.
- A character backup keeps that character's wallpaper. An older backup still imports, and it does not remove a wallpaper it does not mention. Clearing the wallpaper in the backup removes it on the next phone.
- A photo in a chat is kept in the app. Opening the chat again restores the picture when the old link is gone, including a picture the character generated, and that picture is not sent back to the model. Continue, a rewrite, and a failed Continue leave the picture on the reply.
- A fresh chat database, started because the old one could not be opened, no longer gives the next chat or character the previous one's pin, fact notes, unsent line, Memory, layout, voice, or portrait. Those are kept under a new name beside the database that was set aside. Fact notes, Memory, and pins are written through before the app continues, and deleting a chat drops its pin with the rest of its notes.
- Copy and read-aloud on a reply no longer include the language name and the padding that size a code card. Those characters are painted invisibly so the card has a header row. A long-press copy of the markdown no longer starts with a blank line. A long message you sent no longer folds down to the first word when the rest is one long token, and a trailing newline no longer adds an ellipsis that hides nothing.
- Code: a deleted line that starts with `-- ` (a SQL or Lua comment, a flag on its own line) stays in the diff, and an added line that starts with `++ ` stays too. The next file's header is still left out. A shell call sent as a program plus an argument list shows the whole line, including when those arguments arrive as text instead of an object. A tool update that never had a first call still becomes a card, and a later kind sets the icon. A Deny option sent as `reject` or labelled Deny is not treated as Allow.
- A roleplay photo stays in the chat after it is saved and opened again, including a picture the character generated. Later turns still show the two newest pictures and keep a short note for the older ones, instead of sending every photo again. A photo with no caption no longer keeps a blank text part beside the scene line.
- Continue leaves the reply in place when the request fails, including an HTTP error, and says so in a notice. Other versions of that reply stay until the continuation actually arrives. A rewrite that echoes its own note, including the several-line one this app sends, is shown as the story.
- Turning off "Keep Facts up to date" no longer hides Facts you already wrote, from the character or from lore. Fact notes use the same name for you as the rest of the prompt. A captioned photo still counts as a photo in that summary. The character panel shows names instead of `{{char}}` and `{{user}}`, and the Lore tile lights when a book is actually in use.
- A backup or a phone-to-phone transfer no longer uploads the plaintext copy of the chat database, or a database the app had to set aside. Those copies live in a directory the system does not back up, and the backup rules also name the old plaintext file in case it is still in place. Code mode host tokens are excluded the same way. A recovered database is opened from that directory, and a preference that names any other file is ignored.
- A photo that lives only in the message, with no file link, shows in the bubble. That branch did not compile.
- History search keeps the matching words on the one line under the title, including a hit late in the message, a line with no spaces, and a word that contains an underscore. A slice of a photo's data is still not shown as a line.
- A sent or generated picture is only tappable once the file has loaded. A missing file shows the picture stored in the message, and drops the frame when that is gone too, instead of leaving an empty box that cannot open. Edit says so when the photo cannot be put back in the composer.
- The full-screen composer keeps the space for the keyboard. It used to clear that inset, so the buttons sat under the keys. Typing past the sixth line keeps the caret in view.
- Code: a long file read still keeps the start when the later update does not repeat the tool kind, and a long shell log still keeps the end. A tool update that only has a path fills an empty card and does not replace a command. Output sent as `rawOutput` is shown. A new file sent as a unified patch is marked new. A rename scored `R100` shows R. A file or terminal request the phone cannot run is answered again if the send queue was full, so the agent is not left waiting. "Unknown method" on `authenticate` still connects. The session menu can rename. The line you were typing in a session is there when you come back. Changes and a file diff can be tapped to try again when the load fails.
- History search matches the words in a chat, not a photo's data or the JSON around a message. Searching "image", "url", or "text" no longer lists every picture. A space before or after the search is ignored. A long message you sent says Show more under the bubble. A send that is refused leaves the line in the composer.
- A crash while an old chat database was being encrypted no longer deletes the plaintext copy on the next launch. That copy is put back when the encrypted file is missing or empty, and it is removed only after the encrypted file has opened. If the passphrase for a set-aside database cannot be archived, the active key is kept. A chat backup's pins and fact notes are written in one commit. An unreadable composer-draft blob is archived instead of being replaced. A short read of a long message fails the read instead of skipping a gap. Saving a chat, or opening History, no longer closes the app when the database throws.
- A roleplay photo with no caption keeps a scene line on every request, so a later reply, Continue, or a rewrite is not rejected for an image with no words. Rewriting that turn no longer attaches the picture to the next message. Continue keeps a picture already on the reply. Edit puts the photo back in the composer, including when it had no caption, and the bubble still shows it after the file link is gone.
- `{{char}}` and `{{user}}` keep a name that contains `$` or `\`, so a greeting rewrite still matches the card line. Lore and fact notes expand those placeholders in the scene, not only on the card.
- Code: the handshake finishes `authenticate` when the bridge asks for an agent login, and it stops (instead of retrying) when the bridge speaks a newer protocol or only offers a terminal login. A file-read or terminal request forwarded to the phone is answered with an error so the agent is not left waiting. A prompt is still sent when the bridge does not implement `authenticate`.
- Code: a later tool update no longer replaces the command on a shell card with the working folder. A long file read keeps the start of the file; a long shell log still keeps the end. A diff sent as a unified patch is shown, and a Windows line ending no longer makes every line look changed.
- Code: Ask to commit on one file commits that file. Ask to revert that file asks first.
- Opening Chat again uses that thread's unsent text. The single line remembered from the last switch into Roleplay no longer comes back in its place, including after the message was sent.
- A chat message whose text or image part is not a string (null, a number, or an object) no longer crashes when the chat is opened, when History or Roleplay shows the last line, or when the chat is exported to PDF or HTML. That part is skipped.
- A single stream event larger than 4 MB is refused instead of being read until the app runs out of memory. A reply that runs past a million and a half characters stops, keeps what arrived, and says it was cut off. Tool-call arguments stop growing past 2 MB, and a generated audio clip past 12 MB is not decoded.
- Voice input stops after five minutes and keeps what was heard. A recording past 8 MB is dropped instead of being loaded whole and uploaded. A late result from the phone's recognizer, after dictation already finished, no longer pastes the same phrase again.
- Roleplay lore keys written as `{{char}}` or `{{user}}` match the people in the chat, and one entry can still pull the next when the link is a name. A key with a trailing period, or a header typed with fullwidth brackets, still matches. Speech style is scanned with the personality and the scenario, so a key that only appears in how they talk is not lost once the chat is long.
- Continue starts a new paragraph after a sentence that ends in 。！？, including a line that closes on 「」, and it does not insert a space into Japanese, Chinese or Korean. A scene reminder still counts when there is a space before the colon, or a fullwidth colon.
- A reply that arrives wrapped in a plain code fence is shown as the story. A scene note echoed at the start of a reply is left out when the story continues after it. Character lists show the names in a tagline instead of `{{char}}` or `{{user}}`.
- Code: Ask to revert all counts only files git already tracks, and stays off when every change is untracked. Opening an untracked file no longer offers to restore it to the last commit. A diff no longer shows rename headers, file-mode lines, or a binary notice as changed lines, and a line of code that starts with dashes or pluses is kept. A Windows line ending no longer drops the hunk.
- A chat backup keeps the date, whether the chat was pinned, and that chat's fact notes. A character backup keeps Memory, the chat layout, the read-aloud voice, and which lorebook is pinned to them. If that lorebook is not on the phone yet, the pin is applied when the lorebook is imported. An older backup still imports, and it does not wipe notes it does not contain.
- Chat export writes one message at a time, so one long chat is not copied into a second string. Loading every chat with its messages no longer reads each message in one piece, so a long attachment cannot crash that path.
- The chat database passphrase is written through before the app continues, so a kill at that moment does not lose the only key. A Code setting or a saved host token stored as the wrong type no longer crashes Code mode, and copying the last reply from a notification no longer crashes when that preference has the wrong type.
- History opens on the chat you have open, and submitting a search closes the keyboard so the results are not left under it. A new search starts at the top of the list.
- A long message you sent that is many short lines folds after three lines. It used to cut on length only, so a stack of short lines stayed open well past that.
- A long always-on lore block no longer pushes out the entry that matched the scene, and a short always-on block still stays beside a long match. A long Memory note no longer keeps this chat's facts from matching keys. Fact notes no longer save a line that only repeats the Memory note once {{char}} and {{user}} are names, or once a dash or a final period is ignored. A scene reminder works in any capitalization. Lore keys separated with a bar, or wrapped in quotes, still match.
- Code: a shell tool call shows the command when the agent sent one, including a command passed as a list of arguments, instead of only the working folder. A file location includes its line. A link or an embedded file in an agent message is shown instead of being dropped, and a picture-only prompt from another device still appears. A diff whose old or new text is not a string no longer drops the whole tool call.
- A chat message longer than Android's cursor window (a large attached file) no longer crashes when the chat is opened, when History or Roleplay asks for the last line, or when chats are exported. The text is read in slices. Opening a chat that the database cannot return shows a notice instead of closing the app.
- A preference stored as the wrong type (a restored backup, or a key whose type changed) no longer crash-loops launch. The value is left in place and the default is used. A chat fork this version cannot read is left in place instead of being deleted. Saving the tool list archives an unreadable copy first, the same way the model list does.
- If the chat database cannot be opened and the file cannot be moved aside, the app starts a new database file instead of crash-looping on the same one. The old file stays where it is.
- A backup saved by Notepad as Unicode (UTF-16) imports. Prompt and system-message files from a newer version, with fields this version does not know, import instead of being rejected.
- History search stays above the keyboard. The drawer takes the same insets as the chat, so the field is not covered.
- A long message's Show more control sits under the bubble. It used to live in the action row, which stays hidden until you tap the bubble, and the cutoff said "continued" in English.
- Code: coming back to the app reconnects at once instead of waiting out a backoff that started while it was away, and a reconnect whose socket open fails keeps trying. ACP `session_info_update` sets the session title. A name you edited stays; one you did not picks up the agent's title on the next session list. Tool output includes text sent as an embedded file resource, and a terminal snapshot when the bridge includes one. Away-notification taps no longer share one target across sessions, and an earlier alert for the same session still opens after a later one is posted.
- Saving a character or importing one no longer treats a trailing space or newline on the greeting as a new greeting, so a rewrite of that opening stays. Fact notes skip the Continue prompt and a scene note that was echoed on its own; a reply that continues after the note still counts.
- A taller composer (another line, or a staged photo) no longer yanks a short thread up by the full growth. A message the composer would cover moves by only the covered amount, and one resting on the composer rides back down when the composer shrinks.
- History dates no longer treat early January and late December as the same week. Today and yesterday show the time, the rest of the week shows the weekday, and older chats show the date.
- A roleplay greeting you rewrote stays when you save the character, switch persona, or import, unless the greeting text on the card itself changed. A bubble that is still the card's line still follows a rename or a new persona name.
- Rewriting the greeting, or any earlier reply, holds the turn: Send becomes Stop, and that tap cancels the rewrite instead of dropping it with no notice.
- Lore matching starts on a word when a long scene is cut down to the recent part, so a key sliced by that cut is not missed or half-matched.
- Fact notes drop a reasoning model's unfinished scratchpad, and lines copied from the Memory note you wrote. A photo sent with no caption still counts as a beat in that summary.
- Code: Allow or Deny tapped while the bridge is down is kept and sent once the link is back, including after the away-notification wait gives up. A second tap changes that queued choice instead of answering twice. Stop, removing the session, or the turn ending drops it.
- A corrupt model list or system-message list is no longer replaced with the defaults at launch, and the Maverick cleanup waits until the model list can be read. The next save of an unreadable preference (models, prompts, system messages, presets, personas, the deleted-character remap, the cached model catalog) keeps the old text under `key.unreadable` before writing. A corrupt Code host list is left in place the same way: token migration retries later, and a save archives the old text first.
- Chat, prompt and system-message imports stop reading at 5 MB, and roleplay backups (they embed portraits) at 16 MB, instead of loading the whole file and checking afterwards. A UTF-8 BOM no longer makes a valid chat backup look broken. Exports sync the finished cache file and report failure if the copy stops short. Roleplay character and lore imports run in one transaction, so a failure part-way leaves the library as it was. A lorebook marked active no longer leaves the previous book active as well, and a portrait that cannot be decoded is skipped instead of failing the import.
- Code: a bridge event that arrives twice (or a resume that includes the last seq already on screen) no longer appends that chunk again. A prompt queued while reconnecting is sent once even if the resume flush and the retry overlap. A prompt with surrounding spaces matches the bridge's echo, so it stays one bubble. A second connect while the socket is still opening no longer leaves a spare WebSocket, and a send after the socket was retired reports failure so the prompt can be queued again.
- Roleplay Rewrite on the greeting no longer asks you to send a message first. Rewriting the latest reply still shows the model that reply when chat memory is short, and rewriting an earlier reply still shows it the photo. The demo model can rewrite too.
- A photo on its own can be sent: the button no longer stays on Continue, or stays disabled, while a picture is staged, and a second tap cannot send it twice. Camera photos are turned upright before they go to the model. A saved photo still shows after the gallery link expires.
- A photo staged in the composer with no caption left Send disabled, so the picture could not be sent. Send now wakes as soon as a photo or audio clip is attached, and New chat drops the staged attachment instead of carrying it into the next thread.
- Opening the keyboard on a short thread no longer yanks the messages up by the full keyboard height. A message already resting on the composer still rides with it; one the keyboard would cover moves by only the covered amount.
- Composer photos keep their shape (a portrait stays tall, including the camera's rotation flag) instead of being cropped to a square, and the remove control is a 44dp target. Sent and generated pictures use the same rounded cap, with a caption lined up to the text inset.
- The character panel sheet dropped by its own height after coming back from a page.
- The Persona tile no longer washes the portrait out, and the panel header's avatar is no longer squashed.
- The pause between the last words of a reply and its tools appearing: the swap to the final render used to wait out the 340 ms word fade; it now happens at once. The finished reply's markdown and text layout are also prepared off the main thread, and a late update of the same reply no longer throws that work away.
- The avatar picker decodes large photos at a sensible size and releases its bitmaps; Memory and Voice drafts survive rotation; Roleplay pages leave room for the keyboard; the wallpaper and the panel avatar decode off the main thread.
- The empty Roleplay home points at the characters button.
- The AI grammar fix popup can be cancelled with a 48dp button, and says so when the text is read-only instead of closing silently.
- Streaming: a connection that drops mid-reply no longer passes for a finished reply. The reader reports the failure, a stream that ends without `[DONE]` or a finish reason keeps what arrived and says it may be incomplete, and a reply that never started shows the error. The request timeout no longer caps a long stream (read timeout only), timeouts show the configured minutes instead of "90 seconds", and changing the timeout in Settings applies to the next request without a restart.
- Tool calls: every `tool_call_id` gets a reply even when the model repeats a call, and Stop mid-run stubs the unanswered ones, so the next send no longer fails with 400 on strict providers. Streamed tool calls with a missing index, a reused index 0 or a late id assemble correctly. Tool follow-ups and file tools run off the main thread; Stop halts the remaining tools.
- Requests carry only role, content and tool fields; local bookkeeping (image URIs, reasoning, thinking, tools-used flags) no longer leaves the phone.
- Deleting a chat clears its fork stash, facts and swipe data; deleting a character clears its memory, layout, voice, lorebook pin and wallpaper. Imports are one transaction, accept newer backups with unknown fields, and report a bad file separately from a database error.
- The auto-memory note goes to the chat it was written for even if you switched chats meanwhile; the job is cancelled on a session change. A character's Additional instruction expands `{{char}}` and `{{user}}`. The reply cleaner no longer deletes story lines that start with "Instructions" or "No limits". Lorebook keys in Chinese, Japanese, Korean, Thai and similar scripts now match inside running text, and key regexes compile once.
- A corrupt cached model list or prompt no longer crash-loops the app at launch. A failed API key save keeps the old key. The rotation of the app no longer re-sends a shared or auto-send prompt or re-applies the assistant preset. One unrecognised fingerprint no longer closes the app, and a restore after process death still asks for it.
- The character editor no longer creates a duplicate when the avatar save fails on a new character, and keeps in-progress edits and the picked photo across rotation; a discarded persona photo is deleted.
- PDF export paginates onto A4 pages instead of one page as tall as the chat, closes its streams, and no longer crashes on an image that fails to decode. Exported HTML escapes raw HTML and links. Read-file tools cap their output at 200 KB, file edits keep the original if the write fails, History search treats `%` and `_` literally.
- Code: history no longer opens blank after an app restart (the resume cursor was ahead of an empty local transcript); whole-file "replace" diffs on big files with a small edit; the hub no longer opens the Code database on every app start or chat open; decoding, diffs and markdown parsing run off the main thread; "Stopped" showed twice.
- Performance: the liquid mark no longer does a GPU readback on a timer (only when a snapshot needs it); backgrounds and the mark stop their frame loop when animations are off or on power save; shaders are built once, not per frame; the ambient photo key, hour and time zone are cached; the static field renders on a worker thread; dialogs frost the screen once, not three times; streaming polishes only the open tail of the markdown, not the whole reply per frame; history search cancels stale queries; assistant rows bind less. Imported background photos respect EXIF orientation.
- Accessibility: 44dp targets on the Thinking header, the extended top bar, the back and home buttons, photo buttons, Code approval buttons, thought and tool rows; content descriptions on preview, generated and user images and on the Roleplay row menu; tool rows expose Running / Failed / Expanded; diff cards read their path and counts; 13sp minimum on Code subtitles, diff counts and mono text.
- Chat ids come from the database: two quick saves can no longer get the same id, a chat deleted while it was saving is not brought back, and a deleted chat's id is never reused. If the encrypted chat database can't be opened (Keystore wiped, corrupt file), it is moved aside, never deleted, and a fresh one starts with a one-time notice instead of a crash loop; the database also opens off the main thread now. Room schemas are exported and every migration is tested. The passphrase for a set-aside database is kept, a half-finished encrypt can fall back to the plaintext copy, and a second recovery no longer overwrites the first. A corrupt Code session list is left in place instead of being marked migrated and deleted. Chat export writes one chat at a time, so a long history no longer has to sit in one string.

- "Trust self-signed certificates" for local servers pins the server's certificate on first use instead of trusting any certificate; a changed certificate is refused with a clear message, and turning the setting off and on accepts the new one.
- Settings sections inflate only the one you open. Read-aloud and the Roleplay voice page share one speech engine. Exports (HTML, print, EPUB) use neutral grays. Chat and Code composers line up on the 4dp grid (40dp buttons, 16dp margins, 16/12 bubbles), and the unused Michroma font is gone (History's wordmark is Iceland).
- Dead code: the hidden per-message export buttons and their PDF/HTML/PNG chain, `MarkdownViewerFragment`, `StreamCursorSpan`, `AppToast`, the invisible model-chip colour animator and about 160 unused resources are gone.

## 3.0.0 — liquid glass redesign

First release under the GradatiON id (`io.github.warexpor.gradation`), signed with the release key. Everything below is new since 2.1.134-rp; see docs/RELEASING.md for how later updates stay installable.

### Fixed
- Swiping between pages in quick succession no longer breaks: a slide still settling is landed before the next drag starts, so pages, the underline and the mode stay in step.
- The History header lines up with the chat top bar.
- Chat names in History render inline markdown (bold, italic, strikethrough, code).
- Roleplay opens a character's chat with a slide over the characters list, and Continue starts a new paragraph.
- Autosave no longer stalls the screen when a reply finishes streaming.
- Jump-to-latest sits on the left, just above the composer (above the fade, not under it).
- Mode tabs: inactive labels use the quieter tertiary gray and lighten toward ink as you swipe.
- A reply's ⋮ menu grows out of the dots as a compact card, not a full-width strip above the input.

### Changed
- Chat: a round jump-to-latest button rises above the composer once you've scrolled up; each mode keeps its scroll spot when you swipe away and back; the dim under the top bar is always on and a little denser. Reply tools fade in left to right, and the fold-long-answers and share buttons are gone. Code blocks are flat cards with a bare copy icon. Your own messages open their tools on the first tap, and the tools ease open and closed.
- Models: new installs start with just Demo and the free OpenRouter router (older installs drop the untouched seed models, keeping edits and the one in use). A Local filter and a new local-network mark; the list fades under the filter row instead of cutting off.
- Models: one clean list with each maker's mark (OpenAI, Claude, Gemini, Grok, DeepSeek, Llama, Qwen and 20+ more; a letter for the rest), the name, and one quiet line such as "Anthropic · Vision · Free". A single chip row sorts and filters. The plus opens the OpenRouter catalog, your local network, or add-by-id. Long-press a model to edit, open its page, or remove it. The OpenRouter and local-network screens share the same look, and the composer's model popover and Controls panel show the marks too.
- Reasoning no longer depends on a per-model "reasoning" flag: any cloud model can be asked (OpenRouter ignores it where it doesn't apply). Only local models keep a "Thinks" switch, because their servers reject the parameter.
- Controls panel: a Thoughts tile shows or hides thinking above replies. Web search left the chat chrome and was switched off once; presets can still turn it on.
- Thinking stays folded while a reply streams, glints in gently, and opens on tap without a highlight. The finished reply's tools ease in left to right, and the view glides along with the streaming text instead of jumping a line at a time.
- Roleplay no longer has a response-language setting. Replies follow the conversation instead of being forced into English, Russian, or Chinese.
- Install id is `io.github.warexpor.gradation` (dev builds: `io.github.warexpor.gradation.dev`). Older `grokion` installs stay as they are and do not update into this id. Dev builds stay signed with the committed GradatiON Dev key.
- Typography: Plus Jakarta Sans across the app, an Iceland GradatiON wordmark in History, and larger semibold labels on big buttons and dialog actions. Back, chevron and close icons are redrawn as rounded iOS-style strokes.
- Glass everywhere: every dialog, bottom sheet, context menu, dropdown and settings subpage now uses the liquid-glass style; toolbar back and action buttons are glass capsules that spring under the finger.
- Palette one step dimmer and darker (dark base #111111, light a dimmed off-white, #E8E8E8), still strictly neutral.
- Liquid glass: top bar controls are now floating glass buttons and chips over a soft scroll-edge fade, and glass bends content at its rim with a specular edge (Android 13+). Touching glass springs it up, leans it toward your finger and lights it from the touch point. Battery saver and low-RAM devices get a solid frosted fill instead of live blur.
- Glass: the transcript scrolls beneath a live-blurred top bar and a floating glass composer; the Controls panel is the same material, and dialogs and sheets frost the screen behind them.
- Palette moves off pure black to a charcoal base (and a soft paper base in light) with a finer stepped gray ramp and lower overall contrast.
- Streaming reveals text with a soft per-word fade at the live edge, a breathing dot marks it, and the view follows the reply unless you scroll away. "Thinking" is a shimmering label.
- Markdown: code blocks are rounded cards with a language header (tap to copy), inline code is a pill, headings are calmer and paragraph spacing tighter.
- Motion: the Controls panel rises on a spring with its rows cascading in, sent messages rise from the composer, presses sink and settle, dialogs enter like iOS alerts, and screens push in from the edge over a parallaxed, dimmed previous screen. Reasoning expands and collapses smoothly.
- Whole UI reworked to a minimal, iOS-style monochrome look: system grays, soft gradients on the composer, bubbles and chips, no gold accent and no glow. Light and dark now share one theme definition.
- A proper type scale in Plus Jakarta Sans; titles use semibold, and the History wordmark is set in Iceland.
- Pressing things shows a soft gray wash instead of the burst animation.
- Settings, RP hub and forms use grouped inset cards with section headers; dialogs are rounded cards with pill buttons; text fields are filled; nav bars are flush.
- Chat empty state is a small mark with a greeting instead of the big watermark.

### Fixed
- Chats with code blocks crashed on Android 12 and 13 (an Android 14-only text call).
- Toolbar action icons were invisible in light mode (import, export, search, save).
- Attach menu could measure wider than the screen.
- Timezone tool output used locale digits (e.g. Arabic numerals) instead of ASCII.
- Presets and Prompt Library now show an empty state; "Clear chat?" reads "Clear chat when applying".
- Reasoning effort is a proper segmented control; Help and README link the current repo.
- Streaming no longer hops to the UI thread once per token (janky on fast local models); updates are coalesced to one per frame and long replies no longer re-parse all markdown every frame.
- A partial reply could reappear after Stop or an error; tool calls and images arriving with the first text chunk were dropped; LAN citations were listed twice.
- Reasoning, web search, stream, tools, presets, system message, fonts and chat export could only be reached by long-pressing Send on an empty chat. A Controls button next to + now opens them any time.
- Those toggles showed no on/off state; tiles now fill when a feature is on.
- Opening the full-screen composer stranded New chat, System, Paste and Clear outside the Controls panel until restart.
- Attached text files had no visible indicator; the + menu now shows a "files attached" row to review or remove them.
- The model name could stay stuck red or gray after two replies finished close together.
- Picked images are read off the main thread with a size cap.
- Spell-check popup, Help and the HTML viewer used hard-coded dark colors; they follow the theme now.

## Upstream sync: oxproxion 2.1.103 to 2.2.5 (merged before 3.0.0)

### Added
- Nativ LAN server type (OpenAI-compatible `/v1/models`, Mac-only inference server).
- Brave News tool (`brave_news`, `/res/v1/news/search`); Brave Web Search now uses the LLM Context API (pre-extracted page content, up to 50 results, token budget, relevance threshold).

### Changed
- Syntax highlighting grammars are vendored (`io.noties.prism4j.languages`), so kapt and `prism4j-bundler` are gone from the build.
- OkHttp clients: HTTP/2 ping every 56s, retry on connection failure, and a bounded LAN connection pool (fewer "unexpected end of stream" drops on keep-alive LAN servers).
- Dependency bumps: OkHttp 5.5.0, Room 2.8.5, Ktor 3.6.0, org.json 20260814, ConstraintLayout 2.2.2.

### Fixed
- Multi-page PDF attach: the page picker no longer renders from an already-closed file descriptor; Cancel/back release the renderer.
- PDF pages render on a background chosen from the ink (dark-themed PDFs no longer come out blank) at 2x scale.

## 2.1.134-rp — 2026-08-22

### Fixed
- History shows Ask vs RP under the wordmark and uses the GradatiON mark on empty lists; share subjects say GradatiON.
- Ask fork navigator stays hidden in RP (swipe bar owns alternates); RP stream toggle tints gold when on.
- Spell-check restores the attach + icon (not the old overflow dots); Instruct/persona-name dialogs keep OK above the keyboard.
- Prompt/preset/OpenRouter library copy and a11y live in string resources; Help documents Ask overflow as long-press Send.
- Returning from the RP hub no longer overwrites the RP composer hint with Ask copy; empty RP hints you to pick a character; LLM replies show a GradatiON speaker header.
- Last-reply copy / instruct / regen stay above the composer (extra list padding); Instruct dialog uses app OK/Cancel and toasts like regenerate.
- Removed leftover client-side RP content filter (ported from another project; not needed here). Provider `finish_reason: content_filter` is still shown as an error bubble.
- Character / lore / persona-preset deletes use the same centered confirm dialog as the rest of RP.
- Persona and lorebook editors confirm before discarding unsaved edits.
- Regenerate / swipe › shows a short “Generating another reply…” toast.
- SSE streaming finalizes on OpenAI-style `[DONE]` (keep-alive LAN servers no longer leave Stop stuck).
- Instruct action uses `ic_editnote` so it no longer looks identical to Edit.
- Character library shows an Active badge; LLM mode dims Third-person / Show thoughts (prompt no-ops).
- Character editor confirms before discarding unsaved edits.
- Help no longer documents dead per-message PDF/MD/HTML/PNG export icons.
- Autosend no longer races the composer (shared text is applied before Send is clicked).
- Ask↔RP parks/restores composer text per mode; entering RP toasts when staged attachments are dropped; character/lore Save failures toast; first lorebook create/import reminds when “Use active lorebook” is off.
- Leaving RP re-applies Ask attach/gen chrome from the active model’s capabilities (no stale enabled attach on non-vision models).
- Character RP memory trim always keeps the latest turn at tiny Chat-memory budgets.
- Regen Stop after an error reply restores that placeholder (restore-only seed, not swipe alts).
- New chat / LLM-off replace parked drafts (only ephemeral mismatch preserves keepDraftId); error RP replies still allow regen/instruct; LLM memory trim no longer pins the first reply as a greeting; non-stream finalize includes citations in swipe seed; persona commits only via Save (preset browse no longer silently overwrites on Back).
- Start chat always replaces a parked RP draft (and confirms even from empty RP); third-person prompt gated out of LLM mode; LLM composer hint; stream notifications reuse the finalized bubble.
- LLM-off confirms before wiping an open RP thread; Start chat confirms from Ask when a parked RP draft exists.
- Character/LLM RP history titles upgrade from the bare greeting name to include the first user snippet once a user turn exists (without overwriting a renamed title).
- Character RP history titles include a first-user snippet so threads don’t all share one name; Instruct OK keeps the dialog open on soft-fail; avatar pick failure toasts and stays on the editor instead of exporting without an avatar.
- RP first autosave skips the unused title LLM call; LLM history titles use the first user snippet when present; async PDF/audio/file staging re-checks RP before attach; persona preset save toasts when the oldest of 12 is dropped.
- Lore import only auto-activates when the library was empty (won’t undo intentional “no active”); clear-avatar deletes the file only after a successful DB save; late Ask picker results are discarded in RP instead of re-staging attachments.
- Deleting the active lorebook no longer silently activates another (toast prompts Set active); character Save rejects unparseable example text instead of wiping stored examples; Help notes Attach is disabled in RP.
- New chat / LLM-off no longer autosaves over a parked keepDraftId and clears the composer; RP file-attach is disabled like image attach (picker blocked + chrome dimmed).
- Regen Stop restores the swipe variant you were viewing (stash keeps mid-list index); Start chat skips greeting autosave when a parked draft must be preserved and clears leftover composer text.
- clearOpenTranscript can preserve parked drafts; startRp* no longer wipe keepDraftId (LLM re-enable reloads a parked LLM draft); New chat on ephemeral mismatch keeps the pointer; first regen no longer duplicates the seeded swipe alt.
- Parked LLM-mismatch draft survives Ask↔RP (null session no longer wipes the pointer) and idle greeting sync; share/clear_chat uses RP-aware fresh chat so character greeting is reinjected.
- Mismatched LLM draft keep-id is no longer overwritten by a greeting autosave; delete→re-import rematch awaits remap then reinjects greeting into an empty RP transcript.
- Start chat / character refresh sets active character synchronously so Send is not blocked; orphan rematch id is not clobbered by unrelated (or LLM-parked) deletes; mismatched LLM drafts keep their resume pointer across Ask↔RP.
- LLM draft/history load keeps the parked character id; Ask→RP respects an Ask-side LLM toggle over a mismatched draft; deleting a parked character in LLM mode no longer wipes the open LLM thread.
- LLM mode no longer clears the selected character id (LLM-off restores greeting/chrome); chrome refreshes on LLM toggle even when character LiveData is unchanged; regen/instruct respect the same character-or-LLM gate as Send.
- RP swipe state no longer leaks into new chats; selected swipe alt is reapplied on load and autosaved.
- Activating a character from the Hub pops back to chat (not Hub); confirms before wiping an active RP thread.
- Draft session IDs and swipe prefs cleared when a chat is deleted; stale drafts skipped on mode switch.
- Tools / web search / attach affordances forced off in RP; model chip opens Characters (long-press = models).
- Character avatar export embeds Base64 JPEG (portable); import writes avatar files; decode downsamples.
- RP regenerate/instruct no longer duplicates the user turn or creates Ask forks; swipe alts survive and append.
- › starts the first alternate when alts are empty; regenerate only on the last assistant in RP.
- New chat in RP reinjects the active character greeting; turning LLM mode off keeps the character.
- Delete→re-import remaps session `characterId` via exportKey; lore import upserts by name; first lorebook auto-activates.
- Cold start restores the draft session for the saved Ask/RP mode; Ask→RP without a draft reinjects greeting.
- Orphan character loads preserve session `characterId` across autosave (remap still works).
- RP user-edit truncates without Ask forks; regen keeps greeting in API memory; cancel clears pending swipe append.
- Provider `content_filter` finish reasons render as error bubbles (not character speech); client-side RP filter removed.
- RP API builders ignore web-search prefs; Ask←RP restores tools/web LiveData from prefs.
- Instruct/regen require an assistant reply after the last user turn (cancel no longer wipes greeting).
- Deleting the open history chat no longer resurrects it; character delete preserves id for rematch.
- Assistant edits sync swipe alts; Coil avatar cache busts on rewrite; LLM-off restarts with greeting.
- RP chrome force-disables tools/web LiveData without writing Ask prefs; tools hard-gated in API builders.
- Cancel mid-regen restores the stashed swipe alt (does not overwrite greeting); swipe appends if reply missing.
- Deleting the open chat clears the ghost transcript; multi-line example dialogs parse correctly; persona auto-saves on back.
- RP composer keeps draft on filter/character failures; assistant edits autosave; regen errors restore prior alt.
- Character switch confirms on greeting-only threads; one-sided examples reach the prompt; LLM-off with no character clears the transcript.
- Example parse/format moved to `RpPromptEngine` with unit coverage; dead `rp_lang_system` / `blockMessage` removed; Help/DESIGN/a11y synced.
- Lorebook rows expose Set active / Delete (tap row to edit); hot chat/LAN/TTS toasts stringified.
- UTF-8 mojibake fixed in RP swipe/composer strings; character rows expose Delete; reminder-only sends use a continue beat; persona preset blank name toasts.
- Empty RP no longer opens Ask attach via long-press Send; pending files/audio rejected in RP; deleting the active character clears the open thread.
- LLM mode confirms on greeting-only threads; RP model-chip a11y; blocked/filtered replies styled like errors; Help synced.
- RP model-chip long-press opens the picker (not OpenRouter); model observer no longer clobbers character/LLM label.
- Swipe alts cleared/ignored on greeting-only threads; greeting bubble refreshes after editing the active character.
- Character library: row tap edits; Start chat is explicit (parity with lore Activate).
- Mode switch / load / new chat cancel in-flight streams; Ask↔RP blocked while awaiting.
- History New chat reinjects RP greeting; deleting the open RP session no longer autosaves a resurrected greeting.
- Character import refreshes active chrome/greeting; library rows drop redundant Edit buttons; swipe eligibility unit-tested.
- Long-press back/backcopy uses RP-aware new chat; delayed swipe restore invalidated on load/mode switch; Ask↔RP restore job serialized.
- Session load / mode switch / character start share one cancellable transition job; autosave snapshots mode+messages to avoid cross-mode history corruption.
- Assistant edit no longer bakes reasoning into content/swipe alts; Help clarifies RP user vs assistant edit.
- Public new chat cancels in-flight load/restore; nested clears use clearOpenTranscript; save aborts instead of minting after delete; swipe clears reasoning.
- RP send/regen set awaiting early (blocks Ask↔RP race); autosave persists emptied saved chats; thinking labels stringified.
- Stop cancels RP prep job and clears stale pending-instruct; regen marks swipe-append before prep so Stop mid-build restores.
- Regen prep failures restore swipe alts; Stop restores synchronously (no autosave hole race); swipe wipe deferred until send; ChatSaveGate unit-tested.
- LAN early-return clears awaiting; Stop always clears awaiting; session transitions restore mid-regen before epoch bump.
- clearOpenTranscript skips awaiting-clear (no mid-wipe autosave); network finally only clears awaiting for the active job.
- New conversation / long-press Home clear the composer draft (not only the transcript).
- Reminder button focuses an existing `_(Reminder: …)_` instead of nesting another.
- Gate RP swipe while awaiting (no mid-regen append); flush swipe alts when first autosave mints a session id.
- Regen `choice.error` restores swipe alt (same as network errors); delete/truncate reseeds swipe when last reply no longer matches alts.
- Mid-stream Stop/error on regen replaces partial with stashed alt; cancelled rpPrepJob finally must not clear a newer pending Instruct.
- Ask↔RP flips mode after cancel so mid-regen restore runs; Stop on a normal RP stream discards the partial; swipe wipe + composer restore only after send actually starts.
- Stop during RP prep restores the composer draft; streamed `choice.error` aborts via handleErrorResponse; OpenRouter stream finalizes swipe state on Main.
- Non-streamed `choice.error` returns before success/finalize; terminal errors clear discardable so Stop won’t wipe the Error bubble.
- Clear discardable on successful finalize so Stop cannot delete an already-finished RP reply.
- Hub export/import toast honestly on null streams; lore activate warns if “Use active lorebook” is off; character/lore libraries use viewLifecycleOwner scope.
- Stringify AI message action a11y; RP toolbars label Navigate up; character/lore edit + settings use viewLifecycleOwner and fail closed if the row was deleted.
- Start chat from Settings→Hub pops settings too; gone-on-save dismisses editor; empty character/lore export blocked before file picker.
- Settings entry points share the `"settings"` back-stack name so Start chat can reliably return to chat.
- Model-chip Start chat pops the library; Start chat closes history drawer; LLM mode ignores leftover character for chrome/prompt/save.

### Changed
- RP hub sections (Library / Backup); lorebook list rows with Active badge; character Start chat affordance; empty states.
- Persona presets: Delete button on selected preset. Language spinner uses display names. LLM mode warns before wipe.
- Help / a11y copy for Ask↔RP chip and GradatiON RP hub.
- Assistant bubbles in RP show character name + avatar header.
- Character `exportKey` (DB v3) rematches RP chats after character re-import.
- Character/lore edit Save gated until load finishes (no empty overwrite race).
- Dead citation/sources layout stubs removed; Help settings map corrected.
- RP also hides plus/gen/presets; orphan deleted-character sessions toast on load.
- Mode draft IDs update on history open / save / new chat (Ask↔RP no longer restores the wrong thread).
- Provider content-filter errors use string resources; thinking header uses string resource.
- Settings detail labels + common chat/adapter toasts moved to string resources.
- RP Reminder insert button; compact swipe ‹ ›; reply cleaner no longer strips emoji.
- Chat overwrite always persists RP mode/character/isLlm (removed dead title-only branch).
- Deleting the active lorebook clears active state (toast prompts Set active on another book).
- Chat chrome contentDescriptions stringified.
## 2.1.133-rp — 2026-07-28

### Added
- **GradatiON RP mode** — Ask ↔ RP toggle in the chat shell (local SillyTavern-lite).
- Character library (CRUD, avatar, greeting, pro prompt), persona, flat lorebooks, RP settings.
- Ported content_bot prompt engine (`RpPromptEngine`), Reminder syntax, swipe/regenerate/instruct UI wired end-to-end.
- Room v2: `mode` / `characterId` / `isLlm` on sessions; `rp_characters` and `rp_lorebooks` tables.
- Settings → Advanced → GradatiON RP hub; Help/README/DESIGN updated.

### Fixed
- RP regenerate/instruct now use RP prompt path (not Ask resend); instruct applied before prompt build.
- Streaming responses run through RP reply cleaner (no client-side content filter).
- Swipe alternate-replies bar, persona presets, example-dialog editor, lorebook import/export.
- Stale `characterId` on chat import; active character cleared when deleted; draft session saved on character activate.

## 2.1.132-gradation — 2026-07-28

### Added
- GradatiON brand: app name, history wordmark (Iceland), arc logo watermark, and launcher icon.
- Workspace writes to `Download/gradation` (legacy `grokion` / `oxproxion` still readable).

### Changed
- Full rebrand from Grokion/xAI chrome to GradatiON dark monochrome palette (`#050505` / `#E8E8E8`).
- In-app Help rewritten; README, SHELL.md, DESIGN.md, and store metadata updated.
- Screenshots and Play listing art refreshed (Ask, History, Models, Settings).
- `.gitignore` expanded for agent/UI scratch, secrets, and `gradle.properties.local`.

### Removed
- Unused Grok launcher and watermark PNGs; dead robot launcher vectors.

## 2.1.131-grokion — 2026-07-28

### Fixed
- History → Settings fades in over the drawer without flashing the main chat.
- Fork branches anchor to the correct AI message; swap/regenerate no longer
  duplicates or misplaces the navigator.
- History search close button no longer crashes (`ImageView` vs `ImageButton`).
- Copy feedback ticks use ink (white) instead of green success tint.

### Changed
- History screen Grok parity: wordmark scale, section/row typography, APK icons,
  44dp touch targets.
- Delete/rename confirmations use centered M3 dialogs (`GrokConfirmDialog` /
  `GrokInputDialog`); alert theme upgraded for destructive actions.
- New chat starts immediately (no confirmation dialog).
- Streaming no longer auto-scrolls / sticks the list to the bottom.
- Model picker sheet layout and row styling refreshed.

## 2.1.130-grokion — 2026-07-28

### Fixed
- LAN model adder shows tap feedback (haptic, checkmark, pop-in) instead of
  silenced toasts when adding a model.
- Edit/Delete overflow popups (models, presets, prompts, system messages) use
  correct trash icon and theme-aware ink/error tints (no black-on-dark SVGs).
- Selected-model checkmark in Your Models follows `xai_ink` in dark/light themes.

### Changed
- `.gitignore` excludes agent/debug scratch (`.tmp_mat/`, screenshot dumps).

## 2.1.129-grokion — 2026-07-28

### Fixed
- Attach **Gallery** from the composer `+` menu now opens the system photo
  picker (`PickVisualMedia`, with document-picker fallback) instead of being
  blocked on non-vision models or failing to launch after the popup dismisses.
- Model chip chevron in the top bar follows `xai_body` in dark/light themes
  (no longer hardcoded black on dark).

## 2.1.128-grokion — 2026-07-28

### Changed
- Secondary UI clearout: Settings detail regrouped into Grok cards (Libraries /
  Generation / Chat chrome); power tools bar merges Extended Dock + Top Bar.
- Library / model-catalog / dialog screens restyled to canvas + Grokion buttons
  (no FABs / CardView shells).
- Conversations always autosave; History rename for titles; Import/Export visible
  in History and Data & Privacy.

### Removed
- Manual Save chat UI (attach row, dead top-bar button, SaveChat dialog).
- Auto Save Chats settings toggle (behavior is always on).

### Fixed
- New chat confirm no longer implies data loss; Help matches current Ask / History.

## 2.1.127-grokion — 2026-07-27

### Fixed
- Cold-start crash: SQLCipher migration no longer probes already-encrypted
  `chat_database` as plaintext (`SQLiteNotADatabaseException`).
- Stream stick-to-bottom no longer thrash-scrolls every token.

### Changed
- Model chip pinned top-right; Ask tab chrome removed.
- Message action rows match Grok placement (user edit/copy on tap; regenerate
  under assistant); equal icon gaps.
- App-wide Inter; GROKION history wordmark uses Iceland.
- Slimmed bundled selectable fonts to Inter (+ Iceland brand).

## 2.1.126-grokion — 2026-07-27

### Changed
- Grok Ask shell parity: tall composer with in-panel model chip, Ask tab chrome,
  history drawer (profile header, title+time+overflow, bottom search/settings/new),
  settings as grouped cards with X close.
- Shared fragment stack motion + smoother history drawer / send morph.
- Semantic dark palette tuned to Grok canvas/surfaces; monochrome switches.

### Fixed
- Mic gated behind extended dock; expandable-input collapse height; model picker
  LAN/OpenRouter navigation container; history→settings drawer race.

### Removed
- Live STT (mic, Voice settings, assist auto-STT, watermark hold-to-talk,
  Transactivity). TTS and transcription-model file upload remain.

## 2.1.125-grokion — 2026-07-27

### Security
- Chat Room DB (`chat_database`) encrypted at rest with SQLCipher; passphrase wrapped
  via Android Keystore. Existing plaintext DBs migrate once via `sqlcipher_export`.

### Changed
- Docs/Help/F-Droid metadata aligned with Grokion branding, SQLCipher storage, and
  LAN TLS / destructive-tools settings.

## 2.1.124-grokion — 2026-07-27

### Fixed
- Chat overwrite no longer duplicates messages (delete-then-insert per session).
- Stop/cancel no longer surfaces as an error bubble; single-flight network jobs.
- Stream auto-scroll locks on drag only; TTS cleaned up in `onDestroyView`.
- LAN key migration no longer drops plaintext if Keystore encrypt fails; LAN save
  dialog surfaces encrypt failure.
- MLX model fetch uses `lanHttpClient` (honors trust-self-signed toggle).
- HTTP LAN endpoints validated as private/loopback/`.local` (NSC permits cleartext
  because Android cannot express RFC1918 CIDRs; app-layer blocks public cleartext IPs).

### Security
- Cleartext allowed for LAN HTTP with host validation; OpenRouter remains HTTPS.
- LAN self-signed TLS is an opt-in Settings toggle.
- LAN API key encrypted via Android Keystore; backup excludes prefs + chat DB.
- Autosend confirmation; biometric gate on share/assist/spell-check when enabled.
- Tools default off; destructive file tools gated; import size limits; settings-action allowlist.

### Changed
- Grok shell MVP: single-row Ask-anything composer, sparse top bar, history slide-over,
  copy icons, scrim dim, warm light canvas + theme-aware Markwon/switches.
- Extracted `SseJsonReader`, `ChatSessionSaver`, `ToolExecutorPolicy`, `WorkspacePaths`,
  `LanEndpointValidator`.
- Dropped unused Navigation Component dependencies.

## 2.1.123-grokion — 2026-07-27

### Changed
- **Identity rebrand (Phase 3):** user-visible strings, themes (`Theme.Grokion`), HTTP
  User-Agent / OpenRouter headers, workspace folder (`Download/grokion` with legacy
  `oxproxion` read fallback), and tool names (`list_grokion_files`, `read_grokion_file`;
  old names still accepted at runtime).

## 2.1.122-grokion — 2026-07-18

### Changed
- **Auto-save toast removed:** the "Chat saved: ..." toast no longer pops up on
  every message — auto-save runs silently in the background.
- **Title generated once, not per-save:** auto-save now reuses the existing
  session title on subsequent saves. The AI title generation only fires on the
  very first save of a new chat.

## 2.1.121-grokion — 2026-07-17

### Fixed
- **Auto-save only fired once:** removed the `autoSaved` one-shot gate so the
  chat is re-saved with latest messages on every streaming completion, not
  just the first.
- **Title generation swallowed failures:** `autoSaveChat()` now catches
  exceptions from `getSuggestedChatTitle()` without losing the fallback title.
  API error messages (e.g. "Error: 401 ...") are detected and discarded so
  the fallback (first user message or timestamp) is used instead.

## 2.1.120-grokion — 2026-07-16

### Added
- **Auto Save Chats** — new toggle in Settings. When enabled, the chat is
  automatically saved with an LLM-generated title after the first assistant
  response completes streaming.
- `autoSaveChat()` function in ChatViewModel using the existing
  `getSuggestedChatTitle()` infrastructure (was previously unused).
- `SharedPreferencesHelper` getter/setter for auto-save preference.

## 2.1.119-grokion — 2026-07-16

### Fixed
- **Light theme crash (root cause):** `MainActivity.onCreate()` was hardcoding
  `AppCompatDelegate.MODE_NIGHT_YES` on every launch, overriding the user's
  saved theme preference. Changed to read from `SharedPreferencesHelper` and
  apply the saved mode before `super.onCreate()`. This was causing an
  infinite recreation loop when Light mode was selected (MainActivity fought
  ChatFragment's theme listener, each forcing opposite modes).

### Changed
- **Model chip maxWidth** increased from 140dp to 180dp for longer model names.
- **Menu panel** fades in/out (200ms) instead of instant toggle.
- **Dim overlay** fades with the menu panel.
- **Send button** icon cross-fades between send ↔ stop (100ms).
- **Chat frame** has `animateLayoutChanges="true"` for smooth transitions.
- **RecyclerView** item appearance animation via `SimpleItemAnimator`.

### Fixed
- Removed stale `bgreen.xml` and `Widget.Grokion.IconButton.Pill` style.

## 2.1.116-grokion — 2026-07-16

### Fixed
- **Light theme crash (proper fix):** created `values-night/colors.xml` with
  all dark xAI colors and rewrote `values/colors.xml` with light-mode
  equivalents. Theme now uses `Theme.Material3.Light.NoActionBar` in
  `values/` and `Theme.Material3.Dark.NoActionBar` in `values-night/`.
  Android's resource qualifier system handles the swap automatically when
  the user picks a different theme mode.
- **Theme toggle restored:** Settings now has System / Dark options (no
  Light-only option — just System and Dark, both properly supported).
- **ChatFragment theme code** restored to respect saved theme preference
  instead of hardcoding `MODE_NIGHT_YES`.

## 2.1.115-grokion — 2026-07-16

### Fixed
- **Model chip overlap:** constrained `modelNameTextView` between
  `openSavedChatsButton` and `saveChatButton` (start_toEndOf / end_toStartOf),
  reduced `maxWidth` from 220dp to 160dp — no more clipping into neighbors.
- **Light theme crash:** removed the Light / System theme toggle from Settings.
  Grokion now always forces `MODE_NIGHT_YES` (dark-only). Light mode would make
  all xAI colors invisible — completely unusable.
- **Theme toggle remnants:** removed orphaned `MaterialButtonToggleGroup` + theme
  handling code from SettingsFragment and ChatFragment.
- **Theme default:** `getThemeMode()` now defaults to `THEME_DARK` instead of
  `THEME_SYSTEM` — no risk of light mode on first launch.

## 2.1.114-grokion — 2026-07-16

### Fixed
- **Burger position:** composer `gravity` changed from `bottom` to `top` so
  buttons align at the top of the input field, not pushed to the bottom.
- **Missing "new chat" button:** `resetChatButton` visibility restored to
  `visible` by default (was `gone`), matching original oxproxion layout.
- **10 hardcoded `#FF7A17` orange values** across 8 Kotlin files replaced
  with `#FF7D8187` grey — these bypassed colors.xml entirely and kept
  toggle switches, selected states, and text highlights orange.
- `xai_selected` color changed from reddish-brown `#FF2A1A0A` to
  dark grey `#FF2A2A2A` to match the grey accent palette.

## 2.1.113-grokion — 2026-07-16

### Fixed
- Removed 18 commented-out `Log.*` debug statements from ChatFragment.kt
  (abandoned debug code cluttering the source).
- Deleted stale `ic_launcherrobby.png` icons from all mipmap densities
  (typo leftover, not referenced by any resource).

## 2.1.112-grokion — 2026-07-16

### Changed
- **Assistant message button bar:** reduced visual noise from 10 to 5 visible
  buttons. Export actions (PDF, Markdown, PNG, HTML, Save) hidden by default;
  core actions remain: copy, edit, share, speak (TTS), collapse toggle.
- **Model chip:** added `xai_canvas_soft` fill for a more prominent Grok-style
  pill (was transparent).
- **Menu panel:** increased padding (16dp → 20dp), row spacing (10dp/12dp →
  14dp/16dp), and button gaps (10dp → 12dp) for a premium, airier layout.

## 2.1.111-grokion — 2026-07-16

### Changed
- Accent colors: `xai_accent_sunset`, `xai_accent_sunset_soft`, and
  `xai_progress` changed from orange to grey (`#7D8187` / `#DADBDF`).
  Affects toggle switches, cursor, text selection highlight, progress bar,
  dialog accents, selected states, and outlined message borders.
- Mic visibility: reverted `speechButton` logic to match original oxproxion
  — hidden when `isExtendedDockEnabled` is off (default).

## 2.1.110-grokion — 2026-07-16

### Changed
- Bottom composer: full Grok-clean restyle — all non-send buttons use
  transparent backgrounds so icons float inside the pill; only send button
  keeps filled grey circle (`xai_canvas_mid`) as primary CTA.
- Container padding increased from 2dp to 4dp for better breathing room
  inside the pill's rounded corners.
- All pill buttons now have `app:rippleColor="@color/xai_canvas_mid"` for
  subtle touch feedback on transparent backgrounds.
- Layout structure reverted to match original oxproxion (horizontal container,
  vertical button stacks, 48dp IconButton.Filled) to restore programmatic
  expanded/collapsed state logic — the Grok look comes from styling, not
  structural changes.

## 2.1.109-grokion — 2026-07-16

### Changed
- Composer pill: added `elevation="2dp"` for subtle shadow separation from canvas.
- Send button: fixed asymmetric margins (`4dp/1dp` → `2dp/2dp`) for balanced spacing.
- Composer container: tightened bottom padding (`10dp` → `8dp`) to match Grok's compact feel.
- EditText: reduced `minHeight` from `28dp` → `24dp` for tighter single-line input.
- Icon vectors (`ic_grok_menu`, `ic_send`, `ic_mic`): normalized to 8-digit ARGB hex for consistent theming.

### Added
- Custom pill icon button style (`Widget.Grokion.IconButton.Pill`) with zero padding/overrides applied to all 7 pill buttons — eliminates Material3 default icon padding that caused misalignment.

## 2.1.108-grokion — 2026-07-16

### Changed
- Composer pill: tightened proportions (48dp minHeight, tighter padding, smaller icon targets).
- Empty state watermark: replaced raster PNG with exact Grok vector logo (ic_vector_grok_logo paths).
- Send button: defined `bgreen` color (#FF1CAB55) matching Grok iOS green.
- Stop icon: replaced with exact Grok `ic_vector_stop` (rounded square).

### Fixed
- Build error from missing `bgreen` color resource.

## 2.1.107-grokion — 2026-07-16

### Changed
- App display name and id rebranded to **Grokion** (`io.github.warexpor.grokion`).

## 2.1.106-grokilike — 2026-07-16

### Changed
- `applicationId` set to `io.github.warexpor.grokilike` (no longer shares identity with upstream oxproxion / F-Droid updates).
- FileProvider authority uses `${applicationId}.fileprovider`.

## 2.1.105-grok — 2026-07-16

### Changed
- App display name set to Grokilike (superseded by Grokion).
- Launcher icon uses official Grok mark from Grok.ipa (adaptive + density mipmaps, black canvas).

### Added
- Hardened SSE streaming: proper multi-line `data:` frames, keepalive comments, NDJSON fallback for LAN servers, `Accept: text/event-stream` on stream requests.
- Streaming enabled by default for new installs.

### Fixed
- Stream chunk models tolerate missing optional fields (id/model/delta) from sparse providers.
- Restored attach / image-upload top-bar buttons (were `gone` after shell rewrite).
- System-message control visible in composer again.
- Mic always available; clear only when extended dock + text (was hiding mic when dock off).
- Empty-state (mark + prompt) correctly fades under menu and restores on dismiss.
- Extended top bar: parent `HorizontalScrollView` visibility tracks preference (row was permanently hidden).
- Secondary screens/dialogs token drift: licenses, markdown viewer, edit message, LAN dialog, preset chooser.

### Changed
- Chat shell rebuilt for Grok iOS feel: minimal top bar (history · model chip · save), quiet empty state, bottom pill composer.
- Empty state uses Grok mark + "What do you want to know?" (inspired by Grok.ipa 1.3.94).
- Composer: single pill field, "Ask anything" hint, mic + white send CTA; secondary controls hidden until needed.
- Assistant messages: flat full-width text (no bubble); user messages: soft rounded chips, right-aligned.
- Thinking state: soft alpha pulse instead of heavy bubble color flash.
- App display name set to "Grok".

### Added (UI)
- Grok shell drawables: `bg_composer_pill_tall`, `bg_top_bar_grok`, `bg_model_chip`, `bg_menu_panel`, `ic_grok_mark`.

## 2.1.103-xai — 2026-07-16

### Changed
- Full UI overhaul to xAI visual language: near-black canvas (`#0a0a0a`), white/ink text, hairline borders, sunset orange accent (`#ff7a17`).
- Forced dark theme only (xAI is dark-canvas only); light Material theme removed from day/night resources.
- Message bubbles, dialogs, inputs, icon chrome, and button selectors remapped to xAI tokens.
- Runtime hardcoded palette hex values updated to match the new system.

### Added
- Named color tokens (`xai_*`) in `values/colors.xml` for canvas, hairline, body, mute, sunset accent, links, and errors.
