"""dashboard-profile-api 가 커넥터 경로와 실행 파일을 환경 변수에서 읽는 규칙을 검사한다."""

import importlib.util
import json
import logging
import os
import pathlib
import sys
import types
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
PLUGIN = ROOT / "plugins/dashboard-profile-api/__init__.py"
ROOTS_ENV = "FOS_ASSISTANT_CONNECTOR_ROOTS"
COMMAND_ENV = "FOS_ASSISTANT_CONNECTOR_COMMAND"


class ConnectorEnvironmentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # plugin 은 모듈 머리에서 Hermes 모듈을 import 한다. 가짜를 끼운 뒤 불러오고 끝나면 되돌린다.
        auth = types.ModuleType("hermes_cli.dashboard_auth")
        for name in ("DashboardAuthProvider", "LoginStart", "Session", "TokenPrincipal"):
            setattr(auth, name, object)
        hermes = types.ModuleType("hermes_cli")
        hermes.dashboard_auth = auth
        modules = {
            "hermes_cli": hermes,
            "hermes_cli.dashboard_auth": auth,
            "starlette.responses": types.ModuleType("starlette.responses"),
        }
        previous = {name: sys.modules.get(name) for name in modules}

        def restore():
            for name, module in previous.items():
                if module is None:
                    sys.modules.pop(name, None)
                else:
                    sys.modules[name] = module

        cls.addClassCleanup(restore)
        sys.modules.update(modules)
        # 틀린 값은 경고 로그를 남긴다. 검사 출력에는 결과만 둔다.
        logging.disable(logging.CRITICAL)
        cls.addClassCleanup(logging.disable, logging.NOTSET)
        spec = importlib.util.spec_from_file_location("connector_environment_test", PLUGIN)
        cls.plugin = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.plugin)

    def environment(self, **values):
        """두 환경 변수를 지운 뒤 준 값만 넣는다. 검사가 끝나면 되돌린다."""
        patch = mock.patch.dict(os.environ, values)
        patch.start()
        self.addCleanup(patch.stop)
        for name in (ROOTS_ENV, COMMAND_ENV):
            if name not in values:
                os.environ.pop(name, None)

    def test_roots_reads_absolute_paths(self):
        """커넥터 이름마다 준 절대 경로를 돌려준다."""
        self.environment(**{ROOTS_ENV: json.dumps({"fos-accountbook": "/abs/path"})})
        self.assertEqual(self.plugin._connector_roots(), {"fos-accountbook": pathlib.Path("/abs/path")})

    def test_roots_is_empty_when_missing_or_malformed(self):
        """없거나 읽지 못하는 값은 커넥터가 없는 것으로 본다. 예외를 던지지 않는다."""
        self.environment()
        self.assertEqual(self.plugin._connector_roots(), {})
        for raw in ("", "   ", "not json", "[]", "null", '"text"', '{"x": 1}', '{"x": null}'):
            with self.subTest(raw=raw):
                self.environment(**{ROOTS_ENV: raw})
                self.assertEqual(self.plugin._connector_roots(), {})

    def test_roots_drops_only_relative_entries(self):
        """상대 경로 항목은 버리고 절대 경로 항목은 남긴다."""
        self.environment(**{ROOTS_ENV: json.dumps({"x": "relative"})})
        self.assertEqual(self.plugin._connector_roots(), {})
        self.environment(**{ROOTS_ENV: json.dumps({"x": "relative", "y": "/abs/y"})})
        self.assertEqual(self.plugin._connector_roots(), {"y": pathlib.Path("/abs/y")})

    def test_command_reads_absolute_path(self):
        """절대 경로를 주면 그 값을 그대로 돌려준다."""
        self.environment(**{COMMAND_ENV: "/abs/bin/runner"})
        self.assertEqual(self.plugin._connector_command(), "/abs/bin/runner")

    def test_command_is_none_when_missing_empty_or_relative(self):
        """없거나 비었거나 상대 경로면 None 이다. 예외를 던지지 않는다."""
        self.environment()
        self.assertIsNone(self.plugin._connector_command())
        for raw in ("", "   ", "runner", "bin/runner", "./runner"):
            with self.subTest(raw=raw):
                self.environment(**{COMMAND_ENV: raw})
                self.assertIsNone(self.plugin._connector_command())

    def test_owned_record_is_rejected_without_command(self):
        """실행 파일을 받지 못하면 소유 기록 검증이 원본의 ValueError 갈래로 간다."""
        record = {"fos-accountbook": {"allowlist_added": True, "server": {
            "command": "/abs/bin/runner", "args": ["/abs/path/dist/accountbook-mcp.js"], "enabled": True,
            "env": {"ACCOUNTBOOK_API_BASE_URL": "${ACCOUNTBOOK_API_BASE_URL}",
                    "ACCOUNTBOOK_API_TOKEN": "${ACCOUNTBOOK_API_TOKEN}"}}}}
        roots = json.dumps({"fos-accountbook": "/abs/path"})
        self.environment(**{ROOTS_ENV: roots, COMMAND_ENV: "/abs/bin/runner"})
        self.assertEqual(self.plugin._connector_state(record), record)
        self.environment(**{ROOTS_ENV: roots})
        with self.assertRaises(ValueError):
            self.plugin._connector_state(record)
        self.environment(**{ROOTS_ENV: roots, COMMAND_ENV: "/abs/bin/other"})
        with self.assertRaises(ValueError):
            self.plugin._connector_state(record)


if __name__ == "__main__":
    unittest.main()
