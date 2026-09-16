#!/usr/bin/env python3
"""A stand-in for the Hermes Runs API, for local development without the home server.

It implements only what the Control Plane calls: submit a run on a profile route, then report
that run's status and usage. It also enforces the per-profile bearer key, so a routing mistake
shows up here instead of silently succeeding.

    HERMES_FAKE_KEYS='dad=dad-key,mom=mom-key' python3 fake_hermes.py 8899
"""
from __future__ import annotations

import json
import os
import re
import sys
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RUN_PATH = re.compile(r"^/p/([a-z0-9-]+)/v1/runs$")
RUN_STATUS_PATH = re.compile(r"^/p/([a-z0-9-]+)/v1/runs/([A-Za-z0-9_-]+)$")

RUNS: dict[str, dict] = {}


def profile_keys() -> dict[str, str]:
    raw = os.environ.get("HERMES_FAKE_KEYS", "dad=dad-key")
    pairs = [entry.split("=", 1) for entry in raw.split(",") if "=" in entry]
    return {name.strip(): key.strip() for name, key in pairs}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # keep the smoke test output readable
        pass

    def _send(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self) -> bytes:
        """Reads the body whether the client sent Content-Length or chunked encoding.

        Spring's RestClient streams the request body, so a fake that only honours Content-Length
        silently receives nothing and every echoed answer comes back empty.
        """
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = bytearray()
            while True:
                size_line = self.rfile.readline().strip()
                size = int(size_line.split(b";")[0] or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks += self.rfile.read(size)
                self.rfile.readline()
            return bytes(chunks)
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length) if length > 0 else b""

    def _authorized(self, profile: str) -> bool:
        expected = profile_keys().get(profile)
        header = self.headers.get("Authorization", "")
        return expected is not None and header == f"Bearer {expected}"

    def do_POST(self) -> None:
        match = RUN_PATH.match(self.path)
        if not match:
            self._send(404, {"error": "not found"})
            return
        profile = match.group(1)
        if not self._authorized(profile):
            self._send(401, {"error": "bad key for this profile"})
            return

        request = json.loads(self._read_body() or b"{}")
        run_id = f"run_{uuid.uuid4().hex[:12]}"
        session_id = request.get("session_id") or f"sess_{uuid.uuid4().hex[:8]}"
        RUNS[run_id] = {
            "run_id": run_id,
            "status": "completed",
            "session_id": session_id,
            "model": "fake-model",
            "provider": "fake-provider",
            "output": f"[fake hermes on profile {profile}] {request.get('input', '')}",
            "usage": {
                "prompt_tokens": 120,
                "completion_tokens": 40,
                "total_tokens": 160,
                "prompt_tokens_details": {"cached_tokens": 80},
            },
        }
        self._send(200, {"run_id": run_id, "status": "queued"})

    def do_GET(self) -> None:
        match = RUN_STATUS_PATH.match(self.path)
        if not match:
            self._send(404, {"error": "not found"})
            return
        profile, run_id = match.group(1), match.group(2)
        if not self._authorized(profile):
            self._send(401, {"error": "bad key for this profile"})
            return
        run = RUNS.get(run_id)
        if run is None:
            self._send(404, {"error": "no such run"})
            return
        self._send(200, run)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8899
    print(f"fake hermes listening on {port} for profiles {sorted(profile_keys())}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
