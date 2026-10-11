"""관리 owner의 추가 내부 의존을 source AST로만 확인한다. 전체 agent는 실행하지 않는다."""

import ast
import copy
import hashlib
import os
from pathlib import Path
import unittest

import hermes_contract as contract


def definition(tree, qualified):
    nodes = tree.body
    for name in qualified.split("."):
        node = next(item for item in nodes if getattr(item, "name", None) == name)
        nodes = node.body
    return node


def digest(node):
    return hashlib.sha256(ast.dump(node, include_attributes=False).encode()).hexdigest()


def check_function(tree, qualified, expected):
    node = definition(tree, qualified)
    signature, returns, body_hash = expected
    assert ast.unparse(node.args) == signature, qualified + " 인자·기본값·annotation 변경"
    assert (ast.unparse(node.returns) if node.returns else None) == returns, qualified + " 반환 annotation 변경"
    assert digest(node) == body_hash, qualified + " 함수 AST 변경"


class ManagedDeclarationTest(unittest.TestCase):
    def test_surface_is_separate_from_writer_fingerprints(self):
        self.assertEqual(6, len(contract.BODYLESS_MANAGED_FUNCTIONS))
        self.assertEqual(5, len(contract.BODYLESS_REQUEST_FILES))
        self.assertIn("AIAgent.__init__", contract.BODYLESS_MANAGED_FUNCTIONS["run_agent.py"])
        self.assertIn("AIAgent.close", contract.BODYLESS_MANAGED_FUNCTIONS["run_agent.py"])


@unittest.skipUnless(os.environ.get("HERMES_SOURCE"), "HERMES_SOURCE가 없어 관리 source 계약 검사를 건너뛴다")
class ManagedSourceTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(os.environ["HERMES_SOURCE"])

    def tree(self, relative):
        return ast.parse((self.root / relative).read_bytes())

    def test_exact_signatures_and_bounded_function_code(self):
        for relative, functions in contract.BODYLESS_MANAGED_FUNCTIONS.items():
            tree = self.tree(relative)
            for qualified, expected in functions.items():
                with self.subTest(file=relative, symbol=qualified):
                    check_function(tree, qualified, expected)

    def test_context_storage_and_cron_module_entry(self):
        for relative, selectors in contract.BODYLESS_MANAGED_MODULE_NODES.items():
            tree = self.tree(relative)
            for selector, expected in selectors.items():
                with self.subTest(file=relative, selector=selector):
                    nodes = [node for node in tree.body if matches(node, selector)]
                    self.assertEqual(1, len(nodes))
                    self.assertEqual(expected, digest(nodes[0]))

    def test_required_shape_counterexamples_are_rejected(self):
        mutations = (
            ("run_agent.py", "AIAgent.__init__", "initializer_alias"),
            ("tools/delegate_tool.py", "_build_child_agent", "remove_parent"),
            ("tools/delegate_tool.py", "_build_child_agent", "replace_constructor"),
            ("agent/secret_scope.py", "current_secret_scope_home", "global_home"),
            ("hermes_constants.py", "get_hermes_home_override", "global_home"),
            ("cron/scheduler.py", "_launch_external_cron_worker", "global_interpreter"),
            ("cron/scheduler.py", "_run_external_worker_payload", "remove_ack"),
            ("run_agent.py", "AIAgent.__init__", "change_default"),
            ("agent/secret_scope.py", "set_secret_scope", "keyword_only"),
            ("agent/secret_scope.py", "reset_secret_scope", "positional_only"),
            ("agent/secret_scope.py", "reset_secret_scope", "annotation"),
            ("run_agent.py", "AIAgent.close", "decorator"),
        )
        for relative, qualified, mutation in mutations:
            with self.subTest(mutation=mutation, symbol=qualified):
                tree = copy.deepcopy(self.tree(relative))
                node = definition(tree, qualified)
                if mutation == "initializer_alias":
                    node.body = [item for item in node.body if not (
                        isinstance(item, ast.ImportFrom) and item.module == "agent.agent_init")]
                    tree.body.insert(0, ast.ImportFrom(module="agent.agent_init",
                        names=[ast.alias(name="init_agent")], level=0))
                elif mutation == "remove_parent":
                    node.args.args = [arg for arg in node.args.args if arg.arg != "parent_agent"]
                elif mutation == "replace_constructor":
                    calls = [item for item in ast.walk(node) if isinstance(item, ast.Call)
                        and isinstance(item.func, ast.Name) and item.func.id == "AIAgent"]
                    self.assertEqual(1, len(calls))
                    calls[0].func.id = "OtherAgent"
                elif mutation == "global_home":
                    node.body = ast.parse("return GLOBAL_HOME").body
                elif mutation == "global_interpreter":
                    node.body.insert(0, ast.parse("sys.executable = 'managed-python'").body[0])
                elif mutation == "remove_ack":
                    calls = [item for item in ast.walk(node) if isinstance(item, ast.Call)
                        and isinstance(item.func, ast.Attribute) and item.func.attr == "replace"]
                    self.assertEqual(1, len(calls))
                    calls[0].func.attr = "discard_ack"
                elif mutation == "change_default":
                    node.args.defaults[0] = ast.Constant(value="changed")
                elif mutation == "keyword_only":
                    node.args.args.extend(node.args.kwonlyargs)
                    node.args.defaults.extend(node.args.kw_defaults)
                    node.args.kwonlyargs = node.args.kw_defaults = []
                elif mutation == "positional_only":
                    node.args.posonlyargs, node.args.args = node.args.args, []
                elif mutation == "annotation":
                    node.args.args[0].annotation = ast.Name(id="OtherToken")
                elif mutation == "decorator":
                    node.decorator_list.append(ast.Name(id="other_close"))
                with self.assertRaises(AssertionError):
                    check_function(tree, qualified, contract.BODYLESS_MANAGED_FUNCTIONS[relative][qualified])

    def test_module_storage_and_entry_counterexamples_are_rejected(self):
        for relative, selectors in contract.BODYLESS_MANAGED_MODULE_NODES.items():
            for selector, expected in selectors.items():
                with self.subTest(file=relative, selector=selector):
                    node = next(node for node in self.tree(relative).body if matches(node, selector))
                    if selector == "import sys":
                        node.names[0].asname = "other_sys"
                    elif selector == "__main__":
                        node.body = ast.parse("raise SystemExit(0)").body
                    elif isinstance(node, ast.ClassDef):
                        node.body.append(ast.parse("profile_home = None").body[0])
                    else:
                        node.value = ast.Name(id="GLOBAL_STORAGE")
                    self.assertNotEqual(expected, digest(node))


def matches(node, selector):
    if selector == "import sys":
        return isinstance(node, ast.Import) and any(alias.name == "sys" for alias in node.names)
    if selector == "__main__":
        return isinstance(node, ast.If) and ast.unparse(node.test) == "__name__ == '__main__'"
    if isinstance(node, ast.ClassDef):
        return node.name == selector
    if isinstance(node, ast.AnnAssign):
        return isinstance(node.target, ast.Name) and node.target.id == selector
    if isinstance(node, ast.Assign):
        return any(isinstance(target, ast.Name) and target.id == selector for target in node.targets)
    return False
