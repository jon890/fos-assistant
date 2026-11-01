"""`assistant.json` 이 없는 표준 플러그인을 카탈로그에 올리고 도구 목록으로 연결을 확인하는 규칙을 검사한다.

근거는 ADR-20261101 plugin-without-assistant-json 이다.
로컬 MCP 하나와 스킬을 둔 Claude Code 형식 fixture, 원격 MCP 하나와 스킬을 둔 Codex 형식 fixture 를 쓴다.
도구 목록 경로는 로컬 fixture 를 실제 자식 프로세스로, 원격 fixture 를 검사용 원격 서버로 띄워 부른다.
"""

import importlib.util
import json
import pathlib
import shutil
import sys
import types
import unittest

import test_connector_call as call_base
import test_connector_manifest as base

_REMOTE_FIXTURE = importlib.util.spec_from_file_location(
    "remote_mcp_server", pathlib.Path(__file__).resolve().parent / "fixtures/remote_mcp_server.py")
remote_mcp_server = importlib.util.module_from_spec(_REMOTE_FIXTURE)
_REMOTE_FIXTURE.loader.exec_module(remote_mcp_server)

FIXTURES = pathlib.Path(__file__).resolve().parent / "fixtures"
LOCAL = "standard-notes"
REMOTE = "standard-remote"
# 운영자가 믿은 주소다. 플러그인 파일의 주소는 무시한다.
OPERATOR_URL = "https://operator.test/mcp"
LOCAL_TOOLS = "/api/connectors/%s/tools" % LOCAL
REMOTE_TOOLS = "/api/connectors/%s/tools" % REMOTE
# 로그와 응답에 나오면 안 되는 칸 값이다.
LOCAL_TOKEN = "std_local_secret_0123456789"
REMOTE_TOKEN = "std_remote_secret_0123456789"


class StandardPluginCase(base.ConnectorGateCase):
    """로컬과 원격 표준 플러그인 fixture 를 운영 목록에 둔다."""

    def setUp(self):
        super().setUp()
        self.local_root = self.base / "standard-plugin"
        self.remote_root = self.base / "standard-remote-plugin"
        shutil.copytree(FIXTURES / "standard-plugin", self.local_root)
        shutil.copytree(FIXTURES / "standard-remote-plugin", self.remote_root)
        self.operator_list({LOCAL: {"root": str(self.local_root), "command": sys.executable},
                            REMOTE: {"root": str(self.remote_root), "url": OPERATOR_URL}})

    async def send(self, path, method, body=None, token="valid", include=()):
        """기반과 같되 `include` query 를 싣는다."""
        request = types.SimpleNamespace(
            url=types.SimpleNamespace(path=path), method=method, token=token, state=types.SimpleNamespace(),
            query_params=types.SimpleNamespace(getlist=lambda key: list(include) if key == "include" else []),
        )

        async def read_json():
            return body

        request.json = read_json

        async def call_next(current):
            return types.SimpleNamespace(status_code=401, body=b"null")

        response = await self.gate(request, call_next)
        return response.status_code, json.loads(response.body)

    def standard_catalog(self):
        import asyncio
        status, body = asyncio.run(self.send(base.CATALOG, "GET", include=("standard",)))
        self.assertEqual(status, 200)
        return {entry["id"]: entry for entry in body}


class StandardPluginCatalogTest(StandardPluginCase):
    def test_old_control_plane_does_not_see_standard_plugins(self):
        """query 없이 읽는 옛 Control Plane 에는 싣지 않는다. 옛 Control Plane 은 `verify.tool` 없는 항목 하나로 카탈로그를 버린다."""
        self.assertEqual(self.catalog(), [])
        self.assertEqual(set(self.standard_catalog()), {LOCAL, REMOTE})

    def test_local_plugin_reaches_the_catalog_with_inferred_fields_and_no_tool_policy(self):
        """로컬 표준 플러그인은 schema 3, 확인 도구 없음, 도구 정책 없음이고 칸은 MCP env 에서 추론한다."""
        entry = self.standard_catalog()[LOCAL]
        self.assertEqual(entry["schema"], 3)
        self.assertIsNone(entry["verify"])
        self.assertEqual(entry["tools"], {})
        self.assertEqual(entry["title"], "표준 메모")
        self.assertEqual(entry["mcp_server"], "std-notes")
        self.assertEqual(entry["skills"], ["standard-notes"])
        self.assertEqual(entry["fields"], [
            {"key": "std_token", "env": "STD_TOKEN", "label": "STD_TOKEN", "secret": True, "required": True},
            {"key": "std_region", "env": "STD_REGION", "label": "STD_REGION", "secret": True, "required": False},
        ])
        manifest = self.plugin._connector_manifest(LOCAL)
        # 늘 승인을 받는 선언이 없어 모델에게서 빼는 도구가 없다. 판정은 Control Plane 이 호출마다 한다.
        self.assertNotIn("tools", manifest["server"])
        self.assertEqual(manifest["server"]["env"], {"STD_TOKEN": "${STD_TOKEN}", "STD_REGION": "${STD_REGION}"})
        self.assertEqual(manifest["call_tools"], frozenset())
        # commands 와 hooks 는 manifest 에 있어도 읽지 않는다.
        self.assertNotIn("commands", manifest)
        self.assertNotIn("hooks", manifest)

    def test_remote_codex_plugin_uses_the_operator_url_and_header_fields(self):
        """Codex 형식만 있는 원격 표준 플러그인은 운영 목록의 주소로 붙고, 헤더 참조에서 필수 칸 하나를 만든다."""
        entry = self.standard_catalog()[REMOTE]
        self.assertEqual(entry["schema"], 3)
        self.assertIsNone(entry["verify"])
        self.assertEqual(entry["tools"], {})
        # Codex 의 `interface` 는 읽지 않는다. 제목은 이름이다.
        self.assertEqual(entry["title"], REMOTE)
        self.assertEqual(entry["skills"], ["remote-notes"])
        self.assertEqual(entry["fields"], [{"key": "std_remote_token", "env": "STD_REMOTE_TOKEN",
                                            "label": "STD_REMOTE_TOKEN", "secret": True, "required": True}])
        server = self.plugin._connector_manifest(REMOTE)["server"]
        self.assertEqual(server, {"url": OPERATOR_URL, "headers": {"Authorization": "Bearer ${STD_REMOTE_TOKEN}"},
                                  "strict_redirect_headers": True, "enabled": True})
        self.assertNotIn("plugin-file.invalid", json.dumps(self.standard_catalog()))

    def test_assistant_json_still_overrides(self):
        """`assistant.json` 이 생기면 그 선언으로 읽는다. schema 3 은 `assistant.json` 이 선언하지 못한다."""
        declared = {"schema": 2, "id": LOCAL, "title": "덮어쓴 제목",
                    "fields": [{"key": "token", "env": "STD_TOKEN", "label": "토큰", "secret": True},
                               {"key": "region", "env": "STD_REGION", "label": "지역", "required": False}],
                    "verify": {"tool": "list_notes"}, "tools": {"list_notes": {"risk": "READ"}}}
        (self.local_root / "assistant.json").write_text(json.dumps(declared), encoding="utf-8")
        entry = self.standard_catalog()[LOCAL]
        self.assertEqual((entry["schema"], entry["title"], entry["verify"]), (2, "덮어쓴 제목", {"tool": "list_notes"}))
        (self.local_root / "assistant.json").write_text(json.dumps({**declared, "schema": 3}), encoding="utf-8")
        self.assertNotIn(LOCAL, self.standard_catalog())

    def test_unsafe_standard_plugins_are_left_out(self):
        """리터럴 env 값, 다른 이름의 참조, 운영자 env, 칸 key 로 바꿀 수 없는 env 이름은 카탈로그에서 뺀다."""
        mcp = self.local_root / ".mcp.json"
        original = mcp.read_text(encoding="utf-8")
        for env in ({"STD_TOKEN": "literal-secret"}, {"STD_TOKEN": "${OTHER}"}, {"STD_TOKEN": "${STD_TOKEN:-x}"},
                    {"_HIDDEN": "${_HIDDEN}"}, {"PATH": "${PATH}"}):
            with self.subTest(env=env):
                value = json.loads(original)
                value["mcpServers"]["std-notes"]["env"] = env
                mcp.write_text(json.dumps(value), encoding="utf-8")
                self.assertNotIn(LOCAL, self.standard_catalog())
        mcp.write_text(original, encoding="utf-8")
        self.assertIn(LOCAL, self.standard_catalog())
        self.operator_list({LOCAL: {"root": str(self.local_root), "command": sys.executable,
                                    "env": {"STD_TOKEN": "operator"}}})
        self.assertEqual(self.standard_catalog(), {})

    def test_local_server_without_env_has_no_fields(self):
        """env 가 없는 로컬 서버는 칸 없이 오른다."""
        value = json.loads((self.local_root / ".mcp.json").read_text(encoding="utf-8"))
        del value["mcpServers"]["std-notes"]["env"]
        (self.local_root / ".mcp.json").write_text(json.dumps(value), encoding="utf-8")
        self.assertEqual(self.standard_catalog()[LOCAL]["fields"], [])


class PortablePluginCatalogTest(StandardPluginCase):
    """OpenAI 이식형 묶음도 기존 카탈로그·원격 검증을 거친다."""

    def setUp(self):
        super().setUp()
        self.portable_root = self.base / "portable-plugin"
        shutil.copytree(FIXTURES / "portable-plugin", self.portable_root)
        self.operator_list({"portable-notes": {"root": str(self.portable_root), "url": OPERATOR_URL}})

    def write_server(self, server):
        (self.portable_root / "mcp.json").write_text(json.dumps({"mcpServers": {"portable-notes": server}}),
                                                      encoding="utf-8")

    def server(self):
        return json.loads((FIXTURES / "portable-plugin/mcp.json").read_text())["mcpServers"]["portable-notes"]

    def test_portable_bundle_infers_bearer_and_ignores_ui_and_hooks(self):
        entry = self.standard_catalog()["portable-notes"]
        self.assertEqual((entry["schema"], entry["skills"], entry["verify"], entry["tools"]),
                         (3, ["portable-notes"], None, {}))
        self.assertEqual(entry["fields"], [{"key": "portable_notes_api_key", "env": "PORTABLE_NOTES_API_KEY",
                                          "label": "PORTABLE_NOTES_API_KEY", "secret": True, "required": True}])
        manifest = self.plugin._connector_manifest("portable-notes")
        self.assertEqual(manifest["transport"], "http")
        self.assertEqual(manifest["server"], {"url": OPERATOR_URL,
                         "headers": {"Authorization": "Bearer ${PORTABLE_NOTES_API_KEY}"},
                         "strict_redirect_headers": True, "enabled": True})
        self.assertNotIn("unused", json.dumps(entry))
        self.assertNotIn("plugin-file.invalid", json.dumps(manifest["server"]))
        self.assertEqual(self.catalog(), [])

    def test_custom_header_and_no_auth(self):
        for auth, headers in (({"type": "api_key", "headerScheme": "custom_header", "headerName": "X-Api-Key"},
                               {"X-Api-Key": "${PORTABLE_NOTES_API_KEY}"}), ({"type": "none"}, {}), (None, {})):
            with self.subTest(auth=auth):
                server = self.server()
                server["extensions"] = {"com.openai": {"auth": auth}} if auth else {}
                self.write_server(server)
                manifest = self.plugin._connector_manifest("portable-notes")
                self.assertEqual(manifest["server"]["headers"], headers)
                self.assertEqual(len(self.standard_catalog()["portable-notes"]["fields"]), len(headers))

    def test_unsupported_or_malformed_auth_is_left_out(self):
        for auth in ({"type": "oauth"}, {"type": "mixed"}, {"type": "api_key", "headerScheme": "basic"},
                     {"type": "api_key", "headerScheme": "unknown"}, {"type": "api_key"},
                     {"type": "api_key", "headerScheme": "custom_header"},
                     {"type": "api_key", "headerScheme": "custom_header", "headerName": "Host"},
                     {"type": "api_key", "headerScheme": "custom_header", "headerName": "bad\r\nheader"},
                     {"type": "api_key", "headerScheme": "bearer", "headerName": "X-Api-Key"},
                     {"type": "none", "clientSecret": "not-a-credential"}, None, [], "none"):
            with self.subTest(auth=auth):
                server = self.server()
                server["extensions"]["com.openai"]["auth"] = auth
                self.write_server(server)
                self.assertEqual(self.standard_catalog(), {})

    def test_duplicate_auth_header_and_global_env_are_left_out(self):
        server = self.server()
        server["headers"] = {"authorization": "Bearer ${OTHER_TOKEN}"}
        self.write_server(server)
        self.assertEqual(self.standard_catalog(), {})
        server = self.server()
        (self.portable_root / "mcp.json").write_text(json.dumps({"mcpServers": {"HERMES_TOKEN": server}}))
        self.assertEqual(self.standard_catalog(), {})

    def test_two_mcp_files_are_left_out_even_with_explicit_path(self):
        shutil.copyfile(self.portable_root / "mcp.json", self.portable_root / ".mcp.json")
        self.assertEqual(self.standard_catalog(), {})
        manifest = self.portable_root / "plugin.json"
        manifest.write_text(json.dumps({"name": "portable-notes", "mcpServers": "./mcp.json"}))
        self.assertEqual(self.standard_catalog(), {})

    def test_assistant_declaration_uses_normalized_auth_without_inference(self):
        declared = {"schema": 2, "id": "portable-notes", "title": "선언한 제목",
                    "fields": [{"key": "token", "env": "PORTABLE_NOTES_API_KEY", "label": "토큰", "secret": True}],
                    "verify": {"tool": "list_notes"}, "tools": {"list_notes": {"risk": "READ"}}}
        (self.portable_root / "assistant.json").write_text(json.dumps(declared), encoding="utf-8")
        entry = self.standard_catalog()["portable-notes"]
        self.assertEqual((entry["schema"], entry["title"], entry["fields"][0]["key"]), (2, "선언한 제목", "token"))
        server = self.server()
        server["extensions"]["com.openai"]["auth"] = {"type": "oauth"}
        self.write_server(server)
        self.assertEqual(self.standard_catalog(), {})

    def test_malformed_extensions_and_linked_files_are_left_out(self):
        for extensions in (None, [], {"com.openai": []}):
            with self.subTest(extensions=extensions):
                server = self.server()
                server["extensions"] = extensions
                self.write_server(server)
                self.assertEqual(self.standard_catalog(), {})
        for filename in ("mcp.json", "plugin.json"):
            with self.subTest(filename=filename):
                target = self.portable_root / filename
                original = (FIXTURES / "portable-plugin" / filename).read_text()
                outside = self.base / (filename + ".outside")
                outside.write_text(original)
                target.unlink()
                target.symlink_to(outside)
                self.assertEqual(self.standard_catalog(), {})
                target.unlink()
                target.write_text(original)

    def test_manifest_names_and_precedence(self):
        for dirname in (".codex-plugin", ".claude-plugin"):
            folder = self.portable_root / dirname
            folder.mkdir()
            manifest = folder / "plugin.json"
            manifest.write_text(json.dumps({"name": "other"}))
            self.assertEqual(self.standard_catalog(), {})
            manifest.write_text(json.dumps({"name": "portable-notes", "displayName": dirname}))
            expected = "portable-notes" if dirname == ".codex-plugin" else dirname
            self.assertEqual(self.standard_catalog()["portable-notes"]["title"], expected)


class StandardPluginToolListTest(StandardPluginCase):
    """`POST /api/connectors/<id>/tools` 는 `tools/list` 만 받아 도구 이름과 읽기 표시를 낸다."""

    def list_local(self):
        return self.request(LOCAL_TOOLS, "POST", {"values": {"std_token": LOCAL_TOKEN, "std_region": ""}})

    def test_local_tool_list_marks_only_read_only_hint_tools(self):
        """로컬 fixture 의 `list_notes` 만 읽기 표시가 있고 `delete_note` 는 없다. 값은 응답과 로그에 없다."""
        with call_base.collected_logs() as logs:
            status, body = self.list_local()
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"])
        self.assertEqual(sorted(body["tools"], key=lambda tool: tool["name"]),
                         [{"name": "delete_note", "read_only": False}, {"name": "list_notes", "read_only": True}])
        self.assertNotIn(LOCAL_TOKEN, json.dumps(body))
        self.assertNotIn(LOCAL_TOKEN, "\n".join(logs))

    def test_tool_list_refuses_declared_connectors_and_bad_bodies(self):
        """`assistant.json` 을 둔 플러그인(`schema: 2`)와 `tool` 을 실은 본문은 거절한다. 서버에 붙지 않는다."""
        declared = {"schema": 2, "id": LOCAL, "title": "덮어쓴 제목",
                    "fields": [{"key": "token", "env": "STD_TOKEN", "label": "토큰", "secret": True},
                               {"key": "region", "env": "STD_REGION", "label": "지역", "required": False}],
                    "verify": {"tool": "list_notes"}, "tools": {"list_notes": {"risk": "READ"}}}
        (self.local_root / "assistant.json").write_text(json.dumps(declared), encoding="utf-8")
        status, _ = self.request(LOCAL_TOOLS, "POST", {"values": {"token": LOCAL_TOKEN}})
        self.assertEqual(status, 400)
        (self.local_root / "assistant.json").unlink()
        status, _ = self.request(LOCAL_TOOLS, "POST", {"tool": "list_notes", "values": {"std_token": LOCAL_TOKEN}})
        self.assertEqual(status, 400)
        status, _ = self.request(LOCAL_TOOLS, "POST", {"values": {"std_token": LOCAL_TOKEN}}, token="wrong")
        self.assertEqual(status, 401)

    def test_unknown_field_is_invalid_input(self):
        """칸에 없는 key 는 서버에 붙기 전에 `invalid_input` 이다."""
        self.assertEqual(self.request(LOCAL_TOOLS, "POST", {"values": {"other": "x"}}),
                         (200, {"ok": False, "error": "invalid_input"}))

    def test_remote_tool_list_and_rejected_credentials(self):
        """원격 서버의 도구 목록을 받고, 401 로 답하는 서버는 `credential_rejected` 다. 토큰은 로그에 없다."""
        server_base = self.enterContext(remote_mcp_server.running_remote_server())
        body = {"values": {"std_remote_token": REMOTE_TOKEN}}
        with call_base.collected_logs() as logs:
            self.operator_list({REMOTE: {"root": str(self.remote_root), "url": server_base + "/mcp"}})
            status, answer = self.request(REMOTE_TOOLS, "POST", body)
            self.assertEqual(status, 200)
            self.assertEqual({tool["name"]: tool["read_only"] for tool in answer["tools"]},
                             {"list_items": True, "write_item": False, "hang_up": False})
            self.operator_list({REMOTE: {"root": str(self.remote_root), "url": server_base + "/denied"}})
            self.assertEqual(self.request(REMOTE_TOOLS, "POST", body),
                             (200, {"ok": False, "error": "credential_rejected"}))
            self.operator_list({REMOTE: {"root": str(self.remote_root), "url": server_base + "/moved"}})
            self.assertEqual(self.request(REMOTE_TOOLS, "POST", body), (200, {"ok": False, "error": "unavailable"}))
        # 도구 목록은 도구를 부르지 않는다.
        self.assertEqual(remote_mcp_server.received(server_base)["write_item"], 0)
        output = "\n".join(logs)
        self.assertNotIn(REMOTE_TOKEN, output)
        self.assertNotIn(server_base, output)


class StandardPluginExecuteTest(StandardPluginCase):
    """승인한 실행의 응답은 부르기 직전 `tools/list` 의 읽기 표시를 `read_only` 로 싣는다."""

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
        type(self).profile_root = self.base / "profiles"
        profile = self.profile_root / "alice"
        profile.mkdir(parents=True)
        (profile / ".env").write_text("STD_TOKEN=%s\n" % LOCAL_TOKEN, encoding="utf-8")
        (profile / self.plugin.MANAGED_MARKER).write_text("", encoding="utf-8")
        manifest = self.plugin._connector_manifest(LOCAL)
        entry = {"server": self.plugin._connector_server(manifest), "allowlist_added": True,
                 "mcp_server": manifest["mcp_server"], "mode": "bind", "vault": "c1", "skills": ["standard-notes"]}
        (profile / self.plugin.CONNECTOR_STATE).write_text(json.dumps({LOCAL: entry}), encoding="utf-8")

    def execute(self, tool):
        return self.request("/api/connectors/%s/execute" % LOCAL, "POST",
                            {"profile": "alice", "hermes_tool": "mcp__std_notes__" + tool, "args": {}})

    def test_execute_answer_carries_the_read_only_hint_of_the_called_tool(self):
        """읽기 표시가 있는 `list_notes` 는 참, 없는 `delete_note` 는 거짓이다."""
        status, body = self.execute("list_notes")
        self.assertEqual(status, 200, body)
        self.assertEqual((body["ok"], body["read_only"]), (True, True))
        status, body = self.execute("delete_note")
        self.assertEqual(status, 200, body)
        self.assertEqual(body, {"ok": True, "result": {"deleted": ""}, "read_only": False})


if __name__ == "__main__":
    unittest.main()
