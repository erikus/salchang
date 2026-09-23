#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Hermetic salchang demo: a headless Android emulator talking to a private tmux server.

    uv run demo/demo.py up [--build]   boot the emulator, start the SSH + tmux demo,
                                       install the debug APK, seed the host profile,
                                       launch the app
    uv run demo/demo.py connect        tap the demo host so the session screen opens
    uv run demo/demo.py shot NAME      screenshot -> demo/captures/NAME.png
    uv run demo/demo.py record NAME --seconds N   screen recording -> demo/captures/NAME.mp4
    uv run demo/demo.py tap X Y | tap-text TEXT | key KEYCODE | swipe X1 Y1 X2 Y2 [MS]
    uv run demo/demo.py adb ARGS...    run adb against the demo emulator
    uv run demo/demo.py status
    uv run demo/demo.py down           stop the app, emulator, SSH server and tmux server

Everything lives under demo/.state (AVD, host key, pidfiles, pane scripts) and
demo/captures; nothing is installed system-wide. The Android SDK is found via
$ANDROID_HOME, $ANDROID_SDK_ROOT, local.properties or ~/Android/Sdk; creating the
AVD needs a JDK >= 17 ($JAVA_HOME or the newest one Gradle downloaded).
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

REPO_DIR = Path(__file__).resolve().parent.parent
DEMO_DIR = REPO_DIR / "demo"
STATE_DIR = DEMO_DIR / ".state"
CAPTURES_DIR = DEMO_DIR / "captures"
AVD_HOME = STATE_DIR / "avd"
DEMO_HOME = STATE_DIR / "home"  # $HOME for commands the app runs, so no real agent state leaks in
SSHD_SCRIPT = DEMO_DIR / "sshd.py"
TMUX_SCRIPT = DEMO_DIR / "tmux-demo.sh"
APK_PATH = REPO_DIR / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"

AVD_NAME = "salchang-demo"
SYSTEM_IMAGE = "system-images;android-36;default;x86_64"
DEVICE_PROFILE = "pixel_9"
EMULATOR_PORT = 5580  # fixed so the serial is stable and never collides with a default emulator
EMULATOR_SERIAL = f"emulator-{EMULATOR_PORT}"
EMULATOR_FLAGS = ["-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-gpu", "swiftshader_indirect"]
BOOT_TIMEOUT_SECONDS = 240
BOOT_POLL_SECONDS = 2

SSH_PORT = 2222
GUEST_HOST_ADDRESS = "10.0.2.2"  # the host's loopback as seen from the emulator
SSH_USERNAME = "demo"
TMUX_SOCKET_NAME = "salchang-demo"
TMUX_SESSION = "demo"
HOST_PROFILE_ID = "salchang-demo"
HOST_PROFILE_NAME = "Demo host"

APP_ID = "dev.estaab.salchang"
APP_ACTIVITY = f"{APP_ID}/.MainActivity"
APP_DATASTORE_FILE = "files/datastore/hosts.preferences_pb"
APP_KNOWN_HOSTS_FILE = "files/known_hosts"
DEVICE_TMP_DIR = "/data/local/tmp"
HOSTS_JSON_KEY = "hosts_json"
APP_SETTLE_SECONDS = 2.0
UI_DUMP_PATH = "/sdcard/salchang-ui.xml"

MIN_JDK_MAJOR = 17
DEFAULT_RECORD_SECONDS = 15
DEFAULT_SWIPE_MS = 300

# Preferences DataStore wire format (androidx.datastore.preferences.PreferencesProto):
#   PreferenceMap { map<string, Value> preferences = 1; }   Value { string string = 5; }
PROTO_FIELD_MAP_ENTRY = 1
PROTO_FIELD_MAP_KEY = 1
PROTO_FIELD_MAP_VALUE = 2
PROTO_FIELD_VALUE_STRING = 5
PROTO_WIRE_LENGTH_DELIMITED = 2


# --- small helpers ---------------------------------------------------------------------------

def die(message: str) -> None:
    print(f"demo: {message}", file=sys.stderr)
    sys.exit(1)


def sdk_dir() -> Path:
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value: str | None = os.environ.get(var)
        if value:
            return Path(value)
    local_properties: Path = REPO_DIR / "local.properties"
    if local_properties.exists():
        for line in local_properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].strip().replace("\\:", ":"))
    return Path.home() / "Android" / "Sdk"


SDK = sdk_dir()
ADB = SDK / "platform-tools" / "adb"
EMULATOR = SDK / "emulator" / "emulator"
AVDMANAGER = SDK / "cmdline-tools" / "latest" / "bin" / "avdmanager"
SDKMANAGER = SDK / "cmdline-tools" / "latest" / "bin" / "sdkmanager"


def java_home() -> Path:
    explicit: str | None = os.environ.get("JAVA_HOME")
    candidates: list[Path] = [Path(explicit)] if explicit else []
    candidates += sorted((Path.home() / ".gradle" / "jdks").glob("jdk-*"), reverse=True)
    for candidate in candidates:
        release: Path = candidate / "release"
        if not release.exists():
            continue
        match = re.search(r'JAVA_VERSION="(\d+)', release.read_text())
        if match and int(match.group(1)) >= MIN_JDK_MAJOR:
            return candidate
    die(f"need a JDK >= {MIN_JDK_MAJOR} for avdmanager; set JAVA_HOME")
    raise AssertionError


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, text=True, **kwargs)


def adb(*args: str, **kwargs) -> subprocess.CompletedProcess[str]:
    return run([str(ADB), "-s", EMULATOR_SERIAL, *args], **kwargs)


def adb_out(*args: str) -> str:
    return adb(*args, capture_output=True, check=True).stdout.strip()


def pidfile(name: str) -> Path:
    return STATE_DIR / f"{name}.pid"


def pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    return True


def read_pid(name: str) -> int | None:
    path: Path = pidfile(name)
    if not path.exists():
        return None
    try:
        pid = int(path.read_text().strip())
    except ValueError:
        return None
    if pid_alive(pid):
        return pid
    path.unlink()  # stale: the process is gone (e.g. the emulator was killed through adb)
    return None


def spawn_daemon(name: str, cmd: list[str], env: dict[str, str] | None = None) -> None:
    if read_pid(name) is not None:
        return
    log: Path = STATE_DIR / f"{name}.log"
    with log.open("ab") as out:
        proc = subprocess.Popen(cmd, stdout=out, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                env=env, start_new_session=True)
    pidfile(name).write_text(f"{proc.pid}\n")
    print(f"started {name} (pid {proc.pid}, log {log.relative_to(REPO_DIR)})")


def stop_daemon(name: str) -> None:
    pid: int | None = read_pid(name)
    if pid is None:
        return
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(pid, sig)  # spawned with its own session, so the pgid is the pid
        except ProcessLookupError:
            os.kill(pid, sig)
        for _ in range(50):
            if not pid_alive(pid):
                break
            time.sleep(0.1)
        else:
            continue
        break
    pidfile(name).unlink(missing_ok=True)
    print(f"stopped {name}")


# --- pieces ----------------------------------------------------------------------------------

def ensure_sdk() -> None:
    for tool in (ADB, EMULATOR, AVDMANAGER):
        if not tool.exists():
            die(f"missing {tool}; install with {SDKMANAGER} --install emulator platform-tools cmdline-tools;latest")
    image_dir: Path = SDK / "system-images" / Path(*SYSTEM_IMAGE.split(";")[1:])
    if not (image_dir / "system.img").exists():
        die(f"missing system image; run: JAVA_HOME=... {SDKMANAGER} --install '{SYSTEM_IMAGE}'")


def emulator_env() -> dict[str, str]:
    return {**os.environ, "ANDROID_AVD_HOME": str(AVD_HOME)}


def ensure_avd() -> None:
    if (AVD_HOME / f"{AVD_NAME}.avd").is_dir():
        return
    AVD_HOME.mkdir(parents=True, exist_ok=True)
    env: dict[str, str] = {**emulator_env(), "JAVA_HOME": str(java_home())}
    run([str(AVDMANAGER), "create", "avd", "-n", AVD_NAME, "-k", SYSTEM_IMAGE, "-d", DEVICE_PROFILE, "--force"],
        input="no\n", env=env, check=True, stdout=subprocess.DEVNULL)
    print(f"created AVD {AVD_NAME} in {AVD_HOME.relative_to(REPO_DIR)}")


def emulator_running() -> bool:
    result = run([str(ADB), "devices"], capture_output=True)
    return any(line.startswith(EMULATOR_SERIAL) for line in result.stdout.splitlines())


def ensure_emulator() -> None:
    if not emulator_running():
        spawn_daemon("emulator", [str(EMULATOR), "-avd", AVD_NAME, "-port", str(EMULATOR_PORT), *EMULATOR_FLAGS],
                     env=emulator_env())
    deadline: float = time.monotonic() + BOOT_TIMEOUT_SECONDS
    adb("wait-for-device", check=True, timeout=BOOT_TIMEOUT_SECONDS)
    while time.monotonic() < deadline:
        booted = adb("shell", "getprop", "sys.boot_completed", capture_output=True)
        if booted.stdout.strip() == "1":
            print("emulator booted")
            return
        time.sleep(BOOT_POLL_SECONDS)
    die("emulator did not finish booting; see demo/.state/emulator.log")


def ensure_services() -> None:
    DEMO_HOME.mkdir(parents=True, exist_ok=True)
    env: dict[str, str] = {**os.environ, "HOME": str(DEMO_HOME), "SALCHANG_DEMO_STATE": str(STATE_DIR)}
    run(["sh", str(TMUX_SCRIPT), "start"], check=True, env=env)
    spawn_daemon("sshd", ["uv", "run", str(SSHD_SCRIPT), "--state-dir", str(STATE_DIR), "--port", str(SSH_PORT)],
                 env=env)


def known_hosts_line() -> str:
    result = run(["uv", "run", str(SSHD_SCRIPT), "--state-dir", str(STATE_DIR), "--port", str(SSH_PORT),
                  "--print-known-hosts"], capture_output=True, check=True)
    return result.stdout.strip() + "\n"


def proto_varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte: int = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def proto_bytes_field(field: int, payload: bytes) -> bytes:
    return proto_varint((field << 3) | PROTO_WIRE_LENGTH_DELIMITED) + proto_varint(len(payload)) + payload


def hosts_preferences_pb(hosts_json: str) -> bytes:
    value: bytes = proto_bytes_field(PROTO_FIELD_VALUE_STRING, hosts_json.encode())
    entry: bytes = proto_bytes_field(PROTO_FIELD_MAP_KEY, HOSTS_JSON_KEY.encode()) + proto_bytes_field(PROTO_FIELD_MAP_VALUE, value)
    return proto_bytes_field(PROTO_FIELD_MAP_ENTRY, entry)


def host_profile_json() -> str:
    import json
    profile: dict[str, object] = {
        "id": HOST_PROFILE_ID, "name": HOST_PROFILE_NAME, "hostname": GUEST_HOST_ADDRESS, "port": SSH_PORT,
        "username": SSH_USERNAME, "keyId": None, "authMethod": "NONE", "tmuxSession": TMUX_SESSION,
        "tmuxSocketName": TMUX_SOCKET_NAME, "tmuxSocketPath": None, "tmuxBinary": "tmux",
    }
    return json.dumps([profile])


def push_app_file(local: Path, app_relative: str) -> None:
    remote_tmp: str = f"{DEVICE_TMP_DIR}/{local.name}"
    adb("push", str(local), remote_tmp, check=True, stdout=subprocess.DEVNULL)
    parent: str = str(Path(app_relative).parent)
    adb("shell", "run-as", APP_ID, "sh", "-c",
        f"'mkdir -p {parent} && cp {remote_tmp} {app_relative} && chmod 600 {app_relative}'", check=True)
    adb("shell", "rm", remote_tmp, check=True)


def install_and_seed(build: bool) -> None:
    if build or not APK_PATH.exists():
        run([str(REPO_DIR / "gradlew"), ":app:assembleDebug", "-q"], cwd=REPO_DIR, check=True)
    adb("install", "-r", "-t", str(APK_PATH), check=True, stdout=subprocess.DEVNULL)
    adb("shell", "am", "force-stop", APP_ID, check=True)
    prefs: Path = STATE_DIR / "hosts.preferences_pb"
    prefs.write_bytes(hosts_preferences_pb(host_profile_json()))
    known_hosts: Path = STATE_DIR / "known_hosts"
    known_hosts.write_text(known_hosts_line())
    push_app_file(prefs, APP_DATASTORE_FILE)
    push_app_file(known_hosts, APP_KNOWN_HOSTS_FILE)
    print(f"installed {APK_PATH.relative_to(REPO_DIR)} and seeded '{HOST_PROFILE_NAME}' ({GUEST_HOST_ADDRESS}:{SSH_PORT})")


def launch_app() -> None:
    adb("shell", "am", "start", "-W", "-n", APP_ACTIVITY, check=True, stdout=subprocess.DEVNULL)
    time.sleep(APP_SETTLE_SECONDS)


def ui_dump() -> str:
    adb("shell", "uiautomator", "dump", UI_DUMP_PATH, check=True, stdout=subprocess.DEVNULL)
    return adb_out("shell", "cat", UI_DUMP_PATH)


def tap_text(text: str) -> None:
    dump: str = ui_dump()
    pattern = re.compile(r'<node[^>]*?(?:text|content-desc)="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    for match in pattern.finditer(dump):
        if text.lower() in match.group(1).lower():
            x1, y1, x2, y2 = (int(v) for v in match.group(2, 3, 4, 5))
            adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2), check=True)
            return
    die(f"no UI node containing {text!r}")


# --- commands --------------------------------------------------------------------------------

def cmd_up(args: argparse.Namespace) -> None:
    ensure_sdk()
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    ensure_avd()
    ensure_emulator()
    ensure_services()
    install_and_seed(build=args.build)
    launch_app()
    print("app launched; next: uv run demo/demo.py connect && uv run demo/demo.py shot session")


def cmd_connect(_: argparse.Namespace) -> None:
    launch_app()
    tap_text(HOST_PROFILE_NAME)
    time.sleep(APP_SETTLE_SECONDS)


def cmd_shot(args: argparse.Namespace) -> None:
    CAPTURES_DIR.mkdir(parents=True, exist_ok=True)
    target: Path = CAPTURES_DIR / f"{args.name}.png"
    with target.open("wb") as out:
        subprocess.run([str(ADB), "-s", EMULATOR_SERIAL, "exec-out", "screencap", "-p"], stdout=out, check=True)
    print(target.relative_to(REPO_DIR))


def cmd_record(args: argparse.Namespace) -> None:
    CAPTURES_DIR.mkdir(parents=True, exist_ok=True)
    remote: str = f"/sdcard/{args.name}.mp4"
    target: Path = CAPTURES_DIR / f"{args.name}.mp4"
    adb("shell", "screenrecord", "--time-limit", str(args.seconds), remote, check=True)
    adb("pull", remote, str(target), check=True, stdout=subprocess.DEVNULL)
    adb("shell", "rm", remote, check=True)
    print(target.relative_to(REPO_DIR))


def cmd_tap(args: argparse.Namespace) -> None:
    adb("shell", "input", "tap", str(args.x), str(args.y), check=True)


def cmd_tap_text(args: argparse.Namespace) -> None:
    tap_text(args.text)


def cmd_key(args: argparse.Namespace) -> None:
    adb("shell", "input", "keyevent", args.keycode, check=True)


def cmd_swipe(args: argparse.Namespace) -> None:
    adb("shell", "input", "swipe", str(args.x1), str(args.y1), str(args.x2), str(args.y2), str(args.ms), check=True)


def cmd_adb(args: argparse.Namespace) -> None:
    sys.exit(adb(*args.args).returncode)


def cmd_status(_: argparse.Namespace) -> None:
    print(f"emulator: {'running' if emulator_running() else 'stopped'} ({EMULATOR_SERIAL})")
    print(f"sshd:     {'running' if read_pid('sshd') else 'stopped'} ({GUEST_HOST_ADDRESS}:{SSH_PORT} from the guest)")
    tmux = run(["sh", str(TMUX_SCRIPT), "status"], capture_output=True, env={**os.environ, "SALCHANG_DEMO_STATE": str(STATE_DIR)})
    print("tmux:     " + ("running\n" + tmux.stdout.rstrip() if tmux.returncode == 0 else "stopped"))


def cmd_down(_: argparse.Namespace) -> None:
    if emulator_running():
        adb("shell", "am", "force-stop", APP_ID)
        adb("emu", "kill", stdout=subprocess.DEVNULL)
    stop_daemon("emulator")
    stop_daemon("sshd")
    run(["sh", str(TMUX_SCRIPT), "stop"], env={**os.environ, "SALCHANG_DEMO_STATE": str(STATE_DIR)})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                     epilog=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    up = sub.add_parser("up"); up.add_argument("--build", action="store_true", help="run :app:assembleDebug first"); up.set_defaults(fn=cmd_up)
    sub.add_parser("connect").set_defaults(fn=cmd_connect)
    shot = sub.add_parser("shot"); shot.add_argument("name"); shot.set_defaults(fn=cmd_shot)
    rec = sub.add_parser("record"); rec.add_argument("name"); rec.add_argument("--seconds", type=int, default=DEFAULT_RECORD_SECONDS); rec.set_defaults(fn=cmd_record)
    tap = sub.add_parser("tap"); tap.add_argument("x", type=int); tap.add_argument("y", type=int); tap.set_defaults(fn=cmd_tap)
    tt = sub.add_parser("tap-text"); tt.add_argument("text"); tt.set_defaults(fn=cmd_tap_text)
    key = sub.add_parser("key"); key.add_argument("keycode"); key.set_defaults(fn=cmd_key)
    swipe = sub.add_parser("swipe")
    for name in ("x1", "y1", "x2", "y2"):
        swipe.add_argument(name, type=int)
    swipe.add_argument("ms", type=int, nargs="?", default=DEFAULT_SWIPE_MS); swipe.set_defaults(fn=cmd_swipe)
    adb_cmd = sub.add_parser("adb"); adb_cmd.add_argument("args", nargs=argparse.REMAINDER); adb_cmd.set_defaults(fn=cmd_adb)
    sub.add_parser("status").set_defaults(fn=cmd_status)
    sub.add_parser("down").set_defaults(fn=cmd_down)
    args = parser.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
