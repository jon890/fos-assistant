"""dashboard-profile-api 의 connector_binding_install 분기를 검사한다."""

import json
import os
import yaml
from plugin_loading import patch_plugin
from dashboard_profile_api_support import DEMO, DEMO_BASE, DEMO_VALUE, OTHER, OTHER_BASE, OTHER_VALUE
import dashboard_profile_api_support as support


class ProfileApiConnectorBindingInstallTest(support.ProfileApiRouteTest):
    def test_binding_two_connectors_adds_their_names_and_keeps_the_profile(self):
        """두 커넥터를 차례로 붙이면 도구 목록에 서버 이름만 더해지고 Control Plane MCP 와 SOUL.md 는 그대로이며 스킬이 생긴다."""
        demo, other = self.bind_fixture()
        before = self.alice_config()
        catalog = {entry["id"]: entry["skills"] for entry in
                   self.request("/api/connectors/catalog", "GET", token="valid", full_response=True).body}
        self.assertEqual(catalog, {DEMO: ["demo"], OTHER: ["other"]})

        for plugin, vault in ((DEMO, "c1"), (OTHER, "c2")):
            with self.subTest(plugin):
                response = self.bind(plugin, vault)
                self.assertEqual(response.status_code, 200, response.body)
                self.assertIs(response.body["changed"], True)
                self.assertIs(response.body["restart_required"], True)
        config = self.alice_config()
        self.assertEqual(config["platform_toolsets"]["api_server"],
                         ["delegation", "fos-assistant", "terminal", "demo", "other"])
        self.assertEqual(config["mcp_servers"]["fos-assistant"], before["mcp_servers"]["fos-assistant"])
        self.assertEqual(config["agent"], before["agent"])
        self.assertEqual(config["mcp_servers"]["demo"]["env"],
                         {"DEMO_TOKEN": "${DEMO_TOKEN}", "DEMO_SCOPE": "", "DEMO_BASE": DEMO_BASE})
        self.assertEqual(config["mcp_servers"]["other"]["env"],
                         {"OTHER_TOKEN": "${OTHER_TOKEN}", "OTHER_SCOPE": "${OTHER_SCOPE}", "OTHER_BASE": OTHER_BASE})
        self.assertEqual((self.root / "alice/SOUL.md").read_text(encoding="utf-8"), "사용자의 성격\n")
        env = self.root / "alice/.env"
        self.assertEqual(env.read_text(encoding="utf-8").splitlines(),
                         ["DEMO_TOKEN=" + DEMO_VALUE, "OTHER_TOKEN=" + OTHER_VALUE, "OTHER_SCOPE=a"])
        self.assertEqual(env.stat().st_mode & 0o777, 0o600)
        for target, source in (("skills/demo/SKILL.md", demo / "skills/demo/SKILL.md"),
                               ("skills/demo/references/guide.md", demo / "skills/demo/references/guide.md"),
                               ("skills/other/SKILL.md", other / "skills/other-dir/SKILL.md")):
            installed = self.root / "alice" / target
            self.assertEqual(installed.read_bytes(), source.read_bytes(), target)
            self.assertEqual(installed.stat().st_mode & 0o777, 0o644, target)
        record = json.loads((self.root / "alice/.fos-connectors.json").read_text(encoding="utf-8"))
        self.assertEqual(record[DEMO], {"server": config["mcp_servers"]["demo"], "allowlist_added": True,
                                        "mcp_server": "demo", "mode": "bind", "vault": "c1", "skills": ["demo"]})
        self.assertEqual(record[OTHER]["skills"], ["other"])
        self.assertEqual(self.tool_map(), {"v": 1, "isolated": False, "servers": {
            "demo": {"connector": DEMO, "prefix": "mcp__demo__", "tools": {"mcp__demo__list_scopes": "list_scopes"}},
            "other": {"connector": OTHER, "prefix": "mcp__other__",
                      "tools": {"mcp__other__list_scopes": "list_scopes"}}}})
        status = self.status_of()
        self.assertEqual(status["connectors"], [
            {"plugin": DEMO, "enabled": True, "configured": True, "mode": "bind"},
            {"plugin": OTHER, "enabled": True, "configured": True, "mode": "bind"}])
        self.assertIs(status["policy_hook"], True)

        repeated = self.bind()
        self.assertIs(repeated.body["changed"], False)
        self.assertIs(repeated.body["restart_required"], False)
        # 스킬 본문이 plugin 과 달라지면 설치가 덜 된 것으로 답하고, 다시 붙이면 plugin 의 본문으로 돌린다.
        (self.root / "alice/skills/demo/SKILL.md").write_text("바뀐 본문\n", encoding="utf-8")
        self.assertEqual(self.status_of()["connectors"][0]["configured"], False)
        self.assertIs(self.bind().body["changed"], True)
        self.assertEqual(self.status_of()["connectors"][0]["configured"], True)
        # 칸 값은 응답에도 설정 백업에도 없다.
        for path in (self.root / "alice/connector-backups").rglob("*"):
            if path.is_file():
                self.assertNotIn(DEMO_VALUE.encode(), path.read_bytes(), path.name)
        self.assertNotIn(DEMO_VALUE, json.dumps(status))

    def test_changing_one_connector_leaves_the_other_binding_of_the_profile_usable(self):
        """한 커넥터의 실행 정의가 바뀌어도 같은 profile 의 다른 바인딩은 조회, probe, 실행, 다시 붙이기가 되고 바뀐 항목만 쓸 수 없다."""
        _, other = self.bind_fixture()
        self.assertEqual(self.bind(DEMO, "c1").status_code, 200)
        self.assertEqual(self.bind(OTHER, "c2").status_code, 200)
        declared = json.loads((other / ".mcp.json").read_text(encoding="utf-8"))
        declared["mcpServers"]["other"]["args"].append("--verbose")
        (other / ".mcp.json").write_text(json.dumps(declared), encoding="utf-8")

        expected = [{"plugin": DEMO, "enabled": True, "configured": True, "mode": "bind"},
                    {"plugin": OTHER, "enabled": True, "configured": False, "mode": "bind"}]
        self.assertEqual(self.status_of()["connectors"], expected)
        self.assertEqual(self.connector_probe("demo"), 204)
        self.assertEqual(self.connector_probe("other"), 503)

        ran = []

        async def runner(manifest, hermes_tool, args, env, progress):
            ran.append(manifest["id"])
            return None

        with patch_plugin(self.plugin, "_mcp_sdk_problem", return_value=None), \
                patch_plugin(self.plugin, "_run_connector_execute", side_effect=runner):
            for plugin, tool in ((DEMO, "mcp__demo__write_note"), (OTHER, "mcp__other__write_note")):
                self.request("/api/connectors/%s/execute" % plugin, "POST", token="valid", full_response=True,
                             body={"profile": "alice", "hermes_tool": tool, "args": {}})
        # 바뀐 커넥터는 자식을 띄우기 전에 멈춘다.
        self.assertEqual(ran, [DEMO])

        self.assertEqual(self.vault("PUT", vault="c1", connector=DEMO,
                                    values={"token": "demo_new_0123456789"}).status_code, 200)
        rebound = self.bind(DEMO, "c1")
        self.assertEqual(rebound.status_code, 200, rebound.body)
        self.assertIs(rebound.body["changed"], True)
        # 바뀐 항목은 대응에 빈 `tools` 로 남아 모든 호출을 묻는다. 다시 쓴 대응으로 hook 상태가 참이 된다.
        self.assertEqual(self.tool_map()["servers"]["other"]["tools"], {})
        status = self.status_of()
        self.assertEqual(status["connectors"], expected)
        self.assertIs(status["policy_hook"], True)

    def test_binding_needs_the_policy_hook_plugin_turned_on(self):
        """profile 설정에서 fos-ctx 가 켜져 있지 않거나 도구 덮어쓰기를 허용하면 바인딩 설치는 409 이고 아무것도 바꾸지 않는다."""
        self.bind_fixture()
        original = self.alice_config()
        for label, change in (
            ("not enabled", lambda plugins: plugins.update(enabled=[])),
            ("disabled", lambda plugins: plugins.update(disabled=["fos-ctx"])),
            ("override allowed", lambda plugins: plugins["entries"]["fos-ctx"].update(allow_tool_override=True)),
            ("no plugins", None),
        ):
            with self.subTest(label):
                config = json.loads(json.dumps(original))
                if change is None:
                    config.pop("plugins")
                else:
                    change(config["plugins"])
                self.write_config("alice", config)
                before = self.tree("alice")
                self.assertEqual(self.bind().status_code, 409)
                self.assertEqual(self.tree("alice"), before)
        self.write_config("alice", original)
        self.assertEqual(self.bind().status_code, 200)

    def test_binding_a_vision_connector_leaves_builtin_tools_and_terminal_to_the_agent(self):
        """사진 도구를 선언한 커넥터도 바인딩은 실행 공간 정책 없이 붙고 내장 도구와 `terminal:` 을 바꾸지 않는다."""
        self.bind_fixture()
        manifest = self.root.parent / "demo-connector/connector.json"
        declared = json.loads(manifest.read_text(encoding="utf-8"))
        declared["toolsets"] = ["vision"]
        manifest.write_text(json.dumps(declared), encoding="utf-8")
        os.environ.pop("FOS_ASSISTANT_SANDBOX", None)
        before = self.alice_config()

        response = self.bind()

        self.assertEqual(response.status_code, 200, response.body)
        config = self.alice_config()
        self.assertEqual(config["platform_toolsets"]["api_server"], ["delegation", "fos-assistant", "terminal", "demo"])
        self.assertEqual(config.get("terminal"), before.get("terminal"))

    def test_rebinding_an_owned_connector_is_accepted_while_the_policy_hook_is_off(self):
        """이미 붙은 커넥터를 다시 설치하는 것은 fos-ctx 가 꺼져 있어도 받고, hook 상태는 거짓으로 남는다."""
        self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        config = self.alice_config()
        config["plugins"]["disabled"] = ["fos-ctx"]
        self.write_config("alice", config)
        self.assertEqual(self.bind().status_code, 200)
        self.assertIs(self.status_of()["policy_hook"], False)

    def test_profile_with_only_the_connector_marker_takes_bindings_only(self):
        """커넥터 표식만 있는 사람이 만든 profile 은 조회, 바인딩 설치, 떼기가 되고 옛 설치는 401 이다."""
        self.bind_fixture()
        self.host_profile()

        self.assertEqual(self.status_of("human")["connectors"][0],
                         {"plugin": DEMO, "enabled": False, "configured": False, "mode": "isolated"})
        before = self.tree("human")
        self.assertEqual(self.connector(profile="human").status_code, 401)
        self.assertEqual(self.tree("human"), before)

        installed = self.bind(profile="human")
        self.assertEqual(installed.status_code, 200, installed.body)
        self.assertIs(installed.body["restart_required"], True)
        self.assertIs(installed.body["plugin_updated"], True)
        self.assertFalse((self.root / "human/SOUL.md").exists())
        self.assertEqual(self.request("/api/connectors", "GET", token="valid", query_profiles=["human"],
                                      full_response=True).body["policy_hook"], True)
        self.assertEqual(self.status_of("human")["connectors"][0],
                         {"plugin": DEMO, "enabled": True, "configured": True, "mode": "bind"})
        self.assertEqual(self.connector_probe(profile="human"), 204)

        removed = self.bind(profile="human", enabled=False)
        self.assertEqual(removed.status_code, 200)
        self.assertEqual(self.request("/api/connectors", "GET", token="valid", query_profiles=["human"],
                                      full_response=True).body["connectors"][0]["enabled"], False)
        self.assertEqual(yaml.safe_load((self.root / "human/config.yaml").read_text())["platform_toolsets"],
                         {"api_server": ["web", "fos-assistant"]})
