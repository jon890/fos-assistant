"""`connector-oauth` 경로가 제공자 표대로 서비스를 부르고 `outcome` 으로 답하는지 검사한다.

가짜 GitHub 과 가짜 Google 을 표준 라이브러리 HTTP 서버로 띄우고 제공자 표의 주소를 그 서버로 바꾼다
(docs/features/connector-oauth/design.md 의 「대시보드 plugin 경로(서비스 토큰)」, 「취소 범위」).
"""

import asyncio
import http.server
import json
import pathlib
import threading
import unittest
import urllib.parse
from unittest import mock

from dashboard_profile_api_support import DEMO, OTHER_VALUE
from test_connector_vault_encryption import VaultKeyTest

CLIENT_ID = "Iv1.client"
CLIENT_SECRET = "client-secret-value"
REFRESH = "ghr_first_refresh_value"
ROTATED = "ghr_rotated_refresh_value"
ACCESS = "ghu_access_value"
TOKEN_URL = "https://assistant.test/internal/connector-token/k1.sig"
REDIRECT = "https://assistant.test/api/connections/oauth/callback"
GOOGLE_SCOPES = ["https://www.googleapis.com/auth/gmail.modify", "https://www.googleapis.com/auth/gmail.settings.basic"]


class FakeProvider:
    """경로마다 정해 둔 응답을 돌려주고 받은 요청을 모은다. GitHub 토큰 경로는 `Accept: application/json` 이 없으면 400 이다."""

    def __init__(self):
        self.responses = {}
        self.requests = []
        self.hold = None
        fake = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def handle_one(self):
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length).decode("utf-8") if length else ""
                path = urllib.parse.urlsplit(self.path).path
                fake.requests.append({"method": self.command, "path": path, "body": raw,
                                      "headers": dict(self.headers.items())})
                if fake.hold is not None and path in fake.hold[0]:
                    fake.hold[1].wait(10)
                if path == "/github/token" and self.headers.get("Accept") != "application/json":
                    status, body = 400, {"error": "not_json"}
                else:
                    status, body = fake.responses.get((self.command, path), (500, {}))
                data = json.dumps(body).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            do_POST = do_DELETE = handle_one

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.base = "http://127.0.0.1:%d" % self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()

    def form(self, index=-1):
        return dict(urllib.parse.parse_qsl(self.requests[index]["body"]))


class ConnectorOAuthRoutesTest(VaultKeyTest):
    def setUp(self):
        super().setUp()
        self.use_keys()
        self.demo, self.other = self.bind_fixture()
        self.declare_oauth(self.demo)
        self.fake = FakeProvider()
        self.addCleanup(self.fake.close)
        providers = self.plugin.connector_oauth_providers.PROVIDERS
        for name, changes in {
            "github": {"token_url": self.fake.base + "/github/token",
                       "revoke_token_url": self.fake.base + "/github/applications/{client_id}/token",
                       "revoke_grant_url": self.fake.base + "/github/applications/{client_id}/grant"},
            "google": {"token_url": self.fake.base + "/google/token",
                       "revoke_url": self.fake.base + "/google/revoke"},
        }.items():
            patch = mock.patch.dict(providers[name], changes)
            patch.start()
            self.addCleanup(patch.stop)
        self.store = self.plugin.connector_oauth_store

    def oauth(self, action, **body):
        response = self.request("/api/connector-oauth/" + action, "POST", token="valid", full_response=True, body=body)
        return response.status_code, response.body

    def use_google(self):
        self.declare_oauth(self.demo, oauth={"provider": "google", "scopes": GOOGLE_SCOPES, "fields": ["token"],
                                             "token_env": "DEMO_TOKEN_URL"})
        # declare_oauth 는 같은 env 를 다시 넣으므로 서버 env 는 그대로다.

    def begin(self, vault="c1"):
        status, body = self.oauth("begin", vault=vault, connector=DEMO, values={})
        self.assertEqual(status, 200)
        return body["nonce"]

    def exchange(self, nonce, vault="c1"):
        return self.oauth("exchange", vault=vault, connector=DEMO, nonce=nonce, client_id=CLIENT_ID,
                          client_secret=CLIENT_SECRET, code="code-1", code_verifier="verifier-1",
                          redirect_uri=REDIRECT, token_url=TOKEN_URL)

    def connected(self, refresh=REFRESH, provider="github"):
        """본 자리 OAuth 파일을 바로 둔다."""
        self.store._write_oauth("c1", DEMO, {"provider": provider, "refresh_token": refresh, "granted_scopes": [],
                                             "token_url": TOKEN_URL})

    def test_authorize_url_puts_challenge_only_where_it_can_come_back(self):
        """GitHub `install` 에는 challenge 가 없고, `authorize` 와 Google 에는 있다. Google 은 오프라인 접근을 청한다."""
        common = {"connector": DEMO, "client_id": CLIENT_ID, "redirect_uri": REDIRECT, "state": "v1.state"}
        status, body = self.oauth("authorize-url", mode="install", app_slug="demo-app", **common)
        self.assertEqual(status, 200)
        url = urllib.parse.urlsplit(body["url"])
        self.assertEqual(url.path, "/apps/demo-app/installations/new")
        self.assertEqual(urllib.parse.parse_qs(url.query), {"state": ["v1.state"]})
        status, body = self.oauth("authorize-url", mode="authorize", code_challenge="challenge-1", **common)
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(body["url"]).query)
        self.assertEqual((query["code_challenge"], query["code_challenge_method"]), (["challenge-1"], ["S256"]))
        self.assertNotIn("access_type", query)
        self.use_google()
        status, body = self.oauth("authorize-url", mode="authorize", code_challenge="challenge-1", **common)
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(body["url"]).query)
        self.assertEqual((query["access_type"], query["prompt"], query["code_challenge"]),
                         (["offline"], ["consent"], ["challenge-1"]))
        self.assertEqual(query["scope"], [" ".join(GOOGLE_SCOPES)])
        self.assertEqual(self.oauth("authorize-url", mode="install", app_slug="x", **common)[0], 400)

    def test_plugin_without_oauth_is_not_found(self):
        """`oauth` 를 선언하지 않은 플러그인은 404 다."""
        status, _ = self.oauth("authorize-url", connector="other-notes", mode="authorize", client_id=CLIENT_ID,
                               redirect_uri=REDIRECT, state="s", code_challenge="c")
        self.assertEqual(status, 404)

    def test_exchange_fills_the_pending_file_without_returning_the_refresh_token(self):
        """교환이 성공하면 대기 OAuth 파일에 refresh token 이 있고 응답에는 없다. PKCE verifier 를 보낸다."""
        self.fake.responses[("POST", "/github/token")] = (200, {
            "access_token": ACCESS, "refresh_token": REFRESH, "expires_in": 28800, "token_type": "bearer"})
        nonce = self.begin()
        status, body = self.exchange(nonce)
        self.assertEqual((status, body["outcome"], body["access_token"]), (200, "ok", ACCESS))
        self.assertIsInstance(body["expires_at"], int)
        self.assertNotIn(REFRESH, json.dumps(body))
        form = self.fake.form()
        self.assertEqual((form["grant_type"], form["code_verifier"], form["client_secret"]),
                         ("authorization_code", "verifier-1", CLIENT_SECRET))
        pending = self.store._read_oauth("c1", pending=True)
        self.assertEqual((pending["refresh_token"], pending["token_url"]), (REFRESH, TOKEN_URL))
        self.assertEqual(self.oauth("commit", vault="c1", nonce=nonce)[0], 200)
        self.assertEqual(self.store._read_oauth("c1")["refresh_token"], REFRESH)

    def test_exchange_without_refresh_token_is_an_app_problem(self):
        """refresh token 없는 응답은 `app_rejected` 이고 대기 OAuth 파일에 토큰이 없다."""
        self.fake.responses[("POST", "/github/token")] = (200, {"access_token": ACCESS, "token_type": "bearer"})
        nonce = self.begin()
        self.assertEqual(self.exchange(nonce)[1], {"outcome": "app_rejected"})
        self.assertEqual(self.store._read_oauth("c1", pending=True)["refresh_token"], "")

    def test_exchange_with_partial_google_scopes_is_denied(self):
        """Google 이 선언 scope 를 다 주지 않으면 `scope_denied` 이고 대기 OAuth 파일에 토큰이 없다."""
        self.use_google()
        self.fake.responses[("POST", "/google/token")] = (200, {
            "access_token": ACCESS, "refresh_token": REFRESH, "expires_in": 3599, "scope": GOOGLE_SCOPES[0]})
        nonce = self.begin()
        self.assertEqual(self.exchange(nonce)[1], {"outcome": "scope_denied"})
        self.assertEqual(self.store._read_oauth("c1", pending=True)["refresh_token"], "")

    def test_exchange_with_other_nonce_is_a_conflict(self):
        """다른 흐름의 nonce 로 교환하면 서비스를 부르지 않고 409 다."""
        self.begin()
        self.assertEqual(self.exchange("other-nonce")[0], 409)
        self.assertEqual(self.fake.requests, [])

    def test_refresh_writes_the_rotated_token_and_sorts_errors(self):
        """GitHub 이 새 refresh token 을 주면 OAuth 파일이 바뀐다. 오류 낱말은 표대로 나뉜다."""
        self.connected()
        refresh = {"vault": "c1", "connector": DEMO, "client_id": CLIENT_ID, "client_secret": CLIENT_SECRET}
        self.fake.responses[("POST", "/github/token")] = (200, {
            "access_token": ACCESS, "refresh_token": ROTATED, "expires_in": 28800})
        status, body = self.oauth("refresh", **refresh)
        self.assertEqual((status, body["outcome"], body["access_token"]), (200, "ok", ACCESS))
        self.assertEqual(self.fake.form()["refresh_token"], REFRESH)
        self.assertEqual(self.store._read_oauth("c1")["refresh_token"], ROTATED)
        for error, outcome in (("bad_refresh_token", "grant_revoked"),
                               ("incorrect_client_credentials", "app_rejected"), ("other_word", "unavailable")):
            self.fake.responses[("POST", "/github/token")] = (200, {"error": error})
            self.assertEqual(self.oauth("refresh", **refresh)[1], {"outcome": outcome})
        self.fake.responses[("POST", "/github/token")] = (503, {})
        self.assertEqual(self.oauth("refresh", **refresh)[1], {"outcome": "unavailable"})
        self.use_google()
        self.connected(provider="google")
        for error, outcome in (("invalid_grant", "grant_revoked"), ("invalid_client", "app_rejected")):
            self.fake.responses[("POST", "/google/token")] = (400, {"error": error})
            self.assertEqual(self.oauth("refresh", **refresh)[1], {"outcome": outcome})

    def test_refresh_without_an_oauth_file_is_missing(self):
        """갱신할 OAuth 파일이 없으면 `missing` 이다."""
        status, body = self.oauth("refresh", vault="c1", connector=DEMO, client_id=CLIENT_ID,
                                  client_secret=CLIENT_SECRET)
        self.assertEqual((status, body), (200, {"outcome": "missing"}))

    def test_refresh_that_cannot_store_the_rotated_token_gives_no_access_token(self):
        """바뀐 refresh token 을 쓰지 못하면 `unavailable` 이고 access token 이 없다."""
        self.connected()
        self.fake.responses[("POST", "/github/token")] = (200, {
            "access_token": ACCESS, "refresh_token": ROTATED, "expires_in": 28800})
        connector_oauth = self.plugin.connector_oauth
        with mock.patch.object(connector_oauth, "_write_oauth", side_effect=OSError("disk full")):
            status, body = self.oauth("refresh", vault="c1", connector=DEMO, client_id=CLIENT_ID,
                                      client_secret=CLIENT_SECRET)
        self.assertEqual(body, {"outcome": "unavailable"})

    def test_github_revoke_refreshes_first_when_no_access_token(self):
        """GitHub 해제는 access token 이 없으면 갱신부터 하고 허락 전체를 기본 인증으로 취소한다. 파일은 남는다."""
        self.connected()
        self.fake.responses[("POST", "/github/token")] = (200, {"access_token": ACCESS, "refresh_token": ROTATED})
        self.fake.responses[("DELETE", "/github/applications/Iv1.client/grant")] = (204, {})
        status, body = self.oauth("revoke", vault="c1", connector=DEMO, client_id=CLIENT_ID,
                                  client_secret=CLIENT_SECRET)
        self.assertEqual(body, {"outcome": "ok"})
        self.assertEqual([request["path"] for request in self.fake.requests],
                         ["/github/token", "/github/applications/Iv1.client/grant"])
        grant = self.fake.requests[-1]
        self.assertEqual(json.loads(grant["body"]), {"access_token": ACCESS})
        self.assertTrue(grant["headers"]["Authorization"].startswith("Basic "))
        self.assertTrue((self.hermes_root / "connector-vault/c1.oauth").exists())

    def test_google_revoke_sends_the_refresh_token(self):
        """Google 해제는 refresh token 으로 취소 주소를 부른다."""
        self.use_google()
        self.connected(provider="google")
        self.fake.responses[("POST", "/google/revoke")] = (200, {})
        status, body = self.oauth("revoke", vault="c1", connector=DEMO, client_id=CLIENT_ID,
                                  client_secret=CLIENT_SECRET)
        self.assertEqual(body, {"outcome": "ok"})
        self.assertEqual(self.fake.form(), {"token": REFRESH})

    def test_discard_revokes_one_github_token_and_never_calls_google(self):
        """GitHub discard 는 그 access token 하나만 취소하고, Google discard 는 서비스를 부르지 않는다. 대기 파일은 지운다."""
        self.fake.responses[("DELETE", "/github/applications/Iv1.client/token")] = (204, {})
        nonce = self.begin()
        body = {"vault": "c1", "connector": DEMO, "client_id": CLIENT_ID, "client_secret": CLIENT_SECRET}
        self.assertEqual(self.oauth("discard", nonce=nonce, access_token=ACCESS, **body)[0], 200)
        self.assertEqual([request["path"] for request in self.fake.requests], ["/github/applications/Iv1.client/token"])
        self.assertFalse((self.hermes_root / "connector-vault/c1.oauth.pending").exists())
        self.use_google()
        nonce = self.begin()
        self.assertEqual(self.oauth("discard", nonce=nonce, access_token=ACCESS, **body)[0], 200)
        self.assertEqual(len(self.fake.requests), 1)

    def test_token_url_replaces_only_the_channel_address(self):
        """`token-url` 은 OAuth 파일의 통로 주소만 바꾼다."""
        self.connected()
        self.assertEqual(self.oauth("token-url", vault="c1", token_url=TOKEN_URL + "2")[1], {"outcome": "ok"})
        stored = self.store._read_oauth("c1")
        self.assertEqual((stored["token_url"], stored["refresh_token"]), (TOKEN_URL + "2", REFRESH))

    def test_slow_refresh_does_not_block_other_vault_writes(self):
        """갱신이 가짜 서비스에서 멈춘 동안 다른 보관 파일 `PUT` 이 끝난다."""
        self.connected()
        release = threading.Event()
        self.fake.hold = ({"/github/token"}, release)
        self.fake.responses[("POST", "/github/token")] = (200, {"access_token": ACCESS})
        refresh = {"vault": "c1", "connector": DEMO, "client_id": CLIENT_ID, "client_secret": CLIENT_SECRET}

        async def run():
            slow = asyncio.ensure_future(self.send("/api/connector-oauth/refresh", "POST", token="valid", body=refresh))
            await asyncio.sleep(0.3)
            put = await asyncio.wait_for(self.send("/api/connector-vault", "PUT", token="valid", body={
                "vault": "c2", "connector": "other-notes", "values": {"token": OTHER_VALUE, "scope": "a"}}),
                timeout=5)
            done_before_release = slow.done()
            release.set()
            return put, done_before_release, await slow

        put, done_before_release, slow = asyncio.run(run())
        self.assertEqual(put.status_code, 200)
        self.assertFalse(done_before_release)
        self.assertEqual(slow.body["outcome"], "ok")

    def test_begin_refuses_button_field_values(self):
        """begin 은 단추로 채우는 칸 값을 받지 않는다. 받으면 단추 연결의 보관 파일에 붙여 넣은 토큰이 남는다."""
        status, _ = self.oauth("begin", vault="c1", connector=DEMO, values={"token": "demo_ok_1111111111"})
        self.assertEqual(status, 400)
        self.assertFalse((self.hermes_root / "connector-vault/c1.pending").exists())

    def test_shared_fixtures_match_the_routes(self):
        """Control Plane 테스트가 읽을 예시 JSON 이 지금 경로의 본문과 응답 모양과 같다."""
        fixtures = pathlib.Path(__file__).resolve().parent / "fixtures/connector-oauth"
        read = lambda name: json.loads((fixtures / name).read_text(encoding="utf-8"))
        request = read("exchange-request.json")
        required, optional = self.plugin.connector_oauth.BODIES["exchange"]
        self.assertTrue(required <= set(request) <= required | optional)
        self.assertIsNone(self.plugin.connector_oauth._body_problem("exchange", request))
        self.fake.responses[("POST", "/github/token")] = (200, {
            "access_token": ACCESS, "refresh_token": REFRESH, "expires_in": 28800})
        nonce = self.begin()
        exchanged = self.exchange(nonce)[1]
        self.assertEqual(set(read("exchange-response.json")), set(exchanged))
        refreshed = self.oauth("refresh", vault="c1", connector=DEMO, client_id=CLIENT_ID,
                               client_secret=CLIENT_SECRET)[1]
        self.assertEqual(read("refresh-missing-response.json"), refreshed)
        self.oauth("commit", vault="c1", nonce=nonce)
        refreshed = self.oauth("refresh", vault="c1", connector=DEMO, client_id=CLIENT_ID,
                               client_secret=CLIENT_SECRET)[1]
        self.assertEqual(set(read("refresh-response.json")), set(refreshed))

    def test_routes_need_the_service_token(self):
        """Control Plane 토큰 없이 부르면 401 이다."""
        response = self.request("/api/connector-oauth/refresh", "POST", token=None, full_response=True,
                                body={"vault": "c1", "connector": DEMO, "client_id": CLIENT_ID,
                                      "client_secret": CLIENT_SECRET})
        self.assertEqual(response.status_code, 401)

    def test_logs_hold_no_secret_or_token(self):
        """로그에 client secret, refresh token, access token 이 없다."""
        self.fake.responses[("POST", "/github/token")] = (200, {
            "access_token": ACCESS, "refresh_token": ROTATED, "expires_in": 28800})
        with self.captured() as logs:
            nonce = self.begin()
            self.exchange(nonce)
            self.oauth("commit", vault="c1", nonce=nonce)
            self.oauth("refresh", vault="c1", connector=DEMO, client_id=CLIENT_ID, client_secret=CLIENT_SECRET)
        text = "\n".join(logs)
        self.assertIn("connector-oauth exchange", text)
        for secret in (CLIENT_SECRET, REFRESH, ROTATED, ACCESS, TOKEN_URL):
            self.assertNotIn(secret, text)


if __name__ == "__main__":
    unittest.main()
