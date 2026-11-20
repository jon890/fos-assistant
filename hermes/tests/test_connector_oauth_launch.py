"""단추 연결 플러그인의 프로세스가 `token_env` 로 토큰 통로 주소를 받는지 검사한다.

docs/features/connector-oauth/design.md 의 「실행기와 직접 실행」 이 근거다. 실행기는 실제 자식 프로세스로 띄우고,
실제 서버 대신 받은 env 를 파일로 내는 스크립트를 둔다(`test_connector_launcher.py` 와 같은 방식).
"""

import json
import types

from plugin_loading import patch_plugin
from dashboard_profile_api_support import DEMO, DEMO_VALUE
import test_connector_launcher as launcher_tests
from test_connector_vault_encryption import VaultKeyTest

TOKEN_ENV = "DEMO_TOKEN_URL"
TOKEN_URL = "https://assistant.test/internal/connector-token/k1.sig"
PENDING_URL = "https://assistant.test/internal/connector-token/p1.nonce.sig"


class ConnectorOAuthLaunchTest(VaultKeyTest):
    # 실행기 검사의 env 를 파일로 내는 도우미만 빌린다. 그 검사 메서드는 여기서 다시 돌지 않는다.
    env_dump_server = launcher_tests.ConnectorLauncherTest.env_dump_server
    run_definition = launcher_tests.ConnectorLauncherTest.run_definition

    def bound(self, declare=True, oauth=None):
        """key 를 주고 단추 선언을 더한 시험 플러그인을 붙인다. 서버 정의를 돌려준다."""
        self.use_keys()
        self.demo, _ = self.bind_fixture()
        if declare:
            self.declare_oauth(self.demo, oauth=oauth)
        self.store = self.plugin.connector_oauth_store
        response = self.bind()
        self.assertEqual(response.status_code, 200, response.body)
        return self.alice_config()["mcp_servers"]["demo"]

    def write_oauth(self, refresh="ghr_one", token_url=TOKEN_URL, pending=False):
        data = {"provider": "github", "refresh_token": refresh, "granted_scopes": [], "token_url": token_url}
        if pending:
            data["nonce"] = "n1"
        self.store._write_oauth("c1", DEMO, data, pending=pending)

    def child_env(self, definition):
        out = self.env_dump_server()
        finished = self.run_definition(definition)
        self.assertEqual(finished.returncode, 0, finished.stderr)
        return dict(line.split("=", 1) for line in out.read_text(encoding="utf-8").splitlines() if "=" in line)

    def call_with(self, **body):
        """`call` 을 부르고 확인 도구에 넘긴 env 와 응답을 돌려준다."""
        seen = []

        async def verify(manifest, name, env, timeout):
            seen.append(env)
            return types.SimpleNamespace(structured_content={"scopes": []}, is_error=False, content=[])

        with patch_plugin(self.plugin, "_mcp_sdk_problem", return_value=None), \
                patch_plugin(self.plugin, "_run_connector_tool", verify):
            response = self.request("/api/connectors/%s/call" % DEMO, "POST", token="valid", full_response=True,
                                    body={"tool": "list_scopes", **body})
        return response, (seen[0] if seen else None)

    def test_definition_has_the_oauth_argument_and_an_empty_token_env(self):
        """정의 인자에 `@oauth=` 가 있고 정의 env 의 통로 주소 값은 빈 값이다. `config.yaml` 과 소유 기록에 주소가 없다."""
        definition = self.bound()
        args = definition["args"]
        self.assertIn("@oauth=" + TOKEN_ENV, args[:args.index("--")])
        self.assertEqual(definition["env"][TOKEN_ENV], "")
        self.write_oauth()
        self.assertEqual(self.bind().status_code, 200)
        state = (self.root / "alice" / self.plugin.CONNECTOR_STATE).read_text(encoding="utf-8")
        config = (self.root / "alice/config.yaml").read_text(encoding="utf-8")
        self.assertNotIn(TOKEN_URL, state + config + self.env_text())

    def test_launcher_child_gets_the_token_url_only_when_the_oauth_file_exists(self):
        """OAuth 파일이 있으면 실행기 자식 env 의 `token_env` 가 통로 주소이고, 없으면 빈 값이다."""
        definition = self.bound()
        self.assertEqual(self.child_env(definition)[TOKEN_ENV], "")
        self.write_oauth()
        child = self.child_env(definition)
        self.assertEqual((child[TOKEN_ENV], child["DEMO_TOKEN"]), (TOKEN_URL, DEMO_VALUE))

    def test_refresh_token_change_does_not_require_restart(self):
        """OAuth 파일의 refresh token 을 바꾼 뒤 다시 설치해도 `restart_required` 가 아니다."""
        self.bound()
        self.write_oauth()
        self.bind()
        self.write_oauth(refresh="ghr_two")
        repeated = self.bind()
        self.assertEqual({key: repeated.body[key] for key in ("changed", "restart_required")},
                         {"changed": False, "restart_required": False})

    def test_definition_with_the_oauth_argument_is_current(self):
        """`@oauth` 인자가 있는 정의를 낡았다고 보지 않고, 인자가 빠지거나 두 번이면 맞지 않다고 본다."""
        definition = self.bound()
        manifest = self.plugin._connector_manifest(DEMO)
        self.assertTrue(self.plugin._server_matches(manifest, definition))
        args = list(definition["args"])
        without = [arg for arg in args if not arg.startswith("@oauth=")]
        self.assertFalse(self.plugin._server_matches(manifest, {**definition, "args": without}))
        twice = [*args[:4], "@oauth=" + TOKEN_ENV, *args[4:]]
        self.assertFalse(self.plugin._server_matches(manifest, {**definition, "args": twice}))

    def test_direct_execution_env_gets_the_token_url(self):
        """직접 실행과 금융 준비가 쓰는 바인딩 env(`_binding_env`)에 통로 주소가 들어간다."""
        self.bound()
        self.write_oauth()
        manifest = self.plugin._connector_manifest(DEMO)
        state = json.loads((self.root / "alice" / self.plugin.CONNECTOR_STATE).read_text(encoding="utf-8"))
        env = self.plugin.connector_launch._binding_env(self.root / "alice", manifest, state[DEMO])
        self.assertEqual((env[TOKEN_ENV], env["DEMO_TOKEN"]), (TOKEN_URL, DEMO_VALUE))

    def test_wrong_declaration_still_empties_the_token_env(self):
        """`oauth` 선언이 틀려 null 인 플러그인도 정의 env 의 `token_env` 는 빈 값이고 `@oauth` 인자가 없다."""
        definition = self.bound(oauth={"provider": "gitlab", "fields": ["token"], "token_env": TOKEN_ENV})
        self.assertEqual(definition["env"][TOKEN_ENV], "")
        self.assertFalse(any(arg.startswith("@oauth=") for arg in definition["args"]))

    def test_call_rejects_oauth_with_values_or_without_declaration(self):
        """`call` 에 `oauth` 와 `values` 를 함께 주면 400 이다. 선언 없는 플러그인의 `oauth` 도 400 이다."""
        self.bound()
        response, _ = self.call_with(values={"token": DEMO_VALUE}, oauth="current")
        self.assertEqual(response.status_code, 400)
        response, _ = self.call_with(vault="c1", oauth="later")
        self.assertEqual(response.status_code, 400)

    def test_call_without_declaration_rejects_oauth(self):
        """선언 없는 플러그인에 `oauth` 를 주면 400 이다."""
        self.bound(declare=False)
        response, _ = self.call_with(vault="c1", oauth="current")
        self.assertEqual(response.status_code, 400)

    def test_pending_call_uses_pending_files_without_the_current_vault(self):
        """`oauth: "pending"` 은 대기 보관 파일 값과 대기 통로 주소로 돌고, 본 자리 보관 파일이 없어도 된다."""
        self.bound()
        self.write_oauth()
        self.store._begin("c7", DEMO, {"scope": "a"}, "github")
        pending = self.store._read_oauth("c7", pending=True)
        self.store._fill_pending("c7", pending["nonce"], {"refresh_token": "ghr_p", "granted_scopes": [],
                                                          "token_url": PENDING_URL})
        response, env = self.call_with(vault="c7", oauth="pending")
        self.assertEqual(response.status_code, 200, response.body)
        self.assertEqual((env[TOKEN_ENV], env["DEMO_SCOPE"]), (PENDING_URL, "a"))
        self.assertNotIn("DEMO_TOKEN", env)
        response, env = self.call_with(vault="c1", oauth="current")
        self.assertEqual((env[TOKEN_ENV], env["DEMO_TOKEN"]), (TOKEN_URL, DEMO_VALUE))
        response, env = self.call_with(vault="c1")
        self.assertEqual(env[TOKEN_ENV], "")

