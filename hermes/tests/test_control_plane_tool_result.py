"""Control Plane MCP 도구 결과의 모양을 실제 mcp 패키지의 CallToolResult 로 확인한다.

Hermes 는 MCP 서버의 tools/call 결과를 `mcp.types.CallToolResult` 로 검증한다.
`content` 가 없는 값 객체는 `content Field required` 로 버려지고 모델은 도구 실패만 받는다.
backend 의 `McpToolResultShapeTest` 가 같은 fixture 로 backend 의 모양 검사가 이 판정과 같은지 본다.
"""

import json
import pathlib
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
FIXTURE = REPO / "test" / "fixtures" / "mcp-call-tool-result-v1.json"


class ControlPlaneToolResultTest(unittest.TestCase):
    def setUp(self):
        try:
            import mcp.types as mcp_types
            import pydantic
        except ImportError:
            self.skipTest("mcp 패키지가 없다")
        self.types = mcp_types
        self.invalid = pydantic.ValidationError
        self.fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def test_accepted_results_pass_call_tool_result(self):
        for case in self.fixture["accepted"]:
            with self.subTest(case["name"]):
                parsed = self.types.CallToolResult.model_validate(case["result"])
                self.assertEqual(parsed.is_error, case["result"].get("isError", False))
                self.assertTrue(all(item.type == "text" for item in parsed.content))

    def test_rejected_results_fail_like_hermes_log(self):
        for case in self.fixture["rejected"]:
            with self.subTest(case["name"]), self.assertRaises(self.invalid):
                self.types.CallToolResult.model_validate(case["result"])

    def test_bare_value_object_fails_on_missing_content(self):
        case = next(item for item in self.fixture["rejected"] if item["name"] == "bare-value-object")
        with self.assertRaises(self.invalid) as raised:
            self.types.CallToolResult.model_validate(case["result"])
        # 운영 로그의 `content Field required` 와 같은 오류다.
        self.assertIn(("content",), [tuple(error["loc"]) for error in raised.exception.errors()])
        self.assertIn("missing", [error["type"] for error in raised.exception.errors()])


if __name__ == "__main__":
    unittest.main()
