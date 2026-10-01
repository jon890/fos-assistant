"""dashboard-profile-api 가 후보 값으로 커넥터의 읽기 전용 도구를 대신 부르는 규칙을 검사한다(ADR-043).

시험 커넥터의 MCP 서버를 실제 자식 프로세스로 띄운다. 검사가 끝날 때마다 남은 자식이 없는지 본다.
"""

import asyncio
import json
import logging
import os
import subprocess
import sys
import time
import types
import unittest
from unittest import mock

import test_connector_manifest as base

CALL = "/api/connectors/%s/call" % base.DEMO
OK_TOKEN = "demo_ok_0123456789"
BAD_TOKEN = "demo_bad_0123456789"
ODD_TOKEN = "demo_odd_0123456789"
SLOW_TOKEN = "demo_slow_0123456789"
SCOPES = {"scopes": [{"id": "a", "name": "A"}]}
UNAVAILABLE = (200, {"ok": False, "error": "unavailable"})
INVALID = (200, {"ok": False, "error": "invalid_input"})
# 대시보드 프로세스에만 있어야 하는 값이다. 서비스 토큰과 다른 커넥터의 값 구실이다. 자식에게 넘어가면 안 된다.
PARENT_ONLY = {"HERMES_DASHBOARD_PROFILE_API_SECRET": "parent-secret", "OTHER_CONNECTOR_TOKEN": "other-secret",
               "DEMO_SCOPE": "from-parent"}
# MCP SDK 가 자식에게 늘 더하는 기본 env 다. 대시보드 프로세스에 있는 것만 간다.
SDK_DEFAULT_ENV = {"HOME", "LOGNAME", "PATH", "SHELL", "TERM", "USER"}
# 자식의 Python 이 뜨면서 스스로 더하는 이름이다. 대시보드가 넘긴 것이 아니다.
INTERPRETER_ENV = {"LC_CTYPE", "__CF_USER_TEXT_ENCODING"}


class ConnectorCallTest(base.ConnectorGateCase):
    def setUp(self):
        super().setUp()
        self.addCleanup(self.assert_no_child_left)

    def children(self):
        """시험 커넥터 사본의 서버를 돌리고 있는 프로세스 번호다."""
        found = subprocess.run(["pgrep", "-f", str(self.connector_root / "server.py")],
                               capture_output=True, text=True)
        return found.stdout.split()

    def assert_no_child_left(self):
        self.assertEqual(self.children(), [], "도구 호출이 끝났는데 자식 프로세스가 남았다")

    def call(self, token=OK_TOKEN, tool="list_scopes", **values):
        return self.request(CALL, "POST", {"tool": tool, "values": {"token": token, **values}})

    def test_read_only_tool_returns_its_result(self):
        """선택지 도구를 맞는 토큰으로 부르면 도구 결과가 그대로 온다."""
        self.assertEqual(self.call(), (200, {"ok": True, "result": SCOPES}))
        # 비운 선택 칸은 형식을 보지 않고 그대로 넘긴다.
        self.assertEqual(self.call(scope=""), (200, {"ok": True, "result": SCOPES}))

    def test_tool_error_code_is_mapped_to_the_common_vocabulary(self):
        """도구의 오류 코드는 manifest 의 대응 표로 바꾸고, 표에 없는 코드는 unavailable 이다."""
        self.assertEqual(self.call(BAD_TOKEN), (200, {"ok": False, "error": "credential_rejected"}))
        self.assertEqual(self.call(ODD_TOKEN), UNAVAILABLE)

    def test_only_declared_tools_and_known_connectors_are_called(self):
        """manifest 가 선택지나 확인에 쓰지 않는 도구와 모르는 커넥터는 4xx 이고 자식을 띄우지 않는다."""
        with mock.patch.object(self.plugin, "_run_connector_tool") as runner:
            for label, path, body, expected in (
                ("write tool", CALL, {"tool": "write_note", "values": {"token": OK_TOKEN}}, 400),
                ("undeclared read tool", CALL, {"tool": "env_view", "values": {"token": OK_TOKEN}}, 400),
                ("tool type", CALL, {"tool": ["list_scopes"], "values": {}}, 400),
                ("no values", CALL, {"tool": "list_scopes"}, 400),
                ("values type", CALL, {"tool": "list_scopes", "values": "token"}, 400),
                ("extra key", CALL, {"tool": "list_scopes", "values": {}, "profile": "alice"}, 400),
                ("not an object", CALL, ["list_scopes"], 400),
                ("unknown connector", "/api/connectors/unknown/call", {"tool": "list_scopes", "values": {}}, 404),
                ("bad connector id", "/api/connectors/..%2Fdemo/call", {"tool": "list_scopes", "values": {}}, 404),
            ):
                with self.subTest(label):
                    self.assertEqual(self.request(path, "POST", body)[0], expected)
            runner.assert_not_called()
        self.assertEqual(self.request(CALL, "POST", {"tool": "list_scopes", "values": {}}, token=None)[0], 401)
        self.assertEqual(self.request(CALL, "GET")[0], 401)

    def test_tool_without_read_only_hint_is_refused(self):
        """manifest 가 확인 도구로 적었더라도 `tools/list` 에서 읽기 전용이 아니면 부르지 않고 400 이다."""
        self.rewrite("connector.json", lambda value: value.update(verify={"tool": "write_note"}))
        status, body = self.call(tool="write_note")
        self.assertEqual(status, 400)
        self.assertNotIn("written", json.dumps(body))
        # 서버에 없는 도구도 읽기 전용임을 확인하지 못한다.
        self.rewrite("connector.json", lambda value: value.update(verify={"tool": "missing_tool"}))
        self.assertEqual(self.call(tool="missing_tool")[0], 400)

    def test_values_are_checked_before_the_child_starts(self):
        """모르는 칸, 문자열이 아닌 값, 형식에 맞지 않는 값은 invalid_input 이고 자식을 띄우지 않는다."""
        with mock.patch.object(self.plugin, "_run_connector_tool") as runner:
            for label, values in (
                ("unknown key", {"token": OK_TOKEN, "other": "x"}),
                ("env name instead of key", {"DEMO_TOKEN": OK_TOKEN}),
                ("pattern", {"token": "demo_ok_012345678"}),
                ("pattern prefix", {"token": OK_TOKEN + "0"}),
                ("empty required", {"token": ""}),
                ("not a string", {"token": 1}),
                ("newline", {"token": OK_TOKEN, "scope": "a\nb"}),
            ):
                with self.subTest(label):
                    self.assertEqual(self.request(CALL, "POST", {"tool": "list_scopes", "values": values}), INVALID)
            runner.assert_not_called()

    def test_child_receives_only_declared_env(self):
        """자식이 받는 env 는 정확히 칸 값, 운영자 env, MCP SDK 의 기본 env 다. 대시보드의 다른 env 는 가지 않는다."""
        self.rewrite("connector.json", lambda value: value.update(verify={"tool": "env_view"}))
        with mock.patch.dict(os.environ, PARENT_ONLY):
            inherited = SDK_DEFAULT_ENV & set(os.environ)
            status, body = self.call(tool="env_view")
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"], body)
        # 넘기지 않은 칸(DEMO_SCOPE)은 대시보드 프로세스에 같은 이름이 있어도 자식에게 가지 않는다.
        names = set(body["result"]["names"]) - INTERPRETER_ENV
        self.assertEqual(names, {"DEMO_TOKEN", "DEMO_BASE", "PATH"} | inherited)
        # PATH 는 대시보드의 것이 아니라 실행 파일이 있는 디렉터리만이다.
        self.assertEqual(body["result"]["path"], os.path.dirname(sys.executable))

    def test_fifth_concurrent_call_is_refused_without_waiting(self):
        """이미 4개가 돌고 있으면 다섯 번째는 기다리지 않고 unavailable 이고, 자리가 나면 다시 받는다."""
        body = {"tool": "list_scopes", "values": {"token": OK_TOKEN}}

        async def scenario():
            release = asyncio.Event()
            started = 0

            async def held(manifest, tool, env):
                nonlocal started
                started += 1
                await release.wait()
                return types.SimpleNamespace(structured_content=SCOPES, is_error=False)

            with mock.patch.object(self.plugin, "_run_connector_tool", held):
                running = [asyncio.ensure_future(self.send(CALL, "POST", body)) for _ in range(4)]
                while started < 4:
                    await asyncio.sleep(0)
                fifth = await asyncio.wait_for(self.send(CALL, "POST", body), 1)
                still_running = [task.done() for task in running]
                release.set()
                finished = await asyncio.gather(*running)
                sixth = await self.send(CALL, "POST", body)
            return fifth, still_running, finished, sixth, started

        fifth, still_running, finished, sixth, started = asyncio.run(scenario())
        self.assertEqual(fifth, UNAVAILABLE)
        self.assertEqual(still_running, [False] * 4)
        self.assertEqual(finished, [(200, {"ok": True, "result": SCOPES})] * 4)
        self.assertEqual(sixth, (200, {"ok": True, "result": SCOPES}))
        # 거절한 다섯 번째는 도구를 부르지 않았다.
        self.assertEqual(started, 5)

    def test_slot_is_released_after_failures(self):
        """실패로 끝난 호출도 자리를 돌려준다. 다섯 번 넘게 실패한 뒤에도 다음 호출을 받는다."""
        async def broken(manifest, tool, env):
            raise RuntimeError("injected")

        with mock.patch.object(self.plugin, "_run_connector_tool", broken):
            for _ in range(5):
                self.assertEqual(self.call(), UNAVAILABLE)
        self.assertEqual(self.call(), (200, {"ok": True, "result": SCOPES}))

    def test_slow_tool_times_out_and_leaves_no_child(self):
        """시간 제한을 넘긴 도구는 unavailable 이고 자식 프로세스가 남지 않는다."""
        started = time.monotonic()
        with mock.patch.object(self.plugin, "CONNECTOR_CALL_TIMEOUT_SECONDS", 3):
            self.assertEqual(self.call(SLOW_TOKEN), UNAVAILABLE)
        # 도구는 60초를 기다린다. 그보다 훨씬 먼저 돌아와야 제한이 실제로 끊은 것이다.
        self.assertLess(time.monotonic() - started, 20)
        self.assert_no_child_left()

    def test_failure_after_the_child_started_leaves_no_child(self):
        """자식을 띄운 뒤 호출이 예외로 끝나도 unavailable 이고 자식 프로세스가 남지 않는다."""
        from mcp import ClientSession

        seen = []

        def failing_call(session, name, arguments=None, **kwargs):
            # 이 시점에는 초기화와 `tools/list` 가 끝나 자식이 떠 있다.
            seen.extend(self.children())
            raise RuntimeError("injected")

        with mock.patch.object(ClientSession, "call_tool", failing_call):
            self.assertEqual(self.call(), UNAVAILABLE)
        self.assertEqual(len(seen), 1, "자식이 뜬 뒤에 실패해야 이 검사가 뜻이 있다")
        self.assert_no_child_left()

    def test_missing_sdk_makes_only_the_call_unavailable(self):
        """`mcp` SDK 를 읽어 오지 못하면 도구 호출만 unavailable 이고 카탈로그는 그대로 나온다."""
        with mock.patch.dict(sys.modules, {"mcp": None}):
            self.assertEqual(self.call(), UNAVAILABLE)
            self.assertEqual([entry["id"] for entry in self.catalog()], [base.DEMO])

    def test_unreadable_tool_results_are_unavailable(self):
        """구조화 결과도 JSON 텍스트도 없는 결과는 unavailable 이다. 구조화 결과가 있으면 그것을 먼저 쓴다."""
        text = types.SimpleNamespace(type="text", text=json.dumps({"from": "text"}))
        for label, result, expected in (
            ("structured first", types.SimpleNamespace(structured_content={"from": "structured"}, content=[text],
                                                       is_error=False),
             (200, {"ok": True, "result": {"from": "structured"}})),
            ("first text", types.SimpleNamespace(structured_content=None, is_error=False, content=[
                types.SimpleNamespace(type="image"), text]), (200, {"ok": True, "result": {"from": "text"}})),
            ("not json", types.SimpleNamespace(structured_content=None, is_error=False, content=[
                types.SimpleNamespace(type="text", text="plain")]), UNAVAILABLE),
            ("no content", types.SimpleNamespace(structured_content=None, is_error=False, content=[]), UNAVAILABLE),
            ("error without code", types.SimpleNamespace(structured_content=None, is_error=True, content=[
                types.SimpleNamespace(type="text", text="[]")]), UNAVAILABLE),
            ("input required", types.SimpleNamespace(), UNAVAILABLE),
        ):
            with self.subTest(label):
                async def fixed(manifest, tool, env, result=result):
                    return result

                with mock.patch.object(self.plugin, "_run_connector_tool", fixed):
                    self.assertEqual(self.call(), expected)

    def test_candidate_secret_is_not_in_responses_logs_or_files(self):
        """후보 토큰 원문은 응답 본문과 로그에 없고, 호출이 디스크에 아무것도 쓰지 않는다."""
        records = []

        def files():
            return {path: path.read_bytes() for path in self.base.rglob("*")
                    if path.is_file() and "__pycache__" not in path.parts}

        before = files()

        class Collect(logging.Handler):
            def emit(self, record):
                records.append(self.format(record))

        handler = Collect(level=logging.DEBUG)
        root = logging.getLogger()
        previous = root.level
        logging.disable(logging.NOTSET)
        root.addHandler(handler)
        root.setLevel(logging.DEBUG)
        try:
            responses = [self.call(), self.call(BAD_TOKEN), self.call(ODD_TOKEN), self.call(OK_TOKEN, scope="zzz")]
            with mock.patch.object(self.plugin, "CONNECTOR_CALL_TIMEOUT_SECONDS", 2):
                responses.append(self.call(SLOW_TOKEN))
            responses.append(self.request(CALL, "POST", {"tool": "list_scopes",
                                                         "values": {"token": OK_TOKEN, "other": "x"}}))
        finally:
            root.setLevel(previous)
            root.removeHandler(handler)
            logging.disable(logging.CRITICAL)
        self.assertTrue(records, "로그를 하나도 모으지 못하면 이 검사는 아무것도 보지 않는다")
        for token in (OK_TOKEN, BAD_TOKEN, ODD_TOKEN, SLOW_TOKEN):
            self.assertNotIn(token, json.dumps(responses))
            self.assertNotIn(token, "\n".join(records))
        # 시험 커넥터의 서버 파일에는 토큰이 적혀 있다. 원문을 찾지 않고 파일이 그대로인지를 본다.
        after = files()
        self.assertEqual(sorted(after), sorted(before), "도구 호출이 파일을 만들거나 지웠다")
        self.assertEqual(after, before, "도구 호출이 파일을 바꿨다")


if __name__ == "__main__":
    unittest.main()
