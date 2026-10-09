"""dashboard-profile-api 가 `connector.json` 을 읽어 카탈로그로 내는 규칙을 검사한다(ADR-043)."""

import asyncio
import base64
import hashlib
import json
import logging
import os
import pathlib
import shutil
import sys
import tempfile
import time
import types
import unittest
from unittest import mock

from plugin_loading import load_plugin


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
        cls.plugin = load_plugin("connector_gate_test", PLUGIN, cls.addClassCleanup)
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
        self.assertEqual(set(entry), {"id", "schema", "title", "description", "icon", "link", "fields", "verify",
                                      "mcp_server", "toolsets", "attachments", "single_binding", "owner_browser",
                                      "owner_browser_login_url", "tools", "skills"})
        # `icon` 과 `link` 를 선언하지 않은 커넥터는 두 칸이 null 이다.
        self.assertIsNone(entry["icon"])
        self.assertIsNone(entry["link"])
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
            ("no fields while .mcp.json still references the field env", "connector.json",
             lambda value: value.update(fields=[])),
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
            ("errors code in lower case", "connector.json",
             lambda value: value["errors"].update(demo_lower="forbidden")),
            ("errors object without category", "connector.json",
             lambda value: value["errors"].update(DEMO_FORBIDDEN={"recovery": "reconnect"})),
            ("errors object with an unknown key", "connector.json",
             lambda value: value["errors"].update(DEMO_FORBIDDEN={"category": "forbidden", "message": "x"})),
            ("errors recovery outside the vocabulary", "connector.json",
             lambda value: value["errors"].update(DEMO_FORBIDDEN={"category": "forbidden", "recovery": "call_us"})),
            ("errors details over the limit", "connector.json",
             lambda value: value["errors"].update(DEMO_FORBIDDEN={"category": "forbidden",
                                                                  "details": ["a", "b", "c", "d", "e"]})),
            ("errors details repeat a name", "connector.json",
             lambda value: value["errors"].update(DEMO_FORBIDDEN={"category": "forbidden", "details": ["a", "a"]})),
            ("errors details name format", "connector.json",
             lambda value: value["errors"].update(DEMO_FORBIDDEN={"category": "forbidden", "details": ["Count"]})),
            ("errors contract on outcome_unknown", "connector.json",
             lambda value: value["errors"].update(DEMO_UNKNOWN={"category": "outcome_unknown",
                                                                "recovery": "recheck"})),
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
            ("server whose registered prefix equals the Control Plane MCP", ".mcp.json",
             lambda value: value.update(mcpServers={"fos_assistant": value["mcpServers"]["demo"]})),
            ("server that differs from the Control Plane MCP only by case", ".mcp.json",
             lambda value: value.update(mcpServers={"FOS-Assistant": value["mcpServers"]["demo"]})),
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

    def test_server_name_length_boundary(self):
        """등록 이름의 앞부분이 40자인 서버 이름은 받고, 41자가 되는 이름은 카탈로그에서 뺀다."""
        def rename(name):
            return lambda value: value.update(mcpServers={name: next(iter(value["mcpServers"].values()))})

        longest = "s" * (40 - len("mcp____"))
        self.rewrite(".mcp.json", rename(longest))
        self.assertEqual(len(self.plugin._hermes_tool_name(longest, "")), 40)
        self.assertEqual([entry["mcp_server"] for entry in self.catalog()], [longest])
        self.rewrite(".mcp.json", rename(longest + "s"))
        self.assertEqual(self.catalog(), [])
        self.assertIsNone(self.plugin._connector_manifest(DEMO))

    def test_server_name_is_compared_with_the_control_plane_mcp_in_canonical_form(self):
        """MCP 서버 이름은 등록 규칙으로 바꾸고 소문자로 맞춰 Control Plane MCP 와 견준다."""
        original = (self.connector_root / ".mcp.json").read_bytes()
        for name in ("fos-assistant", "fos_assistant", "FOS-Assistant", "Fos_Assistant", "fos.assistant"):
            with self.subTest(name=name):
                self.rewrite(".mcp.json", lambda value, name=name: value.update(
                    mcpServers={name: value["mcpServers"]["demo"]}))
                self.assertEqual(self.catalog(), [], "%s 가 카탈로그에 남았다" % name)
                self.assertIsNone(self.plugin._connector_manifest(DEMO))
                (self.connector_root / ".mcp.json").write_bytes(original)
        # 이름이 다른 서버는 대문자가 섞여도 받는다. 위의 거절이 대문자 때문이 아님을 확인한다.
        for name in ("demo", "Demo-Notes", "fos-assistant-notes"):
            with self.subTest(name=name):
                self.rewrite(".mcp.json", lambda value, name=name: value.update(
                    mcpServers={name: value["mcpServers"]["demo"]}))
                self.assertEqual([entry["id"] for entry in self.catalog()], [DEMO], "%s 가 거절됐다" % name)
                self.assertEqual(self.plugin._connector_manifest(DEMO)["mcp_server"], name)
                (self.connector_root / ".mcp.json").write_bytes(original)

    def test_operator_secrets_are_rejected_as_unsupported(self):
        """`operator_secrets` 를 선언한 커넥터는 카탈로그에서 빠지고 까닭이 경고 로그에 남는다. 빈 목록은 통과한다."""
        original = (self.connector_root / "connector.json").read_bytes()
        self.rewrite("connector.json", lambda value: value.update(operator_secrets=["DEMO_SERVICE_KEY"]))
        # 준비가 로그를 꺼 둔다. 경고를 읽는 동안만 켠다.
        logging.disable(logging.NOTSET)
        self.addCleanup(logging.disable, logging.CRITICAL)
        with self.assertLogs(self.plugin.logger, level="WARNING") as logs:
            self.assertEqual(self.catalog(), [])
        logging.disable(logging.CRITICAL)
        self.assertIn("operator_secrets 는 아직 지원하지 않는다", "\n".join(logs.output))

        (self.connector_root / "connector.json").write_bytes(original)
        self.rewrite("connector.json", lambda value: value.update(operator_secrets=[]))
        self.assertEqual([entry["id"] for entry in self.catalog()], [DEMO])

        for label, wrong in (("string", "X"), ("non-string item", [1])):
            with self.subTest(label):
                self.rewrite("connector.json", lambda value: value.update(operator_secrets=wrong))
                self.assertEqual(self.catalog(), [])

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

    def test_binding_guard_declarations_reach_the_catalog_and_the_manifest(self):
        """두 바인딩 제한 칸을 선언하지 않으면 거짓이고, 선언하면 `single_binding` 만 카탈로그에 참으로 나온다(ADR-20261008 connector-binding-guards)."""
        entry = self.catalog()[0]
        self.assertIs(entry["single_binding"], False)
        manifest = self.plugin._connector_manifest(DEMO)
        self.assertIs(manifest["single_binding"], False)
        self.assertIs(manifest["sandbox_required"], False)
        self.rewrite("connector.json", lambda value: value.update(single_binding=True, sandbox_required=True))
        entry = self.catalog()[0]
        self.assertIs(entry["single_binding"], True)
        # `sandbox_required` 는 대시보드가 설치에서 판정한다. Control Plane 이 쓰지 않으므로 카탈로그에 싣지 않는다.
        self.assertNotIn("sandbox_required", entry)
        manifest = self.plugin._connector_manifest(DEMO)
        self.assertIs(manifest["single_binding"], True)
        self.assertIs(manifest["sandbox_required"], True)

    def test_binding_guard_declaration_that_is_not_a_boolean_leaves_the_connector_out(self):
        """두 칸 가운데 하나라도 boolean 이 아니면 그 커넥터는 카탈로그에서 빠지고 예외가 나지 않는다."""
        original = (self.connector_root / "connector.json").read_bytes()
        for label, change in (
            ("single_binding is a string", lambda value: value.update(single_binding="true")),
            ("sandbox_required is a string", lambda value: value.update(sandbox_required="true")),
            ("single_binding is a number", lambda value: value.update(single_binding=1)),
            ("sandbox_required is null", lambda value: value.update(sandbox_required=None)),
        ):
            with self.subTest(label):
                self.rewrite("connector.json", change)
                self.assertEqual(self.catalog(), [])
                self.assertIsNone(self.plugin._connector_manifest(DEMO))
                (self.connector_root / "connector.json").write_bytes(original)
        # 되돌리면 다시 나온다. 위의 빈 목록이 고친 내용 때문이었음을 확인한다.
        self.assertEqual([entry["id"] for entry in self.catalog()], [DEMO])

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
        # BOM 과 CRLF 로 저장한 파일도 앞머리를 뗀다.
        skill = self.connector_root / "skills/demo/SKILL.md"
        skill.write_bytes(b"\xef\xbb\xbf" + skill.read_bytes().replace(b"\n", b"\r\n"))
        self.assertEqual(self.plugin._connector_manifest(DEMO)["persona"], persona)
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


class ConnectorOwnerAttachmentsTest(ConnectorGateCase):
    """사용자 첨부를 읽는 커넥터가 `owner_attachments_env` 를 선언하는 규칙을 검사한다(ADR-20261007 connector-owner-attachments)."""

    NAME = "DEMO_ATTACHMENT_DIR"

    def declare(self, name=NAME, reference=None):
        """`connector.json` 에 선언하고 `.mcp.json` 서버 env 에 그 이름의 참조를 더한다."""
        self.rewrite("connector.json", lambda value: value.update(owner_attachments_env=name))
        self.rewrite(".mcp.json", lambda value: value["mcpServers"]["demo"]["env"].update(
            {name if isinstance(name, str) else self.NAME: reference or "${%s}" % name}))

    def test_declared_name_reaches_the_manifest_but_not_the_catalog(self):
        """바른 선언은 manifest 에 담기고, 서버 정의의 값은 빈 글이며, 카탈로그 응답에는 이름이 없다."""
        self.assertIsNone(self.plugin._connector_manifest(DEMO)["owner_attachments_env"])
        self.declare()
        manifest = self.plugin._connector_manifest(DEMO)
        self.assertEqual(manifest["owner_attachments_env"], self.NAME)
        # 주인을 모르는 설치는 빈 값 그대로라 커넥터가 사용자 첨부를 읽지 않는다. profile `.env` 참조가 아니다.
        self.assertEqual(manifest["server"]["env"][self.NAME], "")
        body = self.catalog()
        self.assertEqual([entry["id"] for entry in body], [DEMO])
        self.assertNotIn(self.NAME, json.dumps(body))
        self.assertNotIn("owner_attachments_env", body[0])

    def test_invalid_declaration_leaves_the_connector_out(self):
        """선언이 칸이나 운영자 env 와 겹치거나, `.mcp.json` 에 없거나, 모양이 틀리면 카탈로그에서 빠진다."""
        originals = {name: (self.connector_root / name).read_bytes() for name in ("connector.json", ".mcp.json")}
        cases = (
            ("overlaps a field env", lambda: self.rewrite(
                "connector.json", lambda value: value.update(owner_attachments_env="DEMO_TOKEN"))),
            ("overlaps an operator env", lambda: self.rewrite(
                "connector.json", lambda value: value.update(owner_attachments_env="DEMO_BASE"))),
            ("missing from .mcp.json", lambda: self.rewrite(
                "connector.json", lambda value: value.update(owner_attachments_env=self.NAME))),
            ("lower case name", lambda: self.declare("demo_attachment_dir")),
            ("starts with an underscore", lambda: self.declare("_DEMO_DIR")),
            ("65 characters", lambda: self.declare("D" * 65)),
            ("not a string", lambda: self.declare(["DEMO_ATTACHMENT_DIR"])),
            ("a base key", lambda: self.declare("API_SERVER_KEY")),
            ("server env is a literal path", lambda: self.declare(reference="/attachments/users/owner")),
            ("server env references another name", lambda: self.declare(reference="${DEMO_TOKEN}")),
            ("server env uses an empty default", lambda: self.declare(reference="${DEMO_ATTACHMENT_DIR:-}")),
        )
        for label, change in cases:
            with self.subTest(label):
                change()
                self.assertEqual(self.catalog(), [])
                self.assertIsNone(self.plugin._connector_manifest(DEMO))
                for name, data in originals.items():
                    (self.connector_root / name).write_bytes(data)
        # 64자는 받는다. 위의 65자 거절이 길이 때문이었음을 확인한다.
        self.declare("D" * 64)
        self.assertEqual(self.plugin._connector_manifest(DEMO)["owner_attachments_env"], "D" * 64)


class ConnectorOwnerOutputTest(ConnectorGateCase):
    """목록을 파일로 내는 커넥터가 `owner_output_env` 를 선언하는 규칙을 검사한다(ADR-20261008 connector-output-files)."""

    NAME = "DEMO_OUTPUT_DIR"

    def declare(self, name=NAME, reference=None):
        self.rewrite("connector.json", lambda value: value.update(owner_output_env=name))
        self.rewrite(".mcp.json", lambda value: value["mcpServers"]["demo"]["env"].update(
            {name if isinstance(name, str) else self.NAME: reference or "${%s}" % name}))

    def test_declared_name_reaches_the_manifest_with_an_empty_value(self):
        """바른 선언은 manifest 에 담기고 서버 정의의 값은 빈 글이며 카탈로그에는 이름이 없다."""
        self.assertIsNone(self.plugin._connector_manifest(DEMO)["owner_output_env"])
        self.declare()
        manifest = self.plugin._connector_manifest(DEMO)
        self.assertEqual(manifest["owner_output_env"], self.NAME)
        self.assertEqual(manifest["server"]["env"][self.NAME], "")
        body = self.catalog()
        self.assertEqual([entry["id"] for entry in body], [DEMO])
        self.assertNotIn(self.NAME, json.dumps(body))

    def test_invalid_declaration_leaves_the_connector_out(self):
        """선언이 다른 env 와 겹치거나, `.mcp.json` 에 없거나, 모양이 틀리면 카탈로그에서 빠진다."""
        originals = {name: (self.connector_root / name).read_bytes() for name in ("connector.json", ".mcp.json")}

        def same_as_attachments():
            self.rewrite("connector.json", lambda value: value.update(owner_attachments_env=self.NAME))
            self.declare()

        cases = (
            ("overlaps a field env", lambda: self.rewrite(
                "connector.json", lambda value: value.update(owner_output_env="DEMO_TOKEN"))),
            ("overlaps an operator env", lambda: self.rewrite(
                "connector.json", lambda value: value.update(owner_output_env="DEMO_BASE"))),
            ("overlaps owner_attachments_env", same_as_attachments),
            ("missing from .mcp.json", lambda: self.rewrite(
                "connector.json", lambda value: value.update(owner_output_env=self.NAME))),
            ("lower case name", lambda: self.declare("demo_output_dir")),
            ("not a string", lambda: self.declare(["DEMO_OUTPUT_DIR"])),
            ("a base key", lambda: self.declare("API_SERVER_KEY")),
            ("server env is a literal path", lambda: self.declare(reference="/output/users/owner")),
        )
        for label, change in cases:
            with self.subTest(label):
                change()
                self.assertEqual(self.catalog(), [])
                self.assertIsNone(self.plugin._connector_manifest(DEMO))
                for name, data in originals.items():
                    (self.connector_root / name).write_bytes(data)


class ConnectorOwnerBrowserTest(ConnectorGateCase):
    """사용자 브라우저를 쓰는 커넥터가 `owner_browser_env` 와 로그인 안내 주소를 선언하는 규칙을 검사한다(ADR-20261008 browser-gateway-token)."""

    NAME = "DEMO_BROWSER_URL"
    LOGIN = "https://login.example.test/sign-in"
    ADDRESS = "http://cp.example.test/internal/browser-gateway/b1." + "a" * 64

    def declare(self, name=NAME, reference=None, **extra):
        self.rewrite("connector.json", lambda value: value.update(owner_browser_env=name, **extra))
        self.rewrite(".mcp.json", lambda value: value["mcpServers"]["demo"]["env"].update(
            {name if isinstance(name, str) else self.NAME: reference or "${%s}" % name}))

    def test_declaration_reaches_the_manifest_and_the_catalog_shows_only_the_flag(self):
        """바른 선언은 manifest 에 담기고 서버 정의의 값은 빈 글이다. 카탈로그는 사용 여부와 로그인 주소만 내고 env 이름은 내지 않는다."""
        manifest = self.plugin._connector_manifest(DEMO)
        self.assertIsNone(manifest["owner_browser_env"])
        self.assertIsNone(manifest["owner_browser_login_url"])
        entry = self.catalog()[0]
        self.assertIs(entry["owner_browser"], False)
        self.assertIsNone(entry["owner_browser_login_url"])

        self.declare(owner_browser_login_url=self.LOGIN)

        manifest = self.plugin._connector_manifest(DEMO)
        self.assertEqual(manifest["owner_browser_env"], self.NAME)
        self.assertEqual(manifest["owner_browser_login_url"], self.LOGIN)
        self.assertEqual(manifest["server"]["env"][self.NAME], "")
        body = self.catalog()
        self.assertEqual([entry["id"] for entry in body], [DEMO])
        self.assertIs(body[0]["owner_browser"], True)
        self.assertEqual(body[0]["owner_browser_login_url"], self.LOGIN)
        self.assertNotIn(self.NAME, json.dumps(body))
        self.assertNotIn("owner_browser_env", body[0])

    def test_declaration_without_a_login_url_is_accepted(self):
        """로그인 안내 주소는 선택이다. 없으면 카탈로그에 null 로 낸다."""
        self.declare()
        self.assertEqual(self.plugin._connector_manifest(DEMO)["owner_browser_env"], self.NAME)
        self.assertIsNone(self.catalog()[0]["owner_browser_login_url"])

    def test_invalid_declaration_leaves_the_connector_out(self):
        """선언이 다른 env 와 겹치거나, `.mcp.json` 에 없거나, 로그인 주소가 틀리면 카탈로그에서 빠진다."""
        originals = {name: (self.connector_root / name).read_bytes() for name in ("connector.json", ".mcp.json")}

        def same_as_output():
            self.rewrite("connector.json", lambda value: value.update(owner_output_env=self.NAME))
            self.declare()

        def same_as_attachments():
            self.rewrite("connector.json", lambda value: value.update(owner_attachments_env=self.NAME))
            self.declare()

        cases = (
            ("overlaps a field env", lambda: self.rewrite(
                "connector.json", lambda value: value.update(owner_browser_env="DEMO_TOKEN"))),
            ("overlaps an operator env", lambda: self.rewrite(
                "connector.json", lambda value: value.update(owner_browser_env="DEMO_BASE"))),
            ("overlaps owner_output_env", same_as_output),
            ("overlaps owner_attachments_env", same_as_attachments),
            ("missing from .mcp.json", lambda: self.rewrite(
                "connector.json", lambda value: value.update(owner_browser_env=self.NAME))),
            ("lower case name", lambda: self.declare("demo_browser_url")),
            ("not a string", lambda: self.declare(["DEMO_BROWSER_URL"])),
            ("a base key", lambda: self.declare("API_SERVER_KEY")),
            ("server env is a literal address", lambda: self.declare(reference=self.ADDRESS)),
            ("login url over http", lambda: self.declare(owner_browser_login_url="http://login.example.test/")),
            ("login url with a space", lambda: self.declare(owner_browser_login_url="https://login.example.test/a b")),
            ("login url with a control character",
             lambda: self.declare(owner_browser_login_url="https://login.example.test/\x7f")),
            ("login url over 512 characters",
             lambda: self.declare(owner_browser_login_url="https://login.example.test/" + "a" * 486)),
            ("login url not a string", lambda: self.declare(owner_browser_login_url=["https://login.example.test/"])),
            ("login url without the env", lambda: self.rewrite(
                "connector.json", lambda value: value.update(owner_browser_login_url=self.LOGIN))),
        )
        for label, change in cases:
            with self.subTest(label):
                change()
                self.assertEqual(self.catalog(), [])
                self.assertIsNone(self.plugin._connector_manifest(DEMO))
                for name, data in originals.items():
                    (self.connector_root / name).write_bytes(data)
        # 512자는 받는다. 위의 513자 거절이 길이 때문이었음을 확인한다.
        self.declare(owner_browser_login_url="https://login.example.test/" + "a" * 485)
        self.assertEqual(len(self.plugin._connector_manifest(DEMO)["owner_browser_login_url"]), 512)

    def test_installed_value_matches_when_empty_or_shaped_like_a_relay_address(self):
        """설치 기록의 값은 빈 값이거나 중계 주소 모양이면 지금 manifest 와 같다고 본다. 참조나 다른 모양은 다르다."""
        self.declare()
        manifest = self.plugin._connector_manifest(DEMO)
        for label, value, expected in (
            ("empty", "", True),
            ("relay address", self.ADDRESS, True),
            ("https relay with a port", "https://cp.example.test:8443/gw/u1.2." + "b" * 64, True),
            ("profile env reference", "${DEMO_BROWSER_URL}", False),
            ("websocket scheme", "ws://cp.example.test/gw/b1", False),
            ("no path", "http://cp.example.test", False),
            ("trailing newline", self.ADDRESS + "\n", False),
        ):
            with self.subTest(label):
                server = {**manifest["server"], "env": {**manifest["server"]["env"], self.NAME: value}}
                self.assertIs(self.plugin._server_matches(manifest, server), expected)


class ConnectorFieldlessAndSkillTest(ConnectorGateCase):
    """입력 칸이 없는 커넥터와, 바인딩 설치가 복사할 스킬을 카탈로그로 내는 규칙을 검사한다(ADR-083)."""

    def without_fields(self):
        """시험 커넥터의 칸을 모두 뺀다. `.mcp.json` 의 env 에는 운영자 env 만 남는다."""
        self.rewrite("connector.json", lambda value: value.update(fields=[]))
        self.rewrite(".mcp.json", lambda value: value["mcpServers"]["demo"].update(env={"DEMO_BASE": "${DEMO_BASE}"}))

    def test_connector_without_fields_reaches_the_catalog(self):
        """빈 `fields` 는 받는다. 확인 도구와 운영자 env 규칙은 그대로 걸린다."""
        self.without_fields()
        entry = self.catalog()[0]
        self.assertEqual(entry["fields"], [])
        self.assertEqual(entry["verify"], {"tool": "list_scopes"})
        manifest = self.plugin._connector_manifest(DEMO)
        self.assertEqual(manifest["server"]["env"], {"DEMO_BASE": DEMO_BASE})
        self.assertEqual(manifest["call_tools"], frozenset({"list_scopes"}))
        # 확인 도구가 없거나 운영자 env 가 `.mcp.json` 과 다르면 칸이 없어도 거절한다.
        self.rewrite("connector.json", lambda value: value.pop("verify"))
        self.assertEqual(self.catalog(), [])
        self.rewrite("connector.json", lambda value: value.update(verify={"tool": "list_scopes"}, operator_env=[]))
        self.assertEqual(self.catalog(), [])
        # `fields` 가 목록이 아니면 받지 않는다.
        self.rewrite("connector.json", lambda value: value.update(operator_env=["DEMO_BASE"], fields={}))
        self.assertEqual(self.catalog(), [])

    def test_catalog_lists_skill_names_from_the_front_matter_without_bodies(self):
        """카탈로그의 `skills` 는 앞머리 `name` 의 이름 순 목록이고, 앞머리에 이름이 없으면 디렉터리 이름이다."""
        self.assertEqual(self.catalog()[0]["skills"], ["demo"])
        other = self.connector_root / "skills/zz-dir"
        other.mkdir()
        (other / "SKILL.md").write_text("---\nname: alpha-notes\ndescription: 다른 스킬\n---\n본문\n", encoding="utf-8")
        plain = self.connector_root / "skills/plain-dir"
        plain.mkdir()
        (plain / "SKILL.md").write_text("앞머리 없는 본문\n", encoding="utf-8")
        self.assertEqual(self.catalog()[0]["skills"], ["alpha-notes", "demo", "plain-dir"])
        text = json.dumps(self.catalog(), ensure_ascii=False)
        self.assertNotIn("앞머리 없는 본문", text)
        self.assertNotIn("다른 스킬", text)

    def test_skill_files_are_read_from_skill_md_references_and_templates_only(self):
        """복사할 파일은 `SKILL.md` 와 `references/`, `templates/` 아래 정규 파일이고 바이트 그대로다."""
        skill = self.connector_root / "skills/demo"
        (skill / "references/deep").mkdir(parents=True)
        (skill / "references/deep/guide.md").write_bytes("안내\r\n".encode("utf-8"))
        (skill / "templates").mkdir()
        (skill / "templates/reply.txt").write_text("답장 틀\n", encoding="utf-8")
        (skill / "NOTES.md").write_text("복사하지 않는 파일\n", encoding="utf-8")
        files = self.plugin._connector_manifest(DEMO)["skills"]["demo"]
        self.assertEqual(sorted(files), ["SKILL.md", "references/deep/guide.md", "templates/reply.txt"])
        self.assertEqual(files["references/deep/guide.md"], "안내\r\n".encode("utf-8"))
        self.assertEqual(files["SKILL.md"], (DEMO_CONNECTOR / "skills/demo/SKILL.md").read_bytes())

    def test_skill_that_cannot_be_copied_safely_leaves_the_connector_out(self):
        """스킬 아래 링크, UTF-8 이 아닌 파일, 상한을 넘는 스킬, 경로로 쓸 수 없거나 겹치는 이름은 카탈로그에서 뺀다."""
        skill = self.connector_root / "skills/demo"
        skill_md = (skill / "SKILL.md").read_text(encoding="utf-8")
        outside = self.base / "outside.md"
        outside.write_text("밖의 비밀", encoding="utf-8")
        (skill / "references").mkdir()

        def link_in_references():
            (skill / "references/link.md").symlink_to(outside)

        def link_outside_copied_parts():
            # 복사하지 않는 자리의 링크도 거절한다. 그 아래 어느 항목이든 링크이면 그 커넥터를 내지 않는다.
            (skill / "notes.md").symlink_to(outside)

        def name(value):
            return lambda: (skill / "SKILL.md").write_text(
                "---\nname: %s\ndescription: 검사\n---\n본문\n" % value, encoding="utf-8")

        def duplicate():
            twin = self.connector_root / "skills/twin"
            twin.mkdir()
            (twin / "SKILL.md").write_text("---\nname: demo\n---\n본문\n", encoding="utf-8")

        def too_many_files():
            for index in range(self.plugin.CONNECTOR_SKILL_MAX_FILES):
                (skill / ("references/%02d.md" % index)).write_text("x", encoding="utf-8")

        cases = (
            ("link in references", link_in_references),
            ("link outside the copied parts", link_outside_copied_parts),
            ("file that is not UTF-8", lambda: (skill / "references/bin.md").write_bytes(b"\xff\xfe\x00")),
            ("file over the size limit", lambda: (skill / "references/big.md").write_text(
                "가" * (self.plugin.CONNECTOR_SKILL_MAX_CHARS + 1), encoding="utf-8")),
            ("more files than the limit", too_many_files),
            ("name with a slash", name("a/b")),
            ("name with two dots", name("a..b")),
            ("name of two dots", name("'..'")),
            ("upper-case name", name("Demo")),
            ("name that is not a string", name("[demo]")),
            ("two skills with one name", duplicate),
        )
        for label, apply in cases:
            with self.subTest(label):
                apply()
                self.assertEqual(self.catalog(), [])
                self.assertIsNone(self.plugin._connector_manifest(DEMO))
                shutil.rmtree(skill / "references")
                (skill / "references").mkdir()
                (skill / "notes.md").unlink(missing_ok=True)
                shutil.rmtree(self.connector_root / "skills/twin", ignore_errors=True)
                (skill / "SKILL.md").write_text(skill_md, encoding="utf-8")
        # 경계 안쪽은 받는다. 파일 20개(SKILL.md 포함)와 파일마다 10만 자다.
        for index in range(self.plugin.CONNECTOR_SKILL_MAX_FILES - 1):
            (skill / ("references/%02d.md" % index)).write_text("x", encoding="utf-8")
        (skill / "references/00.md").write_text("가" * self.plugin.CONNECTOR_SKILL_MAX_CHARS, encoding="utf-8")
        self.assertEqual(self.catalog()[0]["skills"], ["demo"])
        self.assertEqual(len(self.plugin._connector_manifest(DEMO)["skills"]["demo"]),
                         self.plugin.CONNECTOR_SKILL_MAX_FILES)


class ConnectorToolPolicyTest(ConnectorGateCase):
    """`schema: 2` 의 도구 정책을 검증해 카탈로그로 내는 규칙을 검사한다(ADR-049)."""

    def declare(self, change=lambda tools: None, **extra):
        """시험 커넥터를 `schema: 2` 로 바꾼다. 기준 선언에 `change` 를 입히고 `extra` 를 manifest 에 더한다."""
        tools = {"list_scopes": {"risk": "READ"}, "env_view": {"risk": "READ"}, "write_note": {"risk": "WRITE"}}
        change(tools)
        self.rewrite("connector.json", lambda value: value.update(schema=2, tools=tools, **extra))

    def test_schema_one_lists_only_the_read_only_call_tools(self):
        """`schema: 1` 은 그대로 받고, 대시보드가 부르는 도구만 읽기 전용 정책으로 낸다."""
        entry = self.catalog()[0]
        self.assertEqual(entry["schema"], 1)
        self.assertEqual(entry["tools"], {
            "list_scopes": {"risk": "READ", "approval": "none", "grant": False, "outbound": False, "identifiers": []}})

    def test_schema_two_fills_the_default_approval(self):
        """`approval` 을 적지 않은 도구는 그 위험도의 기본값으로 채워 낸다. 제목이 없으면 `title` 을 내지 않는다."""
        self.declare()
        entry = self.catalog()[0]
        self.assertEqual(entry["schema"], 2)
        self.assertEqual(entry["tools"], {
            "list_scopes": {"risk": "READ", "approval": "none", "grant": False, "outbound": False, "identifiers": []},
            "env_view": {"risk": "READ", "approval": "none", "grant": False, "outbound": False, "identifiers": []},
            "write_note": {"risk": "WRITE", "approval": "required", "grant": True, "outbound": False, "identifiers": []},
        })

    def test_default_approval_of_every_risk(self):
        """위험도 다섯의 기본 `approval` 은 계약의 표와 같다."""
        expected = {"READ": "none", "SENSITIVE": "required", "WRITE": "required",
                    "DESTRUCTIVE": "always", "FINANCIAL": "always"}
        for risk, approval in expected.items():
            with self.subTest(risk):
                self.declare(lambda tools: tools.update(probe={"risk": risk}))
                self.assertEqual(self.catalog()[0]["tools"]["probe"],
                                 {"risk": risk, "approval": approval, "grant": approval == "required",
                                  "outbound": False, "identifiers": []})

    def test_stricter_approval_and_title_reach_the_catalog(self):
        """하한보다 엄격한 `approval` 과 사람 말 제목은 선언한 그대로 낸다."""
        self.declare(lambda tools: tools.update(
            write_note={"risk": "WRITE", "approval": "always", "title": "메모 쓰기"},
            env_view={"risk": "READ", "approval": "required"}))
        tools = self.catalog()[0]["tools"]
        self.assertEqual(tools["write_note"],
                         {"risk": "WRITE", "approval": "always", "title": "메모 쓰기", "grant": False,
                          "outbound": False, "identifiers": []})
        self.assertEqual(tools["env_view"],
                         {"risk": "READ", "approval": "required", "grant": True, "outbound": False, "identifiers": []})

    def test_title_length_boundary(self):
        """제목은 80자까지 받는다. 81자와 빈 문자열은 받지 않는다."""
        self.declare(lambda tools: tools["write_note"].update(title="가" * 80))
        self.assertEqual(self.catalog()[0]["tools"]["write_note"]["title"], "가" * 80)
        for label, title in (("81 chars", "가" * 81), ("empty", ""), ("not a string", 1), ("null", None)):
            with self.subTest(label):
                self.declare(lambda tools: tools["write_note"].update(title=title))
                self.assertEqual(self.catalog(), [])

    def test_destructive_tool_defaults_to_always(self):
        """`DESTRUCTIVE` 는 받고 `approval` 이 `always` 다. `required` 로 내려 선언하면 빠진다."""
        self.declare(lambda tools: tools.update(purge={"risk": "DESTRUCTIVE"}))
        self.assertEqual(self.catalog()[0]["tools"]["purge"],
                         {"risk": "DESTRUCTIVE", "approval": "always", "grant": False, "outbound": False, "identifiers": []})
        self.declare(lambda tools: tools.update(purge={"risk": "DESTRUCTIVE", "approval": "required"}))
        self.assertEqual(self.catalog(), [])

    def test_closed_grant_keeps_the_tool_visible_to_the_model(self):
        """`"grant": false` 인 도구는 `grant` 만 거짓으로 나오고 서버 정의의 `tools.exclude` 에 들지 않는다(ADR-065)."""
        self.declare(lambda tools: tools.update(send_note={"risk": "WRITE", "grant": False}))
        tools = self.catalog()[0]["tools"]
        self.assertEqual(tools["send_note"],
                         {"risk": "WRITE", "approval": "required", "grant": False, "outbound": False, "identifiers": []})
        # 선언하지 않은 쓰기 도구는 상시 허락을 줄 수 있고, 승인이 없는 읽기 도구는 줄 것이 없다.
        self.assertIs(tools["write_note"]["grant"], True)
        self.assertIs(tools["env_view"]["grant"], False)
        self.assertNotIn("tools", self.plugin._connector_manifest(DEMO)["server"])

    def test_explicit_open_grant_is_the_same_as_no_declaration(self):
        """`"grant": true` 는 선언하지 않은 것과 같은 값을 낸다."""
        self.declare(lambda tools: tools.update(write_note={"risk": "WRITE", "grant": True}))
        self.assertEqual(self.catalog()[0]["tools"]["write_note"],
                         {"risk": "WRITE", "approval": "required", "grant": True, "outbound": False, "identifiers": []})

    def test_outbound_tool_with_closed_grant_reaches_the_catalog(self):
        """`"outbound": true` 는 상시 허락을 닫은 도구에서만 받고 카탈로그에 그대로 나온다."""
        self.declare(lambda tools: tools.update(
            send_note={"risk": "WRITE", "grant": False, "outbound": True, "identifiers": []},
            write_note={"risk": "WRITE", "outbound": False, "identifiers": []}))
        tools = self.catalog()[0]["tools"]
        self.assertEqual(tools["send_note"],
                         {"risk": "WRITE", "approval": "required", "grant": False, "outbound": True, "identifiers": []})
        # 거짓으로 적은 것은 적지 않은 것과 같다. 상시 허락은 그대로 열려 있다.
        self.assertEqual(tools["write_note"],
                         {"risk": "WRITE", "approval": "required", "grant": True, "outbound": False, "identifiers": []})
        self.assertIs(tools["env_view"]["outbound"], False)

    def test_identifiers_reach_the_catalog_in_declared_order(self):
        """`identifiers` 는 승인을 받는 도구에서 받고 선언한 순서 그대로 나온다(ADR-089)."""
        self.declare(lambda tools: tools.update(
            write_note={"risk": "WRITE", "grant": False, "identifiers": ["note_id", "folder_id", "a" * 31]}))
        tools = self.catalog()[0]["tools"]
        self.assertEqual(tools["write_note"]["identifiers"], ["note_id", "folder_id", "a" * 31])
        self.assertEqual(tools["env_view"]["identifiers"], [])

    def test_invalid_identifiers_leave_the_connector_out(self):
        """틀린 `identifiers` 는 고쳐 읽지 않고 그 커넥터를 카탈로그에서 뺀다(ADR-089)."""
        cases = (
            ("not a list", "note_id"),
            ("not a string item", [1]),
            ("empty name", [""]),
            ("nested path", ["note.id"]),
            ("starts with a digit", ["1id"]),
            ("32 chars", ["a" * 32]),
            ("trailing newline", ["note_id\n"]),
            ("duplicate", ["note_id", "note_id"]),
            ("secret key", ["api_token"]),
            ("secret key with mixed case", ["ClientSecret"]),
            ("password suffix", ["user_password"]),
        )
        original = (self.connector_root / "connector.json").read_bytes()
        for label, identifiers in cases:
            with self.subTest(label):
                self.declare(lambda tools: tools.update(write_note={"risk": "WRITE", "identifiers": identifiers}))
                self.assertEqual(self.catalog(), [])
                (self.connector_root / "connector.json").write_bytes(original)
        for label, tool in (("read tool", {"risk": "READ", "identifiers": ["id"]}),
                            ("always tool", {"risk": "WRITE", "approval": "always", "identifiers": ["id"]})):
            with self.subTest(label):
                self.declare(lambda tools: tools.update(probe=tool))
                self.assertEqual(self.catalog(), [])
                (self.connector_root / "connector.json").write_bytes(original)

    def test_explicit_deny_default_policy_is_accepted(self):
        """`default_tool_policy` 는 `deny` 만 받는다."""
        self.declare(default_tool_policy="deny")
        self.assertEqual([entry["id"] for entry in self.catalog()], [DEMO])

    def test_invalid_tool_policy_leaves_the_connector_out(self):
        """도구 정책이 하나라도 틀리면 고쳐 읽지 않고 그 커넥터를 카탈로그에서 뺀다."""
        cases = (
            ("write below the floor", lambda: self.declare(
                lambda tools: tools.update(write_note={"risk": "WRITE", "approval": "none"}))),
            ("sensitive below the floor", lambda: self.declare(
                lambda tools: tools.update(env_view={"risk": "SENSITIVE", "approval": "none"}))),
            ("financial below the floor", lambda: self.declare(
                lambda tools: tools.update(pay={"risk": "FINANCIAL", "approval": "required"}))),
            ("verify tool is not READ", lambda: self.declare(
                lambda tools: tools.update(list_scopes={"risk": "WRITE"}))),
            ("verify tool needs approval", lambda: self.declare(
                lambda tools: tools.update(list_scopes={"risk": "READ", "approval": "required"}))),
            ("verify tool is not declared", lambda: self.declare(lambda tools: tools.pop("list_scopes"))),
            ("no tools", lambda: self.rewrite("connector.json", lambda value: value.update(schema=2))),
            ("empty tools", lambda: self.rewrite("connector.json", lambda value: value.update(schema=2, tools={}))),
            ("tools is a list", lambda: self.rewrite(
                "connector.json", lambda value: value.update(schema=2, tools=["list_scopes"]))),
            ("default policy allow", lambda: self.declare(default_tool_policy="allow")),
            ("default policy null", lambda: self.declare(default_tool_policy=None)),
            ("unknown risk", lambda: self.declare(lambda tools: tools.update(write_note={"risk": "DANGEROUS"}))),
            ("lowercase risk", lambda: self.declare(lambda tools: tools.update(write_note={"risk": "write"}))),
            ("no risk", lambda: self.declare(lambda tools: tools.update(write_note={"approval": "always"}))),
            ("risk is not a string", lambda: self.declare(lambda tools: tools.update(write_note={"risk": ["WRITE"]}))),
            ("unknown approval", lambda: self.declare(
                lambda tools: tools.update(write_note={"risk": "WRITE", "approval": "sometimes"}))),
            ("approval null", lambda: self.declare(
                lambda tools: tools.update(write_note={"risk": "WRITE", "approval": None}))),
            ("unknown key", lambda: self.declare(
                lambda tools: tools.update(write_note={"risk": "WRITE", "note": "x"}))),
            ("grant is a string", lambda: self.declare(
                lambda tools: tools.update(write_note={"risk": "WRITE", "grant": "false"}))),
            ("grant is a number", lambda: self.declare(
                lambda tools: tools.update(write_note={"risk": "WRITE", "grant": 0}))),
            ("grant on a tool without approval", lambda: self.declare(
                lambda tools: tools.update(env_view={"risk": "READ", "grant": False}))),
            ("grant on a tool that always needs approval", lambda: self.declare(
                lambda tools: tools.update(write_note={"risk": "WRITE", "approval": "always", "grant": True}))),
            ("outbound is a string", lambda: self.declare(
                lambda tools: tools.update(write_note={"risk": "WRITE", "grant": False, "outbound": "true"}))),
            ("outbound is a number", lambda: self.declare(
                lambda tools: tools.update(write_note={"risk": "WRITE", "grant": False, "outbound": 1}))),
            ("outbound with the grant left open", lambda: self.declare(
                lambda tools: tools.update(write_note={"risk": "WRITE", "outbound": True}))),
            ("outbound with the grant declared open", lambda: self.declare(
                lambda tools: tools.update(write_note={"risk": "WRITE", "grant": True, "outbound": True}))),
            ("outbound on a tool without approval", lambda: self.declare(
                lambda tools: tools.update(env_view={"risk": "READ", "outbound": True}))),
            ("outbound on a tool that always needs approval", lambda: self.declare(
                lambda tools: tools.update(write_note={"risk": "WRITE", "approval": "always", "outbound": True}))),
            ("tool value is not an object", lambda: self.declare(lambda tools: tools.update(write_note="WRITE"))),
            ("tool name format", lambda: self.declare(lambda tools: tools.update({"write note": {"risk": "WRITE"}}))),
            ("registered names collide", lambda: self.declare(
                lambda tools: tools.update({"a.b": {"risk": "READ"}, "a-b": {"risk": "READ"}}))),
            ("schema 3", lambda: (self.declare(), self.rewrite("connector.json", lambda value: value.update(schema=3)))),
            ("schema 1 with tools", lambda: self.rewrite(
                "connector.json", lambda value: value.update(tools={"list_scopes": {"risk": "READ"}}))),
            ("schema 1 with default policy", lambda: self.rewrite(
                "connector.json", lambda value: value.update(default_tool_policy="deny"))),
        )
        original = (self.connector_root / "connector.json").read_bytes()
        for label, apply in cases:
            with self.subTest(label):
                apply()
                self.assertEqual(self.catalog(), [])
                self.assertIsNone(self.plugin._connector_manifest(DEMO))
                (self.connector_root / "connector.json").write_bytes(original)
        # 되돌리면 다시 나온다. 위의 빈 목록이 고친 내용 때문이었음을 확인한다.
        self.assertEqual([entry["id"] for entry in self.catalog()], [DEMO])

    def test_one_of_two_colliding_names_alone_is_accepted(self):
        """등록 이름이 겹치는 두 도구 가운데 하나만 선언하면 받는다. 거절의 까닭이 겹침임을 확인한다."""
        self.declare(lambda tools: tools.update({"a.b": {"risk": "READ"}}))
        self.assertIn("a.b", self.catalog()[0]["tools"])

    def test_call_still_accepts_only_the_dashboard_tools(self):
        """도구 정책에 선언한 쓰기 도구는 대시보드의 `call` 로 부르지 못한다."""
        self.declare()
        manifest = self.plugin._connector_manifest(DEMO)
        self.assertEqual(manifest["call_tools"], frozenset({"list_scopes"}))
        status, body = self.request("/api/connectors/%s/call" % DEMO, "POST", {"tool": "write_note", "values": {}})
        self.assertEqual(status, 400, body)


SVG_OPEN = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
ICON_MAX_BYTES = 32 * 1024


def svg(inner="", head=""):
    """`head` 뒤에 `<svg>` 요소를 두고 그 안에 `inner` 를 넣은 UTF-8 바이트다."""
    return (head + SVG_OPEN + inner + "</svg>").encode("utf-8")


def svg_of_size(size):
    """요소 안을 공백으로 채워 정확히 `size` 바이트인 SVG 다."""
    empty = svg()
    return svg(" " * (size - len(empty)))


# 링크 모양의 시험 벡터다. Control Plane 의 `ConnectorAppearancesTest` 가 같은 목록을 단언한다. 한쪽을 바꾸면 다른 쪽도 바꾼다.
ACCEPTED_LINKS = (
    "https://mail.google.com/",
    "https://blog.naver.com/",
    "https://example.com",
    "https://example.com:8443/a?b=1&c=%20#x",
    "https://xn--9n2bp8q.com/",
    "https://example.com/" + "a" * (500 - len("https://example.com/")),
)
REJECTED_LINKS = (
    "https://example.com/a|b",
    'https://example.com/a"b',
    "https://example.com/<x>",
    "https://example.com/{x}",
    "https://example.com/a^b",
    "https://example.com/%zz",
    "https://my_host.example.com/",
    "https://example.com:abc/",
    "https://예시.com/한글",
    "https://a..b/",
    "https://-bad-.com/",
    "http://example.com/",
    "HTTPS://example.com/",
    "https://user@example.com/",
    "https://example.com/a b",
    "https://example.com/a\\b",
    "https://[::1]/",
    "https://example.com/?a[]=1",
    "https://example.com/" + "a" * (501 - len("https://example.com/")),
)


class ConnectorAppearanceTest(ConnectorGateCase):
    """커넥터 카드의 `icon` 과 `link` 를 검증해 카탈로그에 싣는 규칙을 검사한다(ADR-20261008 connector-card).

    `icon` 이나 `link` 가 규칙을 하나라도 어기면 그 칸만 null 이고 커넥터는 카탈로그에 그대로 나온다.
    """

    def declare(self, **values):
        self.rewrite("connector.json", lambda value: value.update(values))

    def write(self, name, data: bytes):
        path = self.connector_root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def catalog_icon(self, name, data: bytes):
        """`name` 에 `data` 를 쓰고 그 파일을 선언한 뒤 카탈로그의 `icon` 칸을 돌려준다. 빠졌으면 실패다."""
        self.write(name, data)
        self.declare(icon=name)
        body = self.catalog()
        self.assertEqual([entry["id"] for entry in body], [DEMO], "아이콘 %s 를 받지 않았다" % name)
        return body[0]["icon"]

    def catalog_entry(self):
        """카탈로그에 시험 커넥터 하나만 있음을 확인하고 그 항목을 돌려준다."""
        body = self.catalog()
        self.assertEqual([entry["id"] for entry in body], [DEMO])
        return body[0]

    def warnings_while(self, action):
        """`action` 을 부르는 동안 남은 경고 로그를 한 글로 돌려준다. 준비가 로그를 꺼 두므로 그동안만 켠다."""
        logging.disable(logging.NOTSET)
        try:
            with self.assertLogs(self.plugin.logger, level="WARNING") as logs:
                action()
        finally:
            logging.disable(logging.CRITICAL)
        return "\n".join(logs.output)

    def test_declared_svg_icon_and_link_reach_the_catalog(self):
        """선언한 SVG 아이콘은 `media_type` 과 파일 원본의 base64 로, 링크는 그대로 나온다."""
        data = (DEMO_CONNECTOR / "icon.svg").read_bytes()
        self.declare(icon="icon.svg", link="https://example.com/notes?view=card")
        entry = self.catalog()[0]
        self.assertEqual(entry["icon"], {"media_type": "image/svg+xml", "data": base64.b64encode(data).decode("ascii")})
        self.assertEqual(entry["link"], "https://example.com/notes?view=card")

    def test_declared_png_icon_reaches_the_catalog(self):
        """하위 디렉터리의 PNG 아이콘은 서명만 보고 내용은 그대로 싣는다."""
        data = PNG_SIGNATURE + bytes(range(256))
        self.assertEqual(self.catalog_icon("assets/icon.png", data),
                         {"media_type": "image/png", "data": base64.b64encode(data).decode("ascii")})

    def test_svg_with_local_references_and_a_prolog_is_accepted(self):
        """문서 안 참조(`#`)만 가리키는 `href` 와 `url()`, BOM 과 XML 선언과 주석 뒤의 `<svg` 는 받는다."""
        cases = {
            'href="#a"': svg('<use href="#a"/>'),
            "xlink:href='#a'": svg("<use xlink:href='#a'/>"),
            "url(#g)": svg('<rect fill="url(#g)"/>'),
            "url('#g')": svg("<rect fill=\"url('#g')\"/>"),
            "url( #g)": svg('<rect fill="url( #g)"/>'),
            "BOM, XML 선언, 주석": svg(head='\ufeff<?xml version="1.0" encoding="UTF-8"?>\n<!-- 직접 그림 -->\n'),
        }
        for label, data in cases.items():
            with self.subTest(label):
                self.assertEqual(self.catalog_icon("icon.svg", data)["data"], base64.b64encode(data).decode("ascii"))

    def test_icon_of_exactly_the_limit_is_accepted(self):
        """32 KiB 정확히인 아이콘은 받는다. 아래 1 바이트 큰 파일의 거절이 크기 때문임을 보인다."""
        data = svg_of_size(ICON_MAX_BYTES)
        self.assertEqual(len(data), ICON_MAX_BYTES)
        self.assertEqual(self.catalog_icon("icon.svg", data)["data"], base64.b64encode(data).decode("ascii"))

    def test_links_of_the_link_shape_are_accepted(self):
        """링크 모양에 맞는 링크는 그대로 나온다. 500자 링크도 받는다."""
        for link in ACCEPTED_LINKS:
            with self.subTest(link=link[:60]):
                self.declare(link=link)
                self.assertEqual(self.catalog_entry()["link"], link)

    def test_links_outside_the_link_shape_are_dropped(self):
        """링크 모양을 벗어난 링크는 그 칸만 null 이고 아이콘과 커넥터는 남는다."""
        icon = (DEMO_CONNECTOR / "icon.svg").read_bytes()
        expected_icon = {"media_type": "image/svg+xml", "data": base64.b64encode(icon).decode("ascii")}
        for link in REJECTED_LINKS:
            with self.subTest(link=link[:60]):
                self.declare(icon="icon.svg", link=link)
                entry = self.catalog_entry()
                self.assertIsNone(entry["link"], "링크 %r 를 받았다" % link)
                self.assertEqual(entry["icon"], expected_icon)
        self.declare(link={"href": "https://example.com/"})
        self.assertIsNone(self.catalog_entry()["link"])

    def test_invalid_icon_drops_only_the_icon(self):
        """아이콘의 경로, 크기, 형식이 하나라도 틀리면 아이콘 칸만 null 이고 링크와 커넥터는 남는다."""
        outside = self.base / "outside.svg"
        outside.write_bytes(svg())
        link = "https://example.com/notes"

        def icon(name, data=None):
            def change():
                if data is not None:
                    self.write(name, data)
                self.declare(icon=name)
            return change

        def linked():
            (self.connector_root / "linked.svg").symlink_to(outside)
            self.declare(icon="linked.svg")

        cases = (
            ("absolute path", lambda: self.declare(icon=str(self.connector_root / "icon.svg"))),
            ("../icon.svg", lambda: (outside.with_name("icon.svg").write_bytes(svg()), self.declare(icon="../icon.svg"))),
            ("skills/../icon.svg", icon("skills/../icon.svg")),
            ("not a string", lambda: self.declare(icon=["icon.svg"])),
            (".gif extension", icon("icon.gif", b"GIF89a")),
            ("missing file", icon("missing.svg")),
            ("empty file", icon("empty.svg", b"")),
            ("symlink outside the plugin", linked),
            ("one byte over 32 KiB", icon("large.svg", svg_of_size(ICON_MAX_BYTES + 1))),
            ("PNG without signature", icon("icon.png", b"not a png at all")),
            ("SVG not UTF-8", icon("latin.svg", svg() + b"\xff")),
            ("<script>", icon("icon.svg", svg("<script>alert(1)</script>"))),
            ("onload=", icon("icon.svg", svg('<rect onload="alert(1)"/>'))),
            ("<foreignObject>", icon("icon.svg", svg("<foreignObject></foreignObject>"))),
            ("<!DOCTYPE", icon("icon.svg", svg("<!DOCTYPE svg>"))),
            ("<set", icon("icon.svg", svg('<rect><set attributeName="fill" to="red"/></rect>'))),
            ("<animate", icon("icon.svg", svg('<rect><animate attributeName="x"/></rect>'))),
            ("&#", icon("icon.svg", svg("<text>&#106;</text>"))),
            ("backslash", icon("icon.svg", svg('<rect style="fill:\\72 ed"/>'))),
            ('href="https://..."', icon("icon.svg", svg('<use href="https://example.com/a.svg#a"/>'))),
            ('xlink:href="javascript:..."', icon("icon.svg", svg('<a xlink:href="javascript:alert(1)"/>'))),
            ("url(http...)", icon("icon.svg", svg('<rect fill="url(http://example.com/g)"/>'))),
            ("does not start with svg", icon("icon.svg", b"<g/>" + svg())),
        )
        original = (self.connector_root / "icon.svg").read_bytes()
        for label, change in cases:
            with self.subTest(label):
                self.declare(link=link)
                change()
                entry = self.catalog_entry()
                self.assertIsNone(entry["icon"], "%s 아이콘을 받았다" % label)
                self.assertEqual(entry["link"], link)
                (self.connector_root / "icon.svg").write_bytes(original)

    def test_dropped_field_is_logged_without_path_or_value(self):
        """버린 칸은 커넥터 번호, 칸 이름, 직접 낸 사유만 경고 로그에 남는다. 경로와 링크 값은 없다."""
        secret_link = "https://example.com/a|private"
        self.declare(icon="missing-private.svg", link=secret_link)
        output = self.warnings_while(self.catalog_entry)
        self.assertIn("커넥터 %r 의 icon 칸을 버렸다" % DEMO, output)
        self.assertIn("커넥터 %r 의 link 칸을 버렸다" % DEMO, output)
        for leaked in ("missing-private", "private", str(self.base)):
            self.assertNotIn(leaked, output)

    def test_dot_dot_segment_is_rejected_by_the_path_rule(self):
        """`assets/../icon.svg` 는 정규식을 통과하고 가리키는 파일도 있지만 `..` 조각 때문에 경로 규칙에서 거절한다."""
        (self.connector_root / "assets").mkdir()
        self.assertTrue((self.connector_root / "assets/../icon.svg").is_file())
        self.declare(icon="assets/../icon.svg")
        entries = []
        output = self.warnings_while(lambda: entries.append(self.catalog_entry()))
        self.assertIsNone(entries[0]["icon"])
        self.assertIn("icon 칸을 버렸다: icon 은 plugin 디렉터리 기준 상대 경로다", output)

    def test_many_comments_before_the_root_finish_quickly(self):
        """주석이 많은 SVG 도 1초 안에 판정한다. `<svg/>` 가 뒤따르면 받고 다른 글이면 거절한다."""
        comments = b"<!---->" * 2000
        started = time.monotonic()
        self.assertIsNotNone(self.catalog_icon("icon.svg", comments + b"<svg/>"))
        accepted = time.monotonic() - started
        self.write("icon.svg", comments + b"X")
        started = time.monotonic()
        self.assertIsNone(self.catalog_entry()["icon"])
        rejected = time.monotonic() - started
        self.assertLess(accepted, 1.0, "주석 뒤 <svg/> 판정이 %.3f 초 걸렸다" % accepted)
        self.assertLess(rejected, 1.0, "주석 뒤 X 판정이 %.3f 초 걸렸다" % rejected)


class HermesToolNameTest(ConnectorGateCase):
    """원래 도구 이름에서 Hermes 의 등록 이름을 계산하는 규칙을 검사한다."""

    def test_non_word_characters_become_underscores(self):
        self.assertEqual(self.plugin._hermes_tool_name("policy-probe", "write_item"), "mcp__policy_probe__write_item")
        self.assertEqual(self.plugin._hermes_tool_name("demo", "a.b"), "mcp__demo__a_b")

    def test_name_of_exactly_the_limit_is_kept(self):
        """이은 이름이 64자이면 줄이지 않는다."""
        tool = "t" * (64 - len("mcp__demo__"))
        self.assertEqual(self.plugin._hermes_tool_name("demo", tool), "mcp__demo__" + tool)

    def test_long_name_is_cut_with_a_hash(self):
        """64자를 넘으면 앞 55자에 `_` 와 이름 전체의 SHA-256 앞 8자를 붙인다."""
        server, tool = "long-server-name", "tool." + "x" * 60
        full = "mcp__long_server_name__tool_" + "x" * 60
        self.assertGreater(len(full), 64)
        name = self.plugin._hermes_tool_name(server, tool)
        self.assertEqual(len(name), 64)
        self.assertEqual(name[:55], full[:55])
        self.assertRegex(name[55:], r"^_[0-9a-f]{8}$")
        self.assertEqual(name[56:], hashlib.sha256(full.encode("utf-8")).hexdigest()[:8])
        self.assertEqual(self.plugin._hermes_tool_name(server, tool), name)
        # 앞 55자가 같아도 뒤가 다르면 다른 이름이다.
        self.assertNotEqual(self.plugin._hermes_tool_name(server, tool + "y"), name)


if __name__ == "__main__":
    unittest.main()
