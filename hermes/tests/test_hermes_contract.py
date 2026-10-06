"""dashboard-profile-api 가 기대는 Hermes 내부 지점이 그대로인지 본다.

선언은 `hermes_contract.py` 가 갖는다. 근거는 ADR-088 이 갖는다.

`PluginDeclarationTest` 는 Hermes 없이 늘 돈다. plugin 이 쓰는 Hermes 이름과 선언이 맞는지 본다.
`HermesSourceTest` 는 `HERMES_SOURCE` 에 Hermes 소스 디렉터리를 줄 때만 돈다.
그 소스를 import 하지 않고 구문만 읽는다. Hermes 의 의존성을 설치하지 않아도 된다.

```bash
# cwd: 저장소 root
# 계약의 판이나 지정한 tag 의 소스를 받아 확인한다
scripts/check-hermes-contract.sh
scripts/check-hermes-contract.sh v2026.10.1
```
"""

import ast
import os
import pathlib
import re
import sqlite3
import sys
import unittest
import warnings

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import hermes_contract as contract  # noqa: E402


PLUGIN_DIR = pathlib.Path(__file__).resolve().parents[1] / "plugins" / "dashboard-profile-api"
SOURCE_ENV = "HERMES_SOURCE"
ROUTE_METHODS = frozenset({"get", "post", "put", "delete", "patch"})
PATH_PARAM_RE = re.compile(r"\{[^}]*\}")


def _parse(path):
    # Hermes 소스의 잘못된 escape 경고는 계약과 무관하다. 검사 출력에 섞지 않는다.
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", SyntaxWarning)
        return ast.parse(pathlib.Path(path).read_text(encoding="utf-8"), filename=str(path))


def _plugin_trees():
    """plugin 디렉터리의 Python 파일을 모두 읽는다. 모듈을 나눠도 같은 검사가 돈다."""
    return {path.name: _parse(path) for path in sorted(PLUGIN_DIR.glob("*.py"))}


def _is_hermes(node):
    """Hermes 의 이름을 가져오는 import 인지 본다. plugin 안의 상대 import(`from .x import y`)는 세지 않는다."""
    return node.level == 0 and node.module is not None and node.module.split(".")[0] in contract.HERMES_PACKAGES


def _plugin_uses(tree):
    """plugin 이 쓰는 Hermes 이름과, 그 이름을 부른 모양의 목록을 돌려준다.

    `from a import b as c` 에서 `c.x` 로 쓰면 `b` 는 모듈로 보고 `(a.b, x)` 를 쓴 것으로 센다.
    """
    attrs = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name):
            attrs.setdefault(node.value.id, set()).add(node.attr)

    bound = {}
    for node in ast.walk(tree):
        if not isinstance(node, ast.ImportFrom) or not _is_hermes(node):
            continue
        for alias in node.names:
            local = alias.asname or alias.name
            if local in attrs:
                for attr in attrs[local]:
                    bound[(local, attr)] = ("%s.%s" % (node.module, alias.name), attr)
            else:
                bound[(local, None)] = (node.module, alias.name)

    calls = {}
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        func = node.func
        if isinstance(func, ast.Name):
            key = bound.get((func.id, None))
        elif isinstance(func, ast.Attribute) and isinstance(func.value, ast.Name):
            key = bound.get((func.value.id, func.attr))
        else:
            key = None
        if key is not None:
            calls.setdefault(key, set()).add(
                (len(node.args), frozenset(keyword.arg for keyword in node.keywords)))
    return set(bound.values()), calls, attrs


def _provider_methods(tree):
    """`DashboardAuthProvider` 를 상속한 class 의 메서드와 키워드 인자를 읽는다."""
    for node in ast.walk(tree):
        if (isinstance(node, ast.ClassDef)
                and any(isinstance(base, ast.Name) and base.id == "DashboardAuthProvider" for base in node.bases)):
            return {item.name: tuple(arg.arg for arg in item.args.kwonlyargs)
                    for item in node.body if isinstance(item, ast.FunctionDef) and not item.name.startswith("_")}
    return None


def _module_constant(tree, name):
    for node in tree.body:
        if (isinstance(node, ast.Assign) and len(node.targets) == 1
                and isinstance(node.targets[0], ast.Name) and node.targets[0].id == name):
            return ast.literal_eval(node.value)
    raise AssertionError("plugin 에 %s 가 없다" % name)


class PluginDeclarationTest(unittest.TestCase):
    """plugin 이 쓰는 Hermes 지점과 선언이 서로 맞는지 본다. Hermes 를 올릴 때 확인할 목록이 빠지지 않게 한다."""

    @classmethod
    def setUpClass(cls):
        cls.trees = _plugin_trees()
        cls.used = set()
        cls.calls = {}
        cls.attrs = {}
        for tree in cls.trees.values():
            used, calls, attrs = _plugin_uses(tree)
            cls.used |= used
            for key, shapes in calls.items():
                cls.calls.setdefault(key, set()).update(shapes)
            for name, values in attrs.items():
                cls.attrs.setdefault(name, set()).update(values)

    def test_every_hermes_name_is_declared(self):
        missing = sorted(self.used - set(contract.SYMBOLS))
        self.assertEqual([], missing, "plugin 이 쓰는 Hermes 이름을 hermes_contract.SYMBOLS 에 더한다")

    def test_every_declared_name_is_used(self):
        stale = []
        for module, name in contract.SYMBOLS:
            if "." in name:
                # class 안의 메서드다. plugin 은 Hermes 가 넘긴 객체로 부른다.
                if not any(name.split(".")[1] in values for values in self.attrs.values()):
                    stale.append((module, name))
            elif (module, name) not in self.used:
                stale.append((module, name))
        self.assertEqual([], stale, "plugin 이 더는 쓰지 않는 이름은 선언에서 뺀다")

    def test_calls_match_declared_shape(self):
        for key, shapes in sorted(self.calls.items()):
            declared = contract.SYMBOLS[key]
            with self.subTest(symbol=key):
                self.assertIsInstance(declared, contract.Call, "부르는 이름은 Call 로 선언한다")
                self.assertEqual({(declared.positional, frozenset(declared.keywords))}, shapes)

    def test_provider_methods_match_declaration(self):
        found = [_provider_methods(tree) for tree in self.trees.values()]
        found = [methods for methods in found if methods is not None]
        self.assertEqual([contract.PROVIDER_METHODS], found)

    def test_wrapped_routes_are_declared(self):
        routes = set()
        for tree in self.trees.values():
            for node in tree.body:
                if (isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name)
                        and node.targets[0].id == "ALLOWED_ROUTES"):
                    routes |= {(method, path) for path, method in ast.literal_eval(node.value)}
        self.assertTrue(routes, "ALLOWED_ROUTES 를 찾지 못했다")
        self.assertEqual(set(), routes - contract.HERMES_ROUTES)

    def test_session_db_file_matches(self):
        values = {_module_constant(tree, "SESSION_DB_FILE") for tree in self.trees.values()
                  if any(isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name)
                         and node.targets[0].id == "SESSION_DB_FILE" for node in tree.body)}
        self.assertEqual({contract.SESSION_DB_FILE}, values)


def _top_statements(body):
    """모듈이나 class 머리의 문장을 돌려준다. `if`, `try` 안은 들어가고 함수 안은 들어가지 않는다."""
    for node in body:
        yield node
        if isinstance(node, (ast.If, ast.Try)):
            for part in (node.body, node.orelse, getattr(node, "finalbody", []),
                         *[handler.body for handler in getattr(node, "handlers", [])]):
                yield from _top_statements(part)


def _defined_names(node):
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
        return {node.name}
    if isinstance(node, ast.Assign):
        return {target.id for target in node.targets if isinstance(target, ast.Name)}
    if isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
        return {node.target.id}
    return set()


def _params(func, method):
    args = func.args
    positional = list(args.posonlyargs) + list(args.args)
    if method and positional:
        positional = positional[1:]
    return positional, args


def _accepts(func, call, method=False):
    """Hermes 함수가 plugin 의 호출 모양을 받는지 본다. 받지 못하면 까닭을 돌려준다."""
    positional, args = _params(func, method)
    if call.is_async != isinstance(func, ast.AsyncFunctionDef):
        return "async 여부가 다르다"
    if call.positional > len(positional) and args.vararg is None:
        return "위치 인자 %d 개를 받지 못한다" % call.positional
    keyword_names = {arg.arg for arg in args.args} | {arg.arg for arg in args.kwonlyargs}
    if method and args.args:
        keyword_names.discard(args.args[0].arg)
    for keyword in call.keywords:
        if keyword not in keyword_names and args.kwarg is None:
            return "키워드 인자 %s 를 받지 못한다" % keyword
    required = positional[:len(positional) - len(args.defaults)] if args.defaults else positional
    for arg in required[call.positional:]:
        if arg.arg not in call.keywords:
            return "필수 인자 %s 를 주지 않는다" % arg.arg
    for arg, default in zip(args.kwonlyargs, args.kw_defaults):
        if default is None and arg.arg not in call.keywords:
            return "필수 키워드 인자 %s 를 주지 않는다" % arg.arg
    return None


@unittest.skipUnless(os.environ.get(SOURCE_ENV), "%s 가 없어 Hermes 소스 계약을 확인하지 않는다" % SOURCE_ENV)
class HermesSourceTest(unittest.TestCase):
    """`HERMES_SOURCE` 의 Hermes 소스에서 선언한 지점이 그대로인지 본다."""

    @classmethod
    def setUpClass(cls):
        cls.root = pathlib.Path(os.environ[SOURCE_ENV]).resolve()
        if not (cls.root / "hermes_cli").is_dir():
            raise AssertionError("%s 에 hermes_cli 가 없다. Hermes 소스 디렉터리를 준다" % cls.root)
        cls.trees = {}

    def tree(self, path):
        path = pathlib.Path(path)
        if path not in self.trees:
            self.trees[path] = _parse(path)
        return self.trees[path]

    def module_path(self, module):
        base = self.root.joinpath(*module.split("."))
        for candidate in (base.with_suffix(".py"), base / "__init__.py"):
            if candidate.is_file():
                return candidate
        return None

    def resolve(self, module, name, depth=0):
        """모듈 머리에서 그 이름의 정의를 찾는다. 다시 내보낸 이름은 원래 모듈까지 따라간다."""
        path = self.module_path(module)
        if path is None or depth > 5:
            return None
        package = module if path.name == "__init__.py" else module.rpartition(".")[0]
        for node in _top_statements(self.tree(path).body):
            if name in _defined_names(node):
                return node
            if isinstance(node, ast.ImportFrom):
                for alias in node.names:
                    if (alias.asname or alias.name) != name:
                        continue
                    source = node.module or ""
                    if node.level:
                        parent = package.split(".")
                        parent = parent[:len(parent) - node.level + 1]
                        source = ".".join(part for part in parent + [source] if part)
                    found = self.resolve(source, alias.name, depth + 1)
                    if found is None and self.module_path("%s.%s" % (source, alias.name)):
                        return node
                    return found
        return None

    def definition(self, module, name):
        owner, _, member = name.partition(".")
        node = self.resolve(module, owner)
        self.assertIsNotNone(node, "%s.%s 가 없다" % (module, owner))
        if member:
            self.assertIsInstance(node, ast.ClassDef)
            methods = [item for item in node.body
                       if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)) and item.name == member]
            self.assertTrue(methods, "%s.%s 가 없다" % (module, name))
            return methods[0]
        return node

    def test_symbols(self):
        for (module, name), shape in contract.SYMBOLS.items():
            with self.subTest(symbol="%s.%s" % (module, name)):
                node = self.definition(module, name)
                if isinstance(shape, contract.Value):
                    continue
                if isinstance(shape, contract.Base):
                    self.check_base(node, shape)
                    continue
                if isinstance(node, ast.ClassDef):
                    self.check_constructor(node, shape)
                    continue
                self.assertIsInstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
                self.assertIsNone(_accepts(node, shape, method="." in name))

    def check_constructor(self, node, call):
        init = [item for item in node.body if isinstance(item, ast.FunctionDef) and item.name == "__init__"]
        if init:
            self.assertIsNone(_accepts(init[0], call, method=True))
            return
        # dataclass 다. 주석이 달린 칸이 생성자 인자다.
        fields = [item for item in node.body if isinstance(item, ast.AnnAssign) and isinstance(item.target, ast.Name)]
        names = {item.target.id for item in fields}
        self.assertEqual(set(), set(call.keywords) - names)
        required = {item.target.id for item in fields if item.value is None}
        self.assertEqual(set(), required - set(call.keywords))

    def check_base(self, node, shape):
        self.assertIsInstance(node, ast.ClassDef)
        methods = {item.name: item for item in node.body if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef))}
        abstract = {name for name, item in methods.items()
                    if any((isinstance(decorator, ast.Name) and decorator.id == "abstractmethod")
                           or (isinstance(decorator, ast.Attribute) and decorator.attr == "abstractmethod")
                           for decorator in item.decorator_list)}
        self.assertEqual(set(), abstract - set(shape.methods), "plugin 이 구현하지 않은 추상 메서드가 생겼다")
        for name, keywords in shape.methods.items():
            if name in methods:
                self.assertEqual(keywords, tuple(arg.arg for arg in methods[name].args.kwonlyargs),
                                 "%s 의 키워드 인자가 바뀌었다" % name)
        attributes = set()
        for item in node.body:
            attributes |= _defined_names(item) if isinstance(item, (ast.Assign, ast.AnnAssign)) else set()
        self.assertEqual(set(), set(shape.attributes) - attributes)

    def test_middleware_is_imported_per_request(self):
        relative, module, name = contract.PER_REQUEST_IMPORT
        tree = self.tree(self.root / relative)

        def imports(nodes):
            return [node for node in nodes if isinstance(node, ast.ImportFrom) and node.module == module
                    and any(alias.name == name for alias in node.names)]

        self.assertEqual([], imports(_top_statements(tree.body)),
                         "모듈 머리에서 import 하면 바꿔 끼운 함수가 쓰이지 않는다")
        inside = [node for function in ast.walk(tree) if isinstance(function, (ast.FunctionDef, ast.AsyncFunctionDef))
                  for node in imports(ast.walk(function))]
        self.assertTrue(inside, "요청마다 %s 를 import 하는 곳이 없다" % name)

    def test_state_flag_is_read(self):
        flag, files = contract.STATE_FLAG
        for relative in files:
            with self.subTest(file=relative):
                constants = {node.value for node in ast.walk(self.tree(self.root / relative))
                             if isinstance(node, ast.Constant) and isinstance(node.value, str)}
                self.assertIn(flag, constants)

    def test_session_store(self):
        constants = {node.value for node in ast.walk(self.tree(self.root / contract.SESSION_DB_MODULE))
                     if isinstance(node, ast.Constant) and isinstance(node.value, str)}
        self.assertTrue(contract.SESSION_DB_FILE in constants, "session 저장소 파일 이름이 바뀌었다")
        value, relative = contract.SUBAGENT_SOURCE
        platforms = {keyword.value.value for keyword in ast.walk(self.tree(self.root / relative))
                     if isinstance(keyword, ast.keyword) and keyword.arg == "platform"
                     and isinstance(keyword.value, ast.Constant)}
        self.assertTrue(value in platforms, "위임 도구가 자식에게 platform=%r 를 넘기지 않는다" % value)

        statements = {table: [] for table in contract.SESSION_COLUMNS}
        for path in sorted(self.root.glob("hermes_state*.py")):
            for node in ast.walk(self.tree(path)):
                if not (isinstance(node, ast.Constant) and isinstance(node.value, str)):
                    continue
                for table in statements:
                    statements[table].extend(_create_table(node.value, table))
        for table, columns in contract.SESSION_COLUMNS.items():
            with self.subTest(table=table):
                self.assertTrue(statements[table], "%s 표를 만드는 문장이 없다" % table)
                for statement in statements[table]:
                    connection = sqlite3.connect(":memory:")
                    try:
                        connection.execute(statement)
                        found = {row[1] for row in connection.execute("PRAGMA table_info(%s)" % table)}
                    finally:
                        connection.close()
                    self.assertEqual(set(), set(columns) - found)

    def test_routes(self):
        found = set()
        for path in sorted((self.root / "hermes_cli").rglob("*.py")):
            for node in ast.walk(self.tree(path)):
                if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    continue
                for decorator in node.decorator_list:
                    if (isinstance(decorator, ast.Call) and isinstance(decorator.func, ast.Attribute)
                            and decorator.func.attr in ROUTE_METHODS and decorator.args
                            and isinstance(decorator.args[0], ast.Constant)
                            and isinstance(decorator.args[0].value, str)):
                        found.add((decorator.func.attr.upper(), PATH_PARAM_RE.sub("{}", decorator.args[0].value)))
        self.assertEqual(set(), contract.HERMES_ROUTES - found, "plugin 이 감싸는 대시보드 경로가 없어졌다")
        self.assertEqual(set(), contract.OWN_ROUTES & found, "plugin 이 직접 답하는 경로를 Hermes 가 만들었다")

    def test_revisit_signals(self):
        for (module, name), params in contract.REVISIT_SIGNALS.items():
            with self.subTest(symbol="%s.%s" % (module, name)):
                node = self.definition(module, name)
                positional, args = _params(node, False)
                found = tuple(arg.arg for arg in positional + list(args.kwonlyargs))
                self.assertEqual(params, found, "확장점이 바뀌었다. ADR-088 의 판정을 다시 본다")


def _create_table(text, table):
    """문자열에서 그 표의 `CREATE TABLE` 문장을 괄호 짝까지 잘라 낸다."""
    found = []
    for match in re.finditer(r"CREATE TABLE (?:IF NOT EXISTS )?%s\s*\(" % re.escape(table), text):
        depth = 0
        for index in range(match.end() - 1, len(text)):
            if text[index] == "(":
                depth += 1
            elif text[index] == ")":
                depth -= 1
                if depth == 0:
                    found.append(text[match.start():index + 1])
                    break
    return found


if __name__ == "__main__":
    unittest.main()
