"""dashboard-profile-api 가 `connector.json` 을 읽어 카탈로그로 내는 규칙을 검사한다(ADR-043)."""

import asyncio
import importlib.util
import json
import logging
import os
import pathlib
import shutil
import sys
import tempfile
import types
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
PLUGIN = ROOT / "plugins/dashboard-profile-api/__init__.py"
DEMO_CONNECTOR = ROOT / "tests/fixtures/demo-connector"
ROOTS_ENV = "FOS_ASSISTANT_CONNECTOR_ROOTS"
COMMAND_ENV = "FOS_ASSISTANT_CONNECTOR_COMMAND"
DEMO = "demo-notes"
# 운영자가 운영 목록으로 주는 env 값이다. 어느 응답에도 나오면 안 된다.
DEMO_BASE = "http://demo.test/base"
CATALOG = "/api/connectors/catalog"


class ConnectorGateCase(unittest.TestCase):
    """가짜 Hermes 모듈로 plugin 을 불러와 토큰 미들웨어를 거쳐 커넥터 경로를 부른다.

    검사 메서드는 없다. 카탈로그 검사와 도구 호출 검사가 이 준비를 함께 쓴다.
    응답은 실제 starlette 의 것이다. `mcp` SDK 가 starlette 를 함께 설치한다.
    """

    @classmethod
    def setUpClass(cls):
        auth = types.ModuleType("hermes_cli.dashboard_auth")
        for name in ("DashboardAuthProvider", "LoginStart", "Session", "TokenPrincipal"):
            setattr(auth, name, object)
        seam = types.ModuleType("hermes_cli.dashboard_auth.token_auth")
        auth.token_auth = seam
        hermes = types.ModuleType("hermes_cli")
        hermes.dashboard_auth = auth
        modules = {
            "hermes_cli": hermes,
            "hermes_cli.dashboard_auth": auth,
            "hermes_cli.dashboard_auth.token_auth": seam,
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
        # 틀린 manifest 는 경고 로그를 남긴다. 검사 출력에는 결과만 둔다.
        logging.disable(logging.CRITICAL)
        cls.addClassCleanup(logging.disable, logging.NOTSET)

        async def original(request, call_next):
            return await call_next(request)

        seam.token_auth_middleware = original
        seam.authenticate_token = lambda request: (
            (types.SimpleNamespace(provider="fos-profile-api"), None) if request.token == "valid" else (None, None)
        )
        spec = importlib.util.spec_from_file_location("connector_gate_test", PLUGIN)
        cls.plugin = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.plugin)
        assert cls.plugin._install_gate()
        cls.gate = staticmethod(seam.token_auth_middleware)

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        # macOS 의 임시 디렉터리는 심볼릭 링크 아래에 있다. plugin 이 링크를 거절하므로 푼 경로를 쓴다.
        self.base = pathlib.Path(tmp.name).resolve()
        self.connector_root = self.base / "demo-connector"
        shutil.copytree(DEMO_CONNECTOR, self.connector_root)
        self.operator_list({DEMO: {"root": str(self.connector_root), "command": sys.executable,
                                   "env": {"DEMO_BASE": DEMO_BASE}}})

    def operator_list(self, roots, command=None):
        """운영 목록과 기본 실행 파일을 바꿔 끼운다. 검사가 끝나면 되돌린다."""
        patch = mock.patch.dict(os.environ, {ROOTS_ENV: json.dumps(roots)})
        patch.start()
        self.addCleanup(patch.stop)
        if command is None:
            os.environ.pop(COMMAND_ENV, None)
        else:
            os.environ[COMMAND_ENV] = command

    def rewrite(self, name, change):
        """시험 커넥터 사본의 JSON 파일 하나를 고쳐 쓴다."""
        path = self.connector_root / name
        value = json.loads(path.read_text(encoding="utf-8"))
        change(value)
        path.write_text(json.dumps(value), encoding="utf-8")

    async def send(self, path, method, body=None, token="valid"):
        """미들웨어를 한 번 거친다. 답은 `(상태 코드, JSON 본문)` 이다."""
        request = types.SimpleNamespace(
            url=types.SimpleNamespace(path=path), method=method, token=token,
            state=types.SimpleNamespace(), query_params=None,
        )

        async def read_json():
            return body

        request.json = read_json

        async def call_next(current):
            # 우리가 열지 않은 요청이 닿는 자리다. 쿠키가 없으므로 대시보드는 401 로 답한다.
            return types.SimpleNamespace(status_code=401, body=b"null")

        response = await self.gate(request, call_next)
        return response.status_code, json.loads(response.body)

    def request(self, path, method, body=None, token="valid"):
        return asyncio.run(self.send(path, method, body, token))

    def catalog(self):
        status, body = self.request(CATALOG, "GET")
        self.assertEqual(status, 200)
        return body


class ConnectorCatalogTest(ConnectorGateCase):
    def test_catalog_lists_the_validated_connector_without_operator_values(self):
        """시험 커넥터가 카탈로그에 나오고, 칸의 env 와 확인 도구가 있고, 운영자 env 의 이름과 값이 없다."""
        body = self.catalog()
        self.assertEqual(len(body), 1)
        entry = body[0]
        self.assertEqual(set(entry), {"id", "title", "description", "fields", "verify", "mcp_server",
                                      "toolsets", "attachments"})
        # 두 칸이 없는 manifest 는 내장 도구를 열지 않고 사진을 받지 않는다.
        self.assertEqual(entry["toolsets"], [])
        self.assertIs(entry["attachments"], False)
        self.assertEqual(entry["id"], DEMO)
        self.assertEqual(entry["title"], "검사용 메모")
        self.assertEqual(entry["mcp_server"], "demo")
        self.assertEqual(entry["verify"], {"tool": "list_scopes"})
        declared = json.loads((DEMO_CONNECTOR / "connector.json").read_text(encoding="utf-8"))
        # 칸은 manifest 그대로다. Control Plane 이 env 로 쓸 key 를 정하고 options 로 선택지를 채운다.
        self.assertEqual(entry["fields"], declared["fields"])
        self.assertEqual([field["env"] for field in entry["fields"]], ["DEMO_TOKEN", "DEMO_SCOPE"])
        text = json.dumps(body)
        for hidden in (DEMO_BASE, "DEMO_BASE", "DEMO_UNAUTHORIZED", "credential_rejected", sys.executable,
                       str(self.connector_root)):
            self.assertNotIn(hidden, text)

    def test_catalog_needs_the_service_token(self):
        """카탈로그는 Control Plane 토큰으로만 읽는다. 다른 메서드는 열지 않는다."""
        self.assertEqual(self.request(CATALOG, "GET", token=None)[0], 401)
        self.assertEqual(self.request(CATALOG, "GET", token="wrong")[0], 401)
        self.assertEqual(self.request(CATALOG, "POST")[0], 401)

    def test_catalog_is_empty_without_operator_list(self):
        """운영 목록이 없으면 커넥터가 하나도 없다."""
        self.operator_list({})
        self.assertEqual(self.catalog(), [])
        self.assertIsNone(self.plugin._connector_manifest(DEMO))

    def test_invalid_manifest_is_left_out_without_raising(self):
        """manifest 검증이 하나라도 실패하면 그 커넥터는 카탈로그에서 빠지고 예외가 나지 않는다."""
        def field(index):
            return lambda value: value["fields"][index]

        cases = (
            ("schema 2", "connector.json", lambda value: value.update(schema=2)),
            ("schema true", "connector.json", lambda value: value.update(schema=True)),
            ("id differs from the list", "connector.json", lambda value: value.update(id="other")),
            ("no title", "connector.json", lambda value: value.pop("title")),
            ("no fields", "connector.json", lambda value: value.update(fields=[])),
            ("field env differs from .mcp.json", "connector.json",
             lambda value: field(0)(value).update(env="DEMO_KEY")),
            ("field key repeated", "connector.json", lambda value: field(1)(value).update(key="token")),
            ("field key format", "connector.json", lambda value: field(0)(value).update(key="Token")),
            ("field env is a base key", "connector.json",
             lambda value: field(0)(value).update(env="MCP_FOS_ASSISTANT_API_KEY")),
            ("pattern is not a regex", "connector.json", lambda value: field(0)(value).update(pattern="(")),
            ("options.tool is not a string", "connector.json",
             lambda value: field(1)(value)["options"].update(tool=1)),
            ("verify.tool is not a string", "connector.json", lambda value: value.update(verify={"tool": 1})),
            ("errors value outside the vocabulary", "connector.json",
             lambda value: value["errors"].update(DEMO_FORBIDDEN="denied")),
            ("toolsets opens the shell", "connector.json", lambda value: value.update(toolsets=["terminal"])),
            ("toolsets mixes an allowed and a closed name", "connector.json",
             lambda value: value.update(toolsets=["vision", "file"])),
            ("toolsets has an unknown name", "connector.json", lambda value: value.update(toolsets=["sight"])),
            ("toolsets repeats a name", "connector.json", lambda value: value.update(toolsets=["vision", "vision"])),
            ("toolsets is not a list", "connector.json", lambda value: value.update(toolsets="vision")),
            ("toolsets item is not a string", "connector.json", lambda value: value.update(toolsets=[["vision"]])),
            ("attachments without vision", "connector.json", lambda value: value.update(attachments=True)),
            ("attachments is not a boolean", "connector.json",
             lambda value: value.update(toolsets=["vision"], attachments="true")),
            ("operator_env missing from .mcp.json", "connector.json", lambda value: value.update(operator_env=[])),
            ("operator_env repeats a field env", "connector.json",
             lambda value: value.update(operator_env=["DEMO_BASE", "DEMO_TOKEN"])),
            ("server env is not declared", ".mcp.json",
             lambda value: value["mcpServers"]["demo"]["env"].update(DEMO_EXTRA="${DEMO_EXTRA}")),
            ("required field uses an empty default", ".mcp.json",
             lambda value: value["mcpServers"]["demo"]["env"].update(DEMO_TOKEN="${DEMO_TOKEN:-}")),
            ("operator env uses an empty default", ".mcp.json",
             lambda value: value["mcpServers"]["demo"]["env"].update(DEMO_BASE="${DEMO_BASE:-}")),
            ("two servers", ".mcp.json",
             lambda value: value["mcpServers"].update(other=value["mcpServers"]["demo"])),
            ("server named after the Control Plane MCP", ".mcp.json",
             lambda value: value.update(mcpServers={"fos-assistant": value["mcpServers"]["demo"]})),
            ("plugin name differs from the list", ".claude-plugin/plugin.json",
             lambda value: value.update(name="other")),
            ("skills outside the plugin", ".claude-plugin/plugin.json", lambda value: value.update(skills="..")),
        )
        originals = {name: (self.connector_root / name).read_bytes()
                     for name in ("connector.json", ".mcp.json", ".claude-plugin/plugin.json")}
        for label, name, change in cases:
            with self.subTest(label):
                self.rewrite(name, change)
                self.assertEqual(self.catalog(), [])
                self.assertIsNone(self.plugin._connector_manifest(DEMO))
                (self.connector_root / name).write_bytes(originals[name])
        # 되돌리면 다시 나온다. 위의 빈 목록이 고친 내용 때문이었음을 확인한다.
        self.assertEqual([entry["id"] for entry in self.catalog()], [DEMO])

    def test_declared_toolsets_and_attachments_reach_the_catalog(self):
        """허용한 내장 toolset 과 사진 받기를 선언하면 카탈로그가 그대로 낸다."""
        self.rewrite("connector.json", lambda value: value.update(toolsets=["vision"], attachments=True))
        entry = self.catalog()[0]
        self.assertEqual(entry["toolsets"], ["vision"])
        self.assertIs(entry["attachments"], True)
        # 이미지 도구만 열고 사진은 받지 않는 선언도 된다.
        self.rewrite("connector.json", lambda value: value.update(attachments=False))
        entry = self.catalog()[0]
        self.assertEqual(entry["toolsets"], ["vision"])
        self.assertIs(entry["attachments"], False)

    def test_closed_toolsets_are_never_allowed(self):
        """셸, 파일, 기억, 스킬, 위임 도구는 manifest 로 열리지 않는다."""
        for name in ("terminal", "file", "memory", "skills", "delegation", "code_execution", "browser"):
            with self.subTest(name):
                self.assertNotIn(name, self.plugin.CONNECTOR_TOOLSETS)
                self.rewrite("connector.json", lambda value: value.update(toolsets=[name]))
                self.assertEqual(self.catalog(), [])

    def test_skill_body_becomes_the_persona_without_frontmatter(self):
        """스킬의 SKILL.md 만 읽어 앞머리를 떼고 지침으로 삼는다. 카탈로그에는 싣지 않는다."""
        (self.connector_root / "skills/demo/NOTES.md").write_text("읽지 않는 파일", encoding="utf-8")
        (self.connector_root / "skills/README.md").write_text("읽지 않는 파일", encoding="utf-8")
        persona = self.plugin._connector_manifest(DEMO)["persona"]
        self.assertTrue(persona.startswith("# 검사용 메모"))
        self.assertNotIn("name: demo", persona)
        self.assertNotIn("읽지 않는 파일", persona)
        self.assertNotIn("persona", self.catalog()[0])
        self.assertNotIn("검사용 메모\n\n`list_scopes`", json.dumps(self.catalog(), ensure_ascii=False))

    def test_connector_without_skill_body_has_no_persona(self):
        """스킬이 없는 커넥터는 지침 없이 카탈로그에 나온다."""
        shutil.rmtree(self.connector_root / "skills/demo")
        self.assertIsNone(self.plugin._connector_manifest(DEMO)["persona"])
        self.assertEqual([entry["id"] for entry in self.catalog()], [DEMO])

    def test_oversized_or_linked_skill_body_leaves_the_connector_out(self):
        """본문이 상한을 넘거나, 앞머리가 닫히지 않았거나, SKILL.md 나 스킬 디렉터리가 링크이면 카탈로그에서 빠진다."""
        skill = self.connector_root / "skills/demo/SKILL.md"
        original = skill.read_text(encoding="utf-8")
        skill.write_text(original + "가" * self.plugin.CONNECTOR_PERSONA_MAX_CHARS, encoding="utf-8")
        self.assertEqual(self.catalog(), [])
        skill.write_text("---\nname: demo\n본문", encoding="utf-8")
        self.assertEqual(self.catalog(), [])
        # plugin 밖의 파일을 가리키는 링크는 읽지 않는다. 그 내용이 지침에 들어가면 안 된다.
        outside = self.base / "outside.md"
        outside.write_text("밖의 비밀", encoding="utf-8")
        skill.unlink()
        skill.symlink_to(outside)
        self.assertEqual(self.catalog(), [])
        skill.unlink()
        skill.write_text(original, encoding="utf-8")
        linked = self.base / "linked-skill"
        linked.mkdir()
        (linked / "SKILL.md").write_text("밖의 비밀", encoding="utf-8")
        (self.connector_root / "skills/outside").symlink_to(linked, target_is_directory=True)
        self.assertEqual(self.catalog(), [])
        (self.connector_root / "skills/outside").unlink()
        self.assertEqual([entry["id"] for entry in self.catalog()], [DEMO])

    def test_missing_or_unreadable_files_leave_the_connector_out(self):
        """`connector.json` 이 없거나, JSON 이 아니거나, 실행할 파일이 링크면 카탈로그에서 빠진다."""
        manifest = self.connector_root / "connector.json"
        original = manifest.read_bytes()
        manifest.unlink()
        self.assertEqual(self.catalog(), [])
        manifest.write_text("{not json", encoding="utf-8")
        self.assertEqual(self.catalog(), [])
        manifest.write_bytes(original)
        self.assertEqual(len(self.catalog()), 1)
        script = self.connector_root / "server.py"
        script.unlink()
        script.symlink_to(manifest)
        self.assertEqual(self.catalog(), [])

    def test_operator_env_value_must_come_from_the_operator_list(self):
        """`operator_env` 의 값이 운영 목록 항목에 없으면 카탈로그에서 빠진다."""
        self.operator_list({DEMO: {"root": str(self.connector_root), "command": sys.executable}})
        self.assertEqual(self.catalog(), [])
        self.operator_list({DEMO: {"root": str(self.connector_root), "command": sys.executable,
                                   "env": {"DEMO_OTHER": "x"}}})
        self.assertEqual(self.catalog(), [])

    def test_command_must_be_executable(self):
        """실행 파일을 받지 못했거나 실행 권한이 없으면 그 커넥터는 쓸 수 없다."""
        entry = {"root": str(self.connector_root), "env": {"DEMO_BASE": DEMO_BASE}}
        self.operator_list({DEMO: entry})
        self.assertEqual(self.catalog(), [])
        plain = self.base / "not-executable"
        plain.write_text("#!/bin/sh\n", encoding="utf-8")
        plain.chmod(0o644)
        self.operator_list({DEMO: {**entry, "command": str(plain)}})
        self.assertEqual(self.catalog(), [])
        # 항목에 실행 파일이 없으면 기본 실행 파일을 쓴다.
        self.operator_list({DEMO: entry}, command=sys.executable)
        self.assertEqual([item["id"] for item in self.catalog()], [DEMO])
        self.assertEqual(self.plugin._connector_manifest(DEMO)["server"]["command"], sys.executable)

    def test_operator_list_accepts_string_and_object_entries(self):
        """운영 목록의 문자열 모양과 object 모양을 둘 다 읽어 카탈로그로 낸다."""
        # 문자열 모양은 운영자 env 를 줄 자리가 없다. 운영자 env 가 없는 커넥터를 하나 더 만든다.
        plain_root = self.base / "plain-connector"
        shutil.copytree(DEMO_CONNECTOR, plain_root)
        for name, change in (
            (".claude-plugin/plugin.json", lambda value: value.update(name="plain-notes")),
            ("connector.json", lambda value: value.update(id="plain-notes", operator_env=[])),
            (".mcp.json", lambda value: value["mcpServers"]["demo"]["env"].pop("DEMO_BASE")),
        ):
            value = json.loads((plain_root / name).read_text(encoding="utf-8"))
            change(value)
            (plain_root / name).write_text(json.dumps(value), encoding="utf-8")
        self.operator_list({
            "plain-notes": str(plain_root),
            DEMO: {"root": str(self.connector_root), "env": {"DEMO_BASE": DEMO_BASE}},
        }, command=sys.executable)
        self.assertEqual(sorted(entry["id"] for entry in self.catalog()), [DEMO, "plain-notes"])
        plain = self.plugin._connector_manifest("plain-notes")
        self.assertEqual(plain["server"]["env"], {"DEMO_TOKEN": "${DEMO_TOKEN}", "DEMO_SCOPE": "${DEMO_SCOPE}"})
        self.assertEqual(plain["server"]["args"], [str(plain_root / "server.py")])
        # 운영자 env 는 참조가 아니라 운영 목록의 값이 서버 정의에 직접 들어간다.
        demo = self.plugin._connector_manifest(DEMO)
        self.assertEqual(demo["server"]["env"]["DEMO_BASE"], DEMO_BASE)


if __name__ == "__main__":
    unittest.main()
