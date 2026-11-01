# Android inline turn errors and automatic reconnect

## Objective

- Render a failed Turn's error immediately after that Turn instead of after the entire conversation.
- Detect dead WebSocket connections with heartbeat and reconnect automatically without losing the selected thread or project.
- Make connection recovery visible and stop retrying when the Web login has expired.

## Implementation

- [x] Added a pure `ConversationRow` model that flattens each Turn as its items followed by that Turn's error.
- [x] Replaced the separate bottom-of-list error pass in `MainScreen` with the ordered row model.
- [x] Added a 20-second OkHttp WebSocket ping interval with no read/call timeout on the WebSocket client.
- [x] Added exponential retry delays of 1s, 2s, 4s, 8s, 16s, then 30s.
- [x] Re-resolve `/api/auth/me` and `/api/codex/bridge-url` before every reconnect attempt.
- [x] Preserve `selectedThreadId` and resume that exact thread after reconnect; preserve a new-chat selection instead of opening the newest thread.
- [x] Show `Reconnecting… attempt N`, expose a manual retry action, and keep the existing conversation visible.
- [x] Stop retries on sign-out and return to sign-in on session expiry.
- [x] Reject sends while disconnected so they cannot fail against a dead socket.

## Source breadcrumbs

- Turn error: `app-server` Turn `error.message` from `thread/turns/list` and `turn/completed`.
- Disconnect: `web-bridge.close` close reason.
- Reconnect: transport state transition from `connected` to `disconnected`.
- Restored thread: `app-server.thread/resume` and `app-server.thread/read`.

## Verification

- `./gradlew :app:testDebugUnitTest` passed 31 tests.
- `./gradlew :app:assembleRelease -PversionName=0.1.16 -PversionCode=16` built `app-release.apk`; `apksigner` verified the v2 signature.
- Emulator fixture rendered a failed Turn's error card between that Turn and the following Turn.
- Forced bridge test: first WebSocket closed with code `1012`; reconnect upgrades were rejected for 7 seconds; the app retried at increasing intervals and reconnected, logging `thread/resume` for the same `mock-thread-1`.
- Negative test: blank or missing Turn errors produce no error row.
- Transport regression test: the same `CodexRpcClient` can connect, receive a server close, connect again, and complete a request.

## Remaining verification gap

- The headless emulator process terminated after the reconnect test, before a screenshot of the transient “Reconnecting…” banner was captured. Automatic retry and successful recovery are proven by the mock bridge log; the banner is covered by compilation and state-flow wiring.
