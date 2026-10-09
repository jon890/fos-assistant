"""원본 조회 hook과 native 이미지 반환의 회귀 검사다."""
import importlib
import json
import os
import sqlite3
import tempfile
import pathlib
import unittest
from unittest.mock import patch

from test_fos_ctx import load_ctx


class AttachmentInspectTest(unittest.TestCase):
    def setUp(self):
        self.plugin = load_ctx(self.addCleanup)
        self.inspect = importlib.import_module(self.plugin.__name__ + ".attachment_inspect")
        self.mapping = patch(self.plugin.__name__ + ".hooks.read_tool_map", return_value=(None, False))
        self.mapping.start()
        self.addCleanup(self.mapping.stop)

    def test_hook_overwrites_model_context_with_actual_call(self):
        with patch.object(self.inspect, "build_context", return_value={
            "v": 1, "root_session_id": "root", "session_id": "session",
            "tool_call_id": "actual", "sig": "signed",
        }), patch.object(self.inspect, "_read_token", return_value="fake-token"), \
             patch.object(self.inspect, "top_level_session", return_value=True):
            result = self.plugin.pre_tool_call("attachment_inspect", {
                "attachment_id": 7, "_fos_ctx": {"tool_call_id": "model"},
                "_fos_inspect": {"sig": "forged"},
            }, "session", "actual")
        self.assertEqual(result["action"], "modify")
        self.assertEqual(result["args"]["_fos_ctx"]["tool_call_id"], "actual")
        self.assertNotEqual(result["args"]["_fos_inspect"]["sig"], "forged")

    def test_missing_context_and_bad_inputs_block(self):
        for args in ({"attachment_id": "file:///x"}, {"attachment_id": True},
                     {"attachment_id": 7, "region": [0, 0, 1.5, 2]},
                     {"attachment_id": 7, "url": "https://example.test"}):
            self.assertEqual(self.plugin.pre_tool_call("attachment_inspect", args)["action"], "block")
        self.assertIsInstance(self.inspect.handle({"attachment_id": 7}), str)

    def test_native_envelope_and_failure(self):
        args = {"attachment_id": 7, "_fos_ctx": {"v": 1}, "_fos_inspect": {"sig": "proof"}}
        with patch.dict(os.environ, {"FOS_ATTACHMENT_INSPECT_URL": "http://example.test/internal/hermes/attachment-inspect"}), \
             patch.object(self.inspect, "_read_token", return_value="fake"), \
             patch.object(self.inspect, "supervise", return_value=({"mime": "image/png"}, b"\x89PNG\r\n\x1a\nmore")):
            result = self.inspect.handle(args)
        self.assertIs(result["_multimodal"], True)
        self.assertEqual(result["content"][1]["image_url"]["detail"], "original")
        self.assertIn("data:image/png;base64,", result["content"][1]["image_url"]["url"])
        with patch.dict(os.environ, {"FOS_ATTACHMENT_INSPECT_URL": "http://example.test/internal/hermes/attachment-inspect"}), \
             patch.object(self.inspect, "_read_token", return_value="fake"), \
             patch.object(self.inspect, "supervise", side_effect=TimeoutError):
            self.assertIn("판독", self.inspect.handle(args))

    def test_register_independent_tool(self):
        registered = {}
        class Context:
            def register_hook(self, *args):
                pass
            def register_tool(self, **kwargs):
                registered.update(kwargs)
        self.plugin.register(Context())
        self.assertEqual(registered["name"], "attachment_inspect")
        self.assertFalse(registered.get("override", False))

    def test_top_level_compression_and_subagent_chains_are_distinct(self):
        context = importlib.import_module(self.plugin.__name__ + ".context")
        with tempfile.TemporaryDirectory() as directory:
            database = pathlib.Path(directory) / "state.db"
            with sqlite3.connect(database) as connection:
                connection.execute("CREATE TABLE sessions(id TEXT, parent_session_id TEXT, source TEXT)")
                connection.executemany("INSERT INTO sessions VALUES (?, ?, ?)", [
                    ("root", None, "api_server"), ("compressed", "root", "api_server"),
                    ("child", "root", "subagent"), ("child-compressed", "child", "api_server")])
            connection.close()
            with patch.object(context, "_state_db_path", return_value=database):
                self.assertTrue(context.top_level_session("compressed"))
                self.assertFalse(context.top_level_session("child"))
                self.assertFalse(context.top_level_session("child-compressed"))
                self.assertIsNone(context.top_level_session("missing"))
                with patch.object(context, "_read_token", return_value="fake"), \
                     patch.object(self.inspect, "_read_token", return_value="fake"):
                    for session, top in [("root", True), ("compressed", True),
                                         ("child", False), ("child-compressed", False)]:
                        result = self.plugin.pre_tool_call("attachment_inspect", {"attachment_id": 7}, session, "actual")
                        self.assertEqual(result["action"], "modify")
                        self.assertIs(result["args"]["_fos_inspect"]["top_level"], top)
                    result = self.plugin.pre_tool_call("attachment_inspect", {"attachment_id": 7}, "missing", "actual")
                    self.assertEqual(result["action"], "block")
                    with patch.object(context.sqlite3, "connect", side_effect=sqlite3.OperationalError):
                        result = self.plugin.pre_tool_call("attachment_inspect", {"attachment_id": 7}, "root", "actual")
                        self.assertEqual(result["action"], "block")

    def test_limit_error_requests_crop_but_corruption_does_not(self):
        args = {"attachment_id": 7, "_fos_ctx": {}, "_fos_inspect": {}}
        for code, expected in [("ATTACHMENT_INSPECTION_LIMIT", "region_required"),
                               ("ATTACHMENT_INSPECTION_FAILED", "original_unavailable")]:
            with patch.dict(os.environ, {"FOS_ATTACHMENT_INSPECT_URL": "http://example.test/internal/hermes/attachment-inspect"}), \
                 patch.object(self.inspect, "_read_token", return_value="fake"), \
                 patch.object(self.inspect, "supervise", return_value=({"code": expected}, b"")):
                result = json.loads(self.inspect.handle(args))
            self.assertEqual(result["code"], expected)
            self.assertIn("error", result)

    def test_overview_is_explicit_exclusive_signed_and_explained_in_native_and_fallback(self):
        original = {"attachment_id": 7}
        overview = {"attachment_id": 7, "overview": True}
        self.assertNotEqual(self.inspect.digest(original), self.inspect.digest(overview))
        self.assertEqual(self.inspect.digest(original), self.inspect.digest({**original, "overview": False}))
        self.assertFalse(self.inspect.valid_args({**overview, "region": [0, 0, 1, 1]}))
        self.assertFalse(self.inspect.valid_args({**overview, "overview": 1}))
        args = {**overview, "_fos_ctx": {}, "_fos_inspect": {}}
        metadata = {"mime": "image/png", "overview": True, "first_frame": True, "display_width": 5000,
                    "display_height": 4000, "result_width": 1600, "result_height": 1280}
        with patch.dict(os.environ, {"FOS_ATTACHMENT_INSPECT_URL": "http://example.test/internal/hermes/attachment-inspect"}), \
             patch.object(self.inspect, "_read_token", return_value="fake"), \
             patch.object(self.inspect, "supervise", return_value=(metadata, b"\x89PNG\r\n\x1a\nmore")):
            result = self.inspect.handle(args)
        self.assertIs(result["_multimodal"], True)
        for text in (result["content"][0]["text"], result["text_summary"]):
            self.assertIn("축소한 전체 개요", text)
            self.assertIn("5000x4000", text)
            self.assertIn("1600x1280", text)
            self.assertIn("region", text)
            self.assertIn("첫 프레임", text)
