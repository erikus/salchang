# salchang — design

Android client for a remote tmux server reached over SSH on a tailnet. No local
terminal. Every tmux window is a first-class tab in the app; each window has a
**Terminal** tab and an **Info** tab showing per-window metadata (for now: the
JSON Claude Code hands to status line scripts).

## Decisions (with reasons)

| Topic | Decision | Why |
|---|---|---|
| Language / UI | Kotlin, Jetpack Compose (Material 3) | Android ecosystem default. |
| SSH | `com.hierynomus:sshj` 0.40.0 + `bcprov-jdk18on` | Reputable, maintained, pure Java; BC provider must replace Android's stripped "BC". |
| Terminal emulation | Termux `terminal-emulator` + `terminal-view` (Apache 2.0), **vendored** into `terminal/` | Proven Android VT emulator. Vendored because `TerminalSession` is `final` and forks a local pty via JNI; we replace it with a class of the same name that writes to a callback instead. Pinned upstream commit recorded in `terminal/UPSTREAM.md`. |
| tmux protocol | tmux control mode (`tmux -C`) in a pure-JVM module `tmuxctl/` | Testable on the dev box against local tmux without Android or SSH. |
| Attach method | `tmux -C new-session -t <session>` (grouped session, own name `salchang-<random>`), killed on disconnect | Mirrors the user's own workflow (`new-session -t S` + `destroy-unattached keep-group`) and gives the phone its own current window without yanking the desktop's. |
| Terminal size | Emulator always follows the **pane size tmux reports**; app also sends `refresh-client -C WxH`; view auto-fits the font to the pane width (min font size, then horizontal scroll) | Grouped sessions share windows; tmux is the source of truth for the grid. |
| Panes | One window = one tab; the **active pane** is rendered; if a window has >1 pane, a chip row switches which pane is shown | Keeps windows first-class and v1 simple. |
| Metadata transport | tmux **pane user option** `@salchang_meta`, pushed to the app via `refresh-client -B 'meta:%*:#{@salchang_meta}'` (`%subscription-changed`) | Same connection as everything else, no polling, no file paths to agree on. Verified with tmux 3.4: value round-trips JSON with `#`, `\`, `"`, `;` intact. Notifications are rate-limited by tmux to ~1/s. |
| Metadata producer | `remote/salchang-statusline` — a Claude Code `statusLine` command that wraps the stdin JSON and stores it on the pane (`$TMUX_PANE`) | The agent already runs it; nothing new to instruct. Other producers can set the same option. |
| Auth | Tailscale SSH only: the SSH `none` method, the server authenticates by tailnet identity; a check-mode approval URL arrives as the SSH auth banner and is shown while connecting. No keys or passwords on the phone. | Tailnet use; nothing to store or protect on the device. |
| Host keys | TOFU: first connect shows fingerprint, accept stores it in app-private `known_hosts` | Standard. |
| Input | `send-keys -t %N -H <hex bytes>` only | Sidesteps all tmux quoting. |
| Key bindings | Emulated client-side: the prefix key and the `prefix`/`root` key tables are loaded after connect (`show-options -gv prefix`, `list-keys -T prefix`, `list-keys -T root`); typed bytes are tokenized into keys, a matched binding runs its command on the control channel, everything else goes to `send-keys` | `send-keys -H` bypasses tmux's key tables, so tmux never sees the prefix (it printed literally). Interactive commands (`command-prompt`, `confirm-before`, `display-menu`, `choose-*`, `copy-mode`) do nothing useful in control mode and are not supported. |
| Initial screen | `capture-pane -p -e -J -N -t %N -S -<history>` then live `%output` | Standard control-mode bootstrap (same as iTerm2). |

## Modules

```
salchang/
  settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
  tmuxctl/      Kotlin JVM library: control-mode client (no Android deps)
  terminal/     Android library: vendored Termux emulator + view (Java)
  app/          Android app: SSH transport (sshj), Compose UI
  remote/       scripts to install on the tmux host (status line)
  docs/         this file
```

### tmuxctl (JVM)

```kotlin
interface ControlTransport { val input: InputStream; val output: OutputStream; fun close() }

class TmuxControlClient(transport: ControlTransport, scope: CoroutineScope) {
  val state: StateFlow<TmuxState>
  val events: SharedFlow<TmuxEvent>                 // PaneOutput(paneId, bytes), Exit(reason), ...
  suspend fun command(line: String): CommandResult // correlates %begin/%end/%error
  suspend fun start(clientCols: Int, clientRows: Int)   // sends refresh-client -C, subscribes, lists windows/panes
  suspend fun sendKeys(paneId: String, bytes: ByteArray)   // -H hex
  suspend fun capturePane(paneId: String, historyLines: Int): ByteArray
  suspend fun setClientSize(cols: Int, rows: Int)
  suspend fun selectWindow(windowId: String); newWindow(); killWindow(id); renameWindow(id, name)
  fun close()
}
data class TmuxState(sessionId: String?, sessionName: String?, activeWindowId: String?, windows: List<TmuxWindow>)
data class TmuxWindow(id, index, name, active, layout, activePaneId, panes: List<TmuxPane>, meta: Map<paneId, String>, statusLabel)
data class TmuxPane(id, windowId, width, height, active, currentCommand, title)
```

Protocol facts (tmux 3.4, verified locally):
- Every command → `%begin t n f` … `%end t n f` | `%error t n f`. First block after attach is unsolicited.
- `%output %N data` with bytes < 0x20 and `\` escaped as `\ooo` octal.
- `%subscription-changed name $S @W idx %P : value` (pane), `… idx - : value` (window).
- `#{T:window-status-format}` (tmux >= 3.2) expands the server's `window-status-format` option for the target window, including the user's own `#{E:@…}` user options and `#[…]` styles. Subscribed per window (`refresh-client -B 'tab:@*:#{T:window-status-format}'`) tmux re-sends it when the expansion changes, e.g. on `cd` or when the pane's command changes — neither has a notification of its own.
- `#` must be quoted in commands (`refresh-client -B 'name:%*:#{@opt}'`) — it starts a comment.
- `%layout-change @W layout visible-layout flags` — carries pane sizes; re-list panes on it.
- `%window-add/%window-close/%window-renamed/%session-window-changed/%window-pane-changed/%exit`.
- On `%window-add`, `%layout-change`, `%window-renamed` we re-run `list-windows`/`list-panes -s` with tab-separated `-F` formats.

#### Key bindings (`TmuxKeyBindings.kt`)

- `parseListKeys` reads `bind-key [-r] -T <table> <key> <command>` lines. The key token is
  backslash-escaped (`\"`, `\#`, `\;`, `\\`, `\{`, `\~`) or quoted (`'M-"'`, `"M-{"`); the
  command is kept as printed except that a standalone `\;` (a `bind-key` argument separating
  commands) becomes `;` (what a control-mode command line needs). Notes (`-N`) are not printed.
- `TmuxKeyCodes` maps names to bytes: `C-x` control characters (`C-Space` = 0x00, tmux prints
  `C-@`/`C-i`/`C-m`/`C-[` as `C-Space`/`Tab`/`Enter`/`Escape`), named keys (`Up`.. as CSI A-D,
  `Home`/`End` as CSI H/F, `PPage`/`NPage`/`IC`/`DC` as CSI 5/6/2/3 `~`, F1-F4 as SS3 P-S, F5-F12
  as CSI 15..24 `~`, `BTab` as CSI Z), `M-x` as ESC + x, `S-`/`C-` on named keys as xterm
  `CSI 1;m X`. Input also accepts SS3 arrows/home/end (application cursor mode), `CSI 1~`/`4~`,
  and `CSI 1;3D`-style meta (what the Termux key handler sends for Alt+arrow). Aliases tmux
  accepts (`PgUp`, `PageUp`, `Insert`...) collapse to the printed names by round-tripping.
- `TmuxKeyRouter` is a small state machine: prefix key → armed (shown as a `PREFIX` chip);
  next key looked up in the `prefix` table (found → run, unbound → dropped like tmux, prefix
  again with no binding or `send-prefix` → prefix bytes sent); unarmed keys are looked up in
  the `root` table. `-r` bindings get no repeat timer. Bytes that are not keys (emulator
  replies such as cursor position reports) pass through and do not disarm.
- Bound commands run through `TmuxControlClient.commandInKeyOrder`, which takes the same mutex
  as `send-keys` (so keys and commands reach tmux in typing order) and writes a fence
  (`display-message -p salchang-fence-N`) after the command. Needed because a `;` list, or a
  command like `if-shell -F` / `display-menu`, produces one `%begin`/`%end` block *per nested
  command*, all with flag 1; the reader drops blocks until the fence's echo instead of matching
  them to later commands. Commands whose nested commands run asynchronously (`run-shell`,
  `if-shell` with a shell command) can still emit blocks after the fence; those are matched to
  whatever is pending, as before.

### terminal (Android library)

Vendored from termux-app commit `084d709fbf23ea83b5cb85fd3d795c775be06676` (2026-09-16), Apache-2.0
modules only. Changes: `TerminalSession` rewritten (no JNI, no pty): constructor
`TerminalSession(TerminalSessionClient, TerminalSink)` where `TerminalSink.write(bytes)` receives
terminal input to forward; `updateSize` resizes the emulator only; `JNI.java`/`jni/` dropped.
Everything under `com.termux.terminal` / `com.termux.view` otherwise unchanged.

### app

- `ssh/`: `SshControlTransport` — sshj `SSHClient` → exec `tmux -C new-session -t <session>` (+ optional `-L socket`/`-S path`); implements `ControlTransport`. Host key TOFU via `OpenSSHKnownHosts` in app files dir. `AndroidCrypto.install()` swaps the BC provider once at app start.
- `data/`: `HostProfile` (name, hostname, port, user, tmuxSession, tmuxSocket) persisted with DataStore/JSON.
- `session/`: `SessionController` — owns SSH + `TmuxControlClient`, one `TerminalSession`/emulator per pane, bootstraps each pane with `capturePane`, feeds `%output` bytes to emulators, forwards typed bytes to `sendKeys`, parses `@salchang_meta` JSON per pane → window meta.
- `ui/`: `HostsScreen`, `HostEditScreen` (its "Browse" button lists the host's tmux session groups/sessions via a one-off SSH exec of `tmux list-sessions -F ...` — `SshControlTransport.runOnce` + `buildListSessionsCommand` — and fills the tmux session field with the group or session name that `new-session -t` needs), `SessionScreen` (top: window tab row + "+" — each tab shows `TmuxWindow.tabLabel()`, the server's rendered `window-status-format` with styles stripped, so the tabs match the user's own tmux status line and fall back to `index:name`; per window: Terminal | Info tabs; extra-keys bar Esc/Tab/Ctrl/Alt/arrows/Home/End/PgUp/PgDn like Termux; connection banner with reconnect).
- Info tab renders the Claude Code payload: model, cwd/project, git branch/worktree, context used %, cost, duration, lines +/-, rate limits, updated-at; falls back to pretty-printed raw JSON for unknown payloads.

### remote

`remote/salchang-statusline` (POSIX sh + jq): reads Claude Code status JSON on stdin, writes
`{"kind":"claude-code","updated_at":<epoch>,"data":<json>}` to `@salchang_meta` on `$TMUX_PANE`,
then prints a normal one-line status. Install: copy to `~/.local/bin`, set in `~/.claude/settings.json`:
`"statusLine": {"type":"command","command":"~/.local/bin/salchang-statusline"}`.

## Future Work
Multiple simultaneous hosts, pane layout, copy-mode/scrollback UI beyond emulator's own transcript, mouse reporting, file transfer.
