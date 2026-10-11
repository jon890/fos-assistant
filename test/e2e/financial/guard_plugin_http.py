"""실제 plugin 설치·상태·support를 loopback HTTP로 Spring 시험에 연결한다."""

import asyncio
import http.server
import importlib
import json
import os
import pathlib
import sys
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
            "write_note": {"risk": "WRITE"},
            "place_order": {"risk": "FINANCIAL", "approval": "always"}},
            execution_guard={"protocol": "approval-claim-v1", "prepare_tool": "list_scopes",
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

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def handle_request(self):
            parsed = urllib.parse.urlsplit(self.path)
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length)) if length else None
            status, answer = 200, {}
            if parsed.path == "/fixture/inspect":
                answer = fixture.inspect()
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
            self.end_headers()
            self.wfile.write(encoded)

        do_GET = do_POST = do_PUT = do_DELETE = handle_request

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
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
