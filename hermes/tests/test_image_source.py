"""fos-ctx 가 그림을 고칠 바탕 사진을 Control Plane 에서 받아 `image_generate` 의 인자를 바꾸는지 본다.

계약은 fos-assistant `docs/adr/ADR-20261120-image-source.md` 와 `hermes/plugins/fos-ctx/README.md` 의 「그림을 고칠 사진을 받는다」 다.
가짜 Control Plane 은 받은 가리킴마다 정해 둔 답을 준다. Hermes 가 두 인자를 받는 모양과 media cache 의 호스트 읽기는
`test_generated_image_contract.py` 가 Hermes 소스에서 본다.
"""

import http.server
import json
import os
import pathlib
import threading
import time
import unittest
import unittest.mock

import test_fos_ctx as base


PROMPT = "제목: 새 신발\n1번째 사진(첫 입력)의 신발만 3번째 사진(셋째 입력)의 운동화로 바꾼다. 사람과 배경은 그대로 둔다."
PHOTOS = {
    "attachment:11": ("image/jpeg", b"\xff\xd8\xff" + b"\x01" * 40),
    "attachment:12": ("image/png", b"\x89PNG\r\n\x1a\n" + b"\x02" * 40),
    "attachment:13": ("image/jpeg", b"\xff\xd8\xff" + b"\x03" * 40),
    "generated:수채화 고양이": ("image/png", b"\x89PNG\r\n\x1a\n" + b"\x04" * 40),
}


class ImageSourceFixture(base.PluginFixture):
    """가짜 Control Plane 의 판정 경로와 받기 경로를 띄운다. 검사는 두지 않는다."""

    def setUp(self):
        super().setUp()
        self.requests = []
        self.answers = {}
        self.guard_answer = b'{"decision": "allow"}'
        test = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, status, content_type, payload):
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_POST(self):
                raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                if not self.path.endswith("/image-source"):
                    self.reply(200, "application/json", test.guard_answer)
                    return
                body = json.loads(raw)
                test.requests.append({"headers": dict(self.headers), "body": body})
                source = body["source"]
                if source in test.answers:
                    status, code = test.answers[source]
                    self.reply(status, "application/json", json.dumps({"code": code}).encode("utf-8"))
                    return
                content_type, payload = PHOTOS[source]
                self.reply(200, content_type, payload)

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.saved_env = os.environ.get("FOS_CTX_POLICY_URL")
        os.environ["FOS_CTX_POLICY_URL"] = (
            "http://127.0.0.1:%d/internal/hermes/connector-policy" % self.server.server_address[1])
        self.copies = pathlib.Path(self.tmp.name) / "cache" / "fos-image-sources"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        if self.saved_env is None:
            os.environ.pop("FOS_CTX_POLICY_URL", None)
        else:
            os.environ["FOS_CTX_POLICY_URL"] = self.saved_env
        super().tearDown()

    def call(self, image_url=None, refs=None, call="call_1", session=None):
        args = {"prompt": PROMPT, "aspect_ratio": "square"}
        if image_url is not None:
            args["image_url"] = image_url
        if refs is not None:
            args["reference_image_urls"] = refs
        return self.plugin.pre_tool_call(
            tool_name="image_generate", args=args,
            session_id=base.VECTOR_SESSION if session is None else session, tool_call_id=call)

    def copied(self):
        return sorted(self.copies.iterdir()) if self.copies.exists() else []


class FetchTest(ImageSourceFixture):
    """가리킴을 받아 사본 경로로 바꾸는지 본다(스펙 generated-image REQ15)."""

    def test_three_photos_become_copies_in_media_cache(self):
        """바탕 한 장과 참고 두 장을 받아 그 순서대로 사본 경로를 넣는다. 사본의 바이트는 원본과 같다."""
        decision = self.call("attachment:11", ["attachment:12", "attachment:13"])

        self.assertEqual(decision["action"], "modify")
        args = decision["args"]
        self.assertEqual(args["prompt"], PROMPT.split("\n", 1)[1], "제목 줄도 함께 뗀다")
        paths = [args["image_url"], *args["reference_image_urls"]]
        for path, source in zip(paths, ["attachment:11", "attachment:12", "attachment:13"]):
            copy = pathlib.Path(path)
            self.assertEqual(copy.parent, self.copies)
            self.assertEqual(copy.read_bytes(), PHOTOS[source][1])
        self.assertEqual([pathlib.Path(path).suffix for path in paths], [".jpg", ".png", ".jpg"])
        sent = [request["body"] for request in self.requests]
        self.assertEqual([body["source"] for body in sent], ["attachment:11", "attachment:12", "attachment:13"])
        ctx = sent[0]["_fos_ctx"]
        self.assertEqual((ctx["v"], ctx["root_session_id"], ctx["session_id"], ctx["tool_call_id"]),
                         (1, base.VECTOR_ROOT, base.VECTOR_SESSION, "call_1"))
        self.assertEqual(ctx["sig"], self.plugin.sign(
            self.plugin.signing_key(base.FAKE_MCP_CREDENTIAL), "image_generate", base.VECTOR_ROOT,
            base.VECTOR_SESSION, "call_1"))
        self.assertEqual(self.requests[0]["headers"]["Authorization"], "Bearer " + base.FAKE_MCP_CREDENTIAL)

    def test_generated_image_and_single_reference_string(self):
        """만든 그림도 같은 길로 받는다. 참고가 글 하나여도 목록으로 바꾼다. 같은 가리킴은 한 번만 받는다."""
        args = self.call("generated:수채화 고양이", "generated:수채화 고양이")["args"]

        self.assertEqual(args["reference_image_urls"], [args["image_url"]])
        self.assertEqual(pathlib.Path(args["image_url"]).read_bytes(), PHOTOS["generated:수채화 고양이"][1])
        self.assertEqual(len(self.requests), 1)

    def test_result_hook_removes_copies(self):
        """결과 hook 이 그 호출의 사본을 지운다. 그림을 올리지 못한 결과여도 지운다."""
        args = self.call("attachment:11", ["attachment:12"])["args"]
        self.plugin.transform_tool_result(
            tool_name="image_generate", result=json.dumps({"success": False, "error": "rate limit"}),
            args=args, session_id=base.VECTOR_SESSION, tool_call_id="call_1")

        self.assertEqual(self.copied(), [])

    def test_stale_copies_are_swept(self):
        """결과 hook 이 돌지 못해 남은 1시간 넘은 사본은 다음 받기가 지운다."""
        self.copies.mkdir(parents=True)
        stale = self.copies / "old.png"
        stale.write_bytes(b"old")
        old = time.time() - 3700
        os.utime(stale, (old, old))

        self.call("attachment:11")

        self.assertFalse(stale.exists())

    def test_call_without_sources_is_untouched(self):
        """가리킴이 없는 그림 만들기는 Control Plane 에 묻지 않고 제목 줄만 뗀다."""
        self.assertEqual(self.call()["args"], {"prompt": PROMPT.split("\n", 1)[1]})
        self.assertEqual(self.call(image_url="", refs=[])["args"], {"prompt": PROMPT.split("\n", 1)[1]})
        self.assertEqual(self.requests, [])


class RejectTest(ImageSourceFixture):
    """쓸 수 없는 사진이 하나라도 있으면 막는지 본다(스펙 generated-image REQ16, DECISION25, DECISION32)."""

    def assertBlocked(self, decision, *lines):
        self.assertEqual(decision["action"], "block")
        for line in lines:
            self.assertIn(line, decision["message"])
        self.assertTrue(decision["message"].endswith("사용자에게 어느 사진을 왜 쓸 수 없는지 알린다."))

    def test_paths_urls_and_data_urls_are_blocked_without_asking(self):
        """경로, 주소, data URL 은 Control Plane 에 묻지 않고 막는다. 실행 공간의 다른 대화 사진을 읽지 못한다."""
        for value in ("/workspace/attachments/7/19.jpg", "https://example.com/a.png", "data:image/png;base64,AAAA",
                      "attachment:", "attachment:abc", "generated:  "):
            self.assertBlocked(self.call(value), value[:20], "attachment:<첨부 번호>")
            self.assertBlocked(self.call(refs=["attachment:11", value]), value[:20])
        self.assertEqual(self.requests, [])
        self.assertEqual(self.copied(), [])

    def test_more_than_sixteen_is_blocked(self):
        """이미지 서비스가 받는 16장을 넘으면 줄여 달라고 하며 막는다. Hermes 는 넘는 것을 말없이 버린다."""
        refs = ["attachment:%d" % number for number in range(100, 116)]
        self.assertBlocked(self.call("attachment:11", refs), "16장까지")
        self.assertEqual(self.requests, [])

    def test_one_unusable_source_blocks_all_and_removes_copies(self):
        """하나라도 쓸 수 없으면 어느 것을 왜 쓸 수 없는지 모두 적어 막고, 이미 받은 사본을 지운다."""
        self.answers = {"attachment:12": (422, "IMAGE_SOURCE_UNKNOWN"), "attachment:13": (410, "IMAGE_SOURCE_GONE")}

        decision = self.call("attachment:11", ["attachment:12", "attachment:13"])

        self.assertBlocked(decision, "attachment:12: 이 대화에 보낸 사진이나 이 대화에서 만든 그림이 아니다",
                           "attachment:13: 지웠거나 보관 기간이 지나 쓸 수 없다")
        self.assertNotIn("attachment:11", decision["message"])
        self.assertEqual(self.copied(), [])

    def test_other_failures_ask_to_retry(self):
        """그 밖의 답은 받지 못했다고 알린다."""
        self.answers = {"attachment:11": (500, "INTERNAL_ERROR")}
        self.assertBlocked(self.call("attachment:11"), "attachment:11: 사진을 받지 못했다")

    def test_old_control_plane_blocks(self):
        """받기 경로가 없는 옛 Control Plane(404)이면 막는다. 경로를 받던 동작으로 돌아가지 않는다."""
        self.answers = {"attachment:12": (404, "NOT_FOUND")}
        self.assertBlocked(self.call("attachment:11", ["attachment:12"]), "아직 사진을 바탕으로 그림을 고칠 수 없다")
        self.assertEqual(self.copied(), [])

    def test_call_without_tool_call_id_is_blocked(self):
        """코드 실행 안의 호출처럼 호출 번호가 없으면 맥락을 만들지 못해 막는다."""
        self.assertBlocked(self.call("attachment:11", call=""), "이 호출에서는 사진을 받을 수 없다")
        self.assertEqual(self.requests, [])

    def test_write_failure_blocks_and_removes_copies(self):
        """사본을 쓰다 실패하면 받지 못했다고 막고 이미 쓴 사본을 남기지 않는다."""
        original = pathlib.Path.write_bytes
        calls = []

        def fail_second(path, data):
            calls.append(path)
            if len(calls) == 2:
                raise OSError("disk full")
            return original(path, data)

        with unittest.mock.patch.object(pathlib.Path, "write_bytes", fail_second):
            decision = self.call("attachment:11", ["attachment:12"])

        self.assertBlocked(decision, "사진을 받지 못했다")
        self.assertEqual(self.copied(), [])

    def test_guard_block_comes_first(self):
        """도구 판정이 막은 호출은 사진을 받지 않고 판정의 글을 그대로 돌려준다."""
        self.guard_answer = json.dumps({"decision": "block", "message": "사람이 없는 실행이다"}).encode("utf-8")
        self.assertEqual(self.call("attachment:11"), {"action": "block", "message": "사람이 없는 실행이다"})
        self.assertEqual(self.requests, [])


if __name__ == "__main__":
    unittest.main()
