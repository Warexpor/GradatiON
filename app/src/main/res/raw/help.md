# GradatiON help

**App version: {{version}}**

GradatiON is a personal Android client for OpenRouter and models on your own network (Ollama, LM Studio, llama.cpp, MLX LM, Hermes Agent). It has three modes: **Chat**, **Roleplay** and **Code**. It is a fork of [oxproxion](https://github.com/stardomains3/oxproxion).

---

## Getting started

1. Try it first: open the model picker and choose **GradatiON: Demo**. It answers with scripted replies and needs no key or network.
2. For real models, add an [OpenRouter](https://openrouter.ai/) API key in **Settings → Models & API**, or set your local server address there.
3. Tap the model pill in the composer to pick a model. New chats save on their own and show up in History.

**Repo:** [github.com/Warexpor/GradatiON](https://github.com/Warexpor/GradatiON)

---

## Modes

The top bar has a menu button, the mode tabs and a new-chat button {{ic_new_chat}}. Swipe sideways or tap a tab to change mode. Roleplay and Code can be switched off in **Settings → Modes**.

*   **Chat** talks to a model, with attachments, tools and web search.
*   **Roleplay** is for stories with characters you create. Everything stays on the phone.
*   **Code** steers coding agents that run on your own computer. See below.

---

## Chat

### History
The menu button (top left) or a swipe right on the chat opens **History** {{ic_schats}}. It lists your saved chats, pinned ones first, then grouped by day, with the last line under the title. A search shows the line that matched, and the chat you have open is marked. Search sits along the bottom and stays above the keyboard. Long-press a row to pin, rename or delete. Chat and Roleplay chats are listed separately. Tap a chat to reopen it.

### Composer
*   **+** {{ic_plus}} attaches a photo, a file or a document, or opens the tools manager. A row appears while files are attached, so you can review or remove them.
*   **Controls** {{ic_sliders}} opens the Controls panel: model, reasoning, web search, stream, thoughts, read aloud, tools, presets, system message, settings, font and text size, plus export once the chat has messages. A filled tile means the feature is on.
*   **Model pill** shows the active model. Tap it for the picker.
*   **Mic** {{ic_mic}}: tap, talk, then tap the check {{ic_check}}. Your words land in the message box and nothing is sent until you press send {{ic_send}}. There is no hold-to-talk. While a reply streams, send turns into stop {{ic_stop}}.

### Models
The picker lists your models with each maker's mark and a short line such as "Anthropic · Vision · Free". **Manage models** opens the full list: sort A–Z or Newest, filter by type, price or Local. The plus adds a model from the OpenRouter catalog, from your local network, or by id. Press and hold a model to edit it, open its page or remove it.

### Reasoning
The Controls panel has a segmented reasoning picker: Off, Auto, Low, Med, High. Any cloud model can be asked; models that can't think ignore it. A local model needs its **Thinks** switch on in its edit sheet. **Thoughts** shows or hides the model's thinking above replies. It stays folded while the reply streams, and you tap to open it. For a token budget instead of an effort level, use **Settings → Advanced → Advanced reasoning**.

### Replies
Tap the ⋮ under a reply for read aloud, copy, edit, regenerate and more. Editing one of your messages can fork the chat; use the `< n/m >` arrows to move between branches.

### Images and audio
Models with the image badge generate pictures, which save to Downloads. Transcription models turn an audio file into text.

---

## Roleplay

The Roleplay tab opens on your **characters**. Tap one to start a chat. The ⋮ on a row offers New chat, Edit character and Delete chat. The button at the top right opens the character library (create, edit, import and export); the gear at the top left opens Settings.

Inside a chat, each reply carries the character's portrait and name. Tap it to open the **character panel**. Each tile opens its own full-screen page:

| Tile | What it does |
|------|--------------|
| **History** | This character's chats, grouped by day. Open one, or start a new one. |
| **Memory** | A note you write. It goes into every chat with the character and is never rewritten. **Facts** from this chat sit under it and update on their own in long chats (switch in Style). |
| **Lore** | Pick which lorebook the character uses. |
| **Edit** | The character card: greeting, personality, speech style, scenario and more. |
| **Voice** | The voice used by read aloud, with pitch and speed. Every choice plays a sample. |
| **Persona** | Who you are in the story. Save several and switch between them, or turn the persona off. |
| **Wallpaper** | A picture behind this character's chat. |
| **Layout** | Classic, Bubbles or Book. |
| **Style** | Third-person narration, inner thoughts, Facts on or off, and LLM mode (no character card). |

Other things to know:
*   On an empty composer, send becomes **Continue** (»). It extends the last reply from where it stopped.
*   Swipe a reply, or use the arrows, for alternate replies.
*   Lorebooks are plain text. Text above the first `[keys: word, phrase]` line is always included; each block under it joins when the chat mentions a key.
*   The **+** menu attaches a photo for the scene (from the library or the camera), inserts a scene reminder, and opens Controls or the character library. Files and audio stay in Chat. A photo on its own is enough to send; say what it is if you want the character to treat it a certain way.
*   A reply's menu can **Rewrite** it. The latest reply comes back as another swipe; an earlier one, including the greeting, changes where it is and can be undone.
*   Characters and lorebooks export and import as JSON from the library.

---

## Code

Code mode drives coding agents (Claude Code, Codex, OpenCode and others) that run on your own machines. The agents run there; the phone steers them, shows their work and approves what they do. Turn the tab on in **Settings → Modes**.

*   **Machines.** Add one by scanning the pairing QR or typing the bridge address and token. Reach it privately: same Wi-Fi, Tailscale or an SSH tunnel. The **demo machine** is built in and runs scripted sessions, so you can look around before you set anything up.
*   **Sessions.** Pick a machine, agent, model and folder on the Code home, then send a task. Active and recent sessions are listed below, with search.
*   **Approvals.** Each edit or command asks first, with capsule choices. Change the mode from the composer pill: **Ask first**, **Auto-edit**, **Plan only** or **Full auto** (only for sandboxes).
*   **Reading a session.** Each tool call is one quiet line, and diffs are cards. The session menu switches between **Normal view** (folded) and **Thinking view** (every thought and output open). **Changes** lists uncommitted files with their diffs, and can ask the agent to commit or revert.
*   **Away.** With **Notify when away** on, you get a local alert when an approval is needed or a turn finishes.

---

## Settings

Settings opens from History, from the Controls panel, and from the gear on the Roleplay characters list.

| Section | What's inside |
|---------|---------------|
| **Modes** | Switches for Roleplay and Code. |
| **Appearance** | Theme, chat text size, the empty-chat mark, and the **background**: Off, Drift, Flow, Adaptive, or **Photo** (your own picture, with blur, dim, fade behind bars, liquid motion and keep color). |
| **Haptics** | Feedback on buttons and while a reply streams. |
| **Voice** | The engine for the mic: Phone (on the device, private and free), Cloud (OpenRouter), Grok (xAI) or Local (your server). |
| **Models & API** | OpenRouter and Brave keys, your local server, trusting self-signed certificates, remaining credits. |
| **Advanced** | Tools, Prompt library, Presets, System messages and Roleplay; max tokens, timeout, inference parameters and chat memory; chat chrome and web search options. |
| **Data & privacy** | Biometric lock, notifications, screen-on, file tools, import and export, this help and licenses. |

Tools are experimental. Their workspace is **Download/gradation**. [Re-select folder](action://reselect-folder) if tools can't read files.

---

## Privacy and tips

*   No trackers, analytics or ads.
*   Chats stay on the device, encrypted with SQLCipher and the Android Keystore. Export before you uninstall if you want a backup.
*   OpenRouter and local-server traffic, and any pricing, are between you and those providers.
*   A local server over plain HTTP is cleartext on your network. Use HTTPS, and trust a self-signed certificate in Settings if you need to.
*   The "Your answer is ready" notification only appears while the app is in the background.
*   Prompt variables: `{{oxdate}}`, `{{oxtime}}`, `{{oxdatetime}}`, `{{oxhdt}}`.
*   Apache 2.0 · 18+ · provided as is.

**Third-party licenses:** [View in app](oxproxion://licenses)
