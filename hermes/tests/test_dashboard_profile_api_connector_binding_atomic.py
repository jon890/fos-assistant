"""바인딩 설치의 부분 쓰기와 소유 기록 복구를 합성 오류로 검사한다."""

import json
from dashboard_profile_api_support import DEMO
import dashboard_profile_api_support as support
from plugin_loading import patch_plugin


class ProfileApiConnectorBindingAtomicTest(support.ProfileApiRouteTest):
    def files(self):
        return {name: value for name, value in self.tree("alice").items()
                if not name.startswith("connector-backups")}

    def add_excluded_tool(self, connector):
        manifest = connector / "connector.json"
        declared = json.loads(manifest.read_text(encoding="utf-8"))
        declared["schema"] = 2
        declared["tools"] = {"list_scopes": {"risk": "READ"},
                             "write_note": {"risk": "WRITE", "approval": "always"}}
        manifest.write_text(json.dumps(declared), encoding="utf-8")

    def test_state_write_failure_restores_config_and_retry_succeeds(self):
        """설정만 쓴 뒤 소유 기록 쓰기가 실패하면 이전 파일을 복원하고 재시도할 수 있다."""
        self.bind_fixture()
        before = self.files()
        write = self.plugin._atomic_private_write
        state = self.root / "alice/.fos-connectors.json"

        def fail(target, value):
            if target == state:
                raise OSError("injected")
            return write(target, value)

        with patch_plugin(self.plugin, "_atomic_private_write", side_effect=fail):
            self.assertEqual(self.bind().status_code, 503)
        self.assertEqual(self.files(), before)
        self.assertEqual(self.bind().status_code, 200)

    def test_error_after_config_replace_restores_the_replaced_file(self):
        """교체 함수가 파일을 바꾸고 예외를 내도 그 파일을 복원한다."""
        self.bind_fixture()
        before = self.files()
        write = self.plugin._atomic_private_write
        config = self.root / "alice/config.yaml"
        failed = False

        def fail(target, value):
            nonlocal failed
            write(target, value)
            if target == config and not failed:
                failed = True
                raise OSError("injected after replace")

        with patch_plugin(self.plugin, "_atomic_private_write", side_effect=fail):
            self.assertEqual(self.bind().status_code, 503)
        self.assertTrue(failed)
        self.assertEqual(self.files(), before)

    def test_one_restore_failure_does_not_skip_config_and_state_restore(self):
        """env 복원이 실패해도 설정과 소유 기록의 복원을 계속한다."""
        connector, _ = self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        profile = self.root / "alice"
        before = self.files()
        self.add_excluded_tool(connector)
        self.assertEqual(self.vault("PUT", vault="c1", connector=DEMO,
                                    values={"token": "demo_new_9876543210"}).status_code, 200)
        write = self.plugin._atomic_private_write
        failed = False

        def fail(target, value):
            nonlocal failed
            if target == profile / ".env":
                if failed:
                    raise OSError("injected restore failure")
                write(target, value)
                failed = True
                raise OSError("injected after env replace")
            return write(target, value)

        with patch_plugin(self.plugin, "_atomic_private_write", side_effect=fail):
            self.assertEqual(self.bind().status_code, 503)
        after = self.files()
        self.assertEqual(after["config.yaml"], before["config.yaml"])
        self.assertEqual(after[".fos-connectors.json"], before[".fos-connectors.json"])
        self.assertEqual(self.bind().status_code, 200)

    def test_rebind_repairs_only_the_owned_exclusion_difference(self):
        """설정만 새 exclude이고 소유 기록은 옛 정의이면 같은 설치로 복구한다."""
        connector, _ = self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        self.add_excluded_tool(connector)
        config = self.alice_config()
        config["mcp_servers"]["demo"]["tools"] = {"exclude": ["write_note"]}
        self.write_config("alice", config)
        response = self.bind()
        self.assertEqual(response.status_code, 200, response.body)
        self.assertTrue(response.body["restart_required"])
        state = json.loads((self.root / "alice/.fos-connectors.json").read_text(encoding="utf-8"))
        self.assertEqual(state[DEMO]["server"], self.alice_config()["mcp_servers"]["demo"])
        self.assertTrue(self.status_of()["connectors"][0]["configured"])
        self.assertEqual(self.connector_probe(), 204)
        self.assertFalse(self.bind().body["changed"])

    def test_rebind_repairs_a_changed_or_removed_exclusion(self):
        """옛 exclude가 있던 기록도 새 목록이나 목록 제거만 차이 나면 복구한다."""
        connector, _ = self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        state_path = self.root / "alice/.fos-connectors.json"
        original = json.loads(state_path.read_text(encoding="utf-8"))
        for excluded in (["write_note"], None):
            with self.subTest(excluded=excluded):
                state = json.loads(json.dumps(original))
                state[DEMO]["server"]["tools"] = {"exclude": ["old_tool"]}
                state_path.write_text(json.dumps(state), encoding="utf-8")
                declared = json.loads((connector / "connector.json").read_text(encoding="utf-8"))
                if excluded:
                    self.add_excluded_tool(connector)
                else:
                    declared.pop("tools")
                    declared["schema"] = 1
                    (connector / "connector.json").write_text(json.dumps(declared), encoding="utf-8")
                config = self.alice_config()
                if excluded:
                    config["mcp_servers"]["demo"]["tools"] = {"exclude": excluded}
                else:
                    config["mcp_servers"]["demo"].pop("tools")
                self.write_config("alice", config)
                response = self.bind()
                self.assertEqual(response.status_code, 200, response.body)
                state = json.loads(state_path.read_text(encoding="utf-8"))
                self.assertEqual(state[DEMO]["server"], self.alice_config()["mcp_servers"]["demo"])

    def test_recovery_rejects_other_differences_and_missing_ownership(self):
        """tools가 새 정의여도 다른 변경과 소유 기록 없는 서버는 받아들이지 않는다."""
        connector, _ = self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        self.add_excluded_tool(connector)
        original = self.alice_config()
        cases = ({"command": "operator-command"}, {"args": ["operator-arg"]},
                 {"env": {}}, {"enabled": False},
                 {"tools": {"exclude": ["operator-tool"]}},
                 {"tools": {"exclude": ["write_note"], "include": ["list_scopes"]}})
        for changes in cases:
            with self.subTest(changes=changes):
                config = json.loads(json.dumps(original))
                config["mcp_servers"]["demo"].update(tools={"exclude": ["write_note"]})
                config["mcp_servers"]["demo"].update(changes)
                self.write_config("alice", config)
                before = self.tree("alice")
                self.assertEqual(self.bind().status_code, 409)
                self.assertEqual(self.tree("alice"), before)
        original["mcp_servers"]["demo"]["tools"] = {"exclude": ["write_note"]}
        self.write_config("alice", original)
        (self.root / "alice/.fos-connectors.json").unlink()
        before = self.tree("alice")
        self.assertEqual(self.bind().status_code, 409)
        self.assertEqual(self.tree("alice"), before)
