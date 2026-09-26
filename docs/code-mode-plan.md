# Code mode: plan for finishing it

Status (2026-09-26): **UI done, harness foundation done, real connectivity not built.**
This document is the hand-off. It tells the next agent what exists, how it is meant to work,
and exactly what to build, in order, to ship Code mode for real.

---

## 0. What Code mode is

A third top tab (Chat · Roleplay · **Code**), off by default, turned on in
Settings > Code mode. It makes GradatiON a universal mobile client for coding agents
("harnesses") that run on the user's own computer or server: Claude Code, Codex CLI,
OpenCode, Grok Build, Cursor CLI, Pi, and anything else that speaks the Agent Client Protocol (ACP).

The phone never runs an agent or touches code. It:

- starts sessions (agent + folder + approval mode + prompt),
- streams what the agent does (text, thinking, tool calls, command output, diffs, plan),
- answers approval requests (allow once / always / deny),
- sends follow-up prompts, stops turns, switches approval mode,
- lists and reattaches to sessions that keep running while the phone is away.

A small daemon on the user's machine, **the bridge**, launches and supervises the
agents and relays everything to the phone over one authenticated WebSocket.

```
 phone (GradatiON)                    user's machine
 ┌──────────────────────┐   wss    ┌────────────────────────────────────┐
 │ Code UI              │◀────────▶│ gradation-bridge                    │
 │ CodeHub              │ JSON-RPC │  ├─ auth, sessions, replay log      │
 │ BridgeBackend        │  (ACP +  │  ├─ ACP client ──stdio──▶ agent     │
 │ AcpAdapter           │  bridge  │  │    claude-code-acp / codex-acp /  │
 │ WebSocketTransport   │  ext.)   │  │    opencode acp / grok / agent / pi │
 └──────────────────────┘          │  └─ fs + terminal for the agent    │
                                   └────────────────────────────────────┘
      reach: LAN, Tailscale/WireGuard, SSH tunnel, or an optional relay
```

Why ACP: it is the one protocol most harnesses already speak or have a maintained
adapter for, and it models exactly the concepts the UI needs (sessions, streamed
message chunks, tool calls with kinds and statuses, diffs, plans, permission requests).
Using it end to end keeps the phone harness-agnostic: new harness = bridge config, not
an app release.

---

## 1. What exists today (read these files first)

All new code is in `app/src/main/java/io/github/stardomains3/oxproxion/code/`.

| File | What it is | State |
|---|---|---|
| `CodeModel.kt` | Core model: `CodeHost`, `HarnessKind`, `PermissionMode`, `CodeSessionSummary`, `NewSessionRequest`, `CodeEvent` (UserPrompt, AgentText, Thought, ToolCall, FileDiff, Approval, Plan, Notice, TurnEnd) | Done |
| `HarnessAdapter.kt` | `HarnessAdapter` interface (frames ⇄ model), `CodeUpdate` (Upsert, TextChunk, ToolPatch, ApprovalAnswered, TurnDone, Title), `TranscriptReducer` (pure fold + status) | Done |
| `AcpAdapter.kt` | ACP JSON-RPC encode/decode for the subset the UI renders, plus `bridge/*` extension requests | Done for the subset; see §4 |
| `CodeTransport.kt` | `CodeTransport` interface, `WebSocketTransport` (OkHttp, bearer token, pings) | Skeleton: no reconnect/resume/pinning |
| `CodeBackend.kt` | `CodeBackend` interface; `BridgeBackend` (JSON-RPC ids, pending map, fan-out) | Skeleton: happy path only |
| `DemoBackend.kt` | In-process scripted host ("Demo machine"): reads, search, plan, streamed text, edit + approval + diff, test run, summary; deny path too | Done (dev/demo only) |
| `CodeHub.kt` | `CodeStore` (SharedPreferences JSON: enabled, hosts, sessions, defaults) and `CodeHub` (app singleton: hosts, backend per host, sessions + live transcripts as StateFlow) | Done for in-memory; persistence is minimal |
| `Diff.kt` | Unified diff parser; LCS line diff with context hunks (capped) | Done |
| `DiffView.kt` | Canvas diff renderer (clip-aware, neutral palette, gutter, +/−) | Done |
| `CodeModeHost.kt` | Glue into ChatFragment: tab, container, hides chat chrome, retargets top-bar glass, top-edge fade | Done |
| `CodeHomeFragment.kt` | Code tab: machine pill + status, sessions (Active/Recent), onboarding, composer with agent/folder/approval pickers | Done |
| `CodeSessionFragment.kt` | Session screen: transcript, glass chrome, composer (reply/stop, approval mode), options popover, edge-swipe back | Done |
| `CodeTranscriptAdapter.kt` | One cell per event type; incremental markdown while streaming | Done |
| `CodeDiffFragment.kt` | Full-screen diff, scrolls both ways | Done |
| `CodeSettingsFragment.kt`, `CodeHostDialog.kt` | Toggle, machines list, add/edit/remove machine, default approval mode, setup notes | Done |
| `CodeComposer.kt` | Shared composer binding and anchored glass pickers | Done |

Shared files touched (minimally): `ChatFragment.kt` (search `codeMode`), `fragment_chat.xml`
(`tabCode`, `codeModeContainer`), `SettingsFragment.kt` + `fragment_settings.xml`
(`settingsRowCode`). Resources: `res/values/code_*.xml`, `res/values-night/code_colors.xml`,
`res/layout/*code*`, `res/drawable/*code*`.

Tests: `CodeProtocolTest` (ACP decode/encode, reducer, diffs) and `CodeModeScreenshotTest`
(onboarding, home, session with approval, finished session, diff, settings, host dialog,
tab behaviour). Screenshots land in `app/build/screenshots/code_*.png`.

Run: `./gradlew :app:testDebugUnitTest --tests '*Code*'`.

---

## 2. Rules of this repo (do not break)

- Work only on branch `liquid-glass-redesign`. Never create branches. Fetch + rebase before
  pushing; never force-push. Another agent may be polishing chat/theme/settings files at the
  same time: keep Code work in `code/` and new resources; touch shared files minimally.
- Design: strictly neutral grays (R=G=B, no hue at all, including diffs and status).
  Dark base `#111111`, light `#F1F1F1`. Glass (`GlassLinearLayout`, `GlassIconButton`,
  `PickerPopover`, `GlassAlertDialogBuilder`) only on chrome that floats over content, never
  on content cards. No solid white/ink slab buttons; lead actions use
  `Widget.Gradation.Code.Button.Lead` (tonal + ink rim). Font Plus Jakarta Sans, mono
  Atkinson Hyperlegible Mono. See `DESIGN.md` §0.
- Performance is a hard rule: no per-frame work on the UI thread, no per-line views for diffs
  or logs, coalesce streaming updates (see §5.3).
- Always test the **release** variant (R8). Keep rules for anything reflected or serialized
  (see §6.6). Release signing: `-Pgradation.storeFile/...` from the local signing properties.
- Live voice input stays off.

---

## 3. The bridge (biggest missing piece)

A separate small program the user installs on each machine. Suggested: TypeScript on Node 20+
(ACP has an official TS SDK, `@agentclientprotocol/sdk`), published as `gradation-bridge`
on npm so setup is `npx gradation-bridge`. A single static Go binary is a fine alternative.
Keep it in its own repository (or `bridge/` in this repo if the user prefers; ask).

### 3.1 Responsibilities

1. **Pairing and auth.** On first run, generate a random 32-byte token and a self-signed TLS
   cert; print the address, token and a QR code (`gradation://pair?url=wss://…&token=…&fp=<sha256 cert>`).
   Accept connections only with `Authorization: Bearer <token>`. Support multiple phones
   (one token each, revocable: `gradation-bridge devices`, `revoke`).
2. **Harness registry.** Detect installed agents and how to run each as an ACP agent over stdio:

   | Harness | Command (verify current names before shipping) |
   |---|---|
   | Claude Code | `npx @zed-industries/claude-code-acp` (wraps the Claude Agent SDK) |
   | Codex CLI | `npx @zed-industries/codex-acp` (or `codex` app-server if its ACP mode matures) |
   | OpenCode | `opencode acp` |
   | Grok Build | `npx @xai-official/grok agent stdio` (or `grok agent stdio`) |
   | Cursor CLI | `agent acp` |
   | Pi | `npx pi-acp` |
   | Custom | any command line the user configures |

   Config file `~/.config/gradation-bridge/config.json`: harness commands, env, allowed
   workspace roots, default approval mode.
3. **Sessions.** Each phone session maps to one ACP session inside one agent process. The
   bridge owns the process lifetime: sessions survive the phone disconnecting. Keep an
   append-only event log per session (JSONL) with a monotonically increasing `seq`, so a
   reconnecting phone can replay from its last seen `seq`.
4. **Client side of ACP.** The bridge is the ACP *client*: it implements `fs/read_text_file`,
   `fs/write_text_file`, `terminal/*` on the machine, restricted to the session's workspace
   root, and forwards `session/request_permission` to the phone (and to any other attached
   phone; first answer wins, the others get `bridge/permissionResolved`).
5. **Approval policy.** Enforce the session's permission mode on the bridge (never trust the
   phone alone): `ask` forwards every request; `auto-edit` auto-allows `edit` kind inside the
   workspace; `plan` rejects all writes/exec; `full-auto` allows all (show a warning on the
   machine the first time).
6. **Relay to phone.** Forward `session/update` notifications unchanged, add `seq` in `_meta`.
7. **Push when away (later).** If no phone is attached and a session needs approval or
   finishes, send a push (see §5.6).
8. **Security.** Bind to `127.0.0.1` by default; `--lan` binds to the LAN interface,
   `--tailscale` to the tailnet address. Refuse workspaces outside allowed roots. Never
   expose a shell endpoint to the phone; everything goes through the agent.

### 3.2 Wire protocol (phone ⇄ bridge)

WebSocket, text frames, JSON-RPC 2.0. Path `/v1`. Everything ACP defines, relayed as-is,
plus these bridge methods (all requests from phone unless noted):

| Method | Params | Result |
|---|---|---|
| `initialize` (ACP) | `protocolVersion`, `clientCapabilities`, `clientInfo` | ACP result + `_meta.bridge: {version, harnesses: [{id,name,available}], hostName}` |
| `bridge/listHarnesses` | – | `{harnesses:[{id,name,available,models?:[…]}]}` |
| `bridge/listWorkspaces` | `{harness?}` | `{workspaces:[path…]}` (recent + configured roots) |
| `bridge/browse` | `{path}` | `{entries:[{name,dir}]}` inside allowed roots only (folder picker) |
| `bridge/listSessions` | `{limit?,before?}` | `{sessions:[{sessionId,harness,cwd,title,createdAt,updatedAt,preview,branch,status,lastSeq}]}` |
| `session/new` (ACP) | `cwd`, `mcpServers`, `_meta:{harness,permissionMode,model?}` | `{sessionId}` |
| `session/load` (ACP) | `sessionId`, `cwd`, `_meta:{afterSeq?}` | replays updates after `afterSeq`, then `{}` |
| `session/prompt` (ACP) | `sessionId`, `prompt:[ContentBlock]` | `{stopReason}` when the turn ends |
| `session/cancel` (ACP notification) | `sessionId` | – |
| `session/set_mode` (ACP) | `sessionId`, `modeId` (`ask`/`auto-edit`/`plan`/`full-auto`) | `{}` |
| `session/request_permission` (bridge → phone request) | ACP params | ACP outcome |
| `bridge/permissionResolved` (bridge → phone notification) | `{sessionId, requestId, optionKind}` | – |
| `bridge/sessionStatus` (bridge → phone notification) | `{sessionId,status,title?,preview?,branch?}` | – |
| `bridge/closeSession` | `{sessionId}` | `{}` (kills the agent process) |
| `bridge/diff` | `{sessionId, path}` | `{unified}` current `git diff` for a file (review screen) |
| `bridge/gitStatus` | `{sessionId}` | `{branch, ahead, behind, files:[{path,status}]}` |

Every notification the bridge sends carries `_meta.seq`. Unknown methods get JSON-RPC
error `-32601`; the phone already ignores unknown notifications.

---

## 4. Phone: finish the protocol layer

### 4.1 Transport (`CodeTransport.kt`)
- Reconnect with exponential backoff (0.5 s → 30 s cap, jitter), reset after 60 s connected.
  Pause while the app is backgrounded unless a session is running (then keep it for a
  while via the foreground service rules in §5.6).
- Resume: remember `lastSeq` per session; after reconnect call `session/load` with
  `_meta.afterSeq` for every attached session. `TranscriptReducer` upserts by key, so
  replays are idempotent as long as keys are stable (make ACP keys derive from bridge `seq`
  or tool-call ids, not `System.currentTimeMillis()`; today `AcpAdapter` generates text keys
  from time, fix that first).
- Cert pinning for self-signed bridges: pin the SHA-256 from the pairing QR with an OkHttp
  `CertificatePinner` + a custom `X509TrustManager` that only accepts that cert. Plain `ws://`
  only for LAN/tunnel with the warning already shown in the dialog.
- `ConnectionState` + `lastError` already drive the home status pill and the session banner.

### 4.2 Backend (`CodeBackend.kt`)
- Per-method timeouts (prompt has none; others 15 s), cancellation on close, and an outbox
  for prompts typed while reconnecting (send after resume, mark pending in UI).
- Handle `bridge/permissionResolved`, `bridge/sessionStatus` in `AcpAdapter.decode`.
- `listHarnesses` + `browse` for the pickers (replace the demo lists).
- Multiple hosts connected at once: already one backend per host; make `CodeHub.connection`
  per host (map) instead of active-only.

### 4.3 ACP coverage (`AcpAdapter.kt`)
Add: `available_commands_update` (slash commands → composer suggestions), `current_mode_update`
(sync the approval pill), `tool_call` content of type `terminal` (show live output via
bridge-side terminal output notifications), images in `agent_message_chunk` (render inline),
`user_message_chunk` for prompts sent from other devices, `session/prompt` with image
attachments (reuse the chat attach menu), stop reasons (`max_tokens`, `refusal`,
`cancelled`) in `TurnEnd` copy, usage/cost from `_meta` if the bridge provides it.

### 4.4 Direct adapters (optional, later)
For users without the bridge: OpenCode's own HTTP+SSE server (`opencode serve`) is the only
harness that can realistically be driven directly. Implement as another `HarnessAdapter`
+ `CodeTransport` pair (`OpenCodeHttpTransport`). Low priority.

---

## 5. Phone: finish the product

### 5.1 Persistence
Move sessions and transcripts to Room (new tables, new migration in `DatabaseMigrations`):
`code_session(id, hostId, harness, cwd, title, createdAt, updatedAt, mode, branch, preview, lastSeq)`,
`code_event(sessionId, key, seq, type, json)` with an index on `(sessionId, seq)`.
Keep the last N (e.g. 2,000) events per session on device; older ones reload via
`session/load`. Tokens move out of SharedPreferences into Android Keystore-backed
encrypted storage (`EncryptedSharedPreferences` or a Keystore AES key + our own prefs).

### 5.2 Pairing UX
- "Add a machine" → **Scan QR** (CameraX + ML Kit barcode, or ZXing embedded) as the
  primary path, manual entry as secondary (the current dialog). Handle
  `gradation://pair?...` deep links too.
- After saving, run a test connection and show the result inline (connected / wrong token /
  unreachable with a hint about Tailscale or the tunnel).

### 5.3 Streaming performance
Today every chunk emits a new `sessions` map and rebinds the streaming text cell (with
`IncrementalMarkdown`, so cost is bounded by the open block). Before long sessions ship:
- Coalesce updates per frame in `CodeHub` like chat's `StreamUiPump` (buffer `CodeUpdate`s,
  apply on `Choreographer` frame).
- Use `StreamFadeSpan` for the soft per-word fade-in the chat uses (Claude-app style, not a
  typewriter), and `StreamCursorSpan` while streaming.
- Tool output: keep only the tail in the event (already capped at 4,000 chars); add a
  "Full output" screen fetching the whole log from the bridge.

### 5.4 Screens still to build
1. **Folder browser** (sheet): breadcrumbs, dirs only, recent on top, "Use this folder".
   Backed by `bridge/browse`.
2. **Changes review**: per-session list of changed files (`bridge/gitStatus`), tap for
   `bridge/diff`, actions to ask the agent to commit or revert (as prompts, not raw git).
3. **Full output** viewer for a tool call (mono, search, copy).
4. **Session search/filter** on home when there are many sessions; swipe a row to remove
   from phone; long-press for rename.
5. **Slash commands**: typing `/` in the composer shows `available_commands_update` entries
   in a glass popover above the composer.
6. **Attachments**: images/screenshots into prompts via the existing attach menu.
7. **Model picker** per session when the harness reports models (`_meta.models`).
8. **Machine detail**: status, bridge version, harnesses available, revoke this phone.

### 5.5 Approvals UX details
- A pending approval pins a compact glass bar above the composer while the card is off
  screen ("Claude wants to edit SettingsRepository.kt · Review") that scrolls to the card.
- Haptic on arrival (`HapticFeedbackConstants.CONFIRM`), respect the app's haptics setting.
- "Always allow" should state its scope (this session / this kind of tool) as the harness
  reports it in the option name.

### 5.6 Background and notifications
- While any session is running and the app is backgrounded, keep the WebSocket for a
  bounded time (e.g. 10 min) and post a quiet ongoing notification only if the user opts in
  (the app avoids sticky FGS chrome today; follow `ForegroundService` conventions).
- Approval needed / turn finished while away: local notification if connected; otherwise a
  push from the bridge. Pushes need a server (FCM requires a backend key): make it optional,
  e.g. ntfy.sh topic configured in the bridge, or UnifiedPush. Tapping opens the session.
- Notification actions: "Allow" / "Deny" for simple approvals.

### 5.7 Demo machine
Keep it (it is the onboarding story and the screenshot fixture), but hide "Try the demo"
once a real machine is added and label every demo surface "Demo" (already done).

---

## 6. Verification checklist (every PR-sized step)

1. `./gradlew :app:testDebugUnitTest --tests '*Code*'` green, and the whole suite green.
2. Look at the `code_*.png` screenshots in light and dark; nothing tinted, glass only on
   chrome, nothing overlapping the composer or top bar.
3. Add protocol tests for every new ACP/bridge message (pure `AcpAdapter.decode` tests,
   no sockets) and a `MockWebServer` test for `WebSocketTransport` + `BridgeBackend`
   (connect, auth failure, request/response, reconnect + replay).
4. Bridge: unit tests for approval policy and workspace sandboxing; an end-to-end test
   that runs a fake ACP agent (a tiny script speaking ACP over stdio) through the bridge
   and asserts the phone-side frames.
5. Build `assembleRelease` with R8 and install it. kotlinx.serialization classes in `code/`
   (`CodeHost`, `CodeSessionSummary`) rely on the library's shipped consumer rules; if R8
   strips them, add `-keep` rules for `io.github.stardomains3.oxproxion.code.** { *** Companion; }`
   and `*$$serializer`, and extend `ReleaseKeepRulesTest`.
6. Manual on-device pass with a real bridge over Tailscale: start a session, background the
   app mid-turn, come back, approve, stop, reconnect after airplane mode.

---

## 7. Suggested order of work

1. Stable event keys + `seq` handling in `AcpAdapter` (small, unblocks resume).
2. Bridge MVP: auth token, one harness (OpenCode `acp` or Claude via `claude-code-acp`),
   `session/new|prompt|cancel|load`, permission forwarding, JSONL log, `bridge/listSessions`,
   `bridge/listWorkspaces`. Test with the phone app against it.
3. Transport reconnect + resume; backend timeouts and outbox.
4. Room persistence + Keystore tokens.
5. QR pairing + test connection.
6. All four harnesses in the bridge registry; `bridge/listHarnesses`; approval policy on the
   bridge for every mode.
7. Folder browser, changes review, full output, slash commands.
8. Streaming coalescing + fade; pinned approval bar.
9. Notifications (local first, push optional).
10. Polish: search, rename, attachments, model picker, machine detail.

---

## 8. Open questions for the user

- Where should the bridge live: separate repo (recommended) or `bridge/` in this repo?
- Is an optional hosted relay wanted for people without Tailscale, or keep it strictly
  self-hosted (recommended to start)?
- Should Full auto be offered at all, or hidden behind a developer setting?
