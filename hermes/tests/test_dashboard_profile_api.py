"""Hermes 설치 없이 dashboard-profile-api 의 미들웨어 분기를 검사한다."""

import asyncio
import contextlib
import importlib.util
import json
import logging
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock

import yaml


ROOT = pathlib.Path(__file__).resolve().parents[1]
BUNDLE_SCRIPT = ROOT / "bundle.sh"
# 묶음을 만들 때 주는 검사용 Control Plane MCP 주소다.
MCP_URL = "http://control-plane.test/mcp"
# 시험 커넥터다. 운영 목록의 이름과 운영자가 주는 env 값이다.
DEMO_CONNECTOR = ROOT / "tests/fixtures/demo-connector"
DEMO = "demo-notes"
DEMO_BASE = "http://demo.test/base"
# 앞선 판의 plugin 이 이름으로 알던 커넥터다. 그 판이 남긴 소유 기록을 인정하는지 보는 검사만 쓴다.
LEGACY = "fos-accountbook"
LEGACY_BASE = "http://legacy.test/base"

# 설정이 없을 때 API 경로가 떨어지는 복합 toolset 을 줄여 흉내 낸다.
WIDE_TOOLSETS = {"code_execution", "delegation", "file", "memory", "terminal", "web"}


def fake_platform_tools(config, platform):
    """허용 목록과 MCP 자동 허용 뒤 disabled_toolsets 를 빼는 순서를 흉내 낸다."""
    listed = (config.get("platform_toolsets") or {}).get(platform)
    enabled = set(listed) if isinstance(listed, list) else set(WIDE_TOOLSETS)
    if "hermes-api-server" in enabled:
        enabled.remove("hermes-api-server")
        enabled.update(WIDE_TOOLSETS)
    mcp_names = set(config.get("mcp_servers") or {})
    if not (enabled & mcp_names):
        enabled.update(mcp_names)
    return enabled - set((config.get("agent") or {}).get("disabled_toolsets") or [])


class ProfileApiRouteTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # 운영과 같은 모양을 검사하려고 설치 묶음을 만들어 그 안의 plugin 을 불러온다.
        cls.bundle_tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.bundle_tmp.cleanup)
        bundle = pathlib.Path(cls.bundle_tmp.name).resolve() / "bundle"
        built = subprocess.run([str(BUNDLE_SCRIPT), "--out", str(bundle), "--mcp-url", MCP_URL],
                               capture_output=True, text=True)
        if built.returncode != 0:
            raise AssertionError("bundle.sh 가 %d 로 끝났다: %s" % (built.returncode, built.stderr.strip()))
        cls.template = bundle / "default-config.yaml.template"
        cls.profile_plugins = bundle / "profile-plugins"
        auth = types.ModuleType("hermes_cli.dashboard_auth")
        seam = types.ModuleType("hermes_cli.dashboard_auth.token_auth")
        auth.DashboardAuthProvider = object
        auth.LoginStart = object
        auth.Session = object
        auth.TokenPrincipal = object
        auth.token_auth = seam
        cls.profiles = types.ModuleType("hermes_cli.profiles")
        cls.config = types.ModuleType("hermes_cli.config")
        cls.tools = types.ModuleType("hermes_cli.tools_config")
        cls.tools.PLATFORMS = {name: {} for name in ("api_server", "discord", "cli")}
        toolsets = types.ModuleType("toolsets")
        toolsets.TOOLSETS = {name: {} for name in
                            ("delegation", "memory", "web", "terminal", "file", "skills", "code_execution",
                             "hermes-api-server")}
        cls.web_profiles = types.ModuleType("hermes_cli.web_server_profiles")
        constants = types.ModuleType("hermes_constants")
        gateway = types.ModuleType("gateway")
        control = types.ModuleType("gateway.control_socket")
        gateway.control_socket = control
        responses = types.ModuleType("starlette.responses")
        hermes = types.ModuleType("hermes_cli")
        hermes.dashboard_auth = auth
        hermes.profiles = cls.profiles
        hermes.config = cls.config
        hermes.tools_config = cls.tools
        hermes.web_server_profiles = cls.web_profiles
        modules = {
            "hermes_cli": hermes,
            "hermes_cli.dashboard_auth": auth,
            "hermes_cli.dashboard_auth.token_auth": seam,
            "hermes_cli.profiles": cls.profiles,
            "hermes_cli.config": cls.config,
            "hermes_cli.tools_config": cls.tools,
            "hermes_cli.web_server_profiles": cls.web_profiles,
            "hermes_constants": constants,
            "toolsets": toolsets,
            "starlette.responses": responses,
            "gateway": gateway,
            "gateway.control_socket": control,
        }
        # 실패 분기는 예외를 로그로 남긴다. 검사 출력에는 결과만 둔다.
        logging.disable(logging.CRITICAL)
        cls.previous = {name: sys.modules.get(name) for name in modules}
        sys.modules.update(modules)

        # profile 은 임시 디렉터리 아래 디렉터리 하나다. HERMES_HOME override 는 그 경로를 쥔다.
        cls.home = {"dir": None}
        constants.set_hermes_home_override = lambda path: cls.home.update(dir=path) or "token"
        constants.reset_hermes_home_override = lambda token: cls.home.update(dir=None)
        constants.get_default_hermes_root = lambda: "/opt/data"

        # gateway 에 plugin 을 다시 읽으라고 보낸 요청을 남긴다.
        def reload_gateway_plugins(home, profile_home=None):
            cls.reloads.append((str(home), str(profile_home)))
            if cls.reload_fails:
                raise OSError("no gateway")
            return {"reloaded": True, "plugins": ["fos-ctx"]}

        control.reload_gateway_plugins = reload_gateway_plugins

        def save_config(config, strip_defaults=True):
            assert strip_defaults is False
            target = pathlib.Path(cls.home["dir"]) / "config.yaml"
            target.write_text(yaml.safe_dump(config, sort_keys=False), encoding="utf-8")

        def list_profile_names():
            if cls.list_fails:
                raise OSError("list failed")
            return ["default"] + sorted(p.name for p in cls.root.iterdir())

        def delete_profile(name, yes=False):
            assert yes is True
            cls.deleted.append(name)
            shutil.rmtree(cls.root / name)

        # 대시보드의 profile scope 를 흉내 낸다. 계산 함수가 그 안에서 불렸는지 본다.
        cls.scope = {"profile": None}

        @contextlib.contextmanager
        def config_profile_scope(profile):
            cls.scope["profile"] = profile
            try:
                yield
            finally:
                cls.scope["profile"] = None

        cls.web_profiles._config_profile_scope = config_profile_scope
        cls.config.save_config = save_config
        cls.tools._get_plugin_toolset_keys = lambda: set()
        cls.tools._get_platform_tools = fake_platform_tools
        cls.profiles.list_profile_names = list_profile_names
        cls.profiles.get_profile_dir = lambda name: cls.root / name
        cls.profiles.profile_exists = lambda name: (cls.root / name).is_dir()
        cls.profiles.delete_profile = delete_profile
        cls.profiles.NO_BUNDLED_SKILLS_MARKER = ".no-bundled-skills"
        responses.JSONResponse = lambda body, status_code: types.SimpleNamespace(
            status_code=status_code, body=body
        )

        async def original(request, call_next):
            return await call_next(request)

        seam.token_auth_middleware = original
        seam.authenticate_token = lambda request: (
            (types.SimpleNamespace(provider="fos-profile-api"), None) if request.token == "valid" else (None, None)
        )
        spec = importlib.util.spec_from_file_location("profile_api_route_test", bundle / "__init__.py")
        cls.plugin = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.plugin)
        assert cls.plugin._install_gate()
        cls.gate = staticmethod(seam.token_auth_middleware)

    @classmethod
    def tearDownClass(cls):
        logging.disable(logging.NOTSET)
        for name, previous in cls.previous.items():
            if previous is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = previous

    def setUp(self):
        self.plugin.PROFILE_WRITE_LOCK = asyncio.Lock()
        self.plugin.TEMPLATE_PATH = self.template
        self.plugin.PROFILE_PLUGIN_DIR = self.profile_plugins
        self.tools._get_platform_tools = fake_platform_tools
        type(self).list_fails = False
        type(self).deleted = []
        type(self).reloads = []
        type(self).reload_fails = False
        self.tmp = tempfile.TemporaryDirectory()
        # macOS 의 임시 디렉터리는 심볼릭 링크 아래에 있다. 스킬 경로 검사가 링크를 거절하므로 푼 경로를 쓴다.
        base = pathlib.Path(self.tmp.name).resolve()
        type(self).root = base / "profiles"
        self.root.mkdir()
        self.skill_root = base / "skills"
        self.skill_root.mkdir()
        # 커넥터 검사도 환경 변수를 바꿔 끼운다. 되돌리는 순서가 엇갈리지 않게 같은 방식으로 건다.
        skill_env = mock.patch.dict(os.environ, {"FOS_ASSISTANT_SKILL_AGENT_ROOT": str(self.skill_root)})
        skill_env.start()
        self.addCleanup(skill_env.stop)
        # 운영 profile 하나가 먼저 있다. 되돌리기가 이것을 건드리면 안 된다.
        self.make_profile("owner")
        self.register_memory("owner")
        path = self.root / "owner/config.yaml"
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
        config["platform_toolsets"] = {"discord": ["web"]}
        config["agent"] = {"disabled_toolsets": ["memory", "terminal"]}
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        self.created_by_handler = ["alice"]
        self.handler_status = 200

    def tearDown(self):
        self.tmp.cleanup()

    def make_profile(self, name):
        # clone 없이 만든 profile 처럼 model 블록만 둔다.
        (self.root / name).mkdir()
        (self.root / name / "config.yaml").write_text(
            yaml.safe_dump({"model": {"default": "gpt-5.6-sol", "provider": "openai-codex"}}),
            encoding="utf-8",
        )

    def connector_environment(self, roots):
        """운영 목록과 기본 실행 파일을 환경 변수로 준다. 검사가 끝나면 되돌린다."""
        # 커넥터 실행 파일은 검사가 만든 파일이다. 실행 정의가 이 경로를 그대로 싣는지 본다.
        command = self.root.parent / "bin/connector-runner"
        command.parent.mkdir()
        command.write_text("#!/bin/sh\n", encoding="utf-8")
        command.chmod(0o755)
        self.connector_command = str(command)
        environ = mock.patch.dict(os.environ, {
            "FOS_ASSISTANT_CONNECTOR_ROOTS": json.dumps(roots),
            "FOS_ASSISTANT_CONNECTOR_COMMAND": self.connector_command,
        })
        environ.start()
        self.addCleanup(environ.stop)

    def connector_fixture(self):
        """시험 커넥터를 운영 목록에 올리고 관리 profile 하나를 만든다."""
        self.make_profile("alice")
        self.plugin._apply_template("alice")
        root = self.root.parent / "demo-connector"
        shutil.copytree(DEMO_CONNECTOR, root)
        self.connector_environment({DEMO: {"root": str(root), "env": {"DEMO_BASE": DEMO_BASE}}})
        return root

    def connector(self, enabled=True, **overrides):
        return self.request("/api/connectors", "PUT", token="valid", full_response=True, body={
            "profile": "alice", "plugin": DEMO, "enabled": enabled, **overrides})

    def connector_status(self):
        return self.request("/api/connectors", "GET", token="valid", query_profiles=["alice"], full_response=True)

    def connector_probe(self, server="demo", profile="alice"):
        return self.request("/api/mcp/servers/%s/test" % server, "POST", token="valid", query_profiles=[profile])

    def alice_config(self):
        return yaml.safe_load((self.root / "alice/config.yaml").read_text(encoding="utf-8"))

    @contextlib.contextmanager
    def without_environment(self, *names):
        """그 안에서만 환경 변수를 뺀다. 나오면 커넥터 준비가 준 값으로 돌아간다."""
        with mock.patch.dict(os.environ):
            for name in names:
                os.environ.pop(name, None)
            yield

    def test_connector_install_is_rejected_without_environment(self):
        """두 환경 변수가 없으면 알려진 커넥터가 없어 설치 요청이 400 이고 설정은 그대로다."""
        self.connector_fixture()
        before = (self.root / "alice/config.yaml").read_bytes()
        with self.without_environment("FOS_ASSISTANT_CONNECTOR_ROOTS", "FOS_ASSISTANT_CONNECTOR_COMMAND"):
            self.assertEqual(self.connector().status_code, 400)
            status = self.connector_status()
            self.assertEqual(status.status_code, 200)
            self.assertEqual(status.body["connectors"], [])
        self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)
        self.assertFalse((self.root / "alice/.fos-connectors.json").exists())

    def test_connector_install_fails_without_command(self):
        """경로만 받고 실행 파일을 받지 못하면 설치가 503 이고 설정은 그대로다."""
        self.connector_fixture()
        before = (self.root / "alice/config.yaml").read_bytes()
        for label, value in (("missing", None), ("relative", "bin/connector-runner")):
            with self.subTest(label), self.without_environment("FOS_ASSISTANT_CONNECTOR_COMMAND"):
                if value is not None:
                    os.environ["FOS_ASSISTANT_CONNECTOR_COMMAND"] = value
                self.assertEqual(self.connector().status_code, 503)
                self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)
                self.assertFalse((self.root / "alice/.fos-connectors.json").exists())

    def test_installed_connector_is_blocked_when_command_is_missing_or_changed(self):
        """설치한 뒤 실행 파일이 빠지거나 바뀌면 상태 조회, 제거, probe 가 막히고, 값이 돌아오면 다시 읽힌다."""
        self.connector_fixture()
        self.assertEqual(self.connector().status_code, 200)
        self.assertEqual(self.connector_probe(), 204)
        before = (self.root / "alice/config.yaml").read_bytes()
        with self.without_environment("FOS_ASSISTANT_CONNECTOR_COMMAND"):
            self.assertEqual(self.connector_status().status_code, 503)
            self.assertEqual(self.connector(False).status_code, 503)
            self.assertEqual(self.connector_probe(), 503)
            os.environ["FOS_ASSISTANT_CONNECTOR_COMMAND"] = self.connector_command + "-other"
            self.assertEqual(self.connector_status().status_code, 503)
            self.assertEqual(self.connector(False).status_code, 503)
            self.assertEqual(self.connector_probe(), 503)
        self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)
        self.assertEqual(self.connector_probe(), 204)
        status = self.connector_status()
        self.assertEqual(status.status_code, 200)
        self.assertEqual(status.body["connectors"], [{"plugin": DEMO, "enabled": True, "configured": True}])
        self.assertEqual(self.connector(False).status_code, 200)

    def test_installed_connector_is_blocked_when_operator_env_value_changes(self):
        """설치한 뒤 운영 목록의 운영자 env 값이 바뀌면 소유 기록과 달라 상태 조회가 503 이다."""
        root = self.connector_fixture()
        self.assertEqual(self.connector().status_code, 200)
        changed = {DEMO: {"root": str(root), "env": {"DEMO_BASE": DEMO_BASE + "/other"}}}
        with mock.patch.dict(os.environ, {"FOS_ASSISTANT_CONNECTOR_ROOTS": json.dumps(changed)}):
            self.assertEqual(self.connector_status().status_code, 503)
        self.assertEqual(self.connector_status().status_code, 200)

    def test_connector_removed_from_operator_list_can_only_be_turned_off(self):
        """운영 목록에서 빠진 커넥터는 소유 기록으로 설치를 끄고 그 기록이 참조하던 env key 를 지운다."""
        self.connector_fixture()
        env = self.root / "alice/.env"
        env.write_text("DEMO_TOKEN=demo_ok_0123456789\nOTHER=keep\nDEMO_SCOPE=a\n"
                       "MCP_FOS_ASSISTANT_API_KEY=keep-too\n", encoding="utf-8")
        self.assertEqual(self.connector().status_code, 200)
        with self.without_environment("FOS_ASSISTANT_CONNECTOR_ROOTS"):
            status = self.connector_status()
            self.assertEqual(status.status_code, 200)
            self.assertEqual(status.body["connectors"], [{"plugin": DEMO, "enabled": True, "configured": False}])
            self.assertEqual(self.connector_probe(), 404)
            self.assertEqual(self.connector(True).status_code, 400)
            self.assertIn("demo", self.alice_config()["mcp_servers"])

            removed = self.connector(False)
            self.assertEqual(removed.status_code, 200)
            self.assertTrue(removed.body["changed"])
            self.assertTrue(removed.body["restart_required"])
            config = self.alice_config()
            self.assertNotIn("demo", config["mcp_servers"])
            self.assertNotIn("demo", config["platform_toolsets"]["api_server"])
            self.assertIn("fos-assistant", config["mcp_servers"])
            self.assertEqual(env.read_text(encoding="utf-8"), "OTHER=keep\nMCP_FOS_ASSISTANT_API_KEY=keep-too\n")
            self.assertEqual(json.loads((self.root / "alice/.fos-connectors.json").read_text()), {})
            self.assertEqual(self.connector_status().body["connectors"], [])
            # 기록이 사라진 뒤에는 모르는 이름이다.
            self.assertEqual(self.connector(False).status_code, 400)

    def test_connector_installs_idempotently_and_preserves_profile_secrets(self):
        """connector 는 manifest 에서 등록하고 사용자 토큰과 다른 MCP 를 보존한다."""
        root = self.connector_fixture()
        env = self.root / "alice/.env"
        env.write_text("DEMO_TOKEN=test-token\nOTHER=keep\n", encoding="utf-8")
        env.chmod(0o600)
        response = self.connector()
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.body["changed"])
        self.assertFalse(response.body["restart_required"])
        config = self.alice_config()
        server = config["mcp_servers"]["demo"]
        # 실행 파일은 manifest 의 것이 아니라 운영이 준 것이고, 인자는 plugin 안의 실제 경로다.
        self.assertEqual(server["command"], self.connector_command)
        self.assertEqual(server["args"], [str(root / "server.py")])
        self.assertEqual(server["env"], {"DEMO_TOKEN": "${DEMO_TOKEN}", "DEMO_SCOPE": "", "DEMO_BASE": DEMO_BASE})
        self.assertIn("demo", config["platform_toolsets"]["api_server"])
        self.assertIn("fos-assistant", config["mcp_servers"])
        self.assertNotIn("skills", config["platform_toolsets"]["api_server"])
        record = json.loads((self.root / "alice/.fos-connectors.json").read_text())
        self.assertEqual(record, {DEMO: {"server": server, "allowlist_added": True, "mcp_server": "demo"}})
        self.assertIn("DEMO_TOKEN=test-token", env.read_text())
        self.assertEqual(env.stat().st_mode & 0o777, 0o600)
        for backup in (self.root / "alice/connector-backups").glob("*/*"):
            self.assertEqual(backup.stat().st_mode & 0o777, 0o600)
        repeated = self.connector()
        self.assertFalse(repeated.body["changed"])
        self.assertTrue(repeated.body["restart_required"])
        removed = self.connector(False)
        self.assertEqual(removed.status_code, 200)
        self.assertTrue(removed.body["restart_required"])
        config = self.alice_config()
        self.assertNotIn("demo", config["mcp_servers"])
        self.assertNotIn("demo", config["platform_toolsets"]["api_server"])
        self.assertIn("fos-assistant", config["mcp_servers"])
        # 운영 목록에 있는 커넥터의 env 는 Control Plane 이 칸마다 지운다. 설치를 끄는 것이 지우지 않는다.
        self.assertIn("DEMO_TOKEN=test-token", env.read_text())

    def test_connector_uses_operator_command_and_rejects_unsafe_manifests(self):
        """manifest 의 명령은 운영 실행 파일로 바뀌고, 치환 있는 인자와 비밀값 원문과 plugin 밖 링크는 거절한다."""
        root = self.connector_fixture()
        manifest = root / ".mcp.json"
        original = manifest.read_text()
        before = (self.root / "alice/config.yaml").read_bytes()
        for label, mutate in (
            ("shell expansion", lambda server: server.update(args=["-c", "echo $HOME"])),
            ("outside plugin", lambda server: server.update(args=["${CLAUDE_PLUGIN_ROOT}/../bin/connector-runner"])),
            ("missing file", lambda server: server.update(args=["${CLAUDE_PLUGIN_ROOT}/missing.py"])),
            ("literal env", lambda server: server["env"].update(DEMO_TOKEN="literal")),
            ("other reference", lambda server: server["env"].update(DEMO_TOKEN="${DEMO_BASE}")),
            ("undeclared env", lambda server: server["env"].update(OTHER="${OTHER}")),
            ("extra field", lambda server: server.update(cwd="/")),
        ):
            with self.subTest(label):
                value = json.loads(original)
                mutate(value["mcpServers"]["demo"])
                manifest.write_text(json.dumps(value), encoding="utf-8")
                self.assertEqual(self.connector().status_code, 503)
                self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)
        value = json.loads(original)
        value["mcpServers"]["demo"]["command"] = "sh"
        manifest.write_text(json.dumps(value), encoding="utf-8")
        self.assertEqual(self.connector().status_code, 200)
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["command"], self.connector_command)
        self.assertEqual(self.connector(False).status_code, 200)
        script = root / "server.py"
        script.unlink()
        script.symlink_to(self.root / "alice/config.yaml")
        self.assertEqual(self.connector().status_code, 503)

    def test_connector_routes_reject_unmanaged_profile_unknown_plugin_and_extra_fields(self):
        """관리 표식 없는 profile 과 임의 설치 요청을 거절하고 쿠키로 설치하지 않는다."""
        self.connector_fixture()
        self.assertEqual(self.connector(profile="default").status_code, 400)
        self.assertEqual(self.connector(profile="missing").status_code, 404)
        self.assertEqual(self.connector(profile="owner").status_code, 401)
        self.assertEqual(self.connector(plugin="unknown").status_code, 400)
        self.assertEqual(self.connector(False, plugin="unknown").status_code, 400)
        self.assertEqual(self.connector(False, plugin="../demo-notes").status_code, 400)
        self.assertEqual(self.connector(command="sh").status_code, 400)
        self.assertEqual(self.request("/api/connectors", "PUT", cookie=True), 401)
        self.assertEqual(self.request("/api/connectors", "GET", token="valid"), 400)

    def test_connector_keeps_operator_definition_and_rolls_back_write_failure(self):
        """운영자 MCP 정의를 덮어쓰지 않고 저장 실패 시 config 와 env 를 되돌린다."""
        self.connector_fixture()
        path = self.root / "alice/config.yaml"
        config = yaml.safe_load(path.read_text())
        config["mcp_servers"]["demo"] = {"command": "operator"}
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        self.assertEqual(self.connector().status_code, 409)
        del config["mcp_servers"]["demo"]
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        before = path.read_bytes()
        write = self.plugin._atomic_private_write
        failed = False
        def failing_write(target, value):
            nonlocal failed
            if target.name == self.plugin.CONNECTOR_STATE and not failed:
                failed = True
                raise OSError("injected")
            return write(target, value)
        with mock.patch.object(self.plugin, "_atomic_private_write", side_effect=failing_write):
            self.assertEqual(self.connector().status_code, 503)
        self.assertEqual(path.read_bytes(), before)
        self.assertFalse((self.root / "alice/.env").exists())
        self.assertFalse((self.root / "alice" / self.plugin.CONNECTOR_STATE).exists())

    def test_connector_status_and_probe_are_limited_to_installed_managed_profile(self):
        """상태 조회는 비밀값을 내지 않고 probe 는 그 profile 에 설치한 커넥터의 서버만 부른다."""
        self.connector_fixture()
        self.assertEqual(self.connector_probe(), 404)
        self.connector()
        response = self.connector_status()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.body["connectors"], [{"plugin": DEMO, "enabled": True, "configured": True}])
        self.assertNotIn(DEMO_BASE, json.dumps(response.body))
        self.assertEqual(self.connector_probe(), 204)
        # 설치한 커넥터의 서버가 아니면 등록된 MCP 서버여도 넘기지 않는다.
        self.assertEqual(self.connector_probe("fos-assistant"), 404)
        self.assertEqual(self.connector_probe("other"), 404)
        self.assertEqual(self.connector_probe(profile="owner"), 401)
        self.assertEqual(self.request("/api/mcp/servers/demo/test", "POST", token="valid"), 400)
        self.assertEqual(self.request("/api/mcp/servers/demo/test", "GET", token="valid", query_profiles=["alice"]), 401)
        self.assertEqual(self.request("/api/mcp/servers/demo/nested/test", "POST", token="valid",
                                      query_profiles=["alice"]), 401)
        self.connector(False)
        self.assertEqual(self.connector_probe(), 404)

    def test_connector_env_delete_preserves_other_credentials(self):
        """커넥터 칸의 환경 변수 삭제는 AI credential 과 Control Plane 토큰을 지우지 않는다."""
        self.connector_fixture()
        for key in ("DEMO_TOKEN", "DEMO_SCOPE"):
            response = self.request("/api/env", "DELETE", token="valid", body={"profile": "alice", "key": key})
            self.assertEqual(response, 200)
        for key in ("OPENAI_API_KEY", "MCP_FOS_ASSISTANT_API_KEY", "API_SERVER_KEY"):
            response = self.request("/api/env", "DELETE", token="valid", body={"profile": "alice", "key": key})
            self.assertEqual(response, 400)
        self.assertEqual(self.request("/api/env", "DELETE", token="valid",
                                      body={"profile": "owner", "key": "DEMO_TOKEN"}), 401)

    def test_connector_env_keys_follow_the_catalog(self):
        """커넥터 key 는 카탈로그에 있는 동안만 쓸 수 있다. 목록에서 빠지면 400 이다."""
        self.connector_fixture()
        body = {"profile": "alice", "key": "DEMO_TOKEN", "value": "demo_ok_0123456789"}
        self.assertEqual(self.request("/api/env", "PUT", token="valid", body=body), 200)
        with self.without_environment("FOS_ASSISTANT_CONNECTOR_ROOTS"):
            self.assertEqual(self.request("/api/env", "PUT", token="valid", body=body), 400)
            self.assertEqual(self.request("/api/env", "DELETE", token="valid",
                                          body={"profile": "alice", "key": "DEMO_TOKEN"}), 400)

    def test_operator_env_write_is_answered_without_writing(self):
        """운영자 env 이름의 쓰기와 지우기는 성공으로 답하고 profile `.env` 를 바꾸지 않는다."""
        self.connector_fixture()
        self.connector()
        env = self.root / "alice/.env"
        env.write_text("DEMO_TOKEN=demo_ok_0123456789\n", encoding="utf-8")
        for method, body in (("PUT", {"profile": "alice", "key": "DEMO_BASE", "value": "http://changed.test"}),
                             ("DELETE", {"profile": "alice", "key": "DEMO_BASE"})):
            with self.subTest(method):
                response = self.request("/api/env", method, token="valid", full_response=True, body=body)
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.body, {"profile": "alice", "key": "DEMO_BASE", "restart_required": False})
                self.assertEqual(env.read_text(encoding="utf-8"), "DEMO_TOKEN=demo_ok_0123456789\n")
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"]["DEMO_BASE"], DEMO_BASE)
        self.assertEqual(self.request("/api/env", "PUT", token="valid", body={
            "profile": "owner", "key": "DEMO_BASE", "value": "x"}), 401)

    def test_connector_env_update_reports_live_process_restart_without_echoing_token(self):
        """토큰 교체는 설치한 자식의 재시작을 알리고 응답에 토큰 원문을 싣지 않는다."""
        self.connector_fixture()
        body = {"profile": "alice", "key": "DEMO_TOKEN", "value": "demo_ok_0123456789"}
        response = self.request("/api/env", "PUT", token="valid", full_response=True, body=body)
        self.assertEqual(response.status_code, 200)
        self.assertFalse(response.body["restart_required"])
        self.connector()
        response = self.request("/api/env", "PUT", token="valid", full_response=True, body=body)
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.body["restart_required"])
        self.assertNotIn("demo_ok_0123456789", json.dumps(response.body))
        self.assertEqual(self.request("/api/env", "PUT", token="valid", body={**body, "profile": "owner"}), 401)

    def test_connector_probe_rejects_operator_changes_and_other_token_providers(self):
        """변조한 MCP 명령과 다른 provider 의 유효한 토큰으로 probe 를 실행하지 않는다."""
        self.connector_fixture()
        self.connector()
        path = self.root / "alice/config.yaml"
        config = yaml.safe_load(path.read_text())
        config["mcp_servers"]["demo"]["command"] = "sh"
        path.write_text(yaml.safe_dump(config))
        self.assertEqual(self.connector_probe(), 409)
        seam = sys.modules["hermes_cli.dashboard_auth.token_auth"]
        with mock.patch.object(seam, "authenticate_token", return_value=(types.SimpleNamespace(provider="other"), None)):
            self.assertEqual(self.connector_probe(), 401)
            self.assertEqual(self.request("/api/env", "DELETE", token="valid", body={"profile": "alice", "key": "DEMO_TOKEN"}), 401)

    def test_connector_preserves_external_edits(self):
        """외부에서 바꾼 환경 변수를 복원으로 덮지 않는다."""
        self.connector_fixture()
        env = self.root / "alice/.env"
        write = self.plugin._atomic_private_write
        def external_edit(target, value):
            write(target, value)
            if "connector-backups" in target.parts and target.name == "config.yaml":
                env.write_text("OTHER=external\n", encoding="utf-8")
        with mock.patch.object(self.plugin, "_atomic_private_write", side_effect=external_edit):
            self.assertEqual(self.connector().status_code, 409)
        self.assertEqual(env.read_text(), "OTHER=external\n")

    def test_connector_rejects_corrupted_ownership_record(self):
        """소유 기록이 변조한 명령 또는 boolean 이 아닌 필드이면 제거하지 않는다."""
        self.connector_fixture()
        self.connector()
        path = self.root / "alice" / self.plugin.CONNECTOR_STATE
        original = json.loads(path.read_text())
        changed_args = {**original[DEMO]["server"], "args": ["/elsewhere/server.py"]}
        literal_env = {**original[DEMO]["server"],
                       "env": {**original[DEMO]["server"]["env"], "DEMO_TOKEN": "literal"}}
        for field, value in (("allowlist_added", "yes"), ("server", {"command": "sh"}), ("server", changed_args),
                             ("server", literal_env), ("mcp_server", "fos-assistant")):
            with self.subTest(field=field, value=value):
                state = json.loads(json.dumps(original))
                state[DEMO][field] = value
                path.write_text(json.dumps(state))
                self.assertEqual(self.connector(False).status_code, 503)
                self.assertEqual(self.connector_status().status_code, 503)
        self.assertIn("demo", self.alice_config()["mcp_servers"])

    def test_optional_field_updates_explicit_empty_env(self):
        """선택 칸을 채우고 비우면 MCP 설정의 변수 참조와 빈 값이 함께 바뀐다."""
        self.connector_fixture()
        self.connector()
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"]["DEMO_SCOPE"], "")
        response = self.request("/api/env", "PUT", token="valid", body={
            "profile": "alice", "key": "DEMO_SCOPE", "value": "a"})
        self.assertEqual(response, 200)
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"]["DEMO_SCOPE"], "${DEMO_SCOPE}")
        self.assertEqual(self.request("/api/env", "DELETE", token="valid", body={
            "profile": "alice", "key": "DEMO_SCOPE"}), 200)
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"]["DEMO_SCOPE"], "")
        self.assertEqual(self.connector_probe(), 204)

    def test_connector_accepts_direct_server_manifest_and_plain_optional_reference(self):
        """`mcpServers` 없이 서버를 바로 둔 `.mcp.json` 과 기본값 없는 선택 칸 참조도 같은 정의가 된다."""
        root = self.connector_fixture()
        path = root / ".mcp.json"
        value = json.loads(path.read_text())["mcpServers"]
        value["demo"]["env"]["DEMO_SCOPE"] = "${DEMO_SCOPE}"
        path.write_text(json.dumps(value))
        self.assertEqual(self.connector().status_code, 200)
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"]["DEMO_SCOPE"], "")

    def legacy_fixture(self):
        """앞선 판의 plugin 이 설치한 profile 이다. 소유 기록과 서버 정의가 그 판이 쓴 모양 그대로다.

        앞선 판은 커넥터 하나를 이름으로 알았다. 그 이름과 env 이름은 이 호환 검사에만 둔다.
        """
        self.make_profile("alice")
        self.plugin._apply_template("alice")
        root = self.root.parent / "legacy-connector"
        (root / ".claude-plugin").mkdir(parents=True)
        (root / "skills").mkdir()
        (root / "dist").mkdir()
        (root / "dist/accountbook-mcp.js").write_text("// fixture", encoding="utf-8")
        (root / ".claude-plugin/plugin.json").write_text(
            json.dumps({"name": LEGACY, "skills": "./skills"}), encoding="utf-8")
        (root / ".mcp.json").write_text(json.dumps({"mcpServers": {"accountbook": {
            "command": "bun", "args": ["${CLAUDE_PLUGIN_ROOT}/dist/accountbook-mcp.js"],
            "env": {"ACCOUNTBOOK_API_BASE_URL": "${ACCOUNTBOOK_API_BASE_URL}",
                    "ACCOUNTBOOK_API_TOKEN": "${ACCOUNTBOOK_API_TOKEN}",
                    "ACCOUNTBOOK_FAMILY_UUID": "${ACCOUNTBOOK_FAMILY_UUID:-}"},
        }}}), encoding="utf-8")
        (root / "connector.json").write_text(json.dumps({
            "schema": 1, "id": LEGACY, "title": "가계부", "description": "호환 검사용",
            "fields": [
                {"key": "token", "env": "ACCOUNTBOOK_API_TOKEN", "label": "연동 토큰", "secret": True,
                 "required": True},
                {"key": "family", "env": "ACCOUNTBOOK_FAMILY_UUID", "label": "가족", "required": False,
                 "options": {"tool": "list_families", "items": "families", "value": "uuid", "label": "name"}},
            ],
            "verify": {"tool": "list_families"},
            "operator_env": ["ACCOUNTBOOK_API_BASE_URL"],
            "errors": {"ACCOUNTBOOK_UNAUTHORIZED": "credential_rejected"},
        }), encoding="utf-8")
        self.connector_environment(
            {LEGACY: {"root": str(root), "env": {"ACCOUNTBOOK_API_BASE_URL": LEGACY_BASE}}})
        # 앞선 판의 설치가 남긴 것이다. 운영자 env 를 참조로 갖고, 고르지 않은 가족은 빈 값이고, 서버 이름 칸이 없다.
        server = {"command": self.connector_command, "args": [str(root / "dist/accountbook-mcp.js")],
                  "env": {"ACCOUNTBOOK_API_BASE_URL": "${ACCOUNTBOOK_API_BASE_URL}",
                          "ACCOUNTBOOK_API_TOKEN": "${ACCOUNTBOOK_API_TOKEN}",
                          "ACCOUNTBOOK_FAMILY_UUID": ""},
                  "enabled": True}
        path = self.root / "alice/config.yaml"
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
        config["mcp_servers"]["accountbook"] = server
        config["platform_toolsets"]["api_server"].append("accountbook")
        path.write_text(yaml.safe_dump(config, sort_keys=False, allow_unicode=True), encoding="utf-8")
        (self.root / "alice/.fos-connectors.json").write_text(
            json.dumps({LEGACY: {"server": server, "allowlist_added": True}}) + "\n", encoding="utf-8")
        (self.root / "alice/.env").write_text(
            "ACCOUNTBOOK_API_BASE_URL=%s\nACCOUNTBOOK_API_TOKEN=legacy-token\nOTHER=keep\n" % LEGACY_BASE,
            encoding="utf-8")
        return server

    def test_legacy_ownership_record_and_calls_are_still_accepted(self):
        """앞선 판이 남긴 소유 기록을 그대로 인정하고, 앞선 Control Plane 이 부르는 모양을 그대로 받는다."""
        self.legacy_fixture()
        env = self.root / "alice/.env"
        record_path = self.root / "alice/.fos-connectors.json"
        record_before = record_path.read_bytes()

        status = self.connector_status()
        self.assertEqual(status.status_code, 200)
        self.assertEqual(status.body["connectors"], [{"plugin": LEGACY, "enabled": True, "configured": True}])
        self.assertEqual(self.connector_probe("accountbook"), 204)
        self.assertEqual(record_path.read_bytes(), record_before)

        # 앞선 Control Plane 은 공통 주소도 PUT /api/env 로 쓴다. 성공으로 답하고 쓰지 않는다.
        env_before = env.read_bytes()
        response = self.request("/api/env", "PUT", token="valid", full_response=True, body={
            "profile": "alice", "key": "ACCOUNTBOOK_API_BASE_URL", "value": "http://changed.test"})
        self.assertEqual(response.status_code, 200)
        self.assertFalse(response.body["restart_required"])
        self.assertEqual(env.read_bytes(), env_before)
        response = self.request("/api/env", "PUT", token="valid", full_response=True, body={
            "profile": "alice", "key": "ACCOUNTBOOK_API_TOKEN", "value": "legacy-token-2"})
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.body["restart_required"])

        # 다음 설치 요청이 기록을 새 모양으로 다시 쓴다. 운영자 env 는 운영 목록의 값이 직접 들어간다.
        installed = self.connector(plugin=LEGACY)
        self.assertEqual(installed.status_code, 200)
        self.assertTrue(installed.body["changed"])
        self.assertTrue(installed.body["restart_required"])
        record = json.loads(record_path.read_text())[LEGACY]
        self.assertEqual(record["mcp_server"], "accountbook")
        self.assertEqual(record["server"]["env"], {"ACCOUNTBOOK_API_BASE_URL": LEGACY_BASE,
                                                   "ACCOUNTBOOK_API_TOKEN": "${ACCOUNTBOOK_API_TOKEN}",
                                                   "ACCOUNTBOOK_FAMILY_UUID": ""})
        self.assertEqual(self.alice_config()["mcp_servers"]["accountbook"], record["server"])
        self.assertEqual(self.connector_status().body["connectors"],
                         [{"plugin": LEGACY, "enabled": True, "configured": True}])
        self.assertEqual(self.connector_probe("accountbook"), 204)

    def test_legacy_ownership_record_keeps_working_after_optional_field_changes(self):
        """앞선 기록 위에서 선택 칸을 채워도 설정과 기록이 함께 바뀌고 probe 가 통과한다."""
        self.legacy_fixture()
        self.assertEqual(self.request("/api/env", "PUT", token="valid", body={
            "profile": "alice", "key": "ACCOUNTBOOK_FAMILY_UUID", "value": "family-fixture"}), 200)
        env = self.alice_config()["mcp_servers"]["accountbook"]["env"]
        self.assertEqual(env["ACCOUNTBOOK_FAMILY_UUID"], "${ACCOUNTBOOK_FAMILY_UUID}")
        self.assertEqual(self.connector_probe("accountbook"), 204)
        self.assertEqual(self.connector(False, plugin=LEGACY).status_code, 200)
        self.assertNotIn("accountbook", self.alice_config()["mcp_servers"])

    def test_legacy_ownership_record_can_be_turned_off_after_removal_from_operator_list(self):
        """서버 이름 칸이 없는 앞선 기록도 운영 목록에서 빠진 뒤 설치를 끄고 참조하던 env key 를 지운다."""
        self.legacy_fixture()
        with self.without_environment("FOS_ASSISTANT_CONNECTOR_ROOTS"):
            self.assertEqual(self.connector_status().body["connectors"],
                             [{"plugin": LEGACY, "enabled": True, "configured": False}])
            self.assertEqual(self.connector(False, plugin=LEGACY).status_code, 200)
        config = self.alice_config()
        self.assertNotIn("accountbook", config["mcp_servers"])
        self.assertNotIn("accountbook", config["platform_toolsets"]["api_server"])
        self.assertEqual((self.root / "alice/.env").read_text(encoding="utf-8"), "OTHER=keep\n")

    def register_memory(self, name, server="fos-assistant"):
        path = self.root / name / "config.yaml"
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
        config["mcp_servers"] = {server: {"command": "memory"}}
        path.write_text(yaml.safe_dump(config), encoding="utf-8")

    def create(self, body=None):
        return self.request("/api/profiles", "POST", token="valid",
                            body=body if body is not None else {"name": "alice", "no_skills": True})

    def request(self, path, method, token=None, cookie=False, body=None, query_profiles=(), query=None, full_response=False):
        pairs = [("profile", value) for value in query_profiles] + list((query or {}).items())

        class QueryParams:
            def getlist(self, key):
                return [value for name, value in pairs if name == key]

            def keys(self):
                return [name for name, _ in pairs]

        request = types.SimpleNamespace(
            url=types.SimpleNamespace(path=path),
            method=method,
            token=token,
            cookie=cookie,
            state=types.SimpleNamespace(),
            query_params=QueryParams(),
        )

        async def read_json():
            return body

        request.json = read_json

        async def call_next(current):
            authenticated = getattr(current.state, "token_authenticated", False)
            if not (authenticated or current.cookie):
                return types.SimpleNamespace(status_code=401)
            if path == "/api/profiles" and method == "POST":
                # 처리기 흉내. 실패 응답이면 아무것도 만들지 않는다.
                if self.handler_status < 400:
                    for name in self.created_by_handler:
                        self.make_profile(name)
                return types.SimpleNamespace(status_code=self.handler_status)
            if path == "/api/config" and method == "PUT":
                if self.handler_status >= 400:
                    return types.SimpleNamespace(status_code=self.handler_status)
                # Hermes 처리기의 deep merge 를 흉내 낸다. 목록은 통째로 바뀐다.
                target = self.root / body["profile"] / "config.yaml"
                saved = yaml.safe_load(target.read_text(encoding="utf-8"))
                for key, value in body["config"].items():
                    saved.setdefault(key, {}).update(value)
                target.write_text(yaml.safe_dump(saved), encoding="utf-8")
                return types.SimpleNamespace(status_code=200)
            if path == "/api/env" and method in {"PUT", "DELETE"}:
                target = self.root / body["profile"] / ".env"
                lines = target.read_text().splitlines() if target.exists() else []
                lines = [line for line in lines if not line.startswith(body["key"] + "=")]
                if method == "PUT":
                    lines.append(body["key"] + "=" + body["value"])
                target.write_text("\n".join(lines) + "\n", encoding="utf-8")
            return types.SimpleNamespace(status_code=204 if authenticated else 200)

        response = asyncio.run(self.gate(request, call_next))
        return response if full_response else response.status_code

    def test_soul_methods_accept_token_and_preserve_cookie(self):
        """SOUL 경로는 토큰 요청을 받아들이고 쿠키 요청의 기존 동작을 그대로 둔다."""
        for method in ("GET", "PUT"):
            with self.subTest(method=method):
                path = "/api/profiles/agent/soul"
                self.assertEqual(self.request(path, method, token="valid"), 204)
                self.assertEqual(self.request(path, method), 401)
                self.assertEqual(self.request(path, method, token="wrong"), 401)
                self.assertEqual(self.request(path, method, cookie=True), 200)

    def test_soul_path_does_not_open_other_methods_or_nested_names(self):
        """SOUL 경로가 다른 메서드나 중첩된 이름까지 열지 않는다."""
        for path, method in (
            ("/api/profiles/agent/soul", "DELETE"),
            ("/api/profiles/agent/soul", "POST"),
            ("/api/profiles//soul", "GET"),
            ("/api/profiles/agent/nested/soul", "GET"),
        ):
            with self.subTest(path=path, method=method):
                self.assertEqual(self.request(path, method, token="valid"), 401)

    def assert_rolled_back(self, status, deleted=("alice",)):
        self.assertEqual(status, 500)
        self.assertEqual(self.deleted, list(deleted))
        self.assertEqual(sorted(p.name for p in self.root.iterdir()), ["owner"])
        self.assertEqual(self.reloads, [])

    def test_create_writes_template_and_keeps_new_model(self):
        """profile 을 만들면 템플릿을 쓰고 새로 정한 model 을 유지한다."""
        # 틀을 쓰기 전에는 넓게 열린다. 흉내가 실제로 차이를 내는지 먼저 본다.
        self.assertIn("terminal", fake_platform_tools({}, "api_server"))

        self.assertEqual(self.create(), 200)

        config = yaml.safe_load((self.root / "alice/config.yaml").read_text(encoding="utf-8"))
        template = yaml.safe_load(self.template.read_text(encoding="utf-8"))
        self.assertEqual(config["model"], {"default": "gpt-5.6-sol", "provider": "openai-codex"})
        self.assertNotIn("__MODEL__", (self.root / "alice/config.yaml").read_text(encoding="utf-8"))
        self.assertEqual(config["platform_toolsets"], template["platform_toolsets"])
        self.assertEqual(config["agent"], template["agent"])
        self.assertEqual(
            fake_platform_tools(config, "api_server"), {"delegation", "fos-assistant"}
        )
        self.assertTrue((self.root / "alice/.no-bundled-skills").is_file())
        self.assertEqual(self.deleted, [])

    def test_create_registers_control_plane_mcp_and_signing_plugin(self):
        """profile 을 만들면 control plane MCP 와 서명 plugin 을 등록한다."""
        self.assertEqual(self.create(), 200)
        profile = self.root / "alice"
        config = yaml.safe_load((profile / "config.yaml").read_text(encoding="utf-8"))
        server = config["mcp_servers"]["fos-assistant"]
        self.assertEqual(server["url"], MCP_URL)
        # 토큰 원문은 틀에 없다. Control Plane 이 PUT /api/env 로 넣는다.
        self.assertEqual(server["headers"], {"Authorization": "Bearer ${MCP_FOS_ASSISTANT_API_KEY}"})
        self.assertEqual(config["plugins"]["enabled"], ["fos-ctx"])
        self.assertEqual(config["plugins"]["entries"]["fos-ctx"], {"allow_tool_override": False})
        source = self.profile_plugins / "fos-ctx"
        copied = profile / "plugins/fos-ctx"
        self.assertEqual(sorted(p.name for p in copied.iterdir()),
                         sorted(p.name for p in source.iterdir() if p.name != "__pycache__"))
        for name in ("__init__.py", "plugin.yaml"):
            self.assertEqual((copied / name).read_bytes(), (source / name).read_bytes())
            self.assertEqual((copied / name).stat().st_mode & 0o777, 0o644)
        self.assertEqual(copied.stat().st_mode & 0o777, 0o755)
        marker = json.loads((profile / ".fos-assistant-managed").read_text(encoding="utf-8"))
        self.assertEqual(marker["created_by"], "fos-assistant-control-plane")
        self.assertEqual(self.reloads, [("/opt/data", str(profile))])

    def test_create_succeeds_when_gateway_does_not_reload(self):
        """gateway 가 다시 읽지 않아도 profile 생성은 성공한다."""
        type(self).reload_fails = True
        self.assertEqual(self.create(), 200)
        self.assertTrue((self.root / "alice/.fos-assistant-managed").is_file())

    def test_create_removes_profile_when_profile_plugin_is_missing(self):
        """profile 용 plugin 이 없으면 만든 profile 을 지운다."""
        self.plugin.PROFILE_PLUGIN_DIR = self.root / "missing-plugins"
        self.assert_rolled_back(self.create())

    def test_create_rejects_body_keys_before_handler(self):
        """허용하지 않는 본문 키와 형식은 handler 를 부르기 전에 거절한다."""
        for label, body in (
            ("clone_from", {"name": "alice", "clone_from": "owner"}),
            ("clone_all", {"name": "alice", "clone_all": True}),
            ("no name", {"no_skills": True}),
            ("no_skills type", {"name": "alice", "no_skills": "yes"}),
            ("not object", ["alice"]),
        ):
            with self.subTest(label=label):
                self.assertEqual(self.create(body), 400)
                self.assertEqual(sorted(p.name for p in self.root.iterdir()), ["owner"])
        self.assertEqual(self.create({"name": "alice", "no_skills": True, "description": "x"}), 200)

    def test_create_with_two_new_names_removes_both(self):
        """새 이름 둘로 만들면 두 profile 을 모두 지운다."""
        self.created_by_handler = ["alice", "carol"]
        self.assert_rolled_back(self.create(), deleted=("alice", "carol"))

    def test_create_without_template_removes_profile(self):
        """템플릿이 없으면 만든 profile 을 지운다."""
        self.plugin.TEMPLATE_PATH = self.root / "missing.yaml.template"
        self.assert_rolled_back(self.create())

    def test_create_removes_profile_when_forbidden_toolset_remains(self):
        """금지된 toolset 이 남아 있으면 만든 profile 을 지운다."""
        self.tools._get_platform_tools = lambda config, platform: {"delegation", "terminal"}
        self.assert_rolled_back(self.create())

    def test_create_removes_profile_when_calculation_fails(self):
        """유효 toolset 계산이 실패하면 만든 profile 을 지운다."""
        def broken(config, platform):
            raise KeyError("platform")

        self.tools._get_platform_tools = broken
        self.assert_rolled_back(self.create())

    def test_create_removes_profile_when_calculator_cannot_be_imported(self):
        """계산기를 불러오지 못하면 만든 profile 을 지운다."""
        # Hermes 를 올려 내부 함수 이름이 바뀐 경우다. 넓게 열린 profile 을 남기지 않는다.
        del self.tools._get_platform_tools
        self.assert_rolled_back(self.create())

    def test_create_is_refused_before_handler_when_list_fails(self):
        """기존 profile 목록을 읽지 못하면 handler 를 부르기 전에 생성을 거절한다."""
        type(self).list_fails = True
        self.assertEqual(self.create(), 500)
        self.assertEqual(sorted(p.name for p in self.root.iterdir()), ["owner"])
        self.assertEqual(self.deleted, [])

    def test_create_passes_handler_rejection_through(self):
        """handler 가 생성을 거절하면 그 응답을 그대로 돌려준다."""
        self.handler_status = 400
        self.assertEqual(self.create(), 400)
        self.assertEqual(self.deleted, [])
        self.assertEqual(self.reloads, [])

    def test_cookie_create_is_left_to_dashboard(self):
        """쿠키 요청의 생성은 dashboard 원래 동작에 맡긴다."""
        # 사람이 대시보드에서 만드는 길은 plugin 이 손대지 않는다.
        self.assertEqual(self.request("/api/profiles", "POST", cookie=True), 200)
        self.assertEqual(self.request("/api/profiles", "POST"), 401)
        self.assertNotIn("platform_toolsets", yaml.safe_load(
            (self.root / "alice/config.yaml").read_text(encoding="utf-8")
        ))
        self.assertFalse((self.root / "alice/.fos-assistant-managed").exists())

    def test_delete_needs_the_managed_marker(self):
        """관리 표지가 있는 profile 만 지울 수 있다."""
        self.assertEqual(self.create(), 200)
        # 사람이 만든 profile 과 기본 profile 에는 표식이 없다.
        self.assertEqual(self.request("/api/profiles/owner", "DELETE", token="valid"), 401)
        self.assertEqual(self.request("/api/profiles/default", "DELETE", token="valid"), 401)
        self.assertEqual(self.request("/api/profiles/..", "DELETE", token="valid"), 401)
        self.assertEqual(self.request("/api/profiles/missing", "DELETE", token="valid"), 404)
        self.assertEqual(self.request("/api/profiles/alice/soul", "DELETE", token="valid"), 401)
        self.assertEqual(self.request("/api/profiles/alice", "DELETE"), 401)
        self.assertEqual(self.request("/api/profiles/alice", "DELETE", token="wrong"), 401)
        self.assertEqual(self.request("/api/profiles/alice", "DELETE", token="valid"), 204)
        # 사람의 쿠키 요청은 표식과 무관하게 대시보드가 판정한다.
        self.assertEqual(self.request("/api/profiles/owner", "DELETE", cookie=True), 200)

    def env_body(self, **overrides):
        body = {"profile": "owner", "key": "MCP_FOS_ASSISTANT_API_KEY", "value": "token-value"}
        body.update(overrides)
        return body

    def test_env_update_accepts_only_control_plane_keys(self):
        """.env 갱신은 control plane 키만 받아들인다."""
        for key in ("API_SERVER_KEY", "API_SERVER_MODEL_NAME", "MCP_FOS_ASSISTANT_API_KEY"):
            with self.subTest(key=key):
                self.assertEqual(self.request("/api/env", "PUT", token="valid", body=self.env_body(key=key)), 204)
        cases = [
            ("provider key", self.env_body(key="OPENROUTER_API_KEY"), (), 400),
            ("default profile", self.env_body(profile="default"), (), 400),
            ("missing profile", self.env_body(profile="missing"), (), 404),
            ("bad profile name", self.env_body(profile="../owner"), (), 400),
            ("newline", self.env_body(value="a\nAPI_SERVER_KEY" "=b"), (), 400),
            ("not a string", self.env_body(value=1), (), 400),
            ("extra key", {**self.env_body(), "api_key": "x"}, (), 400),
            ("no profile", {"key": "API_SERVER_KEY", "value": "x"}, (), 400),
            ("query mismatch", self.env_body(), ("alice",), 400),
        ]
        for label, body, query_profiles, expected in cases:
            with self.subTest(label=label):
                self.assertEqual(self.request("/api/env", "PUT", token="valid", body=body,
                                              query_profiles=query_profiles), expected)
        self.assertEqual(self.request("/api/env", "PUT", body=self.env_body()), 401)
        self.assertEqual(self.request("/api/env", "PUT", cookie=True, body=self.env_body(key="X")), 200)
        self.assertEqual(self.request("/api/env", "DELETE", token="valid"), 400)

    def test_skill_list_needs_exactly_one_existing_profile(self):
        """skill 목록은 존재하는 profile 하나를 정확히 지정해야 한다."""
        self.assertEqual(self.request("/api/skills", "GET", token="valid", query_profiles=("owner",)), 204)
        for label, query_profiles, query, expected in (
            ("no profile", (), None, 400),
            ("two profiles", ("owner", "owner"), None, 400),
            ("default", ("default",), None, 400),
            ("missing", ("missing",), None, 404),
            ("extra query", ("owner",), {"other": "1"}, 400),
        ):
            with self.subTest(label=label):
                self.assertEqual(self.request("/api/skills", "GET", token="valid",
                                              query_profiles=query_profiles, query=query), expected)
        self.assertEqual(self.request("/api/skills", "GET", query_profiles=("owner",)), 401)
        self.assertEqual(self.request("/api/skills", "POST", token="valid"), 401)
        self.assertEqual(self.request("/api/skills/content", "PUT", token="valid"), 401)

    def test_skill_toggle_body_is_checked(self):
        """skill 켜기와 끄기의 요청 본문을 검사한다."""
        body = {"profile": "owner", "name": "note-taking", "enabled": False}
        self.assertEqual(self.request("/api/skills/toggle", "PUT", token="valid", body=body), 204)
        for label, changed, expected in (
            ("bad name", {"name": "../x"}, 400),
            ("upper name", {"name": "Note"}, 400),
            ("enabled type", {"enabled": "false"}, 400),
            ("default", {"profile": "default"}, 400),
            ("missing", {"profile": "missing"}, 404),
        ):
            with self.subTest(label=label):
                self.assertEqual(self.request("/api/skills/toggle", "PUT", token="valid",
                                              body={**body, **changed}), expected)
        self.assertEqual(self.request("/api/skills/toggle", "PUT", token="valid",
                                      body={**body, "extra": 1}), 400)
        self.assertEqual(self.request("/api/skills/toggle", "PUT", token="valid", body=body,
                                      query_profiles=("alice",)), 400)

    def toolset_body(self):
        return {
            "profile": "owner",
            "config": {
                "platform_toolsets": {"api_server": ["delegation", "web", "fos-assistant"]},
            },
        }

    def test_toolset_update_saves_only_checked_lists(self):
        """toolset 갱신은 검사를 통과한 목록만 저장한다."""
        body = self.toolset_body()
        before = yaml.safe_load((self.root / "owner/config.yaml").read_text(encoding="utf-8"))
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 200)
        saved = yaml.safe_load((self.root / "owner/config.yaml").read_text(encoding="utf-8"))
        self.assertEqual(saved["platform_toolsets"]["api_server"],
                         body["config"]["platform_toolsets"]["api_server"])
        self.assertEqual(saved["platform_toolsets"]["discord"], before["platform_toolsets"]["discord"])
        self.assertEqual(saved["agent"], before["agent"])
        # GET /p/<profile>/v1/toolsets 가 표시하는 API 경로의 실제 켜짐 상태를 계산한다.
        self.assertEqual(fake_platform_tools(saved, "api_server"),
                         {"delegation", "web", "fos-assistant"})
        self.assertEqual(self.request("/api/tools/toolsets", "GET", token="valid"), 204)
        self.assertEqual(self.request("/api/tools/toolsets", "POST", token="valid"), 401)
        self.assertEqual(self.request("/api/config", "GET", token="valid"), 401)
        self.assertEqual(self.request("/api/config", "PUT", cookie=True, body=body), 200)

    def test_unregistered_memory_mcp_needs_builtin_toolset(self):
        """등록되지 않은 memory MCP 는 내장 toolset 이 있어야 다룬다."""
        self.make_profile("blog")
        body = self.toolset_body()
        body["profile"] = "blog"
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 200)
        saved = yaml.safe_load((self.root / "blog/config.yaml").read_text(encoding="utf-8"))
        self.assertEqual(fake_platform_tools(saved, "api_server"),
                         {"delegation", "web", "fos-assistant"})

        body["config"]["platform_toolsets"]["api_server"] = ["fos-assistant"]
        original = (self.root / "blog/config.yaml").read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 400)
        self.assertEqual((self.root / "blog/config.yaml").read_bytes(), original)

    # 옛 이름 fos-assistant-memory 는 더 받지 않는다. 그 이름으로 등록된 profile 이 남아도 거절한다.
    def test_legacy_mcp_name_is_refused_even_if_registered(self):
        """옛 MCP 이름은 등록돼 있어도 거절한다."""
        self.register_memory("owner", "fos-assistant-memory")
        body = self.toolset_body()
        body["config"]["platform_toolsets"]["api_server"] = ["delegation", "web", "fos-assistant-memory"]
        original = (self.root / "owner/config.yaml").read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 400)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    # 새 이름으로 옮긴 profile 에 옛 이름만 보내면 등록된 이름이 목록에 없어 등록된 MCP 가 모두 켜진다.
    def test_legacy_mcp_name_is_refused_after_the_rename(self):
        """이름을 바꾼 뒤에도 옛 MCP 이름은 거절한다."""
        body = self.toolset_body()
        body["config"]["platform_toolsets"]["api_server"] = ["delegation", "web", "fos-assistant-memory"]
        original = (self.root / "owner/config.yaml").read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 400)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    # 공유 gateway 는 multiplex 로 돌아, 계산 함수가 profile scope 밖에서 비밀값을 읽으면 예외가 난다.
    def test_toolset_update_computes_inside_the_profile_scope(self):
        """toolset 갱신은 대상 profile 범위 안에서 유효 목록을 계산한다."""
        seen = []

        def scoped(config, platform):
            seen.append(self.scope["profile"])
            return fake_platform_tools(config, platform)

        self.tools._get_platform_tools = scoped
        self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                      body=self.toolset_body()), 200)
        self.assertTrue(seen)
        self.assertEqual(set(seen), {"owner"})

    def test_toolset_update_rejects_effective_memory_if_hermes_changes(self):
        """Hermes 가 바뀌어 memory 가 유효해지면 toolset 갱신을 거절한다."""
        self.tools._get_platform_tools = lambda config, platform: {"memory", "delegation"}
        original = (self.root / "owner/config.yaml").read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                      body=self.toolset_body()), 400)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_unregistered_memory_mcp_does_not_open_other_mcp(self):
        """등록되지 않은 memory MCP 를 다루는 길이 다른 MCP 를 열지 않는다."""
        self.make_profile("blog")
        path = self.root / "blog/config.yaml"
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
        config["mcp_servers"] = {"other-mcp": {"command": "other"}}
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        body = self.toolset_body()
        body["profile"] = "blog"
        original = path.read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 400)
        self.assertEqual(path.read_bytes(), original)

    def test_toolset_update_rejects_auto_enabled_plugin_toolset(self):
        """plugin 이 자동으로 켠 toolset 이 있으면 갱신을 거절한다."""
        self.tools._get_platform_tools = lambda config, platform: {"delegation", "spotify"}
        original = (self.root / "owner/config.yaml").read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                      body=self.toolset_body()), 400)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_toolset_update_rejects_other_platform_change(self):
        """다른 platform 의 toolset 을 바꾸는 갱신은 거절한다."""
        def changed(config, platform):
            if platform == "discord":
                return {"terminal"} if "api_server" in config.get("platform_toolsets", {}) else {"web"}
            return {"delegation"}

        self.tools._get_platform_tools = changed
        original = (self.root / "owner/config.yaml").read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                      body=self.toolset_body()), 400)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def disable_code_execution(self, discord=("web",)):
        """운영 owner 처럼 API 목록과 disabled_toolsets 에 code_execution 이 함께 있다."""
        path = self.root / "owner/config.yaml"
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
        config["platform_toolsets"] = {"discord": list(discord),
                                       "api_server": ["code_execution", "delegation", "fos-assistant"]}
        config["agent"] = {"disabled_toolsets": ["memory", "terminal", "code_execution"]}
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        return path

    def code_execution_body(self, *extra):
        body = self.toolset_body()
        body["config"]["platform_toolsets"]["api_server"] = ["code_execution", "delegation", "fos-assistant", *extra]
        return body

    def test_toolset_update_lifts_disabled_names_only_for_api(self):
        """켠 도구를 disabled_toolsets 에서 빼고, 목록 없는 다른 platform 은 지금 계산 결과로 고정한다."""
        path = self.disable_code_execution()
        before = yaml.safe_load(path.read_text(encoding="utf-8"))
        self.assertNotIn("code_execution", fake_platform_tools(before, "api_server"))
        others = {name: fake_platform_tools(before, name) for name in ("cli", "discord")}

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.code_execution_body()), 200)
        saved = self.saved_config()
        self.assertEqual(fake_platform_tools(saved, "api_server"), {"code_execution", "delegation", "fos-assistant"})
        self.assertEqual({name: fake_platform_tools(saved, name) for name in others}, others)
        # 요청하지 않은 이름과 memory 는 그대로 남는다.
        self.assertEqual(saved["agent"]["disabled_toolsets"], ["memory", "terminal"])
        self.assertEqual(saved["platform_toolsets"]["cli"], sorted(others["cli"]))
        self.assertEqual(saved["platform_toolsets"]["discord"], ["web"])

        # 끌 때는 허용 목록만 바뀐다. disabled_toolsets 에 다시 넣지 않는다.
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.toolset_body()), 200)
        saved = self.saved_config()
        self.assertEqual(fake_platform_tools(saved, "api_server"), {"delegation", "web", "fos-assistant"})
        self.assertEqual(saved["agent"]["disabled_toolsets"], ["memory", "terminal"])

    def test_toolset_update_keeps_memory_disabled(self):
        """memory 를 요청하면 disabled_toolsets 를 건드리지 않고 거절한다."""
        path = self.disable_code_execution()
        original = path.read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                      body=self.code_execution_body("memory")), 400)
        self.assertEqual(path.read_bytes(), original)

    def test_toolset_update_refuses_to_open_listed_platform(self):
        """목록이 있는 platform 에 그 도구가 열리게 되면 고정하지 않고 거절한다."""
        path = self.disable_code_execution(discord=("web", "code_execution"))
        original = path.read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.code_execution_body()), 400)
        self.assertEqual(path.read_bytes(), original)

    def test_toolset_update_restores_config_when_handler_fails(self):
        """plugin 이 먼저 쓴 설정은 처리기가 실패하면 원래 바이트로 되돌린다."""
        path = self.disable_code_execution()
        original = path.read_bytes()
        self.handler_status = 500
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.code_execution_body()), 500)
        self.assertEqual(path.read_bytes(), original)

    def make_skill_version(self, profile, version, link=None):
        skill = self.skill_root / profile / version / "note"
        skill.mkdir(parents=True)
        (skill / "SKILL.md").write_text("---\nname: note\ndescription: x\n---\nbody\n", encoding="utf-8")
        if link:
            os.symlink(link, skill / "leak.md")
        return str(self.skill_root / profile / version)

    def skills_body(self, dirs, toolsets=None):
        config = {"skills": {"external_dirs": dirs}}
        if toolsets is not None:
            config["platform_toolsets"] = {"api_server": toolsets}
        return {"profile": "owner", "config": config}

    def saved_config(self):
        return yaml.safe_load((self.root / "owner/config.yaml").read_text(encoding="utf-8"))

    def test_skill_publish_with_skills_toolset_in_one_request(self):
        """skills toolset 과 skill 발행을 한 요청에 담아도 처리한다."""
        version = self.make_skill_version("owner", "v1")
        body = self.skills_body([version], ["delegation", "fos-assistant", "skills"])
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 200)
        saved = self.saved_config()
        self.assertEqual(saved["skills"]["external_dirs"], [version])
        self.assertIn("skills", fake_platform_tools(saved, "api_server"))
        # 다음 게시는 목록을 바꾸고, 빈 목록은 게시를 거둔다.
        version2 = self.make_skill_version("owner", "20260929T101500Z-2")
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.skills_body([version2])), 200)
        self.assertEqual(self.saved_config()["skills"]["external_dirs"], [version2])
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.skills_body([])), 200)
        self.assertEqual(self.saved_config()["skills"]["external_dirs"], [])

    def test_skill_publish_rejects_bad_paths_without_writing(self):
        """잘못된 경로의 skill 발행은 아무것도 쓰지 않고 거절한다."""
        good = self.make_skill_version("owner", "v1")
        self.make_skill_version("alice", "v1")
        linked = self.make_skill_version("owner", "vlink", link=str(self.root / "owner/config.yaml"))
        os.symlink("v1", self.skill_root / "owner/valias")
        root = str(self.skill_root)
        cases = [
            ("two entries", [good, good]),
            ("not a list", good),
            ("relative", ["owner/v1"]),
            ("dotdot", [root + "/owner/../owner/v1"]),
            ("dot", [root + "/owner/./v1"]),
            ("double slash", [root + "/owner//v1"]),
            ("tilde", ["~" + root + "/owner/v1"]),
            ("variable", ["${HOME}/owner/v1"]),
            ("other profile", [root + "/alice/v1"]),
            ("profile root", [root + "/owner"]),
            ("too deep", [good + "/note"]),
            ("bad version", [root + "/owner/-v1"]),
            ("missing dir", [root + "/owner/v9"]),
            ("symlinked version", [root + "/owner/valias"]),
            ("symlink inside", [linked]),
            ("not a string", [1]),
        ]
        body = self.skills_body([good], ["delegation", "fos-assistant", "skills"])
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 200)
        original = (self.root / "owner/config.yaml").read_bytes()
        for label, dirs in cases:
            with self.subTest(label=label):
                self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                              body=self.skills_body(dirs)), 400)
                self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_skill_publish_needs_skills_toolset(self):
        """skill 발행은 skills toolset 이 있어야 한다."""
        version = self.make_skill_version("owner", "v1")
        original = (self.root / "owner/config.yaml").read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.skills_body([version])), 400)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)
        # 게시를 거두는 것은 skills 가 꺼져 있어도 된다.
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.skills_body([])), 200)

    def test_skill_publish_keeps_operator_dirs(self):
        """skill 발행이 운영자가 둔 디렉터리를 지우지 않는다."""
        version = self.make_skill_version("owner", "v1")
        path = self.root / "owner/config.yaml"
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
        config["skills"] = {"external_dirs": ["/srv/operator/skills"]}
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        original = path.read_bytes()
        body = self.skills_body([version], ["delegation", "fos-assistant", "skills"])
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 409)
        self.assertEqual(path.read_bytes(), original)

    def test_skill_publish_refuses_other_skill_keys_and_missing_root(self):
        """다른 skill 키나 없는 root 를 가리키는 발행은 거절한다."""
        version = self.make_skill_version("owner", "v1")
        body = self.skills_body([version], ["delegation", "fos-assistant", "skills"])
        body["config"]["skills"]["create_dir"] = "/tmp"
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 400)
        del body["config"]["skills"]["create_dir"]
        os.environ.pop("FOS_ASSISTANT_SKILL_AGENT_ROOT")
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 500)
        # 스킬 루트가 없어도 도구 목록 쓰기는 그대로 된다.
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.toolset_body()), 200)

    def test_toolset_update_rejects_invalid_requests_without_writing(self):
        """잘못된 toolset 갱신 요청은 아무것도 쓰지 않고 거절한다."""
        cases = []
        different_key = self.toolset_body()
        different_key["config"]["model"] = {"default": "other"}
        cases.append(("different key", different_key, (), 400))
        cases.append(("empty config", {"profile": "owner", "config": {}}, (), 400))
        cases.append(("profile mismatch", self.toolset_body(), ("alice",), 400))
        cases.append(("duplicate query", self.toolset_body(), ("owner", "alice"), 400))
        memory_enabled = self.toolset_body()
        memory_enabled["config"]["platform_toolsets"]["api_server"].append("memory")
        cases.append(("memory enabled", memory_enabled, (), 400))
        no_memory_mcp = self.toolset_body()
        no_memory_mcp["config"]["platform_toolsets"]["api_server"].remove("fos-assistant")
        cases.append(("memory MCP missing", no_memory_mcp, (), 400))
        unknown = self.toolset_body()
        unknown["config"]["platform_toolsets"]["api_server"].append("unknown")
        cases.append(("unknown name", unknown, (), 400))
        default_profile = self.toolset_body()
        default_profile["profile"] = "default"
        cases.append(("default profile", default_profile, (), 400))
        missing_profile = self.toolset_body()
        missing_profile["profile"] = "missing"
        cases.append(("missing profile", missing_profile, (), 404))
        agent_key = self.toolset_body()
        agent_key["config"]["agent"] = {"disabled_toolsets": ["memory"]}
        cases.append(("agent key", agent_key, (), 400))
        wrong_type = self.toolset_body()
        wrong_type["config"]["platform_toolsets"]["api_server"] = "delegation"
        cases.append(("wrong list type", wrong_type, (), 400))

        original = (self.root / "owner/config.yaml").read_bytes()
        for label, body, query_profiles, expected in cases:
            with self.subTest(label=label):
                self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                              body=body, query_profiles=query_profiles), expected)
                self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)


if __name__ == "__main__":
    unittest.main(verbosity=2)
