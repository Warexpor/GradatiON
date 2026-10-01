# Design pass backlog

Voice-dictated list from the user (2026-09-27), as read back and agreed. "Full pass,
nothing deferred, everything verified, then build and send the dev APK."
Tick items off here as they land so a fresh session can pick up.

## Quick fixes
- [x] 1. Menus above the chat must stretch to the right edge like the left: chat model
      picker, roleplay characters, code mode harnesses and projects, and the rest.
- [x] 2. Appearance tab: theme picker drifts to the right. Re-center.
- [x] 3. Glass theme-mode picker has a light "cloud" leaking inside the button. Remove
      only that highlight, keep the glass.
- [x] 4. Composer buttons (tools, files/photos/camera, model picker, send) get the same
      glass as the chat settings button.
- [x] 5. Code mode: new harness sessions default to full auto, not ask-first.

## Features and redesigns
- [x] 6. Main screen shows only Chat and Code by default. Settings: Roleplay can be
      enabled, Code can be disabled.
- [x] 7. Chat settings sheet: mostly clutter. Replace with a normal model picker and a
      reasoning-effort picker. Other options move behind an "Advanced" row (not
      deleted). Redraw the streaming icon.
- [x] 8. Redesign the toggles (GlassSwitch).
- [x] 9. Backgrounds: remove Grain. Make Adaptive follow the wallpaper's tones (neutral
      grays only). Add custom wallpaper from the gallery with optional overlays: blur,
      dim, gradient tint, liquid. Fix backgrounds not showing in chat and other
      screens. 12-15fps, pause offscreen.
- [x] 10. Swipe between chat, roleplay and history: lower threshold, and the next screen
      follows the finger while dragging instead of the current one cutting off.
- [x] 11. Code mode: real harness logos next to each harness.
- [x] 12. Polish pass by screenshots: spacing, scale, glass, weak icons, conversation
      menu (roleplay and code entries too).

## Verification
- Robolectric screenshots for every visual change; release-like `assembleDev` build.
- Motion (swipe, glass, backgrounds) still needs a real phone check.

# Pass 2 (2026-09-27, after testing the design-pass APK)

Voice-dictated. My reading is in brackets where the wording was unclear. The user said: "take full control of the
app, drive it to the best direction you think it is."

## Voice (done)
- [x] Voice input back: tap, talk, tap check, pastes and never sends. OpenRouter/Local fallback kept.
- [x] Grok STT engine: Settings > Voice > Grok posts Opus to `api.x.ai/v1/stt` (grok-voice-transcribe-2.0)
      with an encrypted xAI API key on that same screen.

## Bugs
- [x] B1. Mode switch (tap or swipe) flashes a black transition before the next screen. It should go
      straight to the next screen.
- [x] B2. Settings: going between sections and in/out of the models list can glitch heavily and "sink too
      deep" [the back stack grows or pages stack up]. The Chat/RP/Code tab underline can end up in the wrong place.
- [x] B3. Opening Settings briefly flashes the conversation list underneath.
- [x] B4. Status-bar strip (the black bar under the phone's top bar) should blend with the app's
      background, including the chosen background style.
- [x] B5. The send button in Chat/RP sometimes renders smaller than intended until you type (a stale
      scale from the pop animation).
- [x] B6. The text cursor is too thick. Use a thin, default-width caret.
- [x] B7. Sending with no model or key set does nothing silently. Say what's missing and where to fix it.

## Toggles (top priority for feel)
- [x] T1. Rounder capsule, a full-circle knob, glassy (not solid or metal). No color flash on press.

## Chat
- [x] C1. The streaming reveal is too abrupt, especially in Code. Make it graceful, like Claude's apps.
- [x] C2. Demo model: a built-in model that streams varied sample replies (markdown, code, lists, a table,
      thinking) at a medium speed, so the chat can be tried without any API key.
- [x] C3. Chat text scale option (smaller/larger) in Settings.
- [x] C4. Message action buttons (copy, etc. on user and bot messages): smaller, and not in a rounded pill.

## Code mode
- [x] K1. Rework the session transcript to feel like Claude Code: less clutter, with Normal and Thinking
      verbosity levels.

## Roleplay
- [x] R1. Revisit the RP harness (prompt assembly, character/persona/lorebook handling) and make it better.
      Chat harness too where it helps.
- [x] R2. RP UI polish. Done in the 2026-09-30 passes: a full-screen page behind every panel tile, a shared page kit (`RpPageKit`), drawn tile art, persona and character avatar picker with crop, History page, persona off switch, and the sheet-drop fix.
- [x] R3. From the user's RP references (layout and feel only, never visuals):
      - A "continue" / fast-forward button: the AI writes the next beat without a user message,
        to drive the story. It has to fit our composer somehow.
      - A character panel (bottom sheet from the character name) with tiles: Memory, History
        (sessions by date), Voice, Layout, Wallpaper (per-character background), Chat style and
        Persona. The harness (prompt assembly, memory) matters most.
      Done: Memory note per character, card macros, scene-craft rules, Continue on the empty
      send button, panel with titled glass cards. Voice (system TTS voice, pitch, speed),
      Layout (Classic, Bubbles, Book) and per-character Wallpaper are in too. Auto memory is in too
      (RpAutoMemory; switch in RP settings).

## Repo
- [x] P1. New screenshots, a fresh README description of the app, design docs and agent instructions.

## Decided
- Code syntax highlighting stays colored, and diffs are colored too (done: green/red lines, markers,
  counts).
