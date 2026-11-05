# 코드 규칙: rule-64. 까닭과 예외는 docs/code-rules/INDEX.md를 본다.
"""사건 실행의 도구 판정이 기대는 Hermes 의 도구 목록과 호출 지점이 그대로인지 본다.

fos-ctx 의 `pre_tool_call` 은 안전 목록(`SAFE_TOOLS`) 밖의 도구를 Control Plane 에 묻고, session 없는 호출은
코드 실행 안의 호출로 보고 묻지 않는다(fos-assistant ADR-20261105 / event-watch). 그래서 Hermes 를 올릴 때
도구가 생기거나 안전 목록 도구의 toolset 이 바뀌거나 코드 실행 안의 호출에 session 이 생기면 다시 봐야 한다.

Hermes 없이 도는 검사는 안전 목록이 계약의 표와 같은지만 본다.
Hermes 소스 검사는 `HERMES_SOURCE` 를 줄 때만 돈다. `scripts/checks/check-hermes-contract.sh` 가 이 값을 준다.
"""

import ast
import os
import pathlib
import runpy
import unittest

import hermes_contract as contract
from plugin_loading import load_plugin


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE_ENV = "HERMES_SOURCE"
REQUIRED_ENV = "HERMES_CONTRACT_REQUIRED"


class SafeListTest(unittest.TestCase):
    def test_safe_list_matches_contract(self):
        """안전 목록은 계약 표의 내장 도구와 도구 찾기뿐이다. `tool_call` 과 `skill_manage` 는 들지 않는다."""
        plugin = load_plugin("fos_ctx_guard_contract", ROOT / "plugins/fos-ctx/__init__.py", self.addCleanup)
        bridge = contract.TOOL_GUARD_BRIDGE[2] - {"tool_call"}
        self.assertEqual(plugin.SAFE_TOOLS, frozenset(contract.TOOL_GUARD_SAFE_TOOLSETS) | bridge)
        self.assertNotIn("tool_call", plugin.SAFE_TOOLS)
        self.assertNotIn("skill_manage", plugin.SAFE_TOOLS)
        self.assertTrue(set(contract.TOOL_GUARD_SAFE_TOOLSETS) <= contract.HERMES_BUILTIN_TOOLS)


class HermesToolSourceTest(unittest.TestCase):
    """`HERMES_SOURCE` 의 Hermes 소스에서 도구 목록과 코드 실행 안의 호출 지점을 대조한다."""

    @classmethod
    def setUpClass(cls):
        if not os.environ.get(SOURCE_ENV):
            if os.environ.get(REQUIRED_ENV):
                raise AssertionError("%s 가 없다" % SOURCE_ENV)
            raise unittest.SkipTest("%s 가 없어 Hermes 도구 목록을 확인하지 않는다" % SOURCE_ENV)
        cls.root = pathlib.Path(os.environ[SOURCE_ENV]).resolve()
        # `toolsets.py` 는 표준 라이브러리만 import 한다. 다른 import 가 생기면 여기서 실패해 다시 보게 한다.
        cls.toolsets = runpy.run_path(str(cls.root / "toolsets.py"))

    def test_builtin_tool_names_match(self):
        """Hermes 의 내장 도구 이름 전체가 계약의 목록과 같다. 새 도구는 안전 목록에 넣을지 정한 뒤 목록을 고친다."""
        names = set(self.toolsets["_HERMES_CORE_TOOLS"])
        for toolset in self.toolsets["TOOLSETS"].values():
            names |= set(toolset["tools"])
        self.assertEqual(sorted(names - contract.HERMES_BUILTIN_TOOLS), [], "계약에 없는 새 도구")
        self.assertEqual(sorted(contract.HERMES_BUILTIN_TOOLS - names), [], "Hermes 에서 빠진 도구")

    def test_safe_tools_stay_in_read_toolsets(self):
        """안전 목록의 도구는 지금의 읽기 경계 toolset 에 든다. 도구 묶음이 바뀌면 안전한지 다시 본다."""
        toolsets = self.toolsets["TOOLSETS"]
        for tool, toolset in contract.TOOL_GUARD_SAFE_TOOLSETS.items():
            with self.subTest(tool=tool):
                self.assertIn(tool, toolsets[toolset]["tools"])

    def test_bridge_names_match(self):
        """도구 찾기 중계의 이름이 계약과 같다."""
        path, name, expected = contract.TOOL_GUARD_BRIDGE
        tree = ast.parse((self.root / path).read_text(encoding="utf-8"))
        values = {}
        for node in tree.body:
            if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
                if isinstance(node.value, ast.Constant):
                    values[node.targets[0].id] = node.value.value
                elif node.targets[0].id == name:
                    call = node.value
                    self.assertIsInstance(call, ast.Call)
                    found = frozenset(values.get(element.id) for element in call.args[0].elts)
                    self.assertEqual(found, expected)
                    return
        self.fail("%s 에 %s 가 없다" % (path, name))

    def test_code_execution_calls_have_no_session(self):
        """코드 실행 안의 도구 호출은 `handle_function_call` 에 session 없이 `task_id` 만 넘긴다."""
        for path in contract.TOOL_GUARD_SESSIONLESS_CALLS:
            with self.subTest(path=path):
                tree = ast.parse((self.root / path).read_text(encoding="utf-8"))
                calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call)
                         and getattr(node.func, "id", getattr(node.func, "attr", None)) == "handle_function_call"]
                self.assertTrue(calls, "%s 에 handle_function_call 호출이 없다" % path)
                for call in calls:
                    keywords = {keyword.arg for keyword in call.keywords}
                    self.assertIn("task_id", keywords)
                    self.assertNotIn("session_id", keywords)


if __name__ == "__main__":
    unittest.main()
