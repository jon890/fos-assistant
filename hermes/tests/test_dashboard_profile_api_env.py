"""dashboard-profile-api 의 env 분기를 검사한다."""

import json
import os
import pathlib
from unittest import mock
from dashboard_profile_api_support import DEMO, DEMO_BASE, DEMO_VALUE, VAULT_IMPORT
import dashboard_profile_api_support as support


class ProfileApiEnvTest(support.ProfileApiRouteTest):
    def test_installed_connector_is_blocked_when_operator_env_value_changes(self):
        """설치한 뒤 운영 목록의 운영자 env 값이 바뀌면 소유 기록과 달라 상태 조회가 503 이다."""
        root = self.connector_fixture()
        self.assertEqual(self.connector().status_code, 200)
        changed = {DEMO: {"root": str(root), "env": {"DEMO_BASE": DEMO_BASE + "/other"}}}
        with mock.patch.dict(os.environ, {"FOS_ASSISTANT_CONNECTOR_ROOTS": json.dumps(changed)}):
            self.assertEqual(self.connector_status().status_code, 503)
        self.assertEqual(self.connector_status().status_code, 200)

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
