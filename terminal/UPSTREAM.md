# Vendored terminal emulator and view

## Upstream

- Repository: https://github.com/termux/termux-app
- Commit: `084d709fbf23ea83b5cb85fd3d795c775be06676`
- Commit date: 2026-09-16 21:16:49 +0500
- Modules: `terminal-emulator` and `terminal-view`
- License: Apache-2.0 (see `LICENSE` in this directory). `termux-app` as a whole is GPLv3, but its
  `LICENSE.md` exempts `terminal-emulator` and `terminal-view`, which derive from
  [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator) and are
  Apache-2.0. Neither module carries its own LICENSE file upstream; `PopupWindowCompatGingerbread.java`
  carries an Apache-2.0 header from The Android Open Source Project.

## Files copied

Both upstream modules are merged into this single Gradle module (namespace `com.termux.view`).

From `terminal-emulator/src/main/java/com/termux/terminal/` into `src/main/java/com/termux/terminal/`:

`AndroidUtils.java`, `ByteQueue.java`, `ITermImage.java`, `KeyHandler.java`, `Logger.java`,
`TerminalBitmap.java`, `TerminalBuffer.java`, `TerminalColorScheme.java`, `TerminalColors.java`,
`TerminalEmulator.java`, `TerminalOutput.java`, `TerminalRow.java`, `TerminalSessionClient.java`,
`TerminalSession.java` (rewritten, see below), `TerminalSixel.java`, `TextStyle.java`, `WcWidth.java`

From `terminal-view/src/main/java/com/termux/view/` into `src/main/java/com/termux/view/`:

`GestureAndScaleRecognizer.java`, `TerminalRenderer.java`, `TerminalView.java`, `TerminalViewClient.java`,
`support/PopupWindowCompatGingerbread.java`, `textselection/CursorController.java`,
`textselection/TextSelectionCursorController.java`, `textselection/TextSelectionHandleView.java`

From `terminal-view/src/main/res/` into `src/main/res/`:

`drawable/text_select_handle_left_material.xml`, `drawable/text_select_handle_right_material.xml`,
`values/strings.xml`

Every file listed above except `TerminalSession.java` is byte-identical to upstream
(verified with `diff -rq`).

## Not copied

- `terminal-emulator/src/main/java/com/termux/terminal/JNI.java`
- `terminal-emulator/src/main/jni/` (`Android.mk`, `termux.c`)
- Upstream unit tests (`terminal-emulator/src/test/`)
- Upstream `build.gradle`, `proguard-rules.pro`, `AndroidManifest.xml` (both were empty `<manifest/>`)

## Modifications

1. `src/main/java/com/termux/terminal/TerminalSession.java` — rewritten. Same package and class name,
   still `public final class TerminalSession extends TerminalOutput`, so `TerminalView`,
   `TerminalRenderer`, `TextSelectionCursorController` and `TerminalEmulator` compile unchanged.
   - Removed: all JNI/pty/subprocess code (`JNI.createSubprocess`, `JNI.setPtyWindowSize`,
     `JNI.waitFor`, `JNI.close`, `Os.kill`), the reader/writer/waiter threads, `ByteQueue` fields,
     `MainThreadHandler`, `wrapFileDescriptor`, `cleanupResources`, and the constructor parameters
     `shellPath`, `cwd`, `args`, `env`, `transcriptRows`.
   - Constructor is now `TerminalSession(TerminalSessionClient client, TerminalSink sink)`.
   - Added `public static final int DEFAULT_TRANSCRIPT_ROWS = 2000`; `initializeEmulator` uses it.
   - `updateSize` now only calls `mEmulator.resize(...)` then `notifyScreenUpdate()` (upstream also
     called `JNI.setPtyWindowSize`).
   - `write(byte[], int, int)` forwards to `TerminalSink.write` while `isRunning()`.
   - Added `appendOutput(byte[] data, int offset, int count)`: feeds remote pane bytes into
     `mEmulator.append(...)` and calls `notifyScreenUpdate()`. Must be called on the main thread
     (upstream marshalled output through a `Handler`; this class does not).
   - `isRunning()` is backed by a boolean flag; added `setRunning(boolean)`. `finishIfRunning()`
     clears the flag and calls `client.onSessionFinished(this)` (upstream sent SIGKILL).
   - `getExitStatus()` returns 0, `getPid()` returns -1, `getCwd()` returns null.
   - Added `setFixedSize(int columns, int rows)` / `getFixedColumns()` / `getFixedRows()`. While a fixed
     size is set (both > 0), `updateSize(...)` ignores the columns/rows argument (which `TerminalView`
     derives from its own pixel size) and keeps the emulator at the fixed size, still applying the cell
     pixel sizes. `setFixedSize` also initializes/resizes the emulator immediately using the last cell
     pixel sizes seen (defaults `DEFAULT_CELL_WIDTH_PIXELS`/`DEFAULT_CELL_HEIGHT_PIXELS` before any view
     attached). The app uses this so the grid always matches the tmux pane size; `TerminalView` is unchanged.
   - `writeCodePoint`, `getTitle`, `getEmulator`, `reset`, `updateTerminalSessionClient`,
     `notifyScreenUpdate`, `mHandle`, `mSessionName`, and the `TerminalOutput` callbacks
     (`titleChanged`, `onCopyTextToClipboard`, `onPasteTextFromClipboard`, `onBell`,
     `onColorsChanged`) are unchanged from upstream.
2. `src/main/java/com/termux/terminal/TerminalSink.java` — new interface
   `void write(byte[] data, int offset, int count)` receiving bytes for the remote pane.
3. `src/test/java/com/termux/terminal/TerminalSessionTest.java` — new JVM unit test (not from upstream).
4. `build.gradle.kts` — new (Kotlin DSL) build file, not derived from upstream. Sets
   `testOptions.unitTests.isReturnDefaultValues = true` to match upstream's `terminal-emulator`
   test configuration.
