"""dashboard-profile-api 의 커넥터 출력 디렉터리 바인딩을 검사한다(ADR-20261008 connector-output-files)."""

import json
import pathlib
from dashboard_profile_api_support import DEMO
import dashboard_profile_api_support as support


class ProfileApiConnectorBindingOutputTest(support.ProfileApiRouteTest):
    def with_output_root(self):
        self.set_sandbox_policy(self.sandbox_policy(connector_output_root=self.connector_output_root))

    def test_binding_writes_the_profile_output_directory_into_the_server_definition(self):
        """정책에 출력 루트가 있으면 그 profile 과 커넥터의 디렉터리를 만들고 서버 정의 env 에 넣는다. `.env` 에는 쓰지 않는다."""
        demo, _ = self.bind_fixture()
        self.declare_owner_output(demo)
        self.with_output_root()

        response = self.bind(owner="user-1")

        self.assertEqual(response.status_code, 200, response.body)
        expected = self.connector_output_directory("user-1", "alice", DEMO)
        server = self.alice_config()["mcp_servers"]["demo"]
        self.assertEqual(server["env"]["DEMO_OUTPUT_DIR"], expected)
        self.assertTrue(pathlib.Path(expected).is_dir())
        self.assertEqual(pathlib.Path(expected).stat().st_mode & 0o777, 0o700)
        record = json.loads((self.root / "alice/.fos-connectors.json").read_text(encoding="utf-8"))
        self.assertEqual(record[DEMO]["server"], server)
        self.assertNotIn("DEMO_OUTPUT_DIR", (self.root / "alice/.env").read_text(encoding="utf-8"))
        self.assertIs(self.status_of()["connectors"][0]["configured"], True)
        self.assertNotIn(expected, json.dumps(response.body))
        # 같은 값으로 다시 붙이면 바뀐 것이 없다.
        self.assertIs(self.bind(owner="user-1").body["changed"], False)

    def test_binding_without_the_root_or_owner_writes_an_empty_value(self):
        """정책에 출력 루트가 없거나 `sandbox_owner` 가 없으면 빈 값으로 붙인다. 붙이기는 막지 않는다."""
        demo, _ = self.bind_fixture()
        self.declare_owner_output(demo)

        without_root = self.bind(owner="user-1")
        self.assertEqual(without_root.status_code, 200, without_root.body)
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"]["DEMO_OUTPUT_DIR"], "")
        self.assertFalse(pathlib.Path(self.connector_output_root).exists())

        self.with_output_root()
        without_owner = self.bind()
        self.assertEqual(without_owner.status_code, 200, without_owner.body)
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"]["DEMO_OUTPUT_DIR"], "")
        self.assertIs(self.status_of()["connectors"][0]["configured"], True)

    def test_binding_on_a_profile_outside_the_policy_writes_an_empty_value(self):
        """실행 공간 정책에 등록되지 않은 profile 은 출력 디렉터리를 붙이지 않으므로 빈 값으로 붙이고 만들지 않는다."""
        demo, _ = self.bind_fixture()
        self.declare_owner_output(demo)
        policy = self.sandbox_policy(connector_output_root=self.connector_output_root)
        policy["profiles"].pop("alice")
        self.set_sandbox_policy(policy)

        response = self.bind(owner="user-1")

        self.assertEqual(response.status_code, 200, response.body)
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"]["DEMO_OUTPUT_DIR"], "")
        self.assertFalse(pathlib.Path(self.connector_output_root).exists())

    def test_rejected_binding_does_not_create_the_directory(self):
        """보관 파일이 없어 거절된 붙이기는 출력 디렉터리를 만들지 않는다."""
        demo, _ = self.bind_fixture()
        self.declare_owner_output(demo)
        self.with_output_root()

        response = self.bind(owner="user-1", vault="missing")

        self.assertEqual(response.status_code, 400, response.body)
        self.assertFalse(pathlib.Path(self.connector_output_root).exists())

    def test_installed_reference_or_odd_value_is_not_configured(self):
        """설치 기록의 출력 값이 profile `.env` 참조이거나 모양이 틀리면 설치된 것으로 보지 않는다."""
        demo, _ = self.bind_fixture()
        self.declare_owner_output(demo)
        self.with_output_root()
        self.assertEqual(self.bind(owner="user-1").status_code, 200)
        state_path = self.root / "alice/.fos-connectors.json"
        original = json.loads(state_path.read_text(encoding="utf-8"))
        for label, value in (("reference", "${DEMO_OUTPUT_DIR}"),
                             ("trailing newline", self.connector_output_directory("user-1", "alice", DEMO) + "\n"),
                             ("short key", self.connector_output_root + "/users/abc/alice/" + DEMO)):
            with self.subTest(label):
                record = json.loads(json.dumps(original))
                record[DEMO]["server"]["env"]["DEMO_OUTPUT_DIR"] = value
                state_path.write_text(json.dumps(record), encoding="utf-8")
                self.assertIs(self.status_of()["connectors"][0]["configured"], False)

    def test_binding_through_a_link_writes_an_empty_value(self):
        """출력 디렉터리 경로에 링크가 섞이면 그 경로를 넣지 않고 빈 값으로 붙인다."""
        demo, _ = self.bind_fixture()
        self.declare_owner_output(demo)
        self.with_output_root()
        elsewhere = pathlib.Path(self.connector_output_root).parent / "elsewhere"
        elsewhere.mkdir()
        profile_directory = pathlib.Path(self.connector_output_directory("user-1", "alice"))
        profile_directory.parent.mkdir(parents=True)
        profile_directory.symlink_to(elsewhere)

        response = self.bind(owner="user-1")

        self.assertEqual(response.status_code, 200, response.body)
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"]["DEMO_OUTPUT_DIR"], "")

    def test_unbinding_removes_only_that_output_directory(self):
        """떼면 설치한 그 출력 디렉터리와 파일을 지운다. 다른 profile 의 디렉터리는 남는다."""
        demo, _ = self.bind_fixture()
        self.declare_owner_output(demo)
        self.with_output_root()
        self.assertEqual(self.bind(owner="user-1").status_code, 200)
        directory = pathlib.Path(self.connector_output_directory("user-1", "alice", DEMO))
        (directory / "list.jsonl").write_text("{}\n", encoding="utf-8")
        other = pathlib.Path(self.connector_output_directory("user-1", "blog", DEMO))
        other.mkdir(parents=True)

        response = self.bind(enabled=False)

        self.assertEqual(response.status_code, 200, response.body)
        self.assertFalse(directory.exists())
        self.assertTrue(directory.parent.is_dir())
        self.assertTrue(other.is_dir())
        self.assertNotIn("demo", self.alice_config().get("mcp_servers") or {})

    def test_unbinding_keeps_a_linked_directory(self):
        """설치한 디렉터리가 링크로 바뀌었으면 따라가 지우지 않는다."""
        demo, _ = self.bind_fixture()
        self.declare_owner_output(demo)
        self.with_output_root()
        self.assertEqual(self.bind(owner="user-1").status_code, 200)
        directory = pathlib.Path(self.connector_output_directory("user-1", "alice", DEMO))
        directory.rmdir()
        elsewhere = pathlib.Path(self.connector_output_root).parent / "elsewhere"
        elsewhere.mkdir()
        (elsewhere / "keep.txt").write_text("keep", encoding="utf-8")
        directory.symlink_to(elsewhere)

        self.assertEqual(self.bind(enabled=False).status_code, 200)

        self.assertTrue((elsewhere / "keep.txt").is_file())
        self.assertTrue(directory.is_symlink())

    def test_unbinding_keeps_a_directory_outside_the_policy_root(self):
        """설치 기록의 값이 지금 정책 루트 밖이면 지우지 않는다."""
        demo, _ = self.bind_fixture()
        self.declare_owner_output(demo)
        self.with_output_root()
        self.assertEqual(self.bind(owner="user-1").status_code, 200)
        directory = pathlib.Path(self.connector_output_directory("user-1", "alice", DEMO))
        moved = pathlib.Path(self.connector_output_root).parent / "other-root"
        self.set_sandbox_policy(self.sandbox_policy(connector_output_root=str(moved)))

        self.assertEqual(self.bind(enabled=False).status_code, 200)

        self.assertTrue(directory.is_dir())
