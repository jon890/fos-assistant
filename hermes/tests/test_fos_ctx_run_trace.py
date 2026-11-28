"""fos-ctx 가 도구마다 걸린 시간과 실패한 도구의 오류를 Control Plane 에 보내는 모양을 본다(스펙 run-trace REQ6, REQ7).

계약은 `hermes/plugins/fos-ctx/README.md` 의 「도구 기록을 보낸다」 다. 서명 확인 값은 backend 의 `RunTraceEndpointTest` 와 같다.
한쪽을 바꾸면 두 쪽을 함께 고친다.
"""

import http.server
import json
import os
import sys
import threading
import time
import unittest

import test_fos_ctx as base

# backend `RunTraceEndpointTest.VECTOR_SIG` 와 같은 값이다. key, 루트, session, 호출 번호는 도구 판정 확인 값과 같다.
TRACE_VECTOR_SIG = "00838c8bbb3d1461b00deb843c093dc6ad9103c44211569433b6f9e74e283436"
BODY_FIELDS = {"kind", "session_id", "root_session_id", "tool_call_id", "tool_name", "duration_ms", "status",
               "error_type", "error_message", "ended_at", "sig"}


class FakeControlPlane:
    """받은 본문을 모으는 가짜 Control Plane 이다. 답을 늦추거나 실패시킬 수 있다."""

    def __init__(self, status=202, delay=0.0):
        self.bodies = []
        self.headers = []
        self.received = threading.Event()
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                owner.bodies.append(json.loads(self.rfile.read(length).decode("utf-8")))
                owner.headers.append(dict(self.headers))
                owner.received.set()
                time.sleep(delay)
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"accepted": 0}')

            def log_message(self, *args):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def policy_url(self):
        return "http://127.0.0.1:%d/internal/hermes/connector-policy" % self.server.server_address[1]

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class RunTraceTest(base.PluginFixture):

    def setUp(self):
        super().setUp()
        self.saved_env = os.environ.get("FOS_CTX_POLICY_URL")
        self.addCleanup(self.restore_env)

    def restore_env(self):
        if self.saved_env is None:
            os.environ.pop("FOS_CTX_POLICY_URL", None)
        else:
            os.environ["FOS_CTX_POLICY_URL"] = self.saved_env

    def serve(self, **kwargs):
        server = FakeControlPlane(**kwargs)
        self.addCleanup(server.close)
        os.environ["FOS_CTX_POLICY_URL"] = server.policy_url
        return server

    def fire(self, **overrides):
        kwargs = {"tool_name": "terminal", "args": {"command": "합성인자표식"}, "result": "합성결과표식",
                  "session_id": base.VECTOR_SESSION, "tool_call_id": "call_1", "duration_ms": 1200,
                  "status": "error", "error_type": "tool_error", "error_message": "명령을 찾지 못했다",
                  "middleware_trace": [{"name": "합성미들웨어표식"}], "task_id": "t", "turn_id": "u"}
        kwargs.update(overrides)
        started = time.monotonic()
        self.plugin.post_tool_call(**kwargs)
        return time.monotonic() - started

    def test_signature_matches_backend_vector(self):
        """서명이 backend 시험과 같은 확인 값을 낸다."""
        self.assertEqual(self.plugin.sign_trace(base.POLICY_VECTOR_KEY, "tool", base.POLICY_VECTOR_ROOT,
                                                base.POLICY_VECTOR_SESSION, base.POLICY_VECTOR_CALL),
                         TRACE_VECTOR_SIG)

    def test_sends_only_numbers_and_error_with_root_and_signature(self):
        """본문은 정해진 칸뿐이고 인자, 결과, 미들웨어 이력이 없다. 루트 session 과 서명을 붙인다."""
        server = self.serve()

        self.fire()

        self.assertTrue(server.received.wait(5))
        record = server.bodies[0]["records"][0]
        self.assertEqual(server.bodies[0]["v"], 1)
        self.assertEqual(set(record), BODY_FIELDS)
        self.assertEqual(record["root_session_id"], base.VECTOR_ROOT)
        self.assertEqual(record["duration_ms"], 1200)
        self.assertEqual(record["error_message"], "명령을 찾지 못했다")
        self.assertEqual(record["sig"], self.plugin.sign_trace(base.VECTOR_KEY, "tool", base.VECTOR_ROOT,
                                                               base.VECTOR_SESSION, "call_1"))
        self.assertEqual(server.headers[0]["Authorization"], "Bearer " + base.FAKE_MCP_CREDENTIAL)
        self.assertTrue(server.bodies[0]["records"][0]["ended_at"] > 0)
        raw = json.dumps(server.bodies, ensure_ascii=False)
        for marker in ("합성인자표식", "합성결과표식", "합성미들웨어표식"):
            self.assertNotIn(marker, raw)
        self.assertTrue(server.headers[0]["Host"])

    def test_success_and_missing_call_id(self):
        """성공한 도구는 오류 칸이 비고, 코드 실행 안의 호출처럼 번호가 없으면 빈 글로 보낸다. 0 은 모름이다."""
        server = self.serve()

        self.fire(status="ok", error_type="x", error_message="y", tool_call_id="", duration_ms=0)

        self.assertTrue(server.received.wait(5))
        record = server.bodies[0]["records"][0]
        self.assertIsNone(record["error_type"])
        self.assertIsNone(record["error_message"])
        self.assertEqual(record["tool_call_id"], "")
        self.assertIsNone(record["duration_ms"])

    def test_long_error_message_is_cut_one_past_the_redaction_limit(self):
        """오류 글은 Control Plane 가림 규칙의 입력 상한보다 한 글자 길게만 보낸다."""
        server = self.serve()

        self.fire(error_message="가" * 70_000)

        self.assertTrue(server.received.wait(5))
        self.assertEqual(len(server.bodies[0]["records"][0]["error_message"]), 65_537)

    def test_hook_does_not_wait_for_slow_server(self):
        """Control Plane 이 5초 늦게 답해도 hook 은 바로 돌아온다."""
        server = self.serve(delay=5.0)

        elapsed = self.fire()

        self.assertLess(elapsed, 0.2)
        self.assertTrue(server.received.wait(5))

    def test_server_error_is_logged_by_kind_only(self):
        """500 이어도 예외가 없고 로그에는 실패 종류만 남는다."""
        server = self.serve(status=500)

        with self.assertLogs(self.plugin.logger, level="WARNING") as logs:
            self.fire(error_message="합성오류원문표식")
            self.assertTrue(server.received.wait(5))
            deadline = time.monotonic() + 5
            while not any("HTTP 500" in line for line in logs.output) and time.monotonic() < deadline:
                time.sleep(0.05)

        text = "\n".join(logs.output)
        self.assertIn("HTTP 500", text)
        self.assertNotIn("합성오류원문표식", text)
        self.assertNotIn(base.FAKE_MCP_CREDENTIAL, text)

    def test_nothing_is_sent_without_url_or_token(self):
        """주소나 토큰이 없으면 보내지 않고 예외도 없다."""
        os.environ.pop("FOS_CTX_POLICY_URL", None)
        self.fire()
        server = self.serve()
        self.token = None
        self.fire()
        self.assertFalse(server.received.wait(1))

    def test_batches_split_by_count_and_error_size(self):
        """한 요청은 기록 100개, 오류 글의 UTF-8 합 256KB 를 넘지 않는다."""
        records = [{"error_message": "가" * 40_000}] * 3 + [{"error_message": None}] * 150
        batches = list(sys.modules[self.plugin.__name__ + ".run_trace"]._batches(records))
        self.assertTrue(all(len(batch) <= 100 for batch in batches))
        self.assertTrue(all(sum(len((r["error_message"] or "").encode("utf-8")) for r in batch) <= 256 * 1024
                            for batch in batches))
        self.assertEqual(len(batches[0]), 2, "한글 4만 자는 12만 바이트라 셋째는 다음 요청으로 간다")
        self.assertEqual(sum(len(batch) for batch in batches), len(records))


if __name__ == "__main__":
    unittest.main(verbosity=2)
