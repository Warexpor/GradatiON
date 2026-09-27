# GradatiON design contract

This is the source of truth for how GradatiON looks and moves. It replaces the older
Grok-derived token extraction and `SHELL.md`, which are both still in git history. Rules marked
**hard** are not up for taste calls.

## Principles

1. **Monochrome.** (hard) Use only pure neutral grays, with R, G and B equal. There's no accent
   hue, no blue tint, no red or green for errors or deletes, no solid white buttons and nothing
   glowy.
   - Exception: code syntax highlighting and diffs keep their colors (git green and red).
2. **Liquid Glass everywhere.** Dialogs, sheets, menus, pills, toggles and bars are all glass.
   Nothing is a flat card sitting on a flat page.
3. **Quiet by default.** Chrome recedes and content leads. Status appears only when it carries
   meaning: a spinner while working, a mark only on failure.
4. **Performance is a feature.** (hard) Use few blur layers, pause animations offscreen, and run
   backgrounds at 12 to 15 fps.
5. **Reference apps inform layout and feel, never visuals.** Grok, Claude and c.ai screenshots
   are used for structure only.

## Palette

| Token | Dark | Light | Use |
|---|---|---|---|
| `xai_canvas` | `#111111` | `#E8E8E8` (dimmed off-white) | Screen background |
| `xai_ink` | `#ECECEC` | `#1B1B1B` | Primary text, icons |
| `xai_body` | `#D9D9D9` | `#272727` | Long-form text |
| `xai_mute` | `#8A8A8A` | `#808080` | Secondary text, idle icons |
| `xai_hairline` | white 12% | black 9% | Strokes, separators |
| `glass_bar_tint` | `#111111` @ 78% | `#E8E8E8` @ 82% | Top bar, composer |
| `glass_sheet_tint` | `#1A1A1A` @ 85% | `#F1F1F1` @ 85% | Sheets, dialogs, notices |
| `glass_control_tint` | `#1E1E1E` @ 58% | `#EEEEEE` @ 62% | Buttons, tiles, chips |

Color resources live in `values/colors.xml` and `values-night/colors.xml`. Code colors live in
`code_colors.xml`, and diffs use `code_diff_*`.

## Glass

`GlassQuality.level` picks one of three tiers at runtime:

| Tier | When | Look |
|---|---|---|
| `LIQUID` | Android 13+ | Live blur, plus an AGSL lens that bends the backdrop at the rim |
| `BLUR` | Android 12 | Live blur, with the rim drawn on the canvas |
| `SOLID` | Battery saver or a low-RAM phone | Frosted opaque fill, no sampling |

Build surfaces from `GlassDrawable` (`sheet()`, `control()`, or a custom radius) and the
`Glass*Layout` hosts in `Glass.kt`.
- Selected state crossfades to a brighter tint (`selectedTint`). It never inverts into a solid slab.
- `GlassSwitch` is a 52×32 capsule with a 26dp glass bead. The bead never changes color; the track says on or off.
- `GlassNotice` is the one allowed interruption: a pill under the top bar that explains why an action did nothing.
  - Toasts are silenced app-wide (`AppToast` is a no-op), so use `GlassNotice` whenever silence would read as a broken button.

## Type

- UI text is Plus Jakarta Sans (`@font/app_sans`: 400 to 800). Titles use 600.
- The wordmark is Michroma.
- Code is Atkinson Hyperlegible Mono (`TextAppearance.Gradation.Code.Mono`).
- (hard) Nothing tappable is under 13sp.
- (hard) Vertical centering is fixed once in the bundled font files' metrics, never with per-view padding.
- Chat text scales with Appearance > Chat text: S, M, L, XL = 90, 100, 115, 130%.

## Shape and spacing

- Spacing is on a 4dp base. Screen gutters are 16dp.
- Radii:
  - Buttons, chips and the composer are capsules.
  - Tiles and cards are 18 to 22dp.
  - Sheets are 28dp, with a 36×4 grabber.
  - Code blocks are 12dp.
- Touch targets are at least 44dp. Message actions are bare 32dp icons, with no capsule.

## Motion

Curves live in `Motion.kt`:

| Name | Use |
|---|---|
| `iosOut` | Things arriving |
| `iosPush` | Screens pushing over |
| `easeOut` | Fades |
| `spring` / `springBouncy` | Knobs, the send pop, the mic swell |

Behavior:
- The mode pager snapshots the page, switches modes behind the snapshot, and slides both
  together, so the gap is never visible. Taps and swipes share this path.
- Stack animations keep the outgoing screen opaque, and screens opened from History push in.
  `MainActivity` holds touches for the length of each transition.
- Streaming text eases in by ceil(backlog/24) characters per frame, with no cursor glyph.
  Chat and Code share this pacing.
- Every animation checks `Motion.areAnimationsEnabled` and snaps into place when it's off.
- Backgrounds (`AmbientBackgroundView`: Off, Drift, Flow, Adaptive, Photo) run at 12 to 14 fps
  and pause while scrolling and offscreen.

## Components

**Top bar.** A glass menu button, the mode tabs (Chat, Roleplay, Code) with a springing
underline, and a glass new-chat button. The app is edge to edge: the backdrop runs under the
status bar, and the bars are inset by hand (`setupEdgeToEdge`).

**Composer.** A glass capsule holding the input, then a row with:
- `+` for attachments in Chat, or scene tools in Roleplay.
- The sliders button, which opens the chat settings sheet: model, reasoning, search, stream.
- The model pill, which in Roleplay is the character pill.
- The mic and send buttons.

The send button changes with context:
- It's dim while there's nothing to send.
- It pops when there is something.
- It becomes stop while a reply is streaming.
- On an empty Roleplay composer it becomes Continue (»).

**Voice.** Tap the mic, talk, and tap the check when you're done. The words paste at the caret
and never auto-send, and there's no hold-to-talk. While listening:
- A waveform replaces the model pill.
- Words still being recognized show in `xai_mute` until they settle.

**Transcript.**
- User turns are glass bubbles on the right.
- Assistant replies have no bubble.
- Reasoning folds into a "Thinking" row.
- Roleplay adds a speaker line (avatar and name) and swipe navigation between alternate replies.

**Code transcript.**
- Each tool call is one line: a kind glyph, the verb in semibold, and a mono argument, with a
  spinner while running and a mark only on failure. The output hangs underneath on tap.
- Diffs are cards with colored lines and counts.
- Approvals are glass cards with capsule choices.
- The session menu switches between Normal view (folded) and Thinking view (every thought and
  output open).

**Character panel.** A glass sheet with:
- A header: round avatar, name, a one-line description, and New chat and Switch as round glass buttons.
- A 3-column grid of titled cards: Memory, History, Persona, Style, Lore, Edit.
- Cards show live content when there is some (the memory text, the persona name). Otherwise
  they show a large glyph in `xai_mute`.

**Sheets, popovers, dialogs.**
- Sheets and dialogs are glass and blur the screen behind them.
- Pickers use `PickerPopover` (rows with an icon, title and subtitle, plus a footer).
- Confirmations use `GrokConfirmDialog`. Text input uses `GrokInputDialog`, which supports
  multiline for Memory.

## Screens

| Screen | Host |
|---|---|
| Chat / Roleplay | `ChatFragment` (modes via `ChatViewModel.chatMode`) |
| Code | `code/CodeHomeFragment`, `code/CodeSessionFragment`, diff and tool-output screens |
| History | `SavedChatsFragment`, embedded as a slide-over |
| Settings | `SettingsFragment`, then `SettingsDetailFragment` sections |
| Roleplay library | `RpHubFragment`, then characters, personas, lorebooks, RP settings |

## Checking a change

- Run the screenshot tests and look at `app/build/screenshots` in dark and light.
- Motion can't be judged from screenshots. Anything animated needs a look on a real phone.
