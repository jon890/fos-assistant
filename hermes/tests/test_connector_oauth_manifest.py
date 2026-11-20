"""`assistant.json` 의 `oauth` 선언을 카탈로그에 내는 규칙을 검사한다(docs/features/connector-oauth/spec.md REQ1)."""

import json
import logging
import pathlib
import unittest

from test_connector_manifest import ConnectorGateCase
from dashboard_profile_api_support import remote_connector, REMOTE

TOKEN_URL_ENV = "DEMO_TOKEN_URL"
GITHUB = {"provider": "github", "fields": ["token"], "token_env": TOKEN_URL_ENV}


class ConnectorOAuthManifestTest(ConnectorGateCase):
    def declare(self, oauth, env=True):
        """시험 플러그인에 `oauth` 를 선언하고, `env` 가 참이면 `.mcp.json` 에 `token_env` 참조를 더한다."""
        self.rewrite("connector.json", lambda value: value.update(oauth=oauth))
        if env:
            name = oauth.get("token_env") if isinstance(oauth, dict) else None
            self.rewrite(".mcp.json", lambda value: value["mcpServers"]["demo"]["env"].update({name: "${%s}" % name}))

    def warnings(self):
        """카탈로그를 읽고 그때 남은 경고 로그 글을 함께 돌려준다."""
        logging.disable(logging.NOTSET)
        self.addCleanup(logging.disable, logging.CRITICAL)
        with self.assertLogs(self.plugin.logger, level="WARNING") as logs:
            body = self.catalog()
        return body, "\n".join(logs.output)

    def test_github_declaration_is_normalized_in_the_catalog(self):
        """맞는 GitHub 선언이 앱 단위와 설정 화면 주소를 더한 `oauth` 로 나온다."""
        self.declare(GITHUB)
        entry = self.catalog()[0]
        self.assertEqual(entry["oauth"], {
            "provider": "github", "scopes": [], "fields": ["token"], "token_env": TOKEN_URL_ENV,
            "app_scope": "plugin", "settings_url": "https://github.com/settings/apps/authorizations",
            "install_settings_url": "https://github.com/settings/installations"})

    def test_google_declaration_keeps_its_scopes(self):
        """Google 선언은 scope 목록을 그대로 내고 앱 단위가 공유다."""
        scopes = ["https://www.googleapis.com/auth/gmail.modify"]
        self.declare({"provider": "google", "scopes": scopes, "fields": ["token"], "token_env": TOKEN_URL_ENV})
        oauth = self.catalog()[0]["oauth"]
        self.assertEqual((oauth["scopes"], oauth["app_scope"], oauth["install_settings_url"]),
                         (scopes, "shared", None))

    def test_wrong_declaration_drops_only_oauth(self):
        """틀린 선언은 플러그인을 남기고 `oauth` 칸만 빼며 경고 로그를 남긴다."""
        cases = (
            ("unknown provider", {**GITHUB, "provider": "gitlab"}),
            ("google without scopes", {"provider": "google", "scopes": [], "fields": ["token"],
                                       "token_env": TOKEN_URL_ENV}),
            ("github with scopes", {**GITHUB, "scopes": ["repo"]}),
            ("non secret field", {**GITHUB, "fields": ["scope"]}),
            ("empty fields", {**GITHUB, "fields": []}),
            ("unknown key", {**GITHUB, "authorize_url": "https://example.test"}),
        )
        for name, oauth in cases:
            with self.subTest(name):
                self.declare(oauth)
                body, logs = self.warnings()
                self.assertEqual(len(body), 1)
                self.assertNotIn("oauth", body[0])
                self.assertIn("oauth 선언을 쓰지 않는다", logs)

    def test_token_env_taken_by_a_field_drops_only_oauth(self):
        """`token_env` 가 칸 env 와 겹치면 env 합은 맞지만 `oauth` 만 빠진다."""
        self.declare({**GITHUB, "token_env": "DEMO_TOKEN"}, env=False)
        body, logs = self.warnings()
        self.assertEqual(len(body), 1)
        self.assertNotIn("oauth", body[0])
        self.assertIn("oauth 선언을 쓰지 않는다", logs)

    def test_token_env_missing_from_the_server_env_drops_the_plugin(self):
        """`token_env` 가 `.mcp.json` 서버 env 에 없으면 지금처럼 플러그인이 빠진다."""
        self.declare(GITHUB, env=False)
        self.assertEqual(self.catalog(), [])

    def test_remote_plugin_declaration_is_dropped(self):
        """원격 MCP 플러그인의 `oauth` 는 범위 밖이라 칸만 빠진다."""
        root = remote_connector(self.base)
        path = root / "assistant.json"
        value = json.loads(path.read_text(encoding="utf-8"))
        value["oauth"] = {**GITHUB, "fields": [value["fields"][0]["key"]]}
        path.write_text(json.dumps(value), encoding="utf-8")
        self.operator_list({REMOTE: {"root": str(root), "url": "https://mcp.remote.test/mcp", "env": {}}})
        body, logs = self.warnings()
        self.assertEqual([entry["id"] for entry in body], [REMOTE])
        self.assertNotIn("oauth", body[0])
        self.assertIn("원격 MCP", logs)

    def test_plugin_without_oauth_has_no_oauth_entry(self):
        """`oauth` 를 선언하지 않은 플러그인의 카탈로그 항목에는 `oauth` 칸이 없다."""
        self.assertNotIn("oauth", self.catalog()[0])


if __name__ == "__main__":
    unittest.main()
