"""실제 stdio와 로컬 HTTP 대역으로 원문 전달과 금융 차단을 검증한다."""

import asyncio
import base64
import copy
import hashlib
import hmac
import http.server
import importlib
import json
import os
import pathlib
import shutil
import threading
import types
import unittest
from unittest import mock

import test_connector_execute as execute_base
import test_connector_manifest as base

REPO = pathlib.Path(__file__).resolve().parents[2]
PROFILE = execute_base.PROFILE
PREPARE = "/api/connectors/%s/prepare" % base.DEMO
EXECUTE = "/api/connectors/%s/execute" % base.DEMO


class ConnectorGuardCase(execute_base.ConnectorExecuteTest):
    # 기반의 기존 테스트를 새 fixture로 재실행하지 않는다.
    def setUp(self):
        super().setUp()
        self.guard_module = importlib.import_module(self.plugin.__name__ + ".connector_guard")
        self.validation = importlib.import_module(self.plugin.__name__ + ".connector_guard_validation")
        self.vector = json.loads((REPO / "test/resources/financial-transport-v1.json").read_text())
        self.ticket_vector = json.loads((REPO / "test/resources/financial-ticket-v1.json").read_text())
        self.cases = json.loads((REPO / "test/fixtures/financial-approval-v1.json").read_text())
        self.case = self.cases["cases"][0]
        self.rewrite("connector.json", lambda value: value.update(schema=2, tools={
            "list_scopes": {"risk": "READ"}, "prepare_order": {"risk": "READ"},
            "place_order": {"risk": "FINANCIAL", "identifiers": ["symbol"]}}, execution_guard={
                "protocol": "approval-claim-v1", "prepare_tool": "prepare_order",
                "scope_fields": [{"arg": "account_seq", "field": "scope"}], "operations": {"place_order": "CREATE"}}))
        shutil.copyfile(REPO / "hermes/tests/fixtures/financial-transport-server.py", self.connector_root / "server.py")
        (self.connector_root / "prepare-case.json").write_text(json.dumps(self.case))
        self.manifest = self.plugin._connector_manifest(base.DEMO)
        self.profile = self.profile_root / PROFILE
        (self.profile / ".env").write_text("DEMO_TOKEN=demo_ok_0123456789\nDEMO_SCOPE=000007\n")
        self.guard = {"v": 1, "protocol": "approval-claim-v1", "bindingId": "14", "connectionId": "13",
                      "connectionUpdatedAt": "2026-10-10T00:00:00.123456Z",
                      "manifestSha256": self.manifest["execution_guard_manifest_sha256"]}
        self.entry = {"server": self.manifest["server"], "allowlist_added": True, "mcp_server": "demo", "mode": "bind",
                      "vault": "c1", "skills": ["demo"], "guard": self.guard}
        self.write_state()
        self.supports, self.claims, self.trades = [], [], []
        self.mode = "ok"
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                status, answer = 200, {}
                if self.path.endswith("/support"):
                    owner.supports.append((body, self.headers.get("Authorization")))
                    answer = {key: value for key, value in body.items() if key not in {"profile", "connectorId"}}
                    if owner.mode == "missing":
                        status = 404
                    elif owner.mode == "replay":
                        answer = owner.replay
                    elif owner.mode == "nonce":
                        answer["nonce"] = "00000000-0000-4000-8000-000000000000"
                    elif owner.mode == "revision":
                        answer["connectionUpdatedAt"] = "2026-10-10T00:00:01Z"
                    elif owner.mode == "boolean":
                        answer["v"] = True
                    elif owner.mode == "revoke":
                        owner.write_state({})
                    elif owner.mode == "redirect":
                        status = 307
                    owner.replay = copy.deepcopy(answer)
                elif self.path.endswith("/claim"):
                    owner.claims.append(body)
                    answer = {"v": 1, "allowed": True}
                else:
                    owner.trades.append(body)
                    status = 404
                self.send_response(status)
                if status == 307:
                    self.send_header("Location", owner.url + "/trade")
                self.end_headers()
                self.wfile.write(json.dumps(answer).encode())

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.url = "http://127.0.0.1:%d" % server.server_port
        patch = mock.patch.dict(os.environ, {"FOS_CONNECTOR_EXECUTIONS_BASE_URL": self.url,
                                            "HERMES_DASHBOARD_PROFILE_API_SECRET": self.ticket_vector["token"]})
        patch.start()
        self.addCleanup(patch.stop)

    def write_state(self, state=None):
        (self.profile / self.plugin.CONNECTOR_STATE).write_text(json.dumps({base.DEMO: self.entry} if state is None else state))

    async def send(self, path, method, body=None, token="valid"):
        query = types.SimpleNamespace(getlist=lambda key: [PROFILE] if path == "/api/connectors" else [],
                                      keys=lambda: ["profile"] if path == "/api/connectors" else [])
        request = types.SimpleNamespace(url=types.SimpleNamespace(path=path), method=method, token=token,
                                        state=types.SimpleNamespace(), query_params=query)
        async def tree():
            return body
        async def raw():
            return getattr(self, "raw_body", json.dumps(body).encode())
        async def next_handler(current):
            return types.SimpleNamespace(status_code=401, body=b"null")
        request.json, request.body = tree, raw
        answer = await self.gate(request, next_handler)
        return answer.status_code, json.loads(answer.body)

    def body(self, execution=False):
        args = json.loads(self.vector["argsJson"]) if execution else json.loads(self.case["modelArgsJson"])
        body = {"profile": PROFILE, "hermes_tool": "mcp__demo__place_order", "args": args}
        if execution:
            claims = json.loads(self.ticket_vector["payload"])
            claims["argsSha256"] = self.vector["argsSha256"]
            claims["scopeSha256"] = self.vector["scopeSha256"]
            claims["profile"], claims["connectorId"] = PROFILE, base.DEMO
            raw = base64.urlsafe_b64encode(json.dumps(claims, separators=(",", ":")).encode()).decode().rstrip("=")
            key = hmac.digest(self.ticket_vector["token"].encode(), b"fos-approval-signing-key-v1", "sha256")
            signature = base64.urlsafe_b64encode(hmac.digest(key, b"fos-approval-claim-v1" + raw.encode(), "sha256")).decode().rstrip("=")
            body["execution"] = {"v": 1, "protocol": "approval-claim-v1", "ticket": raw + "." + signature,
                                 "argsJson": self.vector["argsJson"], "argsSha256": self.vector["argsSha256"]}
        return body


# unittest의 기반 테스트 상속을 피하면서 setup과 환경 복구만 재사용한다.
for _name in list(vars(execute_base.ConnectorExecuteTest)):
    if _name.startswith("test_"):
        setattr(ConnectorGuardCase, _name, None)


class ConnectorGuardTransportTest(ConnectorGuardCase):
    def test_actual_prepare_and_legacy_empty_call(self):
        status, answer = self.request(PREPARE, "POST", self.body())
        self.assertEqual(status, 200, answer)
        self.assertTrue(answer["ok"], answer)
        self.assertEqual(answer["result"]["executionArgs"], json.loads(self.vector["argsJson"]))
        self.assertEqual(self.request("/api/connectors/%s/call" % base.DEMO, "POST", {"tool": "list_scopes", "values": {}}),
                         (200, {"ok": True, "result": {"empty": True, "executionEnv": False}}))
        self.assertEqual(self.claims + self.trades, [])

    def test_actual_child_receives_original_hash_and_discards_execution_env(self):
        before = (self.profile / ".env").read_bytes(), (self.profile / self.plugin.CONNECTOR_STATE).read_bytes()
        body = self.body(execution=True)
        status, answer = self.request(EXECUTE, "POST", body)
        self.assertEqual(status, 200, answer)
        self.assertEqual(answer, {"ok": True, "result": {"argsMatched": True, "argsSha256": self.vector["argsSha256"],
                                                        "cleared": True, "claimed": True}})
        self.assertEqual(len(self.claims), 1)
        self.assertEqual(self.claims[0]["ticket"], body["execution"]["ticket"])
        self.assertEqual(self.trades, [])
        self.assertEqual(before, ((self.profile / ".env").read_bytes(), (self.profile / self.plugin.CONNECTOR_STATE).read_bytes()))
        self.assertNotIn(body["execution"]["ticket"], json.dumps(answer))

    def test_missing_support_and_legacy_financial_have_no_calls(self):
        self.mode = "missing"
        self.assertEqual(self.request(PREPARE, "POST", self.body())[0], 409)
        self.assertEqual(self.request(EXECUTE, "POST", self.body(execution=True))[0], 409)
        self.mode = "ok"
        self.assertEqual(self.request(EXECUTE, "POST", self.body())[0], 409)
        self.assertEqual(self.claims + self.trades, [])

    def test_prepare_requires_service_token_profile_and_target(self):
        for token in (None, "wrong"):
            self.assertEqual(self.request(PREPARE, "POST", self.body(), token=token)[0], 401)
        for name in ("list_scopes", "prepare_order", "write_note"):
            body = self.body()
            body["hermes_tool"] = "mcp__demo__" + name
            self.assertEqual(self.request(PREPARE, "POST", body)[0], 409)
        body = self.body()
        body["profile"] = "missing"
        self.assertEqual(self.request(PREPARE, "POST", body)[0], 404)
        self.assertEqual(self.request("/api/connectors/%s/call" % base.DEMO, "POST", {"tool": "prepare_order", "values": {}})[0], 400)

    def test_original_hash_strict_sdk_keys_and_types(self):
        body = self.body(execution=True)
        for label, change in (
                ("extra", lambda value: value["args"].update(extra=1)),
                ("number", lambda value: value["args"].update(quantity=2)),
                ("hash", lambda value: value["execution"].update(argsSha256="a" * 64)),
                ("boolean", lambda value: value["execution"].update(v=True)),
                ("raw", lambda value: value["execution"].update(argsJson="{} {}"))):
            with self.subTest(label=label):
                changed = copy.deepcopy(body)
                change(changed)
                self.assertEqual(self.request(EXECUTE, "POST", changed)[0], 409)
        self.assertEqual(self.claims + self.trades, [])

    def test_shared_ticket_vector_and_independent_transport_hash(self):
        vector = self.ticket_vector
        key = hmac.digest(vector["token"].encode(), b"fos-approval-signing-key-v1", "sha256")
        actual = base64.urlsafe_b64encode(hmac.digest(key, b"fos-approval-claim-v1" + vector["payloadBase64Url"].encode(), "sha256")).decode().rstrip("=")
        self.assertEqual(actual, vector["signatureBase64Url"])
        self.assertEqual(hashlib.sha256(self.vector["argsJson"].encode()).hexdigest(), self.vector["argsSha256"])

    def test_duplicate_and_oversized_http_body_never_reach_child(self):
        for raw in (b'{"profile":"alice","hermes_tool":"mcp__demo__place_order","args":{},"args":{}}',
                    json.dumps(self.body()).encode() + b" {}", b" " * 32769):
            self.raw_body = raw
            self.assertEqual(self.request(PREPARE, "POST", self.body())[0], 400)
        del self.raw_body
        self.assertEqual(self.supports + self.claims + self.trades, [])

    def test_prepare_shared_valid_and_invalid_snapshot_vectors(self):
        for case in self.cases["cases"]:
            # scopeJson과 원문의 공백 크기는 DB snapshot의 입력이며 prepare 결과의 칸이 아니다.
            if case["name"].startswith("scope-") or "scopeJson" in case["name"] or "-bytes-" in case["name"]:
                continue
            with self.subTest(case=case["name"]):
                manifest = copy.deepcopy(self.manifest)
                manifest["execution_guard"]["operations"] = {case["tool"]: case["operation"]}
                manifest["execution_guard"]["scope_fields"] = self.cases["scopeFields"]
                manifest["fields"] = [{"key": "account", "env": "DEMO_SCOPE"}]
                def validate():
                    payload = {"v": 1, "executionArgs": self.guard_module._strict_json(case["executionArgsJson"]),
                               "summary": self.guard_module._strict_json(case["summaryJson"])}
                    return self.validation._prepare_result(payload, self.guard_module._strict_json(case["modelArgsJson"]), manifest, case["tool"], {"DEMO_SCOPE": "000007"})
                if case["valid"]:
                    validate()
                else:
                    with self.assertRaises((ValueError, TypeError, KeyError)):
                        validate()


if __name__ == "__main__":
    unittest.main()
