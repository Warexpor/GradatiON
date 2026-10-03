# Handoff (2026-10-03, Notifications/Away wave 35)

On `cursor/notif-wave-35-b12f` (PR into `gradation/app-pass`). Notif/away follow-ups after cancelling an unposted approval and the 50-shade cap (#168). Rebased onto `d9b016db997b3ccf027cd6ce34c8b93c812c2c71` (Stability #174, which includes Roleplay #171, Hub #173, Code #172, Stability #170, Import #169, Notifications #168, Code #167, Hub #166, Chat #165, Roleplay #164, and Settings #163). Not a redo of #174 (a finished side file is installed when the recovered database name is already gone, and a finished wal, shm, or journal side file is kept beside a recovered database that has bytes). Not a redo of #171 (a lore key has to be its own words, so `old man` does not match `old manor` and `the dock` does not match `the docks`, and `Мира` still matches `мира`; `<END_OF_DIALOG>` ends an example, a Unicode line break still splits START or END_OF_DIALOG, and the same marker inside a sentence stays in the reply). Not a redo of #173 (a `#note` on a percent-encoded bridge address is dropped even when a later field keeps the query, a `%23` in the address stays a hash, a fingerprint wrapped with a line break, a tab, or a non-breaking space still pins, and the hub and the character list keep a star or backtick, end the line on a br or a Unicode line break, and show a pasted `&amp;` or `&#39;` as the character). Not a redo of #172 (a click that also names the mouse button or a target still shows the point or the target, a screenshot, snapshot, or PDF saved to a file shows the file, console messages show the level, a network list shows the URL filter, and switching or closing a tab shows select or close plus the index). Not a redo of #168. Not a redo of a cleared shade posting again, cap order, or a new Speak utterance id.

- A later finished turn alerts again while that session's shade is still up. Sending another prompt leaves the shade, and the next finish updates that same notification. `onlyAlertOnce` used to swallow the alert, so the phone never buzzed.
- Answer shade Speak puts Speak back when the engine refuses the utterance. A refusal never calls back, so the shade used to stay on Stop. Speak on a message does the same for the stop icon.
- A long reply is cut for Speak and for the shade's Copy line without ending on half an emoji. The cut used to land on the first half of a character, and that broken character was what Speak and Copy used.

Phone: Notify when away on, finish a turn, send another prompt without dismissing the shade, leave, and finish again (the phone should buzz). Not a phone check for a refused speech engine, or for an emoji sitting on the 3900-character cut.

# Handoff (2026-10-03, Stability wave 35)

On `cursor/stability-wave-35-0841` (PR into `gradation/app-pass`). Persistence follow-ups after a finished side file next to a 0-byte recovered database, import-note side files removed before the installed file, and a portrait or wallpaper rewrite written to a new file first (#170). Rebased onto Roleplay #171 (which includes Hub #173 and Code #172). Not a redo of #171 (a lore key has to be its own words, so `old man` does not match `old manor` and `the dock` does not match `the docks`, and `Мира` still matches `мира`; `<END_OF_DIALOG>` ends an example, a Unicode line break still splits `START` or `END_OF_DIALOG`, and the same marker inside a sentence stays in the reply). Not a redo of #173 (a `#note` on a percent-encoded bridge address is dropped even when a later field keeps the query, and a `%23` in the address stays a hash; a fingerprint wrapped with a line break, a tab, or a non-breaking space still pins; the hub and the character list keep a star or backtick that is part of the words, a `br` or a Unicode line break ends the line, and a pasted `&amp;` or `&#39;` shows as the character). Not a redo of #172 (a click that also names the mouse button or a target still shows the point or the target, a screenshot or PDF saved to a file shows the file, console messages show the level, a network list shows the URL filter, and switching or closing a tab shows the action and the index). Not a redo of #170. Not a redo of a trailing-dot local server, roleplay list underscores, a history star or backtick, pairing notes, away-shade cancel, import pictures, lore names, or keyless characters.

- A finished side file is installed when the recovered name is already gone, not only when that name is 0 bytes. The empty file used to be deleted and the process then died before the side file took the name, and the next launch deleted the side file or opened a new database beside it.
- A finished `-wal`, `-shm`, or `-journal` side file is installed beside a recovered database that already has bytes. Those copies used to be deleted, so messages that had not been checkpointed were gone. A sidecar with no main is still not installed.

Phone: not a phone check for a recovered name that is already gone, or for a wal side file.

# Handoff (2026-10-03, RP wave 35)

On `cursor/rp-wave-35-039a` (PR into `gradation/app-pass`). Roleplay lore keys and example end markers. Rebased onto `07af13e9` (Hub #173). Not a redo of #173 (a `#note` on a percent-encoded bridge address is dropped even when a later field keeps the query, a `%23` in the address stays a hash, a fingerprint wrapped with a line break, a tab, or a non-breaking space still pins, and the hub and the character list keep a star or backtick that is part of the words, a `<br>` or a Unicode line break ends the line, and a pasted `&amp;` or `&#39;` shows as the character). Not a redo of #172 (a click that also names the mouse button or a target still shows the point or the target, a screenshot, snapshot, or PDF saved to a file shows the file, console messages show the level, a network list shows the URL filter, and switching or closing a tab shows select or close plus the index). Not a redo of #164 (a character-list underscore between letters that are not ASCII, an example sample speaker when the card is Jordan and you are Alex, a `<START>` or `END_OF_DIALOG` line that ends the example, and `Bot:` as the character side). Not a redo of #170, #169, #168, #167, #166, #165, or #163.

- A lore key has to be its own words. `old man` used to match `old manor`, and `the dock` used to match `the docks`. `Mira` still matches `Mira's`. A key also matches when only the case differs outside ASCII (`Мира` and `мира`). Case folding used to stop at ASCII.
- `<END_OF_DIALOG>` ends an example the same way `END_OF_DIALOG` does. It used to be stored as the reply, and the next exchange was glued on. A `<START>` or `END_OF_DIALOG` still splits when the line break is a Unicode separator. The same marker inside a sentence stays in the reply.

Phone: a lore key `old man` should stay out of the prompt when the chat says `old manor`, and a key `Мира` should come in when the chat says `мира`. Save example dialogs that end on `<END_OF_DIALOG>` (the marker should not be in the reply; the next exchange should be its own example).

# Handoff (2026-10-03, Hub/Pair wave 35)

On `cursor/hub-wave35-4312` (PR into `gradation/app-pass`). Hub and pairing follow-ups after a `#note` on the token or fingerprint, the first bridge address, and a fingerprint whose spaces were `+` (#166). Rebased onto `b5142861a7ea3718b74b53c7adb7700be47e68ab` (Code #172, which includes Stability #170, Import #169, Notifications #168, Code #167, Hub #166, Chat #165, Roleplay #164, and Settings #163). Not a redo of #172 (a click that also names the mouse button or a target still shows the point or the target, a screenshot, snapshot, or PDF saved to a file shows the file, console messages show the level, a network list shows the URL filter, and switching or closing a tab shows select or close plus the index). Not a redo of #166. Not a redo of a history star or backtick, a far-apart search, or a Unicode line break in a file name (#165). Not a redo of a character-list underscore between letters that are not ASCII, an example sample speaker, or START or END_OF_DIALOG (#164). Not a redo of a trailing-dot local server or a refused zone id or leading-zero mapped host (#163). Not a redo of the hub divider line or the emoji cut (#155).

- A `#note` on a percent-encoded bridge address is dropped even when a later field keeps the query. `url=wss%3A%2F%2Fh%2Fv1#note&token=…` used to save `wss://h/v1#note`, because only a token or fingerprint was cut before decode, and the `&` after the note kept the outer query. A `%23` in that address still stays a hash. A raw `wss://` address still keeps its own `#section`. A `#note` on the token or the pin still drops.
- A fingerprint wrapped onto more than one line still pins. A newline, a tab, or a non-breaking space between the bytes used to reject the link. A `%0A` in the pin is the same break. A `+` in a base64 pin is still a plus, and a space written as `+` between hex bytes still is.
- The hub and the character list keep a star or a backtick that is part of the words (`2 * 3`, `a*b`, a lone `` ` ``). Those used to be stripped with the emphasis marks. Emphasis, a list star, and a closed code span still come off. A `<br>` or a Unicode line break ends the line, so the next sentence stays off the row instead of showing up under the name. A pasted `&amp;` or `&#39;` shows as the character. `&amp;amp;` stays `&amp;`.

Phone: pair `gradation://pair?url=wss%3A%2F%2Fh%2Fv1%23section#note&token=…` (the host should be `wss://h/v1#section`, not `…#section#note`). Pair a fingerprint that is broken across two lines (it should pin). On the hub and the character list, a first line of `2 * 3` should keep the star, and `She waits.<br>Second` should show only `She waits.`

# Handoff (2026-10-03, Code wave 35)

On `cursor/code-mode-wave35-a7ce` (PR into `gradation/app-pass`). Code-mode follow-ups after upload and drop paths, a wait's disappearing text or seconds, an evaluate's function, and Grok Build's question (#167). Not a redo of that pass. Not a redo of the config-option mode pill or Playwright form, dialog, and resize lines. Not a redo of a trailing-dot local server, roleplay list underscores, a history star or backtick, pairing notes, away-shade cancel, or a ready database copy.

- A coordinate click that also names the mouse button shows the point (`10, 20`). A click that names a target shows that target, and a control name still wins. The button used to replace both, including a later update that only repeated `button`. A mouse-down that only has a button still shows it. A shell command that also carries a button stays the command.
- A screenshot, snapshot, or PDF saved to a file shows that file. The line used to be blank, or only the element ref. The control name still wins, and a later update that only repeats the target leaves the file. Console messages show the level, or the file when that is all that was sent. A network list shows the URL filter, the file, or `static` when images and scripts are included. `static: false` is not a line. One request still shows its index.
- Switching or closing a browser tab shows the action and the index (`select 2`, `close 1`). The row used to be only the index, because that key is read first. A new tab still shows its URL, and listing tabs says `list`. A later update that only repeats the index leaves the action.

Phone: on Cursor Agent or Playwright, click at a point that also sends `button` (the line should stay the coordinates when the tool finishes). Save a screenshot to a file (the line should be the file, not blank). Switch to another tab (the line should say `select` and the index, not only the number).

# Handoff (2026-10-03, Stability wave 34)

On `cursor/stability-wave-34-faf8` (PR into `gradation/app-pass`). Persistence follow-ups after a 0-byte recovered database no longer parks over the hold copy, deleting a portrait or wallpaper removes the side file first, and a sliced message stops when the next index would not fit in an Int. Rebased onto Import #169 (which includes Notifications #168, Code #167, Hub #166, Chat #165, Roleplay #164, and Settings #163). Not a redo of #169 (a portrait or wallpaper still over the backup cap is shrunk until it fits, a lore file counts each book once, a file of only blank names imports nothing, a character file with no export key updates the character of that name). Not a redo of #168, #167, #166, #165, #164, or #163. Not a redo of a 0-byte recovered database being skipped, a same-stamp picture replace, import notes written to a new file first, a long slice keeping only the step, the recovered file at the databases root, portrait and wallpaper side files moving with history, or emoji slices.

- A finished side file next to a 0-byte recovered database is the copy that still has bytes. The empty name used to be moved, and the side file was then deleted, so that history was gone. A recovered file that already has bytes, and an empty name with no finished side file, are unchanged.
- Clearing import notes removes the side files before the installed file. A kill after the installed file was removed used to leave an older side file, and the next launch applied those notes.
- Rewriting a portrait or wallpaper writes the new bytes to a new file first. Opening the finished side file for writing used to truncate it before those bytes were durable. Deleting a scene photo removes the side files before the live file, so a kill there cannot put the picture back.

Phone: not a phone check for an empty recovered name, a kill while import notes are cleared, or a kill during a portrait rewrite.

# Handoff (2026-10-03, Import wave 34)

On `cursor/import-wave-34-0712` (PR into `gradation/app-pass`). Import follow-ups after a rolled-back character import, the last greeting in a file, and scaling an oversized portrait or wallpaper (#159). Rebased onto Notifications #168 (which includes Code #167, Hub #166, Chat #165, Roleplay #164, and Settings #163). Not a redo of #168 (cancelling an approval that was never posted no longer removes a different shade that shares the notification id, and the away cap stays inside Android's 50 by dropping the oldest shade before the new one is posted). Not a redo of #167 (upload paths, wait text, evaluate, Grok Build's question). Not a redo of #166 (a `#note` on the pairing token or fingerprint, the first bridge address, a fingerprint whose spaces were `+`). Not a redo of #165, #164, or #163. Not a redo of a lore pin staying on the chosen copy, a waiting pin attaching to the active copy, a wallpaper clear retrying, trimmed names, a blank name not imported, a waiting pin replacing the old pin, a failed portrait write retrying, duplicate lore names exporting the newest text and updating every local row, a rolled-back import not applying its notes, a later edit keeping a waiting portrait, the last copy owning the greeting, or a torn picture being left out.

- A portrait or wallpaper that is still over the backup cap after the usual scale is shrunk until it fits. It used to be left out, so the other phone kept the picture it already had. A torn file is still left out.
- A lore backup counts each book once. World listed twice, or as world, is one book in the update prompt and the result. A file whose names are blank imports nothing.
- A character backup with no export key updates the one character of that name. Importing that file again used to add another copy. A second copy of the name in the file is the same character, and the last copy is saved. Two locals that already share the name: the newest is updated, and a third is not added. The same export key twice is one character in the prompt and the result. A keyless row beside a keyed row of that name stays its own character.

Phone: export a character whose portrait or wallpaper is a large detailed photo (the other phone should get it). Import a lore file that lists World twice and a blank name (one book, and the notice should say 1). Import a character file that lists Ada twice with no export key, the second copy different (one Ada, the second text).

# Handoff (2026-10-03, Notifications/Away wave 34)

On `cursor/notif-wave-34-7bab` (PR into `gradation/app-pass`). Notif/away follow-ups after colon approval keys, a cleared shade's open token, and Speak on a blank reply (#162). Rebased onto Code #167 (which includes Hub #166, Chat #165, Roleplay #164, and Settings #163). Not a redo of #167 (upload paths, wait text, evaluate, Grok Build questions). Not a redo of #166, #165, #164, or #163. Not a redo of #162. Not a redo of the last-shade token, the cap counting shades that stay up, in-chat Stop, a cleared shade posting again, shade age across a restart, or a new Speak utterance id.

- Cancelling an approval that never had a shade no longer cancels a different shade whose notification id is the 24-bit hash of that key. Allow, Deny, or a finished turn for a request that was not posted used to drop the other session's shade. That shade's open token stays.
- The away cap stays inside Android's 50-notification limit. The oldest shade is removed before the new one is posted, so the new alert is not refused. One slot stays free for the answer-ready shade. Shades that stay up after the next prompt still count. After a restart the oldest is still the one that goes.

Phone: Notify when away on, finish 49 turns so the shades stay, then finish one more session (still 49 away shades, the first one gone). With an answer-ready shade also up, finish one more session (the answer shade stays, the oldest away shade goes). Not a phone check for two shades that share a notification id.

# Handoff (2026-10-03, Code wave 34)

On `cursor/code-mode-wave34-6e5b` (PR into `gradation/app-pass`). Code-mode follow-ups after the config-option mode pill and Playwright target, form, dialog, and resize lines. Rebased onto Hub #166 (which includes Chat #165, Roleplay #164, and Settings #163). Not a redo of #166 (a `#note` on the pairing token or fingerprint is dropped even when a later `#section` keeps the query, the first bridge address wins over a later `url` or `address`, and a hex fingerprint whose spaces were form-encoded as `+` still pins). Not a redo of #165 (a star or backtick in a history row, both words of a long search, a Unicode line break in a file name). Not a redo of #164 (a character-list underscore between letters that are not ASCII, an example sample speaker when the card is Jordan and you are Alex, a START or END_OF_DIALOG line that ends the example, and `Bot:` as the character side). Not a redo of #163 (a trailing-dot local server, a refused link-local zone id, or a refused IPv4-mapped address with a leading zero). Not a redo of the config-option mode pill or Playwright browser lines. Not a redo of typed text, a control name, or a scroll beating an element ref, a listed Ask staying Ask, or `session/new` reading modes.

- A file upload shows the paths. Omitting them is the chooser being cancelled. Dropping files uses the execute card and shows those paths, or the dropped text, instead of the element ref. A later update that only repeats the target leaves the paths. A control name still wins. A shell command that also carries paths stays the command. A git ref still wins.
- A wait shows the text that should disappear, or how many seconds, when it is not waiting for text to appear. Evaluating a script shows the function instead of the element ref. Running Playwright code uses the execute card and shows the snippet, or the file when that is all that was sent. Finding text on the page uses the search card. One network request uses the read card.
- Grok Build's `x.ai/ask_user_question` (and `_x.ai/ask_user_question`) shows the question. The answer is the option label, keyed by the question text. A question that allows several answers is skipped, so the turn is not left waiting.

Phone: on Cursor Agent or Playwright, upload a file (the line should be the path, not blank) and drop a file on a control (the execute card should show the path, and stay that path when the tool finishes). A wait for text to disappear should show those words. On Grok Build, a question with two choices should show the card, and the answer should be the label you picked.

# Handoff (2026-10-03, Hub/Pair wave 34)

On `cursor/hub-wave34-78c3` (PR into `gradation/app-pass`). Hub pairing follow-ups after the divider line, the emoji cut, a form-encoded space in the address, and a trailing `#note` (#155), rebased onto Chat #165 (which includes Roleplay #164 and Settings #163). Not a redo of that pass. Not a redo of a history star or backtick that is part of the words, a search that keeps both words when they sit far apart, or a Unicode line break in a file name (#165). Not a redo of a character-list underscore between letters that are not ASCII, an example sample speaker when the card is named Jordan and you are Alex, a `<START>` or `END_OF_DIALOG` line that ends the example, or `Bot:` as the character side (#164). Not a redo of an IPv4 local server with one trailing dot, or refusing a link-local zone id and an IPv4-mapped address with a leading zero (#163). Not a redo of the hub hero line and pairing query text (#147). Not a redo of character-list buttons following whether the list is the screen, a pairing link keeping `#` and query keys named token/auth/ws/fp, or a fingerprint with spaces (#141).

- A `#note` on the token or the fingerprint is dropped even when it is not the last `#`. `token=…#note&url=wss://h/v1#section&fp=…` used to leave the note on the token, and the same shape on `fp` used to reject the pin, because only the last `#` with nothing query-like after it was removed. `#section` followed by those fields still stays. A `%23` in the token still stays a hash. A `#` on a bridge `auth` value that is not the pairing token still stays in the address.
- The first bridge address wins when a later parameter names it again. `address=wss://h/v1&url=wss://other…` (and `ws` then `address`) used to save the later one, because `url` is checked before `address` and `ws`. An empty `url=` still does not hide a later `address`. A key inside an address that already has `?` is still part of that address.
- A hex fingerprint whose spaces were form-encoded as `+` still pins. `URLEncoder` writes a space that way, and the pin was rejected. A `+` in a base64 pin is still a plus.

Phone: pair `gradation://pair?token=…#note&url=wss://h/v1?a=1#section&fp=12+ad+50+…` (the host should keep `#section`, the token should not end in `#note`, and the pin should be accepted). Pair `gradation://pair?address=wss://h/v1&url=wss://other.example/v1&token=…` (the saved host should be `wss://h/v1`).

# Handoff (2026-10-03, Chat wave 34)

On `cursor/chat-wave-34-ec05` (PR into `gradation/app-pass`). Chat follow-ups after history tokens, file indents, and the staged photo from the tap (#161). Rebased onto Roleplay #164 (which includes Settings #163). Not a redo of #164 (a character-list underscore between letters that are not ASCII, an example sample speaker when the card is Jordan and you are Alex, a START or END_OF_DIALOG line that ends the example, and `Bot:` as the character side). Not a redo of #163 (a trailing-dot local server, a refused link-local zone id, or a refused IPv4-mapped address with a leading zero). Not a redo of history sentences, file fences, or the JPEG a send still shows (#153). Not a redo of history keeping `#` `~` `>`, or staged text files one by one and the 1 MB cap (#139).

- History preview keeps a star or a backtick that is part of the words (`2 * 3`, `a*b`, a lone `` ` ``). Those used to be stripped with the emphasis marks, so the row no longer showed the token a search had matched. Emphasis, a list star, and a closed code span still come off.
- History search keeps both words when they sit far apart in a sent message. The row is one line, and the gap used to ellipsize the later word.
- A text file whose name contains a Unicode line break keeps that name on one header line. A line separator that is not CR or LF used to split the header.

Phone: send a chat whose last line is `2 * 3` (History should show the star). Search two words that are far apart in a long message (both should be on the row). Attach a file whose name contains a line separator (the model should see one header line).

# Handoff (2026-10-03, RP wave 34)

On `cursor/rp-wave-34-9771` (PR into `gradation/app-pass`). Roleplay list lines, example stand-ins, and card example markers. Rebased onto `ccf85cce` (Settings #163). Not a redo of #163 (a trailing dot on an IPv4 local server saves, and a link-local zone id or an IPv4-mapped address with a leading zero is refused). Not a redo of #156 (card `<START>` exchanges, `{{bot}}`, `{{random_user_N}}` in an example), #148 (example sides with a space before the colon, the character speaking first, `Keys`/`KEYS` headers, Windows line endings, list marks `#` `~` `>`), or #142 (list underscores, the User side after a leading blank line, lore keys in `()` `（）` `【】`).

- A character-list line keeps an underscore between letters that are not ASCII. `déjà_vu` and `карта_реки` used to lose it. `snake_case` still keeps its underscore, and `_заметка_` still drops the emphasis marks.
- An example's sample speaker is not the character. A card named Jordan with you as Alex used to print Jordan on both sides. `{{random_user_1}}` in that sample is Riley, and it is still not you.
- A `<START>` or `END_OF_DIALOG` line at the end of the example text is not stored as the reply. `END_OF_DIALOG` between two exchanges starts the second one. `Bot:` is the character side. A `<START>` inside a sentence stays in the reply.

Phone: on the character list, a first line of `déjà_vu` should still show the underscore. Save example dialogs that end on `<START>` or `END_OF_DIALOG` (the marker should not be in the reply; a second exchange after `END_OF_DIALOG` should be its own example). A card named Jordan, with your name Alex, should not use Jordan as the sample speaker.

# Handoff (2026-10-03, Settings wave 34)

On `cursor/settings-wave-34-9e0e` (PR into `gradation/app-pass`). Settings follow-ups after an IPv6 local server with `@` or a space in the password, and Get location asking for precise and approximate together (#158). Not a redo of that pass. Not a redo of a hostname with `_` or a password containing `@`, the Models row hiding that password, port 0 and above 65535, a query or fragment swallowing `/v1`, or approximate location counting as granted (#149). Not a redo of an enabled tool turning off without the grant, cleartext to CGNAT 100.64/10 and IPv6 link-local or unique-local, or the Models row hiding userinfo when the host parsed (#140).

- Settings > Models: `http://10.0.0.23.:11434` saves. Java's parser reports a trailing dot on an IPv4 address as no host, so Save refused it and the row printed the whole URL. A zone id on a link-local address (`http://[fe80::1%wlan0]:11434` or `%25`) is refused. An IPv4-mapped address with a leading zero (`http://[::ffff:192.168.001.001]:11434`) is refused. The HTTP client cannot open either, and Save used to accept them. `[fe80::1]` and `[::ffff:192.168.1.1]` still save. A public literal is still refused.

Phone: set the local server to `http://10.0.0.23.:11434` (Save should accept it; the Models row should say `10.0.0.23.:11434`). Set it to `http://[fe80::1%wlan0]:11434` or `http://[::ffff:192.168.001.001]:11434` (Save should refuse). Set it to `http://[fe80::1]:11434` (Save should accept it).

# Handoff (2026-10-03, Notifications/Away wave 33)

On `cursor/notif-wave-33-a28d` (PR into `gradation/app-pass`). Notif/away follow-ups after a cleared shade posting again, cap order, and Speak utterance ids (#150). Not a redo of that pass, of the last-shade token, the 64-cap count, or in-chat Stop (#146). Not a redo of #160, #161, #159, #158, #156, #155, or #157. Not a redo of #139–#154.

- An approval whose request id contains ':' is not the same shade as a longer session id that shares that text. `ab` waiting on `cd:r2` used to replace `ab:cd` waiting on `r2`, and answering one cleared the other's open token.
- A shade the system drops without a swipe loses its open token when a later alert is handled, not only after a kill. The alert that was just posted again keeps the token already on that shade. Another shade for the same session still keeps it.
- Answer shade Speak puts Speak back when the reply strips to nothing. The engine never starts, so the shade used to stay on Stop.

Phone: Notify when away on, with sessions `ab` and `ab:cd` both waiting, and the shorter one's request id containing a colon (both shades should stay; Allow on the shorter one should leave the longer one openable). Clear one session's shade without swiping it, then finish a different session (the cleared tap should not open; the one still up should). Background Chat, and Speak a reply that is only blank lines (the shade should stay on Speak).

# Handoff (2026-10-03, Stability wave 33)

On `cursor/stability-wave-33-e76c` (PR into `gradation/app-pass`). Persistence follow-ups after a 0-byte recovered database is skipped, a same-stamp portrait replace keeps the new picture, and a long message slice keeps only the characters that step asked for (#154). Not a redo of that pass, or of the recovered file at the databases root, portrait and wallpaper side files moving with history, or emoji slices (#143). Not a redo of history tokens, file indents, or the staged photo from the tap (#161). Not a redo of a rolled-back character import, the last greeting in a file, or scaling an oversized portrait or wallpaper into the backup (#159). Not a redo of #158, #156, #155, or #157. Not a redo of #139–#154.

- Parking a 0-byte recovered database no longer renames the hold copy that still has bytes. That rename used a name Room does not open, so the launch read a stale vault copy or an empty file. An empty file already in the no-backup folder no longer blocks that hold copy from moving in. A recovered file that still has bytes at the databases root is still the one that opens.
- Deleting a portrait or a wallpaper removes `.bak` and `.partial` before the live file. A kill after the live name was removed used to leave the side file, and the next open put the picture back.
- A sliced message stops when the next character index would not fit in an `Int`. The step used to wrap to a negative index, SQLite then read from the end, and the tail was appended again.

Phone: delete a character portrait or wallpaper (it should stay gone). Not a phone check for an empty recovered name, or for a message long enough to wrap the slice index.

# Handoff (2026-10-03, Chat wave 33)

On `cursor/chat-wave-33-56da` (PR into `gradation/app-pass`). Chat follow-ups, rebased onto Import #159, Settings #158, Roleplay #156, Hub #155, and Code #157. Not a redo of #153 (history sentences, file fences, staged photos) or #139 (history preview keeping `#` `~` `>`, staged text files one by one and the 1 MB cap). Not a redo of #159 (a rolled-back character import does not apply its notes, the last copy in a file owns the greeting, an oversized portrait or wallpaper is scaled into the backup), #158 (IPv6 local server with `@` or a space in the password, Get location asking for precise and approximate together), #156 (card `<START>` exchanges, `{{bot}}`, `{{random_user_N}}` in an example), #155 (hub divider line, emoji cut, form-encoded pairing space, trailing `#note`), or #157 (config-option mode pill, Playwright browser lines). Not a redo of #152, #151, #150, #149, #147, or #148.

- History preview keeps a line that is only a hash or another long token. It used to be treated as a slice of a photo, so the row went blank. A data URL is still not a line, and neither is a bare slice of the photo bytes. A picture reply stored with the words in `body` still shows those words when the history read is cut inside that string.
- A text file keeps the indent on its first line. Trimming the whole file used to send a snippet or a patch as a different file. A file that is only whitespace is still empty. A code fence inside the file is still wrapped in a longer fence.
- Sending a staged photo keeps the file from the tap, and does not start the turn if another chat opened while the photo was encoded. The line stays on the chat that was on screen.

Phone: send a chat whose last line is a SHA-256 (History should show the hash, not a blank row). Attach a Python snippet whose first line is indented (the model should still see that indent). Stage a photo and open another chat while it is encoding (the picture should stay on the first chat).

# Handoff (2026-10-03, Import wave 33)

On `cursor/import-backup-pictures-b045` (PR into `gradation/app-pass`). Import/export follow-ups after #152. Not a redo of a lore pin staying on the chosen copy, a failed wallpaper clear retrying next launch, or trimmed names and a blank name not being imported. Not a redo of #144 (a waiting lore pin replacing the old pin, a failed portrait write being retried, duplicate lore names exporting the newest text and import updating every local row). Not a redo of #158, #156, #155, or #157. Not a redo of #139–#154.

- A character import writes its notes before the database commit. If that commit does not land, the next launch used to apply Memory and the pictures anyway, because the export key still matched the card that rolled back. The row's old stamp means the notes are dropped. A later edit still keeps a portrait that is waiting.
- A backup that lists the same character twice uses the last copy for the greeting. An earlier greeting used to count as a change after the last copy had put the old one back, so a rewritten opening was replaced.
- A portrait or wallpaper over the backup cap is scaled and carried. It used to be omitted, and the other phone kept the picture it already had. A torn file is still omitted.

Phone: import a character file that contains the same character twice, the second copy with the greeting already on the card, while a rewrite of the opening is showing (the rewrite should stay). Export a character whose portrait or wallpaper is a large photo (the other phone should get that picture, scaled). Not a phone check for a kill between the import note and the database commit.

# Handoff (2026-10-03, Settings wave 33)

On `cursor/settings-wave-33-1cff` (PR into `gradation/app-pass`). Settings follow-ups after #149, rebased onto #156 (which includes #155 and #157). Not a redo of a hostname with `_` or a password containing `@` on a host Java could still parse, the Models row hiding that password, port 0 and above 65535, a query or fragment swallowing `/v1`, or approximate location counting as granted (#149). Not a redo of an enabled tool turning off without the grant, cleartext to CGNAT 100.64/10 and IPv6 link-local or unique-local, or the Models row hiding userinfo when the host parsed (#140). Not a redo of card example exchanges split on `<START>`, `{{bot}}` as the character name, or `{{random_user_N}}` in an example (#156). Not a redo of a divider-only hub line, an emoji cut in half, a form-encoded pairing space, or a trailing `#note` after a bridge fragment (#155). Not a redo of the config-option mode pill or Playwright browser lines (#157).

- Settings > Models: `http://user:p@ss@[fd00::1]:11434` and `http://user:a b@10.0.0.23:11434` save. Java's parser throws on an IPv6 literal after a password that contains `@`, and on a space in the password, so Save said the URL was invalid. The row still shows host and port. An unbracketed `fd00::1` is still refused. Public literals are still refused.
- Settings > Tools: Get location requests precise and approximate together. A precise-only request is ignored on Android 12+, so the dialog never showed. With approximate location, the tool uses the network provider and keeps a coarse fix. GPS throws without precise location, and a 10 m wait never finishes on approximate.

Phone: save `http://user:p@ss@[fd00::1]:11434` (Save should accept it; the Models row should say `[fd00::1]:11434`, not the password). Turn Get location on and choose Approximate (the switch should stay on). Ask the model where you are (it should return a coarse fix, not say location was denied).

# Handoff (2026-10-03, RP wave 33)

On `cursor/rp-wave-33-af37` (PR into `gradation/app-pass`). Roleplay card parsing, lore keys, example dialogs, and prompts. Rebased onto `75503c7` (Hub #155, which includes Code #157). Not a redo of #148 (example sides with a space before the colon, the character speaking first, `Keys`/`KEYS` headers, Windows line endings, list marks `#` `~` `>`) or #142 (list underscores, the User side after a leading blank line, lore keys in `()` `（）` `【】`). Not a redo of #155 or #157. Not a redo of #139–#154.

- Example dialogs pasted from a card keep every exchange. A `<START>` line between them used to be stored as part of the first reply. `{{user}}`, `{{char}}`, `{{bot}}`, and `<USER>` / `<BOT>` are sides. A `<START>` inside a sentence stays in the reply. A later label still stays on the side that is already open.
- `{{bot}}` is the character's name in the prompt, the greeting, and a lore key. A blank character name still leaves the token in place, so the key is not wiped.
- `{{random_user_N}}` in an example is not the person in this chat. It used to avoid the stand-in instead of that person, so Alex and Jordan swapped into the sample.

Phone: paste example dialogs that use `<START>` and `{{user}}:` / `{{char}}:` and save (both exchanges should be there, and the prompt should not use your name for `{{random_user_1}}` when you are Alex or Jordan). A greeting written `{{bot}} smiles` should show the character's name. A lore key `{{bot}}` should stay out of the prompt until the chat says that name.

# Handoff (2026-10-03, Hub/Pair wave 33)

On `cursor/hub-wave33-fc72` (PR into `gradation/app-pass`). Hub shell and pairing follow-ups after the hub hero line and pairing query text (#147), rebased onto Code #157. Not a redo of that pass. Not a redo of character-list buttons following whether the list is the screen, a pairing link keeping `#` and query keys named token/auth/ws/fp, or a fingerprint with spaces (#141). Not a redo of example sides, lore headers, or list marks (#148). Not a redo of the config-option mode pill or Playwright browser lines (#157). Not a redo of #139–#154.

- The hub hero and the character list skip a first line that is only a divider. `---`, `===`, `- - -`, and `###` used to be the description, so the sentence under them never showed. `C#` on its own line still shows. A 140-character cut no longer ends on the first half of an emoji.
- A fully encoded pairing address is decoded as a form value, so a space that `URLEncoder` wrote as `+` is a space. The token decoder still keeps `+` as a plus, and that path used to save `hello+world`. A raw bridge query is still copied as written (`Session`, `%20`, `%2B`).
- A trailing `#note` is dropped even when the bridge address already contains `#`. The note used to stick to the token, or to the fingerprint so the pin was rejected. `#section` followed by the token is still part of the address.

Phone: open Hub on a character whose first line is `---` and the next is `She keeps the locket` (the hero and a fresh list row should say that sentence, not the dashes). A first line of 139 letters plus a gem emoji should not end on a broken character. Pair a link whose address was form-encoded with a space (`name=hello world` inside `url=`). Pair `gradation://pair?url=wss://h/v1?a=1#section&token=…&fp=…#note` (the saved host should keep `#section`, the token should not end in `#note`, and the pin should still be accepted).

# Handoff (2026-10-03, Code wave 33)

On `cursor/code-mode-wave33-f060` (PR into `gradation/app-pass`). Code-mode follow-ups after the browser line preferring typed text, the control, or a scroll over an element ref, a listed Ask staying Ask, and `session/new` / `session/load` reading `modes` (#151). Not a redo of that pass. Not a redo of Codex `read-only` / `auto` / `full-access`, OpenCode `build`, a skipped multi-select question, or browser profile start/stop on the execute card (#145).

- `session/new` and `session/load` read the mode select in `configOptions` (`category: "mode"`, or an uncategorized id / `configId` of `mode`) before the legacy `modes` object. A model row whose value looks like a mode is not the pill. An id this phone does not show does not fall through to `modes` or to the mode the phone just asked for. `config_option_update` moves the pill the same way. A snapshot with no mode select leaves it.
- Playwright names the element `target` instead of `ref`. A click or hover that only has `target` shows that ref. `startTarget` / `endTarget` draw the drag. `target` does not replace typed text, a selected value, or `down 500`, and a later update that only repeats it leaves that line. A git `ref` still wins when there is no browser action.
- `browser_fill_form` shows each field's text or value, not the field refs. `browser_handle_dialog` shows the prompt text, or accept / dismiss. `browser_resize` shows `width × height`. A shell command that also carries a width and height stays the command.

Phone: start a session whose `session/new` result has only a mode config option of `plan` (the pill should say Plan). Change mode from another client so the only update is `config_option_update` (the pill should follow). On a current Playwright browser, click a control that is only `target` (the line should be the ref), type into a field (the words, not the target), drag (the two targets), fill a form (the values), accept a prompt (the words), and resize (the two sizes).

# Handoff (2026-10-03, Stability wave 32)

On `cursor/stability-wave-32-5e40` (PR into `gradation/app-pass`). Persistence follow-ups after the recovered file at the databases root, portrait and wallpaper side files, and emoji slices (#143). Not a redo of that pass, of `user_version`, the live wal park, or `.stuck-N` (#132). Not a redo of #153 (history sentences, file fences, staged photos), #152 (lore pin on the chosen copy, wallpaper retry, trimmed names), #151 (browser action lines, session approval pill), #150 (cleared away shades, cap order, Speak utterance ids), #149 (homelab hostnames, LAN paths, approximate location), #147 (hub hero line, pairing query text), or #148 (example sides, lore headers, character-list marks).

- Room skips a 0-byte recovered database at the databases root and under hold. That name is left when an open dies before the header is written, and it used to hide the copy that still has bytes. A recovered file that still has bytes at the root is still the one that opens.
- A finished `.partial` replaces a portrait or wallpaper when the clock did not tick, so the stamps match. The old picture used to stay. Import notes are written to a new file before that file takes the side-file name, so a kill there no longer wipes the notes already on disk. Those notes are still read when the stamp matches the installed file.
- A sliced message keeps only the characters that step asked for. A longer slice used to be appended whole, and the next step repeated the overlap.

Phone: a portrait or wallpaper replace killed after the side file was written, when that file and the old picture show the same time (the new picture should be the one that shows). A long message should still open in full. Not a phone check for which file opens when the recovered name at the root is empty.

# Handoff (2026-10-03, Chat wave 32)

On `cursor/chat-wave-32-f2c6` (PR into `gradation/app-pass`). Chat follow-ups. Not a redo of #138 (calendar day buckets, Remove all clearing a parked file list) or #139 (history preview keeping `#` `~` `>`, staged text files one by one and the 1 MB cap). Not a redo of #152 (lore pin on the chosen copy, wallpaper retry, trimmed character names), #151 (browser action lines, session approval pill), #150 (cleared away shades, cap order, Speak utterance ids), #149 (homelab hostnames, LAN paths, approximate location), #147 (hub hero line, pairing query text), or #148 (example sides, lore headers, character-list marks).

- History preview keeps a sentence that starts with `data:` or mentions `base64,`. Those used to be treated as the photo payload, so the row went blank or said Photo. A search hit already on the first line stays at the start of the row. An underscore inside a word stays for letters beyond ASCII (`déjà_vu`).
- A text file whose body contains ``` is wrapped in a longer fence. A line break in the file name stays on the header line.
- Sending a staged photo does not delete the JPEG the message still shows. A new chat is not saved until the reply lands, so a database check alone removed the file. The caption and files are parked before the photo is encoded, so leaving during that send does not lose them.

Phone: send a chat whose last line is `data: the numbers` or `use base64, then stop` (History should show that sentence, not Photo or a blank row). Attach a markdown file that contains a code fence (the model should still see the whole file). Stage a photo, open History, send it, and leave before the reply finishes (the picture should still be on the message). Stage a photo with a caption and a file, rotate while it is sending (the caption and the file should still be there if the send did not go out).

# Handoff (2026-10-03, Import wave 32)

On `cursor/import-lore-pin-names-136c` (PR into `gradation/app-pass`). Import/export follow-ups after #144. Not a redo of a waiting lore pin replacing the old pin, a failed portrait write being retried, or duplicate lore names exporting the newest text and import updating every local row. Not a redo of #133 (last lore flag, pin clearing the backup name, missing portrait exported empty). Not a redo of #151 (browser action lines, session approval pill), #150 (dead shade re-alert, cap order, utterance id), #149 (homelab hosts, LAN paths, approximate location), #147 (hub first line, pairing query text), or #148 (example sides, lore headers, list marks).

- A character backup that names a lorebook leaves the pin on the row already chosen when that row's name matches. The newest other row with the same name used to take the pin, and its text replaced the book the character was using. A name that is still waiting attaches to the active copy, not a newer inactive one. Chats were already reading the active book while the pin waited.
- Clearing a wallpaper leaves the import note in place when the file is still there, and the next launch tries again. A side file that comes back counts as still there.
- A character name is stored trimmed. A blank name is not a character, so it is not imported and it is not counted as an update.

Phone: pin a character to an older World while a newer World with different text exists, export and import the character file (the pin should stay on the older text). With no pin, import a character that names World while the older World is the active one and a newer copy is off (the pin should land on the active text). Remove a character wallpaper and import; if that delete does not land, the next launch should remove the picture. Import a character named with spaces around the name, and one whose name is blank (one character, named without the spaces).

# Handoff (2026-10-03, Code wave 32)

On `cursor/code-mode-wave32-5f81` (PR into `gradation/app-pass`). Code-mode follow-ups after Codex `read-only` / `auto` / `full-access`, OpenCode `build`, skipping a multi-select question, and browser profile start/stop plus the action line when the element description is missing (#145). Not a redo of that pass, of cancelled todos, `_cursor/` methods, or mouse cards (#131). Not #139 through #146. Not a redo of #150 (a shade cleared without a swipe, shade-cap post order, a new Speak utterance id), #149 (homelab hostnames, a password that contains `@`, the appended path before a query, approximate location), #147 (hub first line, pairing query case and percent-encoding), or #148 (example sides with a space before the colon, Keys/KEYS lore headers, list marks `#` `~` `>`).

- A browser tool's `ref` was earlier in the detail keys than the control name, the typed text, and a scroll, so the line showed `e9` instead of `hello`, `Submit`, or `down 500`. A later update that only repeats the ref no longer replaces that line. A git `ref` still wins over `owner` when there is no browser action.
- A session list row that says `ask`, `default`, or `read-only` is an explicit Ask. Refresh used to treat it as omitted and put Auto-edit or Full auto back. A missing mode, or one this phone does not show, still leaves the local pill. A blank `permissionMode` falls through to `mode`.
- `session/new` and `session/load` read `modes.currentModeId` (then `modeId`, then a top-level mode). The pill follows the harness. An unknown id does not fall through to the mode the phone just asked for.

Phone: on Cursor Agent, type into a field (the line should be the words, not `e5`) and scroll (it should stay `down 500` when the tool finishes). A Codex session that moves to `read-only` should say Ask after the session list refreshes, not Full auto. Start a session whose `session/new` result says `plan` (the pill should say Plan).

# Handoff (2026-10-03, Notifications/Away wave 32)

On `cursor/notif-dead-shade-order-ac2a` (PR into `gradation/app-pass`). Notif/away follow-ups after the last-shade token, the 64-cap count, and in-chat Stop (#146). Not a redo of that pass, of session-id prefix, of the shade-cap token clear, or of the answer speak line (#134). Not a redo of #149, #147, or #148.

- A shade the system drops without a swipe (no DeleteIntent) no longer counts as still posted. The next approval or finished turn alerts again, on the same id. That dead shade's one-shot open token is dropped, including after a kill. A shade that is still up keeps its token, and clearing one session does not drop another's.
- The 64-entry cap stores each shade's age. Prefs iteration is not post order, so after a kill the cap could cancel a newer shade and leave the oldest. The oldest is still the one that goes.
- Answer shade Speak uses a new utterance id each time. A late end for the previous reading was the same id, so it put Speak back and stopped the engine while the next reading was still going.

Phone: Notify when away on, finish a turn, clear that notification without swiping it, then finish the turn again (the shade should come back; replaying the old tap should not open). Leave another session's shade up and clear only the first (the other still opens). Finish 64 turns, send a new prompt in each so the shades stay, kill the app, finish one more session (still 64, the first session gone, the one just before the new one still there). Background Chat, tap Speak, tap Stop, tap Speak again, and if the first reading's end still arrives the shade should stay on Stop until this reading ends.

# Handoff (2026-10-03, Settings wave 32)

On `cursor/settings-wave-32-0c71` (PR into `gradation/app-pass`). Settings follow-ups after #140. Not a redo of the pre-rename List/Read names, Create file, or the local-server slash and `/v1` strip (#136). Not a redo of an enabled tool turning off without the grant, cleartext to CGNAT 100.64/10 and IPv6 link-local or unique-local, or the Models row hiding userinfo when the host parsed (#140). Not a redo of #147 or #148.

- Settings > Models: a hostname with `_` (`my_nas.local`, `nas_1.home`) saves. A password that contains `@` saves, and the row shows host and port, not the password. Java's parser had reported both as "no host", and the row's fallback printed the raw URL. Port 0 and anything above 65535 are refused.
- The path the app appends (`/v1/models`, `/v1/chat/completions`, `/api/tags`, and the rest) is inserted before a query. A pasted `?` or `#` used to swallow that path, so the server saw the base and never the route. A trailing `/v1` in front of the query is still stripped. The fragment is dropped. The folder URI, the local server address, and the server type commit.
- Settings > Tools: Get location treats approximate location as granted. Android 12+ stores that as coarse only; the switch required fine and toasted after the user had allowed it. The tool itself already accepted coarse.

Phone: set the local server to `http://my_nas.local:11434` or `http://user:p@ss@10.0.0.23:11434` (Save should accept it; the Models row should not show the password). Set it to `http://10.0.0.23:11434/v1?token=abc` and load models (the request should be `http://10.0.0.23:11434/v1/models?token=abc`). Allow approximate location and turn Get location on (it should stay on, with no permission toast).

# Handoff (2026-10-03, Hub/Pair wave 32)

On `cursor/hub-wave32-021f` (PR into `gradation/app-pass`). Hub shell and pairing follow-ups, rebased onto #148. Not a redo of the Roleplay list surviving an Ask history open, unencoded `&` in a bridge address, or 401/403 as status codes. Not a redo of character-list buttons following whether the list is the screen, a pairing link keeping `#` and query keys named token/auth/ws/fp, or a fingerprint with spaces (#137, #141). Not a redo of example sides, lore headers, or list marks (#148).

- The hub hero uses the character list's first line. The old fold left `{{char}}` and `{{user}}` in place and joined every line of the field.
- Pairing parse copies a raw bridge query as written. Lowercasing the key and decoding `%20` or `%2B` while gluing changed the address the QR carried. A fully encoded `url=` value is still decoded once.

Phone: open Hub on a character whose first line is `{{char}} keeps the locket` (the hero should say the name, and not the next line). Pair a link whose address is `wss://h/v1?room=1&Session=Ab%2B1&name=hello%20world` (the saved host should keep `Session`, `%2B`, and `%20`).

# Handoff (2026-10-03, RP wave 32)

On `cursor/rp-wave-32-e558` (PR into `gradation/app-pass`). Roleplay follow-ups after #142. Not a redo of list lines keeping underscores, example dialogs keeping the User side on a leading blank line, or lore keys in `()` `（）` `【】`. Not a redo of bracketed scene notes in auto memory, Vesna edits across a stock refresh, or the active character id commit (#135).

- Example dialogs keep both sides when the label has a space before the colon, or when Char is written first. A later `User:` line inside the reply stays on the character side.
- A lore header still splits when it is written `Keys` or `KEYS`, and when the book uses Windows line endings. Those blocks used to be always on.
- Character list, character History, and the library card keep `#`, `~` and `>` inside the words. A first line that is only markdown marks no longer hides the tagline under it.

Phone: save example dialogs written as `User : hi` / `Char : hello`, and a block that starts with `Char:`. A lore book whose header is `[Keys: locket]` should stay out of the prompt until the chat says locket. On the character list, a last line of `C#` or `~/Downloads` should still show those characters, and a personality that starts with `***` should show the next line.

# Handoff (2026-10-03, Notifications/Away wave 31)

On `cursor/notif-shade-stop-0738` (PR into `gradation/app-pass`). Notif/away follow-ups after longest-id matching, the 64-cap token clear, and the speak line (#134). Not a redo of that pass or of the one-commit shade hold, dismissed open tokens, and in-chat Stop (#127). Not a redo of #144, #145, #141, #140, #143, #142, or #139.

- Allow, Deny, an in-app answer, or a cancelled turn clears the one-shot open token when that was the session's last shade. Programmatic cancel does not run the swipe handler, so the token used to keep opening the session. A second alert keeps it. `ab` does not clear `ab:cd`.
- The 64-entry cap counts a shade that is still up after the next prompt clears turn-done dedup. Counting only the dedup set let new prompts stack past 64. The oldest shade is cancelled and its token goes with it. A cold start counts parked shade ids too.
- In-chat Stop after the speak service has died flips the answer shade back to Speak (or drops it when Chat is in front). Copy or Dismiss, then the utterance ending, does not post the shade again. An interrupted reading (engine stop, not done) returns the shade to Speak.

Phone: Notify when away on, approve the only alert from the shade (or stop the turn), then replay that notification's open (it should not open). Leave a second approval up and Allow the first (the other still opens). Finish 64 turns, send a new prompt in each so the shades stay, then finish one more session (still 64 shades, the oldest gone). Background Chat, Speak from the shade, force-stop the speak service if you can leave the shade saying Stop, open Chat and tap Speak on the message (shade should say Speak). Copy the shade while it is speaking (it should stay gone).

# Handoff (2026-10-03, Import wave 31)

On `cursor/import-portrait-lore-pins-9abf` (PR into `gradation/app-pass`). Import/export follow-ups after #133. Not a redo of the last inactive lore copy, clearing a waiting pin when one is chosen, or exporting a missing portrait as empty. Not a redo of #125 (last character copy, inactive lore backups, cold-start pin linking). Not a redo of #145, #141, #140, #143, #142, or #139.

- A character backup whose lorebook is not on the phone yet clears the pin already stored. The name is kept until that book is imported. The next export still carries that name, and chats stop using the previous book.
- Replacing a portrait that fails to land (the new JPEG never takes the file name) leaves the import note in place and keeps the old picture for the next launch. A backup picture that does not decode is still left, and is not retried.
- Lore export writes one row per name: the newest text, on if any copy of that name was on. Import applies that row to every local book with the name, so an older duplicate does not keep the old text or stay on after the backup turned the book off.

Phone: pin a character to City, import a character backup that pins Forest before Forest exists (the character should not stay on City; exporting again should still say Forest). Import Forest (the pin should attach). Put a second lorebook named World next to one that is on, with older text, export and import (one World, the newer text, and it should still be on if either copy was). Import a character portrait over one the phone already has, after a failed replace (the old picture stays, and the next launch should take the new one).

# Handoff (2026-10-03, Code wave 31)

On `cursor/code-mode-wave31-814a` (PR into `gradation/app-pass`). Code-mode follow-ups after cancelled todos, `_cursor/` methods, and mouse execute cards (#131). Not a redo of that pass, of harness-id folding, of listSessions permissionMode aliases, or of BrowserMouseClickXy / BrowserCdp cards. Not #139 (history preview marks, staged-file cap), #140 (tool switches without a grant, local CGNAT and IPv6), #141 (character-list chrome, pairing `#` and field names), #142 (list underscores, example dialogs, wrapped lore keys), or #143 (recovered database at the root, side pictures, emoji slices).

- Codex ACP mode ids `read-only`, `auto`, and `full-access` (and `full_access`) set the approval pill. A live `current_mode_update` used to be ignored, and a listed session stayed on Ask. `auto` is workspace edits (Auto-edit), not Full auto; `read-only` still asks before an edit. OpenCode's default agent id `build` is Auto-edit. `auto-edit` is unchanged.
- `cursor/ask_question` with `allow_multiple` (or `allowMultiple`) is skipped, the same as several questions. It used to show one option and answer as a single choice.
- `BrowserProfileStart` and `BrowserProfileStop` use the execute card. A type, fill, or select that omits the element description shows `text`, `value`, or `values` (a whole-number double shows as `5`). A scroll with `direction` and `amount` shows `down 500` even when a `ref` is also set. `BrowserDrag` with `startRef` / `endRef` shows `e1 → e2`. A click that names the control still shows that name.

Phone: a Codex session in `auto` or `full-access` should not say Ask. A question that allows several answers should say it was skipped, not offer one button. On Cursor Agent, a profile start should show the execute icon; a scroll should show `down 500`; a drag between two refs should show `e1 → e2`.

# Handoff (2026-10-03, Hub/Pair wave 31)

On `cursor/hub-list-and-pairing-a23e` (PR into `gradation/app-pass`). Hub/mode and pairing follow-ups after #137. Not a redo of the Roleplay list surviving an Ask history open, unencoded `&` in a bridge address, or 401/403 as status codes. Not a redo of Continue/Start staying on the thread, Roleplay-off returning to Chat when idle, or sheet glass after the first layout (#129). Not a redo of history-preview marks or the staged-file cap (#139), of captionless Photo and underscores on the character list (#142), of the recovered-database open, side pictures, and emoji slices (#143), or of an enabled tool staying on and local IPv6/CGNAT (#140).

- Character-list chrome follows whether the list is the screen. The list view stays visible while a thread slides over it, so the chevron opened Settings and the new-chat icon opened the library. Long-press on Manage characters started a new chat and closed the list.
- Pairing parse no longer uses `java.net.URI` for the link. A `#` inside the bridge address is not an outer fragment, a query key named `token` / `auth` / `ws` / `fp` stays in the address when the real pairing field comes after it, and a fingerprint with spaces is not rejected as a bad link. A `#note` with no `&` after it is still dropped.

Phone: on the character list, long-press the top-right characters button (the list should stay; no new chat). Open a thread and tap the chevron while it is still sliding (it should return to the list, not open Settings). Pair a link whose address is `wss://h/v1?room=1&auth=session&b=2#section` with the token after that (the saved host should keep `auth`, `b`, and `#section`). A fingerprint written as `12 AD 50 …` should still pin.

# Handoff (2026-10-03, Settings wave 31)

On `cursor/settings-wave-31-6abb` (PR into `gradation/app-pass`). Settings follow-ups after #136. Not a redo of the pre-rename List/Read names, Create file staying off the folder grant, or the local-server slash and `/v1` strip. Not a redo of #128 (voice row, inference comma/non-finite values, timeout, max tokens, chat-memory presets). Not a redo of #139 (history-preview marks, staged-file cap), #142 (roleplay list lines, example sides, wrapped lore keys), or #143 (recovered database at the root, side pictures, emoji slices).

- Settings > Tools: a folder, location, or sound tool that is already on keeps a usable switch when the grant or runtime permission is missing, so it can be turned off. The model no longer keeps a tool the row cannot reach. Turning one on still needs the permission. Create file is still not a folder-grant tool.
- Settings > Models: cleartext HTTP accepts CGNAT 100.64/10, IPv6 link-local, and IPv6 unique-local (and `::ffff:` of a private IPv4). A public literal is still refused. The local-server row shows host and port; a pasted `user:password@` is not printed on the row.

Phone: turn List files on, then revoke the folder grant (or pick none) and open Tools again (the switch should still turn off, and the model should not get List files). Set the local server to `http://100.64.0.1:11434` or `http://[fd00::1]:11434` (Save should accept it). Paste `http://user:secret@10.0.0.23:11434` and look at the Models row (it should say `10.0.0.23:11434`, not the password).

# Handoff (2026-10-03, Stability wave 31)

On `cursor/stability-wave-31-4ec6` (PR into `gradation/app-pass`). Persistence follow-ups after user_version, the live wal park, and `.stuck-N` discard (#132). Not a redo of that pass or of the passphrase bind and the pre-ATTACH wal park (#130). Not a redo of #139 (history preview marks, staged-file cap) or #142 (list lines, example sides, wrapped lore keys):
- Room opens a recovered database that is still at the databases root, even when `chat_db_hold` already has that name. A failed park used to open the older hold copy and leave the current file at the root, where Auto Backup can upload it.
- Setting history aside moves `char_<id>.jpg.bak` and `.partial` with the portrait and the wallpaper. `ScenePhoto.recover` puts a finished side file back on the live name, so the next character with the reused id showed the old picture. The app background's `photo.jpg` side file stays.
- A sliced message read counts Unicode code points, the same unit as SQLite `length` / `substr`. A short slice of emoji used to look complete (`String.length` is two units per emoji) and the next step skipped the gap.

Phone: force a recovery (wrong passphrase, or a database that will not open) while a character portrait replace was killed after the side file was written (the new character must not show that portrait). A chat whose text is mostly emoji should still open. Not a phone check for the recovered-name order.

# Handoff (2026-10-03, RP wave 31)

On `cursor/rp-wave-31-bab7` (PR into `gradation/app-pass`). Roleplay follow-ups after #135. Not a redo of bracketed scene notes in auto memory, Vesna edits across a stock refresh, or the active character id commit. Not #123 (torn photoUri, Continue lore focus, persona Save commits). Not a redo of #139 (history preview marks, staged-file cap).

- Character list and character History previews keep an underscore inside a word. A photo with no caption is Photo, not "No messages yet". "You:" stays on a caption. The library card uses the same first line, and a markdown mark does not leave a leading space.
- Example dialogs keep the User side when the block has a leading blank line. `---` still splits blocks on a Windows break or with spaces around the dashes. A fullwidth colon after User or Char still labels the side.
- Lore keys in `()`, `（）`, or `【】` match the word inside. `；` and `｜` separate keys.

Phone: on the character list, a last line that is only a photo should say Photo, and a line with snake_case should still contain the underscore. Save example dialogs that start with a blank line (the User side should still be there). A lore key written as `(locket)` or `docks；pier` should still fire.

# Handoff (2026-10-03, Chat wave 31)

On `cursor/chat-wave-31-77b7` (PR into `gradation/app-pass`). Chat follow-ups. Not a redo of #138 (calendar day buckets, Remove all clearing a parked file list) or #126 (first save keeps the rekeyed JPEG).

- History preview only strips markdown marks. A `#`, `~` or `>` inside the words stays, so `C#`, `~/Downloads` and `a > b` still show on the row and can be the bold match. Heading hashes, a blockquote `>` and paired `~~` still come off.
- Several text files picked together are added one after another, and each one is measured against the total already staged (live list, or the park map when the read finishes under Roleplay or Code). A file past 1 MB stops being read instead of being loaded and then refused.

Phone: send a chat whose last line is `C#` or `~/Downloads`, open History (the row should still show those characters). Attach four text files of about 800 KB each (the fourth should be refused, and the first three should stay). Attach one file larger than 1 MB (refused, without the app stalling on the read).

# Handoff (2026-10-03, Chat wave 30)

On `gradation/w30-chat` (PR into `gradation/app-pass`). Chat/History follow-ups. Not a redo of #126 (first save keeps the rekeyed JPEG) or #116 (parked Ask caption promote).

- History day sections and the time/weekday label use calendar midnights. A 24-hour step mis-filed the first hour of yesterday after a DST fallback (This week, weekday instead of the time) and the last hour of the day before yesterday after a spring-forward (Yesterday). The week edge is six calendar days, not 144 hours.
- Remove all on attached files writes the live stage back with an empty file list (`ComposerStaged.dropFiles`). A list already parked for History or Code used to survive, because the next park skips an empty live stage, so the row still said "1 file" and the files returned.

Phone: on a device set to a DST zone, the day after the clocks fall back, a chat from 12:30 a.m. yesterday is under Yesterday and shows the time, not a weekday. Attach a file, open History, close it, Remove all (the row no longer says the file is waiting; leave and come back and it stays gone). A photo staged with the file stays.

# Handoff (2026-10-03, Hub/Pair wave 30)

On `gradation/w30-hub` (PR into `gradation/app-pass`). Hub/mode and pairing follow-ups after #129. Not a redo of Continue/Start staying on the Roleplay thread, Start-while-Roleplay suppress, Roleplay-off-during-reply returning to Chat, or sheet glass reapplying after the first layout. Not #119 (Roleplay tab pin, pair pending lock, topOnly outlines). Not a redo of Code #131, Stability #132, Import #133, or Notifications #134.
- History `loadChat` only signals a thread open when a Roleplay row is actually applied. An Ask open used to close the character list before leaving Roleplay could remember it, so the next Roleplay visit skipped the list. A load that bails (reply in flight, missing row) no longer leaves that suppress stuck.
- Pairing parse keeps an unencoded `&` inside the bridge address until the next known key (`token` / `fp` / aliases), so `wss://host/v1?a=1&b=2` is the address and not a truncated URL plus a stray parameter.
- Pair connect treats 401 and 403 as their own status numbers. A socket error that only contains those digits (port 4010, "4012 ms") stays unreachable.

Phone: on the Roleplay character list, open History and an Ask chat, then switch back to Roleplay (the list should be there). Open History while a reply is still streaming so the open does not land, then open Roleplay (the list should still resume). Pair with a QR whose address is `wss://host/v1?a=1&b=2` (the saved host should keep `b=2`). Fail a connect to port 4010 (the message should be unreachable, not a bad token).

# Handoff (2026-10-03, Notifications/Away wave 30)

On `gradation/w30-notif` (PR into `gradation/app-pass`). Notif/away follow-ups after the one-commit shade hold, dismissed open tokens, and in-chat Stop (#127). Not a redo of that pass or of #120:
- Cancel and "does this session still have a shade" use the longest known session id. A prefix match treated `approval:ab:cd:…` as session `ab`, so finishing or forgetting `ab` cleared `ab:cd`'s approval, and swiping `ab`'s last alert left its open token while `ab:cd` was still up.
- Evicting the oldest away shade (64-entry cap) cancels it without a DeleteIntent, so the one-shot open token is cleared when that was the session's last alert.
- Several once-reject options are not one Deny on the shade (same as several once-allows).
- A streamed tool handoff does not overwrite the follow-up's Speak/Copy line with the preamble, and errors do not replace that line (they still do not post a shade). `saveLastAiResponseForChannel` commits.

Phone: Notify when away on, with two sessions whose ids share a prefix (`ab` and `ab:cd`) both waiting on approval. Finish or forget `ab` (the other approval stays). Swipe `ab`'s only alert and replay its open (it should not open; `ab:cd` still should). Background a chat whose reply calls a tool (Speak should read the finished answer, not the "I'll check" line). Force-stop right after an answer shade appears, then Speak (it should read that answer).
# Handoff (2026-10-03, Settings wave 30)

On `gradation/w30-settings` (PR into `gradation/app-pass`). Settings follow-ups after #128. Not a redo of the voice row, inference comma/non-finite values, timeout 1–45, max tokens 1–999999, or chat-memory exact presets. Not a redo of #121 (pref commits, the Power tools switch, exact chat-text tiles, Voice chips while off, reasoning effort vs the token budget). Not a redo of #131, #132, or #133.
- Settings > Tools: turning List files or Read file off also drops the pre-rename names (`list_grokion_files`, `list_oxproxion_files`, `read_grokion_file`, `read_oxproxion_file`). The row and the request treated those as still on, so the switch came back. Create file is not a folder-grant tool (MediaStore into Download/gradation); the switch is no longer disabled until a tree is picked. Delete, list, read, open, edit, and copy still need that grant.
- Settings > Models: the local server address is the base the app appends `/v1` to. A trailing slash or a pasted `/v1` is stripped on save and on read, so an older `http://10.0.0.23:11434/v1/` still requests `/v1/chat/completions` once.

Phone: with an old file-tool name still stored, open Settings > Advanced > Tools and turn List files off (it should stay off, and the model should not get that tool). Turn Create file on without picking a folder. Set the local server to `http://10.0.0.23:11434/v1/` and load models (the request should be `http://10.0.0.23:11434/v1/models`, not `/v1/v1`).
# Handoff (2026-10-03, RP wave 30)

On `gradation/w30-rp` (PR into `gradation/app-pass`). Roleplay follow-ups after #123. Not a redo of torn photoUri, Continue lore focus, or persona Save commits, and not #117 (Continue swipe alts, portrait side files, regen lore focus):
- Auto memory treats a scene-note or rewrite echo in any bracket the reply cleaner already strips as machinery, not only the ASCII wrapper. The story after that echo stays. A character's own `(OOC: …)` line stays.
- A Vesna row with no seed flag repairs the flag, so deleting her does not bring her back on the next launch. Stock refresh updates fields that still match the previous seed and leaves a renamed card, a rewritten greeting, a changed scenario, and the portrait uri.
- The active character id commits. The demo seed flag and stock-avatar revision commit.

Phone: send a reply that is only `（Scene note…）` or a fullwidth rewrite echo, then let auto memory run (facts should not contain the note). Edit Vesna's greeting on a card that still has the old personality (the greeting should stay; only fields that still match the previous seed refresh). Delete Vesna and relaunch (she stays gone). Open a character and force-stop (that character should still be the one that opens).

# Handoff (2026-10-03, Import wave 30)

On `gradation/w30-import` (PR into `gradation/app-pass`). Import/export follow-ups after #125. Not a redo of the last character copy, inactive lore backups (nothing in the file marked active), or cold-start pin linking. Not a redo of #122 (notes after a later save, wallpaper prepare, torn portrait export):
- A lorebook file that names one book twice keeps the last copy's active flag. An earlier active copy no longer leaves the book on, and it no longer turns off a different book that was already active.
- Picking a lore pin, including "use whichever is active", clears the name a character backup was waiting to attach. The next launch no longer replaces that choice when the book shows up.
- A character with no portrait exports an empty picture, and import removes the old file and `photoUri`. A torn portrait still exports nothing (null), so the other phone keeps its picture. Older backups that omit the field still leave the local portrait.

Phone: import a lore file that lists the same book twice, active then off, onto a phone that already has a different book active (the duplicate should end off, and the other book should stay active). Pin a character to a book while a backup is still waiting on a different name, then import that book (the pin you chose should stay). Remove a portrait, export, import over the same character (the picture should be gone). A half-written portrait file should still not replace the picture on the other phone.

# Handoff (2026-10-03, Stability wave 30)

On `gradation/w30-stability` (PR into `gradation/app-pass`). Persistence follow-ups after the passphrase bind and the pre-ATTACH wal park (#130). Not a redo of that pass or of #118 (sidecar temps and leftover encrypting after `encrypt_ok`):
- `sqlcipher_export` does not copy `PRAGMA user_version`. The export now sets `PRAGMA encrypted.user_version` from the plaintext file before DETACH. Left at 0, Room runs onCreate and skips migrations, so a plaintext database from before the current schema never gains columns and the next open sets the history aside.
- After the encrypted main is installed, a plaintext `-wal`/`-shm`/`-journal` still on the live name is deleted or moved into the no-backup vault as `name.stuck-N`. If it cannot be moved, the plaintext snapshot is put back and the ciphertext is not left in place. SQLite would otherwise replay that wal into the new file.
- Once `encrypt_ok` is set, `name.stuck-N` renames of `pre_sqlcipher` / `encrypting` (and a parked live wal/shm/journal) are discarded in the vault, at the databases root, and under hold. A non-empty directory with that name keeps the marker for a retry. Recovered `.stuck-*` names stay.

Phone: a plaintext chat database whose Room version is older than this build should open with history intact (not the set-aside notice, and not a missing-column crash). Leave a non-empty directory named `chat_database-wal` beside the live database and a `chat_database.encrypting-wal.stuck-1` file in the no-backup chat-db folder, then relaunch after a successful encrypt (the live wal name should be gone, and the stuck rename should be gone once the encrypted file has opened).


# Handoff (2026-10-03, Code wave 30)

On `gradation/w30-code` (PR into `gradation/app-pass`). Code-mode follow-ups after harness-id folding, session mode aliases, and click/CDP cards (#124). Not a redo of that pass, and not #115 (gitStatus / listSessions / sessionStatus doubles, or the earlier browser card names):
- A Cursor todo with status `cancelled` / `canceled` stays cancelled (struck on the plan card) and a request-shaped `cursor/update_todos` echoes `cancelled`, not `pending`. `success` still counts as completed. `create_plan` todos that exist only inside `phases` still show.
- Extension methods prefixed `_cursor/` (`_cursor/ask_question`) use the same path as `cursor/`. `cursor/task` and `cursor/generate_image` that arrive with an id are acknowledged (`completed` / `generated`) instead of JSON-RPC "Method not found". A notification with no id still needs no reply.
- `BrowserMouseMoveXy`, `BrowserMouseDragXy`, `BrowserMouseDown`, `BrowserMouseUp`, and `BrowserMouseWheel` use the execute card. A drag with `startX`/`endX` (including `10.0`) shows `10, 20 → 30, 40`. A wheel shows `deltaX, deltaY`. A mouse-down shows `button`.

Phone: cancel a todo (the row should be struck, not still pending). On Cursor Agent, a drag should show the execute icon and `10, 20 → 30, 40`. An older agent that sends `_cursor/ask_question` should still show the question, not fail the method.


# Handoff (2026-10-03, Stability wave 29)

On `gradation/w29-stability` (PR into `gradation/app-pass`). Persistence follow-ups after w28 encrypt sidecar temps / encrypt_ok discard (#118). Not a redo of that pass, and not #123–#129:
- Plaintext-to-SQLCipher export binds the passphrase bytes on ATTACH. Room opens with `sqlite3_key` of those bytes (PBKDF2). The export used to write the raw-key literal `x'hex'`, so the migrated file opened as not a database and recovery set the history aside.
- Before ATTACH, a leftover `encrypting` / `pre_sqlcipher` wal that cannot be deleted (non-empty directory) is renamed off the SQLite name. If it still cannot be moved, the plaintext snapshot stays and the export does not start. Throwing would run recovery and could replace the live file with the snapshot.
- Auto Backup and device transfer exclude `ForegroundServiceAnswer.xml` (answer shade title and the speaking flag). `code_away_shade_hold.xml` is already excluded by the notifications pass.

Phone: a plaintext chat database on first encrypt after upgrade should open, not show the history-set-aside notice. Leave a non-empty directory named `chat_database.encrypting-wal` next to `chat_database.pre_sqlcipher` in the no-backup chat-db folder and relaunch (the snapshot should remain only if that directory cannot be moved; otherwise the wal name should be gone before export). A cloud backup should not include the answer speaking flag (shade-hold ids stay out via the notifications exclusion).

# Handoff (2026-10-03, Hub/Pair/Glass wave 29)

On `gradation/w29-hub` (PR into `gradation/app-pass`). Hub/mode and sheet-glass follow-ups after #119, unit-tested (`HubModeTest`, `GlassDrawableOutlineTest`). Not a redo of the Roleplay tab pin, the pair pending lock, or topOnly outlines:
- Hub Continue / Start chat keep the character list closed when setChatMode(RP) lands later, so a list you had resumed does not cover the thread. Start chat while already in Roleplay does not leave that suppress stuck (the mode observer never clears it).
- Settings turning Roleplay off during a reply still returns to Chat when the reply goes idle. The tab already hid; the flip used to be skipped for good.
- Bottom-sheet glass is applied again after BottomSheetBehavior's first layout, which replaces the container background with a MaterialShapeDrawable and used to leave the sheet with neither glass.

Phone: on Ask, leave Roleplay from the character list, open Hub, Continue (the thread, not the list). Start a new chat from the library while already in Roleplay, switch to Ask and back (the list can still resume). Turn Roleplay off in Settings while a reply is streaming (Chat returns when it finishes). History ⋮ sheet stays glass after it settles, not a flat transparent card.
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
