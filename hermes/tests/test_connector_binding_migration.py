"""기동 때 이미 붙은 로컬 바인딩을 실행기 정의로 옮기는 이관을 검사한다(docs/features/connector/spec.md 의 REQ7, DECISION10)."""

import contextlib
import json
import logging
import os
import sys
from unittest import mock

from dashboard_profile_api_support import (DEMO, DEMO_VALUE, OTHER, OTHER_VALUE, REMOTE, REMOTE_URL, REMOTE_VALUE,
                                           remote_connector)
import dashboard_profile_api_support as support


class ConnectorBindingMigrationTest(support.ProfileApiRouteTest):
    def setUp(self):
        super().setUp()
        self.bind_fixture()
        remote = remote_connector(self.root.parent / "remote-connector")
        roots = json.loads(os.environ["FOS_ASSISTANT_CONNECTOR_ROOTS"])
        roots[REMOTE] = {"root": str(remote), "url": REMOTE_URL}
        patch = mock.patch.dict(os.environ, {"FOS_ASSISTANT_CONNECTOR_ROOTS": json.dumps(roots)})
        patch.start()
        self.addCleanup(patch.stop)
        self.assertEqual(self.vault("PUT", vault="c3", connector=REMOTE, values={"token": REMOTE_VALUE}).status_code, 200)
        for plugin, vault in ((DEMO, "c1"), (OTHER, "c2"), (REMOTE, "c3")):
            self.assertEqual(self.bind(plugin, vault).status_code, 200)
        self.state_path = self.root / "alice" / self.plugin.CONNECTOR_STATE

    def make_legacy(self, plugin, lines):
        """그 바인딩을 PR1 까지의 옛 정의(`${이름}` 참조)로 되돌리고 `.env` 에 사본 줄을 둔다."""
        state = json.loads(self.state_path.read_text(encoding="utf-8"))
        launch = __import__(self.plugin.__name__ + ".connector_launch", fromlist=["_launcher_inner"])
        legacy = launch._launcher_inner(state[plugin]["server"])
        state[plugin]["server"] = legacy
        self.state_path.write_text(json.dumps(state), encoding="utf-8")
        config = self.alice_config()
        config["mcp_servers"][state[plugin]["mcp_server"]] = legacy
        self.write_config("alice", config)
        env = self.root / "alice/.env"
        env.write_text(self.env_text() + "".join(line + "\n" for line in lines), encoding="utf-8")

    @contextlib.contextmanager
    def logs(self):
        records = []

        class Collect(logging.Handler):
            def emit(self, record):
                records.append(record.getMessage())

        handler = Collect()
        logging.disable(logging.NOTSET)
        logging.getLogger().addHandler(handler)
        previous = logging.getLogger().level
        logging.getLogger().setLevel(logging.DEBUG)
        try:
            yield records
        finally:
            logging.getLogger().removeHandler(handler)
            logging.getLogger().setLevel(previous)
            logging.disable(logging.CRITICAL)

    def test_legacy_binding_moves_to_the_launcher_and_its_copies_are_removed(self):
        """풀 수 있는 옛 바인딩은 실행기 정의가 되고 `.env` 사본이 지워진다. 풀지 못한 것과 원격은 그대로이고, 다시 돌리면 바뀌는 것이 없다."""
        self.make_legacy(DEMO, ["DEMO_TOKEN=" + DEMO_VALUE, "OPERATOR_NOTE=keep"])
        self.make_legacy(OTHER, ["OTHER_TOKEN=" + OTHER_VALUE, "OTHER_SCOPE=a"])
        # 다른 연결 이름에서 복사한 v1 파일처럼 그 플러그인의 파일이 아니면 풀지 못한 것과 같이 남긴다.
        (self.hermes_root / "connector-vault/c2.json").write_text(json.dumps(
            {"v": 1, "connector": DEMO, "values": {"token": DEMO_VALUE}}), encoding="utf-8")
        other_before = self.alice_config()["mcp_servers"]["other"]

        with self.logs() as logs:
            counts = self.plugin._migrate_bindings()

        self.assertEqual(counts, {"moved": 1, "kept": 0, "left": 1, "remote": 1, "unreadable": 0})
        config = self.alice_config()
        self.assertEqual(config["mcp_servers"]["demo"]["args"][0], "-I")
        record = json.loads(self.state_path.read_text(encoding="utf-8"))
        self.assertEqual(record[DEMO]["server"], config["mcp_servers"]["demo"])
        self.assertEqual(config["mcp_servers"]["other"], other_before)
        self.assertEqual(record[OTHER]["server"], other_before)
        self.assertEqual(self.env_text().splitlines(),
                         ["REMOTE_TOKEN=" + REMOTE_VALUE, "OPERATOR_NOTE=keep", "OTHER_TOKEN=" + OTHER_VALUE,
                          "OTHER_SCOPE=a"])
        self.assertEqual(self.launched()[1]["env"]["DEMO_TOKEN"], DEMO_VALUE)
        connectors = {item["plugin"]: item for item in self.status_of()["connectors"]}
        self.assertIs(connectors[DEMO]["configured"], True)
        self.assertIn("mcp__demo__list_scopes", self.tool_map()["servers"]["demo"]["tools"])
        text = "\n".join(logs)
        self.assertIn("실행기로 옮김 1, 그대로 둔 실행기 바인딩 0, 남은 사본 1, 원격 1", text)
        self.assertIn("%s 1" % OTHER, text)
        self.assertIn("gateway 재시작이 필요하다", text)
        for secret in (DEMO_VALUE, OTHER_VALUE, REMOTE_VALUE, "alice", str(self.root)):
            self.assertNotIn(secret, text)

        before = self.tree("alice")
        self.assertEqual(self.plugin._migrate_bindings(), {"moved": 0, "kept": 1, "left": 1, "remote": 1, "unreadable": 0})
        self.assertEqual(self.tree("alice"), before)

    def test_stale_launcher_definition_is_rewritten_and_operator_definition_is_left(self):
        """경로가 바뀐 실행기 정의는 지금 경로로 다시 쓰고, 운영자가 바꾼 정의는 손대지 않는다."""
        state = json.loads(self.state_path.read_text(encoding="utf-8"))
        stale = {**state[DEMO]["server"], "command": "/old/image/python"}
        state[DEMO]["server"] = stale
        self.state_path.write_text(json.dumps(state), encoding="utf-8")
        config = self.alice_config()
        config["mcp_servers"]["demo"] = stale
        config["mcp_servers"]["other"] = {**config["mcp_servers"]["other"], "cwd": "/operator"}
        self.write_config("alice", config)
        self.make_legacy(OTHER, ["OTHER_TOKEN=" + OTHER_VALUE])
        config = self.alice_config()
        config["mcp_servers"]["other"]["cwd"] = "/operator"
        self.write_config("alice", config)
        operator_definition = self.alice_config()["mcp_servers"]["other"]

        counts = self.plugin._migrate_bindings()

        self.assertEqual(counts, {"moved": 1, "kept": 0, "left": 1, "remote": 1, "unreadable": 0})
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["command"], sys.executable)
        self.assertEqual(json.loads(self.state_path.read_text(encoding="utf-8"))[DEMO]["server"],
                         self.alice_config()["mcp_servers"]["demo"])
        self.assertIs({item["plugin"]: item for item in self.status_of()["connectors"]}[DEMO]["configured"], True)
        self.assertEqual(self.alice_config()["mcp_servers"]["other"], operator_definition)
        self.assertIn("OTHER_TOKEN=" + OTHER_VALUE, self.env_text().splitlines())

    def test_leftover_copy_of_a_current_launcher_binding_is_removed_without_a_restart(self):
        """지금 값의 실행기 정의에 사본만 남았으면 사본만 지우고 그대로 둔 수에 센다. 재시작을 요구하지 않는다."""
        (self.root / "alice/.env").write_text(self.env_text() + "DEMO_TOKEN=leftover\n", encoding="utf-8")
        before = self.alice_config()["mcp_servers"]["demo"]
        with self.logs() as logs:
            counts = self.plugin._migrate_bindings()
        self.assertEqual(counts, {"moved": 0, "kept": 2, "left": 0, "remote": 1, "unreadable": 0})
        self.assertNotIn("DEMO_TOKEN", self.env_text())
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"], before)
        self.assertNotIn("gateway 재시작이 필요하다", "\n".join(logs))

    def test_unreadable_record_is_counted_and_migration_never_raises(self):
        """소유 기록을 읽지 못한 profile 은 세고 넘어간다. 이관 전체가 실패해도 기동은 잇는다."""
        self.state_path.write_text("{", encoding="utf-8")
        self.assertEqual(self.plugin._migrate_bindings()["unreadable"], 1)
        with mock.patch.object(self.profiles, "list_profile_names", side_effect=OSError("boom")):
            self.plugin._migrate_connectors()
