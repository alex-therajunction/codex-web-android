# Native Android client handover

## Architecture

- `CodexWebViewModel` owns the high-level phase: signed out, WebView authentication, connecting, ready.
- `WebSessionGateway` validates the deployment URL, reads `/api/auth/me` and `/api/codex/bridge-url` with the WebView cookie, and resolves a same-origin bridge path to `ws`/`wss`.
- `CodexRpcClient` owns the JSON-RPC WebSocket, request IDs, pending calls, notifications, server requests, close events, and malformed-message reporting.
- `AppServerRepository` initializes the protocol, loads `model/list`, `account/read`, and `thread/list`, hydrates threads, starts turns, interrupts turns, and answers server requests.
- `SessionReducer` is a pure notification reducer. It keeps unknown notifications as diagnostics and has no UI dependencies.
- Compose screens consume a single `AppUiState` plus the app-server snapshot.

## Protocol notes

- `initialize` sends `clientInfo`, `capabilities.experimentalApi=true`, then the `initialized` notification.
- Thread discovery uses `thread/list` with `sortKey=recency_at`, `sortDirection=desc`, and `archived=false`.
- Opening a thread calls `thread/resume`, then `thread/read` with `includeTurns=true`.
- Sending calls `thread/start` when there is no selected thread, then `turn/start` with a text `UserInput`.
- Command and file-change approvals respond with `{ "decision": "accept" | "acceptForSession" | "decline" }`.
- Permission approvals respond with `{ "permissions": {...}, "scope": "turn" | "session" }`; they intentionally do not use the command approval shape.
- User input responds with `{ "answers": { "<question-id>": { "answers": ["..."] } } }`.
- MCP elicitation currently responds with `{ "action": "cancel", "content": null, "_meta": null }`.
- `serverRequest/resolved` removes pending prompts by request ID.

## Important constraints

- The app is a client of a separately deployed Codex Web server. It does not launch `codex app-server` on the phone.
- The WebView is only an authentication surface. The native UI does not load the Codex Web application after sign-in.
- The bridge must be reachable from the device. A deployment advertising a loopback bridge URL will fail visibly.
- Cookies can expire. A 401 from session discovery returns the user to the sign-in screen.
- No signing secrets are stored in the repository. The release workflow supports configured secrets and has an ephemeral self-signed fallback.

## Authentication handoff

- Android WebView owns the login flow and the `codex_web_session` HttpOnly cookie.
- A page-load event only starts a handoff probe when the cookie header contains that exact session cookie name. Analytics, theme, or other cookies cannot trigger an early connection.
- The handoff probe calls `/api/auth/me` before leaving the WebView. A 401 during login keeps the WebView and login page visible; only a successful authenticated response transitions to `CONNECTING`.
- `retryConnection()` starts the login flow when the session cookie is absent. App preferences still contain only the deployment URL and last model ID.

## Verification commands

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew testDebugUnitTest
./gradlew assembleRelease
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

## Verification results (2026-10-31)

- `./gradlew :app:testDebugUnitTest --no-daemon` passed all 11 unit tests.
- `./gradlew :app:assembleRelease -PversionName=0.1.0 -PversionCode=1 --no-daemon --offline` completed successfully.
- `apksigner verify --verbose --print-certs` reported a valid APK Signature Scheme v2 signature.
- An Android 15 (API 35, x86_64) emulator installed the release APK and verified URL onboarding, WebView login, native thread rendering, streamed app-server notifications, a command approval round-trip (`{"decision":"accept"}`), and automatic reconnect after a cold app restart.
- The emulator smoke used a local mock Codex Web bridge at `10.0.2.2:3100`, not a real user `CODEX_HOME` or the local production server.

## Remaining gaps

- MCP elicitation form rendering (request can be cancelled natively).
- Image/file attachment picking.
- Rich markdown beyond fenced code blocks.
- Multiple simultaneous pending request forms are queued one at a time in the composer area.
