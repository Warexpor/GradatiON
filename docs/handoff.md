# Handoff (2026-09-27)

Read `CLAUDE.md`, then `AGENTS.md` (code map, test gotchas) and `docs/backlog.md`. Branch
`liquid-glass-redesign`. The full suite (426 tests) passed at b9789d9. Dev APK `GradatiON-dev-b9789d9.apk` was sent to the user.

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
- RP auto memory is done: after a reply, once the chat nears the API window (or 60 messages when
  memory is "All"), a quiet non-streamed call (`LlmService.completeOnce`) rewrites the character's
  Memory, at most every 6 messages. Switch in RP settings. The demo model answers it too. Voice/Layout/Wallpaper cards are done (RpVoiceDialog, ChatAdapter.rpLayout,
  AmbientBackgroundView.photoSlot with BackgroundPhoto slots "char_<id>").
- The old scroll-progress bar (the full-width line under the tabs) is off by default and fades
  when idle if someone turns it on.
- The phone still needs to check: Continue feel, the panel's blur and lens, the Thinking toggle, and
  all motion.
