"""dashboard-profile-api 의 사건 감지 목록 조회(`/events`)와 `event_watch` 선언을 검사한다(ADR-20261105 / event-watch).

시험 플러그인의 사본에 조회 도구 하나를 더해 실제 자식 프로세스로 띄운다.
"""

import json
import sys
import types
import unittest

import test_connector_call as call_base
import test_connector_manifest as base

EVENTS = "/api/connectors/%s/events" % base.DEMO
PROFILE = "alice"
SINCE = 1700000000

# 조회 도구다. 검색식의 `after:` 값을 첫 항목 식별자에 넣어 `{since}` 가 바뀐 것을 보인다.
# 토큰이 `demo_many_` 면 늘 다음 쪽을 내고, `demo_bad_` 면 인증 오류를 낸다.
LIST_MAIL = '''

def mail_result(body):
    return CallToolResult(content=[TextContent(type="text", text=json.dumps(body, ensure_ascii=False))])


@server.tool(annotations=ToolAnnotations(read_only_hint=True), structured_output=False)
async def list_mail(query: str = "", max_results: int = 25, page_token: str = "") -> CallToolResult:
    """사건 감지 검사용 조회 도구다. 제목과 요약을 함께 내지만 대시보드가 돌려주면 안 된다."""
    token = os.environ.get("DEMO_TOKEN", "")
    if token.startswith("demo_bad_"):
        return failure("DEMO_UNAUTHORIZED")
    since = query.rsplit("after:", 1)[-1]
    if token.startswith("demo_many_"):
        return mail_result({"messages": [{"id": "m" + page_token, "subject": "비밀 제목"}],
                            "next_page_token": page_token + "x"})
    if page_token == "":
        return mail_result({"messages": [
            {"id": "m" + since, "date": "Tue, 14 Nov 2023 22:13:20 +0000", "labels": ["INBOX"],
             "subject": "비밀 제목", "snippet": "비밀 요약", "from": "someone@example.com"},
            {"id": "bad id!", "date": "x", "labels": []},
        ], "next_page_token": "p2"})
    return mail_result({"messages": [
        {"id": "promo1", "date": "", "labels": ["INBOX", "CATEGORY_PROMOTIONS"]},
        {"id": "m2", "date": "nope", "labels": "INBOX"},
        {"id": "m" + since, "date": "", "labels": []},
    ], "next_page_token": None})
'''

WATCH = {"tool": "list_mail", "args": {"query": "in:inbox after:{since}", "max_results": 25},
         "items": "messages", "page_token": {"result": "next_page_token", "arg": "page_token"},
         "id": "id", "received_at": "date", "labels": "labels", "skip_labels": ["CATEGORY_PROMOTIONS"],
         "noun": "메모", "link": "https://demo.test/notes/{id}"}


class ConnectorEventsTest(base.ConnectorGateCase):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        profiles = types.ModuleType("hermes_cli.profiles")
        profiles.get_profile_dir = lambda name: cls.profile_root / name
        profiles.profile_exists = lambda name: (cls.profile_root / name).is_dir()
        previous = sys.modules.get("hermes_cli.profiles")

        def restore():
            if previous is None:
                sys.modules.pop("hermes_cli.profiles", None)
            else:
                sys.modules["hermes_cli.profiles"] = previous

        cls.addClassCleanup(restore)
        sys.modules["hermes_cli.profiles"] = profiles

    def setUp(self):
        super().setUp()
        server = self.connector_root / "server.py"
        text = server.read_text(encoding="utf-8")
        marker = '\n\nif __name__ == "__main__":'
        server.write_text(text.replace(marker, LIST_MAIL + marker), encoding="utf-8")
        self.declare(WATCH)
        type(self).profile_root = self.base / "profiles"
        self.profile_root.mkdir()

    def declare(self, watch):
        """시험 플러그인을 `schema: 2` 로 바꾸고 조회 도구와 사건 감지 선언을 더한다."""
        def change(value):
            value.update(schema=2, tools={"list_scopes": {"risk": "READ"}, "list_mail": {"risk": "READ"},
                                          "write_note": {"risk": "WRITE", "outbound": False}})
            value.pop("event_watch", None)
            if watch is not None:
                value["event_watch"] = watch
        self.rewrite("connector.json", change)

    def install(self, name=PROFILE, token=call_base.OK_TOKEN):
        """플러그인을 바인딩으로 설치한 관리 profile 을 만든다."""
        profile = self.profile_root / name
        profile.mkdir()
        (profile / ".env").write_text("DEMO_TOKEN=%s\n" % token, encoding="utf-8")
        (profile / self.plugin.MANAGED_MARKER).write_text("", encoding="utf-8")
        manifest = self.plugin._connector_manifest(base.DEMO)
        entry = {"server": self.plugin._connector_server(manifest), "allowlist_added": True,
                 "mcp_server": manifest["mcp_server"], "mode": "bind", "vault": "c1", "skills": ["demo"]}
        (profile / self.plugin.CONNECTOR_STATE).write_text(json.dumps({base.DEMO: entry}), encoding="utf-8")

    async def send(self, path, method, body=None, token="valid"):
        request = types.SimpleNamespace(
            url=types.SimpleNamespace(path=path), method=method, token=token,
            state=types.SimpleNamespace(), query_params=types.SimpleNamespace(getlist=lambda key: []),
        )

        async def read_json():
            return body

        request.json = read_json

        async def call_next(current):
            return types.SimpleNamespace(status_code=401, body=b"null")

        response = await self.gate(request, call_next)
        return response.status_code, json.loads(response.body)

    def events(self, body=None, **request):
        return self.request(EVENTS, "POST", {"profile": PROFILE, "since": SINCE} if body is None else body, **request)

    def test_returns_only_identifier_received_time_and_labels(self):
        """조회 도구를 쪽마다 불러 식별자, 받은 시각, 라벨만 돌려주고, 뺄 라벨과 틀린 식별자와 같은 식별자는 뺀다(REQ3, DECISION21)."""
        self.install()
        with call_base.collected_logs() as logs:
            status, body = self.events()
        self.assertEqual(status, 200, body)
        self.assertEqual(body, {"ok": True, "truncated": False, "items": [
            {"id": "m%d" % SINCE, "received_at": "2023-11-14T22:13:20Z", "labels": ["INBOX"]},
            {"id": "m2", "received_at": None, "labels": []},
        ]})
        self.assertNotIn("비밀", json.dumps(body, ensure_ascii=False))
        self.assertFalse([line for line in logs if "비밀" in line or call_base.OK_TOKEN in line])

    def test_stops_after_the_page_limit_and_marks_truncated(self):
        """다음 쪽이 끝나지 않으면 쪽 상한까지만 읽고 `truncated` 를 참으로 둔다."""
        self.install(token="demo_many_0123456789")
        status, body = self.events()
        self.assertEqual(status, 200, body)
        self.assertTrue(body["truncated"])
        self.assertEqual([item["id"] for item in body["items"]], ["m", "mx", "mxx", "mxxx"])

    def test_tool_error_becomes_the_declared_word(self):
        """조회 도구의 오류 코드는 `errors` 표의 공통 어휘로만 돌려준다."""
        self.install(token="demo_bad_0123456789")
        self.assertEqual(self.events(), (200, {"ok": False, "error": "credential_rejected"}))

    def test_rejects_requests_outside_the_contract(self):
        """선언이 없거나, 본문이 계약 밖이거나, 설치하지 않은 profile 이거나, 토큰이 없으면 도구를 부르지 않는다."""
        self.install()
        self.assertEqual(self.events({"profile": PROFILE, "since": True})[0], 400)
        self.assertEqual(self.events({"profile": PROFILE, "since": -1})[0], 400)
        self.assertEqual(self.events({"profile": PROFILE, "since": SINCE, "query": "x"})[0], 400)
        self.assertEqual(self.events({"profile": "bob", "since": SINCE})[0], 404)
        self.assertEqual(self.events(token="wrong")[0], 401)
        self.declare(None)
        self.assertEqual(self.events()[0], 404)

    def test_catalog_publishes_only_the_tool_noun_and_link(self):
        """카탈로그는 선언 가운데 조회 도구 이름, 항목 이름, 원문 링크 틀만 싣는다."""
        entry = self.catalog()[0]
        self.assertEqual(entry["event_watch"], {"tool": "list_mail", "noun": "메모", "link": WATCH["link"]})
        self.declare(None)
        self.assertNotIn("event_watch", self.catalog()[0])

    def test_rejects_declarations_that_could_leak_or_write(self):
        """쓰기 도구, 승인이 필요한 도구, 정해 둔 셋 밖의 칸, https 가 아닌 링크를 선언한 플러그인은 카탈로그에서 뺀다."""
        cases = {
            "쓰기 도구": {**WATCH, "tool": "write_note"},
            "없는 도구": {**WATCH, "tool": "nothing"},
            "제목 칸": {**WATCH, "subject": "subject"},
            "다른 자리": {**WATCH, "args": {"query": "{subject}"}},
            "http 링크": {**WATCH, "link": "http://demo.test/{id}"},
            "자리 없는 링크": {**WATCH, "link": "https://demo.test/notes"},
        }
        for name, watch in cases.items():
            with self.subTest(name):
                self.declare(watch)
                self.assertEqual(self.catalog(), [])
        self.declare(WATCH)
        self.rewrite("connector.json", lambda value: value["tools"]["list_mail"].update(approval="required"))
        self.assertEqual(self.catalog(), [])


if __name__ == "__main__":
    unittest.main()
