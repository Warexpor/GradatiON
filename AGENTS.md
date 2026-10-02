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
| Motion | `Motion.kt` (curves, stack animations), `StreamRevealAnimator` (paced streaming), `StreamRevealPacing` (how much to reveal per frame, from the arrival rate) |
| Touch | `TouchTargets` (44dp hit areas), `Haptics` (one helper that honors Settings > Haptics) |
| Backgrounds | `AmbientBackgroundView`, `BackgroundPhoto` |
| Voice | `VoiceInput` (engine choice, device recognizer, cloud and local fallback), `VoiceDictation` (composer wiring, mic states), `VoiceWaveView` |
| Demo | `DemoModel` (an OkHttp interceptor that streams scripted SSE through the real pipeline) and `code/DemoBackend` (a scripted agent session) |
| Roleplay | `RpPromptEngine` (system prompt, macros, memory, craft rules), `RpChatDelegate` (reads prefs and the repo), `RpApiMemory` (history trimming), `RpCharacterPanel` (the tile sheet), `RpPages` (`RpPageFragment` and `RpPageKit`, the shared full-screen page each tile opens), `RpTileArt` (the drawn tile pictures), `RpChatHistoryFragment` (the History tile's page), `AvatarPicker` and `AvatarCropView` (choose a photo, then pan-zoom crop, for characters and personas), `Rp*Fragment` screens, `RpSwipe*` (alternate replies) |
| Code mode | `code/CodeHub` (singleton state), `CodeBackend` / `CodeTransport` / `AcpAdapter` (agent protocol), `CodeTranscriptAdapter` (transcript rows), `CodeSessionFragment`, `DiffView`, `code/store/CodeStore` (prefs) |
| Navigation | `SwipeNavLayout` (wide-swipe recognizer), the mode pager and history drawer in `ChatFragment` (snapshot slide; a new drag lands any slide still settling first), `RpChatsHome` (RP characters list and its slide into a chat) |
| Text | `TitleMarkdown` (inline markdown for one-line chat names in History) |
| Settings | `SettingsFragment`, `SettingsDetailFragment`, `SharedPreferencesHelper` (every pref key) |

## Build and test

```bash
export ANDROID_HOME=/opt/android-sdk        # or wherever your SDK lives
./gradlew assembleDev                        # minified, dev-signed, .dev app id
./gradlew testDebugUnitTest                  # logic tests only, skips the screenshot classes (~20 s)
./gradlew testDebugUnitTest -Pfull           # everything, screenshots included (~4 min)
./gradlew testDebugUnitTest --tests '*RpScreenshotTest.rpConversationContinueDark'
```

- Fresh cloud container with no SDK: run `scripts/cloud-setup.sh` (SDK, Gradle 9.5.0, Maven mirror). The
  wrapper can't download Gradle behind the proxy, so use `/opt/gradle-9.5.0/bin/gradle` instead of `./gradlew`.
- If Maven Central rate-limits you, route it through the Google mirror with an init script.
  `CLAUDE.md` has the details.
- Screenshots land in `app/build/screenshots/`. Look at them after any UI change.
- The copies in `screenshots/` (12, numbered in README order) and `fastlane/.../phoneScreenshots/` (8) are curated
  from that folder, dark theme only. Sources: `demoModelStreamsWithoutKey`, `replyMoreMenuDark`, `historyDark`
  (seeded with markdown names), `rpHomeDark`, `rpConversationContinueDark` (also writes the panel and Bubbles
  shots), `CodeModeScreenshotTest`, `chatDictatingDark`, `settingsAppearanceDark`, `controlsPanelDark`.
- A full `ScreenshotTest` run aborts if Robolectric can't fetch the API 31 jar (`chatConversationApi31`).
  Run the tests you need by name with `--tests`.

## Releasing

`docs/RELEASING.md`. The version is `appVersionMajor/Minor/Patch` in `app/build.gradle.kts`
(versionCode is derived). `scripts/release.sh` builds and checks the signed APK against
`docs/release-cert.sha256`; plain `assembleRelease` fails without the key on purpose. Room changes
need a version bump plus a migration, never a destructive fallback.

## Test gotchas

- `CodeHub.get` keeps one hub per Application. `ScreenshotHarness` still calls `CodeHub.resetForTesting()`
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
  `ScreenshotHarness` sets `DemoModel.pace` near 0 so it doesn't wait on the fake typing.
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
- Keyboard (`ChatFragment.setupEdgeToEdge`): the IME is laid out frame by frame from its own animation
  (`applyKb`: dock padding, list padding and, at the newest message, its scroll, all in one layout pass).
  Don't go back to translating the chrome and handing off to layout at the end, and don't post any part of
  it: every hand-off left a frame a step behind, which the user saw as a flash at the end of each slide.
  Things that track the composer (`composerFade`, the empty-state mark) follow it in a pre-draw.
