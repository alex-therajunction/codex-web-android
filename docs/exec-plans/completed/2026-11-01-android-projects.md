# Android folder projects

## Goal

Mirror Codex Web's folder-based project model in the native Android client. Projects come from `thread.cwd` and the current server working directory; no project database or fabricated project records are introduced.

## Scope

- Group `thread/list` conversations by normalized `cwd` in the drawer.
- Show the current project first, including an empty project before its first thread exists.
- Add a new-chat project selector with recent projects and server-directory browsing through `fs/readDirectory`.
- Persist the last selected server project path and use it for `thread/start` / `turn/start`.
- Keep existing-thread model/history behavior unchanged.

## Verification

- Unit tests for project grouping, path handling, directory response parsing, and existing catalog/reducer tests.
- `./gradlew testDebugUnitTest`
- `./gradlew assembleRelease`
- Emulator smoke against the mock bridge: grouped projects, project switching, folder browsing, no duplicate empty project, and new-thread cwd.

## Verification results

- All 27 unit tests passed with `./gradlew :app:testDebugUnitTest`; the new project tests cover grouping, active-empty insertion, path normalization, parent navigation, and `fs/readDirectory` parsing.
- The `0.1.15` release APK built successfully with `./gradlew :app:assembleRelease -PversionName=0.1.15 -PversionCode=15`.
- Emulator smoke rendered two project groups with the active project first, verified project search, browsed from `/home/test/work` to `/home/test`, selected `/home/test/new-project`, and confirmed the selected path in both `thread/start` and `turn/start` requests.
- No project registry, fabricated project records, or credential storage was introduced.

## Status

Complete for local implementation and verification. The APK has not been committed, pushed, or published.
