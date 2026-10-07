"""dashboard-profile-api 의 routes 분기를 검사한다."""


import dashboard_profile_api_support as support


class ProfileApiRoutesTest(support.ProfileApiRouteTest):
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
