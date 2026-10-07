"""dashboard-profile-api 의 connector_install_security 분기를 검사한다."""

import json
import sys
import types
from unittest import mock
import yaml
from plugin_loading import patch_plugin
from dashboard_profile_api_support import DEMO, DEMO_BASE
import dashboard_profile_api_support as support


class ProfileApiConnectorInstallSecurityTest(support.ProfileApiRouteTest):
    def test_connector_install_writes_skill_body_as_persona(self):
        """설치는 스킬 본문을 앞머리 없이 그 profile 의 SOUL.md 에 쓰고, 본문이 바뀌면 다시 설치할 때 다시 쓴다."""
        root = self.connector_fixture()
        soul = self.root / "alice/SOUL.md"
        soul.write_text("기본 성격\n", encoding="utf-8")
        other = self.root / "bob/SOUL.md"
        self.make_profile("bob")
        other.write_text("bob 의 성격\n", encoding="utf-8")
        self.assertEqual(self.connector().status_code, 200)
        body = soul.read_text(encoding="utf-8")
        self.assertEqual(body, "# 검사용 메모\n\n`list_scopes` 로 볼 수 있는 범위를 조회한다.\n")
        self.assertNotIn("description:", body)
        # 다른 profile 의 지침은 그대로다.
        self.assertEqual(other.read_text(encoding="utf-8"), "bob 의 성격\n")
        # plugin 의 스킬 본문이 바뀌면 같은 설치 요청이 지침을 다시 쓴다. 서버 정의는 그대로다.
        skill = root / "skills/demo/SKILL.md"
        skill.write_text(skill.read_text(encoding="utf-8") + "\n새 절차다.\n", encoding="utf-8")
        config_before = (self.root / "alice/config.yaml").read_bytes()
        repeated = self.connector()
        self.assertEqual(repeated.status_code, 200)
        self.assertTrue(repeated.body["changed"])
        self.assertTrue(soul.read_text(encoding="utf-8").endswith("새 절차다.\n"))
        self.assertEqual((self.root / "alice/config.yaml").read_bytes(), config_before)
        self.assertFalse(self.connector().body["changed"])
        # 해제는 지침을 지우지 않는다. 에이전트가 꺼지고 다시 등록하면 다시 쓴다.
        self.assertEqual(self.connector(False).status_code, 200)
        self.assertTrue(soul.read_text(encoding="utf-8").endswith("새 절차다.\n"))

    def test_connector_persona_is_rolled_back_and_kept_off_unmanaged_profiles(self):
        """지침 쓰기가 실패하면 설정과 기록을 되돌리고, 관리 표식이 없는 profile 에는 쓰지 않는다."""
        self.connector_fixture()
        soul = self.root / "alice/SOUL.md"
        soul.write_text("기본 성격\n", encoding="utf-8")
        before = (self.root / "alice/config.yaml").read_bytes()
        write = self.plugin._atomic_private_write

        def failing_write(target, value):
            if target.name == "SOUL.md" and target.parent.name == "alice":
                raise OSError("injected")
            return write(target, value)

        with patch_plugin(self.plugin, "_atomic_private_write", side_effect=failing_write):
            self.assertEqual(self.connector().status_code, 503)
        self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)
        self.assertEqual(soul.read_text(encoding="utf-8"), "기본 성격\n")
        self.assertFalse((self.root / "alice" / self.plugin.CONNECTOR_STATE).exists())
        # 요청 경로는 표식을 먼저 본다. 함수만 불러도 표식 없는 profile 의 지침은 바뀌지 않는다.
        (self.root / "alice" / self.plugin.MANAGED_MARKER).unlink()
        self.assertEqual(self.connector().status_code, 401)
        with self.assertRaises(ValueError):
            self.plugin._connector_config(self.root / "alice", DEMO, True)
        self.assertEqual(soul.read_text(encoding="utf-8"), "기본 성격\n")

    def test_connector_uses_operator_command_and_rejects_unsafe_manifests(self):
        """manifest 의 명령은 운영 실행 파일로 바뀌고, 치환 있는 인자와 비밀값 원문과 plugin 밖 링크는 거절한다."""
        root = self.connector_fixture()
        manifest = root / ".mcp.json"
        original = manifest.read_text()
        before = (self.root / "alice/config.yaml").read_bytes()
        for label, mutate in (
            ("shell expansion", lambda server: server.update(args=["-c", "echo $HOME"])),
            ("outside plugin", lambda server: server.update(args=["${CLAUDE_PLUGIN_ROOT}/../bin/connector-runner"])),
            ("missing file", lambda server: server.update(args=["${CLAUDE_PLUGIN_ROOT}/missing.py"])),
            ("literal env", lambda server: server["env"].update(DEMO_TOKEN="literal")),
            ("other reference", lambda server: server["env"].update(DEMO_TOKEN="${DEMO_BASE}")),
            ("undeclared env", lambda server: server["env"].update(OTHER="${OTHER}")),
            ("extra field", lambda server: server.update(cwd="/")),
        ):
            with self.subTest(label):
                value = json.loads(original)
                mutate(value["mcpServers"]["demo"])
                manifest.write_text(json.dumps(value), encoding="utf-8")
                self.assertEqual(self.connector().status_code, 503)
                self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)
        value = json.loads(original)
        value["mcpServers"]["demo"]["command"] = "sh"
        manifest.write_text(json.dumps(value), encoding="utf-8")
        self.assertEqual(self.connector().status_code, 200)
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["command"], self.connector_command)
        self.assertEqual(self.connector(False).status_code, 200)
        script = root / "server.py"
        script.unlink()
        script.symlink_to(self.root / "alice/config.yaml")
        self.assertEqual(self.connector().status_code, 503)

    def test_connector_routes_reject_unmanaged_profile_unknown_plugin_and_extra_fields(self):
        """관리 표식 없는 profile 과 임의 설치 요청을 거절하고 쿠키로 설치하지 않는다."""
        self.connector_fixture()
        self.assertEqual(self.connector(profile="default").status_code, 400)
        self.assertEqual(self.connector(profile="missing").status_code, 404)
        self.assertEqual(self.connector(profile="owner").status_code, 401)
        self.assertEqual(self.connector(plugin="unknown").status_code, 400)
        # 운영 목록에도 소유 기록에도 없는 이름을 끄는 것은 끌 것이 없어 성공이다. 설정은 그대로다.
        before = (self.root / "alice/config.yaml").read_bytes()
        unknown = self.connector(False, plugin="unknown")
        self.assertEqual(unknown.status_code, 200)
        self.assertEqual(unknown.body, {"profile": "alice", "plugin": "unknown", "enabled": False,
                                        "changed": False, "restart_required": False,
                                        "plugin_updated": False})
        self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)
        self.assertFalse((self.root / "alice/.fos-connectors.json").exists())
        self.assertEqual(self.connector_status().body["connectors"],
                         [{"plugin": DEMO, "enabled": False, "configured": False, "mode": "isolated"}])
        # 이름 형식 검사와 관리 표식 검사는 끄기에도 그대로 걸린다.
        self.assertEqual(self.connector(False, plugin="unknown", profile="owner").status_code, 401)
        self.assertEqual(self.connector(False, plugin="../demo-notes").status_code, 400)
        self.assertEqual(self.connector(command="sh").status_code, 400)
        self.assertEqual(self.request("/api/connectors", "PUT", cookie=True), 401)
        self.assertEqual(self.request("/api/connectors", "GET", token="valid"), 400)

    def test_connector_keeps_operator_definition_and_rolls_back_write_failure(self):
        """운영자 MCP 정의를 덮어쓰지 않고 저장 실패 시 config 와 env 를 되돌린다."""
        self.connector_fixture()
        path = self.root / "alice/config.yaml"
        config = yaml.safe_load(path.read_text())
        config["mcp_servers"]["demo"] = {"command": "operator"}
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        self.assertEqual(self.connector().status_code, 409)
        del config["mcp_servers"]["demo"]
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        before = path.read_bytes()
        write = self.plugin._atomic_private_write
        failed = False
        def failing_write(target, value):
            nonlocal failed
            if target.name == self.plugin.CONNECTOR_STATE and not failed:
                failed = True
                raise OSError("injected")
            return write(target, value)
        with patch_plugin(self.plugin, "_atomic_private_write", side_effect=failing_write):
            self.assertEqual(self.connector().status_code, 503)
        self.assertEqual(path.read_bytes(), before)
        self.assertFalse((self.root / "alice/.env").exists())
        self.assertFalse((self.root / "alice" / self.plugin.CONNECTOR_STATE).exists())

    def test_connector_status_and_probe_are_limited_to_installed_managed_profile(self):
        """상태 조회는 비밀값을 내지 않고 probe 는 그 profile 에 설치한 커넥터의 서버만 부른다."""
        self.connector_fixture()
        self.assertEqual(self.connector_probe(), 404)
        self.connector()
        response = self.connector_status()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.body["connectors"], [{"plugin": DEMO, "enabled": True, "configured": True, "mode": "isolated"}])
        self.assertNotIn(DEMO_BASE, json.dumps(response.body))
        self.assertEqual(self.connector_probe(), 204)
        # 설치한 커넥터의 서버가 아니면 등록된 MCP 서버여도 넘기지 않는다.
        self.assertEqual(self.connector_probe("fos-assistant"), 404)
        self.assertEqual(self.connector_probe("other"), 404)
        self.assertEqual(self.connector_probe(profile="owner"), 401)
        self.assertEqual(self.request("/api/mcp/servers/demo/test", "POST", token="valid"), 400)
        self.assertEqual(self.request("/api/mcp/servers/demo/test", "GET", token="valid", query_profiles=["alice"]), 401)
        self.assertEqual(self.request("/api/mcp/servers/demo/nested/test", "POST", token="valid",
                                      query_profiles=["alice"]), 401)
        self.connector(False)
        self.assertEqual(self.connector_probe(), 404)

    def test_connector_probe_rejects_operator_changes_and_other_token_providers(self):
        """변조한 MCP 명령과 다른 provider 의 유효한 토큰으로 probe 를 실행하지 않는다."""
        self.connector_fixture()
        self.connector()
        path = self.root / "alice/config.yaml"
        config = yaml.safe_load(path.read_text())
        config["mcp_servers"]["demo"]["command"] = "sh"
        path.write_text(yaml.safe_dump(config))
        self.assertEqual(self.connector_probe(), 409)
        seam = sys.modules["hermes_cli.dashboard_auth.token_auth"]
        with mock.patch.object(seam, "authenticate_token", return_value=(types.SimpleNamespace(provider="other"), None)):
            self.assertEqual(self.connector_probe(), 401)
            self.assertEqual(self.request("/api/env", "DELETE", token="valid", body={"profile": "alice", "key": "DEMO_TOKEN"}), 401)

    def test_connector_preserves_external_edits(self):
        """외부에서 바꾼 환경 변수를 복원으로 덮지 않는다."""
        self.connector_fixture()
        env = self.root / "alice/.env"
        write = self.plugin._atomic_private_write
        def external_edit(target, value):
            write(target, value)
            if "connector-backups" in target.parts and target.name == "config.yaml":
                env.write_text("OTHER=external\n", encoding="utf-8")
        with patch_plugin(self.plugin, "_atomic_private_write", side_effect=external_edit):
            self.assertEqual(self.connector().status_code, 409)
        self.assertEqual(env.read_text(), "OTHER=external\n")

    def test_connector_rejects_corrupted_ownership_record(self):
        """소유 기록이 변조한 명령 또는 boolean 이 아닌 필드이면 제거하지 않는다."""
        self.connector_fixture()
        self.connector()
        path = self.root / "alice" / self.plugin.CONNECTOR_STATE
        original = json.loads(path.read_text())
        changed_args = {**original[DEMO]["server"], "args": ["/elsewhere/server.py"]}
        literal_env = {**original[DEMO]["server"],
                       "env": {**original[DEMO]["server"]["env"], "DEMO_TOKEN": "literal"}}
        for field, value in (("allowlist_added", "yes"), ("server", {"command": "sh"}), ("server", changed_args),
                             ("server", literal_env), ("mcp_server", "fos-assistant")):
            with self.subTest(field=field, value=value):
                state = json.loads(json.dumps(original))
                state[DEMO][field] = value
                path.write_text(json.dumps(state))
                self.assertEqual(self.connector(False).status_code, 503)
                self.assertEqual(self.connector_status().status_code, 503)
        self.assertIn("demo", self.alice_config()["mcp_servers"])

    def test_connector_accepts_direct_server_manifest_and_plain_optional_reference(self):
        """`mcpServers` 없이 서버를 바로 둔 `.mcp.json` 과 기본값 없는 선택 칸 참조도 같은 정의가 된다."""
        root = self.connector_fixture()
        path = root / ".mcp.json"
        value = json.loads(path.read_text())["mcpServers"]
        value["demo"]["env"]["DEMO_SCOPE"] = "${DEMO_SCOPE}"
        path.write_text(json.dumps(value))
        self.assertEqual(self.connector().status_code, 200)
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"]["DEMO_SCOPE"], "")
