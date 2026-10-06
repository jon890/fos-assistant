"""dashboard-profile-api 가 Control Plane 이 승인한 호출을 그 profile 의 값으로 한 번 실행하는 규칙을 검사한다(ADR-050).

시험 커넥터의 MCP 서버를 실제 자식 프로세스로 띄운다. 검사가 끝날 때마다 남은 자식이 없는지 본다.
"""

import asyncio
import json
import os
import subprocess
import sys
import time
import types
import unittest
from unittest import mock

import test_connector_call as call_base
import test_connector_manifest as base

EXECUTE = "/api/connectors/%s/execute" % base.DEMO
PROFILE = "alice"
WRITE_NOTE = "mcp__demo__write_note"
UNAVAILABLE = (200, {"ok": False, "error": "unavailable"})
# 그 profile 의 `.env` 에 함께 있는 다른 값이다. 이 커넥터의 칸이 아니므로 자식에게 가면 안 된다.
PROFILE_ONLY = {"API_SERVER_KEY": "profile-api-key", "OTHER_CONNECTOR_TOKEN": "profile-other"}


class ConnectorExecuteTest(base.ConnectorGateCase):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        # 기반은 profile 을 다루지 않는다. profile 디렉터리를 찾는 Hermes 모듈만 대역으로 더한다.
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
        type(self).profile_root = self.base / "profiles"
        self.profile_root.mkdir()
        self.install(PROFILE)
        self.addCleanup(self.assert_no_child_left)

    def install(self, name, token=call_base.OK_TOKEN, managed=True, installed=True, host=False, bind=False):
        """커넥터를 설치한 profile 의 파일을 만든다. 표식과 소유 기록은 빼고 만들 수 있다.

        `host` 는 운영자가 사람이 만든 profile 에 두는 커넥터 표식이고, `bind` 는 소유 기록을 바인딩 항목으로 둔다.
        """
        profile = self.profile_root / name
        profile.mkdir()
        lines = ["%s=%s" % item for item in {"DEMO_TOKEN": token, **PROFILE_ONLY}.items()]
        (profile / ".env").write_text("\n".join(lines) + "\n", encoding="utf-8")
        if managed:
            (profile / self.plugin.MANAGED_MARKER).write_text("", encoding="utf-8")
        if host:
            (profile / self.plugin.CONNECTOR_HOST_MARKER).write_text("", encoding="utf-8")
        if installed:
            manifest = self.plugin._connector_manifest(base.DEMO)
            entry = {"server": self.plugin._connector_server(manifest), "allowlist_added": True,
                     "mcp_server": manifest["mcp_server"]}
            if bind:
                entry.update(mode="bind", vault="c1", skills=["demo"])
            (profile / self.plugin.CONNECTOR_STATE).write_text(json.dumps({base.DEMO: entry}), encoding="utf-8")

    def test_binding_on_a_profile_with_only_the_connector_marker_runs(self):
        """커넥터 표식만 있는 profile 에서 바인딩 항목은 실행하고, 옛 항목이나 표식 없는 profile 은 401 이다."""
        self.install("human", managed=False, host=True, bind=True)
        self.install("human-old", managed=False, host=True)
        self.install("plain", managed=False, bind=True)
        status, body = self.execute(args={"text": "안녕"}, profile="human")
        self.assertEqual(status, 200, body)
        self.assertEqual(body, {"ok": True, "result": {"written": True, "text": "안녕", "token": "demo"}})
        with mock.patch.object(self.plugin, "_run_connector_execute") as runner:
            self.assertEqual(self.execute(profile="human-old")[0], 401)
            self.assertEqual(self.execute(profile="plain")[0], 401)
            runner.assert_not_called()
        # 관리 표식이 있는 profile 은 바인딩 항목도 실행한다.
        self.install("managed-bind", bind=True)
        self.assertEqual(self.execute(args={"text": "a"}, profile="managed-bind")[0], 200)

    def children(self):
        """시험 커넥터 사본의 서버를 돌리고 있는 프로세스 번호다."""
        found = subprocess.run(["pgrep", "-f", str(self.connector_root / "server.py")],
                               capture_output=True, text=True)
        return found.stdout.split()

    def assert_no_child_left(self):
        self.assertEqual(self.children(), [], "실행이 끝났는데 자식 프로세스가 남았다")

    async def send(self, path, method, body=None, token="valid"):
        """미들웨어를 한 번 거친다. 기반과 달리 query 를 읽을 수 있게 빈 query 를 준다."""
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

    def execute(self, hermes_tool=WRITE_NOTE, args=None, profile=PROFILE, **request):
        body = {"profile": profile, "hermes_tool": hermes_tool, "args": {} if args is None else args}
        return self.request(EXECUTE, "POST", body, **request)

    def declare_schema_two(self):
        """시험 커넥터를 `schema: 2` 로 바꾼다. `env_view` 는 선언하지 않는다."""
        self.rewrite("connector.json", lambda value: value.update(
            schema=2, tools={"list_scopes": {"risk": "READ"}, "write_note": {"risk": "WRITE"}}))

    def test_write_tool_runs_with_the_given_arguments(self):
        """설치한 profile 에서 쓰기 도구를 받은 인자로 부르고 결과를 그대로 돌려준다."""
        status, body = self.execute(args={"text": "안녕"})
        self.assertEqual(status, 200, body)
        # 토큰은 그 profile 의 `.env` 에서 왔다. 시험 도구는 앞 4자만 돌려준다.
        self.assertEqual(body, {"ok": True, "result": {"written": True, "text": "안녕", "token": "demo"}})
        self.assert_no_child_left()

    def test_child_receives_only_the_connector_fields_of_that_profile(self):
        """자식이 받는 env 는 그 profile 의 칸 값, 운영자 env, MCP SDK 의 기본 env 뿐이다."""
        with mock.patch.dict(os.environ, call_base.PARENT_ONLY):
            inherited = call_base.SDK_DEFAULT_ENV & set(os.environ)
            status, body = self.execute("mcp__demo__env_view")
        self.assertEqual(status, 200, body)
        self.assertTrue(body["ok"], body)
        # 비운 선택 칸(DEMO_SCOPE)은 빈 값으로 간다. profile 의 다른 값과 대시보드의 값은 가지 않는다.
        names = set(body["result"]["names"]) - call_base.INTERPRETER_ENV
        self.assertEqual(names, {"DEMO_TOKEN", "DEMO_SCOPE", "DEMO_BASE", "PATH"} | inherited)
        self.assertEqual(body["result"]["path"], os.path.dirname(sys.executable))

    def test_token_comes_from_the_requested_profile(self):
        """다른 profile 의 값으로 실행하지 않는다. 토큰이 거절되는 profile 은 그 오류를 받는다."""
        self.install("bob", token=call_base.BAD_TOKEN)
        self.assertEqual(self.execute("mcp__demo__list_scopes", profile="bob"),
                         (200, {"ok": False, "error": "credential_rejected"}))
        self.assertEqual(self.execute("mcp__demo__list_scopes"), (200, {"ok": True, "result": call_base.SCOPES}))

    def test_unknown_registered_name_is_refused(self):
        """서버에 없는 도구의 등록 이름은 400 이다."""
        status, body = self.execute("mcp__demo__nope")
        self.assertEqual(status, 400, body)
        # 다른 서버의 접두사를 가진 이름도 이 서버의 도구가 아니다.
        self.assertEqual(self.execute("mcp__other__write_note")[0], 400)

    def test_schema_two_runs_only_declared_tools(self):
        """`schema: 2` 는 `tools` 에 선언한 도구만 실행한다. 선언에 없는 도구는 400 이고 부르지 않는다."""
        from mcp import ClientSession

        self.declare_schema_two()
        called = []
        original = ClientSession.call_tool

        async def recording(session, name, arguments=None, **kwargs):
            called.append(name)
            return await original(session, name, arguments, **kwargs)

        with mock.patch.object(ClientSession, "call_tool", recording):
            status, body = self.execute("mcp__demo__env_view")
            self.assertEqual(status, 400, body)
            self.assertEqual(called, [], "선언에 없는 도구를 불렀다")
            # 선언한 도구는 그대로 실행한다. 위의 400 이 선언이 없어서였음을 확인한다.
            self.assertEqual(self.execute(args={"text": "a"})[0], 200)
            self.assertEqual(called, ["write_note"])

    def test_profile_and_connector_are_checked_before_the_child_starts(self):
        """설치하지 않았거나 관리 표식이 없는 profile, 모르는 커넥터, 틀린 본문은 4xx 이고 자식을 띄우지 않는다."""
        self.install("plain", installed=False)
        self.install("human", managed=False)
        valid = {"profile": PROFILE, "hermes_tool": WRITE_NOTE, "args": {}}
        with mock.patch.object(self.plugin, "_run_connector_execute") as runner:
            for label, path, body, expected in (
                ("not installed", EXECUTE, {**valid, "profile": "plain"}, 404),
                ("no managed marker", EXECUTE, {**valid, "profile": "human"}, 401),
                ("missing profile", EXECUTE, {**valid, "profile": "nobody"}, 404),
                ("default profile", EXECUTE, {**valid, "profile": "default"}, 400),
                ("profile type", EXECUTE, {**valid, "profile": ["alice"]}, 400),
                ("unknown connector", "/api/connectors/unknown/execute", valid, 404),
                ("bad connector id", "/api/connectors/..%2Fdemo/execute", valid, 404),
                ("args is a list", EXECUTE, {**valid, "args": ["안녕"]}, 400),
                ("extra key", EXECUTE, {**valid, "tool": "write_note"}, 400),
                ("no args", EXECUTE, {"profile": PROFILE, "hermes_tool": WRITE_NOTE}, 400),
                ("tool type", EXECUTE, {**valid, "hermes_tool": [WRITE_NOTE]}, 400),
                ("empty tool", EXECUTE, {**valid, "hermes_tool": ""}, 400),
                ("tool of 129 chars", EXECUTE, {**valid, "hermes_tool": "t" * 129}, 400),
                ("not an object", EXECUTE, [WRITE_NOTE], 400),
            ):
                with self.subTest(label):
                    self.assertEqual(self.request(path, "POST", body)[0], expected)
            runner.assert_not_called()

    def test_registered_name_of_the_longest_length_reaches_the_child(self):
        """등록 이름 128자는 본문 검사를 지난다. 서버에 그런 도구가 없어 400 이다."""
        real = self.plugin._run_connector_execute
        with mock.patch.object(self.plugin, "_run_connector_execute", wraps=real) as runner:
            self.assertEqual(self.execute("t" * 128)[0], 400)
        runner.assert_called_once()

    def test_service_token_is_required(self):
        """토큰이 없거나 틀리면 401 이고, `POST` 밖의 메서드는 열지 않는다."""
        with mock.patch.object(self.plugin, "_run_connector_execute") as runner:
            self.assertEqual(self.execute(token=None)[0], 401)
            self.assertEqual(self.execute(token="wrong")[0], 401)
            self.assertEqual(self.request(EXECUTE, "GET")[0], 401)
            runner.assert_not_called()

    def test_tool_error_is_mapped_to_the_common_vocabulary(self):
        """도구가 오류로 답하면 200 이고 manifest 의 대응 표로 바꾼 공통 어휘를 준다."""
        self.install("bob", token=call_base.BAD_TOKEN)
        self.install("carol", token=call_base.ODD_TOKEN)
        self.assertEqual(self.execute("mcp__demo__list_scopes", profile="bob"),
                         (200, {"ok": False, "error": "credential_rejected"}))
        self.assertEqual(self.execute("mcp__demo__list_scopes", profile="carol"), UNAVAILABLE)

    def test_outcome_unknown_code_answers_504(self):
        """도구가 `errors` 표에서 `outcome_unknown` 인 코드로 끝나면 `{ok: false}` 가 아니라 504 다."""
        self.install("lost", token=call_base.LOST_TOKEN)
        self.install("slow", token=call_base.SLOW_TOKEN)
        status, body = self.execute("mcp__demo__list_scopes", profile="lost")
        self.assertEqual(status, 504, body)
        # 시간 초과 때와 같은 상태와 모양이다. Control Plane 이 두 경우를 같은 길로 읽는다. 글만 까닭을 따로 말한다.
        with mock.patch.object(self.plugin, "CONNECTOR_EXECUTE_TIMEOUT_SECONDS", 3):
            slow_status, slow_body = self.execute("mcp__demo__list_scopes", profile="slow")
        self.assertEqual((slow_status, set(slow_body)), (status, set(body)))
        self.assertEqual(set(body), {"detail"})
        # 다른 코드는 그대로 200 과 공통 어휘다.
        self.install("bob", token=call_base.BAD_TOKEN)
        self.assertEqual(self.execute("mcp__demo__list_scopes", profile="bob"),
                         (200, {"ok": False, "error": "credential_rejected"}))
        self.assert_no_child_left()

    def test_plain_text_result_of_a_finished_write_is_a_success(self):
        """오류 없이 끝난 쓰기가 평문만 돌려주면 200 `ok: true` 이고 그 글을 `text` 로 준다."""
        self.assertEqual(self.execute("mcp__demo__append_line"),
                         (200, {"ok": True, "result": {"text": "appended one line"}}))
        # 텍스트 칸이 하나도 없으면 빈 글이다. 오류로 끝난 읽지 못한 결과는 그대로 unavailable 이다.
        for label, result, expected in (
            ("no content", call_base.tool_result(), (200, {"ok": True, "result": {"text": ""}})),
            ("error without code", call_base.tool_result(is_error=True, content=[call_base.text_content("plain")]),
             UNAVAILABLE),
        ):
            with self.subTest(label):
                async def fixed(manifest, hermes_tool, args, env, progress, result=result):
                    return result

                with mock.patch.object(self.plugin, "_run_connector_execute", fixed):
                    self.assertEqual(self.execute(), expected)

    def test_plain_text_write_tool_is_still_refused_by_call(self):
        """평문을 돌려주는 쓰기 도구는 manifest 가 확인 도구로 적어도 `call` 에서 400 이다."""
        self.rewrite("connector.json", lambda value: value.update(verify={"tool": "append_line"}))
        status, body = self.request(call_base.CALL, "POST",
                                    {"tool": "append_line", "values": {"token": call_base.OK_TOKEN}})
        self.assertEqual(status, 400, body)
        self.assertNotIn("appended", json.dumps(body))

    def test_slow_tool_answers_504_and_leaves_no_child(self):
        """시간 제한을 넘긴 실행은 결과를 모르므로 504 이고 자식 프로세스가 남지 않는다."""
        self.install("slow", token=call_base.SLOW_TOKEN)
        started = time.monotonic()
        with mock.patch.object(self.plugin, "CONNECTOR_EXECUTE_TIMEOUT_SECONDS", 3):
            status, body = self.execute("mcp__demo__list_scopes", profile="slow")
        self.assertEqual(status, 504, body)
        self.assertEqual(set(body), {"detail"})
        # 도구는 60초를 기다린다. 그보다 훨씬 먼저 돌아와야 제한이 실제로 끊은 것이다.
        self.assertLess(time.monotonic() - started, 20)
        self.assert_no_child_left()

    def test_failure_after_the_tool_was_called_answers_504(self):
        """도구 호출을 보낸 뒤의 예외는 실행됐는지 모르므로 504 이고 자식 프로세스가 남지 않는다."""
        from mcp import ClientSession

        def failing_call(session, name, arguments=None, **kwargs):
            raise RuntimeError("injected")

        with mock.patch.object(ClientSession, "call_tool", failing_call):
            status, body = self.execute()
        self.assertEqual(status, 504, body)
        self.assertEqual(set(body), {"detail"})
        self.assert_no_child_left()

    def test_failure_before_the_tool_was_called_is_unavailable(self):
        """도구 호출을 보내기 전의 예외는 실행되지 않은 것이므로 unavailable 이다."""
        from mcp import ClientSession

        def failing_list(session, *args, **kwargs):
            raise RuntimeError("injected")

        with mock.patch.object(ClientSession, "list_tools", failing_list):
            self.assertEqual(self.execute(), UNAVAILABLE)
        self.assert_no_child_left()
        # 소유 기록을 읽지 못해도 실행되지 않은 것이다.
        (self.profile_root / PROFILE / self.plugin.CONNECTOR_STATE).write_text("{not json", encoding="utf-8")
        with mock.patch.object(self.plugin, "_run_connector_execute") as runner:
            self.assertEqual(self.execute(), UNAVAILABLE)
            runner.assert_not_called()

    def test_sdk_outside_supported_range_is_unavailable_without_starting_child(self):
        """지원 범위 밖의 SDK 판이면 자식을 띄우지 않고 unavailable 이다."""
        with mock.patch.object(self.plugin, "_mcp_sdk_version", return_value="1.30.0"), \
                mock.patch.object(self.plugin, "_run_connector_execute") as runner:
            self.assertEqual(self.execute(), UNAVAILABLE)
        runner.assert_not_called()

    def test_limit_is_shared_with_call(self):
        """`call` 이 한도를 다 쓰고 있으면 실행은 기다리지 않고 unavailable 이고, 자리가 나면 받는다."""
        call_body = {"tool": "list_scopes", "values": {"token": call_base.OK_TOKEN}}
        execute_body = {"profile": PROFILE, "hermes_tool": WRITE_NOTE, "args": {"text": "a"}}

        async def scenario():
            release = asyncio.Event()
            started = 0

            async def held(manifest, tool, env):
                nonlocal started
                started += 1
                await release.wait()
                return call_base.tool_result(call_base.SCOPES)

            with mock.patch.object(self.plugin, "_run_connector_tool", held):
                running = [asyncio.ensure_future(self.send(call_base.CALL, "POST", call_body))
                           for _ in range(self.plugin.CONNECTOR_CALL_LIMIT)]
                while started < self.plugin.CONNECTOR_CALL_LIMIT:
                    await asyncio.sleep(0)
                refused = await asyncio.wait_for(self.send(EXECUTE, "POST", execute_body), 5)
                release.set()
                await asyncio.gather(*running)
            return refused, await self.send(EXECUTE, "POST", execute_body)

        refused, accepted = asyncio.run(scenario())
        self.assertEqual(refused, UNAVAILABLE)
        self.assertEqual(accepted[0], 200, accepted)
        self.assertTrue(accepted[1]["ok"], accepted)

    def test_arguments_and_results_are_not_logged(self):
        """인자와 결과와 profile 의 값은 성공과 실패의 어느 로그에도 없다."""
        from mcp import ClientSession

        secret_text = "private-note-body"

        def failing_call(session, name, arguments=None, **kwargs):
            raise RuntimeError(secret_text)

        with call_base.collected_logs() as records:
            self.assertEqual(self.execute(args={"text": secret_text})[0], 200)
            with mock.patch.object(ClientSession, "call_tool", failing_call):
                self.assertEqual(self.execute(args={"text": secret_text})[0], 504)
        log = "\n".join(records)
        self.assertIn("RuntimeError", log, "실패 로그를 모으지 못하면 이 검사는 아무것도 보지 않는다")
        for hidden in (secret_text, call_base.OK_TOKEN, "written"):
            self.assertNotIn(hidden, log)


if __name__ == "__main__":
    unittest.main()
