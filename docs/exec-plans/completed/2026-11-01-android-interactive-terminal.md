# Android interactive terminal

## Objective

Add the standalone PTY terminal available in the Codex Web workspace sidebar to the native Android app, including ANSI rendering, keyboard input, PTY resize, process exit handling, restart, and cleanup.

## Implementation

- [x] Added a `TerminalEmulator` backed by ConnectBot `org.connectbot:termlib:0.0.10` and libvterm.
- [x] Added typed `process/spawn`, `process/writeStdin`, `process/resizePty`, and `process/kill` request builders.
- [x] Captured `initialize.platformFamily` and selected the matching shell command.
- [x] Routed `process/outputDelta` and `process/exited` notifications by `processHandle` without adding chat diagnostics.
- [x] Added ordered terminal input and resize channels with buffered input while the PTY is starting.
- [x] Added **More → Terminal**, a full terminal screen, soft-keyboard toggle, key bar, restart, close, and exit overlays.
- [x] Added close, restart, sign-out, disconnect, and ViewModel teardown handling.
- [x] Compensated for a late successful `process/spawn` after the terminal was closed or restarted.
- [x] Updated compileSdk and the release workflow to platform/build-tools 36.

## Verification

- [x] `./gradlew :app:testDebugUnitTest --no-daemon` passed 73 tests.
- [x] `./gradlew :app:assembleRelease --no-daemon` produced a v2-signed release APK.
- [x] Android 15 emulator smoke rendered mocked PTY output and sent the expected spawn environment and initial size.
- [x] Typed input reached `process/writeStdin` byte-for-byte; Ctrl+C sent `0x03`.
- [x] Opening and closing the soft keyboard emitted PTY resize requests.
- [x] Restart emitted `process/kill` for the old handle followed by a new `process/spawn`.
- [x] Closing the terminal emitted `process/kill` and returned to the conversation screen.
- [x] A forced bridge disconnect showed the reconnect guidance, automatic reconnection restored the bridge, and `process/exited` rendered the exit overlay with the server-provided code.

See `docs/handover/2026-11-01-interactive-terminal.md` for the protocol and lifecycle details.
