"""dashboard-profile-api 의 connector_install_legacy 분기를 검사한다."""

import json
from dashboard_profile_api_support import LEGACY, LEGACY_BASE
import dashboard_profile_api_support as support


class ProfileApiConnectorInstallLegacyTest(support.ProfileApiRouteTest):
    def test_legacy_ownership_record_and_calls_are_still_accepted(self):
        """앞선 판이 남긴 소유 기록을 그대로 인정하고, 앞선 Control Plane 이 부르는 모양을 그대로 받는다."""
        self.legacy_fixture()
        env = self.root / "alice/.env"
        record_path = self.root / "alice/.fos-connectors.json"
        record_before = record_path.read_bytes()

        # 앞선 판의 목록에는 Control Plane MCP 가 함께 있다. 다시 설치하기 전에는 설치가 덜 된 것이다.
        status = self.connector_status()
        self.assertEqual(status.status_code, 200)
        self.assertEqual(status.body["connectors"], [{"plugin": LEGACY, "enabled": True, "configured": False, "mode": "isolated"}])
        self.assertEqual(self.connector_probe("accountbook"), 204)
        self.assertEqual(record_path.read_bytes(), record_before)

        # 앞선 Control Plane 은 공통 주소도 PUT /api/env 로 쓴다. 성공으로 답하고 쓰지 않는다.
        env_before = env.read_bytes()
        response = self.request("/api/env", "PUT", token="valid", full_response=True, body={
            "profile": "alice", "key": "ACCOUNTBOOK_API_BASE_URL", "value": "http://changed.test"})
        self.assertEqual(response.status_code, 200)
        self.assertFalse(response.body["restart_required"])
        self.assertEqual(env.read_bytes(), env_before)
        response = self.request("/api/env", "PUT", token="valid", full_response=True, body={
            "profile": "alice", "key": "ACCOUNTBOOK_API_TOKEN", "value": "legacy-token-2"})
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.body["restart_required"])

        # 다음 설치 요청이 기록을 새 모양으로 다시 쓴다. 운영자 env 는 운영 목록의 값이 직접 들어간다.
        installed = self.connector(plugin=LEGACY)
        self.assertEqual(installed.status_code, 200)
        self.assertTrue(installed.body["changed"])
        self.assertTrue(installed.body["restart_required"])
        record = json.loads(record_path.read_text())[LEGACY]
        self.assertEqual(record["mcp_server"], "accountbook")
        self.assertIs(record["allowlist_added"], True)
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["accountbook"])
        self.assertNotIn("fos-assistant", self.alice_config()["mcp_servers"])
        self.assertEqual(record["server"]["env"], {"ACCOUNTBOOK_API_BASE_URL": LEGACY_BASE,
                                                   "ACCOUNTBOOK_API_TOKEN": "${ACCOUNTBOOK_API_TOKEN}",
                                                   "ACCOUNTBOOK_FAMILY_UUID": ""})
        self.assertEqual(self.alice_config()["mcp_servers"]["accountbook"], record["server"])
        self.assertEqual(self.connector_status().body["connectors"],
                         [{"plugin": LEGACY, "enabled": True, "configured": True, "mode": "isolated"}])
        self.assertEqual(self.connector_probe("accountbook"), 204)

    def test_legacy_ownership_record_meets_a_manifest_that_excludes_tools(self):
        """`tools` 가 없는 앞선 기록은 늘 승인이 필요한 도구를 선언한 manifest 를 만나도 조회, 재설치, 해제가 된다."""
        legacy = self.legacy_fixture()
        self.declare_tools(self.root.parent / "legacy-connector",
                           {"list_families": {"risk": "READ"}, "purge": {"risk": "DESTRUCTIVE"}})
        status = self.connector_status()
        self.assertEqual(status.status_code, 200)
        self.assertEqual(status.body["connectors"], [{"plugin": LEGACY, "enabled": True, "configured": False, "mode": "isolated"}])
        self.assertIs(status.body["policy_hook"], False)
        self.assertNotIn("tools", legacy)

        installed = self.connector(plugin=LEGACY)
        self.assertEqual(installed.status_code, 200)
        self.assertIs(installed.body["changed"], True)
        server = self.alice_config()["mcp_servers"]["accountbook"]
        self.assertEqual(server["tools"], {"exclude": ["purge"]})
        record = json.loads((self.root / "alice/.fos-connectors.json").read_text(encoding="utf-8"))
        self.assertEqual(record[LEGACY]["server"], server)
        status = self.connector_status()
        self.assertEqual(status.body["connectors"], [{"plugin": LEGACY, "enabled": True, "configured": True, "mode": "isolated"}])
        self.assertIs(status.body["policy_hook"], True)

        self.assertEqual(self.connector(False, plugin=LEGACY).status_code, 200)
        self.assertNotIn("accountbook", self.alice_config()["mcp_servers"])
        self.assertEqual(self.tool_map(), {"v": 1, "servers": {}})

    def test_legacy_ownership_record_is_turned_off_under_a_manifest_that_excludes_tools(self):
        """`tools` 가 없는 앞선 기록은 다시 설치하지 않고도 해제된다."""
        self.legacy_fixture()
        self.declare_tools(self.root.parent / "legacy-connector",
                           {"list_families": {"risk": "READ"}, "purge": {"risk": "DESTRUCTIVE"}})
        self.assertEqual(self.connector(False, plugin=LEGACY).status_code, 200)
        self.assertNotIn("accountbook", self.alice_config()["mcp_servers"])

    def test_legacy_ownership_record_keeps_working_after_optional_field_changes(self):
        """앞선 기록 위에서 선택 칸을 채워도 설정과 기록이 함께 바뀌고 probe 가 통과한다."""
        self.legacy_fixture()
        self.assertEqual(self.request("/api/env", "PUT", token="valid", body={
            "profile": "alice", "key": "ACCOUNTBOOK_FAMILY_UUID", "value": "family-fixture"}), 200)
        env = self.alice_config()["mcp_servers"]["accountbook"]["env"]
        self.assertEqual(env["ACCOUNTBOOK_FAMILY_UUID"], "${ACCOUNTBOOK_FAMILY_UUID}")
        # 선택 칸 쓰기가 설치를 다시 쓰므로 목록도 커넥터 서버만 남는다.
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["accountbook"])
        self.assertEqual(self.connector_probe("accountbook"), 204)
        self.assertEqual(self.connector(False, plugin=LEGACY).status_code, 200)
        self.assertNotIn("accountbook", self.alice_config()["mcp_servers"])
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["no_mcp"])

    def test_legacy_ownership_record_can_be_turned_off_after_removal_from_operator_list(self):
        """서버 이름 칸이 없는 앞선 기록도 운영 목록에서 빠진 뒤 설치를 끄고 참조하던 env key 를 지운다."""
        self.legacy_fixture()
        with self.without_environment("FOS_ASSISTANT_CONNECTOR_ROOTS"):
            self.assertEqual(self.connector_status().body["connectors"],
                             [{"plugin": LEGACY, "enabled": True, "configured": False, "mode": "isolated"}])
            self.assertEqual(self.connector(False, plugin=LEGACY).status_code, 200)
        config = self.alice_config()
        self.assertNotIn("accountbook", config["mcp_servers"])
        self.assertEqual(config["platform_toolsets"]["api_server"], ["no_mcp"])
        self.assertEqual((self.root / "alice/.env").read_text(encoding="utf-8"), "OTHER=keep\n")
