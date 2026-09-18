# salchang

Android client for a tmux server on another machine on your tailnet. SSH only,
no local terminal: the app attaches to tmux in control mode and shows every tmux
window as a tab. Each window has a **Terminal** tab and an **Info** tab that
renders per-window metadata (currently the JSON Claude Code passes to status
line scripts).

See `docs/DESIGN.md` for the architecture and the reasons behind it.

## Layout

- `tmuxctl/` — pure-JVM tmux control-mode client (tested against local tmux)
- `terminal/` — vendored Termux terminal emulator + view (Apache-2.0), pty code replaced
- `app/` — the Android app (sshj transport, Compose UI)
- `remote/` — scripts for the tmux host

## Build

Requires JDK 17, the Android SDK (platform 37, build-tools 36) and `local.properties`
pointing at it (`sdk.dir=...`).

```sh
./gradlew :tmuxctl:test :terminal:testDebugUnitTest :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug                                                  # APK in app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Remote setup

1. Install `remote/salchang-statusline` on the tmux host (needs `jq`):

   ```sh
   install -m 0755 remote/salchang-statusline ~/.local/bin/salchang-statusline
   ```

2. Point Claude Code at it in `~/.claude/settings.json`:

   ```json
   { "statusLine": { "type": "command", "command": "~/.local/bin/salchang-statusline" } }
   ```

   The script stores each status update on the tmux pane Claude Code runs in
   (user option `@salchang_meta`) and prints a normal status line. Anything else
   can publish metadata the same way:

   ```sh
   tmux set-option -p -t "$TMUX_PANE" @salchang_meta '{"kind":"note","data":{"text":"hello"}}'
   ```

3. In the app, add the host (tailnet name, user, authentication, tmux session name). The app
   attaches with `tmux -C new-session -t <session>` (a grouped session, so your
   desktop client keeps its own current window) and kills that grouped session
   when it disconnects.

## Authentication

- **Tailscale SSH** needs no key: pick "None (Tailscale SSH)" for the host. The
  server authenticates by tailnet identity and accepts the SSH `none` method.
  If your ACL uses check mode, the approval URL the server sends is shown in the
  connection banner while connecting; tap it (or Copy it) and approve it in a
  browser within the handshake timeout (2 minutes).
- **SSH key** (plain sshd): either generate an ed25519 key under Keys and add its
  public key to `~/.ssh/authorized_keys` on the host (Copy or Share it from the
  key's dialog, e.g. over Tailscale SSH), or get an existing OpenSSH private key
  file onto the phone (Taildrop, USB, `adb push`) and Import it. Encrypted keys
  ask for their passphrase at connect time; it is never stored.
