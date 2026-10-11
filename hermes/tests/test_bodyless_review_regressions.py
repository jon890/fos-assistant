"""독립 리뷰의 미방문 content와 FD 초기화 실패를 회귀로 고정한다."""

import errno
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from runtime import bodyless_requests as writer
from runtime.request_counts import count_request


class UnvisitedContentTest(unittest.TestCase):
    def test_nested_fields_and_unselected_content_are_unknown(self):
        cases = [
            {"type": "input_image", "image_url": {"url": "data:image/png;base64,YWJj", "extra": object()}},
            {"type": "image", "source": {"type": "base64", "data": "YWJj", "extra": object()}},
            {"inlineData": {"mimeType": "image/png", "data": "YWJj", "extra": object()}},
            {"inline_data": {"mime_type": "image/png", "data": b"abc", "extra": object()}},
            {"type": "input_text", "text": "a", "content": "unvisited"},
            {"text": "a", "parts": ["unvisited"]},
            {"type": "input_image", "image_url": "data:image/png;base64,YWJj", "text": "unvisited"},
            {"type": "image", "source": {"type": "base64", "data": "YWJj", "url": "unvisited"}},
            {"inlineData": {"mimeType": "image/png", "data": "YWJj"}, "inline_data": object()},
            {"inlineData": {"mimeType": "image/png", "mime_type": "image/png", "data": "YWJj"}},
            {"type": "text", "text": "a", "detail": object()},
        ]
        for index, part in enumerate(cases):
            with self.subTest(case=index):
                self.assertEqual("unknown", count_request({"input": [part]})["completeness"])

    def test_supported_metadata_and_root_configuration_remain_complete(self):
        parts = [
            {"type": "text", "text": "a", "cache_control": {"type": "ephemeral"}},
            {"type": "input_image", "image_url": {"url": "data:image/png;base64,YWJj", "detail": "auto"}},
            {"type": "input_image", "image_url": "data:image/png;base64,YWJj", "detail": "auto"},
            {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "YWJj"}},
            {"role": "user", "content": ["a"], "parts": ["b"], "output": ["c"]},
        ]
        for index, part in enumerate(parts):
            with self.subTest(case=index):
                result = count_request({"input": [part], "extra_body": object(), "tools": object()})
                self.assertEqual("complete", result["completeness"])

    def test_unvisited_content_does_not_override_invalid(self):
        result = count_request({"input": [{"type": "text", "text": 5, "content": object()}]})
        self.assertEqual("invalid", result["completeness"])

    def test_unsupported_type_is_not_known_metadata(self):
        for kind in ("audio", object()):
            result = count_request({"input": [{"type": kind,
                "inlineData": {"mimeType": "image/png", "data": "YWJj"}}]})
            self.assertEqual("unknown", result["completeness"])


class DescriptorOwnershipTest(unittest.TestCase):
    def assert_closed(self, fd):
        # 수정 전 실패를 확인할 때도 시험 자체는 FD를 남기지 않는다.
        try:
            os.fstat(fd)
        except OSError as error:
            self.assertEqual(errno.EBADF, error.errno)
        else:
            os.close(fd)
            self.fail("실패 뒤에도 FD가 열려 있다")

    def test_random_name_failure_closes_parent(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = os.path.realpath(temporary)
            parent = writer._open_directory(directory)
            with patch.object(writer, "_open_directory", return_value=parent), \
                    patch.object(writer.secrets, "token_hex", side_effect=OSError("synthetic")):
                self.assertIsNone(writer.make_writer(lambda agent: directory)(None, {"input": "a"}, reason="preflight"))
            self.assert_closed(parent)
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_fdopen_failure_closes_leaf_and_parent_and_removes_leaf(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = os.path.realpath(temporary)
            parent = writer._open_directory(directory)
            opened = []
            def failing_fdopen(fd, *args, **kwargs):
                opened.append(fd)
                raise MemoryError("synthetic")
            with patch.object(writer, "_open_directory", return_value=parent), \
                    patch.object(writer.os, "fdopen", side_effect=failing_fdopen):
                self.assertIsNone(writer.make_writer(lambda agent: directory)(None, {"input": "a"}, reason="preflight"))
            self.assertEqual(1, len(opened))
            self.assert_closed(parent)
            self.assert_closed(opened[0])
            self.assertEqual([], list(Path(directory).iterdir()))
