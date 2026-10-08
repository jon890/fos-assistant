"""dashboard-profile-api 의 connector_binding_detach 분기를 검사한다."""

import contextlib
import json
import os
from unittest import mock
from plugin_loading import load_plugin, patch_plugin
from dashboard_profile_api_support import DEMO, DEMO_VALUE, OTHER, OTHER_VALUE
import dashboard_profile_api_support as support


class ProfileApiConnectorBindingDetachTest(support.ProfileApiRouteTest):
    def test_detaching_replaces_the_skill_index_marker_and_keeps_operator_names(self):
        """떼면 재시작 없이 반영되고, 색인 표식만 새 값으로 바뀌며 운영자가 넣은 다른 이름은 남는다."""
        self.bind_fixture()
        self.assertEqual(self.bind(DEMO, "c1").status_code, 200)
        config = self.alice_config()
        old_marker = config["skills"]["disabled"][0]
        config["skills"]["disabled"] = ["operator-skill", old_marker]
        self.write_config("alice", config)

        removed = self.bind(DEMO, enabled=False)
        self.assertEqual(removed.status_code, 200, removed.body)
        self.assertEqual({key: removed.body[key] for key in ("changed", "restart_required", "reload_pending")},
                         {"changed": True, "restart_required": False, "reload_pending": True})
        disabled = self.alice_config()["skills"]["disabled"]
        self.assertEqual(len(disabled), 2, disabled)
        self.assertEqual(disabled[0], "operator-skill")
        self.assertTrue(disabled[1].startswith("fos-skill-index-"), disabled)
        self.assertNotEqual(disabled[1], old_marker)

    def test_detaching_one_binding_removes_only_its_name_env_and_skills(self):
        """하나를 떼면 그 서버와 이름과 env 와 스킬만 빠지고 다른 바인딩은 남으며 재시작이 필요 없다."""
        self.bind_fixture()
        self.assertEqual(self.bind(DEMO, "c1").status_code, 200)
        self.assertEqual(self.bind(OTHER, "c2").status_code, 200)
        before = self.alice_config()

        removed = self.bind(DEMO, enabled=False)
        self.assertEqual(removed.status_code, 200, removed.body)
        self.assertIs(removed.body["changed"], True)
        self.assertIs(removed.body["restart_required"], False)
        config = self.alice_config()
        self.assertNotIn("demo", config["mcp_servers"])
        self.assertEqual(config["mcp_servers"]["other"], before["mcp_servers"]["other"])
        self.assertEqual(config["mcp_servers"]["fos-assistant"], before["mcp_servers"]["fos-assistant"])
        self.assertEqual(config["platform_toolsets"]["api_server"], ["delegation", "fos-assistant", "terminal", "other"])
        self.assertEqual((self.root / "alice/.env").read_text(encoding="utf-8").splitlines(),
                         ["OTHER_TOKEN=" + OTHER_VALUE, "OTHER_SCOPE=a"])
        self.assertFalse((self.root / "alice/skills/demo").exists())
        self.assertTrue((self.root / "alice/skills/other/SKILL.md").is_file())
        self.assertEqual(sorted(json.loads((self.root / "alice/.fos-connectors.json").read_text())), [OTHER])
        # 뗀 서버는 빈 `tools` 로 대응에 남는다. 떼기 전에 시작한 실행이 그 서버를 쥐고 있어도 hook 이 묻는다.
        self.assertEqual(self.tool_map()["servers"]["demo"], {"connector": DEMO, "prefix": "mcp__demo__", "tools": {}})
        self.assertIn("mcp__other__list_scopes", self.tool_map()["servers"]["other"]["tools"])
        self.assertEqual((self.root / "alice/SOUL.md").read_text(encoding="utf-8"), "사용자의 성격\n")
        self.assertEqual(self.status_of()["connectors"], [
            {"plugin": DEMO, "enabled": False, "configured": False, "mode": "isolated"},
            {"plugin": OTHER, "enabled": True, "configured": True, "mode": "bind"}])

        # 마지막 바인딩을 떼도 대응 파일은 뗀 서버를 빈 `tools` 로 싣고 남는다. 목록은 붙이기 전으로 돌아간다.
        last = self.bind(OTHER, enabled=False)
        self.assertEqual(last.status_code, 200)
        self.assertIs(last.body["restart_required"], False)
        self.assertEqual(self.tool_map(), {"v": 1, "isolated": False, "servers": {
            "demo": {"connector": DEMO, "prefix": "mcp__demo__", "tools": {}},
            "other": {"connector": OTHER, "prefix": "mcp__other__", "tools": {}}}})
        self.assertEqual(json.loads((self.root / "alice/.fos-connectors.json").read_text()), {})
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"],
                         ["delegation", "fos-assistant", "terminal"])
        self.assertEqual((self.root / "alice/.env").read_text(encoding="utf-8"), "")
        self.assertEqual(list((self.root / "alice/skills").iterdir()), [])
        self.assertIs(self.bind(OTHER, enabled=False).body["changed"], False)

    def test_detaching_after_the_operator_changes_the_run_definition_removes_server_env_and_skills(self):
        """운영자가 커넥터의 실행 정의를 바꾸거나 운영 목록에서 빼도 떼기는 그 서버와 이름과 env 와 스킬을 지운다."""
        demo, _ = self.bind_fixture()
        alice = self.root / "alice"
        for label in ("run definition changed", "removed from operator list"):
            with self.subTest(label):
                self.assertEqual(self.vault("PUT", vault="c1", connector=DEMO,
                                            values={"token": DEMO_VALUE, "scope": "a"}).status_code, 200)
                self.assertEqual(self.bind(DEMO, "c1").status_code, 200)
                self.assertEqual(self.bind(OTHER, "c2").status_code, 200)
                (alice / ".env").write_text((alice / ".env").read_text(encoding="utf-8") + "KEEP=me\n",
                                            encoding="utf-8")
                declared = json.loads((demo / ".mcp.json").read_text(encoding="utf-8"))
                original = json.dumps(declared)
                if label == "run definition changed":
                    # 서버의 실행 인자와 칸 env 이름이 함께 바뀐다. 지금 manifest 로는 기록을 검증할 수 없다.
                    server = declared["mcpServers"]["demo"]
                    server["args"].append("--verbose")
                    server["env"]["DEMO_SECRET"] = server["env"].pop("DEMO_TOKEN").replace("TOKEN", "SECRET")
                    connector_json = json.loads((demo / "connector.json").read_text(encoding="utf-8"))
                    connector_original = json.dumps(connector_json)
                    connector_json["fields"][0]["env"] = "DEMO_SECRET"
                    (demo / "connector.json").write_text(json.dumps(connector_json), encoding="utf-8")
                    (demo / ".mcp.json").write_text(json.dumps(declared), encoding="utf-8")
                    context = contextlib.nullcontext()
                else:
                    connector_original = None
                    roots = json.loads(os.environ["FOS_ASSISTANT_CONNECTOR_ROOTS"])
                    roots.pop(DEMO)
                    context = mock.patch.dict(os.environ, {"FOS_ASSISTANT_CONNECTOR_ROOTS": json.dumps(roots)})
                with context:
                    removed = self.bind(DEMO, enabled=False)
                self.assertEqual(removed.status_code, 200, removed.body)
                self.assertIs(removed.body["changed"], True)
                config = self.alice_config()
                self.assertNotIn("demo", config["mcp_servers"])
                self.assertEqual(config["platform_toolsets"]["api_server"],
                                 ["delegation", "fos-assistant", "terminal", "other"])
                self.assertEqual((alice / ".env").read_text(encoding="utf-8").splitlines(),
                                 ["OTHER_TOKEN=" + OTHER_VALUE, "OTHER_SCOPE=a", "KEEP=me"])
                self.assertFalse((alice / "skills/demo").exists())
                self.assertEqual(sorted(json.loads((alice / ".fos-connectors.json").read_text())), [OTHER])
                # 다음 경우를 위해 되돌린다.
                (demo / ".mcp.json").write_text(original, encoding="utf-8")
                if connector_original is not None:
                    (demo / "connector.json").write_text(connector_original, encoding="utf-8")
                self.assertEqual(self.bind(OTHER, enabled=False).status_code, 200)
                (alice / ".env").write_text("", encoding="utf-8")

    def test_detaching_keeps_another_binding_under_its_recorded_name_when_its_manifest_changed(self):
        """다른 바인딩 항목의 서버 이름이나 실행 정의를 manifest 에서 바꾼 뒤 이 커넥터를 떼면 대응 파일에 기록의 이름이 빈 tools 로 남는다."""
        _, other = self.bind_fixture()
        alice = self.root / "alice"
        original = (other / ".mcp.json").read_text(encoding="utf-8")
        for label in ("server renamed", "run definition changed"):
            with self.subTest(label):
                self.assertEqual(self.bind(DEMO, "c1").status_code, 200)
                self.assertEqual(self.bind(OTHER, "c2").status_code, 200)
                declared = json.loads(original)
                if label == "server renamed":
                    declared["mcpServers"] = {"other-renamed": declared["mcpServers"]["other"]}
                else:
                    declared["mcpServers"]["other"]["args"].append("--verbose")
                (other / ".mcp.json").write_text(json.dumps(declared), encoding="utf-8")

                removed = self.bind(DEMO, enabled=False)
                self.assertEqual(removed.status_code, 200, removed.body)
                self.assertIs(removed.body["changed"], True)
                # config.yaml 에 남은 서버는 기록의 이름이다. 대응에서 빠지면 hook 이 그 서버를 판정 없이 통과시킨다.
                # 뗀 서버도 빈 `tools` 로 남는다.
                self.assertIn("other", self.alice_config()["mcp_servers"])
                self.assertEqual(self.tool_map(), {"v": 1, "isolated": False, "servers": {
                    "demo": {"connector": DEMO, "prefix": "mcp__demo__", "tools": {}},
                    "other": {"connector": OTHER, "prefix": "mcp__other__", "tools": {}}}})

                # 다음 경우를 위해 되돌린다. 뗀 서버는 대응에 빈 `tools` 로 남는다.
                (other / ".mcp.json").write_text(original, encoding="utf-8")
                self.assertEqual(self.bind(OTHER, enabled=False).status_code, 200)
                self.assertEqual({name: server["tools"] for name, server in self.tool_map()["servers"].items()},
                                 {"demo": {}, "other": {}})

    def test_detaching_refuses_a_binding_entry_whose_names_cannot_be_paths(self):
        """떼기는 기록을 모양만 보지만, 지울 서버 이름이나 스킬 이름이 경로 조각이 될 수 없으면 아무 파일도 바꾸지 않는다."""
        self.bind_fixture()
        self.assertEqual(self.bind(DEMO, "c1").status_code, 200)
        state_path = self.root / "alice/.fos-connectors.json"
        record = json.loads(state_path.read_text(encoding="utf-8"))
        record[DEMO]["skills"] = ["../outside"]
        state_path.write_text(json.dumps(record), encoding="utf-8")
        before = self.tree("alice")

        self.assertEqual(self.bind(DEMO, enabled=False).status_code, 503)

        self.assertEqual(self.tree("alice"), before)

    def test_detached_server_stays_in_the_tool_map_so_a_running_run_is_still_judged(self):
        """뗀 서버는 대응에 빈 `tools` 로 남아 fos-ctx 가 그 도구를 정책에 묻는다. 다시 붙이면 뗀 기록이 지워진다."""
        self.bind_fixture()
        alice = self.root / "alice"
        detached = alice / self.plugin.CONNECTOR_DETACHED
        # 처음 붙이는 새 이름은 gateway 가 다음 설정 맞추기에서 연결할 수 있다.
        initial = self.bind(DEMO, "c1")
        self.assertEqual(initial.status_code, 200, initial.body)
        self.assertEqual({key: initial.body[key] for key in ("changed", "restart_required", "reload_pending")},
                         {"changed": True, "restart_required": False, "reload_pending": True})
        removed = self.bind(DEMO, enabled=False)
        self.assertEqual(removed.status_code, 200, removed.body)
        self.assertNotIn("demo", self.alice_config()["mcp_servers"])
        self.assertEqual(self.tool_map(), {"v": 1, "isolated": False, "servers": {
            "demo": {"connector": DEMO, "prefix": "mcp__demo__", "tools": {}}}})
        self.assertEqual(json.loads(detached.read_text(encoding="utf-8")), {DEMO: "demo"})

        # 다른 커넥터의 새 이름은 재시작 없이 반영을 기다리고, 뗀 DEMO 기록은 보존한다.
        other = self.bind(OTHER, "c2")
        self.assertEqual(other.status_code, 200, other.body)
        self.assertEqual({key: other.body[key] for key in ("changed", "restart_required", "reload_pending")},
                         {"changed": True, "restart_required": False, "reload_pending": True})
        self.assertEqual(json.loads(detached.read_text(encoding="utf-8")), {DEMO: "demo"})
        # 대응 파일이 소유 기록과 뗀 기록으로 계산한 것과 같으므로 hook 상태는 참이다.
        self.assertIs(self.status_of()["policy_hook"], True)

        # 떼기 전에 시작한 실행은 그 서버를 쥐고 있다. 그 profile 에 설치된 fos-ctx 가 쓰기 도구를 정책에 묻는다.
        ctx = load_plugin("fos_ctx_installed", alice / "plugins/fos-ctx/__init__.py", self.addCleanup)
        asked = []

        def policy(tool_name, args, session_id, tool_call_id, servers):
            asked.append((tool_name, ctx._connector_server(tool_name, servers)["connector"]))
            return {"action": "block", "message": "붙은 연결이 없다"}

        read_tool_map = ctx.read_tool_map
        with patch_plugin(ctx, "read_tool_map", lambda: read_tool_map(alice)), \
                patch_plugin(ctx, "connector_policy", side_effect=policy):
            result = ctx.pre_tool_call(tool_name="mcp__demo__write_note", args={"text": "x"},
                                       session_id="session-1", tool_call_id="call-1")
        self.assertEqual(result, {"action": "block", "message": "붙은 연결이 없다"})
        self.assertEqual(asked, [("mcp__demo__write_note", DEMO)])

        # 다시 보낸 떼기는 바꾸지 않고, 뗀 기록이 남은 profile 에는 옛 설치를 하지 않는다.
        before = self.tree("alice")
        self.assertIs(self.bind(DEMO, enabled=False).body["changed"], False)
        self.assertEqual(self.connector().status_code, 409)
        self.assertEqual(self.tree("alice"), before)

        # 같은 이름을 다시 붙이면 gateway 가 옛 연결을 쥐고 있을 수 있어 재시작을 기다린다.
        rebound = self.bind(DEMO, "c1")
        self.assertEqual(rebound.status_code, 200, rebound.body)
        self.assertEqual({key: rebound.body[key] for key in ("changed", "restart_required", "reload_pending")},
                         {"changed": True, "restart_required": True, "reload_pending": False})
        self.assertFalse(detached.exists())
        self.assertEqual(self.tool_map()["servers"]["demo"]["tools"], {"mcp__demo__list_scopes": "list_scopes"})
        self.assertIs(self.status_of()["policy_hook"], True)
        # 뗀 기록이 지워진 뒤 같은 요청을 재시도하면 아무것도 바꾸지 않는다.
        retry = self.bind(DEMO, "c1")
        self.assertEqual(retry.status_code, 200, retry.body)
        self.assertEqual({key: retry.body[key] for key in ("changed", "restart_required", "plugin_updated", "reload_pending")},
                         {"changed": False, "restart_required": False, "plugin_updated": False, "reload_pending": False})

    def test_detaching_again_on_a_profile_with_only_the_connector_marker_succeeds(self):
        """커넥터 표식만 있는 profile 에서 떼기를 다시 보내거나 붙인 적 없는 커넥터를 떼면 바꾸지 않고 200 이다."""
        self.bind_fixture()
        self.host_profile()
        self.assertEqual(self.bind(profile="human").status_code, 200)

        first = self.bind(profile="human", enabled=False)
        self.assertEqual(first.status_code, 200, first.body)
        self.assertIs(first.body["changed"], True)
        before = self.tree("human")
        for plugin in (DEMO, OTHER):
            with self.subTest(plugin):
                again = self.bind(plugin, profile="human", enabled=False)
                self.assertEqual(again.status_code, 200, again.body)
                self.assertIs(again.body["changed"], False)
                self.assertEqual(self.tree("human"), before)
