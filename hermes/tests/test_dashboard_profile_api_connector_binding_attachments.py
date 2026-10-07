"""dashboard-profile-api 의 connector_binding_attachments 분기를 검사한다."""

import hashlib
import json
import pathlib
import yaml
from dashboard_profile_api_support import DEMO, DEMO_BASE
import dashboard_profile_api_support as support


class ProfileApiConnectorBindingAttachmentsTest(support.ProfileApiRouteTest):
    def test_binding_a_connector_that_reads_owner_attachments_writes_the_owner_directory(self):
        """선언한 커넥터의 바인딩 설치는 서버 정의 env 에 정책 루트 아래 그 주인의 디렉터리를 넣고 `.env` 에는 쓰지 않는다."""
        demo, _ = self.bind_fixture()
        self.declare_owner_attachments(demo)

        response = self.bind(owner="user-1")

        self.assertEqual(response.status_code, 200, response.body)
        expected = self.owner_attachments("user-1")
        server = self.alice_config()["mcp_servers"]["demo"]
        self.assertEqual(server["env"], {"DEMO_TOKEN": "${DEMO_TOKEN}", "DEMO_SCOPE": "", "DEMO_BASE": DEMO_BASE,
                                         "DEMO_ATTACHMENT_DIR": expected})
        record = json.loads((self.root / "alice/.fos-connectors.json").read_text(encoding="utf-8"))
        self.assertEqual(record[DEMO]["server"], server)
        self.assertNotIn("DEMO_ATTACHMENT_DIR", (self.root / "alice/.env").read_text(encoding="utf-8"))
        status = self.status_of()
        self.assertEqual(status["connectors"][0], {"plugin": DEMO, "enabled": True, "configured": True, "mode": "bind"})
        self.assertNotIn(expected, json.dumps(response.body))

    def test_rebinding_with_another_owner_rewrites_the_owner_directory(self):
        """다시 설치할 때마다 요청의 주인으로 값을 다시 쓴다. 다른 사용자의 에이전트에 붙으면 그 사용자의 디렉터리다."""
        demo, _ = self.bind_fixture()
        self.declare_owner_attachments(demo)
        self.assertEqual(self.bind(owner="user-1").status_code, 200)

        repeated = self.bind(owner="user-1")
        self.assertIs(repeated.body["changed"], False)
        moved = self.bind(owner="user-2")

        self.assertEqual(moved.status_code, 200, moved.body)
        self.assertIs(moved.body["changed"], True)
        expected = self.owner_attachments("user-2")
        self.assertNotEqual(expected, self.owner_attachments("user-1"))
        server = self.alice_config()["mcp_servers"]["demo"]
        self.assertEqual(server["env"]["DEMO_ATTACHMENT_DIR"], expected)
        record = json.loads((self.root / "alice/.fos-connectors.json").read_text(encoding="utf-8"))
        self.assertEqual(record[DEMO]["server"]["env"]["DEMO_ATTACHMENT_DIR"], expected)
        self.assertIs(self.status_of()["connectors"][0]["configured"], True)
        # 다른 사용자의 에이전트 profile 에 붙여도 그 profile 의 값은 그 주인의 것이다.
        self.make_profile("bob")
        self.plugin._apply_template("bob")
        config = yaml.safe_load((self.root / "bob/config.yaml").read_text(encoding="utf-8"))
        config["platform_toolsets"]["api_server"] = ["fos-assistant"]
        self.write_config("bob", config)
        self.assertEqual(self.bind(profile="bob", owner="user-a").status_code, 200)
        bob = yaml.safe_load((self.root / "bob/config.yaml").read_text(encoding="utf-8"))
        self.assertEqual(bob["mcp_servers"]["demo"]["env"]["DEMO_ATTACHMENT_DIR"], self.owner_attachments("user-a"))
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"]["DEMO_ATTACHMENT_DIR"], expected)

    def test_binding_a_connector_that_reads_owner_attachments_refuses_without_a_safe_directory(self):
        """주인이 없으면 400, 정책이 없거나 그 디렉터리가 없거나 링크면 409 이고 아무 파일도 바꾸지 않는다."""
        demo, _ = self.bind_fixture()
        self.declare_owner_attachments(demo)
        before = self.tree("alice")

        missing = self.bind()
        self.assertEqual(missing.status_code, 400, missing.body)
        self.assertEqual(self.tree("alice"), before)

        users = pathlib.Path(self.attachment_agent_root) / "users"
        key = hashlib.sha256("user-9".encode("utf-8")).hexdigest()
        no_directory = self.bind(owner="user-9")
        self.assertEqual((no_directory.status_code, no_directory.body.get("code")), (409, "sandbox_unavailable"))
        # 그 주인의 자리에 다른 사용자의 디렉터리를 가리키는 링크가 있으면 거절한다.
        (users / key).symlink_to(users / hashlib.sha256("user-1".encode("utf-8")).hexdigest())
        linked = self.bind(owner="user-9")
        self.assertEqual((linked.status_code, linked.body.get("code")), (409, "sandbox_unavailable"))
        self.assertEqual(self.tree("alice"), before)

        with self.without_environment("FOS_ASSISTANT_SANDBOX"):
            unavailable = self.bind(owner="user-1")
        self.assertEqual((unavailable.status_code, unavailable.body.get("code")), (409, "sandbox_unavailable"))
        self.assertEqual(self.tree("alice"), before)

    def test_binding_a_connector_without_the_declaration_ignores_the_owner(self):
        """선언하지 않은 커넥터의 설치는 `sandbox_owner` 를 받아도 쓰지 않고, 실행 공간 정책 없이 지금처럼 붙는다."""
        self.bind_fixture()
        with self.without_environment("FOS_ASSISTANT_SANDBOX"):
            response = self.bind(owner="user-9")

        self.assertEqual(response.status_code, 200, response.body)
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"],
                         {"DEMO_TOKEN": "${DEMO_TOKEN}", "DEMO_SCOPE": "", "DEMO_BASE": DEMO_BASE})

    def test_owner_directory_in_a_record_must_keep_its_shape(self):
        """소유 기록의 주인 디렉터리 값이 참조나 다른 모양으로 바뀌면 그 커넥터의 실행 정의가 manifest 와 다르다."""
        demo, _ = self.bind_fixture()
        self.declare_owner_attachments(demo)
        self.assertEqual(self.bind(owner="user-1").status_code, 200)
        manifest = self.plugin._connector_manifest(DEMO)
        server = self.alice_config()["mcp_servers"]["demo"]
        self.assertTrue(self.plugin._server_matches(manifest, server))
        for value in ("${DEMO_ATTACHMENT_DIR}", "/attachments/users/owner", "relative/users/" + "a" * 64):
            with self.subTest(value=value):
                self.assertFalse(self.plugin._server_matches(
                    manifest, {**server, "env": {**server["env"], "DEMO_ATTACHMENT_DIR": value}}))
        # 주인을 모르는 설치가 남긴 빈 값은 받는다.
        self.assertTrue(self.plugin._server_matches(
            manifest, {**server, "env": {**server["env"], "DEMO_ATTACHMENT_DIR": ""}}))
