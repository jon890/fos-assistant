"""fos-ctx 가 웹 검색과 페이지 읽기의 결과에서 주소를 뽑아 Control Plane 에 알리는지 본다.

계약은 fos-assistant `docs/adr/ADR-20261121-found-link-image-source.md` 와 `hermes/plugins/fos-ctx/README.md` 의 「찾은 주소를 알린다」 다.
두 도구의 결과 모양은 `test_found_links_contract.py` 가 Hermes 소스에서 본다.
"""

import http.server
import json
import os
import sys
import threading

import test_fos_ctx as base


SEARCH = json.dumps({"success": True, "data": {"web": [
    {"title": "GGRN 양말", "url": "https://shop.example.com/goods/1", "position": 1,
     "description": "상세는 https://shop.example.com/goods/1/detail. 사진 (https://img.example.com/a.png)"},
    {"title": "두 번째", "url": "https://other.example.com/p?id=2&color=red", "description": "설명", "position": 2},
]}}, ensure_ascii=False)
EXTRACT = json.dumps({"results": [
    {"url": "https://shop.example.com/goods/1", "title": "양말", "error": None,
     "content": "# 양말\n![앞](/img/front.jpg) [옆](https://shop.example.com/img/side.png).\n"
                "<img src=\"//cdn.example.com/c.webp\"> <a href='detail?x=1&amp;y=2'>상세</a>\n"
                "자기 주소 https://shop.example.com/goods/1 그리고 [맨 위](#top) [메일](mailto:a@example.com)"},
    {"url": "https://blocked.example.com/x", "title": "", "content": "https://blocked.example.com/secret", "error": "Blocked"},
]}, ensure_ascii=False)


class FoundLinksFixture(base.PluginFixture):
    """가짜 Control Plane 의 알리는 경로를 띄운다. 검사는 두지 않는다."""

    def setUp(self):
        super().setUp()
        self.requests = []
        self.status = 204
        test = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                test.requests.append({"path": self.path, "headers": dict(self.headers), "body": json.loads(raw)})
                self.send_response(test.status)
                self.send_header("Content-Length", "0")
                self.end_headers()

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.saved_env = os.environ.get("FOS_CTX_POLICY_URL")
        os.environ["FOS_CTX_POLICY_URL"] = (
            "http://127.0.0.1:%d/internal/hermes/connector-policy" % self.server.server_address[1])
        self.found_links = sys.modules[self.plugin.__name__ + ".found_links"]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        if self.saved_env is None:
            os.environ.pop("FOS_CTX_POLICY_URL", None)
        else:
            os.environ["FOS_CTX_POLICY_URL"] = self.saved_env
        super().tearDown()

    def result(self, tool, result, call="call_1", session=base.VECTOR_SESSION, args=None):
        return self.plugin.transform_tool_result(
            tool_name=tool, result=result, session_id=session, tool_call_id=call, args={} if args is None else args)


class PagesTest(FoundLinksFixture):
    """결과에서 후보를 뽑는 모양을 본다(스펙 agent-found-image REQ2, DECISION25)."""

    def test_search_links_come_from_result_items(self):
        """검색 결과 항목의 주소와 제목, 설명 안의 주소를 뽑는다. 문장 부호와 짝 없는 괄호는 떼고 기준 주소는 없다."""
        self.assertEqual(self.found_links.pages("web_search", SEARCH), [{"base": None, "links": [
            "https://shop.example.com/goods/1",
            "https://shop.example.com/goods/1/detail",
            "https://img.example.com/a.png",
            "https://other.example.com/p?id=2&color=red",
        ]}])

    def test_failed_search_error_text_is_not_read(self):
        """실패한 검색의 오류 글에 든 주소는 남이 쓴 결과가 아니라 뽑지 않는다."""
        failed = json.dumps({"success": False, "error": "could not fetch https://typed.example.com/?q=secret"})

        self.assertEqual(self.found_links.pages("web_search", failed), [])

    def test_extract_links_come_from_content_with_the_page_as_base(self):
        """읽은 본문의 절대 주소, 마크다운 대상, 속성 값을 뽑고 기준은 항목의 주소다. 항목의 주소 자체는 본문에 있을 때만 후보에 든다."""
        found = self.found_links.pages("web_extract", EXTRACT)

        self.assertEqual(len(found), 1, "오류가 있는 항목은 읽지 않는다")
        self.assertEqual(found[0]["base"], "https://shop.example.com/goods/1")
        self.assertEqual(found[0]["links"], [
            "https://shop.example.com/img/side.png",
            "https://shop.example.com/goods/1",
            "/img/front.jpg",
            "//cdn.example.com/c.webp",
            "detail?x=1&amp;y=2",
        ])

    def test_page_url_alone_is_not_a_candidate(self):
        """본문에 주소가 없으면 읽은 페이지의 주소만으로는 아무것도 알리지 않는다."""
        plain = json.dumps({"results": [{"url": "https://typed.example.com/a", "title": "t", "content": "본문", "error": None}]})

        self.assertEqual(self.found_links.pages("web_extract", plain), [])

    def test_limits_count_and_length(self):
        """한 호출은 2,000개까지, 후보 하나는 2,048자까지다. 같은 후보는 한 번만 보낸다."""
        many = " ".join("https://a.example.com/%d" % number for number in range(2100))
        long_link = "https://a.example.com/" + "x" * 2048
        content = "https://a.example.com/0 " + long_link + " " + many
        result = json.dumps({"results": [{"url": "https://a.example.com/", "title": "", "content": content, "error": None}]})

        links = self.found_links.pages("web_extract", result)[0]["links"]

        self.assertEqual(len(links), 2000)
        self.assertEqual(len(set(links)), 2000)
        self.assertNotIn(long_link, links)

    def test_link_followed_by_korean_particle_yields_the_real_address(self):
        """주소 뒤에 조사나 표 구분자가 바로 붙어도 그 앞까지 자른 주소를 뽑는다. 붙은 꼴도 한글 경로일 수 있어 함께 낸다."""
        text = "사진은 https://a.example.com/x.png를 보고 |https://a.example.com/y| **https://a.example.com/z**"

        self.assertEqual(self.found_links.absolute_links(text), [
            "https://a.example.com/x.png", "https://a.example.com/x.png를",
            "https://a.example.com/y", "https://a.example.com/y|",
            "https://a.example.com/z",
        ])
        self.assertEqual(self.found_links.absolute_links("https://a.example.com/상품/1"),
                         ["https://a.example.com/", "https://a.example.com/상품/1"], "한글 경로는 붙은 꼴이 맞는 주소다")

    def test_long_runs_of_closing_brackets_do_not_stall(self):
        """닫는 괄호를 길게 이어 붙인 본문도 금방 끝난다. 길이의 제곱으로 느려지면 나쁜 페이지 하나가 결과 hook 을 붙잡는다."""
        import time
        hostile = ("https://a.example.com/" + ")" * 2000 + " ") * 60 + "https://a.example.com/" + "]" * 400000
        started = time.monotonic()

        links = self.found_links.absolute_links(hostile)

        self.assertLess(time.monotonic() - started, 2.0)
        self.assertEqual(set(links), {"https://a.example.com/"})

    def test_typed_urls_are_the_extract_arguments(self):
        """비서가 `web_extract` 에 적어 넣은 주소를 따로 낸다. 검색에는 없다."""
        self.assertEqual(self.found_links.typed_urls("web_extract", {"urls": ["https://t.example.com/a", 3, ""]}),
                         ["https://t.example.com/a"])
        self.assertEqual(self.found_links.typed_urls("web_extract", {"urls": "https://t.example.com/a"}), ["https://t.example.com/a"])
        self.assertEqual(self.found_links.typed_urls("web_search", {"query": "https://t.example.com/a"}), [])
        self.assertEqual(self.found_links.typed_urls("web_extract", None), [])

    def test_page_with_too_long_url_is_skipped(self):
        """기준 주소가 상한을 넘는 항목은 건너뛰고 나머지 항목은 그대로 낸다. 서버가 본문 전체를 거절하지 않게 한다."""
        result = json.dumps({"results": [
            {"url": "https://a.example.com/" + "x" * 2048, "title": "", "content": "https://a.example.com/1", "error": None},
            {"url": "https://b.example.com/p", "title": "", "content": "https://b.example.com/2", "error": None},
        ]})

        self.assertEqual(self.found_links.pages("web_extract", result),
                         [{"base": "https://b.example.com/p", "links": ["https://b.example.com/2"]}])

    def test_other_shapes_yield_nothing(self):
        """JSON 이 아니거나 모양이 다른 결과, 다른 도구는 빈 목록이다."""
        for tool, result in (("web_search", "not json"), ("web_search", "[]"), ("web_extract", json.dumps({"results": "x"})),
                             ("web_search", json.dumps({"success": True, "data": {}})), ("terminal", SEARCH),
                             ("web_search", None)):
            self.assertEqual(self.found_links.pages(tool, result), [], (tool, result))


class ReportTest(FoundLinksFixture):
    """결과 hook 이 알리고 결과는 바꾸지 않는지 본다."""

    def test_reports_signed_pages_and_keeps_the_result(self):
        """알리는 본문에 서명한 맥락과 후보가 있고 hook 은 None 을 돌려준다."""
        self.assertIsNone(self.result("web_search", SEARCH))
        self.assertIsNone(self.result("web_extract", EXTRACT, call="call_2", args={"urls": ["shop.example.com/goods/1"]}))

        self.assertEqual([request["path"] for request in self.requests], ["/internal/hermes/found-links"] * 2)
        first = self.requests[0]
        self.assertEqual(first["headers"]["Authorization"], "Bearer " + base.FAKE_MCP_CREDENTIAL)
        ctx = first["body"]["_fos_ctx"]
        self.assertEqual((ctx["root_session_id"], ctx["session_id"], ctx["tool_call_id"]),
                         (base.VECTOR_ROOT, base.VECTOR_SESSION, "call_1"))
        context = sys.modules[self.plugin.__name__ + ".context"]
        self.assertEqual(ctx["sig"], context.sign(context.signing_key(base.FAKE_MCP_CREDENTIAL), "found_links",
                                                  base.VECTOR_ROOT, base.VECTOR_SESSION, "call_1"))
        self.assertEqual(first["body"]["pages"], self.found_links.pages("web_search", SEARCH))
        self.assertEqual(self.requests[1]["body"]["pages"][0]["base"], "https://shop.example.com/goods/1")
        self.assertNotIn("typed", first["body"], "검색에는 적어 넣은 주소가 없다")
        self.assertEqual(self.requests[1]["body"]["typed"], ["shop.example.com/goods/1"])

    def test_rejections_and_failures_leave_the_result_alone(self):
        """옛 Control Plane 의 거절, 서버 오류, 연결 실패에도 예외 없이 None 이다."""
        for status in (401, 403, 404, 500):
            self.status = status
            self.assertIsNone(self.result("web_search", SEARCH), status)
        os.environ["FOS_CTX_POLICY_URL"] = "http://127.0.0.1:1/internal/hermes/connector-policy"
        self.assertIsNone(self.result("web_search", SEARCH))

    def test_failures_are_logged_as_warnings(self):
        """알리지 못하면 기본 로그 수준에서 보이게 경고로 남긴다. 주소는 로그에 없다."""
        self.status = 500
        with self.assertLogs(self.plugin.logger, level="WARNING") as logs:
            self.result("web_search", SEARCH)
        self.assertIn("찾은 주소를 알리지 못했다: HTTP 500", "\n".join(logs.output))
        self.assertNotIn("http", "\n".join(logs.output).replace("HTTP", ""))

    def test_does_not_report_without_call_context_or_links(self):
        """`tool_call_id` 나 session 이 없는 호출(코드 실행 안의 호출), 뽑을 주소가 없는 결과, 토큰이 없는 profile 은 알리지 않는다."""
        self.assertIsNone(self.result("web_search", SEARCH, call=""))
        self.assertIsNone(self.result("web_search", SEARCH, session=""))
        self.assertIsNone(self.result("web_search", json.dumps({"success": False, "error": "x"})))
        self.token = None
        self.assertIsNone(self.result("web_search", SEARCH))

        self.assertEqual(self.requests, [])

    def test_other_tools_are_not_reported(self):
        """웹 도구가 아닌 내장 도구의 결과는 주소가 있어도 알리지 않는다."""
        self.assertIsNone(self.result("terminal", SEARCH))
        self.assertIsNone(self.result("browser_navigate", SEARCH))

        self.assertEqual(self.requests, [])
