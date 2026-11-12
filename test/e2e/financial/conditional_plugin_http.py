"""Spring 의 실제 support·claim 과 조건주문 SDK 자식을 잇는 loopback 시험 fixture 다.

조건 SDK 의 테스트 원본 생성 명령(`conditional-entry.ts --generate`)이 만든 임시 connector 를 설치한다.
금융 목적지만 이 프로세스의 loopback 대역이고, support 와 claim 은 인자로 받은 Spring 주소로 간다.
"""

import asyncio
import copy
import http.server
import importlib
import json
import logging
import os
import pathlib
import signal
import sys
import threading
import time
import types
import urllib.parse

REPO = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "hermes/tests"))
import test_connector_conditional_sdk as sdk  # noqa: E402

FINANCIAL_PREFIX = "/api/v1/conditional-orders"
# 공식 거절 표에 있는 code 와 없는 code 다. 없는 code 는 같은 status 라도 UNKNOWN 이다.
ERRORS = {"429": (429, "too-many-requests"), "500": (500, "internal"), "400": (400, "invalid-request"),
          "400-unknown": (400, "bad-request")}


class Fixture(sdk.ConditionalSdkCase):
    def setUp(self):
        super().setUp()
        # 부모가 support·claim 을 이 프로세스의 대역으로 돌렸다. 실제 Spring 으로 되돌린다.
        os.environ["HERMES_DASHBOARD_PROFILE_API_SECRET"] = sys.argv[2]
        os.environ["FOS_CONNECTOR_EXECUTIONS_BASE_URL"] = sys.argv[1]
        self.guard_module = importlib.import_module(self.plugin.__name__ + ".connector_guard")
        self.profile_name = None
        self.prepares = self.executes = 0
        self.original = copy.deepcopy(sdk.ORIGINAL)
        self.addCleanup(self.assert_no_child_left)

    def configure(self, body):
        profile = self.profile_root / body["profile"]
        profile.mkdir(exist_ok=True)
        (profile / self.plugin.MANAGED_MARKER).touch()
        (profile / ".env").write_text("TOSSINVEST_CLIENT_ID=fake-client-id-for-tests\n"
                                      "TOSSINVEST_CLIENT_SECRET=fake-client-secret-for-tests\n"
                                      "TOSSINVEST_ACCOUNT_SEQ=%s\n" % sdk.ACCOUNT)
        manifest = self.plugin._connector_manifest("tossinvest")
        self.entry = {"server": manifest["server"], "mcp_server": "tossinvest", "allowlist_added": True,
                      "mode": "bind", "vault": "c1", "skills": list(manifest["skills"]), "guard": body["guard"]}
        (profile / self.plugin.CONNECTOR_STATE).write_text(json.dumps({"tossinvest": self.entry}))
        self.profile_name = body["profile"]
        self.prepares = self.executes = 0
        self.reset()
        self.mode = body.get("mode", "ok")
        self.original = copy.deepcopy(sdk.ORIGINAL)
        return {"ok": True}

    def counters(self):
        return {"url": self.url, "auth": self.count("POST", "/oauth2/token"), "financial": self.financial(),
                "deletes": self.count("DELETE"), "prepare": self.prepares, "execute": self.executes}

    def answer(self, handler):
        raw = handler.rfile.read(int(handler.headers.get("Content-Length") or 0))
        path, _, query = handler.path.partition("?")
        symbols = urllib.parse.parse_qs(query).get("symbols", ["ABC"])[0].split(",")
        self.seen.append((handler.command, path))
        status, body = 200, None
        if path == "/oauth2/token":
            body = {"access_token": "fake-access-token-1", "token_type": "Bearer", "expires_in": 3600}
        elif path == "/api/v1/accounts":
            body = {"result": [{"accountSeq": int(sdk.ACCOUNT), "accountNo": "00001234", "accountType": "BROKERAGE"}]}
        elif path == "/api/v1/stocks":
            if self.mode == "slow-prepare":
                # Spring prepareClient 의 읽기 제한 2초를 넘긴다. 늦게 끝난 준비 결과는 받을 쪽이 없다.
                time.sleep(2.5)
            body = {"result": [dict(sdk.STOCK, symbol=symbol) for symbol in symbols]}
        elif path == "/api/v1/prices":
            body = {"result": [{"symbol": symbol, "timestamp": None, "lastPrice": "100", "currency": "USD"}
                               for symbol in symbols]}
        elif handler.command == "GET" and path.startswith(FINANCIAL_PREFIX + "/"):
            if path.rsplit("/", 1)[1] == self.original["conditionalOrderId"]:
                body = {"result": self.original}
            else:
                status, body = 404, error("conditional-order-not-found")
        else:
            self.bodies.append(raw)
            if self.mode == "disconnect":
                handler.close_connection = True
                return
            if self.mode == "timeout":
                time.sleep(2.5)
            if self.mode in ERRORS:
                status, body = ERRORS[self.mode][0], error(ERRORS[self.mode][1])
            elif handler.command == "DELETE":
                status = 204
            elif self.mode == "malformed":
                body = {"result": {}}
            else:
                sent = json.loads(raw)
                # 공식상 clientOrderId 는 응답에서 빠지거나 null 일 수 있다.
                echoed = {"no-client-id": {}, "null-client-id": {"clientOrderId": None}}.get(
                    self.mode, {"clientOrderId": sent["clientOrderId"]} if sent.get("clientOrderId") else {})
                body = {"result": {"conditionalOrderId": "cond-new", **echoed}}
        reply(handler, status, body)

    async def gateway(self, path, method, body=None, token="valid"):
        # 카탈로그는 `?include=standard` 를 싣고 온다. starlette 처럼 경로와 query 를 나눠 넘긴다.
        path, _, raw_query = path.partition("?")
        query = urllib.parse.parse_qs(raw_query)
        request = types.SimpleNamespace(url=types.SimpleNamespace(path=path), method=method, token=token,
            state=types.SimpleNamespace(), query_params=types.SimpleNamespace(getlist=lambda key: query.get(key, []),
                                                                              keys=lambda: list(query)))

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


def error(code):
    return {"error": {"requestId": "mock", "code": code, "message": "mock"}}


def reply(handler, status, body):
    encoded = b"" if body is None else json.dumps(body, ensure_ascii=False).encode()
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(encoded)))
    handler.end_headers()
    handler.wfile.write(encoded)


Fixture.setUpClass()
logging.disable(logging.CRITICAL)
fixture = Fixture()
fixture.setUp()
# 운영 대시보드처럼 이벤트 루프 하나에서 요청을 처리한다. 스레드마다 새 루프를 만들면 plugin 의 전역 asyncio.Lock 이 멈춘다.
loop = asyncio.new_event_loop()
threading.Thread(target=loop.run_forever, daemon=True).start()


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
            answer = fixture.counters()
        elif self.path == "/fixture/original":
            # 승인 뒤 claim 전에 현재 원주문이 바뀐 상황을 만든다. configure 가 되돌린다.
            fixture.original = body
            answer = {"ok": True}
        elif self.path == "/fixture/current-original":
            answer = {"original": fixture.original}
        elif self.path == "/fixture/bodies":
            answer = {"bodies": [raw.decode() for raw in fixture.bodies]}
        elif self.path.startswith("/api/connectors?"):
            answer = fixture.state()
        else:
            if self.path.endswith("/prepare"):
                fixture.prepares += 1
            if self.path.endswith("/execute"):
                fixture.executes += 1
            token = "valid" if self.headers.get("Authorization") == "Bearer " + sys.argv[2] else "wrong"
            status, answer = asyncio.run_coroutine_threadsafe(
                fixture.gateway(self.path, self.command, body, token), loop).result()
        reply(self, status, answer)


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
signal.signal(signal.SIGTERM, lambda signum, frame: threading.Thread(target=server.shutdown).start())
print(json.dumps({"url": "http://127.0.0.1:%d" % server.server_port}), flush=True)
try:
    server.serve_forever()
finally:
    server.server_close()
    loop.call_soon_threadsafe(loop.stop)
    fixture.doCleanups()
    Fixture.doClassCleanups()
