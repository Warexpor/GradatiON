# GradatiON shell contract

**Product:** GradatiON — personal Android LLM client (fork of [oxproxion](https://github.com/stardomains3/oxproxion)).  
**Shell:** Ask-style chat + History drawer + grouped Settings (layout inherited from Grok Ask / oxproxion polish passes).  
**Stack:** Views/Fragments, MVVM. Pref keys unchanged unless migrated.  
**Rule:** Real-capability rows only — no cloud account / paywall stubs.

Visual tokens and component specs: [`DESIGN.md`](DESIGN.md).

---

## 1. Screen inventory

| Screen | Host | Notes |
|--------|------|-------|
| Ask | `ChatFragment` | Top: history · model chip · new chat; composer pill; empty GradatiON mark |
| History | `SavedChatsFragment` embedded | ~84% width; **GradatiON** wordmark (Inter Bold, large title); Search; Conversations; settings gear |
| Settings root | `SettingsFragment` | App + AI section cards → push detail screens |
| Appearance | `SettingsDetailFragment` | Theme + preview |
| Haptics | detail section | Button + responding toggles |
| Models & API | detail section | LAN, keys, credits, trust TLS |
| Advanced | detail section | Libraries, generation, chat chrome (power tools bar) |
| Data & Privacy | detail section | Biometrics, notifications, import/export, **Help**, licenses |
| Model picker | `BotModelPickerFragment` | Primary entry via model chip |
| Attach sheet | composer `+` | Camera / Gallery / Files (+ Tools) |

Voice settings entry is disabled (live STT removed). Conversations always autosave to History.

---

## 2. Settings remap (legacy prefs → sections)

### Appearance
Theme toggle, appearance preview, in-chat font size controls.

### Haptics
`haptic_buttons`, `haptic_responding`.

### Models & API
LAN dialog, trust self-signed TLS, OpenRouter / Brave keys, credits, model catalogs (chip + picker).

### Advanced
Tools, prompt library, presets, system messages, inference params, max tokens, timeout, chat memory, advanced reasoning, OpenRouter transforms, web-search auto-off, power tools bar (extended dock + top bar), scroll helpers, presets-on-chat, citations, etc.

### Data & Privacy
Biometrics, notifications, destructive file tools, keep screen on, History import/export, **Help**, third-party licenses.

---

## 3. Omit list (never ship)

SuperGrok, sign-out, billing, connectors marketplace, Imagine tab, LiveKit pipeline, cloud memory, team workspace, kids mode, and other Grok-cloud-only surfaces.

---

## 4. Screenshot gates (release checklist)

Use [`screenshots/`](screenshots/) as the canonical set; mirror to `fastlane/.../phoneScreenshots/` for Play uploads.

1. Empty Ask (dark; light optional)  
2. History open (wordmark + list + bottom bar)  
3. Settings root (App / AI cards)  
4. Model picker  
5. Streaming reply + action row (optional fifth shot)

Smoke path: pick LAN or OpenRouter model → stream → stop → new chat → pin/search in History → theme toggle → import/export.

---

## 5. Local reference material (not in git)

Optional Grok APK decompile for historical token extraction lives under `references/` (gitignored). GradatiON branding and behavior are defined by this repo + app Help, not by side-by-side parity with `ai.x.grok`.
