"""OAuth 파일과 대기 파일, 보관 파일 잠금을 검사한다(docs/features/connector-oauth/design.md 의 「OAuth 파일」)."""

import asyncio
import os
import time
import unittest
from unittest import mock

from dashboard_profile_api_support import DEMO
from test_connector_vault_encryption import VaultKeyTest

REFRESH = "ghr_refresh_secret_value"
TOKEN_URL = "http://control-plane.test/internal/connector-token/k1.sig"


class ConnectorOAuthStoreTest(VaultKeyTest):
    def setUp(self):
        super().setUp()
        self.demo, _ = self.bind_fixture()
        self.declare_oauth(self.demo)
        self.store = self.plugin.connector_oauth_store

    def directory(self):
        return self.hermes_root / "connector-vault"

    def connect(self, vault="c1", values=None, refresh=REFRESH):
        """begin → fill → commit 을 한 번 돈다."""
        nonce = self.store._begin(vault, DEMO, values if values is not None else {}, "github")
        self.store._fill_pending(vault, nonce, {"refresh_token": refresh, "granted_scopes": [],
                                                "token_url": TOKEN_URL})
        self.store._commit(vault, nonce, TOKEN_URL)

    def test_commit_moves_pending_files_and_keeps_the_vault_revision(self):
        """commit 뒤 OAuth 파일이 있고 대기 파일 둘이 없다. 같은 칸 값이면 보관 파일의 inode·수정 시각이 그대로다."""
        self.vault("PUT", vault="c1", connector=DEMO, values={}, oauth=True)
        before = os.stat(self.directory() / "c1.json")
        self.connect()
        after = os.stat(self.directory() / "c1.json")
        self.assertEqual((before.st_ino, before.st_mtime_ns), (after.st_ino, after.st_mtime_ns))
        self.assertTrue((self.directory() / "c1.oauth").exists())
        self.assertFalse((self.directory() / "c1.oauth.pending").exists())
        self.assertFalse((self.directory() / "c1.pending").exists())
        stored = self.store._read_oauth("c1")
        self.assertEqual((stored["refresh_token"], stored["token_url"]), (REFRESH, TOKEN_URL))
        self.assertNotIn("nonce", stored)

    def test_other_nonce_cannot_fill_or_commit(self):
        """다른 nonce 의 fill 과 commit 은 `NonceMismatch` 이고 본 자리 파일은 그대로다."""
        self.connect()
        before = (self.directory() / "c1.oauth").read_bytes()
        self.store._begin("c1", DEMO, {}, "github")
        with self.assertRaises(self.store.NonceMismatch):
            self.store._fill_pending("c1", "wrong", {"refresh_token": "x", "granted_scopes": [], "token_url": ""})
        with self.assertRaises(self.store.NonceMismatch):
            self.store._commit("c1", "wrong", TOKEN_URL)
        with self.assertRaises(self.store.NonceMismatch):
            self.store._discard("c1", "wrong")
        self.assertEqual((self.directory() / "c1.oauth").read_bytes(), before)

    def test_commit_without_a_token_is_refused(self):
        """교환이 채우지 않은 대기 파일은 commit 하지 않는다."""
        nonce = self.store._begin("c1", DEMO, {}, "github")
        with self.assertRaises(self.store.NonceMismatch):
            self.store._commit("c1", nonce, TOKEN_URL)

    def test_failed_oauth_write_keeps_the_pasted_values(self):
        """붙여 넣기 연결을 단추로 바꾸는 commit 에서 OAuth 파일 쓰기가 실패하면 보관 파일의 칸 값이 그대로다."""
        before = (self.directory() / "c1.json").read_bytes()
        nonce = self.store._begin("c1", DEMO, {}, "github")
        self.store._fill_pending("c1", nonce, {"refresh_token": REFRESH, "granted_scopes": [], "token_url": TOKEN_URL})
        with mock.patch.object(self.store, "_durable_private_write", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                self.store._commit("c1", nonce, TOKEN_URL)
        self.assertEqual((self.directory() / "c1.json").read_bytes(), before)
        self.assertFalse((self.directory() / "c1.oauth").exists())

    def test_failed_vault_write_restores_the_previous_oauth_file(self):
        """보관 파일 쓰기가 실패하면 OAuth 파일이 앞 상태(없음, 또는 앞 내용)로 돌아간다."""
        nonce = self.store._begin("c1", DEMO, {}, "github")
        self.store._fill_pending("c1", nonce, {"refresh_token": REFRESH, "granted_scopes": [], "token_url": TOKEN_URL})
        with mock.patch.object(self.store, "_write_vault", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                self.store._commit("c1", nonce, TOKEN_URL)
        self.assertFalse((self.directory() / "c1.oauth").exists())
        self.connect()
        before = (self.directory() / "c1.oauth").read_bytes()
        nonce = self.store._begin("c1", DEMO, {}, "github")
        self.store._fill_pending("c1", nonce, {"refresh_token": "other", "granted_scopes": [], "token_url": TOKEN_URL})
        with mock.patch.object(self.store, "_write_vault", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                self.store._commit("c1", nonce, TOKEN_URL)
        self.assertEqual((self.directory() / "c1.oauth").read_bytes(), before)

    def test_vault_delete_removes_oauth_and_pending_files(self):
        """보관 파일 `DELETE` 가 OAuth 파일과 대기 파일까지 지운다."""
        self.connect()
        self.store._begin("c1", DEMO, {}, "github")
        response = self.vault("DELETE", vault="c1")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(sorted(path.name for path in self.directory().glob("c1*")), [])

    def test_oauth_put_skips_button_fields_and_paste_put_drops_the_oauth_file(self):
        """`oauth: true` 인 `PUT` 은 단추 칸이 비어도 받는다. `oauth` 없는 `PUT` 은 지금처럼 거절하고, 받으면 OAuth 파일을 지운다."""
        self.assertEqual(self.vault("PUT", vault="c3", connector=DEMO, values={}, oauth=True).status_code, 200)
        self.assertEqual(self.vault("PUT", vault="c3", connector=DEMO, values={}).status_code, 400)
        self.connect(vault="c3")
        self.assertEqual(self.vault("PUT", vault="c3", connector=DEMO, values={}, oauth=True).status_code, 200)
        self.assertTrue((self.directory() / "c3.oauth").exists())
        self.assertEqual(self.put(vault="c3").status_code, 200)
        self.assertFalse((self.directory() / "c3.oauth").exists())

    def test_oauth_put_needs_an_oauth_declaration(self):
        """단추 선언이 없는 플러그인에 `oauth: true` 를 주면 거절한다. `oauth` 는 boolean 만 받는다."""
        self.assertEqual(self.vault("PUT", vault="c2", connector="other-notes", values={"token": "x"},
                                    oauth=True).status_code, 400)
        self.assertEqual(self.vault("PUT", vault="c3", connector=DEMO, values={}, oauth="yes").status_code, 400)

    def test_sweep_removes_only_old_pending_files(self):
        """10분 지난 대기 파일만 지운다."""
        self.store._begin("c1", DEMO, {}, "github")
        self.store._begin("c2", "other-notes", {}, "github")
        old = time.time() - 700
        for name in ("c1.pending", "c1.oauth.pending"):
            os.utime(self.directory() / name, (old, old))
        self.assertEqual(self.store._sweep_pending(), 2)
        self.assertEqual(sorted(path.name for path in self.directory().glob("*.pending")),
                         ["c2.oauth.pending", "c2.pending"])

    def test_key_change_rewrites_oauth_and_pending_files(self):
        """key 를 바꿔 기동하면 OAuth 파일과 대기 파일도 새 key 로 다시 쓰이고 옛 key 파일 수에 들어간다."""
        self.use_keys(active="old")
        self.connect()
        self.store._begin("c1", DEMO, {}, "github")
        self.use_keys(active="new")
        with self.captured():
            counts = self.plugin._migrate_vault_files()
        # c2 의 보관 파일은 key 없이 쓴 v1 이다. commit 이 옛 key 로 다시 쓴 c1 보관 파일과 OAuth 파일, 대기 파일 둘이 옛 key 다.
        self.assertEqual((counts["from_old_key"], counts["from_plain"]), (4, 1))
        for name in ("c1.oauth", "c1.oauth.pending", "c1.pending"):
            self.assertIn('"key_id": "new"', (self.directory() / name).read_text(encoding="utf-8"))
        self.assertEqual(self.store._read_oauth("c1")["refresh_token"], REFRESH)

    def test_refresh_token_is_not_stored_in_plain_text(self):
        """key 를 준 경우 OAuth 파일 원문에 refresh token 평문이 없다."""
        self.use_keys()
        self.connect()
        for name in ("c1.oauth",):
            self.assertNotIn(REFRESH, (self.directory() / name).read_text(encoding="utf-8"))
        self.assertNotIn(TOKEN_URL, (self.directory() / "c1.oauth").read_text(encoding="utf-8"))

    def test_vault_put_and_binding_install_together_do_not_deadlock(self):
        """같은 보관 파일의 `PUT` 과 바인딩 설치를 동시에 보내도 둘 다 끝난다."""
        put_body = {"vault": "c1", "connector": DEMO, "values": {"token": "demo_ok_1111111111"}, "oauth": True}
        bind_body = {"profile": "alice", "plugin": DEMO, "enabled": True, "bind": {"vault": "c1"}}

        async def both():
            first = self.send("/api/connector-vault", "PUT", token="valid", body=put_body)
            second = self.send("/api/connectors", "PUT", token="valid", body=bind_body)
            return await asyncio.wait_for(asyncio.gather(first, second), timeout=30)

        results = asyncio.run(both())
        self.assertEqual([result.status_code for result in results], [200, 200])


if __name__ == "__main__":
    unittest.main()
