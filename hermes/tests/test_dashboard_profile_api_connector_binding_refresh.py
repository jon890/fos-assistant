"""바뀐 connector manifest 로 바인딩을 다시 설치하는 경로를 검사한다."""

import json
import os
import sys
from unittest import mock

from dashboard_profile_api_support import DEMO
import dashboard_profile_api_support as support


class ProfileApiConnectorBindingRefreshTest(support.ProfileApiRouteTest):
    def test_rebinding_accepts_an_added_manifest_env_and_keeps_vault_values(self):
        """manifest 에 env 하나를 더한 뒤 같은 보관 파일로 다시 붙이면 새 정의를 기록한다."""
        connector, _ = self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        self.assertEqual(self.bind("other-notes", "c2").status_code, 200)
        before_env = (self.root / "alice/.env").read_bytes()
        before_vault = {path.name: path.read_bytes() for path in self.hermes_root.rglob("*") if path.is_file()}
        before_other = json.loads((self.root / "alice/.fos-connectors.json").read_text(encoding="utf-8"))["other-notes"]

        self.declare_owner_output(connector)

        rebound = self.bind()

        self.assertEqual(rebound.status_code, 200, rebound.body)
        self.assertIs(rebound.body["restart_required"], True)
        self.assertEqual((self.root / "alice/.env").read_bytes(), before_env)
        self.assertEqual({path.name: path.read_bytes() for path in self.hermes_root.rglob("*") if path.is_file()}, before_vault)
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"]["DEMO_OUTPUT_DIR"], "")
        record = json.loads((self.root / "alice/.fos-connectors.json").read_text(encoding="utf-8"))
        self.assertEqual(record[DEMO]["server"], self.alice_config()["mcp_servers"]["demo"])
        self.assertEqual(record["other-notes"], before_other)
        repeated = self.bind()
        self.assertEqual({key: repeated.body[key] for key in ("changed", "restart_required")},
                         {"changed": False, "restart_required": False})
        self.assertTrue(self.status_of()["connectors"][0]["configured"])
        self.assertEqual(self.connector_probe(), 204)

    def test_rebinding_refreshes_command_and_args_without_touching_other_profiles_or_connectors(self):
        """실행 파일과 인자가 바뀌어도 재설치하고 다른 바인딩과 profile 은 보존한다."""
        connector, _ = self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        self.assertEqual(self.bind("other-notes", "c2").status_code, 200)
        other_before = json.loads((self.root / "alice/.fos-connectors.json").read_text(encoding="utf-8"))["other-notes"]
        owner_before = self.tree("owner")
        replacement = self.root.parent / "bin/replacement-runner"
        replacement.write_text("#!/bin/sh\n", encoding="utf-8")
        replacement.chmod(0o755)
        roots = json.loads(os.environ["FOS_ASSISTANT_CONNECTOR_ROOTS"])
        roots[DEMO]["command"] = str(replacement)
        os.environ["FOS_ASSISTANT_CONNECTOR_ROOTS"] = json.dumps(roots)
        mcp_path = connector / ".mcp.json"
        declared = json.loads(mcp_path.read_text(encoding="utf-8"))
        declared["mcpServers"]["demo"]["args"].append("--refresh")
        mcp_path.write_text(json.dumps(declared), encoding="utf-8")

        refreshed = self.bind()

        self.assertEqual(refreshed.status_code, 200, refreshed.body)
        self.assertIs(refreshed.body["restart_required"], True)
        self.assertEqual(self.connector_probe(), 204)
        state = json.loads((self.root / "alice/.fos-connectors.json").read_text(encoding="utf-8"))
        self.assertEqual(state[DEMO]["server"], self.alice_config()["mcp_servers"]["demo"])
        self.assertEqual(state["other-notes"], other_before)
        self.assertEqual(self.tree("owner"), owner_before)
        before = self.tree("alice")
        with mock.patch.object(self.plugin.logger, "warning") as warning:
            self.assertEqual(self.bind().body["changed"], False)
        self.assertNotIn("정의 차이", " ".join(map(str, warning.call_args_list)))
        self.assertEqual(self.tree("alice"), before)

    def test_rebinding_rejects_operator_server_change_without_writing_files(self):
        """운영자가 바꾼 실제 서버 정의는 새 manifest 가 있어도 409 으로 보존한다."""
        connector, _ = self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        self.declare_owner_output(connector)
        original = self.alice_config()
        for change in ("edit", "delete"):
            with self.subTest(change):
                config = json.loads(json.dumps(original))
                if change == "edit":
                    config["mcp_servers"]["demo"]["env"]["OPERATOR_EDIT"] = "keep"
                else:
                    del config["mcp_servers"]["demo"]
                self.write_config("alice", config)
                before = self.tree("alice")
                response = self.bind()
                self.assertEqual(response.status_code, 409, response.body)
                self.assertEqual(self.tree("alice"), before)
                self.assertEqual(self.bind().status_code, 409)
                self.write_config("alice", original)

    def test_rebinding_rejects_manifest_server_name_change_that_matches_an_operator_server(self):
        """새 manifest 서버 이름에 운영자가 같은 정의를 넣어도 소유 기록의 이름은 자동 이관하지 않는다."""
        connector, _ = self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        mcp_path = connector / ".mcp.json"
        declared = json.loads(mcp_path.read_text(encoding="utf-8"))
        server = declared["mcpServers"].pop("demo")
        declared["mcpServers"]["demo-renamed"] = server
        mcp_path.write_text(json.dumps(declared), encoding="utf-8")
        config = self.alice_config()
        config["mcp_servers"]["demo-renamed"] = config["mcp_servers"]["demo"]
        config["platform_toolsets"]["api_server"].append("demo-renamed")
        self.write_config("alice", config)
        before = self.tree("alice")

        response = self.bind()

        self.assertEqual(response.status_code, 409, response.body)
        self.assertEqual(self.tree("alice"), before)

    def test_rebinding_removes_renamed_field_env_and_detach_removes_the_new_secret(self):
        """같은 보관 파일의 field env 이름을 바꾸면 옛 줄을 지우고 이후 떼기도 새 줄을 지운다."""
        connector, _ = self.bind_fixture()
        self.assertEqual(self.vault("PUT", vault="c1", connector=DEMO,
                                    values={"token": "demo_ok_0123456789", "scope": "scope-a"}).status_code, 200)
        self.assertEqual(self.bind().status_code, 200)
        self.assertEqual(self.bind("other-notes", "c2").status_code, 200)
        vault_before = {path.name: path.read_bytes() for path in self.hermes_root.rglob("*") if path.is_file()}
        connector_path = connector / "connector.json"
        declared = json.loads(connector_path.read_text(encoding="utf-8"))
        declared["fields"][0]["env"] = "DEMO_TOKEN_RENAMED"
        declared["fields"][1]["env"] = "DEMO_SCOPE_RENAMED"
        connector_path.write_text(json.dumps(declared), encoding="utf-8")
        mcp_path = connector / ".mcp.json"
        mcp = json.loads(mcp_path.read_text(encoding="utf-8"))
        env = mcp["mcpServers"]["demo"]["env"]
        env.pop("DEMO_TOKEN")
        env.pop("DEMO_SCOPE")
        env["DEMO_TOKEN_RENAMED"] = "${DEMO_TOKEN_RENAMED}"
        env["DEMO_SCOPE_RENAMED"] = "${DEMO_SCOPE_RENAMED:-}"
        mcp_path.write_text(json.dumps(mcp), encoding="utf-8")

        rebound = self.bind()

        self.assertEqual(rebound.status_code, 200, rebound.body)
        lines = (self.root / "alice/.env").read_text(encoding="utf-8")
        self.assertNotIn("DEMO_TOKEN=", lines)
        self.assertNotIn("DEMO_SCOPE=", lines)
        self.assertIn("DEMO_TOKEN_RENAMED=demo_ok_0123456789", lines)
        self.assertIn("DEMO_SCOPE_RENAMED=scope-a", lines)
        self.assertIn("OTHER_TOKEN=other_1234", lines)
        self.assertEqual({path.name: path.read_bytes() for path in self.hermes_root.rglob("*") if path.is_file()}, vault_before)
        self.assertEqual(self.bind(enabled=False).status_code, 200)
        after = (self.root / "alice/.env").read_text(encoding="utf-8")
        self.assertNotIn("DEMO_TOKEN_RENAMED", after)
        self.assertNotIn("DEMO_SCOPE_RENAMED", after)
        self.assertIn("OTHER_TOKEN=other_1234", after)

    def test_rebinding_rejects_renamed_field_env_that_an_operator_already_uses(self):
        """새 field env 이름이 profile의 비커넥터 값과 겹치면 그 값을 덮어쓰지 않는다."""
        connector, _ = self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        vault_before = {path.name: path.read_bytes() for path in self.hermes_root.rglob("*") if path.is_file()}
        connector_path = connector / "connector.json"
        declared = json.loads(connector_path.read_text(encoding="utf-8"))
        declared["fields"][0]["env"] = "DEMO_REPLACEMENT_TOKEN"
        connector_path.write_text(json.dumps(declared), encoding="utf-8")
        mcp_path = connector / ".mcp.json"
        mcp = json.loads(mcp_path.read_text(encoding="utf-8"))
        env = mcp["mcpServers"]["demo"]["env"]
        env.pop("DEMO_TOKEN")
        env["DEMO_REPLACEMENT_TOKEN"] = "${DEMO_REPLACEMENT_TOKEN}"
        mcp_path.write_text(json.dumps(mcp), encoding="utf-8")
        env_path = self.root / "alice/.env"
        env_path.write_text(env_path.read_text(encoding="utf-8") + "DEMO_REPLACEMENT_TOKEN=operator-value\n",
                            encoding="utf-8")
        before = self.tree("alice")

        response = self.bind()

        self.assertEqual(response.status_code, 409, response.body)
        self.assertEqual(self.tree("alice"), before)
        self.assertEqual({path.name: path.read_bytes() for path in self.hermes_root.rglob("*") if path.is_file()}, vault_before)

    def test_failure_logs_only_stage_id_exception_class_and_fixed_mismatch_names(self):
        """실패 로그는 비밀값이나 예외 본문 없이 진단에 필요한 고정 정보만 남긴다."""
        connector, _ = self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        self.declare_owner_output(connector)
        config = self.alice_config()
        config["mcp_servers"]["demo"]["env"]["OPERATOR_EDIT"] = "keep"
        self.write_config("alice", config)
        with mock.patch.object(self.plugin.logger, "warning") as warning:
            response = self.bind()
        self.assertEqual(response.status_code, 409, response.body)
        logged = " ".join(" ".join(map(str, call.args)) for call in warning.call_args_list)
        self.assertIn("bind", logged)
        self.assertIn(DEMO, logged)
        self.assertIn("FileExistsError", logged)
        self.assertIn("env", logged)
        self.assertNotIn("keep", logged)
        self.assertNotIn("demo_ok_0123456789", logged)

    def test_503_log_does_not_expose_vault_value_or_exception_text(self):
        """설치 예외의 503도 보관 값이나 예외 본문을 로그에 싣지 않는다."""
        self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        install_module = sys.modules[self.plugin._connector_request.__module__]
        with mock.patch.object(install_module, "_connector_bind_config",
                               side_effect=RuntimeError("synthetic secret demo_ok_0123456789 /private/path")), \
                mock.patch.object(self.plugin.logger, "warning") as warning:
            response = self.bind()
        self.assertEqual(response.status_code, 503, response.body)
        logged = " ".join(" ".join(map(str, call.args)) for call in warning.call_args_list)
        self.assertIn("bind", logged)
        self.assertIn(DEMO, logged)
        self.assertIn("RuntimeError", logged)
        self.assertNotIn("demo_ok_0123456789", logged)
        self.assertNotIn("synthetic secret", logged)
        self.assertNotIn("/private/path", logged)
