"""Hermes 설치 없이 dashboard-profile-api 의 미들웨어 분기를 검사한다."""

import asyncio
import contextlib
import hashlib
import importlib.util
import json
import logging
import os
import pathlib
import shutil
import sqlite3
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
# 바인딩 설치 검사가 시험 커넥터와 함께 붙이는 두 번째 커넥터다. 시험 커넥터를 복사해 이름과 env 이름만 바꾼다.
OTHER = "other-notes"
OTHER_BASE = "http://other.test/base"
# 보관 파일에 넣는 칸 값이다. 응답과 백업 어디에도 나오면 안 된다.
DEMO_VALUE = "demo_ok_0123456789"
OTHER_VALUE = "other_1234"
VAULT = "/api/connector-vault"
VAULT_IMPORT = "/api/connector-vault/import"

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

    def test_model_defaults_only_returns_public_fields(self):
        path = self.root / "owner/config.yaml"
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
        config["agent"]["reasoning_effort"] = "medium"
        config["secret"] = "must-not-return"
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        response = self.request("/api/profiles/owner/model-defaults", "GET", token="valid", full_response=True)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.body, {"provider": "openai-codex", "model": "gpt-5.6-sol", "reasoningEffort": "medium"})

    def test_model_defaults_requires_control_plane_token(self):
        self.assertEqual(self.request("/api/profiles/owner/model-defaults", "GET", cookie=True), 401)
        self.assertEqual(self.request("/api/profiles/owner/model-defaults", "GET", token="invalid"), 401)

    def test_model_defaults_reads_default_without_allowing_writes(self):
        self.make_profile("default")
        response = self.request("/api/profiles/default/model-defaults", "GET", token="valid", full_response=True)
        self.assertEqual(response.status_code, 200)
        self.assertIsNone(response.body["reasoningEffort"])
        self.assertEqual(self.request("/api/profiles/default", "DELETE", token="valid"), 401)
        self.assertEqual(self.request("/api/profiles/missing/model-defaults", "GET", token="valid"), 404)

    def session_store(self, profile="owner", source="subagent", model="m1", provider="p1", usage=(),
                      usage_table=True, wal=False):
        """그 profile 에 session 저장소를 만들고 `child` session 한 줄을 넣는다. 저장소 파일의 경로를 돌려준다.

        저장소는 검사마다 새로 만드는 임시 profile 디렉터리 아래에 있어 다른 검사에 남지 않는다.
        """
        path = self.root / profile / "state.db"
        connection = sqlite3.connect(path)
        try:
            if wal:
                connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("CREATE TABLE sessions (id TEXT PRIMARY KEY, source TEXT, model TEXT,"
                               " billing_provider TEXT, system_prompt TEXT)")
            if usage_table:
                connection.execute("CREATE TABLE session_model_usage (session_id TEXT, model TEXT,"
                                   " billing_provider TEXT, task TEXT)")
            connection.execute("INSERT INTO sessions VALUES ('child', ?, ?, ?, 'must-not-return')",
                               (source, model, provider))
            if usage:
                connection.executemany("INSERT INTO session_model_usage VALUES ('child', ?, ?, ?)", usage)
            connection.commit()
        finally:
            connection.close()
        return path

    def session_provider(self, session="child", profile="owner", **kwargs):
        return self.request("/api/profiles/%s/sessions/%s/provider" % (profile, session), "GET",
                            **{"token": "valid", "full_response": True, **kwargs})

    def test_session_provider_only_returns_provider_and_model(self):
        """자식 session 의 provider 와 모델만 돌려준다. 같은 줄의 다른 칸은 싣지 않는다."""
        self.session_store(usage=[("m1", "p1", "")])
        response = self.session_provider()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.body, {"provider": "p1", "model": "m1"})

    def test_session_provider_is_returned_without_usage_rows(self):
        self.session_store()
        response = self.session_provider()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.body["provider"], "p1")

    def test_session_provider_ignores_pairs_of_auxiliary_calls(self):
        """제목 만들기 같은 보조 호출이 쓴 모델과 provider 의 짝은 세지 않는다."""
        self.session_store(usage=[("m1", "p1", ""), ("m9", "p9", "title")])
        response = self.session_provider()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.body["provider"], "p1")

    def test_session_provider_is_withheld_when_main_calls_used_two_pairs(self):
        """주 호출의 짝이 둘이면 어느 것으로 환산할지 알 수 없어 provider 를 주지 않는다."""
        self.session_store(usage=[("m1", "p1", ""), ("m2", "p2", "")])
        response = self.session_provider()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.body, {"provider": None, "model": "m1"})

    def test_session_provider_is_withheld_when_the_single_pair_names_another_provider(self):
        """주 호출의 짝이 하나여도 그 provider 가 session 줄과 다르면 provider 를 주지 않는다."""
        self.session_store(usage=[("m1", "p2", "")])
        response = self.session_provider()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.body, {"provider": None, "model": "m1"})

    def test_session_provider_is_returned_when_the_single_pair_has_no_provider(self):
        """주 호출의 짝에 provider 가 비어 있으면 견줄 값이 없어 session 줄의 값을 준다."""
        self.session_store(usage=[("m1", "", "")])
        response = self.session_provider()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.body, {"provider": "p1", "model": "m1"})

    def test_session_provider_is_none_when_the_row_has_no_provider(self):
        for label, provider in (("null", None), ("empty", "")):
            with self.subTest(label):
                path = self.session_store(provider=provider, usage=[("m1", "p1", "")])
                response = self.session_provider()
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.body, {"provider": None, "model": "m1"})
                path.unlink()

    def test_session_provider_rejects_sessions_that_are_not_children(self):
        self.session_store(source="api_server", usage=[("m1", "p1", "")])
        self.assertEqual(self.session_provider().status_code, 404)

    def test_session_provider_is_404_for_missing_session_profile_and_store(self):
        self.make_profile("alice")
        self.assertEqual(self.session_provider(profile="alice").status_code, 404)
        self.session_store()
        self.assertEqual(self.session_provider("other").status_code, 404)
        self.assertEqual(self.session_provider(profile="missing").status_code, 404)

    def test_session_provider_rejects_malformed_session_id(self):
        self.session_store()
        self.assertEqual(self.session_provider("a" * 128).status_code, 404)
        for label, session in (("dot", "a.b"), ("too long", "a" * 129)):
            with self.subTest(label):
                self.assertEqual(self.session_provider(session).status_code, 400)

    def test_session_provider_is_503_when_the_store_lacks_a_table(self):
        self.session_store(usage_table=False)
        self.assertEqual(self.session_provider().status_code, 503)

    def test_session_provider_requires_control_plane_token(self):
        self.session_store(usage=[("m1", "p1", "")])
        self.assertEqual(self.session_provider(token=None, cookie=True).status_code, 401)
        self.assertEqual(self.session_provider(token="invalid").status_code, 401)

    def test_session_provider_does_not_change_the_store(self):
        path = self.session_store(usage=[("m1", "p1", "")])
        before = path.read_bytes()
        self.assertEqual(self.session_provider().status_code, 200)
        self.assertEqual(self.session_provider("other").status_code, 404)
        self.assertEqual(path.read_bytes(), before, "요청 뒤 state.db 의 바이트가 달라졌다")

    def test_session_provider_reads_a_wal_store_without_changing_it(self):
        """실제 Hermes 의 저장소처럼 WAL 인 저장소도 읽고, 본 파일을 바꾸지 않는다."""
        path = self.session_store(usage=[("m1", "p1", "")], wal=True)
        connection = sqlite3.connect(path)
        try:
            self.assertEqual(connection.execute("PRAGMA journal_mode").fetchone()[0], "wal")
        finally:
            connection.close()
        before = path.read_bytes()
        response = self.session_provider()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.body["provider"], "p1")
        self.assertEqual(path.read_bytes(), before, "요청 뒤 state.db 의 바이트가 달라졌다")

    def test_session_provider_reads_the_default_profile(self):
        self.make_profile("default")
        self.session_store(profile="default", usage=[("m1", "p1", "")])
        response = self.session_provider(profile="default")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.body, {"provider": "p1", "model": "m1"})

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
        self.assertEqual(status.body["connectors"], [{"plugin": DEMO, "enabled": True, "configured": True, "mode": "isolated"}])
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
            self.assertEqual(status.body["connectors"], [{"plugin": DEMO, "enabled": True, "configured": False, "mode": "isolated"}])
            self.assertEqual(self.connector_probe(), 404)
            self.assertEqual(self.connector(True).status_code, 400)
            self.assertIn("demo", self.alice_config()["mcp_servers"])

            removed = self.connector(False)
            self.assertEqual(removed.status_code, 200)
            self.assertTrue(removed.body["changed"])
            self.assertTrue(removed.body["restart_required"])
            config = self.alice_config()
            self.assertNotIn("demo", config["mcp_servers"])
            self.assertEqual(config["platform_toolsets"]["api_server"], ["no_mcp"])
            # 설치가 지운 Control Plane MCP 등록은 제거해도 되살아나지 않는다.
            self.assertNotIn("fos-assistant", config["mcp_servers"])
            self.assertEqual(env.read_text(encoding="utf-8"), "OTHER=keep\nMCP_FOS_ASSISTANT_API_KEY=keep-too\n")
            self.assertEqual(json.loads((self.root / "alice/.fos-connectors.json").read_text()), {})
            self.assertEqual(self.connector_status().body["connectors"], [])
            # 기록이 사라진 뒤에는 끌 것이 없다. 바꾸지 않고 성공으로 답하고 설치는 거절한다.
            before = {path.name: path.read_bytes() for path in (self.root / "alice").iterdir() if path.is_file()}
            again = self.connector(False)
            self.assertEqual(again.status_code, 200)
            self.assertFalse(again.body["changed"])
            self.assertFalse(again.body["restart_required"])
            self.assertEqual(self.connector(True).status_code, 400)
            after = {path.name: path.read_bytes() for path in (self.root / "alice").iterdir() if path.is_file()}
            self.assertEqual(after, before)

    def test_connector_installs_idempotently_and_preserves_profile_secrets(self):
        """connector 는 manifest 에서 등록하고 도구 목록을 그 서버만으로 쓰며 profile 의 비밀값을 보존한다."""
        root = self.connector_fixture()
        env = self.root / "alice/.env"
        env.write_text("DEMO_TOKEN=test-token\nOTHER=keep\nMCP_FOS_ASSISTANT_API_KEY=keep-too\n", encoding="utf-8")
        env.chmod(0o600)
        self.assertIn("fos-assistant", self.alice_config()["mcp_servers"])
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
        self.assertEqual(config["platform_toolsets"]["api_server"], ["demo"])
        self.assertNotIn("fos-assistant", config["mcp_servers"])
        record = json.loads((self.root / "alice/.fos-connectors.json").read_text())
        self.assertEqual(record, {DEMO: {"server": server, "allowlist_added": True, "mcp_server": "demo"}})
        self.assertIn("DEMO_TOKEN=test-token", env.read_text())
        self.assertIn("MCP_FOS_ASSISTANT_API_KEY=keep-too", env.read_text().splitlines())
        self.assertEqual(env.stat().st_mode & 0o777, 0o600)
        backups = sorted(path.name for path in (self.root / "alice/connector-backups").glob("*/*"))
        # 백업에는 설정만 있다. 사용자의 비밀 원문이 있는 `.env` 는 뜨지 않는다.
        self.assertEqual(backups, ["config.yaml"])
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
        self.assertEqual(config["platform_toolsets"]["api_server"], ["no_mcp"])
        self.assertNotIn("fos-assistant", config["mcp_servers"])
        self.assertEqual(list((self.root / "alice/connector-backups").glob("*/.env")), [])
        # 운영 목록에 있는 커넥터의 env 는 Control Plane 이 칸마다 지운다. 설치를 끄는 것이 지우지 않는다.
        self.assertIn("DEMO_TOKEN=test-token", env.read_text())

    def test_connector_uninstall_leaves_no_secret_on_disk(self):
        """비밀값을 넣고 설치한 뒤 해제하면 그 원문이 profile 디렉터리의 어느 파일에도 남지 않는다."""
        self.connector_fixture()
        secret = "demo_secret_value_0123456789"
        self.assertEqual(self.request("/api/env", "PUT", token="valid", body={
            "profile": "alice", "key": "DEMO_TOKEN", "value": secret}), 200)
        self.assertEqual(self.connector().status_code, 200)
        self.assertEqual(self.request("/api/env", "DELETE", token="valid", body={
            "profile": "alice", "key": "DEMO_TOKEN"}), 200)
        self.assertEqual(self.connector(False).status_code, 200)
        files = [path for path in (self.root / "alice").rglob("*") if path.is_file()]
        self.assertIn(self.root / "alice/config.yaml", files)
        holding = [str(path.relative_to(self.root)) for path in files if secret.encode() in path.read_bytes()]
        self.assertEqual(holding, [], "비밀 원문이 남은 파일이 있다")
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["no_mcp"])

    def test_connector_write_removes_env_copies_left_by_older_version(self):
        """이전 판이 백업에 남긴 `.env` 사본은 다음 설치가 지운다."""
        self.connector_fixture()
        stale = self.root / "alice/connector-backups/1/.env"
        stale.parent.mkdir(parents=True)
        stale.write_text("DEMO_TOKEN=demo_secret_value_0123456789\n", encoding="utf-8")
        self.assertEqual(self.connector().status_code, 200)
        self.assertFalse(stale.exists())
        self.assertEqual(list((self.root / "alice/connector-backups").glob("*/.env")), [])

    def test_unchanged_connector_install_still_removes_old_env_copies(self):
        """이미 설치되어 바뀔 것이 없는 요청도 이전 판이 백업에 남긴 `.env` 사본을 지운다."""
        self.connector_fixture()
        self.assertEqual(self.connector().status_code, 200)
        stale = self.root / "alice/connector-backups/1/.env"
        stale.parent.mkdir(parents=True)
        stale.write_text("DEMO_TOKEN=demo_secret_value_0123456789\n", encoding="utf-8")
        repeated = self.connector()
        self.assertEqual(repeated.status_code, 200)
        self.assertFalse(repeated.body["changed"])
        self.assertFalse(stale.exists())

    def test_connector_install_continues_when_old_env_copy_cannot_be_removed(self):
        """옛 `.env` 사본을 지우지 못해도 설치는 끝난다."""
        self.connector_fixture()
        stale = self.root / "alice/connector-backups/1/.env"
        stale.parent.mkdir(parents=True)
        stale.write_text("DEMO_TOKEN=x\n", encoding="utf-8")
        unlink = pathlib.Path.unlink

        def failing_unlink(target, *args, **kwargs):
            # 옛 사본만 지우지 못하게 한다. 원자적 쓰기의 임시 파일 정리는 그대로 둔다.
            if target == stale:
                raise PermissionError("injected")
            return unlink(target, *args, **kwargs)

        with mock.patch.object(pathlib.Path, "unlink", autospec=True, side_effect=failing_unlink):
            response = self.connector()
        self.assertEqual(response.status_code, 200)
        self.assertTrue(stale.exists())
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["demo"])

    def test_connector_install_rewrites_old_allowlist(self):
        """앞선 판이 쓴 목록과 Control Plane MCP 등록이 남은 profile 은 설치가 덜 된 것으로 보고 다시 쓴다."""
        self.connector_fixture()
        control_plane = self.alice_config()["mcp_servers"]["fos-assistant"]
        self.assertEqual(self.connector().status_code, 200)
        path = self.root / "alice/config.yaml"
        config = self.alice_config()
        config["mcp_servers"]["fos-assistant"] = control_plane
        config["platform_toolsets"]["api_server"] = ["fos-assistant", "demo"]
        path.write_text(yaml.safe_dump(config, sort_keys=False, allow_unicode=True), encoding="utf-8")
        self.assertEqual(self.connector_status().body["connectors"],
                         [{"plugin": DEMO, "enabled": True, "configured": False, "mode": "isolated"}])
        installed = self.connector()
        self.assertEqual(installed.status_code, 200)
        self.assertTrue(installed.body["changed"])
        self.assertTrue(installed.body["restart_required"])
        config = self.alice_config()
        self.assertEqual(config["platform_toolsets"]["api_server"], ["demo"])
        self.assertNotIn("fos-assistant", config["mcp_servers"])
        self.assertEqual(self.connector_status().body["connectors"],
                         [{"plugin": DEMO, "enabled": True, "configured": True, "mode": "isolated"}])

    def test_connector_install_works_without_control_plane_mcp_in_allowlist(self):
        """목록에 Control Plane MCP 가 없는 관리 profile 에도 설치하고 목록을 커넥터 서버만으로 쓴다."""
        self.connector_fixture()
        path = self.root / "alice/config.yaml"
        config = self.alice_config()
        config["platform_toolsets"]["api_server"] = ["delegation"]
        path.write_text(yaml.safe_dump(config, sort_keys=False, allow_unicode=True), encoding="utf-8")
        response = self.connector()
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.body["changed"])
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["demo"])

    def test_turning_off_a_connector_that_is_not_installed_keeps_the_allowlist(self):
        """설치하지 않은 커넥터를 꺼도 일반 profile 의 도구 목록과 Control Plane MCP 등록은 그대로다."""
        self.connector_fixture()
        before = self.alice_config()
        response = self.connector(False)
        self.assertEqual(response.status_code, 200)
        self.assertFalse(response.body["restart_required"])
        after = self.alice_config()
        self.assertEqual(after["platform_toolsets"]["api_server"], before["platform_toolsets"]["api_server"])
        self.assertIn("fos-assistant", after["platform_toolsets"]["api_server"])
        self.assertIn("fos-assistant", after["mcp_servers"])

    def test_connector_allowlist_holds_a_name_shared_by_server_and_toolset_once(self):
        """서버 이름과 선언한 toolset 이름이 같으면 목록에 그 이름을 한 번만 싣는다."""
        root = self.connector_fixture()
        manifest = root / "connector.json"
        declared = json.loads(manifest.read_text(encoding="utf-8"))
        declared["toolsets"] = ["vision"]
        manifest.write_text(json.dumps(declared), encoding="utf-8")
        mcp_path = root / ".mcp.json"
        mcp = json.loads(mcp_path.read_text(encoding="utf-8"))
        servers = mcp["mcpServers"] if "mcpServers" in mcp else mcp
        servers["vision"] = servers.pop(next(iter(servers)))
        mcp_path.write_text(json.dumps(mcp), encoding="utf-8")
        self.assertEqual(self.connector().status_code, 200)
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["vision"])

    def test_connector_install_adds_declared_toolsets_after_the_server_name(self):
        """manifest 가 `toolsets` 를 선언하면 목록은 서버 이름 다음에 그 toolset 이고 Control Plane MCP 는 없다."""
        root = self.connector_fixture()
        manifest = root / "connector.json"
        declared = json.loads(manifest.read_text(encoding="utf-8"))
        declared["toolsets"] = ["vision"]
        manifest.write_text(json.dumps(declared), encoding="utf-8")
        self.assertEqual(self.connector().status_code, 200)
        config = self.alice_config()
        self.assertEqual(config["platform_toolsets"]["api_server"], ["demo", "vision"])
        self.assertNotIn("fos-assistant", config["mcp_servers"])
        self.assertEqual(self.connector_status().body["connectors"],
                         [{"plugin": DEMO, "enabled": True, "configured": True, "mode": "isolated"}])
        # 선언이 바뀌면 같은 목록이 아니라 설치가 덜 된 것으로 보고, 다시 설치하면 새 목록이 된다.
        del declared["toolsets"]
        manifest.write_text(json.dumps(declared), encoding="utf-8")
        self.assertEqual(self.connector_status().body["connectors"],
                         [{"plugin": DEMO, "enabled": True, "configured": False, "mode": "isolated"}])
        self.assertEqual(self.connector().status_code, 200)
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["demo"])
        # 마지막 커넥터를 끄면 선언한 toolset 도 남지 않는다.
        declared["toolsets"] = ["vision"]
        manifest.write_text(json.dumps(declared), encoding="utf-8")
        self.assertEqual(self.connector().status_code, 200)
        self.assertEqual(self.connector(False).status_code, 200)
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["no_mcp"])

    def test_connector_install_writes_skill_body_as_persona(self):
        """설치는 스킬 본문을 앞머리 없이 그 profile 의 SOUL.md 에 쓰고, 본문이 바뀌면 다시 설치할 때 다시 쓴다."""
        root = self.connector_fixture()
        soul = self.root / "alice/SOUL.md"
        soul.write_text("기본 성격\n", encoding="utf-8")
        other = self.root / "bob/SOUL.md"
        self.make_profile("bob")
        other.write_text("bob 의 성격\n", encoding="utf-8")
        self.assertEqual(self.connector().status_code, 200)
        body = soul.read_text(encoding="utf-8")
        self.assertEqual(body, "# 검사용 메모\n\n`list_scopes` 로 볼 수 있는 범위를 조회한다.\n")
        self.assertNotIn("description:", body)
        # 다른 profile 의 지침은 그대로다.
        self.assertEqual(other.read_text(encoding="utf-8"), "bob 의 성격\n")
        # plugin 의 스킬 본문이 바뀌면 같은 설치 요청이 지침을 다시 쓴다. 서버 정의는 그대로다.
        skill = root / "skills/demo/SKILL.md"
        skill.write_text(skill.read_text(encoding="utf-8") + "\n새 절차다.\n", encoding="utf-8")
        config_before = (self.root / "alice/config.yaml").read_bytes()
        repeated = self.connector()
        self.assertEqual(repeated.status_code, 200)
        self.assertTrue(repeated.body["changed"])
        self.assertTrue(soul.read_text(encoding="utf-8").endswith("새 절차다.\n"))
        self.assertEqual((self.root / "alice/config.yaml").read_bytes(), config_before)
        self.assertFalse(self.connector().body["changed"])
        # 해제는 지침을 지우지 않는다. 에이전트가 꺼지고 다시 등록하면 다시 쓴다.
        self.assertEqual(self.connector(False).status_code, 200)
        self.assertTrue(soul.read_text(encoding="utf-8").endswith("새 절차다.\n"))

    def test_connector_persona_is_rolled_back_and_kept_off_unmanaged_profiles(self):
        """지침 쓰기가 실패하면 설정과 기록을 되돌리고, 관리 표식이 없는 profile 에는 쓰지 않는다."""
        self.connector_fixture()
        soul = self.root / "alice/SOUL.md"
        soul.write_text("기본 성격\n", encoding="utf-8")
        before = (self.root / "alice/config.yaml").read_bytes()
        write = self.plugin._atomic_private_write

        def failing_write(target, value):
            if target.name == "SOUL.md" and target.parent.name == "alice":
                raise OSError("injected")
            return write(target, value)

        with mock.patch.object(self.plugin, "_atomic_private_write", side_effect=failing_write):
            self.assertEqual(self.connector().status_code, 503)
        self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)
        self.assertEqual(soul.read_text(encoding="utf-8"), "기본 성격\n")
        self.assertFalse((self.root / "alice" / self.plugin.CONNECTOR_STATE).exists())
        # 요청 경로는 표식을 먼저 본다. 함수만 불러도 표식 없는 profile 의 지침은 바뀌지 않는다.
        (self.root / "alice" / self.plugin.MANAGED_MARKER).unlink()
        self.assertEqual(self.connector().status_code, 401)
        with self.assertRaises(ValueError):
            self.plugin._connector_config(self.root / "alice", DEMO, True)
        self.assertEqual(soul.read_text(encoding="utf-8"), "기본 성격\n")

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
        # 운영 목록에도 소유 기록에도 없는 이름을 끄는 것은 끌 것이 없어 성공이다. 설정은 그대로다.
        before = (self.root / "alice/config.yaml").read_bytes()
        unknown = self.connector(False, plugin="unknown")
        self.assertEqual(unknown.status_code, 200)
        self.assertEqual(unknown.body, {"profile": "alice", "plugin": "unknown", "enabled": False,
                                        "changed": False, "restart_required": False,
                                        "plugin_updated": False})
        self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)
        self.assertFalse((self.root / "alice/.fos-connectors.json").exists())
        self.assertEqual(self.connector_status().body["connectors"],
                         [{"plugin": DEMO, "enabled": False, "configured": False, "mode": "isolated"}])
        # 이름 형식 검사와 관리 표식 검사는 끄기에도 그대로 걸린다.
        self.assertEqual(self.connector(False, plugin="unknown", profile="owner").status_code, 401)
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
        self.assertEqual(response.body["connectors"], [{"plugin": DEMO, "enabled": True, "configured": True, "mode": "isolated"}])
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

    def test_optional_field_update_restores_the_profile_plugin_and_asks_for_restart(self):
        """선택 칸을 쓰는 요청도 hook plugin 을 묶음의 판으로 되돌리고 재시작이 필요하다고 답한다."""
        self.connector_fixture()
        self.assertEqual(self.connector().status_code, 200)
        installed = self.root / "alice/plugins/fos-ctx/__init__.py"
        bundled = (self.profile_plugins / "fos-ctx/__init__.py").read_bytes()
        installed.write_bytes(bundled + b"# changed\n")
        response = self.request("/api/env", "PUT", token="valid", full_response=True, body={
            "profile": "alice", "key": "DEMO_SCOPE", "value": "a"})
        self.assertEqual(response.status_code, 200)
        self.assertIs(response.body["restart_required"], True)
        self.assertEqual(installed.read_bytes(), bundled)
        self.assertEqual(installed.stat().st_mode & 0o777, 0o644)
        self.assertIs(self.policy_hook(), True)

    def test_connector_accepts_direct_server_manifest_and_plain_optional_reference(self):
        """`mcpServers` 없이 서버를 바로 둔 `.mcp.json` 과 기본값 없는 선택 칸 참조도 같은 정의가 된다."""
        root = self.connector_fixture()
        path = root / ".mcp.json"
        value = json.loads(path.read_text())["mcpServers"]
        value["demo"]["env"]["DEMO_SCOPE"] = "${DEMO_SCOPE}"
        path.write_text(json.dumps(value))
        self.assertEqual(self.connector().status_code, 200)
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"]["DEMO_SCOPE"], "")

    def declare_tools(self, root, tools):
        """그 커넥터의 `connector.json` 을 `schema: 2` 로 바꾸고 도구 정책을 선언한다."""
        path = root / "connector.json"
        declared = json.loads(path.read_text(encoding="utf-8"))
        declared.update(schema=2, tools=tools)
        path.write_text(json.dumps(declared), encoding="utf-8")

    def tool_map(self):
        return json.loads((self.root / "alice" / self.plugin.CONNECTOR_TOOL_MAP).read_text(encoding="utf-8"))

    def policy_hook(self):
        status = self.connector_status()
        self.assertEqual(status.status_code, 200)
        return status.body["policy_hook"]

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
        # manifest 가 바뀌면 다시 설치하기 전까지 대응이 옛것이다.
        self.assertIs(self.policy_hook(), False)
        repeated = self.connector()
        self.assertEqual(repeated.status_code, 200)
        self.assertIs(repeated.body["changed"], True)
        self.assertIs(repeated.body["plugin_updated"], False)
        self.assertEqual(self.tool_map()["servers"]["demo"]["tools"], {
            "mcp__demo__list_scopes": "list_scopes", "mcp__demo__env_view": "env_view",
            "mcp__demo__write_note": "write_note"})
        self.assertNotIn("tools", self.alice_config()["mcp_servers"]["demo"])
        self.assertIs(self.policy_hook(), True)
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
        self.plugin.PROFILE_PLUGIN_DIR = self.root.parent / "no-profile-plugins"
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

        # 도구 이름은 그대로이고 승인 방식만 올랐다. 대응 파일은 같고 서버 정의만 옛것이다.
        self.declare_tools(root, {"list_scopes": {"risk": "READ"}, "purge": {"risk": "DESTRUCTIVE"},
                                  "erase": {"risk": "WRITE", "approval": "always"}})
        status = self.connector_status()
        self.assertEqual(status.status_code, 200)
        self.assertEqual(status.body["connectors"], [{"plugin": DEMO, "enabled": True, "configured": True, "mode": "isolated"}])
        self.assertIs(self.policy_hook(), False)

        self.assertEqual(self.connector().status_code, 200)
        server = self.alice_config()["mcp_servers"]["demo"]
        self.assertEqual(server["tools"], {"exclude": ["erase", "purge"]})
        record_path = self.root / "alice" / self.plugin.CONNECTOR_STATE
        record = json.loads(record_path.read_text(encoding="utf-8"))
        self.assertEqual(record[DEMO]["server"], server)
        # 모델에게서 뺀 도구도 대응에는 있다. 다른 경로로 불리면 hook 이 정책을 찾아야 한다.
        self.assertEqual(self.tool_map()["servers"]["demo"]["tools"]["mcp__demo__purge"], "purge")
        self.assertIs(self.policy_hook(), True)
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

        with mock.patch.object(self.plugin, "_atomic_private_write", side_effect=failing_write):
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

        with mock.patch.object(self.plugin, "_atomic_private_write", side_effect=failing_write):
            self.assertEqual(self.connector().status_code, 503)
        self.assertFalse((profile / "plugins").exists())
        self.assertFalse((profile / self.plugin.CONNECTOR_TOOL_MAP).exists())
        self.assertFalse((profile / self.plugin.CONNECTOR_STATE).exists())
        self.assertNotIn("demo", self.alice_config()["mcp_servers"])

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

        # 앞선 판의 목록에는 Control Plane MCP 가 함께 있다. 다시 설치하기 전에는 설치가 덜 된 것이다.
        status = self.connector_status()
        self.assertEqual(status.status_code, 200)
        self.assertEqual(status.body["connectors"], [{"plugin": LEGACY, "enabled": True, "configured": False, "mode": "isolated"}])
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
        self.assertIs(record["allowlist_added"], True)
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["accountbook"])
        self.assertNotIn("fos-assistant", self.alice_config()["mcp_servers"])
        self.assertEqual(record["server"]["env"], {"ACCOUNTBOOK_API_BASE_URL": LEGACY_BASE,
                                                   "ACCOUNTBOOK_API_TOKEN": "${ACCOUNTBOOK_API_TOKEN}",
                                                   "ACCOUNTBOOK_FAMILY_UUID": ""})
        self.assertEqual(self.alice_config()["mcp_servers"]["accountbook"], record["server"])
        self.assertEqual(self.connector_status().body["connectors"],
                         [{"plugin": LEGACY, "enabled": True, "configured": True, "mode": "isolated"}])
        self.assertEqual(self.connector_probe("accountbook"), 204)

    def test_legacy_ownership_record_meets_a_manifest_that_excludes_tools(self):
        """`tools` 가 없는 앞선 기록은 늘 승인이 필요한 도구를 선언한 manifest 를 만나도 조회, 재설치, 해제가 된다."""
        legacy = self.legacy_fixture()
        self.declare_tools(self.root.parent / "legacy-connector",
                           {"list_families": {"risk": "READ"}, "purge": {"risk": "DESTRUCTIVE"}})
        status = self.connector_status()
        self.assertEqual(status.status_code, 200)
        self.assertEqual(status.body["connectors"], [{"plugin": LEGACY, "enabled": True, "configured": False, "mode": "isolated"}])
        self.assertIs(status.body["policy_hook"], False)
        self.assertNotIn("tools", legacy)

        installed = self.connector(plugin=LEGACY)
        self.assertEqual(installed.status_code, 200)
        self.assertIs(installed.body["changed"], True)
        server = self.alice_config()["mcp_servers"]["accountbook"]
        self.assertEqual(server["tools"], {"exclude": ["purge"]})
        record = json.loads((self.root / "alice/.fos-connectors.json").read_text(encoding="utf-8"))
        self.assertEqual(record[LEGACY]["server"], server)
        status = self.connector_status()
        self.assertEqual(status.body["connectors"], [{"plugin": LEGACY, "enabled": True, "configured": True, "mode": "isolated"}])
        self.assertIs(status.body["policy_hook"], True)

        self.assertEqual(self.connector(False, plugin=LEGACY).status_code, 200)
        self.assertNotIn("accountbook", self.alice_config()["mcp_servers"])
        self.assertEqual(self.tool_map(), {"v": 1, "servers": {}})

    def test_legacy_ownership_record_is_turned_off_under_a_manifest_that_excludes_tools(self):
        """`tools` 가 없는 앞선 기록은 다시 설치하지 않고도 해제된다."""
        self.legacy_fixture()
        self.declare_tools(self.root.parent / "legacy-connector",
                           {"list_families": {"risk": "READ"}, "purge": {"risk": "DESTRUCTIVE"}})
        self.assertEqual(self.connector(False, plugin=LEGACY).status_code, 200)
        self.assertNotIn("accountbook", self.alice_config()["mcp_servers"])

    def test_legacy_ownership_record_keeps_working_after_optional_field_changes(self):
        """앞선 기록 위에서 선택 칸을 채워도 설정과 기록이 함께 바뀌고 probe 가 통과한다."""
        self.legacy_fixture()
        self.assertEqual(self.request("/api/env", "PUT", token="valid", body={
            "profile": "alice", "key": "ACCOUNTBOOK_FAMILY_UUID", "value": "family-fixture"}), 200)
        env = self.alice_config()["mcp_servers"]["accountbook"]["env"]
        self.assertEqual(env["ACCOUNTBOOK_FAMILY_UUID"], "${ACCOUNTBOOK_FAMILY_UUID}")
        # 선택 칸 쓰기가 설치를 다시 쓰므로 목록도 커넥터 서버만 남는다.
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["accountbook"])
        self.assertEqual(self.connector_probe("accountbook"), 204)
        self.assertEqual(self.connector(False, plugin=LEGACY).status_code, 200)
        self.assertNotIn("accountbook", self.alice_config()["mcp_servers"])
        self.assertEqual(self.alice_config()["platform_toolsets"]["api_server"], ["no_mcp"])

    def test_legacy_ownership_record_can_be_turned_off_after_removal_from_operator_list(self):
        """서버 이름 칸이 없는 앞선 기록도 운영 목록에서 빠진 뒤 설치를 끄고 참조하던 env key 를 지운다."""
        self.legacy_fixture()
        with self.without_environment("FOS_ASSISTANT_CONNECTOR_ROOTS"):
            self.assertEqual(self.connector_status().body["connectors"],
                             [{"plugin": LEGACY, "enabled": True, "configured": False, "mode": "isolated"}])
            self.assertEqual(self.connector(False, plugin=LEGACY).status_code, 200)
        config = self.alice_config()
        self.assertNotIn("accountbook", config["mcp_servers"])
        self.assertEqual(config["platform_toolsets"]["api_server"], ["no_mcp"])
        self.assertEqual((self.root / "alice/.env").read_text(encoding="utf-8"), "OTHER=keep\n")

    def other_connector(self):
        """시험 커넥터를 복사해 이름, 서버 이름, env 이름, 스킬 이름을 바꾼 두 번째 커넥터를 만든다."""
        root = self.root.parent / "other-connector"
        shutil.copytree(DEMO_CONNECTOR, root)
        declared = json.loads((root / "connector.json").read_text(encoding="utf-8"))
        declared.update(id=OTHER, title="다른 메모", operator_env=["OTHER_BASE"])
        declared["fields"][0].update(env="OTHER_TOKEN", pattern="^other_[0-9]{4}$")
        declared["fields"][1].update(env="OTHER_SCOPE")
        (root / "connector.json").write_text(json.dumps(declared), encoding="utf-8")
        (root / ".mcp.json").write_text(json.dumps({"mcpServers": {"other": {
            "command": "python3", "args": ["${CLAUDE_PLUGIN_ROOT}/server.py"],
            "env": {"OTHER_TOKEN": "${OTHER_TOKEN}", "OTHER_SCOPE": "${OTHER_SCOPE:-}", "OTHER_BASE": "${OTHER_BASE}"},
        }}}), encoding="utf-8")
        (root / ".claude-plugin/plugin.json").write_text(json.dumps({"name": OTHER, "skills": "./skills"}),
                                                         encoding="utf-8")
        shutil.rmtree(root / "skills/demo")
        (root / "skills/other-dir").mkdir()
        (root / "skills/other-dir/SKILL.md").write_text(
            "---\nname: other\ndescription: 두 번째 커넥터의 스킬이다.\n---\n\n# 다른 메모\n", encoding="utf-8")
        return root

    def bind_fixture(self):
        """바인딩 설치 검사의 준비다. 커넥터 둘을 운영 목록에 올리고 일반 에이전트의 관리 profile 과 보관 파일 둘을 둔다."""
        self.make_profile("alice")
        self.plugin._apply_template("alice")
        demo = self.root.parent / "demo-connector"
        shutil.copytree(DEMO_CONNECTOR, demo)
        (demo / "skills/demo/references").mkdir()
        (demo / "skills/demo/references/guide.md").write_text("범위를 고르는 법\n", encoding="utf-8")
        other = self.other_connector()
        self.connector_environment({DEMO: {"root": str(demo), "env": {"DEMO_BASE": DEMO_BASE}},
                                    OTHER: {"root": str(other), "env": {"OTHER_BASE": OTHER_BASE}}})
        # 보관 파일은 대시보드의 Hermes 루트 아래에 있다. 검사마다 임시 디렉터리를 루트로 준다.
        self.hermes_root = self.root.parent / "hermes"
        self.hermes_root.mkdir()
        home = mock.patch.object(sys.modules["hermes_constants"], "get_default_hermes_root",
                                 lambda: str(self.hermes_root))
        home.start()
        self.addCleanup(home.stop)
        # 일반 에이전트는 Control Plane MCP 와 내장 도구를 쓰고 사용자의 성격 본문이 있다.
        config = self.alice_config()
        config["platform_toolsets"]["api_server"] = ["delegation", "fos-assistant", "terminal"]
        self.write_config("alice", config)
        (self.root / "alice/SOUL.md").write_text("사용자의 성격\n", encoding="utf-8")
        self.assertEqual(self.vault("PUT", vault="c1", connector=DEMO, values={"token": DEMO_VALUE}).status_code, 200)
        self.assertEqual(self.vault("PUT", vault="c2", connector=OTHER,
                                    values={"token": OTHER_VALUE, "scope": "a"}).status_code, 200)
        return demo, other

    def write_config(self, profile, config):
        (self.root / profile / "config.yaml").write_text(
            yaml.safe_dump(config, sort_keys=False, allow_unicode=True), encoding="utf-8")

    def vault(self, method, path=VAULT, **body):
        return self.request(path, method, token="valid", full_response=True, body=body,
                            query_profiles=[body["profile"]] if "profile" in body else ())

    def bind(self, plugin=DEMO, vault="c1", profile="alice", enabled=True):
        body = {"profile": profile, "plugin": plugin, "enabled": enabled}
        if enabled:
            body["bind"] = {"vault": vault}
        return self.request("/api/connectors", "PUT", token="valid", full_response=True, body=body)

    def tree(self, profile):
        """profile 디렉터리의 모든 파일 내용이다. 실패한 요청이 아무것도 바꾸지 않았는지 볼 때 쓴다."""
        base = self.root / profile
        return {str(path.relative_to(base)): path.read_bytes() for path in sorted(base.rglob("*"))
                if path.is_file() and "__pycache__" not in path.parts}

    def status_of(self, profile="alice"):
        response = self.request("/api/connectors", "GET", token="valid", query_profiles=[profile],
                                full_response=True)
        self.assertEqual(response.status_code, 200)
        return response.body

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
        self.assertEqual(list(self.tool_map()["servers"]), ["other"])
        self.assertEqual((self.root / "alice/SOUL.md").read_text(encoding="utf-8"), "사용자의 성격\n")
        self.assertEqual(self.status_of()["connectors"], [
            {"plugin": DEMO, "enabled": False, "configured": False, "mode": "isolated"},
            {"plugin": OTHER, "enabled": True, "configured": True, "mode": "bind"}])

        # 마지막 바인딩을 떼면 대응 파일이 없어지고 목록은 붙이기 전으로 돌아간다.
        last = self.bind(OTHER, enabled=False)
        self.assertEqual(last.status_code, 200)
        self.assertIs(last.body["restart_required"], False)
        self.assertFalse((self.root / "alice" / self.plugin.CONNECTOR_TOOL_MAP).exists())
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
                self.assertIn("other", self.alice_config()["mcp_servers"])
                self.assertEqual(self.tool_map(), {"v": 1, "isolated": False, "servers": {
                    "other": {"connector": OTHER, "prefix": "mcp__other__", "tools": {}}}})

                # 다음 경우를 위해 되돌린다.
                (other / ".mcp.json").write_text(original, encoding="utf-8")
                self.assertEqual(self.bind(OTHER, enabled=False).status_code, 200)
                self.assertFalse((alice / self.plugin.CONNECTOR_TOOL_MAP).exists())

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

    def test_values_with_quotes_and_backslashes_round_trip_through_vault_binding_and_import(self):
        """`"` 와 `\\` 가 든 값은 보관 파일에서 바인딩 `.env` 로 쓰인 뒤 실행과 옮기기가 같은 값으로 읽는다."""
        self.bind_fixture()
        tricky = 'a "quoted" path\\to\\ dir #1'
        self.assertEqual(self.vault("PUT", vault="c1", connector=DEMO,
                                    values={"token": DEMO_VALUE, "scope": tricky}).status_code, 200)
        self.assertEqual(self.bind(DEMO, "c1").status_code, 200)

        env_text = (self.root / "alice/.env").read_text(encoding="utf-8")
        self.assertIn('DEMO_SCOPE="', env_text)
        self.assertEqual(self.plugin._env_value(env_text, "DEMO_SCOPE"), tricky)
        self.assertEqual(self.plugin._env_value(env_text, "DEMO_TOKEN"), DEMO_VALUE)
        # 옮기기는 그 profile 의 `.env` 를 읽어 새 보관 파일을 만든다. 처음 보관한 값과 같아야 한다.
        imported = self.vault("POST", VAULT_IMPORT, vault="c7", connector=DEMO, profile="alice")
        self.assertEqual(imported.status_code, 200, imported.body)
        self.assertEqual(json.loads((self.hermes_root / "connector-vault/c7.json").read_text())["values"],
                         {"token": DEMO_VALUE, "scope": tricky})

    def test_env_value_reads_what_env_line_writes(self):
        """`_env_value` 는 `_env_line` 의 역이고 `export` 꼴과 `=` 둘레의 공백과 작은따옴표도 읽는다."""
        for value in ('plain', 'a "b" c', 'back\\slash', 'end\\', '"', "it's #hash", ""):
            with self.subTest(value=value):
                self.assertEqual(self.plugin._env_value(self.plugin._env_line("K", value), "K"), value)
        self.assertEqual(self.plugin._env_value('export K="x \\" y"\n', "K"), 'x " y')
        self.assertEqual(self.plugin._env_value("K = 'single \\ kept'\n", "K"), "single \\ kept")
        self.assertEqual(self.plugin._env_value("K=first\nOTHER=x\nK=last\n", "K"), "last")
        self.assertEqual(self.plugin._env_value("KK=x\n", "K"), "")

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

        with mock.patch.object(self.plugin, "_atomic_private_write", side_effect=failing_write):
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

    def test_profile_with_only_the_connector_marker_takes_bindings_only(self):
        """커넥터 표식만 있는 사람이 만든 profile 은 조회, 바인딩 설치, 떼기가 되고 옛 설치는 401 이다."""
        self.bind_fixture()
        self.make_profile("human")
        config = yaml.safe_load((self.root / "human/config.yaml").read_text(encoding="utf-8"))
        config.update(
            mcp_servers={"fos-assistant": {"url": "http://control-plane.test/mcp"}},
            platform_toolsets={"api_server": ["web", "fos-assistant"]},
            plugins={"enabled": ["fos-ctx"], "disabled": [], "entries": {"fos-ctx": {"allow_tool_override": False}}})
        self.write_config("human", config)
        (self.root / "human" / self.plugin.CONNECTOR_HOST_MARKER).write_text("", encoding="utf-8")

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

    def test_vault_files_are_written_deleted_and_imported_without_echoing_values(self):
        """보관 파일을 쓰고 지우고 옮긴다. 형식 오류와 필수 칸 누락은 400 이고 응답에 값이 없다."""
        self.bind_fixture()
        directory = self.hermes_root / "connector-vault"
        self.assertEqual(directory.stat().st_mode & 0o777, 0o700)
        stored = directory / "c1.json"
        self.assertEqual(stored.stat().st_mode & 0o777, 0o600)
        self.assertEqual(json.loads(stored.read_text(encoding="utf-8")),
                         {"v": 1, "connector": DEMO, "values": {"token": DEMO_VALUE}})
        # 빈 선택 칸은 넣지 않는다.
        self.assertEqual(self.vault("PUT", vault="c3", connector=DEMO,
                                    values={"token": DEMO_VALUE, "scope": ""}).status_code, 200)
        self.assertEqual(json.loads((directory / "c3.json").read_text())["values"], {"token": DEMO_VALUE})

        written = stored.read_bytes()
        secret = "demo_bad_0123456789"
        for label, body in (
            ("unknown key", {"vault": "c1", "connector": DEMO, "values": {"token": secret, "other": "x"}}),
            ("required missing", {"vault": "c1", "connector": DEMO, "values": {"scope": "a"}}),
            ("required empty", {"vault": "c1", "connector": DEMO, "values": {"token": ""}}),
            ("pattern", {"vault": "c1", "connector": DEMO, "values": {"token": secret + "0"}}),
            ("two lines", {"vault": "c1", "connector": DEMO, "values": {"token": secret, "scope": "a\nb"}}),
            ("not a string", {"vault": "c1", "connector": DEMO, "values": {"token": 1}}),
            ("values not an object", {"vault": "c1", "connector": DEMO, "values": [secret]}),
            ("bad vault name", {"vault": "c0", "connector": DEMO, "values": {"token": secret}}),
            ("path in vault name", {"vault": "../c1", "connector": DEMO, "values": {"token": secret}}),
            ("unknown connector", {"vault": "c1", "connector": "unknown", "values": {"token": secret}}),
            ("extra key", {"vault": "c1", "connector": DEMO, "values": {"token": secret}, "profile": "alice"}),
        ):
            with self.subTest(label):
                response = self.vault("PUT", **body)
                self.assertEqual(response.status_code, 400)
                self.assertNotIn(secret, json.dumps(response.body))
                self.assertEqual(stored.read_bytes(), written)
        # 같은 이름의 보관 파일이 다른 커넥터의 것이면 409 다.
        self.assertEqual(self.vault("PUT", vault="c1", connector=OTHER,
                                    values={"token": OTHER_VALUE}).status_code, 409)
        self.assertEqual(stored.read_bytes(), written)
        self.assertEqual(self.request(VAULT, "PUT", body={"vault": "c1", "connector": DEMO,
                                                          "values": {"token": DEMO_VALUE}}), 401)

        deleted = self.vault("DELETE", vault="c3")
        self.assertEqual((deleted.status_code, deleted.body), (200, {"changed": True}))
        self.assertFalse((directory / "c3.json").exists())
        self.assertEqual(self.vault("DELETE", vault="c3").body, {"changed": False})
        self.assertEqual(self.vault("DELETE", vault="c3", connector=DEMO).status_code, 400)

        # 옛 설치의 관리 profile 에서 칸 값을 옮긴다. 빈 선택 칸은 넣지 않는다.
        self.make_profile("bob")
        self.plugin._apply_template("bob")
        (self.root / "bob/.env").write_text("DEMO_TOKEN=%s\nDEMO_SCOPE=\nOTHER=keep\n" % DEMO_VALUE, encoding="utf-8")
        self.assertEqual(self.connector(profile="bob").status_code, 200)
        imported = self.vault("POST", VAULT_IMPORT, vault="c5", connector=DEMO, profile="bob")
        self.assertEqual((imported.status_code, imported.body), (200, {"ok": True}))
        self.assertEqual(json.loads((directory / "c5.json").read_text()),
                         {"v": 1, "connector": DEMO, "values": {"token": DEMO_VALUE}})
        (self.root / "bob/.env").write_text("DEMO_SCOPE=a\n", encoding="utf-8")
        missing = self.vault("POST", VAULT_IMPORT, vault="c6", connector=DEMO, profile="bob")
        self.assertEqual(missing.status_code, 400)
        self.assertFalse((directory / "c6.json").exists())
        self.assertEqual(self.vault("POST", VAULT_IMPORT, vault="c6", connector=DEMO, profile="owner").status_code, 401)
        self.assertEqual(self.vault("POST", VAULT_IMPORT, vault="c6", connector=OTHER, profile="bob").status_code, 404)
        self.assertEqual(self.vault("POST", VAULT_IMPORT, vault="c6", connector=DEMO, profile="nobody").status_code, 404)

    def test_connector_without_fields_is_bound_with_an_empty_vault(self):
        """칸이 없는 커넥터는 카탈로그에 오르고, 빈 `values` 의 보관 파일로 확인 도구를 부르고 바인딩 설치가 된다."""
        demo, _ = self.bind_fixture()
        declared = json.loads((demo / "connector.json").read_text(encoding="utf-8"))
        declared["fields"] = []
        (demo / "connector.json").write_text(json.dumps(declared), encoding="utf-8")
        mcp = json.loads((demo / ".mcp.json").read_text(encoding="utf-8"))
        mcp["mcpServers"]["demo"]["env"] = {"DEMO_BASE": "${DEMO_BASE}"}
        (demo / ".mcp.json").write_text(json.dumps(mcp), encoding="utf-8")
        entry = next(item for item in self.request("/api/connectors/catalog", "GET", token="valid",
                                                   full_response=True).body if item["id"] == DEMO)
        self.assertEqual((entry["fields"], entry["skills"]), ([], ["demo"]))

        self.assertEqual(self.vault("PUT", vault="c7", connector=DEMO, values={}).status_code, 200)
        seen = []

        async def verify(manifest, tool, env):
            seen.append((tool, env))
            return types.SimpleNamespace(structured_content={"scopes": []}, is_error=False, content=[])

        with mock.patch.object(self.plugin, "_mcp_sdk_problem", return_value=None), \
                mock.patch.object(self.plugin, "_run_connector_tool", verify):
            called = self.request("/api/connectors/%s/call" % DEMO, "POST", token="valid", full_response=True,
                                  body={"tool": "list_scopes", "vault": "c7"})
        self.assertEqual((called.status_code, called.body), (200, {"ok": True, "result": {"scopes": []}}))
        self.assertEqual(seen, [("list_scopes", {"DEMO_BASE": DEMO_BASE,
                                                 "PATH": os.path.dirname(self.connector_command)})])

        installed = self.bind(vault="c7")
        self.assertEqual(installed.status_code, 200, installed.body)
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"], {"DEMO_BASE": DEMO_BASE})
        self.assertFalse((self.root / "alice/.env").exists())
        self.assertEqual(self.status_of()["connectors"][0]["configured"], True)

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
