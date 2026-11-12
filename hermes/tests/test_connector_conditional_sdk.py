"""조건주문 실제 SDK 를 실제 준비·실행 처리기로 stdio 왕복하고 loopback 목적지의 단일 전송을 센다.

support 응답과 claim 만 loopback 대역이다. 합성 ticket 은 가짜 계정과 키를 쓰며 실제 Spring 인증·DB 소비를 대신하지 않는다.
"""

import base64
import copy
import datetime
import hashlib
import http.server
import json
import os
import pathlib
import shutil
import subprocess
import threading
import unittest
import uuid
from unittest import mock

import test_connector_execute as execute_base

REPO = pathlib.Path(__file__).resolve().parents[2]
CONNECTOR = REPO / "hermes/connectors/tossinvest"
PROFILE = "toss"
ACCOUNT = "7"
PREPARE = "/api/connectors/tossinvest/prepare"
EXECUTE = "/api/connectors/tossinvest/execute"
GUARD = {"v": 1, "protocol": "approval-claim-v2", "bindingId": "14", "connectionId": "13",
         "connectionUpdatedAt": "2026-10-10T00:00:00.123456Z"}
STOCK = {"symbol": "ABC", "name": "합성 종목", "englishName": "Synthetic", "isinCode": "FAKE00000000",
         "market": "NASDAQ", "securityType": "STOCK", "isCommonShare": False, "status": "ACTIVE",
         "currency": "USD", "sharesOutstanding": "100", "listDate": None, "delistDate": None,
         "leverageFactor": None, "koreanMarketDetail": None}
LEG = {"type": "STOP", "status": "WATCHING", "orderSide": "SELL", "triggerPrice": "120",
       "targetProfitRate": None, "orderPrice": "120", "triggeredOrderId": None}
ORIGINAL = {"conditionalOrderId": "cond-1", "type": "OTO", "status": "ORDERED", "symbol": "ABC", "market": "US",
            "quantity": "0.5", "orderType": "LIMIT",
            "first": dict(LEG, status="ORDERED", orderSide="BUY", triggerPrice="100", orderPrice="100",
                          triggeredOrderId="ord-1"),
            "second": LEG, "createdAt": "2026-06-12T09:00:00+09:00"}


def kst_date(days=30):
    return (datetime.datetime.now(datetime.UTC) + datetime.timedelta(hours=9, days=days)).date().isoformat()


def models():
    expire = kst_date()
    return {
        "create_conditional_order": {"symbol": "abc", "type": "OCO", "quantity": "002", "orderType": "LIMIT",
                                     "expireDate": expire,
                                     "first": {"orderSide": "SELL", "triggerPrice": "0110.00", "orderPrice": "110"},
                                     "second": {"orderSide": "SELL", "triggerPrice": "90", "orderPrice": "90"},
                                     "confirmHighValueOrder": False},
        "modify_conditional_order": {"conditionalOrderId": "cond-1", "type": "OTO", "quantity": "1",
                                     "orderType": "LIMIT", "expireDate": expire,
                                     "first": {"orderSide": "BUY", "triggerPrice": "95", "orderPrice": "95"},
                                     "second": {"orderSide": "SELL", "triggerPrice": "130", "orderPrice": "130"}},
        "cancel_conditional_order": {"conditionalOrderId": "cond-1"},
    }


FINANCIAL = {"create_conditional_order": ("POST", "/api/v1/conditional-orders"),
             "modify_conditional_order": ("POST", "/api/v1/conditional-orders/cond-1/modify"),
             "cancel_conditional_order": ("DELETE", "/api/v1/conditional-orders/cond-1")}


class ConditionalSdkCase(execute_base.ConnectorExecuteTest):
    def setUp(self):
        super().setUp()
        if not (CONNECTOR / "node_modules").is_dir():
            self.fail("tossinvest 의존성이 없다. bun install --frozen-lockfile --cwd hermes/connectors/tossinvest 를 먼저 돌린다.")
        self.reset()
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                owner.answer(self)

            def do_POST(self):
                owner.answer(self)

            def do_DELETE(self):
                owner.answer(self)

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.url = "http://127.0.0.1:%d" % server.server_port
        # 실제 생성 명령 한 곳이 테스트 원본과 SDK bundle 을 만든다. Bun 테스트도 같은 명령을 쓴다.
        generated = subprocess.run([shutil.which("bun"), str(CONNECTOR / "tests/fixtures/conditional-entry.ts"),
                                    "--generate", self.url], capture_output=True, text=True, check=True)
        self.root = pathlib.Path(generated.stdout.strip()).resolve()
        self.addCleanup(shutil.rmtree, self.root)
        self.operator_list({"tossinvest": {"root": str(self.root), "command": shutil.which("bun")}})
        patch = mock.patch.dict(os.environ, {"FOS_CONNECTOR_EXECUTIONS_BASE_URL": self.url,
                                            "HERMES_DASHBOARD_PROFILE_API_SECRET": "synthetic-service-token"})
        patch.start()
        self.addCleanup(patch.stop)
        self.manifest = self.plugin._connector_manifest("tossinvest")
        self.profile = self.profile_root / PROFILE
        self.profile.mkdir()
        (self.profile / self.plugin.MANAGED_MARKER).touch()
        (self.profile / ".env").write_text("TOSSINVEST_CLIENT_ID=fake-client-id-for-tests\n"
                                           "TOSSINVEST_CLIENT_SECRET=fake-client-secret-for-tests\n"
                                           "TOSSINVEST_ACCOUNT_SEQ=%s\n" % ACCOUNT)
        self.entry = {"server": self.manifest["server"], "mcp_server": "tossinvest", "allowlist_added": True,
                      "mode": "bind", "vault": "c1", "skills": list(self.manifest["skills"]),
                      "guard": dict(GUARD, manifestSha256=self.manifest["execution_guard_manifest_sha256"])}
        (self.profile / self.plugin.CONNECTOR_STATE).write_text(json.dumps({"tossinvest": self.entry}))

    def reset(self):
        self.seen, self.bodies, self.consumed, self.mode = [], [], set(), "ok"

    def count(self, method, path=None):
        return sum(1 for item in self.seen if item[0] == method and (path is None or item[1] == path))

    def financial(self):
        return sum(1 for method, path in self.seen
                   if method == "DELETE" or (method == "POST" and path.startswith("/api/v1/conditional-orders")))

    def answer(self, handler):
        raw = handler.rfile.read(int(handler.headers.get("Content-Length") or 0))
        path = handler.path.split("?")[0]
        self.seen.append((handler.command, path))
        status, body = 200, None
        if path.endswith("/support"):
            body = {key: value for key, value in json.loads(raw).items() if key not in {"profile", "connectorId"}}
        elif path.endswith("/claim"):
            status, body = self.claim(json.loads(raw))
        elif path == "/oauth2/token":
            body = {"access_token": "fake-access-token-1", "token_type": "Bearer", "expires_in": 3600}
        elif path == "/api/v1/accounts":
            body = {"result": [{"accountSeq": int(ACCOUNT), "accountNo": "00001234", "accountType": "BROKERAGE"}]}
        elif path == "/api/v1/stocks":
            body = {"result": [STOCK]}
        elif path == "/api/v1/prices":
            body = {"result": [{"symbol": "ABC", "timestamp": None, "lastPrice": "100", "currency": "USD"}]}
        elif path == "/api/v1/conditional-orders/cond-1" and handler.command == "GET":
            body = {"result": ORIGINAL}
        else:
            self.bodies.append(raw)
            if self.mode == "unknown":
                status, body = 500, {"error": {"requestId": "r", "code": "internal", "message": "m"}}
            elif handler.command == "DELETE":
                status = 204
            else:
                sent = json.loads(raw)
                body = {"result": {"conditionalOrderId": "cond-new",
                                   **({"clientOrderId": sent["clientOrderId"]} if "clientOrderId" in sent else {})}}
        encoded = b"" if body is None else json.dumps(body, ensure_ascii=False).encode()
        handler.send_response(status)
        handler.send_header("Content-Type", "application/json")
        handler.send_header("Content-Length", str(len(encoded)))
        # 닫는다고 헤더에 적는다. http.server 의 HTTP/1.0 응답은 연결을 닫으면서도 이를 적지 않아 JDK HttpClient 와 bun fetch 가 닫힌 연결을 재사용하다 EOF 로 끝난다.
        handler.send_header("Connection", "close")
        handler.end_headers()
        handler.wfile.write(encoded)

    def claim(self, body):
        # 실제 ClaimRequest 의 네 칸과 ClaimResponse(v, allowed, ticketId, expiresAt), 성공 200·replay 409 를 따른다.
        if set(body) != {"ticket", "tool", "argsSha256", "scope"} or len(body["ticket"]) > 4096:
            return 400, {}
        payload = json.loads(base64.urlsafe_b64decode(body["ticket"].split(".")[0] + "=="))
        if body["ticket"] in self.consumed or body["tool"] != payload["tool"] or body["argsSha256"] != payload["argsSha256"]:
            return 409, {}
        self.consumed.add(body["ticket"])
        return 200, {"v": 1, "allowed": True, "ticketId": payload["ticketId"], "expiresAt": payload["expiresAt"]}

    async def send(self, path, method, body=None, token="valid"):
        import types
        query = types.SimpleNamespace(getlist=lambda key: [], keys=lambda: [])
        request = types.SimpleNamespace(url=types.SimpleNamespace(path=path), method=method, token=token,
                                        state=types.SimpleNamespace(), query_params=query)

        async def tree():
            return body

        async def raw():
            return json.dumps(body).encode()

        async def next_handler(current):
            return types.SimpleNamespace(status_code=401, body=b"null")

        request.json, request.body = tree, raw
        answer = await self.gate(request, next_handler)
        return answer.status_code, json.loads(answer.body)

    def prepare(self, tool, args=None):
        body = {"profile": PROFILE, "hermes_tool": "mcp__tossinvest__" + tool, "args": models()[tool] if args is None else args}
        return self.request(PREPARE, "POST", body)

    def execution(self, tool, args, seconds=50, raw=None):
        raw = json.dumps(args, ensure_ascii=False, separators=(",", ":")) if raw is None else raw
        now = datetime.datetime.now(datetime.UTC)
        stamp = lambda value: value.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
        claims = {"v": 1, "ticketId": str(uuid.uuid4()), "actionId": str(uuid.uuid4()), "userId": 901, "agentId": 801,
                  "connectionId": 13, "bindingId": 14, "profile": PROFILE, "connectorId": "tossinvest", "tool": tool,
                  "argsSha256": hashlib.sha256(raw.encode()).hexdigest(),
                  "scopeSha256": hashlib.sha256(json.dumps({"account_seq": ACCOUNT}, separators=(",", ":")).encode()).hexdigest(),
                  "issuedAt": stamp(now - datetime.timedelta(seconds=1)),
                  "expiresAt": stamp(now + datetime.timedelta(seconds=seconds))}
        encoded = base64.urlsafe_b64encode(json.dumps(claims, separators=(",", ":")).encode()).decode().rstrip("=")
        return {"v": 1, "protocol": "approval-claim-v2", "ticket": encoded + "." + "A" * 43,
                "argsJson": raw, "argsSha256": claims["argsSha256"]}

    def execute(self, tool, args, execution=None):
        body = {"profile": PROFILE, "hermes_tool": "mcp__tossinvest__" + tool, "args": args,
                "execution": execution or self.execution(tool, args)}
        return self.request(EXECUTE, "POST", body)


# 기반의 기존 테스트를 이 fixture 로 다시 돌리지 않는다.
for _name in list(vars(execute_base.ConnectorExecuteTest)):
    if _name.startswith("test_"):
        setattr(ConditionalSdkCase, _name, None)


class ConnectorConditionalSdkTest(ConditionalSdkCase):
    def test_three_operations_round_trip_through_actual_prepare_and_execute(self):
        for tool, (method, path) in FINANCIAL.items():
            with self.subTest(tool=tool):
                self.reset()
                status, prepared = self.prepare(tool)
                self.assertEqual(status, 200, prepared)
                self.assertTrue(prepared["ok"], prepared)
                args = prepared["result"]["executionArgs"]
                self.assertEqual(prepared["result"]["summary"]["orderKind"], "CONDITIONAL")
                self.assertEqual(self.financial(), 0)
                before = len(self.seen)
                execution = self.execution(tool, args)
                status, answer = self.execute(tool, args, execution)
                self.assertEqual(status, 200, answer)
                self.assertTrue(answer["ok"], answer)
                expected = ({"conditionalOrderId": "cond-1", "canceled": True} if method == "DELETE"
                            else {"conditionalOrderId": "cond-new", **({"clientOrderId": args["clientOrderId"]}
                                                                        if "clientOrderId" in args else {})})
                self.assertEqual(answer["result"], expected)
                executed = self.seen[before:]
                self.assertEqual([item for item in executed if item[1].endswith("/support")], [("POST", "/internal/connector-executions/support")])
                self.assertEqual(sum(1 for item in executed if item[1].endswith("/claim")), 1)
                self.assertEqual(sum(1 for item in executed if item == ("POST", "/oauth2/token")), 1)
                posts = sum(1 for item in executed if item[0] == "POST" and item[1].startswith("/api/v1/conditional-orders"))
                deletes = sum(1 for item in executed if item[0] == "DELETE")
                self.assertEqual((posts, deletes), (0, 1) if method == "DELETE" else (1, 0))
                self.assertIn((method, path), executed)
                raw = self.bodies[-1]
                if method == "DELETE":
                    self.assertEqual(raw, b"")
                else:
                    sent = json.loads(raw)
                    hidden = {"scope", "account_seq", "market", "currency", "expected_conditional_order", "expected"}
                    if tool == "modify_conditional_order":
                        hidden |= {"symbol", "clientOrderId", "conditionalOrderId"}
                    self.assertFalse(hidden & set(sent), sent)
                    self.assertIs(sent["confirmHighValueOrder"], False)
                # 같은 ticket 의 replay 는 claim 409 로 끝나고 금융 전송은 늘지 않는다.
                count = self.financial()
                status, _ = self.execute(tool, args, execution)
                self.assertEqual(self.financial(), count)

    def test_tampered_or_stale_executions_send_nothing(self):
        status, prepared = self.prepare("create_conditional_order")
        self.assertTrue(prepared["ok"], prepared)
        args = prepared["result"]["executionArgs"]
        changed = dict(args, quantity="3")
        cases = {
            "변조": (changed, self.execution("create_conditional_order", args, raw=json.dumps(args))),
            "추가 키": (dict(args, extra="1"), None),
            "number decimal": (dict(args, quantity=2), None),
            "만료": (args, self.execution("create_conditional_order", args, seconds=-0.5)),
        }
        for label, (value, execution) in cases.items():
            with self.subTest(case=label):
                self.execute("create_conditional_order", value, execution)
                self.assertEqual(self.financial(), 0)
        self.assertEqual(self.count("POST", "/internal/connector-executions/claim"), 0)
        # 계좌 env 가 바뀌면 scope 대조에서, manifest 가 바뀌면 SHA 대조에서 자식을 띄우기 전에 멈춘다.
        (self.profile / ".env").write_text((self.profile / ".env").read_text().replace("SEQ=7", "SEQ=8"))
        self.assertEqual(self.execute("create_conditional_order", args)[0], 409)
        (self.profile / ".env").write_text((self.profile / ".env").read_text().replace("SEQ=8", "SEQ=7"))
        manifest = json.loads((self.root / "assistant.json").read_text())
        manifest["title"] = "변경한 선언"
        (self.root / "assistant.json").write_text(json.dumps(manifest))
        self.assertEqual(self.execute("create_conditional_order", args)[0], 409)
        self.assertEqual((self.financial(), self.count("POST", "/internal/connector-executions/claim")), (0, 0))

    def test_unknown_after_send_answers_504_without_replay(self):
        status, prepared = self.prepare("modify_conditional_order")
        self.assertTrue(prepared["ok"], prepared)
        args = prepared["result"]["executionArgs"]
        self.mode = "unknown"
        execution = self.execution("modify_conditional_order", args)
        status, answer = self.execute("modify_conditional_order", args, execution)
        self.assertEqual(status, 504, answer)
        self.assertEqual(answer["code"], "TOSSINVEST_OUTCOME_UNKNOWN")
        self.assertEqual(self.financial(), 1)
        self.execute("modify_conditional_order", args, execution)
        self.assertEqual(self.financial(), 1)

    def test_rejected_prepare_sends_nothing(self):
        for label, args in (("true 동의", dict(models()["create_conditional_order"], confirmHighValueOrder=True)),
                            ("모델 scope", dict(models()["create_conditional_order"], account_seq=ACCOUNT)),
                            ("C1 ID", {"conditionalOrderId": "a\u0085b"})):
            with self.subTest(case=label):
                tool = "cancel_conditional_order" if "conditionalOrderId" in args else "create_conditional_order"
                status, answer = self.prepare(tool, copy.deepcopy(args))
                self.assertEqual(status, 200, answer)
                self.assertFalse(answer["ok"], answer)
        self.assertEqual(self.financial(), 0)


if __name__ == "__main__":
    unittest.main()
