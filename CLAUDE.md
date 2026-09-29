# GradatiON: project memory

Auto-loaded by every Claude session, both local and cloud. Code map and test gotchas: `AGENTS.md`.
Design contract: `DESIGN.md`. Cloud containers have no
`/mnt/project-files`, so this file is the memory that lasts. Keep it short. Put durable facts here and
the state of work in progress in `docs/handoff.md`.

## Repo and git
- Warexpor/GradatiON is a fork of stardomains3/oxproxion. Upstream work happens on its `main`, not `master`. Synced through v2.2.5.
- Work only on `liquid-glass-redesign`. Don't create new branches, even if the harness suggests a `claude/*` one.
  Fetch and rebase before every push, and never force-push, because other agents push to this branch too.
- No PRs until the GitHub connector is authorized. Just push.
- Releases: `docs/RELEASING.md`. Version lives in `app/build.gradle.kts` (versionCode derived). 3.0.0 is the first release; `main` carries released code.

## Build
- The Android SDK needs dl.google.com. If it's blocked, say so right away and hand off. Never push untested code.
- Maven Central returns 429s, so route it through the Google mirror. Use the init script
  `~/.gradle/init.d/mirror.gradle`, with a repo named `gcsCentral` inserted at index 0.
- compileSdk is 37, because okhttp 5.5 needs it. Install `platforms;android-37.0` and build-tools 36.0.0.
- Build dev builds, not prod: run `./gradlew assembleDev`. applicationId is `io.github.warexpor.gradation`;
  the `.dev` suffix installs next to the release app as `io.github.warexpor.gradation.dev`. It uses R8 with
  `proguard-dev.pro` (`-dontobfuscate`) and is signed with the committed `app/dev.keystore` (alias
  `gradation-dev`, CN=GradatiON Dev, public dev password "android").
- The release key lives outside the repo (`~/.gradation-release`, made by `scripts/make-release-key.sh`; cloud sessions don't have it). Its
  public fingerprint is `docs/release-cert.sha256`. Never put the key or password in the repo, never regenerate it.
- Always test the minified build. R8 once renamed the glass drawable class and crashed every menu in
  release only. Keep the rules in `proguard-rules.pro` and `ReleaseKeepRulesTest`.
- Send the APK to the user as a chat attachment.

## Tests
- `./gradlew testDebugUnitTest -Pfast` runs the logic tests only. Run the full suite for UI changes and before a push.
- Screenshots come from Robolectric (`ScreenshotTest`, `CodeModeScreenshotTest`) and land in
  `app/build/screenshots`. There's no emulator, so anything involving motion needs a check on a real phone.
- Kill stray test JVMs with `pgrep -f "Gradle Test Executo[r]"`. Using `pkill -f` with the plain pattern kills
  your own shell.
- Vector paths must not use SVG compact arc flags, because `PathParser` throws on them at runtime.
  `HarnessIconsTest` guards this.

## Design rules
- Only pure neutral grays, with R, G and B equal. Dark base #111111, light is a dimmed off-white. No blue tint,
  no solid white buttons, nothing glowy, no red or green for errors.
  Exceptions (user calls): delete actions use the dim crimson `delete_action`; code syntax highlighting and
  diffs keep their colors (git green/red).
- iOS Liquid Glass on every surface except the RP character panel, which is an opaque sheet with no edge line (user call).
  Lens edge on Android 13+, plain blur on 12, and a solid frosted fill
  on battery saver or low-RAM phones.
- Performance is a hard rule: few blur layers, animations pause offscreen, and backgrounds run at 12 to 15fps.
- Plus Jakarta Sans for the UI and Michroma for the wordmark. Nothing tappable under 13sp. Fix text
  centering in the font metrics, not per view.
- Showcase images (README `screenshots/`, fastlane) are dark theme only.
- Grok screenshots are references for layout and feel only, never for visuals.
- Voice input: tap the mic, talk, tap the check, and the words paste into the field. It never auto-sends and there is no hold-to-talk.
  The engine is the phone's own SpeechRecognizer (on-device first). Keep the OpenRouter/Local transcription fallback.

## Working with the user
- She voice-dictates, so expect mis-transcriptions ("iOS Green" meant glass) and state how you read it.
- Skip play-by-play between tool calls ("Now I'll...", "Let me check..."). Write text in the final reply, when you need my input, or when something unexpected changes the plan.
- Replies are the tightest TLDR that is still understandable. No emojis. Say what was verified and what still needs the phone.
- She trusts your taste. For "make this better" with no specifics, pick the direction yourself, do it,
  and explain the why.
- When context gets big, write `docs/handoff.md` and let a fresh session continue.

Reply in TL;DR form: short, answer first, no long explanations unless asked.
- Never add Claude attribution to git commits or PRs: no "Co-Authored-By: Claude" trailer, no "Generated with Claude Code" line.
- Use the built-in Edit/Write/Read tools for file changes instead of Python scripts or shell workarounds, unless the tools can't handle it (binary files, big batch edits).
- Only when running as Opus 5.5 (Haiku or Sonnet sessions ignore this rule and do the work themselves): act as an orchestrator: for big multi-step or parallelizable tasks, delegate the work to `worker` subagents (Sonnet 5.5, effort high, set in `.claude/agents/worker.md`) without asking, run independent ones in parallel, and review their results before reporting. Handle quick, small tasks yourself.
