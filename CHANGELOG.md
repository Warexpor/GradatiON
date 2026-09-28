# Changelog

## Unreleased — monochrome redesign

### Changed
- Roleplay no longer has a response-language setting. Replies follow the conversation instead of being forced into English, Russian, or Chinese.
- Install id is `io.github.warexpor.gradation` (dev builds: `io.github.warexpor.gradation.dev`). Older `grokion` installs stay as they are and do not update into this id. Dev builds stay signed with the committed GradatiON Dev key.
- Typography: Plus Jakarta Sans across the app, a serif-italic GradatiON wordmark in History, and larger semibold labels on big buttons and dialog actions. Back, chevron and close icons are redrawn as rounded iOS-style strokes.
- Glass everywhere: every dialog, bottom sheet, context menu, dropdown and settings subpage now uses the liquid-glass style; toolbar back and action buttons are glass capsules that spring under the finger.
- Palette one step dimmer and darker (dark base #111111, light #F1F1F1), still strictly neutral.
- Liquid glass: top bar controls are now floating glass buttons and chips over a soft scroll-edge fade, and glass bends content at its rim with a specular edge (Android 13+). Touching glass springs it up, leans it toward your finger and lights it from the touch point. Battery saver and low-RAM devices get a solid frosted fill instead of live blur.
- Glass: the transcript scrolls beneath a live-blurred top bar and a floating glass composer; the Controls panel is the same material, and dialogs and sheets frost the screen behind them.
- Palette moves off pure black to a charcoal base (and a soft paper base in light) with a finer stepped gray ramp and lower overall contrast.
- Streaming reveals text with a soft per-word fade at the live edge, a breathing dot marks it, and the view follows the reply unless you scroll away. "Thinking" is a shimmering label.
- Markdown: code blocks are rounded cards with a language header (tap to copy), inline code is a pill, headings are calmer and paragraph spacing tighter.
- Motion: the Controls panel rises on a spring with its rows cascading in, sent messages rise from the composer, presses sink and settle, dialogs enter like iOS alerts, and screens push in from the edge over a parallaxed, dimmed previous screen. Reasoning expands and collapses smoothly.
- Whole UI reworked to a minimal, iOS-style monochrome look: system grays, soft gradients on the composer, bubbles and chips, no gold accent and no glow. Light and dark now share one theme definition.
- Inter 4 in four weights with a proper type scale; titles use semibold, the History wordmark is a large title.
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

## Unreleased — upstream sync (oxproxion v2.1.103 to v2.2.5)

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
