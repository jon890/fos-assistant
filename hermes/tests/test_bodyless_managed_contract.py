"""관리 source AST와 scope 묶기·env loader의 분리 실행을 확인한다. 전체 agent는 실행하지 않는다."""

import ast
import copy
from contextvars import copy_context
import hashlib
import os
from pathlib import Path
import sys
import tempfile
import types
from typing import Any
import unittest
from unittest.mock import patch

import hermes_contract as contract
from bodyless_source import SKIP_REASON, bodyless_source
import test_bodyless_requests  # noqa: F401  addon 경로를 sys.path 에 둔다
from runtime.bodyless_requests import make_writer


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
        self.assertFalse(set(contract.BODYLESS_MANAGED_FUNCTIONS) & set(contract.BODYLESS_REQUEST_FILES))
        self.assertIn("AIAgent.__init__", contract.BODYLESS_MANAGED_FUNCTIONS["run_agent.py"])
        self.assertIn("AIAgent.close", contract.BODYLESS_MANAGED_FUNCTIONS["run_agent.py"])
        self.assertIn("bind_profile_scope", contract.BODYLESS_MANAGED_FUNCTIONS["agent/runtime_scope.py"])
        self.assertIn("current_scope", contract.BODYLESS_MANAGED_FUNCTIONS["hermes_scope.py"])
        self.assertEqual(contract.BODYLESS_SOURCE_LINE[1], "agent/runtime_scope.py")

    def test_legacy_setters_are_not_expected(self):
        # v0.23 의 옛 setter 는 묶인 scope 를 옮기거나 거절한다. 관리 owner 가 기대는 경계에 두지 않는다.
        declared = {name for functions in contract.BODYLESS_MANAGED_FUNCTIONS.values() for name in functions}
        for legacy in ("set_secret_scope", "reset_secret_scope", "current_secret_scope_home",
                       "set_hermes_home_override", "get_hermes_home_override", "reset_hermes_home_override"):
            self.assertNotIn(legacy, declared)

    def test_definition_rejects_empty_or_missing_selector(self):
        for selector in ("", "absent", "A.absent"):
            with self.subTest(selector=selector), self.assertRaises(AssertionError):
                definition(ast.parse("class A: pass"), selector)


def _key(value) -> str:
    return os.path.realpath(str(value))


class ActualScope:
    """실제 `hermes_scope` 와 `agent/runtime_scope.py` 전체를 module 로 실행한다.

    두 파일 밖의 profile 적재(secret 파일 읽기, 외부 secret 채우기, terminal 정책)는 대역이다.
    대역은 부를 때의 scope 상태를 기록해 묶기가 대상 profile 의 home 아래에서 값을 만드는지 보인다.
    """

    def __init__(self, root: Path, launch_home: Path):
        self.launch_home = launch_home
        self.hydrated: list[tuple[str, str | None]] = []
        self.policies: list[tuple[str, str | None]] = []
        self.scope = types.ModuleType("hermes_scope")
        self.runtime = types.ModuleType("agent.runtime_scope")
        constants = types.ModuleType("hermes_constants")
        constants.hermes_home_key = _key
        constants.get_process_hermes_home = lambda: launch_home
        secret_scope = types.ModuleType("agent.secret_scope")
        secret_scope._is_process_home = lambda home: _key(home) == _key(launch_home)
        terminal = types.ModuleType("tools.terminal_scope")
        terminal.prepare_profile_terminal_scope = self._policy
        launch = types.ModuleType("tui_gateway.launch_profile_policy")
        launch.owner_secret_scope = lambda home: {"SYNTHETIC_SECRET": "owner:" + Path(home).name}
        launch.launch_secret_scope = lambda home: {"SYNTHETIC_SECRET": "launch:" + Path(home).name}
        launch.launch_terminal_env = lambda: {}
        env_loader = types.ModuleType("hermes_cli.env_loader")
        env_loader.hydrate_profile_secret_sources = lambda home: self.hydrated.append((str(home), self.home()))
        self.modules = {module.__name__: module for module in (
            self.scope, self.runtime, constants, secret_scope, terminal, launch, env_loader)}
        self.sources = ((self.scope, root / "hermes_scope.py"), (self.runtime, root / "agent/runtime_scope.py"))

    def __enter__(self):
        self._patch = patch.dict(sys.modules, self.modules)
        self._patch.__enter__()
        for module, source in self.sources:
            module.__file__ = str(source)
            exec(compile(source.read_bytes(), str(source), "exec", dont_inherit=True), module.__dict__)
        return self

    def __exit__(self, *exc):
        return self._patch.__exit__(*exc)

    def _policy(self, home, env_overlay=None):
        self.policies.append((str(home), self.home()))
        return {"TERMINAL_HOME": str(home)}

    def home(self) -> str | None:
        return self.scope.current_state().home

    def snapshot(self) -> tuple[str | None, str | None, str | None, str | None, Any]:
        """scope 의 home, home override, secret 의 stamp 와 값, terminal 정책을 한 번에 읽는다."""
        state = self.scope.current_state()
        secrets = state.secrets
        return (state.scope.home() if state.scope is not None else None, state.home,
                secrets.profile_home if secrets is not None else None,
                secrets.mapping.get("SYNTHETIC_SECRET") if secrets is not None else None,
                state.terminal)


@unittest.skipUnless(bodyless_source(), SKIP_REASON)
class ManagedSourceTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(os.environ["HERMES_SOURCE"])

    def tree(self, relative: str) -> ast.Module:
        return ast.parse((self.root / relative).read_bytes())

    def homes(self):
        folder = tempfile.TemporaryDirectory(prefix="fos-contract-scope-")
        self.addCleanup(folder.cleanup)
        homes = [Path(folder.name) / name for name in ("launch", "profile-A", "profile-B")]
        for home in homes:
            home.mkdir()
        return homes

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
        for relative in contract.BODYLESS_CONTEXT_MODULES:
            with self.subTest(file=relative):
                check_context_bindings(self.tree(relative), relative)

    def test_context_string_targets_are_rejected_but_local_targets_are_allowed(self):
        for relative in contract.BODYLESS_CONTEXT_MODULES:
            for source in (
                    "try:\n    pass\nexcept Exception as ContextVar:\n    pass",
                    "match None:\n    case ContextVar:\n        pass",
                    "match [None]:\n    case [ContextVar as other]:\n        pass",
                    "match []:\n    case [*ContextVar]:\n        pass",
                    "match {}:\n    case {**ContextVar}:\n        pass",
                    "try:\n    pass\nexcept Exception as _STATE:\n    pass"):
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

    def test_actual_profile_binds_isolate_contexts_and_restore_on_exit(self):
        launch, home_a, home_b = self.homes()
        with ActualScope(self.root, launch) as actual:
            bind = actual.runtime.bind_profile_scope
            self.assertEqual((None, None, None, None, None), actual.snapshot())
            with bind(home_a) as scope_a:
                expected_a = (str(home_a), str(home_a), str(home_a), "owner:profile-A", {"TERMINAL_HOME": str(home_a)})
                self.assertEqual(expected_a, actual.snapshot())
                self.assertIs(scope_a, actual.scope.current_scope())
                self.assertTrue(scope_a.is_local)

                def child():
                    self.assertEqual(expected_a, actual.snapshot())
                    with bind(home_b):
                        self.assertEqual((str(home_b), str(home_b), str(home_b), "owner:profile-B",
                                          {"TERMINAL_HOME": str(home_b)}), actual.snapshot())
                    self.assertEqual(expected_a, actual.snapshot())
                copy_context().run(child)
                self.assertEqual(expected_a, actual.snapshot())
                with bind(home_b):
                    self.assertEqual("owner:profile-B", actual.snapshot()[3])
                self.assertEqual(expected_a, actual.snapshot())
                # 같은 profile 을 다시 묶으면 같은 scope 를 쓴다.
                with bind(home_a) as again:
                    self.assertIs(scope_a, again)
            self.assertEqual((None, None, None, None, None), actual.snapshot())
            # 실행 profile 은 stamp 없이 launch 값으로 묶인다. scope 의 home 은 그대로 그 profile 이다.
            with bind(launch):
                self.assertEqual((str(launch), str(launch), None, "launch:launch"), actual.snapshot()[:4])
        # secret 채우기와 terminal 정책은 대상 profile 의 home 아래에서 만든다.
        self.assertIn((str(home_a), str(home_a)), actual.hydrated)
        self.assertIn((str(home_b), str(home_b)), actual.policies)

    def test_actual_tokens_never_leave_a_mixed_profile_state(self):
        launch, home_a, home_b = self.homes()
        with ActualScope(self.root, launch) as actual:
            scope_for, bind_state = actual.runtime.scope_for_profile, actual.runtime.bind_state
            token_a = bind_state(scope_for(home_a))
            token_b = bind_state(scope_for(home_b))
            self.assertEqual("owner:profile-B", actual.snapshot()[3])
            # 순서가 어긋난 reset: A 를 먼저 풀면 A 안에서 묶은 B 도 함께 빠진다.
            token_a.reset()
            self.assertEqual((None, None, None, None, None), actual.snapshot())
            token_b.reset()
            token_a.reset()
            self.assertEqual((None, None, None, None, None), actual.snapshot())
            # token 은 context manager 다. 안에서 묶은 B 를 풀면 A 가 그대로 남는다.
            with bind_state(scope_for(home_a)):
                inner = bind_state(scope_for(home_b))
                inner.reset()
                self.assertEqual("owner:profile-A", actual.snapshot()[3])
            self.assertEqual((None, None, None, None, None), actual.snapshot())

    def test_writer_rejects_a_capability_outside_its_profile_scope(self):
        launch, home_a, home_b = self.homes()
        folder = tempfile.TemporaryDirectory(prefix="fos-contract-diagnostics-")
        self.addCleanup(folder.cleanup)
        # writer 는 경로의 symlink 를 따라가지 않는다. macOS 임시 폴더의 symlink 를 풀어 둔다.
        directory = Path(os.path.realpath(folder.name))
        agent_a, unknown = object(), object()
        with ActualScope(self.root, launch) as actual:
            # 계약이 정한 resolver 꼴: 등록된 agent 의 profile 과 지금 묶인 local scope 의 home 이 같을 때만 목적지를 준다.
            registry = {id(agent_a): str(home_a)}

            def resolver(agent):
                home = registry.get(id(agent))
                scope = actual.scope.current_scope()
                if home is None or scope is None or not scope.is_local or _key(scope.home()) != _key(home):
                    return None
                return str(directory)
            writer = make_writer(resolver)
            payload = {"messages": [{"role": "user", "content": "synthetic"}]}

            def written():
                return sorted(directory.glob("request_diagnostic_*.json"))
            self.assertIsNone(writer(agent_a, payload, reason="preflight"))
            with actual.runtime.bind_profile_scope(home_b):
                self.assertIsNone(writer(agent_a, payload, reason="preflight"))
            with actual.runtime.bind_profile_scope(home_a):
                self.assertIsNone(writer(unknown, payload, reason="preflight"))
                self.assertEqual([], written())
                self.assertIsNotNone(writer(agent_a, payload, reason="preflight"))
                with actual.runtime.bind_profile_scope(home_b):
                    self.assertIsNone(writer(agent_a, payload, reason="preflight"))
            self.assertEqual(1, len(written()))

    def test_factory_and_loader_counterexamples_are_rejected(self):
        for relative in contract.BODYLESS_CONTEXT_MODULES:
            for replacement in ("ContextVar = GLOBAL_STORAGE", "from other_store import ContextVar",
                    "if True:\n    ContextVar = GLOBAL_STORAGE", "_STATE = GLOBAL_STORAGE",
                    "def current_state():\n    return GLOBAL_STATE",
                    "from contextvars import ContextVar as OtherVar"):
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
            ("hermes_scope.py", "current_scope", "global_home"),
            ("hermes_scope.py", "current_state", "global_home"),
            ("agent/runtime_scope.py", "bind_profile_scope", "global_home"),
            ("cron/scheduler.py", "_launch_external_cron_worker", "global_interpreter"),
            ("cron/scheduler.py", "_run_external_worker_payload", "remove_ack"),
            ("run_agent.py", "AIAgent.__init__", "change_default"),
            ("agent/runtime_scope.py", "bind_profile_scope", "keyword_only"),
            ("hermes_scope.py", "pop_state", "positional_only"),
            ("hermes_scope.py", "pop_state", "annotation"),
            ("run_agent.py", "AIAgent.close", "decorator"),
            ("agent/runtime_scope.py", "bind_profile_scope", "decorator"),
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
                    node.decorator_list.append(ast.Name(id="other_wrapper"))
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
    protected = {*contract.BODYLESS_CONTEXT_MODULES[relative], *selectors.keys(),
                 *contract.BODYLESS_MANAGED_FUNCTIONS[relative]}
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
