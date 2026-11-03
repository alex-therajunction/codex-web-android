# Android screen share

## Objective

Bring the Codex Web host screen-share feature to the native Android app with the same `/workspace/screen` noVNC client, explicit exclusive-session takeover, pointer control, keyboard input, reconnect, and cleanup.

## Implementation

- [x] Added capability types/parser, frame-message parser, and bridge WebSocket URL builder that preserves query tokens.
- [x] Added authenticated `GET /api/codex/screen-share` through `WebSessionGateway`, including session-expiry handling.
- [x] Added the screen-share state machine and lifecycle to `CodexWebViewModel`.
- [x] Added a same-origin raw HTML wrapper and intercepted WebView host URL so the `SameSite=Strict` session cookie reaches `/workspace/screen`.
- [x] Added the Compose screen with status/resolution display, pointer/keyboard control, retry, reconnect, takeover, close, and error overlays.
- [x] Added an IME bridge that forwards soft-keyboard text, Backspace, Enter, and navigation keys through noVNC.
- [x] Added local pinch-to-zoom, two-finger panning, and a reset-zoom action.
- [x] Added **More → Screen share** wiring and full-screen precedence over chat/terminal.
- [x] Added WebSocket close-code probing for `4001`, `4002`, and `4003` before noVNC connects.
- [x] Added unit tests for capability/frame parsing, URL construction, close-code reduction, and authenticated capability requests.
- [x] Updated README with server prerequisites and security notes.

## Verification

- [x] `./gradlew :app:testDebugUnitTest --no-daemon` passed.
- [x] Android 15 emulator connected to a local Codex Web smoke server and rendered the host desktop.
- [x] Pointer and hardware-keyboard input reached the host.
- [x] Soft-keyboard text, Backspace, and Enter reached the host through the IME bridge.
- [x] Pinch, pan, and reset-zoom were exercised through WebView touch injection.
- [x] Already-active, takeover, reconnect, rotation, and close cleanup were exercised.
- [x] `./gradlew :app:assembleRelease --no-daemon` release build.

See `docs/handover/2026-11-02-android-screen-share.md` for the architecture and lifecycle details.
