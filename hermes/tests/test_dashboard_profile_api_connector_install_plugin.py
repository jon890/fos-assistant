"""dashboard-profile-api 의 connector_install_plugin 분기를 검사한다."""

import hashlib
import json
import shutil
import yaml
from plugin_loading import patch_plugin, set_plugin
from dashboard_profile_api_support import DEMO
import dashboard_profile_api_support as support


class ProfileApiConnectorInstallPluginTest(support.ProfileApiRouteTest):
    def test_connector_install_writes_tool_name_map(self):
        """설치는 Hermes 등록 이름과 원래 도구 이름의 대응을 쓰고, 제거는 그 대응을 비운다."""
        root = self.connector_fixture()
        # 아무것도 설치하지 않은 profile 에는 대응 파일이 없다. hook 이 켜졌다고 답하지 않는다.
        self.assertIs(self.policy_hook(), False)
        installed = self.connector()
        self.assertEqual(installed.status_code, 200)
        self.assertIs(installed.body["plugin_updated"], False)
        self.assertEqual(self.tool_map(), {"v": 1, "servers": {"demo": {
            "connector": DEMO, "prefix": "mcp__demo__", "tools": {"mcp__demo__list_scopes": "list_scopes"}}}})
        self.assertIs(self.policy_hook(), True)
        # 늘 승인이 필요한 도구가 없으면 서버 정의에 `tools` 를 넣지 않는다.
        self.assertNotIn("tools", self.alice_config()["mcp_servers"]["demo"])

        self.declare_tools(root, {"list_scopes": {"risk": "READ"}, "env_view": {"risk": "SENSITIVE"},
                                  "write_note": {"risk": "WRITE"}})
        # manifest 가 바뀌면 다시 설치하기 전까지 대응의 그 서버 `tools` 가 옛것이다. 서버 목록과 접두사는 같으므로
        # hook 은 접두사로 서버를 잡아 묻는다. 그 커넥터 항목만 설치가 덜 된 것이고 `policy_hook` 은 참이다.
        status = self.connector_status()
        self.assertEqual(status.body["connectors"],
                         [{"plugin": DEMO, "enabled": True, "configured": False, "mode": "isolated"}])
        self.assertIs(status.body["policy_hook"], True)
        repeated = self.connector()
        self.assertEqual(repeated.status_code, 200)
        self.assertIs(repeated.body["changed"], True)
        self.assertIs(repeated.body["plugin_updated"], False)
        self.assertEqual(self.tool_map()["servers"]["demo"]["tools"], {
            "mcp__demo__list_scopes": "list_scopes", "mcp__demo__env_view": "env_view",
            "mcp__demo__write_note": "write_note"})
        self.assertNotIn("tools", self.alice_config()["mcp_servers"]["demo"])
        status = self.connector_status()
        self.assertEqual(status.body["connectors"][0]["configured"], True)
        self.assertIs(status.body["policy_hook"], True)
        unchanged = self.connector()
        self.assertIs(unchanged.body["changed"], False)
        self.assertIs(unchanged.body["plugin_updated"], False)

        soul = (self.root / "alice/SOUL.md").read_bytes()
        removed = self.connector(False)
        self.assertEqual(removed.status_code, 200)
        self.assertIs(removed.body["plugin_updated"], False)
        self.assertEqual(self.tool_map(), {"v": 1, "servers": {}})
        self.assertEqual((self.root / "alice/SOUL.md").read_bytes(), soul)

    def test_tool_name_map_follows_hermes_renaming_of_long_and_dashed_names(self):
        """글자가 바뀌거나 64자를 넘어 줄어든 등록 이름도 원래 도구 이름으로 되찾는다."""
        root = self.connector_fixture()
        long_name = "note-" + "a" * 60
        self.declare_tools(root, {"list_scopes": {"risk": "READ"}, "env-view": {"risk": "READ"},
                                  long_name: {"risk": "READ"}})
        self.assertEqual(self.connector().status_code, 200)
        full = "mcp__demo__note_" + "a" * 60
        shortened = full[:55] + "_" + hashlib.sha256(full.encode("utf-8")).hexdigest()[:8]
        self.assertEqual(len(shortened), 64)
        self.assertEqual(self.tool_map()["servers"]["demo"]["tools"], {
            "mcp__demo__list_scopes": "list_scopes", "mcp__demo__env_view": "env-view", shortened: long_name})

    def test_policy_hook_is_reported_only_when_the_profile_plugin_and_config_are_intact(self):
        """조회는 hook plugin 이 켜져 있고 묶음의 판과 같고 대응 파일이 맞을 때만 `policy_hook` 을 참으로 답한다."""
        self.connector_fixture()
        self.assertEqual(self.connector().status_code, 200)
        self.assertIs(self.policy_hook(), True)

        installed = self.root / "alice/plugins/fos-ctx/__init__.py"
        bundled = (self.profile_plugins / "fos-ctx/__init__.py").read_bytes()
        installed.write_bytes(bundled + b"# changed\n")
        self.assertIs(self.policy_hook(), False)
        # 다시 보낸 설치가 묶음의 판으로 되돌리고, 파일이 바뀌었다고 따로 답한다.
        repaired = self.connector()
        self.assertEqual(repaired.status_code, 200)
        self.assertIs(repaired.body["changed"], True)
        self.assertIs(repaired.body["plugin_updated"], True)
        self.assertEqual(installed.read_bytes(), bundled)
        self.assertEqual(installed.stat().st_mode & 0o777, 0o644)
        self.assertIs(self.policy_hook(), True)
        again = self.connector()
        self.assertIs(again.body["changed"], False)
        self.assertIs(again.body["plugin_updated"], False)

        config_path = self.root / "alice/config.yaml"
        intact = config_path.read_bytes()

        def edited(change):
            config = yaml.safe_load(intact)
            change(config["plugins"])
            config_path.write_text(yaml.safe_dump(config, sort_keys=False), encoding="utf-8")
            return self.policy_hook()

        self.assertIs(edited(lambda plugins: plugins["enabled"].remove("fos-ctx")), False)
        self.assertIs(edited(lambda plugins: plugins["disabled"].append("fos-ctx")), False)
        self.assertIs(edited(lambda plugins: plugins["entries"]["fos-ctx"].update(allow_tool_override=True)), False)
        self.assertIs(edited(lambda plugins: plugins["entries"].pop("fos-ctx")), False)
        config_path.write_bytes(intact)
        self.assertIs(self.policy_hook(), True)

        (self.root / "alice" / self.plugin.CONNECTOR_TOOL_MAP).unlink()
        self.assertIs(self.policy_hook(), False)
        # 대응 파일만 없어도 설치가 다시 쓴다. plugin 파일은 그대로라 바뀌었다고 답하지 않는다.
        rewritten = self.connector()
        self.assertIs(rewritten.body["changed"], True)
        self.assertIs(rewritten.body["plugin_updated"], False)
        self.assertIs(self.policy_hook(), True)

    def test_policy_hook_checks_and_repairs_every_python_module(self):
        """하위 모듈이 바뀌거나 없어져도 hook 조회가 거짓이고 설치가 모두 복원한다."""
        self.connector_fixture()
        self.assertEqual(self.connector().status_code, 200)
        source = self.profile_plugins / "fos-ctx"
        installed = self.root / "alice/plugins/fos-ctx"
        expected = {"plugin.yaml"} | {module.name for module in source.glob("*.py")}
        self.assertEqual(set(self.plugin.PROFILE_PLUGIN_FILES), expected)
        for name in sorted(expected):
            original = (source / name).read_bytes()
            for change in ("changed", "missing"):
                with self.subTest(module=name, change=change):
                    target = installed / name
                    if change == "changed":
                        target.write_bytes(original + b"# changed\n")
                    else:
                        target.unlink()
                    self.assertIs(self.policy_hook(), False)
                    repaired = self.connector()
                    self.assertEqual(repaired.status_code, 200)
                    self.assertIs(repaired.body["plugin_updated"], True)
                    self.assertEqual(target.read_bytes(), original)
                    self.assertIs(self.policy_hook(), True)

    def test_connector_install_writes_profile_entrypoint_after_modules(self):
        self.connector_fixture()
        self.assert_profile_entrypoint_is_written_last(self.connector)

    def test_binding_install_writes_profile_entrypoint_after_modules(self):
        self.bind_fixture()
        self.assert_profile_entrypoint_is_written_last(self.bind)

    def test_connector_install_restores_a_missing_profile_plugin(self):
        """profile 에 hook plugin 디렉터리가 없으면 설치가 묶음의 판으로 만든다."""
        self.connector_fixture()
        shutil.rmtree(self.root / "alice/plugins")
        installed = self.connector()
        self.assertEqual(installed.status_code, 200)
        self.assertIs(installed.body["plugin_updated"], True)
        for name in ("plugin.yaml", "__init__.py"):
            target = self.root / "alice/plugins/fos-ctx" / name
            self.assertEqual(target.read_bytes(), (self.profile_plugins / "fos-ctx" / name).read_bytes())
            self.assertEqual(target.stat().st_mode & 0o777, 0o644)
        self.assertEqual((self.root / "alice/plugins/fos-ctx").stat().st_mode & 0o777, 0o755)
        self.assertIs(self.policy_hook(), True)

    def test_connector_install_leaves_profile_plugin_alone_without_bundled_copy(self):
        """묶음에 hook plugin 이 없으면 설치는 되지만 profile 의 plugin 을 건드리지 않고 `policy_hook` 이 거짓이다."""
        self.connector_fixture()
        installed = self.root / "alice/plugins/fos-ctx/__init__.py"
        installed.write_bytes(b"# kept\n")
        set_plugin(self.plugin, "PROFILE_PLUGIN_DIR", self.root.parent / "no-profile-plugins")
        response = self.connector()
        self.assertEqual(response.status_code, 200)
        self.assertIs(response.body["changed"], True)
        self.assertIs(response.body["plugin_updated"], False)
        self.assertEqual(installed.read_bytes(), b"# kept\n")
        self.assertIn("demo", self.tool_map()["servers"])
        self.assertIs(self.policy_hook(), False)

    def test_connector_install_rejects_a_linked_profile_plugin(self):
        """profile 의 hook plugin 디렉터리가 링크이면 설치하지 않는다. 링크 밖의 파일을 덮어쓰지 않는다."""
        self.connector_fixture()
        outside = self.root.parent / "outside-plugin"
        shutil.move(str(self.root / "alice/plugins/fos-ctx"), outside)
        (self.root / "alice/plugins/fos-ctx").symlink_to(outside)
        (outside / "__init__.py").write_bytes(b"# outside\n")
        self.assertEqual(self.connector().status_code, 503)
        self.assertEqual((outside / "__init__.py").read_bytes(), b"# outside\n")
        self.assertNotIn("demo", self.alice_config()["mcp_servers"])

    def test_tools_that_always_need_approval_are_excluded_from_the_server_definition(self):
        """`approval: always` 인 도구는 서버 정의의 `tools.exclude` 에 이름 순으로 들어가고 소유 기록도 같다."""
        root = self.connector_fixture()
        self.declare_tools(root, {"list_scopes": {"risk": "READ"}, "purge": {"risk": "WRITE"}})
        self.assertEqual(self.connector().status_code, 200)
        self.assertNotIn("tools", self.alice_config()["mcp_servers"]["demo"])
        self.assertIs(self.policy_hook(), True)

        # `purge` 는 승인 방식만 올랐고 `erase` 가 더해졌다. 서버 정의의 `tools.exclude` 와 대응의 그 서버 `tools` 가 옛것이다.
        # 둘 다 그 커넥터 항목만 거짓으로 만든다. 대응의 서버 목록과 접두사는 같으므로 `policy_hook` 은 참이다.
        self.declare_tools(root, {"list_scopes": {"risk": "READ"}, "purge": {"risk": "DESTRUCTIVE"},
                                  "erase": {"risk": "WRITE", "approval": "always"}})
        status = self.connector_status()
        self.assertEqual(status.status_code, 200)
        self.assertEqual(status.body["connectors"], [{"plugin": DEMO, "enabled": True, "configured": False, "mode": "isolated"}])
        self.assertIs(status.body["policy_hook"], True)

        self.assertEqual(self.connector().status_code, 200)
        server = self.alice_config()["mcp_servers"]["demo"]
        self.assertEqual(server["tools"], {"exclude": ["erase", "purge"]})
        record_path = self.root / "alice" / self.plugin.CONNECTOR_STATE
        record = json.loads(record_path.read_text(encoding="utf-8"))
        self.assertEqual(record[DEMO]["server"], server)
        # 모델에게서 뺀 도구도 대응에는 있다. 다른 경로로 불리면 hook 이 정책을 찾아야 한다.
        self.assertEqual(self.tool_map()["servers"]["demo"]["tools"]["mcp__demo__purge"], "purge")
        status = self.connector_status()
        self.assertEqual(status.body["connectors"][0]["configured"], True)
        self.assertIs(status.body["policy_hook"], True)
        self.assertIs(self.connector().body["changed"], False)

        # 소유 기록의 `tools` 는 `exclude` 문자열 목록만 받는다.
        for tools in ({"exclude": "purge"}, {"exclude": ["purge", 1]}, {"include": []}, ["purge"]):
            with self.subTest(tools=tools):
                broken = json.loads(json.dumps(record))
                broken[DEMO]["server"]["tools"] = tools
                record_path.write_text(json.dumps(broken), encoding="utf-8")
                self.assertEqual(self.connector_status().status_code, 503)
        record_path.write_text(json.dumps(record) + "\n", encoding="utf-8")
        self.assertEqual(self.connector(False).status_code, 200)
        self.assertNotIn("demo", self.alice_config()["mcp_servers"])

    def test_connector_install_rolls_back_tool_map_and_profile_plugin_when_the_last_write_fails(self):
        """마지막 파일의 쓰기가 실패하면 먼저 쓴 설정, 소유 기록, 대응 파일, plugin 파일을 모두 되돌린다."""
        root = self.connector_fixture()
        self.assertEqual(self.connector().status_code, 200)
        profile = self.root / "alice"
        plugin_dir = profile / "plugins/fos-ctx"
        for name in ("plugin.yaml", "__init__.py"):
            with open(plugin_dir / name, "ab") as handle:
                handle.write(b"# old version\n")
        # 설정, 소유 기록, 대응 파일이 모두 바뀌는 설치다.
        self.declare_tools(root, {"list_scopes": {"risk": "READ"}, "purge": {"risk": "DESTRUCTIVE"}})
        watched = [profile / "config.yaml", profile / self.plugin.CONNECTOR_STATE,
                   profile / self.plugin.CONNECTOR_TOOL_MAP, plugin_dir / "plugin.yaml", plugin_dir / "__init__.py"]
        before = {path: path.read_bytes() for path in watched}
        write = self.plugin._atomic_private_write
        attempted = []

        def failing_write(target, value):
            if "connector-backups" in target.parts or None in attempted:
                return write(target, value)
            if target == plugin_dir / "__init__.py":
                attempted.append(None)
                raise OSError("injected")
            attempted.append(target)
            return write(target, value)

        with patch_plugin(self.plugin, "_atomic_private_write", side_effect=failing_write):
            self.assertEqual(self.connector().status_code, 503)
        # 실패한 쓰기가 마지막이었다. 그 앞에 나머지 파일을 모두 썼어야 되돌리기를 검사한 것이다.
        self.assertEqual(attempted[-1], None)
        self.assertLessEqual(set(watched[:-1]), set(attempted[:-1]))
        for path in watched:
            self.assertEqual(path.read_bytes(), before[path], "%s 가 되돌아오지 않았다" % path.name)
        self.assertEqual((plugin_dir / "plugin.yaml").stat().st_mode & 0o777, 0o644)

    def test_connector_install_failure_removes_the_plugin_directory_it_created(self):
        """첫 설치가 마지막 쓰기에서 실패하면 새로 만든 대응 파일과 plugin 디렉터리를 남기지 않는다."""
        self.connector_fixture()
        profile = self.root / "alice"
        shutil.rmtree(profile / "plugins")
        write = self.plugin._atomic_private_write

        def failing_write(target, value):
            if target == profile / "plugins/fos-ctx/__init__.py":
                raise OSError("injected")
            return write(target, value)

        with patch_plugin(self.plugin, "_atomic_private_write", side_effect=failing_write):
            self.assertEqual(self.connector().status_code, 503)
        self.assertFalse((profile / "plugins").exists())
        self.assertFalse((profile / self.plugin.CONNECTOR_TOOL_MAP).exists())
        self.assertFalse((profile / self.plugin.CONNECTOR_STATE).exists())
        self.assertNotIn("demo", self.alice_config()["mcp_servers"])
