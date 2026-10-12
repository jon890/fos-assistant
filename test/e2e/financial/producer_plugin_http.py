"""Spring의 실제 support·claim과 SDK 자식을 잇는 loopback 시험 fixture다."""

import asyncio
import http.server
import importlib
import json
import logging
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import signal
import threading
import types
from unittest import mock

REPO = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "hermes/tests"))
from test_connector_execute import ConnectorExecuteTest


class Fixture(ConnectorExecuteTest):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="fos-producer-")
        self.base = pathlib.Path(self.temporary.name).resolve()
        self.addCleanup(self.temporary.cleanup)
        self.trade = subprocess.Popen(["bun", str(REPO / "test/e2e/financial/trading_http.ts")],
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        self.addCleanup(self.stop_trade)
        self.destination = self.counter()["url"]
        generated = subprocess.run(["bun", str(REPO / "hermes/connectors/tossinvest/tests/fixtures/trading-entry.ts"),
                                    "--generate", self.destination], capture_output=True, text=True, check=True)
        self.connector_root = pathlib.Path(generated.stdout.strip()).resolve()
        self.addCleanup(shutil.rmtree, self.connector_root)
        self.operator_list({"tossinvest": {"root": str(self.connector_root), "command": shutil.which("bun")}})
        type(self).profile_root = self.base / "profiles"
        self.profile_root.mkdir()
        self.guard_module = importlib.import_module(self.plugin.__name__ + ".connector_guard")
        self.guard = None
        self.profile_name = None
        self.prepares = self.executes = 0
        self.addCleanup(self.assert_no_child_left)
        os.environ["HERMES_DASHBOARD_PROFILE_API_SECRET"] = sys.argv[2]
        os.environ["FOS_CONNECTOR_EXECUTIONS_BASE_URL"] = sys.argv[1]

    def counter(self, **command):
        self.trade.stdin.write(json.dumps(command) + "\n")
        self.trade.stdin.flush()
        return json.loads(self.trade.stdout.readline())

    def stop_trade(self):
        self.trade.stdin.close()
        self.trade.wait(timeout=10)

    def configure(self, body):
        self.guard = body["guard"]
        self.profile_name = body["profile"]
        profile = self.profile_root / self.profile_name
        profile.mkdir(exist_ok=True)
        self.profile = profile
        (profile / self.plugin.MANAGED_MARKER).touch()
        (profile / ".env").write_text("TOSSINVEST_CLIENT_ID=fake-client-id-for-tests\n"
                                      "TOSSINVEST_CLIENT_SECRET=fake-client-secret-for-tests\n"
                                      "TOSSINVEST_ACCOUNT_SEQ=7\n")
        manifest = self.plugin._connector_manifest("tossinvest")
        self.entry = {"server": manifest["server"], "mcp_server": "tossinvest", "allowlist_added": True,
                      "mode": "bind", "vault": "c1", "skills": list(manifest["skills"]), "guard": self.guard}
        (profile / self.plugin.CONNECTOR_STATE).write_text(json.dumps({"tossinvest": self.entry}))
        self.prepares = self.executes = 0
        self.counter(reset=True, mode=body.get("mode", "ok"))
        return {"ok": True}

    async def send(self, path, method, body=None, token="valid"):
        request = types.SimpleNamespace(url=types.SimpleNamespace(path=path), method=method, token=token,
            state=types.SimpleNamespace(), query_params=types.SimpleNamespace(getlist=lambda key: [], keys=lambda: []))
        async def tree():
            return body
        async def raw():
            return json.dumps(body).encode()
        async def next_handler(current):
            return types.SimpleNamespace(status_code=401, body=b"null")
        request.json, request.body = tree, raw
        response = await self.gate(request, next_handler)
        return response.status_code, json.loads(response.body)

    def state(self):
        manifest = self.plugin._connector_manifest("tossinvest")
        state = self.guard_module._guard_support(self.profile_name, manifest, self.entry)
        return {"profile": self.profile_name, "policy_hook": True, "connectors": [{"plugin": "tossinvest",
            "enabled": True, "configured": True, "restart_required": False, "mode": "bind", "execution_guard": state}]}


Fixture.setUpClass()
logging.disable(logging.CRITICAL)
fixture = Fixture()
fixture.setUp()


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def handle_request(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or "null")
        status = 200
        if self.path == "/fixture/configure":
            answer = fixture.configure(body)
        elif self.path == "/fixture/counters":
            answer = {**fixture.counter(), "prepare": fixture.prepares, "execute": fixture.executes}
        elif self.path == "/fixture/manifest-change":
            fixture.rewrite("connector.json", lambda value: value.update(title="변경한 선언"))
            answer = {"ok": True}
        elif self.path.startswith("/api/connectors?"):
            answer = fixture.state()
        else:
            if self.path.endswith("/prepare"):
                fixture.prepares += 1
            if self.path.endswith("/execute"):
                fixture.executes += 1
            token = "valid" if self.headers.get("Authorization") == "Bearer " + sys.argv[2] else "wrong"
            status, answer = fixture.request(self.path, self.command, body, token=token)
        encoded = json.dumps(answer, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
signal.signal(signal.SIGTERM, lambda signum, frame: threading.Thread(target=server.shutdown).start())
print(json.dumps({"url": "http://127.0.0.1:%d" % server.server_port}), flush=True)
try:
    server.serve_forever()
finally:
    server.server_close()
    fixture.doCleanups()
    Fixture.doClassCleanups()
