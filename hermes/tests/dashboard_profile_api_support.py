"""Hermes 설치 없이 dashboard-profile-api 의 미들웨어 분기를 검사한다."""

import asyncio
import contextlib
import hashlib
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

from plugin_loading import load_plugin, patch_plugin, set_plugin


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
    if "no_mcp" in enabled:
        enabled.remove("no_mcp")
    elif not (enabled & mcp_names):
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
                             "vision", "image_gen", "video_gen", "hermes-api-server")}
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
        cls.plugin = load_plugin("profile_api_route_test", bundle / "__init__.py", cls.addClassCleanup)
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
        set_plugin(self.plugin, "PROFILE_WRITE_LOCK", asyncio.Lock())
        set_plugin(self.plugin, "TEMPLATE_PATH", self.template)
        set_plugin(self.plugin, "PROFILE_PLUGIN_DIR", self.profile_plugins)
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
        self.sandbox_root = base / "sandbox"
        self.sandbox_root.mkdir()
        self.attachment_root = base / "attachments"
        self.attachment_root.mkdir()
        self.attachment_agent_root = str(base / "agent-attachments")
        pathlib.Path(self.attachment_agent_root).mkdir()
        # Control Plane 이 Hermes 를 부르기 전에 주인의 첨부 디렉터리를 만든다. plugin 은 만들지 않는다(ADR-091).
        for owner in ("user-1", "user-2", "user-a", "user-b"):
            self.prepare_attachment_directory(owner)
        # 커넥터 검사도 환경 변수를 바꿔 끼운다. 되돌리는 순서가 엇갈리지 않게 같은 방식으로 건다.
        skill_env = mock.patch.dict(os.environ, {"FOS_ASSISTANT_SKILL_AGENT_ROOT": str(self.skill_root),
                                                 "FOS_ASSISTANT_SANDBOX": json.dumps(self.sandbox_policy())})
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

    def prepare_attachment_directory(self, owner):
        """Control Plane 이 하는 것처럼 양쪽 첨부 루트에 주인의 사용자 디렉터리를 만든다."""
        key = hashlib.sha256(owner.encode("utf-8")).hexdigest()
        for root in (self.attachment_root, pathlib.Path(self.attachment_agent_root)):
            (root / "users" / key).mkdir(parents=True, exist_ok=True)

    def make_profile(self, name):
        # clone 없이 만든 profile 처럼 model 블록만 둔다.
        (self.root / name).mkdir()
        (self.root / name / "config.yaml").write_text(
            yaml.safe_dump({"model": {"default": "gpt-5.6-sol", "provider": "openai-codex"}}),
            encoding="utf-8",
        )

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
            "profile": "alice", "plugin": DEMO, "enabled": enabled, "sandbox_owner": "user-1", **overrides})

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

    def assert_profile_entrypoint_is_written_last(self, install):
        """단일 파일 plugin 을 갱신할 때 진입점 교체 전에 하위 모듈이 모두 있어야 한다."""
        installed = self.root / "alice/plugins/fos-ctx"
        modules = {module.name for module in (self.profile_plugins / "fos-ctx").glob("*.py")}
        modules.remove("__init__.py")
        for name in modules:
            (installed / name).unlink()
        (installed / "__init__.py").write_bytes(b"# previous plugin\n")
        original_write = self.plugin._atomic_private_write
        written = []

        def write(target, value):
            if target.parent == installed:
                if target.name == "__init__.py":
                    self.assertTrue(all((installed / name).is_file() for name in modules))
                written.append(target.name)
            return original_write(target, value)

        with patch_plugin(self.plugin, "_atomic_private_write", side_effect=write):
            response = install()
        self.assertEqual(response.status_code, 200)
        self.assertIs(response.body["plugin_updated"], True)
        self.assertEqual(written[-1], "__init__.py")

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

    def bind(self, plugin=DEMO, vault="c1", profile="alice", enabled=True, owner=None):
        body = {"profile": profile, "plugin": plugin, "enabled": enabled}
        if enabled:
            body["bind"] = {"vault": vault}
        if owner is not None:
            body["sandbox_owner"] = owner
        return self.request("/api/connectors", "PUT", token="valid", full_response=True, body=body)

    def declare_owner_attachments(self, connector, server="demo", name="DEMO_ATTACHMENT_DIR"):
        """시험 커넥터 사본이 주인의 첨부 디렉터리를 받는 env 를 선언하게 한다(ADR-20261007 connector-owner-attachments)."""
        declared = json.loads((connector / "connector.json").read_text(encoding="utf-8"))
        declared["owner_attachments_env"] = name
        (connector / "connector.json").write_text(json.dumps(declared), encoding="utf-8")
        mcp = json.loads((connector / ".mcp.json").read_text(encoding="utf-8"))
        mcp["mcpServers"][server]["env"][name] = "${%s}" % name
        (connector / ".mcp.json").write_text(json.dumps(mcp), encoding="utf-8")

    def owner_attachments(self, owner):
        """정책의 `attachment_agent_root` 아래 그 주인의 첨부 디렉터리다."""
        return "%s/users/%s" % (self.attachment_agent_root, hashlib.sha256(owner.encode("utf-8")).hexdigest())

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

    def host_profile(self):
        """커넥터 표식만 있는 사람이 만든 profile `human` 을 만든다. Control Plane MCP 와 정책 hook 이 켜져 있다."""
        self.make_profile("human")
        config = yaml.safe_load((self.root / "human/config.yaml").read_text(encoding="utf-8"))
        config.update(
            mcp_servers={"fos-assistant": {"url": "http://control-plane.test/mcp"}},
            platform_toolsets={"api_server": ["web", "fos-assistant"]},
            plugins={"enabled": ["fos-ctx"], "disabled": [], "entries": {"fos-ctx": {"allow_tool_override": False}}})
        self.write_config("human", config)
        (self.root / "human" / self.plugin.CONNECTOR_HOST_MARKER).write_text("", encoding="utf-8")

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

    def assert_rolled_back(self, status, deleted=("alice",)):
        self.assertEqual(status, 500)
        self.assertEqual(self.deleted, list(deleted))
        self.assertEqual(sorted(p.name for p in self.root.iterdir()), ["owner"])
        self.assertEqual(self.reloads, [])

    def env_body(self, **overrides):
        body = {"profile": "owner", "key": "MCP_FOS_ASSISTANT_API_KEY", "value": "token-value"}
        body.update(overrides)
        return body

    def toolset_body(self):
        return {
            "profile": "owner",
            "config": {
                "platform_toolsets": {"api_server": ["delegation", "web", "fos-assistant"]},
            },
        }

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
        body["sandbox_owner"] = "user-1"
        return body

    def sandbox_policy(self, **changed):
        """검사용 실행 공간 설정이다. 경로와 이름은 모두 임시 값이다."""
        policy = {
            "image": "sandbox-image:test",
            "workspace_root": str(self.sandbox_root),
            "attachment_root": str(self.attachment_root),
            "attachment_agent_root": self.attachment_agent_root,
            "network": "sandbox-net",
            "cpu": 2,
            "memory_mb": 2048,
            "read_only_mounts": ["/srv/shared:/opt/shared"],
            "profiles": {
                "owner": {"read_only_mounts": ["/srv/owner-skills:/opt/owner-skills"]},
                "alice": {"read_only_mounts": ["/srv/alice-skills:/opt/alice-skills"]},
                "blog": {},
            },
        }
        policy.update(changed)
        return {key: value for key, value in policy.items() if value is not None}

    def set_sandbox_policy(self, policy):
        os.environ["FOS_ASSISTANT_SANDBOX"] = policy if isinstance(policy, str) else json.dumps(policy)

    def file_body(self, owner="user-1"):
        body = self.toolset_body()
        body["config"]["platform_toolsets"]["api_server"] = ["delegation", "file", "fos-assistant"]
        if owner is not None:
            body["sandbox_owner"] = owner
        return body

    def expected_terminal(self, owner, mounts, extra_args=("--network=sandbox-net",), cpu=2, memory=2048,
                          profile="owner"):
        """`hermes/README.md` 의 「셸 실행 공간」 YAML 을 그대로 옮긴 기대값이다.

        `docker_shared_container_key` 는 그 칸을 뺀 나머지를 키 정렬 JSON 으로 만든 sha256 앞 12자를 붙인다.
        """
        terminal = {
            "backend": "docker",
            "cwd": "/workspace",
            "docker_image": "sandbox-image:test",
            "container_persistent": True,
            "docker_persist_across_processes": True,
            "docker_orphan_reaper": True,
            "docker_mount_cwd_to_workspace": False,
            "docker_run_as_host_user": False,
            "docker_network": True,
            "docker_extra_args": list(extra_args) + ["--label=fos-sandbox-profile=%s" % profile],
            "docker_volumes": [
                "%s/%s:/workspace" % (self.sandbox_root, owner),
                "%s/users/%s:%s/users/%s:ro" % (
                    self.attachment_root,
                    hashlib.sha256(owner.encode("utf-8")).hexdigest(),
                    self.attachment_agent_root,
                    hashlib.sha256(owner.encode("utf-8")).hexdigest(),
                ),
            ] + [m + ":ro" for m in mounts],
            "docker_forward_env": [],
            "env_passthrough": [],
            "credential_files": [],
            "container_cpu": cpu,
            "container_memory": memory,
        }
        fingerprint = hashlib.sha256(json.dumps(terminal, sort_keys=True).encode("utf-8")).hexdigest()[:12]
        terminal["docker_shared_container_key"] = "%s-%s-%s" % (profile, owner, fingerprint)
        return terminal

    def save_sandbox_key(self, profile="owner", owner="user-1"):
        """셸 도구를 켜는 저장을 보내고 저장된 컨테이너 키를 돌려준다."""
        body = self.file_body(owner=owner)
        body["profile"] = profile
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 200)
        saved = yaml.safe_load((self.root / profile / "config.yaml").read_text(encoding="utf-8"))
        return saved["terminal"]["docker_shared_container_key"]

    @staticmethod
    def hermes_save_shape(value):
        """Hermes 의 config 저장이 지우는 빈 dict 를 뺀다. 빈 dict 는 보존할 잎 경로가 아니고 기본값 `{}` 과 같다."""
        if not isinstance(value, dict):
            return value
        return {k: ProfileApiRouteTest.hermes_save_shape(v) for k, v in value.items()
                if not (isinstance(v, dict) and not v)}

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
