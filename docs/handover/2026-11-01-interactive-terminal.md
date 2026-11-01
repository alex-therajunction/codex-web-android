# Interactive terminal handover

## Scope

The native Android app now exposes the same standalone PTY terminal as the Codex Web workspace sidebar. This is separate from rendering `item/commandExecution/terminalInteraction` inside a conversation command card.

Web reference:

- `codex-web/src/codex-web/process-terminal.ts`
- `codex-web/src/components/layout/WorkspaceSidebar/TerminalPanel.tsx`
- `codex-web/src/components/layout/WorkspaceSidebar/TerminalFrame.tsx`

## Architecture

- `TerminalProcess.kt` owns typed terminal sizes, process events, shell selection, environment values, base64 encoding/decoding, and the four `process/*` request builders.
- `CodexRpcClient` captures `platformFamily` from the `initialize` result.
- `AppServerRepository` exposes terminal subscriptions and process requests, while dispatching process notifications outside chat state.
- `CodexWebViewModel` owns the emulator, process handle, ordered input/resize channels, startup input buffer, status transitions, restart, and cleanup.
- `TerminalScreen.kt` renders the libvterm Compose terminal, keyboard toggle, mobile key bar, status, restart action, and exit overlay.
- `MainScreen.kt` opens the terminal from **More → Terminal**, and `CodexWebApp.kt` replaces the conversation screen while a terminal session is active.

## Protocol

`process/spawn` mirrors Codex Web:

- Unix command: `["/bin/sh", "-lc", "exec \"${SHELL:-/bin/sh}\" -i"]`.
- Windows command: `["powershell.exe"]`.
- `tty=true`, `streamStdin=true`, and `streamStdoutStderr=true`.
- `outputBytesCap=null` and `timeoutMs=null`.
- Environment: `TERM=xterm-256color`, `COLORTERM=truecolor`, and `CLICOLOR=1`.
- `cwd` is the selected thread/project path and `size` is the current emulator dimensions.

The process handle is unique per session (`codex-web-android-terminal-<UUID>`). Follow-up requests use:

- `process/writeStdin` with base64 `deltaBase64`.
- `process/resizePty` with `{ cols, rows }`.
- `process/kill` with the handle.

Notifications are routed by `processHandle`:

- `process/outputDelta` decodes `deltaBase64` into the libvterm emulator.
- `process/exited` appends an exit line, marks the session exited, and stops accepting input.

## Lifecycle and ordering

- Input typed while `process/spawn` is pending is buffered up to 64 KiB and flushed immediately after the process becomes running.
- Input and resize channels serialize follow-up requests. Resize is conflated so only the latest pending size is sent.
- Every emulator resize while running is forwarded to `process/resizePty`; a final resize is sent when startup completes.
- Close, restart, sign-out, and ViewModel teardown unsubscribe notifications, stop jobs, and send `process/kill`.
- If a closed or restarted session's spawn response arrives late, the app sends `process/kill` for that late handle. The Codex Web bridge also kills connection-owned handles when the WebSocket closes.
- A bridge disconnect changes an active terminal to the error state and asks the user to restart after reconnecting.

## Terminal dependency

The app uses `org.connectbot:termlib:0.0.10` (Apache-2.0), which provides the Compose terminal, ANSI/true-color rendering, input dispatch, and libvterm-backed emulation. Version `0.0.10` is the compatible release for the app's Kotlin 2.2.21, Compose BOM 2025.12.00, and compileSdk 36 toolchain. It has no public `close()` API, so the app reuses one emulator across restarts and clears it before spawning a new PTY.

The dependency required upgrading AGP to 8.9.1, Gradle to 8.11.1, Kotlin to 2.2.21, compileSdk to 36, and the Compose BOM to 2025.12.00.

## Verification

- `./gradlew :app:testDebugUnitTest --no-daemon`: 73 tests passed.
- `./gradlew :app:assembleRelease --no-daemon`: release APK built and `apksigner` verified its v2 signature.
- Android 15 emulator with a mock authenticated deployment and bridge:
  - terminal output rendered with ANSI styling;
  - `process/spawn` contained the expected shell, environment, cwd, and size;
  - typed input reached `process/writeStdin` byte-for-byte;
  - the Ctrl+C key sent `0x03`;
  - showing/hiding the keyboard changed PTY rows through `process/resizePty`;
  - restart killed the old handle and spawned a new one;
  - close sent `process/kill` and returned to the conversation;
  - a forced bridge disconnect showed the connection-lost state, automatic reconnect restored the bridge, and `process/exited` rendered the exit overlay with code 7.

## Remaining gaps

- Mouse reporting and richer mobile modifier keys are not exposed beyond the current key bar.
- Exit/disconnect overlays are manually smoke-tested but do not yet have a screenshot regression test.
- The terminal dependency has native code for armeabi-v7a, arm64-v8a, x86, and x86_64; CI must continue building against SDK 36.
