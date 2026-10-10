"""고정 Hermes 소스의 함수로 plugin envelope→native input_image와 detail을 검증한다."""
import ast
import copy
import hashlib
import io
import importlib
import json
import logging
import os
import pathlib
import sys
import types
import unittest
from unittest.mock import patch

import hermes_contract as contract
from test_fos_ctx import load_ctx
from test_hermes_contract import _accepts


@unittest.skipUnless(os.environ.get("HERMES_SOURCE"), "HERMES_SOURCE가 없어 실제 소스 계약 검사를 건너뛴다")
class NativeImageContractTest(unittest.TestCase):
    def setUp(self):
        self.plugin = load_ctx(self.addCleanup)
        self.inspect = importlib.import_module(self.plugin.__name__ + ".attachment_inspect")

    def test_plugin_result_reaches_responses_as_native_image(self):
        source = pathlib.Path(os.environ["HERMES_SOURCE"])
        relative, name, call = contract.NATIVE_ATTACHMENT_REGISTRATION
        registration = next(node for node in ast.walk(ast.parse((source / relative).read_text()))
            if isinstance(node, ast.FunctionDef) and node.name == name)
        self.assertIsNone(_accepts(registration, call, method=True))
        env = {"json": json, "hashlib": hashlib, "logger": logging.getLogger("contract"),
               "tool_error": lambda message, **kwargs: json.dumps({"error": message, **kwargs}),
               "_bound_json_error_result": lambda result: result,
               "file_mutation_result_landed": lambda *args: False, "safe_json_loads": json.loads,
               "is_guardrail_refusal": lambda data: False, "_trim_error": lambda text: text,
               "maybe_persist_tool_result": lambda **kwargs: kwargs["content"]}
        for relative, names in contract.NATIVE_ATTACHMENT_FUNCTIONS.items():
            tree = ast.parse((source / relative).read_text())
            selected = []
            for name in names:
                node = copy.deepcopy(next(n for n in ast.walk(tree)
                    if isinstance(n, ast.FunctionDef) and n.name == name))
                node.decorator_list = []
                selected.append(node)
            module = ast.Module(body=[ast.ImportFrom(module="__future__", level=0,
                names=[ast.alias(name="annotations")]), *selected], type_ignores=[])
            exec(compile(ast.fix_missing_locations(module), relative, "exec"), env)
        env.update(_IMAGE_PART_TYPES={"image_url", "input_image"},
                   _TEXT_PART_TYPES={"text", "input_text", "output_text"},
                   _VIDEO_PART_TYPES={"video", "video_url", "input_video"}, _MAX_RESPONSES_ITEM_ID_LENGTH=64)
        prep = types.ModuleType("tools.vision_tools_image_prep")
        prep.unsupported_inline_image_media_type = lambda url: None
        prep.rasterize_svg_data_url = lambda url: None
        with patch.dict(sys.modules, {prep.__name__: prep}), \
             patch.dict(os.environ, {"FOS_ATTACHMENT_INSPECT_URL": "http://example.test/internal/hermes/attachment-inspect"}), \
             patch.object(self.inspect, "_read_token", return_value="fake"), \
             patch.object(self.inspect, "_open", return_value=self.response()):
            envelope = self.inspect.handle({"attachment_id": 7, "_fos_ctx": {}, "_fos_inspect": {}})
            normalized = env["_normalize_handler_result"]("attachment_inspect", envelope)
            self.assertIs(normalized, envelope)
            persisted = env["_persist_multimodal_text_parts"](normalized, "attachment_inspect", "actual", None, None)
            self.assertEqual(persisted["content"][1], envelope["content"][1])
            self.assertFalse(env["_detect_tool_failure"]("attachment_inspect", envelope)[0])
            self.assertTrue(env["_detect_tool_failure"]("attachment_inspect", self.inspect.failure())[0])
            relative, name = contract.NATIVE_TOOL_EVENT_FIELDS
            tree = ast.parse((source / relative).read_text())
            assignment = next(node for node in tree.body if isinstance(node, ast.Assign)
                and any(isinstance(target, ast.Name) and target.id == name for target in node.targets))
            exec(compile(ast.Module(body=[assignment], type_ignores=[]), relative, "exec"), env)
            fields = env[name]["tool.completed"]("attachment_inspect", None, {"is_error": True, "duration": 0.5})
            self.assertIs(fields["error"], True)
            model = types.SimpleNamespace(provider="openai-codex", model="gpt-6.1-sol",
                _no_list_tool_content_models=set(), _model_supports_vision=lambda: True,
                _provider_supports_vision_tool_messages=lambda: True,
                _content_has_image_parts=lambda parts: any(part.get("type") == "image_url" for part in parts))
            content = env["_tool_result_content_for_active_model"](model, "attachment_inspect", normalized)
            wire = env["_tool_output_items"]({"role": "tool", "tool_call_id": "actual", "content": content})
            self.assertEqual(wire[0]["output"][1], {"type": "input_image", "detail": "original",
                "image_url": envelope["content"][1]["image_url"]["url"]})
            model._no_list_tool_content_models.add(("openai-codex", "gpt-6.1-sol"))
            fallback = env["_tool_result_content_for_active_model"](model, "attachment_inspect", normalized)
            self.assertIsInstance(fallback, str)
            self.assertIn("판독 실패", fallback)

    @staticmethod
    def response():
        class Response(io.BytesIO):
            headers = {"Content-Type": "image/png", "Content-Length": "12"}
        return Response(b"\x89PNG\r\n\x1a\nmore")
