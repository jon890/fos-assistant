"""dashboard-profile-api 가 `connector.json` 을 읽어 카탈로그로 내는 규칙을 검사한다(ADR-043)."""

import asyncio
import hashlib
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
        self.assertEqual(set(entry), {"id", "schema", "title", "description", "fields", "verify", "mcp_server",
                                      "toolsets", "attachments", "tools"})
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
        self.assertEqual(entry["tools"], {"list_scopes": {"risk": "READ", "approval": "none", "grant": False}})

    def test_schema_two_fills_the_default_approval(self):
        """`approval` 을 적지 않은 도구는 그 위험도의 기본값으로 채워 낸다. 제목이 없으면 `title` 을 내지 않는다."""
        self.declare()
        entry = self.catalog()[0]
        self.assertEqual(entry["schema"], 2)
        self.assertEqual(entry["tools"], {
            "list_scopes": {"risk": "READ", "approval": "none", "grant": False},
            "env_view": {"risk": "READ", "approval": "none", "grant": False},
            "write_note": {"risk": "WRITE", "approval": "required", "grant": True},
        })

    def test_default_approval_of_every_risk(self):
        """위험도 다섯의 기본 `approval` 은 계약의 표와 같다."""
        expected = {"READ": "none", "SENSITIVE": "required", "WRITE": "required",
                    "DESTRUCTIVE": "always", "FINANCIAL": "always"}
        for risk, approval in expected.items():
            with self.subTest(risk):
                self.declare(lambda tools: tools.update(probe={"risk": risk}))
                self.assertEqual(self.catalog()[0]["tools"]["probe"],
                                 {"risk": risk, "approval": approval, "grant": approval == "required"})

    def test_stricter_approval_and_title_reach_the_catalog(self):
        """하한보다 엄격한 `approval` 과 사람 말 제목은 선언한 그대로 낸다."""
        self.declare(lambda tools: tools.update(
            write_note={"risk": "WRITE", "approval": "always", "title": "메모 쓰기"},
            env_view={"risk": "READ", "approval": "required"}))
        tools = self.catalog()[0]["tools"]
        self.assertEqual(tools["write_note"],
                         {"risk": "WRITE", "approval": "always", "title": "메모 쓰기", "grant": False})
        self.assertEqual(tools["env_view"], {"risk": "READ", "approval": "required", "grant": True})

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
                         {"risk": "DESTRUCTIVE", "approval": "always", "grant": False})
        self.declare(lambda tools: tools.update(purge={"risk": "DESTRUCTIVE", "approval": "required"}))
        self.assertEqual(self.catalog(), [])

    def test_closed_grant_keeps_the_tool_visible_to_the_model(self):
        """`"grant": false` 인 도구는 `grant` 만 거짓으로 나오고 서버 정의의 `tools.exclude` 에 들지 않는다(ADR-060)."""
        self.declare(lambda tools: tools.update(send_note={"risk": "WRITE", "grant": False}))
        tools = self.catalog()[0]["tools"]
        self.assertEqual(tools["send_note"], {"risk": "WRITE", "approval": "required", "grant": False})
        # 선언하지 않은 쓰기 도구는 상시 허락을 줄 수 있고, 승인이 없는 읽기 도구는 줄 것이 없다.
        self.assertIs(tools["write_note"]["grant"], True)
        self.assertIs(tools["env_view"]["grant"], False)
        self.assertNotIn("tools", self.plugin._connector_manifest(DEMO)["server"])

    def test_explicit_open_grant_is_the_same_as_no_declaration(self):
        """`"grant": true` 는 선언하지 않은 것과 같은 값을 낸다."""
        self.declare(lambda tools: tools.update(write_note={"risk": "WRITE", "grant": True}))
        self.assertEqual(self.catalog()[0]["tools"]["write_note"],
                         {"risk": "WRITE", "approval": "required", "grant": True})

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
