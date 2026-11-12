"""실제 plugin 설치·상태·support를 loopback HTTP로 Spring 시험에 연결한다."""

import asyncio
import hashlib
import http.server
import importlib
import json
import os
import pathlib
import shutil
import signal
import sys
import tempfile
import threading
import time
import traceback
import urllib.parse
import urllib.request
import mcp

REPO = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "hermes/tests"))
from dashboard_profile_api_support import ProfileApiRouteTest, DEMO

AUTH_PATH = "/oauth/token"
FINANCIAL_PATH = "/financial/orders"
SUPPORT_PATH = "/internal/connector-executions/support"


class Fixture(ProfileApiRouteTest):
    """Hermes 환경 준비만 재사용한다. guard와 설치 처리기는 원본 plugin이다."""

    def prepare(self, callback, token):
        self.setUp()
        self.connector, _ = self.bind_fixture()
        # 기반 환경의 보관 파일은 지운다. 이후 연결 ID의 파일은 실제 Java 클라이언트만 만든다.
        for vault in ("c1", "c2"):
            self.assertEqual(self.vault("DELETE", vault=vault).status_code, 200)
        os.environ["FOS_ASSISTANT_CONNECTOR_COMMAND"] = sys.executable
        declared = json.loads((self.connector / "connector.json").read_text())
        declared.update(schema=2, tools={
            "list_scopes": {"risk": "READ"},
            "write_note": {"risk": "WRITE", "outbound": False},
            "place_order": {"risk": "FINANCIAL", "approval": "always"}},
            execution_guard={"protocol": "approval-claim-v2", "prepare_tool": "list_scopes",
                             "scope_fields": [{"arg": "account_seq", "field": "scope"}],
                             "operations": {"place_order": "CREATE"}})
        self.original = declared
        self.write_manifest(declared)
        os.environ["FOS_CONNECTOR_EXECUTIONS_BASE_URL"] = callback
        os.environ["HERMES_DASHBOARD_PROFILE_API_SECRET"] = token
        self.nonces = []
        self.support_codes = []
        self.financial_posts = self.financial_deletes = self.auth_posts = 0
        # 이 프로세스의 urllib 시도만 센다. MCP 자식과 다른 HTTP 라이브러리는 관측하지 않는다.
        # loopback 대역의 목적지 경로를 구분하며 support는 실제 Spring에 보낸다.
        original_open = urllib.request.OpenerDirector.open
        self.addCleanup(setattr, urllib.request.OpenerDirector, "open", original_open)

        def observed(opener, request, *args, **kwargs):
            url = request.full_url if isinstance(request, urllib.request.Request) else request
            parsed = urllib.parse.urlsplit(url)
            if parsed.hostname != "127.0.0.1":
                raise AssertionError("loopback 밖의 HTTP 요청이다")
            method = request.get_method() if isinstance(request, urllib.request.Request) else "GET"
            support = parsed.path == SUPPORT_PATH and method == "POST"
            if support:
                self.nonces.append(json.loads(request.data)["nonce"])
            elif parsed.path == AUTH_PATH and method == "POST":
                self.auth_posts += 1
            elif parsed.path == FINANCIAL_PATH and method == "POST":
                self.financial_posts += 1
            elif parsed.path == FINANCIAL_PATH and method == "DELETE":
                self.financial_deletes += 1
            try:
                answer = original_open(opener, request, *args, **kwargs)
                if support:
                    self.support_codes.append(answer.status)
                return answer
            except urllib.error.HTTPError as error:
                if support:
                    self.support_codes.append(error.code)
                raise

        urllib.request.OpenerDirector.open = observed
        self.snapshot()

    def snapshot(self):
        """준비를 마친 임시 디렉터리를 통째로 떠 둔다. 검사 클래스가 프로세스를 함께 쓸 때 reset 이 이것으로 되돌린다."""
        # profile, 보관 파일, 플러그인 원본이 모두 이 디렉터리 아래에 있다. 파일 이름을 고르지 않고 디렉터리째 뜬다.
        self.workspace = self.root.parent
        held = tempfile.TemporaryDirectory(prefix="fos-guard-snapshot-")
        self.addCleanup(held.cleanup)
        self.saved = pathlib.Path(held.name) / "workspace"
        shutil.copytree(self.workspace, self.saved, symlinks=True)
        self.initial_digest = self.digest(self.workspace)

    @staticmethod
    def digest(base):
        """디렉터리 아래 경로, 권한, 내용, 링크 대상을 한 값으로 줄인다. 되돌린 뒤 처음과 같은지 비교한다."""
        summary = hashlib.sha256()
        for path in sorted(base.rglob("*")):
            relative = str(path.relative_to(base))
            if path.is_symlink():
                summary.update(("L %s %s\n" % (relative, os.readlink(path))).encode())
            elif path.is_dir():
                summary.update(("D %s %o\n" % (relative, path.stat().st_mode)).encode())
            else:
                content = hashlib.sha256(path.read_bytes()).hexdigest()
                summary.update(("F %s %o %s\n" % (relative, path.stat().st_mode, content)).encode())
        return summary.hexdigest()

    def reset(self):
        """앞 검사가 바꾼 파일과 관측값을 준비 직후로 되돌리고, 파일이 처음과 같은지 답한다."""
        # 앞 검사가 띄운 stdio MCP 서버가 종료 중에 plugin 디렉터리 아래에 파일(__pycache__ 등)을 쓸 수 있다.
        # 지우는 도중 그 쓰기와 겹치면 OSError 가 나므로 몇 번 다시 지운다.
        for attempt in range(5):
            try:
                for child in self.workspace.iterdir():
                    if child.is_dir() and not child.is_symlink():
                        shutil.rmtree(child)
                    else:
                        child.unlink()
                break
            except OSError:
                if attempt == 4:
                    raise
                time.sleep(0.2)
        shutil.copytree(self.saved, self.workspace, symlinks=True, dirs_exist_ok=True)
        # guard 판정은 매번 Spring 에 묻지만, 다시 읽는 것으로 모듈에 남은 값이 없게 한다.
        importlib.reload(importlib.import_module(self.plugin.__name__ + ".connector_guard"))
        type(self).deleted = []
        type(self).reloads = []
        type(self).list_fails = False
        type(self).reload_fails = False
        self.nonces.clear()
        self.support_codes.clear()
        self.financial_posts = self.financial_deletes = self.auth_posts = 0
        return {"restored": self.digest(self.workspace) == self.initial_digest}

    def write_manifest(self, declared):
        (self.connector / "connector.json").write_text(json.dumps(declared, ensure_ascii=False))

    def inspect(self):
        directory = self.root / "alice"
        state = json.loads((directory / self.plugin.CONNECTOR_STATE).read_text()) if (
            directory / self.plugin.CONNECTOR_STATE).exists() else {}
        env = (directory / ".env").read_text() if (directory / ".env").exists() else ""
        entry = state.get(DEMO, {})
        return {"entry": entry, "nonces": self.nonces, "supportCodes": self.support_codes,
                "financialPosts": self.financial_posts, "financialDeletes": self.financial_deletes,
                "authPosts": self.auth_posts, "executionEnv": "FOS_APPROVAL_" in env}

    async def probe(self):
        # MCP 목록도 실제 stdio SDK에서 얻는다. 금융 도구를 실행하지 않는다.
        from mcp import ClientSession, StdioServerParameters
        from mcp.client.stdio import stdio_client
        manifest = self.plugin._connector_manifest(DEMO)
        server = manifest["server"]
        async with stdio_client(StdioServerParameters(command=server["command"], args=server["args"])) as streams:
            async with ClientSession(*streams) as session:
                await session.initialize()
                result = await session.list_tools()
                return {"ok": True, "tools": [{"name": tool.name} for tool in result.tools]}


def main():
    Fixture.setUpClass()
    fixture = Fixture()
    fixture.prepare(sys.argv[1], sys.argv[2])

    # reset 은 진행 중인 다른 요청이 끝난 뒤에 돌고, 도는 동안 새 요청은 기다린다.
    # reset 이 디렉터리를 지우는 동안 다른 요청이 같은 파일을 읽거나 쓰면 한쪽이 예외로 끝나 연결이 끊긴다.
    gate = threading.Condition()
    gate_state = {"active": 0, "resetting": False}

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def handle_request(self):
            resetting = urllib.parse.urlsplit(self.path).path == "/fixture/reset"
            with gate:
                if resetting:
                    gate_state["resetting"] = True
                    gate_state["active"] += 1
                    gate.wait_for(lambda: gate_state["active"] == 1, timeout=5)
                else:
                    gate.wait_for(lambda: not gate_state["resetting"], timeout=30)
                    gate_state["active"] += 1
            try:
                self.dispatch()
            except Exception:
                # 처리기가 예외로 끝나면 Java 쪽은 응답 없는 연결 종료만 본다. 원인을 응답 본문에 싣는다.
                detail = traceback.format_exc()
                sys.stderr.write(detail)
                encoded = json.dumps({"error": detail}, ensure_ascii=False).encode()
                self.send_response(500)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                # 닫는다고 헤더에 적는다. http.server 의 HTTP/1.0 응답은 연결을 닫으면서도 이를 적지 않아 JDK HttpClient 와 bun fetch 가 닫힌 연결을 재사용하다 EOF 로 끝난다.
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(encoded)
            finally:
                with gate:
                    gate_state["active"] -= 1
                    if resetting:
                        gate_state["resetting"] = False
                    gate.notify_all()

        def dispatch(self):
            parsed = urllib.parse.urlsplit(self.path)
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length)) if length else None
            status, answer = 200, {}
            if parsed.path == "/fixture/inspect":
                answer = fixture.inspect()
            elif parsed.path == "/fixture/reset":
                answer = fixture.reset()
            elif parsed.path == "/fixture/change":
                declared = json.loads(json.dumps(fixture.original))
                if body["mode"] == "changedSha":
                    declared["title"] = "달라진 설치 원본"
                elif body["mode"] == "noGuard":
                    declared.pop("execution_guard")
                    declared["tools"].pop("place_order")
                fixture.write_manifest(declared)
                if body["mode"] == "missingGuard":
                    state_file = fixture.root / "alice" / fixture.plugin.CONNECTOR_STATE
                    state = json.loads(state_file.read_text())
                    state[DEMO].pop("guard", None)
                    state_file.write_text(json.dumps(state))
                if body["mode"] == "restart":
                    module = importlib.import_module(fixture.plugin.__name__ + ".connector_guard")
                    importlib.reload(module)
            elif parsed.path.startswith("/api/mcp/servers/"):
                answer = asyncio.run(fixture.probe())
            else:
                profiles = urllib.parse.parse_qs(parsed.query).get("profile", [])
                response = fixture.request(parsed.path, self.command, token="valid", body=body,
                                           query_profiles=profiles, full_response=True)
                status, answer = response.status_code, getattr(response, "body", {})
            encoded = json.dumps(answer, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(encoded)

        do_GET = do_POST = do_PUT = do_DELETE = handle_request

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    # Java 쪽 close() 는 SIGTERM 으로 끈다. serve_forever 를 멈춰 아래 finally 가 임시 디렉터리와 스냅샷을 지우게 한다.
    signal.signal(signal.SIGTERM, lambda signum, frame: threading.Thread(target=server.shutdown).start())
    print(json.dumps({"port": server.server_port}), flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()
        fixture.doCleanups()
        fixture.tearDown()
        Fixture.tearDownClass()
        Fixture.doClassCleanups()


if __name__ == "__main__":
    main()
