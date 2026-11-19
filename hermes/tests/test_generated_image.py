"""fos-ctx 가 `image_generate` 의 그림을 Control Plane 에 올리고 모델에게 가는 결과에서 경로를 빼는지 본다.

계약은 fos-assistant `docs/adr/ADR-20261119-generated-image.md` 와 `hermes/plugins/fos-ctx/README.md` 의 「만든 그림을 올린다」 다.
가짜 Control Plane 이 받은 multipart 를 표준 라이브러리의 `email` 로 풀어 조각과 서명을 본다. Hermes 의 실제 결과 모양과 캐시 폴더는
`test_generated_image_contract.py` 가 Hermes 소스에서 본다.
"""

import email
import email.policy
import hashlib
import http.server
import json
import os
import pathlib
import threading

import test_fos_ctx as base


# 그림 올리기의 서명 확인 값이다. 정책 확인 값의 key, 루트, session, 호출 id 와 그림 `image-bytes`, 제목 `수채화 고양이`,
# 지시문 `창가에 앉은 고양이` 다. 서버 쪽 `backend/src/test/java/com/bifos/assistant/mcp/GeneratedImageEndpointTest.java` 와 같은 값이어야 한다.
VECTOR_SIG = "d5c850a5926fcf396a1e87367d88573994150da200a6ce78a4270388394d69ee"
PNG = b"\x89PNG\r\n\x1a\n" + b"\x01" * 64
PROMPT = "창가에 앉은 고양이를 수채화로, 부드러운 오후 햇살, 배경은 흐리게"


def parse_multipart(content_type, raw):
    """받은 본문을 `{조각 이름: 바이트}` 로 푼다."""
    message = email.message_from_bytes(
        b"Content-Type: " + content_type.encode("ascii") + b"\r\n\r\n" + raw, policy=email.policy.HTTP)
    return {part.get_param("name", header="content-disposition"): part.get_payload(decode=True)
            for part in message.iter_parts()}


class GeneratedImageFixture(base.PluginFixture):
    """가짜 Control Plane 의 판정 경로와 올리기 경로를 띄운다. 검사는 두지 않는다."""

    def setUp(self):
        super().setUp()
        self.uploads = []
        self.guard_answer = (200, b'{"decision": "allow"}')
        self.upload_answer = (200, json.dumps({"title": "수채화 고양이"}).encode("utf-8"))
        test = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                if self.path.endswith("/generated-image"):
                    test.uploads.append({"headers": dict(self.headers),
                                         "parts": parse_multipart(self.headers["Content-Type"], raw)})
                    status, payload = test.upload_answer
                else:
                    status, payload = test.guard_answer
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.saved_env = os.environ.get("FOS_CTX_POLICY_URL")
        os.environ["FOS_CTX_POLICY_URL"] = (
            "http://127.0.0.1:%d/internal/hermes/connector-policy" % self.server.server_address[1])
        self.cache = pathlib.Path(self.tmp.name) / "cache" / "generated" / "images"
        self.cache.mkdir(parents=True)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        if self.saved_env is None:
            os.environ.pop("FOS_CTX_POLICY_URL", None)
        else:
            os.environ["FOS_CTX_POLICY_URL"] = self.saved_env
        super().tearDown()

    def image(self, name="openai_codex_medium_1.png", body=PNG):
        path = self.cache / name
        path.write_bytes(body)
        return path

    def result(self, path):
        return json.dumps({"success": True, "image": str(path), "modality": "text"})

    def transform(self, result, prompt=PROMPT, call="call_1", session=None):
        return self.plugin.transform_tool_result(
            tool_name="image_generate", result=result, args={"prompt": prompt},
            session_id=base.VECTOR_SESSION if session is None else session, tool_call_id=call)


class TitleLineTest(GeneratedImageFixture):
    """`pre_tool_call` 이 판정을 통과한 그림 만들기의 제목 줄을 떼는지 본다."""

    def call(self, prompt, tool="image_generate"):
        return self.plugin.pre_tool_call(
            tool_name=tool, args={"prompt": prompt, "aspect_ratio": "square"},
            session_id=base.VECTOR_SESSION, tool_call_id="call_1")

    def test_strips_title_line_from_prompt(self):
        """제목 줄은 이미지 서비스로 가지 않는다. 나머지 인자는 건드리지 않는다."""
        self.assertEqual(self.call("제목: 수채화 고양이\n" + PROMPT),
                         {"action": "modify", "args": {"prompt": PROMPT}})
        self.assertEqual(self.call("  제목:   수채화 고양이  \n\n" + PROMPT)["args"], {"prompt": PROMPT})

    def test_prompt_without_title_is_untouched(self):
        """제목 줄이 없거나 제목 줄뿐이면 고치지 않는다."""
        self.assertIsNone(self.call(PROMPT))
        self.assertIsNone(self.call("제목: 수채화 고양이"))
        self.assertIsNone(self.call("제목: 수채화 고양이\n  "))

    def test_blocked_call_is_not_modified(self):
        """판정이 막으면 그 결과를 그대로 돌려준다. 제목 줄을 떼지 않는다."""
        self.guard_answer = (200, json.dumps({"decision": "block", "message": "사람이 없는 실행이다"}).encode("utf-8"))
        self.assertEqual(self.call("제목: 수채화 고양이\n" + PROMPT),
                         {"action": "block", "message": "사람이 없는 실행이다"})

    def test_other_tools_are_untouched(self):
        self.assertIsNone(self.call("제목: 수채화 고양이\n" + PROMPT, tool="web_search"))

    def test_split_title(self):
        self.assertEqual(self.plugin.split_title("제목: 가\n나"), ("가", "나"))
        self.assertEqual(self.plugin.split_title("나"), ("", "나"))
        self.assertEqual(self.plugin.split_title(None), ("", ""))


class PublishTest(GeneratedImageFixture):
    """`transform_tool_result` 가 그림을 올리고 결과를 경로 없는 글로 바꾸는지 본다."""

    def test_uploads_image_and_hides_path(self):
        """성공한 그림은 `meta` 와 `image` 두 조각으로 올라가고, 모델은 경로 없는 결과를 받는다."""
        self.plugin.pre_tool_call(tool_name="image_generate", args={"prompt": "제목: 수채화 고양이\n" + PROMPT},
                                  session_id=base.VECTOR_SESSION, tool_call_id="call_1")
        path = self.image()

        replaced = self.transform(self.result(path))

        answer = json.loads(replaced)
        self.assertEqual(answer["success"], True)
        self.assertEqual(answer["title"], "수채화 고양이")
        self.assertNotIn(str(self.cache), replaced)
        self.assertNotIn("MEDIA:/", replaced)
        self.assertFalse(path.exists(), "올린 뒤 캐시의 원본을 지운다")
        upload = self.uploads[0]
        self.assertEqual(upload["headers"]["Authorization"], "Bearer " + base.FAKE_MCP_CREDENTIAL)
        self.assertEqual(upload["parts"]["image"], PNG)
        meta = json.loads(upload["parts"]["meta"])
        self.assertEqual((meta["v"], meta["root_session_id"], meta["session_id"], meta["tool_call_id"]),
                         (1, base.VECTOR_ROOT, base.VECTOR_SESSION, "call_1"))
        self.assertEqual((meta["title"], meta["prompt"]), ("수채화 고양이", PROMPT))
        self.assertEqual(meta["sig"], self.plugin.sign_generated_image(
            self.plugin.signing_key(base.FAKE_MCP_CREDENTIAL), base.VECTOR_ROOT, base.VECTOR_SESSION, "call_1",
            hashlib.sha256(PNG).hexdigest(), "수채화 고양이", PROMPT))

    def test_title_line_left_in_args_is_used(self):
        """hook 이 고치기 전의 인자를 받아도 제목 줄을 제목으로 쓰고 지시문에서 뗀다."""
        self.transform(self.result(self.image()), prompt="제목: 강아지\n" + PROMPT)
        meta = json.loads(self.uploads[0]["parts"]["meta"])
        self.assertEqual((meta["title"], meta["prompt"]), ("강아지", PROMPT))

    def test_failed_or_remote_result_is_untouched(self):
        """실패한 결과와 원격 주소, 캐시 폴더 밖 경로는 건드리지 않고 올리지 않는다."""
        outside = pathlib.Path(self.tmp.name) / "state.db"
        for result in (json.dumps({"success": False, "image": None, "error": "rate limit"}),
                       json.dumps({"success": True, "image": "https://example.com/a.png"}),
                       self.result(outside), self.result(self.cache / "missing.png"), "not json"):
            self.assertIsNone(self.transform(result))
        self.assertEqual(self.uploads, [])
        self.assertTrue(outside.exists(), "캐시 폴더 밖 파일은 지우지 않는다")

    def test_symlink_out_of_cache_is_untouched(self):
        """캐시 폴더 안의 링크가 밖을 가리키면 읽지 않는다."""
        link = self.cache / "link.png"
        link.symlink_to(pathlib.Path(self.tmp.name) / "state.db")
        self.assertIsNone(self.transform(self.result(link)))
        self.assertEqual(self.uploads, [])

    def test_upload_failure_becomes_pathless_error(self):
        """올리기가 실패하면 경로 없는 오류 글로 바꾼다. 그림 파일은 남긴다."""
        path = self.image()
        for status in (403, 400, 500):
            self.upload_answer = (status, b'{"code": "X"}')
            replaced = self.transform(self.result(path))
            self.assertEqual(json.loads(replaced)["success"], False)
            self.assertNotIn(str(self.cache), replaced)
        self.assertTrue(path.exists())

    def test_missing_call_id_or_token_becomes_error(self):
        """호출 번호나 토큰이 없으면 올리지 않고 오류 글로 바꾼다."""
        path = self.image()
        self.assertEqual(json.loads(self.transform(self.result(path), call=""))["success"], False)
        self.token = None
        self.assertEqual(json.loads(self.transform(self.result(path)))["success"], False)
        self.assertEqual(self.uploads, [])

    def test_old_control_plane_keeps_result(self):
        """올리기 경로가 없는 옛 Control Plane(404)이면 결과를 바꾸지 않는다(rule-66)."""
        self.upload_answer = (404, b"{}")
        path = self.image()
        self.assertIsNone(self.transform(self.result(path)))
        self.assertTrue(path.exists())

    def test_unexpected_error_becomes_pathless_error(self):
        """hook 안의 예외는 경로가 든 원래 결과로 넘어가지 않고 오류 글이 된다."""
        self.secret_error = RuntimeError("boom")
        replaced = self.transform(self.result(self.image()))
        self.assertEqual(json.loads(replaced)["success"], False)

    def test_too_large_image_is_not_sent(self):
        self.plugin.generated_image.MAX_BYTES = 8
        self.addCleanup(setattr, self.plugin.generated_image, "MAX_BYTES", 20 * 1024 * 1024)
        self.assertEqual(json.loads(self.transform(self.result(self.image())))["success"], False)
        self.assertEqual(self.uploads, [])

    def test_signature_vector(self):
        """서명 확인 값이다. 서버 쪽 `GeneratedImageEndpointTest` 의 값과 같아야 한다."""
        self.assertEqual(
            self.plugin.sign_generated_image(
                base.POLICY_VECTOR_KEY, base.POLICY_VECTOR_ROOT, base.POLICY_VECTOR_SESSION, base.POLICY_VECTOR_CALL,
                hashlib.sha256(b"image-bytes").hexdigest(), "수채화 고양이", "창가에 앉은 고양이"),
            VECTOR_SIG)
