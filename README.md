# Codex Web Android

A native Android client for a self-hosted [Codex Web](https://github.com/dimitrovs/codex-web) deployment.

The app does not embed the Codex Web application UI. It uses a WebView only for the deployment's existing sign-in flow, reads the resulting authenticated session cookie from Android's WebView cookie store, and then talks directly to the same Codex Web bridge and app-server in a Jetpack Compose interface.

## What works

- Enter and persist the Codex Web deployment URL.
- Sign in through the deployment's normal password or SSO page in a WebView.
- Automatically reconnect on launch while the WebView session cookie is valid.
- Keep the session alive with WebSocket heartbeats and recover from in-session drops using exponential backoff while preserving the selected thread and project.
- Discover the bridge through `/api/codex/bridge-url`.
- Native thread drawer backed by `thread/list`, `thread/resume`, metadata-only `thread/read`, and paginated `thread/turns/list`, with a “Load earlier messages” control.
- Folder-based projects derived from thread `cwd`: the drawer groups conversations by server project, with an empty current project shown until its first conversation exists.
- Project-aware new chat: choose a recent project or browse the server directory tree through `fs/readDirectory`; the selected path is persisted and sent as `cwd` to `thread/start` and `turn/start`.
- Streaming assistant text from `item/agentMessage/delta`.
- Failed Turn errors render immediately after that Turn rather than at the end of the conversation.
- Native command, file-change, reasoning, plan, tool, and unknown-item rendering.
- Colorized per-Turn unified diffs from `turn/diff/updated` and `item/fileChange/patchUpdated`, with file and line-change counts.
- Model picker from the server's `model/list` catalog and `config/read` model/provider settings. If `config/read` fails, the app retries, then uses thread model metadata; it never falls back to an unverified OpenAI-only catalog.
- Interrupt active turns through `turn/interrupt`.
- Permission selector matching Codex Web: Ask for Approval, Auto Approval, Full Access, and the server-configured custom profile. Full Access requires confirmation and sends the app-server's `never`/`danger-full-access` settings.
- Command, file-change, and permission approval cards using the exact app-server response schemas.
- User-input question responses and MCP request cancellation.
- Plan, goal, token usage, account, and connection state from app-server notifications.
- Command terminal input from `item/commandExecution/terminalInteraction` and deprecation notices as sourced diagnostics.
- Unknown notifications and malformed bridge messages are kept in a native diagnostics panel.
- UnifiedPush notifications for completed or failed Turns, completed goals, approvals, and user-input prompts.
- Self-signed APK releases from GitHub Actions.

## Connection flow

```text
Android Compose UI
       |
       | JSON-RPC over WebSocket (Cookie + same-origin Origin)
       v
Codex Web authenticated bridge
       |
       v
codex app-server

Android WebView -> deployment /login -> HttpOnly codex_web_session cookie
```

The Android app resolves the session with `GET /api/auth/me` and `GET /api/codex/bridge-url` before opening the WebSocket. It never stores OAuth access tokens, refresh tokens, API keys, or copied `CODEX_HOME` credentials.

## Requirements

- Android 8.0 (API 26) or newer.
- A reachable Codex Web deployment with `CODEX_WEB_BRIDGE_URL` configured.
- The deployment must expose a `ws://` or `wss://` URL that is reachable from the Android device. A bridge URL pointing at the server's `127.0.0.1` is not reachable from a phone.
- HTTPS is recommended. HTTP is permitted for trusted LAN deployments.
- A UnifiedPush distributor such as Sunup or ntfy to receive background notifications.

## Notifications

Open **More → Notifications** in the app, choose an installed UnifiedPush distributor, and enable notifications. Android 13 and newer also require the system notification permission. If notifications are disabled at the OS level, the dialog links to the app's notification settings.

The app registers its endpoint with the connected Codex Web deployment over the authenticated bridge. Codex Web decides which events are notification-worthy; the app only renders validated payloads. It notifies for completed or failed Turns, achieved goals, command/file/permission approvals, and user-input questions, but not ordinary messages or streaming text.

Each app installation uses a separate UnifiedPush instance ID, so multiple Android devices can subscribe to the same Codex Web account. Signing out unregisters the device locally and asks Codex Web to remove its endpoint. If an endpoint becomes stale, Codex Web removes it after the push service reports `404` or `410`.

The app stores only the selected distributor package, its local UnifiedPush instance ID, registration status, and the public VAPID key. The encrypted endpoint and Web Push encryption keys are stored by Codex Web, not in Android preferences.

## Install

Download the latest `codex-web-android-*.apk` from [GitHub Releases](https://github.com/alex-therajunction/codex-web-android/releases), allow installs from unknown sources for your browser/file manager, and install it.

After an upgrade, open **More → Diagnostics** and confirm the `app-server.initialize` entry reports the installed `Codex Web Android <version>`. Use that version when reporting whether an upgrade took effect.

Each APK is self-signed. If repository signing secrets are not configured, the workflow creates an ephemeral self-signed key for that build; installing a later build may require uninstall/reinstall. Configure the secrets below for stable upgrades.

## Build locally

Prerequisites: JDK 17, Android SDK platform 35, and build-tools 35.0.0.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew testDebugUnitTest
./gradlew assembleRelease
```

The fallback release uses the local debug signing key when no release keystore is configured. The artifact is at `app/build/outputs/apk/release/app-release.apk`.

### Stable self-signed signing key

Create a keystore and add these GitHub repository secrets:

```bash
keytool -genkeypair \
  -keystore codex-web-release.jks \
  -storetype PKCS12 \
  -storepass 'replace-with-a-strong-password' \
  -keypass 'replace-with-a-strong-password' \
  -alias codex-web \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Codex Web Android, OU=Self-hosted, O=Codex Web Android, L=Local, ST=Local, C=US"

base64 -w0 codex-web-release.jks > codex-web-release.jks.b64
```

Add:

- `ANDROID_KEYSTORE_BASE64`: contents of `codex-web-release.jks.b64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS` (`codex-web`)
- `ANDROID_KEY_PASSWORD`

Do not commit the keystore or password.

## GitHub release pipeline

`.github/workflows/android-release.yml`:

- runs unit tests;
- builds a signed release APK;
- verifies the signature with `apksigner`;
- uploads the APK and SHA-256 checksum as workflow artifacts;
- publishes a GitHub Release on `v*` tags and manual `workflow_dispatch` runs.

Examples:

```bash
git tag v0.1.0
git push origin v0.1.0
```

or run **Actions → Build and publish Android APK → Run workflow**.

## Security notes

- The app persists the deployment URL, last selected server-provided model ID, last selected server project path, UnifiedPush distributor/instance/status settings, and the public VAPID key. Project paths and push metadata are not Codex credentials; the push endpoint and encryption keys remain server-side.
- WebView cookies are managed by Android's WebView cookie store, not copied into app preferences or logs.
- WebView file/content access is disabled and mixed content is rejected.
- SSL certificate errors are not bypassed. Use a trusted certificate for HTTPS deployments.
- The app does not modify `codex-core`; it uses the standard Codex Web bridge.
- The default permission profile asks before sandbox escapes, network access, or protected file changes. Auto Approval delegates review to the app-server's risk reviewer, and Full Access disables approval prompts for the selected thread after an explicit warning.
- MCP elicitation forms are not implemented in the native UI; the request is surfaced and can be cancelled. Command/file/permission approvals and Codex user-input questions have native UI.

## Project layout

- `app/src/main/java/.../data/`: deployment URL validation, authenticated HTTP discovery, preferences.
- `app/src/main/java/.../protocol/`: JSON-RPC transport, app-server models/reducer, request responses.
- `app/src/main/java/.../push/`: UnifiedPush registration, bridge subscription requests, payload validation, and notification rendering.
- `app/src/main/java/.../ui/`: Compose screens, chat rendering, approval and diagnostics surfaces.
- `app/src/test/`: reducer, project grouping/path browsing, URL/session, and exact approval response tests.
- `docs/handover/`: implementation handover and protocol notes.
