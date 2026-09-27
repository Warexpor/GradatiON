# Working in GradatiON

This file is for coding agents and new contributors. Read [`CLAUDE.md`](CLAUDE.md) first.
It holds the standing rules: the branch, the build, the design rules, and how the owner likes
to work. For look and feel, see [`DESIGN.md`](DESIGN.md). For the state of work, see
[`docs/handoff.md`](docs/handoff.md) and [`docs/backlog.md`](docs/backlog.md).

## Code map

The code lives in `app/src/main/java/io/github/stardomains3/oxproxion/`. The package name is
upstream's and stays as it is.

| Area | Files |
|---|---|
| Shell | `MainActivity` (touch lock during transitions), `ChatFragment` (top bar, composer, mode pager, RP chrome), `ChatViewModel` (sending, streaming, saving, forks, RP flow) |
| Glass | `Glass.kt` (`GlassQuality` tiers, backdrop and lens), `GlassSurfaces.kt` (`GlassDrawable`, dialog chrome), `GlassSwitch`, `GlassNotice`, `GlassSegmentedGroup`, `PickerPopover`, `BackdropBlur` (frost behind open menus), `LiquidMarkView` (the animated glass logo) |
| Motion | `Motion.kt` (curves, stack animations), `StreamRevealAnimator` (paced streaming) |
| Backgrounds | `AmbientBackgroundView`, `BackgroundPhoto` |
| Voice | `VoiceInput` (engine choice, device recognizer, cloud and local fallback), `VoiceDictation` (composer wiring, mic states), `VoiceWaveView` |
| Demo | `DemoModel` (an OkHttp interceptor that streams scripted SSE through the real pipeline) and `code/DemoBackend` (a scripted agent session) |
| Roleplay | `RpPromptEngine` (system prompt, macros, memory, craft rules), `RpChatDelegate` (reads prefs and the repo), `RpApiMemory` (history trimming), `RpCharacterPanel`, `Rp*Fragment` screens, `RpSwipe*` (alternate replies) |
| Code mode | `code/CodeHub` (singleton state), `CodeBackend` / `CodeTransport` / `AcpAdapter` (agent protocol), `CodeTranscriptAdapter` (transcript rows), `CodeSessionFragment`, `DiffView`, `code/store/CodeStore` (prefs) |
| Settings | `SettingsFragment`, `SettingsDetailFragment`, `SharedPreferencesHelper` (every pref key) |

## Build and test

```bash
export ANDROID_HOME=/opt/android-sdk        # or wherever your SDK lives
./gradlew assembleDev                        # minified, dev-signed, .dev app id
./gradlew testDebugUnitTest -Pfast          # logic tests only (~310), skips the screenshot classes
./gradlew testDebugUnitTest                  # everything (~410), about 2.5 minutes
./gradlew testDebugUnitTest --tests '*ScreenshotTest.rpConversationContinueDark'
```

- If Maven Central rate-limits you, route it through the Google mirror with an init script.
  `CLAUDE.md` has the details.
- Screenshots land in `app/build/screenshots/`. Look at them after any UI change.
- The copies in `screenshots/` and `fastlane/.../phoneScreenshots/` are curated from that folder.

## Test gotchas

- `CodeHub.get` keeps one hub per Application. `ScreenshotTest` still calls `CodeHub.resetForTesting()`
  in `@After` (and can `installForTesting`) so a later test does not see the previous hub.
  Chat and saved-chat ViewModels are created with `AppViewModelFactory`, not the framework factory
  that caches the first Application.
- Roleplay state also leaks between tests (the active character, the RP model). A roleplay test
  should start its own chat (`startRpChatWithCharacter`) and pick its model after switching to
  the Roleplay tab.
- Robolectric's animator scale is static per JVM, so animations can finish instantly. Assert on
  the first frame, not mid-flight.
- The default ViewModel factory caches the first Application in a static, so later tests' ViewModels
  read stale prefs. Screenshot tests call `TestEnv.resetViewModelFactory()` in `@Before`.
- Robolectric reports a speech recognizer as available. Use `VoiceInput.deviceAvailableOverride`.
- The demo stream runs on a real thread. Pump the looper in real time with `waitFor`.
  `ScreenshotTest` sets `DemoModel.pace` near 0 so it doesn't wait on the fake typing.
- Light-theme screenshots are kept to one per screen family (chat, glass, settings, RP hub,
  dialogs, backgrounds, Code home). Add new ones in dark; showcase images are dark only.
- A live `RecyclerView` follows the bottom edge. To check an off-screen row, bind it through the
  adapter (`onCreateViewHolder` and `onBindViewHolder`).
- Vector paths must not use SVG compact arc flags. `HarnessIconsTest` guards this.
- R8 once renamed the glass class and broke release builds only. Keep `proguard-rules.pro` and
  `ReleaseKeepRulesTest` in step.

## Conventions

- Match the code around you: its comment density, naming and idioms. Comments explain why, not what.
- Put strings in `res/values/strings.xml` (Code mode uses `code_strings.xml`). Put colors in the palette files. Never hardcode a hue.
- Add new prefs to `SharedPreferencesHelper`, or to `CodeStore` for Code mode, with a one-line doc comment.
- Every UI change needs a screenshot test, or an update to an existing one.
- Commit messages give a short summary line and a body that explains the why.
