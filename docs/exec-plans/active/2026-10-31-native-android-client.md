# Native Android client

## Goal

Create a separately hosted Android client for Codex Web. It accepts the deployment URL, authenticates through the deployment's normal WebView login, attaches to the same authenticated app-server bridge, and renders a native Compose interface.

## Scope

- URL onboarding and Android-only URL persistence.
- WebView authentication with cookie handoff; never persist OAuth/API credentials in app preferences.
- App-server JSON-RPC transport over the deployment's authenticated WebSocket bridge.
- Native thread list, model picker, streaming chat, tool/reasoning cards, plan/goal/token usage, interrupt, approvals, user-input requests, and diagnostics.
- Unit tests for protocol reduction, URL resolution, session discovery, and approval payload schemas.
- GitHub Actions release workflow producing a self-signed APK and publishing it to GitHub Releases.

## Verification

- `./gradlew testDebugUnitTest`
- `./gradlew assembleRelease`
- `apksigner verify --print-certs`
- Launch the app in an Android emulator and inspect the onboarding/authentication/native connected states.
- Trigger the GitHub workflow and verify a release asset exists.

## Status

Implementation, unit tests, local release build, emulator UI smoke, and automatic reconnect verification are complete. GitHub workflow and published release asset verification are pending.
