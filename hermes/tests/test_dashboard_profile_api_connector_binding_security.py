"""dashboard-profile-api 의 connector_binding_security 분기를 검사한다."""

import json
import os
import shutil
from unittest import mock
from plugin_loading import patch_plugin
from dashboard_profile_api_support import DEMO, DEMO_BASE, OTHER, OTHER_VALUE
import dashboard_profile_api_support as support


class ProfileApiConnectorBindingSecurityTest(support.ProfileApiRouteTest):
    def test_binding_failures_change_no_file(self):
        """기록 없는 env 와 겹치거나, 스킬 디렉터리가 있거나, 방식이 섞이거나, 표식이 없거나, 보관 파일이 맞지 않으면 아무 파일도 바꾸지 않는다."""
        _, other = self.bind_fixture()
        alice = self.root / "alice"

        def refused(response, status, profile="alice"):
            self.assertEqual(response.status_code, status, response.body)
            self.assertEqual(self.tree(profile), before)

        (alice / ".env").write_text("DEMO_TOKEN=someone-else\n", encoding="utf-8")
        before = self.tree("alice")
        refused(self.bind(), 409)
        (alice / ".env").unlink()

        (alice / "skills/demo").mkdir(parents=True)
        (alice / "skills/demo/SKILL.md").write_text("사람이 둔 스킬\n", encoding="utf-8")
        before = self.tree("alice")
        refused(self.bind(), 409)
        shutil.rmtree(alice / "skills")

        before = self.tree("alice")
        refused(self.bind(vault="c2"), 400)
        refused(self.bind(vault="c9"), 400)
        refused(self.bind(vault="../c1"), 400)

        # 다른 바인딩 커넥터가 같은 env 이름을 쓰면 붙이지 않는다.
        self.assertEqual(self.bind().status_code, 200)
        declared = json.loads((other / "connector.json").read_text(encoding="utf-8"))
        declared["fields"][1]["env"] = "DEMO_SCOPE"
        (other / "connector.json").write_text(json.dumps(declared), encoding="utf-8")
        mcp = json.loads((other / ".mcp.json").read_text(encoding="utf-8"))
        mcp["mcpServers"]["other"]["env"] = {"OTHER_TOKEN": "${OTHER_TOKEN}", "DEMO_SCOPE": "${DEMO_SCOPE:-}",
                                             "OTHER_BASE": "${OTHER_BASE}"}
        (other / ".mcp.json").write_text(json.dumps(mcp), encoding="utf-8")
        self.assertEqual(self.vault("PUT", vault="c3", connector=OTHER, values={"token": OTHER_VALUE}).status_code, 200)
        before = self.tree("alice")
        refused(self.bind(OTHER, "c3"), 409)
        # 바인딩이 있는 profile 에 옛 설치를 보내도 거절한다.
        refused(self.connector(plugin=OTHER), 409)

        # 옛 설치가 있는 profile 에 바인딩을 보내면 거절한다.
        self.make_profile("bob")
        self.plugin._apply_template("bob")
        self.assertEqual(self.connector(profile="bob").status_code, 200)
        before = self.tree("bob")
        refused(self.bind(OTHER, "c2", profile="bob"), 409, "bob")
        # 표식이 없는 profile 은 401 이다.
        before = self.tree("owner")
        refused(self.bind(profile="owner"), 401, "owner")

    def test_binding_refuses_a_connector_skill_that_requests_secrets(self):
        """커넥터 스킬의 앞머리가 환경 값이나 자격 증명 파일을 요청하면 붙이지 않고 아무 파일도 바꾸지 않는다(ADR-086)."""
        self.bind_fixture()
        skill = self.root.parent / "demo-connector/skills/demo/SKILL.md"
        original = skill.read_text(encoding="utf-8")
        before = self.tree("alice")
        for field in ("required_environment_variables: [DEMO_TOKEN]", "required_credential_files: [.env]",
                      "setup:\n  collect_secrets: [DEMO_TOKEN]", "prerequisites:\n  env_vars: [DEMO_TOKEN]"):
            with self.subTest(field=field):
                # 첫 줄 뒤에 공백이 붙어도 Hermes 는 앞머리로 읽으므로 같은 검사를 받는다.
                for opening in ("---\n", "--- \n"):
                    skill.write_text(original.replace("---\n", opening + "%s\n" % field, 1), encoding="utf-8")
                    response = self.bind()
                    self.assertNotEqual(response.status_code, 200, response.body)
                    self.assertEqual(self.tree("alice"), before)
        skill.write_text(original, encoding="utf-8")
        self.assertEqual(self.bind().status_code, 200)

    def test_binding_rolls_back_every_file_it_wrote_when_the_last_write_fails(self):
        """마지막 파일의 쓰기가 실패하면 먼저 쓴 설정, 소유 기록, 대응 파일, `.env`, 스킬을 되돌리고 만든 디렉터리를 지운다."""
        self.bind_fixture()
        profile = self.root / "alice"
        (profile / ".env").write_text("OTHER=keep\n", encoding="utf-8")
        # plugin 파일이 마지막에 쓰이도록 profile 의 hook plugin 을 지운다.
        shutil.rmtree(profile / "plugins")

        def files():
            return {name: value for name, value in self.tree("alice").items()
                    if not name.startswith("connector-backups")}

        before = files()
        write = self.plugin._atomic_private_write
        attempted = []

        def failing_write(target, value):
            # 실패 뒤의 쓰기는 되돌리기다. 시도한 쓰기로 세지 않는다.
            if "connector-backups" in target.parts or None in attempted:
                return write(target, value)
            if target == profile / "plugins/fos-ctx/__init__.py":
                attempted.append(None)
                raise OSError("injected")
            attempted.append(target)
            return write(target, value)

        with patch_plugin(self.plugin, "_atomic_private_write", side_effect=failing_write):
            self.assertEqual(self.bind().status_code, 503)
        # 실패한 쓰기가 마지막이었다. 그 앞에 나머지 파일을 모두 썼어야 되돌리기를 검사한 것이다.
        self.assertEqual(attempted[-1], None)
        self.assertLessEqual({profile / "config.yaml", profile / ".env", profile / self.plugin.CONNECTOR_STATE,
                              profile / self.plugin.CONNECTOR_TOOL_MAP, profile / "skills/demo/SKILL.md"},
                             set(attempted))
        self.assertEqual(files(), before)
        self.assertFalse((profile / "skills").exists())
        self.assertFalse((profile / "plugins").exists())

    def test_binding_needs_an_api_list_that_holds_the_control_plane_mcp(self):
        """API 도구 목록이 없거나 그 안에 Control Plane MCP 가 없으면 바인딩 설치는 409 이고 아무것도 바꾸지 않는다."""
        self.bind_fixture()
        for label, change in (
            ("no list", lambda platform: platform.pop("api_server")),
            ("no control plane", lambda platform: platform.update(api_server=["delegation", "terminal"])),
        ):
            with self.subTest(label):
                config = self.alice_config()
                change(config["platform_toolsets"])
                self.write_config("alice", config)
                before = self.tree("alice")
                self.assertEqual(self.bind().status_code, 409)
                self.assertEqual(self.tree("alice"), before)

    def test_config_update_must_keep_bound_server_names(self):
        """도구 저장이 붙은 커넥터의 서버 이름을 빠뜨리면 409 이고 설정이 그대로다. 함께 보내면 지금처럼 쓴다."""
        self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        path = self.root / "alice/config.yaml"
        original = path.read_bytes()
        body = {"profile": "alice", "config": {"platform_toolsets": {"api_server": ["delegation", "fos-assistant"]}}}
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 409)
        self.assertEqual(path.read_bytes(), original)
        body["config"]["platform_toolsets"]["api_server"].append("demo")
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 200)
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["delegation", "fos-assistant", "demo"])
        # 스킬 경로만 쓰는 요청은 도구 목록을 바꾸지 않으므로 보지 않는다.
        self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                      body={"profile": "alice", "config": {"skills": {"external_dirs": []}}}), 200)

    def test_tool_map_keeps_a_bound_server_whose_connector_left_the_operator_list(self):
        """운영 목록에서 빠진 바인딩 커넥터의 서버도 빈 `tools` 로 대응에 남고, 떼면 기록이 참조하던 env 를 지운다."""
        demo, _ = self.bind_fixture()
        self.assertEqual(self.bind(DEMO, "c1").status_code, 200)
        self.assertEqual(self.bind(OTHER, "c2").status_code, 200)
        listed = {DEMO: {"root": str(demo), "env": {"DEMO_BASE": DEMO_BASE}}}
        with mock.patch.dict(os.environ, {"FOS_ASSISTANT_CONNECTOR_ROOTS": json.dumps(listed)}):
            # 다른 값으로 다시 붙이면 대응 파일을 다시 쓴다. 빠진 커넥터의 서버는 도구 없이 남는다.
            self.assertEqual(self.vault("PUT", vault="c1", connector=DEMO,
                                        values={"token": "demo_new_0123456789"}).status_code, 200)
            self.assertIs(self.bind(DEMO, "c1").body["changed"], True)
            self.assertEqual(self.tool_map()["servers"]["other"],
                             {"connector": OTHER, "prefix": "mcp__other__", "tools": {}})
            self.assertIn("mcp__demo__list_scopes", self.tool_map()["servers"]["demo"]["tools"])
            status = self.status_of()
            self.assertEqual(status["connectors"][1], {"plugin": OTHER, "enabled": True, "configured": False,
                                                       "mode": "bind"})
            self.assertIs(status["policy_hook"], True)
            self.assertEqual(self.bind(OTHER, enabled=False).status_code, 200)
        self.assertEqual((self.root / "alice/.env").read_text(encoding="utf-8").splitlines(),
                         ["DEMO_TOKEN=demo_new_0123456789"])
        self.assertFalse((self.root / "alice/skills/other").exists())
        self.assertNotIn("other", self.alice_config()["platform_toolsets"]["api_server"])
