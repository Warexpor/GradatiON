# Design pass backlog

Voice-dictated list from the user (2026-09-27), as read back and agreed. "Full pass,
nothing deferred, everything verified, then build and send the dev APK."
Tick items off here as they land so a fresh session can pick up.

## Quick fixes
- [ ] 1. Menus above the chat must stretch to the right edge like the left: chat model
      picker, roleplay characters, code mode harnesses and projects, and the rest.
- [ ] 2. Appearance tab: theme picker drifts to the right. Re-center.
- [ ] 3. Glass theme-mode picker has a light "cloud" leaking inside the button. Remove
      only that highlight, keep the glass.
- [ ] 4. Composer buttons (tools, files/photos/camera, model picker, send) get the same
      glass as the chat settings button.
- [ ] 5. Code mode: new harness sessions default to full auto, not ask-first.

## Features and redesigns
- [ ] 6. Main screen shows only Chat and Code by default. Settings: Roleplay can be
      enabled, Code can be disabled.
- [ ] 7. Chat settings sheet: mostly clutter. Replace with a normal model picker and a
      reasoning-effort picker. Other options move behind an "Advanced" row (not
      deleted). Redraw the streaming icon.
- [ ] 8. Redesign the toggles (GlassSwitch).
- [ ] 9. Backgrounds: remove Grain. Make Adaptive follow the wallpaper's tones (neutral
      grays only). Add custom wallpaper from the gallery with optional overlays: blur,
      dim, gradient tint, liquid. Fix backgrounds not showing in chat and other
      screens. 12-15fps, pause offscreen.
- [ ] 10. Swipe between chat, roleplay and history: lower threshold, and the next screen
      follows the finger while dragging instead of the current one cutting off.
- [ ] 11. Code mode: real harness logos next to each harness.
- [ ] 12. Polish pass by screenshots: spacing, scale, glass, weak icons, conversation
      menu (roleplay and code entries too).

## Verification
- Robolectric screenshots for every visual change; release-like `assembleDev` build.
- Motion (swipe, glass, backgrounds) still needs a real phone check.
