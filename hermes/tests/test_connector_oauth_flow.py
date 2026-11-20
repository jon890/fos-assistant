"""단추 연결의 대시보드 경로를 처음부터 끝까지 한 번에 돈다(스펙 REQ4, REQ6, REQ9 의 Hermes 쪽).

가짜 GitHub 서버와 단추 선언을 더한 시험 플러그인을 쓴다. 실행기는 실제 자식 프로세스로 띄운다.
"""

import types
import unittest
from unittest import mock

from plugin_loading import patch_plugin
from dashboard_profile_api_support import DEMO, DEMO_VALUE
import test_connector_launcher as launcher_tests
import test_connector_oauth_routes as routes_tests
from test_connector_vault_encryption import VaultKeyTest

TOKEN_ENV = "DEMO_TOKEN_URL"
CURRENT_URL = "https://assistant.test/internal/connector-token/k1.sig"
PENDING_URL = "https://assistant.test/internal/connector-token/p1.nonce.sig"
REDIRECT = "https://assistant.test/api/connections/oauth/callback"
APP = {"client_id": "Iv1.client", "client_secret": "client-secret-value"}


class ConnectorOAuthFlowTest(VaultKeyTest):
    env_dump_server = launcher_tests.ConnectorLauncherTest.env_dump_server
    run_definition = launcher_tests.ConnectorLauncherTest.run_definition

    def setUp(self):
        super().setUp()
        self.use_keys()
        self.demo, _ = self.bind_fixture()
        self.declare_oauth(self.demo)
        self.fake = routes_tests.FakeProvider()
        self.addCleanup(self.fake.close)
        patch = mock.patch.dict(self.plugin.connector_oauth_providers.PROVIDERS["github"],
                                {"token_url": self.fake.base + "/github/token"})
        patch.start()
        self.addCleanup(patch.stop)
        self.directory = self.hermes_root / "connector-vault"

    def oauth(self, action, **body):
        response = self.request("/api/connector-oauth/" + action, "POST", token="valid", full_response=True, body=body)
        self.assertEqual(response.status_code, 200, (action, response.body))
        return response.body

    def verify_env(self, vault):
        """확인 도구를 `oauth: "pending"` 으로 부르고 도구가 받은 env 를 돌려준다."""
        seen = []

        async def verify(manifest, name, env, timeout):
            seen.append(dict(env))
            return types.SimpleNamespace(structured_content={"scopes": []}, is_error=False, content=[])

        with patch_plugin(self.plugin, "_mcp_sdk_problem", return_value=None), \
                patch_plugin(self.plugin, "_run_connector_tool", verify):
            response = self.request("/api/connectors/%s/call" % DEMO, "POST", token="valid", full_response=True,
                                    body={"tool": "list_scopes", "vault": vault, "oauth": "pending"})
        self.assertEqual(response.status_code, 200, response.body)
        return seen[0]

    def button_connect(self, vault, refresh):
        """로그인 주소 → begin → 교환 → 확인 도구 → commit 을 돈다. 확인 도구가 받은 env 를 돌려준다."""
        url = self.oauth("authorize-url", connector=DEMO, mode="authorize", client_id=APP["client_id"],
                         redirect_uri=REDIRECT, state="v1.state", code_challenge="challenge")["url"]
        self.assertIn("code_challenge=challenge", url)
        nonce = self.oauth("begin", vault=vault, connector=DEMO, values={"scope": "a"})["nonce"]
        self.fake.responses[("POST", "/github/token")] = (200, {
            "access_token": "ghu_access", "refresh_token": refresh, "expires_in": 28800})
        exchanged = self.oauth("exchange", vault=vault, connector=DEMO, nonce=nonce, code="code-1",
                               code_verifier="verifier", redirect_uri=REDIRECT, token_url=PENDING_URL, **APP)
        self.assertEqual(exchanged["outcome"], "ok")
        self.assertTrue((self.directory / (vault + ".oauth.pending")).exists())
        env = self.verify_env(vault)
        self.oauth("commit", vault=vault, nonce=nonce, token_url=CURRENT_URL)
        self.assertFalse((self.directory / (vault + ".oauth.pending")).exists())
        self.assertFalse((self.directory / (vault + ".pending")).exists())
        return env

    def child_env(self, definition):
        out = self.env_dump_server()
        finished = self.run_definition(definition)
        self.assertEqual(finished.returncode, 0, finished.stderr)
        return dict(line.split("=", 1) for line in out.read_text(encoding="utf-8").splitlines() if "=" in line)

    def test_button_connection_from_start_to_disconnect(self):
        """확인 도구는 대기 주소, 실행기 자식은 본 주소를 받는다. 붙여 넣기로 바꾸면 OAuth 파일이 사라지고 해제는 모두 지운다."""
        # 처음 연결은 본 자리 보관 파일이 없다.
        verified = self.button_connect("c5", "ghr_first")
        self.assertEqual((verified[TOKEN_ENV], verified["DEMO_SCOPE"]), (PENDING_URL, "a"))
        self.assertNotIn("DEMO_TOKEN", verified)
        self.assertEqual(self.bind(vault="c5").status_code, 200)
        definition = self.alice_config()["mcp_servers"]["demo"]
        child = self.child_env(definition)
        self.assertEqual((child[TOKEN_ENV], child["DEMO_TOKEN"], child["DEMO_SCOPE"]), (CURRENT_URL, "", "a"))

        # 붙여 넣기로 바꾼다. OAuth 파일이 사라지고 실행기 자식의 통로 주소가 빈 값이다.
        response = self.vault("PUT", vault="c5", connector=DEMO, values={"token": DEMO_VALUE})
        self.assertEqual(response.status_code, 200)
        self.assertFalse((self.directory / "c5.oauth").exists())
        self.assertEqual(self.bind(vault="c5").status_code, 200)
        child = self.child_env(self.alice_config()["mcp_servers"]["demo"])
        self.assertEqual((child[TOKEN_ENV], child["DEMO_TOKEN"]), ("", DEMO_VALUE))

        # 단추로 다시 연결한다.
        self.button_connect("c5", "ghr_second")
        self.assertTrue((self.directory / "c5.oauth").exists())

        # 해제한다. 보관 파일, OAuth 파일, 대기 파일이 모두 없다.
        self.oauth("begin", vault="c5", connector=DEMO, values={})
        self.assertEqual(self.vault("DELETE", vault="c5").status_code, 200)
        self.assertEqual(sorted(path.name for path in self.directory.glob("c5*")), [])


if __name__ == "__main__":
    unittest.main()
