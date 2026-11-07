# Full Access Default Permission Checkbox

- The Android Full Access warning now mirrors Codex Web's “Use this mode for all future sessions” checkbox.
- Confirming without the checkbox changes only the selected thread and the current selection, matching the previous behavior.
- Confirming with the checkbox first applies the thread permission, then writes `default_permissions`, `approvals_reviewer`, `approval_policy`, and `sandbox_mode` through `config/batchWrite` with `reloadUserConfig: true`.
- The client reads `config/read` after the write and updates the new-chat default immediately, so selecting New Chat uses the persisted mode without reconnecting.
- A failed thread update or default save is surfaced through the existing error snackbar; the thread permission is not reverted if only the default save fails.
- `./gradlew :app:testDebugUnitTest :app:assembleRelease -PversionName=0.1.17 -PversionCode=17 --no-daemon` passed all 91 unit tests and built the release APK.
