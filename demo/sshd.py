# /// script
# requires-python = ">=3.12"
# dependencies = ["asyncssh==2.24.0"]
# ///
"""A loopback-only SSH server for the salchang demo (run with `uv run demo/sshd.py`).

The emulator reaches the host's loopback as 10.0.2.2, so the app connects to
10.0.2.2:PORT with auth method NONE and gets a shell on this machine with no
sshd installed system-wide and nothing exposed beyond 127.0.0.1. Only `exec`
requests are served (what the app uses: `tmux -C ...`, `sh -s`, one-off tmux
commands); no shells, no port forwarding, no SFTP.

The host key is generated on first run into the state directory and printed as
an OpenSSH known_hosts line so the emulator's app can be pre-seeded and never
sees the trust-on-first-use prompt.
"""

from __future__ import annotations

import argparse
import asyncio
import os
import signal
import sys
from pathlib import Path

import asyncssh

BIND_ADDRESS = "127.0.0.1"
DEFAULT_PORT = 2222
HOST_KEY_FILE = "ssh_host_ed25519_key"
# What the emulator's app puts in known_hosts: hostname as seen from the guest.
GUEST_HOST_ADDRESS = "10.0.2.2"
SHELL = "/bin/sh"


class DemoServer(asyncssh.SSHServer):
    """Accepts every client without authentication (the `none` method)."""

    def begin_auth(self, username: str) -> bool:
        return False


async def run_exec(process: asyncssh.SSHServerProcess) -> None:
    command: str | None = process.command
    if command is None:
        process.stderr.write("salchang demo sshd: only exec requests are served\n")
        process.exit(1)
        return
    child = await asyncio.create_subprocess_exec(
        SHELL,
        "-c",
        command,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        env=os.environ,
    )
    await process.redirect(stdin=child.stdin, stdout=child.stdout, stderr=child.stderr)
    status: int = await child.wait()
    process.exit(status)


def known_hosts_line(key_path: Path, port: int) -> str:
    key: asyncssh.SSHKey = asyncssh.read_private_key(str(key_path))
    public: str = key.export_public_key("openssh").decode().strip()
    host: str = GUEST_HOST_ADDRESS if port == 22 else f"[{GUEST_HOST_ADDRESS}]:{port}"
    return f"{host} {public}"


def ensure_host_key(state_dir: Path) -> Path:
    state_dir.mkdir(parents=True, exist_ok=True)
    key_path: Path = state_dir / HOST_KEY_FILE
    if not key_path.exists():
        asyncssh.generate_private_key("ssh-ed25519").write_private_key(str(key_path))
        key_path.chmod(0o600)
    return key_path


async def serve(port: int, key_path: Path) -> None:
    server = await asyncssh.create_server(
        DemoServer,
        BIND_ADDRESS,
        port,
        server_host_keys=[str(key_path)],
        process_factory=run_exec,
        allow_scp=False,
        sftp_factory=None,
    )
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        loop.add_signal_handler(sig, stop.set)
    print(f"salchang demo sshd listening on {BIND_ADDRESS}:{port}", flush=True)
    await stop.wait()
    server.close()
    await server.wait_closed()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--state-dir", type=Path, required=True, help="where the host key lives")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument(
        "--print-known-hosts",
        action="store_true",
        help="print the known_hosts line for the guest and exit (generates the key if needed)",
    )
    args = parser.parse_args()
    key_path: Path = ensure_host_key(args.state_dir)
    if args.print_known_hosts:
        print(known_hosts_line(key_path, args.port))
        return 0
    asyncio.run(serve(args.port, key_path))
    return 0


if __name__ == "__main__":
    sys.exit(main())
