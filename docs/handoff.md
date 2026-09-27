# Handoff (2026-09-27)

Read `CLAUDE.md` first, then `docs/backlog.md` (Pass 2 is the live list). Branch
`liquid-glass-redesign`, HEAD `b228172`. All pushed. Full suite 421/421 green. Dev APK
`GradatiON-dev-b228172.apk` was sent to the user.

## Done this session (Pass 2)
- Voice input (`VoiceInput`, `VoiceDictation`, `VoiceWaveView`; Settings > Voice).
- Glass toggles (`GlassSwitch.kt`): round capsule and a glass bead that never changes color.
- `GlassNotice`: toasts are silenced app-wide (`AppToast` is a no-op on purpose), so use
  `GlassNotice.show(ctx, text)` wherever silence would look broken.
- Mode pager (`ChatFragment`: `openPager` / `movePager` / `settlePager` / `pageToTab`). The pager
  snapshots the page, switches modes behind the snapshot, and slides both. It runs for taps and
  swipes. `pagerBusy` gates `placeModeTabIndicator`.
- `setChatMode` skips parking the draft while a transition is still loading. Without that, drafts
  crossed on swipe-cancel.
- Edge to edge: `setupEdgeToEdge` in `ChatFragment`. `rootLayout` has no `fitsSystemWindows`
  anymore. The backdrop gets negative margins, and the top bar, dock and code container are inset.
- `MainActivity` holds touches for the length of each back-stack transition (the double-tap
  "sinks too deep" bug). Stack animations keep the old screen opaque. Screens opened from History
  push in (`withGrokPushOver`).
- Streaming: `StreamRevealAnimator` eases at backlog/24 per frame, with no cursor glyph. Code
  transcripts now use the same paced reveal.
- Chat text size: Appearance > Chat text S/M/L/XL (90/100/115/130 via `getFontSizeCh`).
- Message actions: 32dp bare icons, no capsule.
- Demo model `gradation/demo` (`DemoModel.kt`). An OkHttp interceptor streams a fake SSE reply
  through the real pipeline. It is always first in the model list.

## Next (in this order)
1. K1: rework the Code session transcript to feel like Claude Code. Less chrome, tool calls as
   quiet one-liners that expand, and Normal/Thinking verbosity levels. Files:
   `code/CodeTranscriptAdapter.kt`, `code/CodeSessionFragment.kt`, and the `item_code_*` layouts.
2. R1–R3: RP harness and UI. The user's references are listed in the backlog. The harness (prompt
   assembly, memory) matters most. Also a "continue" button so the AI writes the next beat, and a
   character panel sheet.
3. P1: repo pass. New screenshots in `screenshots/` from the Robolectric tests, a README rewrite,
   and design and agent docs (`DESIGN.md` exists but predates these passes).
4. (Decided) Code syntax and diffs stay colored. Diffs are done.

## Test gotchas learned
- `ScreenshotTest` resets `CodeHub` in `@After` and forces Chat mode in `withChat`. Modes leak
  between tests otherwise, because `CodeHub` is a singleton.
- Robolectric's `ValueAnimator` scale is static per JVM, so animations may finish instantly.
  Assert on the first frame, not mid-flight.
- Robolectric reports a speech recognizer as available. Tests use
  `VoiceInput.deviceAvailableOverride`.
- The demo test pumps the looper in real time (`waitFor`), because the stream comes from a thread.
