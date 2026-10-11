"""관리 source AST와 문맥·env loader의 분리 실행을 확인한다. 전체 agent는 실행하지 않는다."""

import ast
import copy
from contextvars import copy_context
import hashlib
import os
from pathlib import Path
import tempfile
from typing import Any
import unittest
from unittest.mock import patch

import hermes_contract as contract


def definition(tree: ast.Module, qualified: str) -> ast.FunctionDef | ast.AsyncFunctionDef | ast.ClassDef:
    if not qualified:
        raise AssertionError("빈 정의 선택자")
    nodes: list[ast.stmt] = tree.body
    node = None
    for name in qualified.split("."):
        node = next((item for item in nodes if isinstance(item,
            (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) and item.name == name), None)
        if node is None:
            raise AssertionError("선택한 정의가 없다: " + qualified)
        nodes = node.body
    if node is None:
        raise AssertionError("빈 정의 선택자")
    return node


def digest(node):
    return hashlib.sha256(ast.dump(node, include_attributes=False).encode()).hexdigest()


def check_function(tree, qualified, expected):
    node = definition(tree, qualified)
    assert isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)), qualified
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

    def test_definition_rejects_empty_or_missing_selector(self):
        for selector in ("", "absent", "A.absent"):
            with self.subTest(selector=selector), self.assertRaises(AssertionError):
                definition(ast.parse("class A: pass"), selector)


@unittest.skipUnless(os.environ.get("HERMES_SOURCE"), "HERMES_SOURCE가 없어 관리 source 계약 검사를 건너뛴다")
class ManagedSourceTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(os.environ["HERMES_SOURCE"])

    def tree(self, relative: str) -> ast.Module:
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

    def test_context_bindings_have_no_module_redefinitions(self):
        for relative in ("hermes_constants.py", "agent/secret_scope.py"):
            with self.subTest(file=relative):
                check_context_bindings(self.tree(relative), relative)

    def test_context_string_targets_are_rejected_but_local_targets_are_allowed(self):
        for relative in ("hermes_constants.py", "agent/secret_scope.py"):
            for source in (
                    "try:\n    pass\nexcept Exception as ContextVar:\n    pass",
                    "match None:\n    case ContextVar:\n        pass",
                    "match [None]:\n    case [ContextVar as other]:\n        pass",
                    "match []:\n    case [*ContextVar]:\n        pass",
                    "match {}:\n    case {**ContextVar}:\n        pass"):
                with self.subTest(file=relative, source=source):
                    tree = self.tree(relative)
                    tree.body.extend(ast.parse(source).body)
                    with self.assertRaises(AssertionError):
                        check_context_bindings(tree, relative)
                    for header in ("def local():", "async def local():", "class Local:"):
                        local_tree = self.tree(relative)
                        local_tree.body.extend(ast.parse(header + "\n" + "\n".join(
                            "    " + line for line in source.splitlines())).body)
                        check_context_bindings(local_tree, relative)

    def test_actual_context_functions_isolate_and_restore_tokens(self):
        for relative in ("hermes_constants.py", "agent/secret_scope.py"):
            tree = self.tree(relative)
            selectors = contract.BODYLESS_MANAGED_MODULE_NODES[relative]
            functions = contract.BODYLESS_MANAGED_FUNCTIONS[relative]
            # 표준 import와 선언한 문맥 노드만 실행한다. 전체 Hermes는 import하지 않는다.
            selected: list[ast.stmt] = [node for node in tree.body if
                isinstance(node, ast.ImportFrom) and node.module in (
                    "__future__", "contextvars", "pathlib", "typing") or
                any(matches(node, selector) for selector in selectors) or
                isinstance(node, ast.FunctionDef) and node.name in functions]
            namespace: dict[str, Any] = {}
            exec(compile(ast.Module(body=selected, type_ignores=[]), relative, "exec"), namespace)
            if relative == "hermes_constants.py":
                set_scope = namespace["set_hermes_home_override"]
                get_scope = namespace["get_hermes_home_override"]
                reset_scope = namespace["reset_hermes_home_override"]
                bind = lambda value: set_scope(value)
            else:
                set_scope = namespace["set_secret_scope"]
                get_scope = namespace["current_secret_scope_home"]
                reset_scope = namespace["reset_secret_scope"]
                bind = lambda value: set_scope({"SYNTHETIC_SECRET": value}, profile_home=value)
            self.assertIsNone(get_scope())
            token = bind("profile-A")
            try:
                def child():
                    self.assertEqual("profile-A", get_scope())
                    child_token = bind("profile-B")
                    try:
                        self.assertEqual("profile-B", get_scope())
                    finally:
                        reset_scope(child_token)
                    self.assertEqual("profile-A", get_scope())
                copy_context().run(child)
                self.assertEqual("profile-A", get_scope())
            finally:
                reset_scope(token)
            self.assertIsNone(get_scope())

    def test_factory_and_loader_counterexamples_are_rejected(self):
        for relative in ("hermes_constants.py", "agent/secret_scope.py"):
            for replacement in ("ContextVar = GLOBAL_STORAGE", "from other_store import ContextVar",
                    "if True:\n    ContextVar = GLOBAL_STORAGE", "Token = object",
                    "from contextvars import ContextVar as OtherVar, Token"):
                with self.subTest(file=relative, replacement=replacement):
                    tree = self.tree(relative)
                    tree.body.extend(ast.parse(replacement).body)
                    with self.assertRaises(AssertionError):
                        check_context_bindings(tree, relative)
        tree = self.tree("agent/secret_scope.py")
        node = definition(tree, "load_env_file")
        node.body.insert(0, ast.parse("return dict(os.environ)").body[0])
        with self.assertRaises(AssertionError):
            check_function(tree, "load_env_file",
                contract.BODYLESS_MANAGED_FUNCTIONS["agent/secret_scope.py"]["load_env_file"])

    def test_actual_env_loader_keeps_explicit_home_and_return_values_separate(self):
        tree = self.tree("agent/secret_scope.py")
        helpers = {"load_env_file", "invalidate_env_file_cache", "_decode_env_bytes",
            "_parse_env_text", "_parse_env_value", "_strip_inline_comment"}
        cache = {"_ENV_FILE_CACHE", "_ENV_FILE_CACHE_LOCK", "_ENV_FILE_CACHE_MAX"}
        selected: list[ast.stmt] = [node for node in tree.body if
            isinstance(node, ast.Import) and all(alias.name in ("codecs", "os", "re", "threading")
                for alias in node.names) or
            isinstance(node, ast.ImportFrom) and node.module in ("__future__", "collections", "pathlib", "typing") or
            any(matches(node, selector) for selector in cache) or
            isinstance(node, ast.FunctionDef) and node.name in helpers]
        namespace: dict[str, Any] = {"os": os}
        # stat helper도 실제 source에서 선택한다. 전이 의존의 AST 지문을 늘리지는 않는다.
        signature = definition(self.tree("utils.py"), "file_signature")
        selected.append(signature)
        exec(compile(ast.Module(body=selected, type_ignores=[]), "<actual-env-loader>", "exec"), namespace)
        load = namespace["load_env_file"]
        with tempfile.TemporaryDirectory(prefix="fos-contract-env-") as folder:
            homes = [Path(folder) / name for name in ("profile-A", "profile-B")]
            for home, value in zip(homes, ("synthetic-A", "synthetic-B")):
                home.mkdir()
                (home / ".env").write_text("SYNTHETIC_SECRET=" + value + "\n")
            with patch.dict(os.environ, {"SYNTHETIC_SECRET": "synthetic-global"}, clear=True):
                before = dict(os.environ)
                first = load(homes[0] / ".env")
                second = load(homes[1] / ".env")
                self.assertEqual({"SYNTHETIC_SECRET": "synthetic-A"}, first)
                self.assertEqual({"SYNTHETIC_SECRET": "synthetic-B"}, second)
                first["SYNTHETIC_SECRET"] = "caller-change"
                self.assertEqual({"SYNTHETIC_SECRET": "synthetic-A"}, load(homes[0] / ".env"))
                self.assertEqual(second, load(homes[1] / ".env"))
                self.assertEqual({}, load(Path(folder) / "missing" / ".env"))
                self.assertEqual(before, dict(os.environ))

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
                assert isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
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
                    assert isinstance(calls[0].func, ast.Name)
                    calls[0].func.id = "OtherAgent"
                elif mutation == "global_home":
                    node.body = ast.parse("return GLOBAL_HOME").body
                elif mutation == "global_interpreter":
                    node.body.insert(0, ast.parse("sys.executable = 'managed-python'").body[0])
                elif mutation == "remove_ack":
                    calls = [item for item in ast.walk(node) if isinstance(item, ast.Call)
                        and isinstance(item.func, ast.Attribute) and item.func.attr == "replace"]
                    self.assertEqual(1, len(calls))
                    assert isinstance(calls[0].func, ast.Attribute)
                    calls[0].func.attr = "discard_ack"
                elif mutation == "change_default":
                    node.args.defaults[0] = ast.Constant(value="changed")
                elif mutation == "keyword_only":
                    node.args.args.extend(node.args.kwonlyargs)
                    node.args.defaults.extend(value for value in node.args.kw_defaults if value is not None)
                    node.args.kwonlyargs = []
                    node.args.kw_defaults = []
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
                        assert isinstance(node, ast.Import)
                        node.names[0].asname = "other_sys"
                    elif selector == "from contextvars":
                        assert isinstance(node, ast.ImportFrom)
                        node.names[0].asname = "other_contextvar"
                    elif selector == "__main__":
                        assert isinstance(node, ast.If)
                        node.body = ast.parse("raise SystemExit(0)").body
                    elif isinstance(node, ast.ClassDef):
                        node.body.append(ast.parse("profile_home = None").body[0])
                    else:
                        assert isinstance(node, (ast.Assign, ast.AnnAssign))
                        node.value = ast.Name(id="GLOBAL_STORAGE")
                    self.assertNotEqual(expected, digest(node))


def matches(node, selector):
    if selector == "from contextvars":
        return isinstance(node, ast.ImportFrom) and node.module == "contextvars"
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


def check_context_bindings(tree: ast.Module, relative: str):
    """문맥 module의 선언 이름은 각 한 번만 bind한다. 함수·class 내부는 별도 scope다."""
    selectors = contract.BODYLESS_MANAGED_MODULE_NODES[relative]
    protected = {"ContextVar", "Token", *selectors.keys(), *contract.BODYLESS_MANAGED_FUNCTIONS[relative]}
    protected.discard("from contextvars")
    counts = dict.fromkeys(protected, 0)

    class Bindings(ast.NodeVisitor):
        def bound(self, name: str):
            if name in counts:
                counts[name] += 1

        def visit_Name(self, node: ast.Name):
            if isinstance(node.ctx, (ast.Store, ast.Del)):
                self.bound(node.id)

        def visit_ImportFrom(self, node: ast.ImportFrom):
            assert all(alias.name != "*" for alias in node.names), "문맥 module의 wildcard import"
            for alias in node.names:
                self.bound(alias.asname or alias.name)

        def visit_Import(self, node: ast.Import):
            for alias in node.names:
                self.bound(alias.asname or alias.name.split(".")[0])

        def visit_FunctionDef(self, node: ast.FunctionDef):
            self.bound(node.name)

        def visit_AsyncFunctionDef(self, node: ast.AsyncFunctionDef):
            self.bound(node.name)

        def visit_ClassDef(self, node: ast.ClassDef):
            self.bound(node.name)

        def visit_ExceptHandler(self, node: ast.ExceptHandler):
            if node.name:
                self.bound(node.name)
            self.generic_visit(node)

        def visit_MatchAs(self, node: ast.MatchAs):
            if node.name:
                self.bound(node.name)
            self.generic_visit(node)

        def visit_MatchStar(self, node: ast.MatchStar):
            if node.name:
                self.bound(node.name)

        def visit_MatchMapping(self, node: ast.MatchMapping):
            if node.rest:
                self.bound(node.rest)
            self.generic_visit(node)

    Bindings().visit(tree)
    assert all(count == 1 for count in counts.values()), (relative, counts)
    imports = [node for node in tree.body if matches(node, "from contextvars")]
    assert len(imports) == 1, relative
    assert digest(imports[0]) == selectors["from contextvars"], relative
