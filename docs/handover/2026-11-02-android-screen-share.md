# Android screen share handover

## Scope

The Android app now exposes the same host desktop stream as the Codex Web workspace sidebar. It is opened from **More → Screen share** as a full-screen viewer and reuses Codex Web's existing `/workspace/screen` noVNC client rather than implementing a native RFB stack.

Web reference:

- `codex-web/src/components/layout/WorkspaceSidebar/ScreenShareFrame.tsx`
- `codex-web/src/components/layout/WorkspaceSidebar/screen-share-frame-protocol.ts`
- `codex-web/src/app/workspace/screen/page.tsx`
- `codex-web/server/screen-share/*`

## Architecture

- `data/ScreenShare.kt` parses `GET /api/codex/screen-share`, validates the same-origin WebSocket path, builds the bridge URL without dropping query tokens, and parses `screen-frame/*` messages.
- `WebSession.kt` performs the authenticated capability request. A `401` is surfaced as `SessionExpiredException`.
- `CodexWebViewModel.kt` owns the retained deployment session and the screen-share state machine: loading, connecting, connected, disabled, unsupported, already active, taken over, disconnected, error, retry, reconnect, takeover, and close.
- `ui/screens/ScreenShareScreen.kt` hosts a WebView, reports status and resolution, and renders the status overlays and actions.
- `res/raw/screen_share_host.html` is a small same-origin wrapper with an iframe for `/workspace/screen`. It relays the existing screen-frame protocol over the Android WebMessage bridge.
- The wrapper includes a hidden textarea used by the Android IME. Committed text is dispatched to noVNC's canvas as virtual-keyboard key events; Backspace, Enter, and navigation keys are sent as their corresponding RFB keysyms.
- `MainScreen.kt` adds the menu entry; `CodexWebApp.kt` gives the screen-share surface precedence over the conversation and terminal.

## Same-origin wrapper

The Codex Web session cookie is `SameSite=Strict`. A wrapper loaded with `loadDataWithBaseURL` has an opaque origin, so its `/workspace/screen` iframe request was treated as cross-site and redirected to `/login`.

The wrapper is therefore served by `WebViewClient.shouldInterceptRequest` at:

```text
<deployment>/__codex_android/screen-share-host
```

The document retains the real deployment origin, and the iframe can send the existing session cookie. The wrapper's `html`, `body`, and iframe use fixed positioning because percentage heights resolved to zero in the Android WebView even though the viewport had a height. The intercepted path is never sent to Codex Web.

## Frame protocol and noVNC close codes

The web client sends `screen-frame/ready`, `screen-frame/status`, and `screen-frame/error`. Android sends `screen-frame/connect` only after the iframe reports ready; this preserves the web panel's explicit takeover flow.

noVNC 1.7 binds its `_socketClose` handler in its constructor, so the web client's later monkey patch cannot observe WebSocket close codes. The Android wrapper wraps `WebSocket` after `screen-frame/ready` and before noVNC constructs its socket. Close codes `4001` (taken over), `4002` (already active), and `4003` (launch failed) are merged into the subsequent `screen-frame/status` message. This lets the native UI distinguish "already active" from a clean disconnect without modifying Codex Web.

## Lifecycle

- Capability is fetched when the menu entry opens. Disabled and unsupported capabilities render explanatory overlays.
- The WebView is created only when a valid capability and WebSocket URL are available.
- Closing sends `screen-frame/disconnect`, removes the message listener, stops loading, destroys the WebView, and releases screen-share state.
- Rotation recreates the WebView and reconnects. The old WebSocket closes before the new iframe is ready in the observed Android 15 emulator path.
- Reconnect uses the normal WebSocket URL. Takeover adds `takeover=1`; the server closes the previous viewer with `4001`.
- Sign-out, login expiry, connection failure, and ViewModel teardown all clear screen-share state and release the WebView.
- The WebView requests Android focus on creation and on touch so hardware key events reach noVNC. Pointer clicks are forwarded through noVNC's RFB pointer events.
- The keyboard action focuses the wrapper textarea and shows the Android IME. Text is sent only after IME composition commits, avoiding duplicate characters from intermediate composition updates. Closing the viewer hides the IME before releasing the WebView.

## Security

- No new Android permissions are required.
- The wrapper and WebMessage listener accept only the configured deployment origin.
- WebView file and content access remain disabled; mixed content is rejected; certificate errors are not bypassed.
- The deployment's normal authenticated bridge still validates the WebSocket token, origin, and remote-address policy.
- Clipboard sync stays disabled by Codex Web: `ClientCutText` is dropped and `x11vnc` runs with `-noclipboard -nosetclipboard`.

## Verification

- `./gradlew :app:testDebugUnitTest --no-daemon` covers capability parsing, bridge URL preservation, frame-message parsing, close-code reduction, and authenticated capability requests.
- Android 15 emulator (`codex-api35`, API 35) against a local Codex Web smoke server:
  - More → Screen share connected and rendered a `1920 × 1080` host desktop;
  - tapping the host window's button closed it through noVNC pointer input;
  - hardware-keyboard events reached `xev` as `a`, `h`, `e`, `l`, `l`, `o`;
  - the on-screen keyboard opened, and `soft`, Backspace, and Enter reached `xev` as the matching keysyms;
  - a second viewer received `4002 already-active` and the UI offered **Take over**;
  - takeover closed the previous viewer with `4001 taken-over` and connected Android;
  - the Android overlay showed **Taken over by another client** with the `4001` condition;
  - Reconnect while another viewer held the session showed **Already active**;
  - rotation to landscape and back reconnected and retained the resolution;
  - closing returned to chat and the server logged `stop reason=tab-closed`.

## Known gaps

- HTTP deployments work but noVNC logs an insecure-context warning; HTTPS is recommended.
- The viewer is exclusive. A second client must use **Take over**; the server intentionally rejects concurrent viewers with `4002`.
- Destroying an active WebView can log a Chromium renderer-process termination on the emulator. It has not crashed the app and matches Android WebView teardown behavior.
