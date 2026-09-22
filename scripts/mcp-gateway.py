#!/usr/bin/env python3
"""Keep the MCP connection alive across Eclipse restarts.

Does not launch Eclipse itself - start eclipse-mieux manually first.
"""

import fcntl
import json
import os
from pathlib import Path
import sys
import time
from urllib.error import URLError
from urllib.request import Request, urlopen


ENDPOINT = os.environ.get("ECLIPSE_MIEUX_MCP_ENDPOINT", "http://127.0.0.1:38573/mcp")
LAUNCHER = os.environ.get("ECLIPSE_MIEUX_LAUNCHER", str(Path.home() / ".local" / "bin" / "eclipse-mieux"))
STATE_DIR = Path(os.environ.get("XDG_STATE_HOME", str(Path.home() / ".local" / "state"))) / "eclipse-mieux"
RUNTIME_DIR = Path(os.environ["XDG_RUNTIME_DIR"]) if os.environ.get("XDG_RUNTIME_DIR") else None
LOCK_CANDIDATES = [
    (RUNTIME_DIR / "eclipse-mieux" / "mcp-gateway.lock") if RUNTIME_DIR else None,
    STATE_DIR / "mcp-gateway.lock",
    Path("/tmp") / f"eclipse-mieux-mcp-gateway-{os.getuid()}.lock",
]
START_TIMEOUT = float(os.environ.get("ECLIPSE_MIEUX_MCP_START_TIMEOUT", "120"))
REQUEST_TIMEOUT = float(os.environ.get("ECLIPSE_MIEUX_MCP_REQUEST_TIMEOUT", "30"))


class Gateway:
    def __init__(self):
        self.lock_file = None
        for lock_path in LOCK_CANDIDATES:
            if lock_path is None:
                continue
            try:
                lock_path.parent.mkdir(parents=True, exist_ok=True)
                self.lock_file = lock_path.open("a+")
                break
            except OSError:
                continue
        if self.lock_file is None:
            raise OSError("Could not create an MCP gateway lock file")
        fcntl.flock(self.lock_file.fileno(), fcntl.LOCK_EX)

    def close(self):
        fcntl.flock(self.lock_file.fileno(), fcntl.LOCK_UN)
        self.lock_file.close()

    def request(self, message):
        payload = json.dumps(message, separators=(",", ":")).encode("utf-8")
        request = Request(ENDPOINT, data=payload, method="POST", headers={"Content-Type": "application/json"})
        with urlopen(request, timeout=REQUEST_TIMEOUT) as response:
            body = response.read()
        return json.loads(body) if body else None

    def backend_is_ready(self):
        try:
            response = self.request({"jsonrpc": "2.0", "id": "gateway-probe", "method": "initialize", "params": {}})
            return isinstance(response, dict) and response.get("result") is not None
        except (OSError, URLError, ValueError):
            return False

    def eclipse_is_running(self):
        for process_dir in Path("/proc").glob("[0-9]*"):
            try:
                pid = int(process_dir.name)
                if pid == os.getpid():
                    continue
                command = (process_dir / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="ignore")
            except (OSError, ValueError):
                continue
            if "eclipse-mieux" in command or str(Path(LAUNCHER).resolve()) in command:
                return True
        return False

    def ensure_backend(self):
        if self.backend_is_ready():
            return
        if self.eclipse_is_running():
            # Already starting up (e.g. launched moments ago) - wait for it
            # rather than launching a second instance.
            deadline = time.monotonic() + START_TIMEOUT
            while time.monotonic() < deadline:
                if self.backend_is_ready():
                    return
                time.sleep(0.25)
            raise RuntimeError(f"Eclipse MCP backend did not become ready at {ENDPOINT}")
        raise RuntimeError(
            f"Eclipse Mieux is not running. Start it with '{LAUNCHER}' (or run "
            "eclipse-mieux from your launcher) and try again - the gateway no "
            "longer starts it automatically."
        )

    def run(self):
        for line in sys.stdin:
            if not line.strip():
                continue
            message = None
            try:
                message = json.loads(line)
                self.ensure_backend()
                response = self.request(message)
                if response is not None:
                    sys.stdout.write(json.dumps(response, separators=(",", ":")) + "\n")
                    sys.stdout.flush()
            except Exception as error:  # Keep the stdio transport alive after one failed call.
                request_id = message.get("id") if isinstance(message, dict) else None
                response = {
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "error": {"code": -32603, "message": str(error)},
                }
                sys.stdout.write(json.dumps(response, separators=(",", ":")) + "\n")
                sys.stdout.flush()


def main():
    gateway = Gateway()
    try:
        gateway.run()
    finally:
        gateway.close()


if __name__ == "__main__":
    main()
