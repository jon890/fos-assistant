"""dashboard-profile-api 가 원격 MCP 플러그인의 확인 도구와 승인한 쓰기를 Streamable HTTP 로 부르는 규칙을 검사한다.

근거는 ADR-20261026 remote-mcp-plugin 이다. 검사용 원격 서버를 loopback 의 빈 포트에 따로 프로세스로 띄운다.
칸 값은 선언한 헤더로만 가고, redirect 는 따라가지 않고, 주소와 토큰은 로그에 남지 않아야 한다.
"""

import importlib.util
import json
import pathlib
import sys
import types
import unittest

from plugin_loading import patch_plugin

import test_connector_call as call_base
import test_connector_manifest as base
from dashboard_profile_api_support import REMOTE, REMOTE_VALUE, remote_connector

_FIXTURE = importlib.util.spec_from_file_location(
    "remote_mcp_server", pathlib.Path(__file__).resolve().parent / "fixtures/remote_mcp_server.py")
remote_mcp_server = importlib.util.module_from_spec(_FIXTURE)
_FIXTURE.loader.exec_module(remote_mcp_server)

CALL = "/api/connectors/%s/call" % REMOTE
EXECUTE = "/api/connectors/%s/execute" % REMOTE
PROFILE = "alice"
WRITE_ITEM = "mcp__remote__write_item"
HANG_UP = "mcp__remote__hang_up"
UNAVAILABLE = (200, {"ok": False, "error": "unavailable"})
# 서버가 받은 `Authorization` 헤더다. 칸 값은 이 자리에만 들어간다.
SENT_HEADER = "Bearer " + REMOTE_VALUE


class RemoteCallTest(base.ConnectorGateCase):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        # 승인 실행은 profile 디렉터리를 찾는다. 그 Hermes 모듈만 대역으로 더한다.
        profiles = types.ModuleType("hermes_cli.profiles")
        profiles.get_profile_dir = lambda name: cls.profile_root / name
        profiles.profile_exists = lambda name: (cls.profile_root / name).is_dir()
        previous = sys.modules.get("hermes_cli.profiles")

        def restore():
            if previous is None:
                sys.modules.pop("hermes_cli.profiles", None)
            else:
                sys.modules["hermes_cli.profiles"] = previous

        cls.addClassCleanup(restore)
        sys.modules["hermes_cli.profiles"] = profiles

    def setUp(self):
        super().setUp()
        self.server_base = self.enterContext(remote_mcp_server.running_remote_server())
        self.remote_root = remote_connector(self.base / "remote-connector")
        self.rewrite_remote("assistant.json", lambda value: value["tools"].update(
            write_item={"risk": "WRITE", "outbound": False}, hang_up={"risk": "WRITE", "outbound": False}))
        self.point_to("/mcp")
        type(self).profile_root = self.base / "profiles"
        self.profile_root.mkdir()
        self.install()

    def point_to(self, path):
        """운영 목록의 주소를 검사용 서버의 그 경로로 둔다. 운영 목록의 `url` 은 `http://` 도 받는다."""
        self.url = self.server_base + path
        self.operator_list({REMOTE: {"root": str(self.remote_root), "url": self.url}})

    def rewrite_remote(self, name, change):
        path = self.remote_root / name
        value = json.loads(path.read_text(encoding="utf-8"))
        change(value)
        path.write_text(json.dumps(value), encoding="utf-8")

    def install(self, token=REMOTE_VALUE):
        """원격 플러그인을 바인딩으로 설치한 profile 의 파일을 만든다. 칸 값은 `.env` 에 있다."""
        profile = self.profile_root / PROFILE
        profile.mkdir(exist_ok=True)
        (profile / ".env").write_text("REMOTE_TOKEN=%s\n" % token, encoding="utf-8")
        (profile / self.plugin.MANAGED_MARKER).write_text("", encoding="utf-8")
        manifest = self.plugin._connector_manifest(REMOTE)
        entry = {"server": self.plugin._connector_server(manifest), "allowlist_added": True,
                 "mcp_server": manifest["mcp_server"], "mode": "bind", "vault": "c1", "skills": ["remote"]}
        (profile / self.plugin.CONNECTOR_STATE).write_text(json.dumps({REMOTE: entry}), encoding="utf-8")

    async def send(self, path, method, body=None, token="valid"):
        """미들웨어를 한 번 거친다. 승인 실행이 query 를 읽으므로 빈 query 를 준다."""
        request = types.SimpleNamespace(
            url=types.SimpleNamespace(path=path), method=method, token=token,
            state=types.SimpleNamespace(), query_params=types.SimpleNamespace(getlist=lambda key: []),
        )

        async def read_json():
            return body

        request.json = read_json

        async def call_next(current):
            return types.SimpleNamespace(status_code=401, body=b"null")

        response = await self.gate(request, call_next)
        return response.status_code, json.loads(response.body)

    def call(self, tool="list_items", values=None):
        return self.request(CALL, "POST", {"tool": tool, "values": {"token": REMOTE_VALUE} if values is None else values})

    def execute(self, hermes_tool=WRITE_ITEM, args=None):
        return self.request(EXECUTE, "POST", {"profile": PROFILE, "hermes_tool": hermes_tool,
                                              "args": {} if args is None else args})

    def received(self):
        return remote_mcp_server.received(self.server_base)

    def assert_nothing_leaked(self, logs):
        """모은 로그 어디에도 원격 주소와 서버의 주소 앞부분, 토큰이 없다."""
        output = "\n".join(logs)
        for secret in (self.url, self.server_base, REMOTE_VALUE):
            self.assertNotIn(secret, output, "로그에 %r 가 남았다" % secret)

    def test_call_sends_the_field_value_only_in_the_declared_header(self):
        """확인 도구를 부르면 결과가 오고, 서버가 받은 헤더는 `Bearer <칸 값>` 이다. 로그에 주소와 토큰이 없다."""
        with call_base.collected_logs() as logs:
            answer = self.call()
        self.assertEqual(answer, (200, {"ok": True, "result": {"authorization": SENT_HEADER}}))
        self.assert_nothing_leaked(logs)

    def test_call_without_the_header_value_does_not_reach_the_server(self):
        """헤더가 참조하는 칸 값이 없으면 부르지 않고 `unavailable` 이다."""
        self.assertEqual(self.call(values={}), UNAVAILABLE)

    def test_vault_call_keeps_the_operator_url_after_plugin_url_changes(self):
        """보관 토큰으로 확인해도 플러그인 갱신이 토큰의 목적지를 바꾸지 않는다."""
        self.rewrite_remote(".mcp.json", lambda value: value["mcpServers"]["remote"].update(
            url=self.server_base + "/landing"))
        stored = {"v": 1, "connector": REMOTE, "values": {"token": REMOTE_VALUE}}
        with patch_plugin(self.plugin, "_read_vault", return_value=stored):
            answer = self.request(CALL, "POST", {"tool": "list_items", "vault": "c1"})
        self.assertEqual(answer, (200, {"ok": True, "result": {"authorization": SENT_HEADER}}))
        self.assertEqual(self.received()["landing"], 0)

    def test_vault_call_without_operator_url_never_reads_the_token(self):
        self.operator_list({REMOTE: {"root": str(self.remote_root)}})
        with patch_plugin(self.plugin, "_read_vault") as read:
            status, _ = self.request(CALL, "POST", {"tool": "list_items", "vault": "c1"})
        self.assertEqual(status, 404)
        read.assert_not_called()

    def test_oversized_json_and_sse_fail_without_leaking_logs(self):
        for path in ("/oversized-json", "/oversized-sse"):
            with self.subTest(path=path):
                self.point_to(path)
                with call_base.collected_logs() as logs:
                    self.assertEqual(self.call(), UNAVAILABLE)
                self.assert_nothing_leaked(logs)

    def test_call_does_not_run_a_tool_that_is_not_read_only(self):
        """확인 도구가 서버의 `tools/list` 에서 읽기 전용이 아니면 부르지 않는다."""
        # 플러그인은 읽기 도구로 선언했지만 서버는 읽기 전용이라고 알리지 않는다.
        self.rewrite_remote("assistant.json", lambda value: value.update(
            verify={"tool": "write_item"}, tools={**value["tools"], "write_item": {"risk": "READ"}}))
        self.point_to("/mcp")
        status, body = self.call(tool="write_item")
        self.assertEqual(status, 400, body)
        self.assertEqual(self.received()["write_item"], 0, "읽기 전용이 아닌 도구가 불렸다")

    def test_execute_runs_the_write_tool_with_the_profile_value(self):
        """승인한 쓰기를 받은 인자로 부른다. 헤더의 칸 값은 그 profile 의 `.env` 에서 왔다."""
        with call_base.collected_logs() as logs:
            status, body = self.execute(args={"text": "안녕"})
        self.assertEqual(status, 200, body)
        self.assertEqual(body, {"ok": True, "result": {"authorization": SENT_HEADER, "text": "안녕"}})
        self.assertEqual(self.received()["write_item"], 1)
        self.assert_nothing_leaked(logs)

    def test_execute_that_loses_the_connection_after_sending_is_unknown(self):
        """서버가 요청을 받은 뒤 답하지 않고 끊으면 실행됐는지 모르므로 504 다. 로그에 주소와 토큰이 없다."""
        with patch_plugin(self.plugin, "CONNECTOR_EXECUTE_TIMEOUT_SECONDS", 15), call_base.collected_logs() as logs:
            status, body = self.execute(HANG_UP)
        self.assertEqual(status, 504, body)
        self.assert_nothing_leaked(logs)

    def test_execute_with_a_header_breaking_value_does_not_run(self):
        """profile `.env` 의 칸 값에 NUL 이 있으면 부르기 전에 까닭을 로그로 남기고 `{ok: false}` 다."""
        self.install(token="remote_\x000123")
        with patch_plugin(self.plugin, "_run_connector_execute") as runner, call_base.collected_logs() as logs:
            self.assertEqual(self.execute(args={"text": "a"}), UNAVAILABLE)
        runner.assert_not_called()
        self.assertTrue(any("헤더로 보낼 수 없는 글자" in line for line in logs), logs)
        self.assertEqual(self.received()["write_item"], 0)

    def test_redirect_is_not_followed(self):
        """주소가 redirect 를 주면 따라가지 않고 `unavailable` 이다. redirect 대상은 요청을 받지 않는다."""
        self.point_to("/moved")
        with call_base.collected_logs() as logs:
            self.assertEqual(self.call(), UNAVAILABLE)
            # 도구 호출을 보내기 전의 실패다. 실행되지 않은 것이 분명하다.
            self.assertEqual(self.execute(args={"text": "a"}), UNAVAILABLE)
        self.assertEqual(self.received(), {"landing": 0, "write_item": 0})
        self.assert_nothing_leaked(logs)


if __name__ == "__main__":
    unittest.main()
