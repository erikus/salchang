#!/bin/sh
# tmux-demo.sh — start (or reset) the private tmux server the demo app connects to.
#
# Runs a separate tmux server (-L salchang-demo, no user config) with a fixed
# set of windows and hand-written @salchang_meta payloads, so captures look the
# same on every run and never show the user's real sessions. The payloads use
# source "demo" (or the legacy hook format with no source) which the real
# prober, started by the app, leaves untouched.
#
# Usage: tmux-demo.sh start|stop|status
set -eu

SOCKET_NAME="salchang-demo"
SESSION="demo"
META_OPTION="@salchang_meta"
STATE_DIR="${SALCHANG_DEMO_STATE:-$(cd "$(dirname "$0")" && pwd)/.state}"
PANES_DIR="$STATE_DIR/panes"
DEMO_WIDTH=100
DEMO_HEIGHT=40
STOP_WAIT_TICKS=50
DEMO_CWD="/home/demo/code/salchang"
# Fixed "now" so ages in the Info tab read as minutes, not years, on any run.
now=$(date +%s)

t() { tmux -L "$SOCKET_NAME" -f /dev/null "$@"; }

# pane_script NAME: prints a shell program that fills the pane with plausible
# output and then idles (sleep, so the pane stays alive without a shell prompt
# racing the capture). Written to a file so no quoting passes through tmux.
pane_script() {
    case "$1" in
        claude) cat <<'SCRIPT'
printf '\033[1m> Make the Info tab show context usage as a bar\033[0m\n\n'
printf '\033[2m● Reading app/src/main/kotlin/dev/estaab/salchang/ui/InfoTab.kt\033[0m\n'
printf '\033[2m● Reading app/src/main/kotlin/dev/estaab/salchang/meta/WindowMeta.kt\033[0m\n\n'
printf 'I will add a LinearProgressIndicator under the model chip when both\n'
printf 'context_used and context_window are present, and fall back to the raw\n'
printf 'token count otherwise.\n\n'
printf '\033[2m● Edit(app/src/main/kotlin/dev/estaab/salchang/ui/InfoTab.kt)\033[0m\n'
printf '\033[2m  ⎿  Added 14 lines, removed 3 lines\033[0m\n\n'
printf '\033[2m● Bash(./gradlew :app:testDebugUnitTest)\033[0m\n'
printf '\033[2m  ⎿  BUILD SUCCESSFUL in 41s · 136 tests\033[0m\n\n'
printf '\033[33m✻ Thinking…\033[0m\n'
SCRIPT
        ;;
        codex) cat <<'SCRIPT'
printf '\033[1;35m›\033[0m Add retry with backoff to the sync client\n\n'
printf '\033[2mcodex\033[0m\n'
printf 'I looked at sync/client.go. The Do method returns on the first transport\n'
printf 'error, so I will wrap it in a bounded retry loop with jittered backoff.\n\n'
printf '\033[2mexec\033[0m go test ./sync/...\n'
printf 'ok      example.com/api/sync    0.412s\n\n'
printf '\033[2mcodex\033[0m\n'
printf 'Done. Three retries, 200ms base, capped at 5s, only on net.Error.\n'
printf 'Want me to expose the limits as flags?\n\n'
printf '\033[2m› \033[0m'
SCRIPT
        ;;
        pi) cat <<'SCRIPT'
printf '\033[36m┃\033[0m Summarise the last week of changelog entries\n\n'
printf 'Here are the highlights from 2026-09-15 to 2026-09-22:\n\n'
printf '  • Window tabs now render the server side window-status-format\n'
printf '  • Session picker lists grouped sessions once\n'
printf '  • Agents are discovered without a host side hook\n\n'
printf 'Shall I turn this into a release note?\n\n'
printf '\033[36m┃\033[0m \033[2m…\033[0m\n'
SCRIPT
        ;;
        shell) cat <<'SCRIPT'
printf '\033[32mdemo@salchang\033[0m:\033[34m~/code/salchang\033[0m$ git log --oneline -3\n'
printf 'f12e594 Render window tabs from the server'"'"'s window-status-format\n'
printf '56e1e5b Add Claude Code GitHub workflow triggered by @claude mentions\n'
printf '638a2cc Emulate tmux key bindings, add session picker, fix terminal focus\n'
printf '\033[32mdemo@salchang\033[0m:\033[34m~/code/salchang\033[0m$ '
SCRIPT
        ;;
    esac
    printf 'exec sleep 2147483647\n'
}

# Payload for the claude window in the legacy hook format (no "source", full
# status-line JSON under "data") so the Claude-specific Info cards render too.
meta_claude() {
    jq -nc --arg cwd "$DEMO_CWD" --argjson now "$now" '{
        kind: "claude-code", updated_at: $now, agent_name: "Claude Code", version: "2.1.278",
        cwd: $cwd, session_id: "6f1c2a9e-demo-4a1b-9c3d-000000000001", title: "info-tab-bar",
        status: "busy", started_at: ($now - 1860), model: "claude-fable-5-1", model_name: "Fable 5.1",
        context_used: 83120, context_window: 200000, cost_usd: 1.42,
        data: {
            model: {id: "claude-fable-5-1", display_name: "Fable 5.1"},
            cwd: $cwd, version: "2.1.278", session_name: "info-tab-bar",
            workspace: {current_dir: $cwd, project_dir: $cwd,
                        repo: {host: "github.com", owner: "estaab", name: "salchang"}},
            cost: {total_cost_usd: 1.42, total_duration_ms: 1860000, total_api_duration_ms: 412000,
                   total_lines_added: 96, total_lines_removed: 21},
            context_window: {context_window_size: 200000, used_percentage: 41.6, remaining_percentage: 58.4,
                             total_input_tokens: 412000, total_output_tokens: 18400,
                             current_usage: {input_tokens: 3120, output_tokens: 900,
                                             cache_creation_input_tokens: 12000, cache_read_input_tokens: 68000}},
            rate_limits: {five_hour: {used_percentage: 23, resets_at: ($now + 9000)},
                          seven_day: {used_percentage: 61, resets_at: ($now + 300000)}},
            effort: {level: "high"},
            pr: {number: 42, url: "https://github.com/estaab/salchang/pull/42", title: "Info tab context bar"},
            worktree: {name: "info-tab-bar", path: ($cwd + "-info-tab-bar")}
        }}'
}

meta_codex() {
    jq -nc --argjson now "$now" '{
        kind: "codex", source: "demo", updated_at: $now, agent_name: "Codex", version: "0.61.0",
        cwd: "/home/demo/code/api", session_id: "019a7c2e-demo-7d2a-b0f1-000000000002",
        title: "sync retry", status: "idle", started_at: ($now - 5400), model: "gpt-6-astra",
        context_used: 61234, context_window: 258400, data: {originator: "codex_cli_rs"}}'
}

meta_pi() {
    jq -nc --argjson now "$now" '{
        kind: "pi", source: "demo", updated_at: $now, agent_name: "Pi", version: "0.52.1",
        cwd: "/home/demo/notes", session_id: "2026-09-22T17-04-11_demo0003",
        status: "busy", started_at: ($now - 640), model: "claude-sonnet-5",
        context_used: 14210, cost_usd: 0.07, data: {provider: "anthropic"}}'
}

# pane_file NAME: writes the pane program for NAME and prints its path.
pane_file() {
    pane_script "$1" > "$PANES_DIR/$1.sh"
    printf '%s\n' "$PANES_DIR/$1.sh"
}

start() {
    stop
    mkdir -p "$PANES_DIR"
    t new-session -d -s "$SESSION" -n salchang -x "$DEMO_WIDTH" -y "$DEMO_HEIGHT" "sh $(pane_file claude)"
    t set-option -g status-interval 1
    t set-option -g mouse on
    t new-window -t "$SESSION" -n api   "sh $(pane_file codex)"
    t new-window -t "$SESSION" -n notes "sh $(pane_file pi)"
    t new-window -t "$SESSION" -n shell "sh $(pane_file shell)"
    t set-option -p -t "$SESSION:salchang" "$META_OPTION" "$(meta_claude)"
    t set-option -p -t "$SESSION:api"      "$META_OPTION" "$(meta_codex)"
    t set-option -p -t "$SESSION:notes"    "$META_OPTION" "$(meta_pi)"
    t select-window -t "$SESSION:salchang"
    echo "tmux -L $SOCKET_NAME: session $SESSION ready"
}

# Waits for the old server to unlink its socket; starting a new server before
# that races the unlink and fails with "server exited unexpectedly".
stop() {
    t kill-server 2>/dev/null || true
    sock="${TMUX_TMPDIR:-/tmp}/tmux-$(id -u)/$SOCKET_NAME"
    i=0
    while [ -S "$sock" ] && [ "$i" -lt "$STOP_WAIT_TICKS" ]; do
        sleep 0.1
        i=$((i + 1))
    done
}

case "${1:-}" in
    start) start ;;
    stop) stop ;;
    status) t list-windows -a -F '#{session_name}:#{window_index} #{window_name} #{pane_id} meta=#{?#{@salchang_meta},yes,no}' ;;
    *) echo "usage: $0 start|stop|status" >&2; exit 1 ;;
esac
