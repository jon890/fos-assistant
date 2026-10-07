"""dashboard-profile-api 의 session 분기를 검사한다."""

import sqlite3
import dashboard_profile_api_support as support


class ProfileApiSessionTest(support.ProfileApiRouteTest):
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
