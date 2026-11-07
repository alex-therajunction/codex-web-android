# Native Android client handover

## Architecture

- `CodexWebViewModel` owns the high-level phase: signed out, WebView authentication, connecting, ready.
- `WebSessionGateway` validates the deployment URL, reads `/api/auth/me` and `/api/codex/bridge-url` with the WebView cookie, and resolves a same-origin bridge path to `ws`/`wss`.
- `CodexRpcClient` owns the JSON-RPC WebSocket, request IDs, pending calls, notifications, server requests, close events, malformed-message reporting, and a 20-second WebSocket heartbeat.
- `AppServerRepository` initializes the protocol, loads `model/list`, `config/read`, `account/read`, and `thread/list`, browses server directories through `fs/readDirectory`, hydrates paginated thread history, starts turns, interrupts turns, and answers server requests. Reconnect calls preserve the current snapshot and accept an explicit thread to resume.
- `CodexWebViewModel` schedules automatic reconnects after a connected bridge drops, re-resolves the web session and bridge URL on every attempt, shows the attempt count, and stops on sign-out or session expiry.
- `SessionReducer` is a pure notification reducer. It keeps unknown notifications as diagnostics and has no UI dependencies.
- Compose screens consume a single `AppUiState` plus the app-server snapshot.

## Protocol notes

- `initialize` sends `clientInfo`, `capabilities.experimentalApi=true`, then the `initialized` notification.
- Thread discovery uses `thread/list` with `sortKey=recency_at`, `sortDirection=desc`, and `archived=false`.
- Model options come only from app-server data: `model/list` supplies the catalog, while `config/read` supplies the configured model/provider. A non-OpenAI provider suppresses incompatible OpenAI catalog entries and the configured model is sent to thread/turn requests.
- `config/read` first sends `{ "includeLayers": false, "cwd": null }` and retries with `{ "includeLayers": false }` for app-server version compatibility. Failures are recorded as `app-server.config/read` diagnostics rather than silently ignored.
- If effective config is unavailable after both attempts, the newest thread metadata with `model` / `modelProvider` supplies the configured model/provider. If no verified model can be obtained, the OpenAI catalog is not exposed; the app fails closed instead of selecting an incompatible model.
- Opening a thread calls `thread/resume` with `excludeTurns=true`, metadata-only `thread/read`, then `thread/turns/list` with `itemsView=full` and `sortDirection=desc`. The initial page is reversed into chronological order, and older pages are prepended through the “Load earlier messages” control.
- `thread/resume` or `thread/read` model metadata reconciles the selected model with the thread’s real model/provider when available.
- Sending calls `thread/start` when there is no selected thread, then `turn/start` with a text `UserInput`.
- Projects are a client-side view over unique thread `cwd` values; there is no separate project registry or app-server project API. The current working directory is inserted as an empty project when it has no thread yet, and the active project is sorted first without duplication.
- The new-chat project picker lists recent project paths derived from `thread/list`. Server folder browsing calls `fs/readDirectory` with `{ "path": "<absolute server path>" }` and reads `entries[].fileName` / `entries[].isDirectory`; selecting a directory joins it under the current server path.
- The selected project is stored as `lastProjectPath`, shown in the top context chip, and passed as `cwd` to both `thread/start` and `turn/start`. Opening a thread reconciles the active project from that thread's server-provided `cwd`.
- The composer permission selector maps Codex Web's four profiles to app-server runtime settings. Ask for Approval uses `approvalPolicy=on-request` with the workspace sandbox; Auto Approval adds `approvalsReviewer=auto_review`; Full Access uses `approvalPolicy=never` and the `dangerFullAccess`/`danger-full-access` sandbox; Custom inherits `config/read` settings. Existing threads are updated through `thread/settings/update`, and resumed thread settings reconcile the selector.
- `item/commandExecution/terminalInteraction` appends its `stdin` payload to the matching command card and renders it as “Terminal input” instead of producing an unhandled-notification diagnostic.
- `deprecationNotice` is stored as a deduplicated `app-server.deprecationNotice` diagnostic with its summary and details.
- Conversation rows are flattened per Turn: all Turn items render first, followed immediately by that Turn's error card. Errors are never collected into a bottom-of-conversation pass.
- WebSocket clients use a 20-second ping interval. A connected-to-disconnected transition starts retries at 1s, 2s, 4s, 8s, 16s, then 30s; each attempt re-resolves the web session and bridge URL. A successful reconnect resumes the prior thread, while a selected new chat remains a new chat.
- Command and file-change approvals respond with `{ "decision": "accept" | "acceptForSession" | "decline" }`.
- Permission approvals respond with `{ "permissions": {...}, "scope": "turn" | "session" }`; they intentionally do not use the command approval shape.
- User input responds with `{ "answers": { "<question-id>": { "answers": ["..."] } } }`.
- MCP elicitation currently responds with `{ "action": "cancel", "content": null, "_meta": null }`.
- `serverRequest/resolved` removes pending prompts by request ID.
- Submitted replies are recorded in-memory so a bridge replay (same request ID or the same user-input item) is re-acknowledged and kept out of the pending UI instead of prompting again.

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
- `retryConnection()` starts the login flow when the session cookie is absent. App preferences contain only the deployment URL, last model ID, and last server project path; they never contain credentials.

## Verification results (reconnect and inline errors, 2026-11-01)

- `./gradlew :app:testDebugUnitTest` passed all 31 unit tests.
- `./gradlew :app:assembleRelease -PversionName=0.1.16 -PversionCode=16` built a v2-signed local release APK.
- The emulator mock fixture placed a failed Turn error between that Turn and the following Turn.
- A forced WebSocket close (`1012`) followed by seven seconds of rejected reconnects produced automatic retries and a successful reattach; the mock observed `thread/resume` for the same thread after reconnect.
- The mock bridge was local-only and did not read or modify a user `CODEX_HOME`.

## Verification commands

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew testDebugUnitTest
./gradlew assembleRelease
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

## Verification results (2026-11-01)

- `./gradlew :app:testDebugUnitTest :app:assembleRelease -PversionName=0.1.15 -PversionCode=15 --no-daemon` passed all 27 unit tests and built the release APK.
- Android 15 (API 35, x86_64) emulator smoke against the local mock bridge rendered `PROJECTS · 2`, grouped `work` (`/home/test/work`) and `other` (`/home/test/other`), kept the active `work` project first, and showed no duplicate empty project.
- Project search reduced the picker from two projects to only `other` when typing `other`.
- The folder browser opened at `/home/test/work`, navigated to `/home/test`, selected `/home/test/new-project`, and updated the new-chat project bar and composer path.
- Sending from that new chat logged `thread/start` with `"cwd":"/home/test/new-project"` and `turn/start` with the same `cwd`.
- The mock bridge was local-only and did not read or modify a user `CODEX_HOME`. This Android build is local verification only and has not been published.

## Verification results (2026-10-31)

- `./gradlew :app:testDebugUnitTest --no-daemon` passed all 21 unit tests.
- `./gradlew :app:assembleRelease --no-daemon` completed successfully.
- `apksigner verify --verbose --print-certs` reported a valid APK Signature Scheme v2 signature.
- An Android 15 (API 35, x86_64) emulator installed the release APK and verified URL onboarding, WebView login, native thread rendering, streamed app-server notifications, a command approval round-trip (`{"decision":"accept"}`), and automatic reconnect after a cold app restart.
- The emulator smoke used a local mock Codex Web bridge at `10.0.2.2:3100`, not a real user `CODEX_HOME` or the local production server.
- A server-model smoke returned an OpenAI catalog from `model/list` plus `model=deepseek-flash` / `model_provider=deepseek` from `config/read`. The Android UI showed only `deepseek-flash`, and `turn/start` sent `"model":"deepseek-flash"`.
- A negative model smoke forced both `config/read` request variants to fail. The app fell back to `model=deepseek-flash` / `modelProvider=deepseek` in `thread/list`, still showed only `deepseek-flash`, and displayed `app-server.config/read` with the failure instead of exposing the OpenAI catalog.
- Paginated-history smoke observed `thread/resume` with `excludeTurns=true`, metadata-only `thread/read`, and `thread/turns/list` with `itemsView=full`. “Load earlier messages” issued a second request with the returned cursor, prepended the older turns, and removed the control when `nextCursor` was null.
- Command-card smoke observed `item/commandExecution/terminalInteraction` and rendered `mock terminal input` under “Terminal input” without an `unhandled:` diagnostic. The mock `deprecationNotice` appeared as `app-server.deprecationNotice` without an `unhandled:` prefix.
- The initialization diagnostic renders `BuildConfig.VERSION_NAME`. Local builds without `-PversionName` report the default `0.1.0`; the release workflow injects the published release version.
- GitHub Actions run `41247303356` published these model fail-safe, history pagination, terminal-input, and diagnostics changes as `android-v0.1.14` with `codex-web-android-0.1.14.apk` and a portable `.sha256` asset.
- Re-download verification passed `sha256sum -c` and `apksigner verify --verbose --print-certs`. The released APK is signed by the persistent 4096-bit RSA certificate with SHA-256 fingerprint `dacd89b1d753f0a0709b7feb263dba6c2fc7cb64b409e4d8da00a8e3c11f9aac`.
- GitHub Actions run `41203481232` published release `android-v0.1.7` with `codex-web-android-0.1.7.apk` and a portable `.sha256` asset.
- Re-download verification passed `sha256sum -c` and `apksigner verify --verbose --print-certs`; the release is signed by a self-signed 4096-bit RSA certificate.

## Remaining gaps

- MCP elicitation form rendering (request can be cancelled natively).
- Image/file attachment picking.
- Rich markdown beyond fenced code blocks.
- Multiple simultaneous pending request forms are queued one at a time in the composer area.
