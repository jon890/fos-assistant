"""dashboard-profile-api 의 connector_binding_install 분기를 검사한다."""

import json
import os
import yaml
from unittest import mock
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
                # 새 서버 이름은 공유 gateway 의 MCP 설정 맞추기가 연결한다. 재시작을 기다리지 않는다.
                self.assertIs(response.body["restart_required"], False)
                self.assertIs(response.body["reload_pending"], True)
        config = self.alice_config()
        self.assertEqual(config["platform_toolsets"]["api_server"],
                         ["delegation", "fos-assistant", "terminal", "demo", "other"])
        markers = config["skills"]["disabled"]
        self.assertEqual(len(markers), 1, markers)
        self.assertTrue(markers[0].startswith("fos-skill-index-"), markers)
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

    def test_rebinding_with_changed_values_requires_restart(self):
        """이미 있던 서버의 `.env` 값을 바꾸면 gateway 가 같은 이름을 다시 연결하지 않으므로 재시작을 기다린다."""
        self.bind_fixture()
        self.assertEqual(self.bind(DEMO, "c1").status_code, 200)

        self.assertEqual(self.vault("PUT", vault="c1", connector=DEMO,
                                    values={"token": "demo_new_9876543210"}).status_code, 200)
        changed_value = self.bind(DEMO, "c1")
        self.assertEqual(changed_value.status_code, 200, changed_value.body)
        self.assertEqual({key: changed_value.body[key] for key in ("changed", "restart_required", "reload_pending")},
                         {"changed": True, "restart_required": True, "reload_pending": False})
        self.assertIn("DEMO_TOKEN=demo_new_9876543210",
                      (self.root / "alice/.env").read_text(encoding="utf-8").splitlines())

    def test_rebinding_with_the_same_values_changes_nothing(self):
        """같은 값으로 다시 붙이면 모든 칸이 거짓이고 `config.yaml` 이 그대로다. 표식도 새로 쓰지 않는다."""
        self.bind_fixture()
        self.assertEqual(self.bind(DEMO, "c1").status_code, 200)
        before = (self.root / "alice/config.yaml").read_bytes()

        repeated = self.bind(DEMO, "c1")
        self.assertEqual(repeated.status_code, 200, repeated.body)
        self.assertEqual({key: repeated.body[key]
                          for key in ("changed", "restart_required", "plugin_updated", "reload_pending")},
                         {"changed": False, "restart_required": False, "plugin_updated": False,
                          "reload_pending": False})
        self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)

    def test_mapping_skills_disabled_rejects_binding_but_detaching_still_removes_env(self):
        """`skills.disabled` 가 사전이면 붙이기는 409 로 파일을 바꾸지 않고, 떼기는 표식 없이 칸 값을 지운다."""
        self.bind_fixture()
        self.assertEqual(self.bind(DEMO, "c1").status_code, 200)
        config = self.alice_config()
        config["skills"] = {"disabled": {"operator": True}}
        self.write_config("alice", config)
        before = self.tree("alice")

        rejected = self.bind(OTHER, "c2")
        self.assertEqual(rejected.status_code, 409, rejected.body)
        self.assertEqual(self.tree("alice"), before)

        removed = self.bind(DEMO, enabled=False)
        self.assertEqual(removed.status_code, 200, removed.body)
        self.assertIs(removed.body["changed"], True)
        self.assertNotIn("DEMO_TOKEN", (self.root / "alice/.env").read_text(encoding="utf-8"))
        self.assertEqual(self.alice_config()["skills"], {"disabled": {"operator": True}})

    def test_string_skills_disabled_is_rewritten_as_the_list_hermes_reads(self):
        """문자열 `skills.disabled` 는 Hermes 가 읽는 대로 목록으로 바꿔 표식과 함께 쓴다. 쉼표로 나누지 않는다."""
        self.bind_fixture()
        for plugin, vault, written, kept in ((DEMO, "c1", "['a', 'b']", ["a", "b"]),
                                             (OTHER, "c2", "a,b", ["a,b"])):
            with self.subTest(written):
                config = self.alice_config()
                config["skills"] = {"disabled": written}
                self.write_config("alice", config)
                response = self.bind(plugin, vault)
                self.assertEqual(response.status_code, 200, response.body)
                disabled = self.alice_config()["skills"]["disabled"]
                self.assertEqual(disabled[:-1], kept)
                self.assertTrue(disabled[-1].startswith("fos-skill-index-"), disabled)

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

    def warnings_of_status(self, profile="alice"):
        """상태 조회의 본문과, 그 조회가 남긴 경고 줄을 인자를 채운 문자열로 돌려준다."""
        with mock.patch.object(self.plugin.logger, "warning") as warning:
            status = self.status_of(profile)
        return status, [call.args[0] % call.args[1:] for call in warning.call_args_list]

    def test_stale_tools_exclude_marks_only_that_connector_unconfigured(self):
        """승인 방식만 올라 서버 정의의 `tools.exclude` 만 어긋나면 그 커넥터 항목만 거짓이고 같은 profile 의 다른 바인딩과 hook 은 참이다."""
        demo, _ = self.bind_fixture()
        self.declare_tools(demo, {"list_scopes": {"risk": "READ"}, "purge": {"risk": "WRITE"}})
        self.assertEqual(self.bind(DEMO, "c1").status_code, 200)
        self.assertEqual(self.bind(OTHER, "c2").status_code, 200)
        self.assertNotIn("tools", self.alice_config()["mcp_servers"]["demo"])
        tool_map = self.tool_map()

        # 도구 이름이 같아 이름 대응은 그대로다. 늘 승인이 필요해진 `purge` 가 서버 정의에서 빠져 있지 않다.
        self.declare_tools(demo, {"list_scopes": {"risk": "READ"}, "purge": {"risk": "DESTRUCTIVE"}})
        status, warnings = self.warnings_of_status()
        self.assertEqual(status["connectors"], [
            {"plugin": DEMO, "enabled": True, "configured": False, "mode": "bind"},
            {"plugin": OTHER, "enabled": True, "configured": True, "mode": "bind"}])
        self.assertIs(status["policy_hook"], True)
        self.assertEqual(warnings, [])
        self.assertEqual(self.tool_map(), tool_map)

        # 다시 붙이면 서버 정의가 바뀌므로 gateway 를 재시작해야 반영된다.
        rebound = self.bind(DEMO, "c1")
        self.assertEqual(rebound.status_code, 200, rebound.body)
        self.assertIs(rebound.body["restart_required"], True)
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["tools"], {"exclude": ["purge"]})
        status = self.status_of()
        self.assertEqual([entry["configured"] for entry in status["connectors"]], [True, True])
        self.assertIs(status["policy_hook"], True)

    def test_added_tool_marks_only_that_connector_unconfigured(self):
        """도구가 더해져 이름 대응의 그 서버 `tools` 만 계산한 것과 다르면 그 커넥터 항목만 거짓이고 hook 은 참이다."""
        demo, _ = self.bind_fixture()
        self.assertEqual(self.bind(DEMO, "c1").status_code, 200)
        self.assertEqual(self.bind(OTHER, "c2").status_code, 200)
        tool_map = self.tool_map()

        # 확인 도구 `list_scopes` 를 남겨야 manifest 가 유효하다. 늘 승인이 필요한 도구가 없어 서버 정의는 그대로다.
        self.declare_tools(demo, {"list_scopes": {"risk": "READ"}, "list_tags": {"risk": "READ"}})
        self.assertEqual(self.tool_map(), tool_map)
        self.assertEqual(tool_map["servers"]["demo"]["tools"], {"mcp__demo__list_scopes": "list_scopes"})
        self.assertNotIn("tools", self.alice_config()["mcp_servers"]["demo"])
        status, warnings = self.warnings_of_status()
        self.assertEqual(status["connectors"], [
            {"plugin": DEMO, "enabled": True, "configured": False, "mode": "bind"},
            {"plugin": OTHER, "enabled": True, "configured": True, "mode": "bind"}])
        self.assertIs(status["policy_hook"], True)
        self.assertEqual(warnings, [])

        self.assertEqual(self.bind(DEMO, "c1").status_code, 200)
        self.assertEqual(self.tool_map()["servers"]["demo"]["tools"], {
            "mcp__demo__list_scopes": "list_scopes", "mcp__demo__list_tags": "list_tags"})
        self.assertEqual(self.tool_map()["servers"]["other"], tool_map["servers"]["other"])
        status = self.status_of()
        self.assertEqual([entry["configured"] for entry in status["connectors"]], [True, True])
        self.assertIs(status["policy_hook"], True)

    def test_tool_map_missing_a_server_turns_the_policy_hook_off(self):
        """이름 대응에서 서버가 빠지면 그 서버의 호출이 판정 없이 나가므로 profile 단위 `policy_hook` 이 거짓이고 조건 이름을 남긴다."""
        self.bind_fixture()
        self.assertEqual(self.bind(DEMO, "c1").status_code, 200)
        self.assertEqual(self.bind(OTHER, "c2").status_code, 200)
        tool_map = self.tool_map()
        del tool_map["servers"]["other"]
        (self.root / "alice" / self.plugin.CONNECTOR_TOOL_MAP).write_text(json.dumps(tool_map), encoding="utf-8")

        status, warnings = self.warnings_of_status()

        self.assertIs(status["policy_hook"], False)
        self.assertEqual(warnings, ["dashboard-profile-api: policy_hook 거짓 조건=tool_map"])

    def test_disabled_policy_plugin_is_logged_by_condition_name_only(self):
        """`plugins.disabled` 에 fos-ctx 가 있으면 `policy_hook` 이 거짓이고 조건 이름만 경고 한 줄로 남긴다. profile 이름은 남기지 않는다."""
        self.bind_fixture()
        self.assertEqual(self.bind(DEMO, "c1").status_code, 200)
        config = self.alice_config()
        config["plugins"]["disabled"] = ["fos-ctx"]
        self.write_config("alice", config)

        status, warnings = self.warnings_of_status()

        self.assertIs(status["policy_hook"], False)
        self.assertEqual(warnings, ["dashboard-profile-api: policy_hook 거짓 조건=plugin_config"])
        self.assertNotIn("alice", warnings[0])

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

    def test_sandbox_required_connector_is_rejected_without_a_policy(self):
        """실행 공간 정책이 없으면 `sandbox_required` 커넥터의 바인딩 설치는 409 `sandbox_unavailable` 이고 profile 파일이 그대로다."""
        demo, _ = self.bind_fixture()
        self.declare_sandbox_required(demo)
        before = self.tree("alice")

        with self.without_environment("FOS_ASSISTANT_SANDBOX"):
            response = self.bind()

        self.assertEqual(response.status_code, 409, response.body)
        self.assertEqual(response.body["code"], "sandbox_unavailable")
        self.assertEqual(self.tree("alice"), before)
        self.assertFalse((self.root / "alice/.fos-connectors.json").exists())

    def test_sandbox_required_connector_is_rejected_on_a_profile_outside_the_policy(self):
        """정책은 있어도 그 profile 이 `profiles` 에 없으면 같은 409 이고 profile 파일이 그대로다."""
        demo, _ = self.bind_fixture()
        self.declare_sandbox_required(demo)
        policy = self.sandbox_policy()
        policy["profiles"].pop("alice")
        self.set_sandbox_policy(policy)
        before = self.tree("alice")

        response = self.bind()

        self.assertEqual(response.status_code, 409, response.body)
        self.assertEqual(response.body["code"], "sandbox_unavailable")
        self.assertEqual(self.tree("alice"), before)
        self.assertFalse((self.root / "alice/.fos-connectors.json").exists())

    def test_sandbox_required_connector_binds_on_a_profile_in_the_policy(self):
        """그 profile 이 정책에 있으면 셸이 켜져 있지 않아도 설치가 성공한다."""
        demo, _ = self.bind_fixture()
        self.declare_sandbox_required(demo)
        self.set_sandbox_policy(self.sandbox_policy())

        response = self.bind()

        self.assertEqual(response.status_code, 200, response.body)
        self.assertIs(response.body["changed"], True)
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"],
                         ["delegation", "fos-assistant", "terminal", "demo"])
        record = json.loads((self.root / "alice/.fos-connectors.json").read_text(encoding="utf-8"))
        self.assertEqual(record[DEMO]["mode"], "bind")
        self.assertIs(self.status_of()["connectors"][0]["configured"], True)

    def test_connector_without_the_declaration_binds_without_a_policy(self):
        """`sandbox_required` 를 선언하지 않은 커넥터는 정책이 없어도 지금처럼 붙는다."""
        self.bind_fixture()

        with self.without_environment("FOS_ASSISTANT_SANDBOX"):
            response = self.bind()

        self.assertEqual(response.status_code, 200, response.body)
        self.assertIs(response.body["changed"], True)
        self.assertEqual(self.status_of()["connectors"][0],
                         {"plugin": DEMO, "enabled": True, "configured": True, "mode": "bind"})

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
        # 새 서버만으로는 재시작이 필요 없지만 `fos-ctx` plugin 파일을 처음 복사했으므로 그것으로 재시작을 기다린다.
        self.assertIs(installed.body["restart_required"], False)
        self.assertIs(installed.body["plugin_updated"], True)
        self.assertIs(installed.body["reload_pending"], False)
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


# 바인딩 설치가 받는 브라우저 중계 주소의 모양이다. `<gateway-base-url>/<바인딩 표식>` 이다.
BROWSER_ADDRESS = "http://cp.example.test/internal/browser-gateway/b1." + "a" * 64


class ProfileApiConnectorBindingBrowserTest(support.ProfileApiRouteTest):
    """`owner_browser_env` 를 선언한 커넥터의 바인딩 설치를 검사한다(ADR-20261008 browser-gateway-token)."""

    def test_binding_writes_the_relay_address_into_the_server_definition(self):
        """받은 중계 주소를 서버 정의 env 에 넣고 `.env` 에는 쓰지 않는다. 같은 주소로 다시 설치하면 재시작이 필요 없다."""
        demo, _ = self.bind_fixture()
        self.declare_owner_browser(demo)

        response = self.bind(browser=BROWSER_ADDRESS)

        self.assertEqual(response.status_code, 200, response.body)
        server = self.alice_config()["mcp_servers"]["demo"]
        self.assertEqual(server["env"]["DEMO_BROWSER_URL"], BROWSER_ADDRESS)
        record = json.loads((self.root / "alice/.fos-connectors.json").read_text(encoding="utf-8"))
        self.assertEqual(record[DEMO]["server"], server)
        self.assertNotIn("DEMO_BROWSER_URL", (self.root / "alice/.env").read_text(encoding="utf-8"))
        self.assertNotIn(BROWSER_ADDRESS, json.dumps(response.body))
        self.assertIs(self.status_of()["connectors"][0]["configured"], True)
        before = (self.root / "alice/config.yaml").read_bytes()

        repeated = self.bind(browser=BROWSER_ADDRESS)

        self.assertEqual(repeated.status_code, 200, repeated.body)
        self.assertEqual({key: repeated.body[key] for key in ("changed", "restart_required")},
                         {"changed": False, "restart_required": False})
        self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)

    def test_binding_without_an_address_writes_an_empty_value(self):
        """중계가 꺼져 주소가 빈 값으로 오거나 오지 않아도 붙이기는 막지 않고 빈 값을 넣는다."""
        demo, _ = self.bind_fixture()
        self.declare_owner_browser(demo)

        for label, browser in (("empty", ""), ("missing", None)):
            with self.subTest(label):
                response = self.bind(browser=browser)

                self.assertEqual(response.status_code, 200, response.body)
                self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"]["DEMO_BROWSER_URL"], "")
                self.assertIs(self.status_of()["connectors"][0]["configured"], True)

    def test_connector_without_the_declaration_ignores_the_address(self):
        """선언하지 않은 커넥터는 주소를 받아도 서버 정의 env 에 이름을 더하지 않는다."""
        self.bind_fixture()

        response = self.bind(browser=BROWSER_ADDRESS)

        self.assertEqual(response.status_code, 200, response.body)
        self.assertNotIn("DEMO_BROWSER_URL", self.alice_config()["mcp_servers"]["demo"]["env"])

    def test_malformed_address_is_rejected_before_anything_changes(self):
        """문자열이 아니거나 중계 주소 모양이 아닌 `owner_browser` 는 400 이고 profile 을 바꾸지 않는다."""
        demo, _ = self.bind_fixture()
        self.declare_owner_browser(demo)
        before = self.tree("alice")
        for label, value in (
            ("not a string", 1),
            ("ftp scheme", "ftp://cp.example.test/internal/browser-gateway/b1"),
            ("no path", "http://cp.example.test"),
            ("query", BROWSER_ADDRESS + "?x=1"),
            ("trailing newline", BROWSER_ADDRESS + "\n"),
        ):
            with self.subTest(label):
                self.assertEqual(self.bind(browser=value).status_code, 400)
                self.assertEqual(self.tree("alice"), before)

    def test_rebinding_removes_the_env_line_of_a_field_no_longer_declared(self):
        """옛 소유 기록이 참조하던 칸이 지금 manifest 에 없으면 다시 붙일 때 그 `.env` 줄을 지운다.

        브라우저 주소를 연결 칸으로 받던 커넥터가 중계 주소를 받도록 바뀐 profile 이다. 옛 주소 줄이 남지 않는다.
        """
        demo, _ = self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        state_path = self.root / "alice/.fos-connectors.json"
        record = json.loads(state_path.read_text(encoding="utf-8"))
        record[DEMO]["server"]["env"]["DEMO_OLD_URL"] = "${DEMO_OLD_URL}"
        state_path.write_text(json.dumps(record), encoding="utf-8")
        config = self.alice_config()
        config["mcp_servers"]["demo"] = record[DEMO]["server"]
        self.write_config("alice", config)
        env_path = self.root / "alice/.env"
        env_path.write_text(env_path.read_text(encoding="utf-8") + "DEMO_OLD_URL=http://192.0.2.1:9222\n",
                            encoding="utf-8")
        self.declare_owner_browser(demo)

        response = self.bind(browser=BROWSER_ADDRESS)

        self.assertEqual(response.status_code, 200, response.body)
        lines = env_path.read_text(encoding="utf-8").splitlines()
        self.assertEqual([line for line in lines if line.startswith("DEMO_OLD_URL=")], [], lines)
        self.assertIn("DEMO_TOKEN=" + DEMO_VALUE, lines)
        server_env = self.alice_config()["mcp_servers"]["demo"]["env"]
        self.assertNotIn("DEMO_OLD_URL", server_env)
        self.assertEqual(server_env["DEMO_BROWSER_URL"], BROWSER_ADDRESS)

    def test_vault_with_a_field_no_longer_declared_still_binds(self):
        """지금 manifest 에 없는 칸이 남은 보관 파일로도 설치된다. 남은 칸만 `.env` 에 쓴다. 보관 파일을 쓰는 검사는 그대로 거절한다."""
        self.bind_fixture()
        vault = self.hermes_root / "connector-vault" / "c3.json"
        vault.write_text(json.dumps({"v": 1, "connector": DEMO,
                                     "values": {"token": DEMO_VALUE, "retired": "old-value"}}), encoding="utf-8")

        response = self.bind(vault="c3")

        self.assertEqual(response.status_code, 200, response.body)
        env = (self.root / "alice/.env").read_text(encoding="utf-8")
        self.assertIn("DEMO_TOKEN=" + DEMO_VALUE, env.splitlines())
        self.assertNotIn("old-value", env)
        written = self.vault("PUT", vault="c4", connector=DEMO, values={"token": DEMO_VALUE, "retired": "old-value"})
        self.assertEqual(written.status_code, 400)
