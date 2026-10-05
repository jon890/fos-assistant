"""`hermes/connectors/` 아래의 모든 커넥터가 `docs/connector-authoring.md` 「공통 검사」 를 지키는지 본다(ADR-064).

커넥터 목록을 코드에 적지 않는다. 디렉터리를 찾아 돌므로 커넥터를 더하면 검사 대상이 된다.
서버는 자식으로 띄우되 `initialize` 와 `tools/list` 만 부른다. 외부 서비스를 부르지 않는다.
"""

import ast
import asyncio
import json
import pathlib
import shutil
import sys
import tempfile
import unittest

import test_connector_manifest as base

REPO = pathlib.Path(__file__).resolve().parents[2]
CONNECTORS = REPO / "hermes/connectors"
SECRET_WORDS = ("TOKEN", "SECRET", "PASSWORD", "KEY")
ALLOWED_IMPORTS = {"mcp", "mcp_types", "anyio"}
CLOSED_RISKS = {"DESTRUCTIVE", "FINANCIAL"}
SERVER_TIMEOUT_SECONDS = 30


def connector_dirs(connectors: pathlib.Path = CONNECTORS) -> list[pathlib.Path]:
    return sorted(p for p in connectors.iterdir() if p.is_dir() and not p.name.startswith("."))


def read_json(path: pathlib.Path):
    return json.loads(path.read_text(encoding="utf-8"))


async def _list_tools(command: str, args: list[str], env: dict[str, str]) -> dict[str, bool]:
    """서버를 띄워 `{도구 이름: read_only_hint 가 참인가}` 를 돌려준다."""
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client

    params = StdioServerParameters(command=command, args=args, env=env)
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            result = await session.list_tools()
    return {tool.name: bool(tool.annotations and tool.annotations.read_only_hint is True) for tool in result.tools}


def server_tools(server: dict, root: pathlib.Path) -> dict[str, bool]:
    args = [str(a).replace("${CLAUDE_PLUGIN_ROOT}", str(root)) for a in server.get("args", [])]
    # 칸마다 뜻 없는 글을 준다. 실제 값이 아니므로 서비스는 거절하고, 도구 목록만 읽는다.
    env = {name: "placeholder" for name in server.get("env", {})}
    return asyncio.run(asyncio.wait_for(_list_tools(sys.executable, args, env), SERVER_TIMEOUT_SECONDS))


def imported_roots(source: str) -> set[str]:
    names = set()
    for node in ast.walk(ast.parse(source)):
        if isinstance(node, ast.Import):
            names.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
            names.add(node.module.split(".")[0])
    return names


def check_connector(root: pathlib.Path, repo: pathlib.Path, load_connector) -> list[str]:
    """커넥터 하나의 위반 글 목록을 돌려준다. 비면 통과다. `load_connector` 는 plugin 의 `_load_connector` 다."""
    name = root.name
    problems: list[str] = []

    try:
        load_connector(name, {"root": root, "command": sys.executable, "env": {}})
    except Exception as error:
        problems.append("manifest 검증 실패: %s" % error)

    try:
        declared = read_json(root / "connector.json")
        plugin = read_json(root / ".claude-plugin/plugin.json")
        mcp_config = read_json(root / ".mcp.json")
    except (OSError, ValueError) as error:
        return problems + ["manifest 파일을 읽지 못한다: %s" % error]

    if not (name == declared.get("id") == plugin.get("name")):
        problems.append("이름 불일치: 디렉터리 %r, id %r, plugin name %r" % (name, declared.get("id"), plugin.get("name")))
    if declared.get("schema") != 2:
        problems.append("schema 가 2 가 아니다: %r" % declared.get("schema"))

    tools = declared.get("tools", {})
    for tool, spec in tools.items():
        if spec.get("risk") in CLOSED_RISKS:
            problems.append("닫힌 위험도 %s 선언: %s" % (spec["risk"], tool))
        if spec.get("risk") != "READ" and not str(spec.get("title", "")).strip():
            problems.append("READ 가 아닌 도구에 title 이 없다: %s" % tool)
        if spec.get("risk") != "READ":
            if type(spec.get("outbound")) is not bool:
                problems.append("READ 가 아닌 도구가 outbound 를 boolean 으로 선언하지 않았다: %s" % tool)
            elif spec["outbound"] and spec.get("grant") is not False:
                problems.append("outbound 가 참인 도구의 grant 가 false 가 아니다: %s" % tool)

    for field in declared.get("fields", []):
        env_name = str(field.get("env", "")).upper()
        if any(word in env_name for word in SECRET_WORDS) and field.get("secret") is not True:
            problems.append("비밀 칸의 secret 이 참이 아니다: %s" % field.get("env"))

    for key in ("operator_env", "operator_secrets"):
        if declared.get(key):
            problems.append("%s 가 비어 있지 않다" % key)

    servers = mcp_config.get("mcpServers", {})
    server = next(iter(servers.values()), None) if servers else None
    if server is None:
        problems.append(".mcp.json 에 서버가 없다")
    else:
        if server.get("command") != "python3":
            problems.append(".mcp.json 의 command 가 python3 가 아니다: %r" % server.get("command"))
        try:
            served = server_tools(server, root)
        except Exception as error:
            problems.append("서버를 띄워 도구를 읽지 못한다: %r" % error)
        else:
            declared_names = set(tools)
            if set(served) != declared_names:
                problems.append("서버 도구와 선언이 다르다: 선언에 없는 도구 %s, 서버에 없는 선언 %s" % (
                    sorted(set(served) - declared_names), sorted(declared_names - set(served))))
            read_declared = {t for t, spec in tools.items() if spec.get("risk") == "READ"}
            read_served = {t for t, read_only in served.items() if read_only}
            if read_served != read_declared:
                problems.append("읽기 전용 표시와 READ 선언이 다르다: 서버만 읽기 전용 %s, 선언만 READ %s" % (
                    sorted(read_served - read_declared), sorted(read_declared - read_served)))

    allowed = set(sys.stdlib_module_names) | ALLOWED_IMPORTS
    for source in sorted(root.rglob("*.py")):
        extra = sorted(imported_roots(source.read_text(encoding="utf-8")) - allowed)
        if extra:
            problems.append("표준 라이브러리 밖을 import 한다: %s %s" % (source.relative_to(root), extra))

    skills_dir = root / str(plugin.get("skills", "./skills"))
    if not any(skills_dir.glob("*/SKILL.md")):
        problems.append("스킬이 없다: %s" % skills_dir)

    slug = name.replace("-", "_")
    for needed in ("hermes/tests/connectors/test_%s.py" % slug, "docs/connectors/%s.md" % name):
        if not (repo / needed).is_file():
            problems.append("파일이 없다: %s" % needed)
    owners = repo / ".github/CODEOWNERS"
    prefix = "/hermes/connectors/%s/" % name
    lines = owners.read_text(encoding="utf-8").splitlines() if owners.is_file() else []
    if not any(len(parts) >= 2 and parts[0].startswith(prefix) for parts in (line.split() for line in lines)):
        problems.append("CODEOWNERS 에 %s 소유자 줄이 없다" % prefix)
    return problems


class ConnectorsContractTest(base.ConnectorGateCase):
    def test_at_least_one_connector_is_found(self):
        """경로가 바뀌어 아무것도 보지 않는 검사가 통과하지 않는다."""
        self.assertTrue(connector_dirs(), "%s 아래에 커넥터가 없다" % CONNECTORS)

    def test_every_connector_keeps_the_contract(self):
        for root in connector_dirs():
            with self.subTest(connector=root.name):
                self.assertEqual(check_connector(root, REPO, self.plugin._load_connector), [])

    def test_connector_skills_do_not_request_secrets(self):
        """커넥터 스킬은 앞머리로 비밀값을 요청하지 않는다(ADR-084).

        Hermes 는 이 칸들에 적힌 값과 파일을 셸 실행 공간에 넘긴다. 커넥터 값은 MCP 서버만 받는다.
        """
        skills = sorted(CONNECTORS.rglob("SKILL.md"))
        self.assertTrue(skills, "%s 아래에 SKILL.md 가 없다" % CONNECTORS)
        for path in skills:
            with self.subTest(skill=str(path.relative_to(REPO))):
                self.assertEqual(secret_requests(path), [])

    def test_secret_check_rejects_non_mapping_front_matter(self):
        """앞머리가 mapping 이 아니면 칸을 검사할 수 없으므로 위반으로 본다."""
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        path = pathlib.Path(tmp.name) / "SKILL.md"
        path.write_text("---\n- required_environment_variables\n---\n본문\n", encoding="utf-8")
        self.assertEqual(secret_requests(path), ["앞머리가 mapping 이 아니다"])


def secret_requests(path: pathlib.Path) -> list[str]:
    """SKILL.md 앞머리에서 비밀값을 요청하는 칸의 이름 목록이다."""
    import yaml

    text = path.read_text(encoding="utf-8")
    if not text.startswith("---\n"):
        return []
    head = yaml.safe_load(text[4:text.index("\n---", 4)]) or {}
    if not isinstance(head, dict):
        # 목록이나 글자 앞머리는 칸 이름으로 검사할 수 없다. 통과로 두지 않고 위반으로 돌린다.
        return ["앞머리가 mapping 이 아니다"]
    found = [key for key in ("required_environment_variables", "required_credential_files") if key in head]
    setup = head.get("setup")
    if isinstance(setup, dict) and "collect_secrets" in setup:
        found.append("setup.collect_secrets")
    prerequisites = head.get("prerequisites")
    if isinstance(prerequisites, dict) and "env_vars" in prerequisites:
        found.append("prerequisites.env_vars")
    return found


class ContractCatchesViolationsTest(base.ConnectorGateCase):
    """실제 커넥터를 복사해 하나씩 망가뜨리면 같은 검사가 위반을 내는지 본다."""

    def broken_copy(self, change, source: pathlib.Path = CONNECTORS / "gmail") -> list[str]:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = pathlib.Path(tmp.name).resolve() / source.name
        shutil.copytree(source, root, ignore=shutil.ignore_patterns("__pycache__"))
        change(root)
        return check_connector(root, REPO, self.plugin._load_connector)

    def edit_declaration(self, mutate):
        def change(root):
            path = root / "connector.json"
            value = read_json(path)
            mutate(value)
            path.write_text(json.dumps(value), encoding="utf-8")
        return change

    def assert_reported(self, problems, word):
        self.assertTrue(any(word in p for p in problems), "%r 를 알리는 위반이 없다: %s" % (word, problems))

    def test_untouched_copy_passes(self):
        self.assertEqual(self.broken_copy(lambda root: None), [])

    def test_declaration_missing_a_server_tool_is_reported(self):
        problems = self.broken_copy(self.edit_declaration(lambda v: v["tools"].pop("get_thread")))
        self.assert_reported(problems, "get_thread")

    def test_write_tool_without_title_is_reported(self):
        problems = self.broken_copy(self.edit_declaration(lambda v: v["tools"]["create_draft"].pop("title")))
        self.assert_reported(problems, "title")

    def test_write_tool_without_outbound_is_reported(self):
        problems = self.broken_copy(self.edit_declaration(lambda v: v["tools"]["create_draft"].pop("outbound")))
        self.assert_reported(problems, "outbound 를 boolean 으로 선언하지 않았다: create_draft")

    def test_outbound_tool_with_open_grant_is_reported(self):
        problems = self.broken_copy(self.edit_declaration(lambda v: v["tools"]["send_message"].pop("grant")))
        self.assert_reported(problems, "grant 가 false 가 아니다: send_message")
        # plugin 의 검증도 같은 선언을 거절한다. 그 까닭이 위반 글에 든다.
        self.assert_reported(problems, "manifest 검증 실패: outbound 가 참인 도구")

    def test_secret_field_not_marked_secret_is_reported(self):
        def mutate(value):
            for field in value["fields"]:
                if field["env"] == "GMAIL_OAUTH_CLIENT_SECRET":
                    field["secret"] = False
        self.assert_reported(self.broken_copy(self.edit_declaration(mutate)), "secret")

    def test_third_party_import_in_server_is_reported(self):
        def change(root):
            server = root / "server.py"
            server.write_text("import requests\n" + server.read_text(encoding="utf-8"), encoding="utf-8")
        self.assert_reported(self.broken_copy(change), "requests")


if __name__ == "__main__":
    unittest.main()
