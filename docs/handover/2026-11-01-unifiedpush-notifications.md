# UnifiedPush notifications handover

Cross-link: server counterpart at `codex-web/docs/handover/2026-11-01-unifiedpush-notifications.md`.

## Architecture

- `CodexWebPushService` extends the UnifiedPush connector `PushService` and receives endpoint, message, registration-failure, and unregistration callbacks.
- `PushDistributors` discovers installed distributors, persists a per-install UUID, and starts/stops UnifiedPush registration.
- `PushRegistrationClient` reuses the WebView-authenticated session cookie and the authenticated Codex Web bridge to call `codexWeb/push/config`, `codexWeb/push/register`, and `codexWeb/push/unregister`.
- `PushPayloadParser` accepts only version 1 payloads with the five supported event kinds and non-empty title/body fields.
- `PushNotifier` creates the `Codex events` high-importance channel and opens the target thread when a notification is tapped.
- `CodexWebViewModel` owns distributor selection, permission-gated enablement, retry, reconnect re-registration, sign-out cleanup, and notification deep-link routing.

## Registration flow

1. The user installs a distributor and opens **More → Notifications**.
2. The app requests Android notification permission when needed, saves the distributor, and calls `UnifiedPush.register` with the persisted instance UUID.
3. The distributor returns an endpoint and Web Push key set through `onNewEndpoint`.
4. The app sends those values over the authenticated bridge with the instance UUID.
5. If the distributor requires VAPID, `onRegistrationFailed(VAPID_REQUIRED)` fetches the public key with `codexWeb/push/config` and retries registration.

The server identity comes from the existing bridge/broker session. No separate push credential is introduced.

## Limitations and security

- The connector dependency is pinned to `org.unifiedpush.android:connector:3.0.10` because newer releases require a Kotlin metadata version newer than this project's Kotlin 2.0.21 toolchain. Re-evaluate when the project upgrades Kotlin.
- The app stores the distributor package, instance UUID, short status/error strings, and public VAPID key. It never stores the push endpoint, Web Push private material, OAuth tokens, refresh tokens, or API keys.
- Notifications are suppressed when Android permission or the global app notification toggle is off. The settings dialog links to system notification settings for recovery.
- A live UnifiedPush delivery test needs a distributor, push service, and Android runtime; the JVM verification does not prove end-to-end delivery.

## Verification

- `./gradlew testDebugUnitTest`
- `./gradlew assembleRelease`

Merged manifest verification confirms `POST_NOTIFICATIONS` and the `org.unifiedpush.android.connector.PUSH_EVENT` service action.
