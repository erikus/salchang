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
| Keys | Per host `authMethod`: `NONE` (SSH `none` method, for Tailscale SSH which authenticates by tailnet identity; a check-mode approval URL arrives as the SSH auth banner and is shown while connecting) or `KEY`. Keys: import OpenSSH private key file (SAF picker) or generate ed25519 in-app; stored in app-private storage; passphrase asked at connect time, never stored | Tailnet use; simple. |
| Host keys | TOFU: first connect shows fingerprint, accept stores it in app-private `known_hosts` | Standard. |
| Input | `send-keys -t %N -H <hex bytes>` only | Sidesteps all tmux quoting. |
| Initial screen | `capture-pane -p -e -J -N -t %N -S -<history>` then live `%output` | Standard control-mode bootstrap (same as iTerm2). |

## Modules

```
salchang/
  settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
  tmuxctl/      Kotlin JVM library: control-mode client (no Android deps)
  terminal/     Android library: vendored Termux emulator + view (Java)
  app/          Android app: SSH transport (sshj), Compose UI, key store
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
data class TmuxWindow(id, index, name, active, layout, activePaneId, panes: List<TmuxPane>, meta: String?)
data class TmuxPane(id, windowId, width, height, active, currentCommand, title)
```

Protocol facts (tmux 3.4, verified locally):
- Every command → `%begin t n f` … `%end t n f` | `%error t n f`. First block after attach is unsolicited.
- `%output %N data` with bytes < 0x20 and `\` escaped as `\ooo` octal.
- `%subscription-changed name $S @W idx %P : value` (pane), `… idx - : value` (window).
- `#` must be quoted in commands (`refresh-client -B 'name:%*:#{@opt}'`) — it starts a comment.
- `%layout-change @W layout visible-layout flags` — carries pane sizes; re-list panes on it.
- `%window-add/%window-close/%window-renamed/%session-window-changed/%window-pane-changed/%exit`.
- On `%window-add`, `%layout-change`, `%window-renamed` we re-run `list-windows`/`list-panes -s` with tab-separated `-F` formats.

### terminal (Android library)

Vendored from termux-app commit `084d709fbf23ea83b5cb85fd3d795c775be06676` (2026-09-16), Apache-2.0
modules only. Changes: `TerminalSession` rewritten (no JNI, no pty): constructor
`TerminalSession(TerminalSessionClient, TerminalSink)` where `TerminalSink.write(bytes)` receives
terminal input to forward; `updateSize` resizes the emulator only; `JNI.java`/`jni/` dropped.
Everything under `com.termux.terminal` / `com.termux.view` otherwise unchanged.

### app

- `ssh/`: `SshControlTransport` — sshj `SSHClient` → exec `tmux -C new-session -t <session>` (+ optional `-L socket`/`-S path`); implements `ControlTransport`. Host key TOFU via `OpenSSHKnownHosts` in app files dir. `AndroidCrypto.install()` swaps the BC provider once at app start.
- `data/`: `HostProfile` (name, hostname, port, user, authMethod, keyId, tmuxSession, tmuxSocket) persisted with DataStore/JSON; `KeyStore` (files dir; generate ed25519 with BC; import via SAF).
- `session/`: `SessionController` — owns SSH + `TmuxControlClient`, one `TerminalSession`/emulator per pane, bootstraps each pane with `capturePane`, feeds `%output` bytes to emulators, forwards typed bytes to `sendKeys`, parses `@salchang_meta` JSON per pane → window meta.
- `ui/`: `HostsScreen`, `HostEditScreen`, `SessionScreen` (top: window tab row + "+" ; per window: Terminal | Info tabs; extra-keys bar Esc/Tab/Ctrl/Alt/arrows/Home/End/PgUp/PgDn like Termux; connection banner with reconnect).
- Info tab renders the Claude Code payload: model, cwd/project, git branch/worktree, context used %, cost, duration, lines +/-, rate limits, updated-at; falls back to pretty-printed raw JSON for unknown payloads.

### remote

`remote/salchang-statusline` (POSIX sh + jq): reads Claude Code status JSON on stdin, writes
`{"kind":"claude-code","updated_at":<epoch>,"data":<json>}` to `@salchang_meta` on `$TMUX_PANE`,
then prints a normal one-line status. Install: copy to `~/.local/bin`, set in `~/.claude/settings.json`:
`"statusLine": {"type":"command","command":"~/.local/bin/salchang-statusline"}`.

## Not in v1
Multiple simultaneous hosts, pane layout rendering, copy-mode/scrollback UI beyond the emulator's own transcript, mouse reporting, file transfer, Tailscale-embedded networking (the Tailscale app provides the VPN).
