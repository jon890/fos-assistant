"""dashboard-profile-api 의 connector_install_lifecycle 분기를 검사한다."""

import json
import os
import yaml
from dashboard_profile_api_support import DEMO, DEMO_BASE
import dashboard_profile_api_support as support


class ProfileApiConnectorInstallLifecycleTest(support.ProfileApiRouteTest):
    def test_connector_install_is_rejected_without_environment(self):
        """두 환경 변수가 없으면 알려진 커넥터가 없어 설치 요청이 400 이고 설정은 그대로다."""
        self.connector_fixture()
        before = (self.root / "alice/config.yaml").read_bytes()
        with self.without_environment("FOS_ASSISTANT_CONNECTOR_ROOTS", "FOS_ASSISTANT_CONNECTOR_COMMAND"):
            self.assertEqual(self.connector().status_code, 400)
            status = self.connector_status()
            self.assertEqual(status.status_code, 200)
            self.assertEqual(status.body["connectors"], [])
        self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)
        self.assertFalse((self.root / "alice/.fos-connectors.json").exists())

    def test_connector_install_fails_without_command(self):
        """경로만 받고 실행 파일을 받지 못하면 설치가 503 이고 설정은 그대로다."""
        self.connector_fixture()
        before = (self.root / "alice/config.yaml").read_bytes()
        for label, value in (("missing", None), ("relative", "bin/connector-runner")):
            with self.subTest(label), self.without_environment("FOS_ASSISTANT_CONNECTOR_COMMAND"):
                if value is not None:
                    os.environ["FOS_ASSISTANT_CONNECTOR_COMMAND"] = value
                self.assertEqual(self.connector().status_code, 503)
                self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)
                self.assertFalse((self.root / "alice/.fos-connectors.json").exists())

    def test_installed_connector_is_blocked_when_command_is_missing_or_changed(self):
        """설치한 뒤 실행 파일이 빠지거나 바뀌면 상태 조회, 제거, probe 가 막히고, 값이 돌아오면 다시 읽힌다."""
        self.connector_fixture()
        self.assertEqual(self.connector().status_code, 200)
        self.assertEqual(self.connector_probe(), 204)
        before = (self.root / "alice/config.yaml").read_bytes()
        with self.without_environment("FOS_ASSISTANT_CONNECTOR_COMMAND"):
            self.assertEqual(self.connector_status().status_code, 503)
            self.assertEqual(self.connector(False).status_code, 503)
            self.assertEqual(self.connector_probe(), 503)
            os.environ["FOS_ASSISTANT_CONNECTOR_COMMAND"] = self.connector_command + "-other"
            self.assertEqual(self.connector_status().status_code, 503)
            self.assertEqual(self.connector(False).status_code, 503)
            self.assertEqual(self.connector_probe(), 503)
        self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)
        self.assertEqual(self.connector_probe(), 204)
        status = self.connector_status()
        self.assertEqual(status.status_code, 200)
        self.assertEqual(status.body["connectors"], [{"plugin": DEMO, "enabled": True, "configured": True, "mode": "isolated"}])
        self.assertEqual(self.connector(False).status_code, 200)

    def test_connector_removed_from_operator_list_can_only_be_turned_off(self):
        """운영 목록에서 빠진 커넥터는 소유 기록으로 설치를 끄고 그 기록이 참조하던 env key 를 지운다."""
        self.connector_fixture()
        env = self.root / "alice/.env"
        env.write_text("DEMO_TOKEN=demo_ok_0123456789\nOTHER=keep\nDEMO_SCOPE=a\n"
                       "MCP_FOS_ASSISTANT_API_KEY=keep-too\n", encoding="utf-8")
        self.assertEqual(self.connector().status_code, 200)
        with self.without_environment("FOS_ASSISTANT_CONNECTOR_ROOTS"):
            status = self.connector_status()
            self.assertEqual(status.status_code, 200)
            self.assertEqual(status.body["connectors"], [{"plugin": DEMO, "enabled": True, "configured": False, "mode": "isolated"}])
            self.assertEqual(self.connector_probe(), 404)
            self.assertEqual(self.connector(True).status_code, 400)
            self.assertIn("demo", self.alice_config()["mcp_servers"])

            removed = self.connector(False)
            self.assertEqual(removed.status_code, 200)
            self.assertTrue(removed.body["changed"])
            self.assertTrue(removed.body["restart_required"])
            config = self.alice_config()
            self.assertNotIn("demo", config["mcp_servers"])
            self.assertEqual(config["platform_toolsets"]["api_server"], ["no_mcp"])
            # 설치가 지운 Control Plane MCP 등록은 제거해도 되살아나지 않는다.
            self.assertNotIn("fos-assistant", config["mcp_servers"])
            self.assertEqual(env.read_text(encoding="utf-8"), "OTHER=keep\nMCP_FOS_ASSISTANT_API_KEY=keep-too\n")
            self.assertEqual(json.loads((self.root / "alice/.fos-connectors.json").read_text()), {})
            self.assertEqual(self.connector_status().body["connectors"], [])
            # 기록이 사라진 뒤에는 끌 것이 없다. 바꾸지 않고 성공으로 답하고 설치는 거절한다.
            before = {path.name: path.read_bytes() for path in (self.root / "alice").iterdir() if path.is_file()}
            again = self.connector(False)
            self.assertEqual(again.status_code, 200)
            self.assertFalse(again.body["changed"])
            self.assertFalse(again.body["restart_required"])
            self.assertEqual(self.connector(True).status_code, 400)
            after = {path.name: path.read_bytes() for path in (self.root / "alice").iterdir() if path.is_file()}
            self.assertEqual(after, before)

    def test_connector_installs_idempotently_and_preserves_profile_secrets(self):
        """connector 는 manifest 에서 등록하고 도구 목록을 그 서버만으로 쓰며 profile 의 비밀값을 보존한다."""
        root = self.connector_fixture()
        env = self.root / "alice/.env"
        env.write_text("DEMO_TOKEN=test-token\nOTHER=keep\nMCP_FOS_ASSISTANT_API_KEY=keep-too\n", encoding="utf-8")
        env.chmod(0o600)
        self.assertIn("fos-assistant", self.alice_config()["mcp_servers"])
        response = self.connector()
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.body["changed"])
        self.assertFalse(response.body["restart_required"])
        config = self.alice_config()
        server = config["mcp_servers"]["demo"]
        # 실행 파일은 manifest 의 것이 아니라 운영이 준 것이고, 인자는 plugin 안의 실제 경로다.
        self.assertEqual(server["command"], self.connector_command)
        self.assertEqual(server["args"], [str(root / "server.py")])
        self.assertEqual(server["env"], {"DEMO_TOKEN": "${DEMO_TOKEN}", "DEMO_SCOPE": "", "DEMO_BASE": DEMO_BASE})
        self.assertEqual(config["platform_toolsets"]["api_server"], ["demo"])
        self.assertNotIn("fos-assistant", config["mcp_servers"])
        record = json.loads((self.root / "alice/.fos-connectors.json").read_text())
        self.assertEqual(record, {DEMO: {"server": server, "allowlist_added": True, "mcp_server": "demo"}})
        self.assertIn("DEMO_TOKEN=test-token", env.read_text())
        self.assertIn("MCP_FOS_ASSISTANT_API_KEY=keep-too", env.read_text().splitlines())
        self.assertEqual(env.stat().st_mode & 0o777, 0o600)
        backups = sorted(path.name for path in (self.root / "alice/connector-backups").glob("*/*"))
        # 백업에는 설정만 있다. 사용자의 비밀 원문이 있는 `.env` 는 뜨지 않는다.
        self.assertEqual(backups, ["config.yaml"])
        for backup in (self.root / "alice/connector-backups").glob("*/*"):
            self.assertEqual(backup.stat().st_mode & 0o777, 0o600)
        repeated = self.connector()
        self.assertFalse(repeated.body["changed"])
        self.assertTrue(repeated.body["restart_required"])
        removed = self.connector(False)
        self.assertEqual(removed.status_code, 200)
        self.assertTrue(removed.body["restart_required"])
        config = self.alice_config()
        self.assertNotIn("demo", config["mcp_servers"])
        self.assertEqual(config["platform_toolsets"]["api_server"], ["no_mcp"])
        self.assertNotIn("fos-assistant", config["mcp_servers"])
        self.assertEqual(list((self.root / "alice/connector-backups").glob("*/.env")), [])
        # 운영 목록에 있는 커넥터의 env 는 Control Plane 이 칸마다 지운다. 설치를 끄는 것이 지우지 않는다.
        self.assertIn("DEMO_TOKEN=test-token", env.read_text())

    def test_connector_uninstall_leaves_no_secret_on_disk(self):
        """비밀값을 넣고 설치한 뒤 해제하면 그 원문이 profile 디렉터리의 어느 파일에도 남지 않는다."""
        self.connector_fixture()
        secret = "demo_secret_value_0123456789"
        self.assertEqual(self.request("/api/env", "PUT", token="valid", body={
            "profile": "alice", "key": "DEMO_TOKEN", "value": secret}), 200)
        self.assertEqual(self.connector().status_code, 200)
        self.assertEqual(self.request("/api/env", "DELETE", token="valid", body={
            "profile": "alice", "key": "DEMO_TOKEN"}), 200)
        self.assertEqual(self.connector(False).status_code, 200)
        files = [path for path in (self.root / "alice").rglob("*") if path.is_file()]
        self.assertIn(self.root / "alice/config.yaml", files)
        holding = [str(path.relative_to(self.root)) for path in files if secret.encode() in path.read_bytes()]
        self.assertEqual(holding, [], "비밀 원문이 남은 파일이 있다")
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["no_mcp"])

    def test_connector_install_rewrites_old_allowlist(self):
        """앞선 판이 쓴 목록과 Control Plane MCP 등록이 남은 profile 은 설치가 덜 된 것으로 보고 다시 쓴다."""
        self.connector_fixture()
        control_plane = self.alice_config()["mcp_servers"]["fos-assistant"]
        self.assertEqual(self.connector().status_code, 200)
        path = self.root / "alice/config.yaml"
        config = self.alice_config()
        config["mcp_servers"]["fos-assistant"] = control_plane
        config["platform_toolsets"]["api_server"] = ["fos-assistant", "demo"]
        path.write_text(yaml.safe_dump(config, sort_keys=False, allow_unicode=True), encoding="utf-8")
        self.assertEqual(self.connector_status().body["connectors"],
                         [{"plugin": DEMO, "enabled": True, "configured": False, "mode": "isolated"}])
        installed = self.connector()
        self.assertEqual(installed.status_code, 200)
        self.assertTrue(installed.body["changed"])
        self.assertTrue(installed.body["restart_required"])
        config = self.alice_config()
        self.assertEqual(config["platform_toolsets"]["api_server"], ["demo"])
        self.assertNotIn("fos-assistant", config["mcp_servers"])
        self.assertEqual(self.connector_status().body["connectors"],
                         [{"plugin": DEMO, "enabled": True, "configured": True, "mode": "isolated"}])

    def test_connector_install_works_without_control_plane_mcp_in_allowlist(self):
        """목록에 Control Plane MCP 가 없는 관리 profile 에도 설치하고 목록을 커넥터 서버만으로 쓴다."""
        self.connector_fixture()
        path = self.root / "alice/config.yaml"
        config = self.alice_config()
        config["platform_toolsets"]["api_server"] = ["delegation"]
        path.write_text(yaml.safe_dump(config, sort_keys=False, allow_unicode=True), encoding="utf-8")
        response = self.connector()
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.body["changed"])
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["demo"])

    def test_turning_off_a_connector_that_is_not_installed_keeps_the_allowlist(self):
        """설치하지 않은 커넥터를 꺼도 일반 profile 의 도구 목록과 Control Plane MCP 등록은 그대로다."""
        self.connector_fixture()
        before = self.alice_config()
        response = self.connector(False)
        self.assertEqual(response.status_code, 200)
        self.assertFalse(response.body["restart_required"])
        after = self.alice_config()
        self.assertEqual(after["platform_toolsets"]["api_server"], before["platform_toolsets"]["api_server"])
        self.assertIn("fos-assistant", after["platform_toolsets"]["api_server"])
        self.assertIn("fos-assistant", after["mcp_servers"])

    def test_connector_allowlist_holds_a_name_shared_by_server_and_toolset_once(self):
        """서버 이름과 선언한 toolset 이름이 같으면 목록에 그 이름을 한 번만 싣는다."""
        root = self.connector_fixture()
        manifest = root / "connector.json"
        declared = json.loads(manifest.read_text(encoding="utf-8"))
        declared["toolsets"] = ["vision"]
        manifest.write_text(json.dumps(declared), encoding="utf-8")
        mcp_path = root / ".mcp.json"
        mcp = json.loads(mcp_path.read_text(encoding="utf-8"))
        servers = mcp["mcpServers"] if "mcpServers" in mcp else mcp
        servers["vision"] = servers.pop(next(iter(servers)))
        mcp_path.write_text(json.dumps(mcp), encoding="utf-8")
        self.assertEqual(self.connector().status_code, 200)
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["vision"])

    def test_connector_install_adds_declared_toolsets_after_the_server_name(self):
        """manifest 가 `toolsets` 를 선언하면 목록은 서버 이름 다음에 그 toolset 이고 Control Plane MCP 는 없다."""
        root = self.connector_fixture()
        manifest = root / "connector.json"
        declared = json.loads(manifest.read_text(encoding="utf-8"))
        declared["toolsets"] = ["vision"]
        manifest.write_text(json.dumps(declared), encoding="utf-8")
        self.assertEqual(self.connector().status_code, 200)
        config = self.alice_config()
        self.assertEqual(config["platform_toolsets"]["api_server"], ["demo", "vision"])
        self.assertNotIn("fos-assistant", config["mcp_servers"])
        self.assertEqual(self.connector_status().body["connectors"],
                         [{"plugin": DEMO, "enabled": True, "configured": True, "mode": "isolated"}])
        # 선언이 바뀌면 같은 목록이 아니라 설치가 덜 된 것으로 보고, 다시 설치하면 새 목록이 된다.
        del declared["toolsets"]
        manifest.write_text(json.dumps(declared), encoding="utf-8")
        self.assertEqual(self.connector_status().body["connectors"],
                         [{"plugin": DEMO, "enabled": True, "configured": False, "mode": "isolated"}])
        self.assertEqual(self.connector().status_code, 200)
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["demo"])
        # 마지막 커넥터를 끄면 선언한 toolset 도 남지 않는다.
        declared["toolsets"] = ["vision"]
        manifest.write_text(json.dumps(declared), encoding="utf-8")
        self.assertEqual(self.connector().status_code, 200)
        self.assertEqual(self.connector(False).status_code, 200)
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["no_mcp"])
