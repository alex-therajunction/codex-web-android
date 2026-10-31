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

## Verification results

- 11 unit tests passed.
- Local release build completed and produced a v2-signed APK.
- Android 15 emulator smoke verified onboarding, WebView login, native app-server rendering, streaming, command approval, and automatic reconnect against a local mock bridge.
- GitHub Actions run `41203481232` passed tests, signed-release build, `apksigner` verification, artifact upload, and GitHub Release publication.
- Release `android-v0.1.7` contains `codex-web-android-0.1.7.apk` and a portable SHA-256 checksum. The downloaded checksum passes `sha256sum -c`, and the downloaded APK passes `apksigner verify --verbose --print-certs` with a self-signed 4096-bit RSA certificate.

## Status

Complete. The native Android client is implemented and the verified self-signed APK is published at https://github.com/alex-therajunction/codex-web-android/releases/tag/android-v0.1.7.
