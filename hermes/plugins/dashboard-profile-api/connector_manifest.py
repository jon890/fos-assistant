from __future__ import annotations

import hashlib
import json
import os
import pathlib
import re
from .common import (
    BASE_ENV_KEYS,
    CONTROL_PLANE_MCP,
    SKILL_NAME_RE,
    logger,
)


# 요청은 이름만 받는다. 실행 정의는 커넥터 checkout 의 manifest 가 소유한다.
# 커넥터 이름과 plugin 디렉터리를 묶은 JSON 을 대시보드 프로세스의 환경 변수로 받는다. 근거는 ADR-041 이 갖는다.
CONNECTOR_ROOTS_ENV = "FOS_ASSISTANT_CONNECTOR_ROOTS"
# 커넥터 MCP 서버를 실행할 파일의 절대 경로다. 운영 목록 항목에 `command` 가 없을 때 쓴다.
CONNECTOR_COMMAND_ENV = "FOS_ASSISTANT_CONNECTOR_COMMAND"
CONNECTOR_STATE = ".fos-connectors.json"
# 소유 기록 항목의 설치 방식이다. 칸이 없으면 옛 설치다.
BIND_MODE = "bind"
ISOLATED_MODE = "isolated"
CONNECTOR_SKILL_PARTS = ("references", "templates")
# 커넥터 스킬 하나의 상한이다. Control Plane 이 올린 스킬에 거는 제한과 같다(`docs/backend/skill.md`).
CONNECTOR_SKILL_MAX_FILES = 20
CONNECTOR_SKILL_MAX_CHARS = 100_000
# `connector.json` 의 형식 규칙이다. `docs/connectors.md` 의 「connector.json」 표와 같다.
CONNECTOR_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
FIELD_KEY_RE = re.compile(r"^[a-z][a-z0-9_]{0,31}$")
ENV_NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,127}$")
# 바인딩 주인의 첨부 디렉터리를 받을 env 이름이다(ADR-20261007 connector-owner-attachments).
OWNER_ATTACHMENTS_ENV_RE = re.compile(r"^[A-Z][A-Z0-9_]{0,63}$")
# 그 env 에 설치가 넣는 값의 끝 모양이다. `<attachment_agent_root>/users/<SHA-256 16진수>` 다.
OWNER_ATTACHMENTS_VALUE_RE = re.compile(r"^/[^$\0\r\n]*/users/[0-9a-f]{64}$")
TOOL_NAME_RE = re.compile(r"^[A-Za-z0-9_.-]{1,128}$")
SERVER_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
ERROR_WORDS = frozenset({"credential_rejected", "forbidden", "invalid_input", "unavailable", "outcome_unknown"})
# 쓰기를 보냈는데 됐는지 모른다는 어휘다. 실행 경로만 504 로 답하고 `call` 은 `unavailable` 로 읽는다.
OUTCOME_UNKNOWN = "outcome_unknown"
# `errors` 표의 오류 코드 형식이다. 승인한 호출의 실행 경로가 이 코드를 Control Plane 에 그대로 넘긴다(ADR-092).
ERROR_CODE_RE = re.compile(r"[A-Z][A-Z0-9_]{0,63}")
# `errors` 표의 객체 항목이 고를 수 있는 복구 어휘다. 커넥터는 글을 쓰지 않고 어휘만 고른다.
# Control Plane 의 `ConnectorRecovery` 와 같다. 한쪽을 바꾸면 다른 쪽도 바꾼다.
ERROR_RECOVERIES = frozenset({"recheck", "reconnect", "fix_input", "retry_later"})
# 오류 하나가 넘길 수 있는 세부 칸의 수와 정수 값의 절댓값 상한이다. Control Plane 이 같은 상한으로 다시 본다.
ERROR_DETAILS_MAX = 4
# `connector.json` 의 `toolsets` 가 열 수 있는 내장 toolset 이다. 읽기 전용 이미지 도구만 둔다(ADR-044).
# 셸, 파일, 기억, 스킬, 위임 도구는 manifest 로 열리지 않는다.
CONNECTOR_TOOLSETS = frozenset({"vision"})
# `connector.json` 의 `schema: 2` 가 도구마다 선언하는 위험도와 승인 방식이다(ADR-049).
# 표는 `docs/backend/connector-tool-policy.md` 의 「도구 정책」 과 같다.
TOOL_RISKS = ("READ", "SENSITIVE", "WRITE", "DESTRUCTIVE", "FINANCIAL")
# 느슨한 것에서 엄격한 것의 순서다. 하한 비교가 이 순서의 자리를 쓴다.
TOOL_APPROVALS = ("none", "required", "always")
# 위험도마다 (`approval` 이 없을 때의 기본값, 선언이 내려갈 수 없는 하한) 이다.
TOOL_RISK_DEFAULTS = {"READ": ("none", "none"), "SENSITIVE": ("required", "required"),
                      "WRITE": ("required", "required"), "DESTRUCTIVE": ("always", "always"),
                      "FINANCIAL": ("always", "always")}
TOOL_TITLE_MAX_CHARS = 80
# `tools.<이름>.identifiers` 가 가리키는 인자 이름이다(ADR-089). 도구 인자 객체의 맨 위 칸만 가리킨다.
# 31자까지다. 32자 이상인 이름은 Control Plane 이 키 이름 자체를 긴 덩어리로 보고 가린다.
TOOL_IDENTIFIER_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,30}")
# 비밀 키로 읽히는 인자 이름이다. Control Plane 의 `ToolDetailRedactor` 의 `SECRET_KEYS` 와 `isSecretKey` 와 같다.
# 한쪽을 바꾸면 다른 쪽도 바꾼다. 어긋나도 Control Plane 이 그 칸을 다시 가리므로 비밀이 보이지는 않는다.
SECRET_ARGUMENT_NAMES = frozenset({"token", "secret", "password", "passwd", "apikey", "authorization", "cookie",
                                   "credential", "credentials", "privatekey", "accesskey", "clientsecret"})
SECRET_ARGUMENT_SUFFIXES = ("token", "secret", "password", "privatekey")
# Hermes 가 MCP 도구의 등록 이름에 허용하는 길이다. 넘으면 앞부분에 해시를 붙여 줄인다.
HERMES_TOOL_NAME_MAX_CHARS = 64
# 서버 이름으로 계산한 등록 이름의 앞부분(`mcp__<서버>__`)이 넘지 못하는 길이다.
# 앞부분이 길면 등록 이름이 잘릴 때 앞부분까지 잘려, Control Plane 이 그 서버의 도구임을 알아보지 못한다.
HERMES_TOOL_PREFIX_MAX_CHARS = 40
# 설치가 연결용 profile 의 `SOUL.md` 에 쓰는 스킬 본문의 상한이다. Control Plane 의 성격 본문 상한과 같다.
CONNECTOR_PERSONA_MAX_CHARS = 8000
# `.mcp.json` 의 인자가 plugin 디렉터리를 가리키는 자리다. 그 밖의 치환은 받지 않는다.
PLUGIN_ROOT_REF = "${CLAUDE_PLUGIN_ROOT}"


def _connector_roots() -> dict[str, dict]:
    """운영 목록을 읽는다. `{"<커넥터 이름>": {"root", "command", "env"}}` 다. 없거나 틀리면 빈 dict 다.

    환경 변수의 값은 이름마다 문자열(plugin 디렉터리)이나
    `{"root": "<plugin 디렉터리>", "command": "<실행 파일>", "env": {"<이름>": "<값>"}}` 다.
    문자열은 `{"root": 그 값}` 으로 읽는다.
    """
    raw = os.environ.get(CONNECTOR_ROOTS_ENV, "").strip()
    if not raw:
        return {}
    try:
        value = json.loads(raw)
    except ValueError:
        value = None
    if not isinstance(value, dict) or any(not isinstance(entry, (str, dict)) for entry in value.values()):
        # 값에는 운영 경로가 들어 있어 로그에 싣지 않는다.
        logger.warning("dashboard-profile-api: %s 가 문자열이나 object 값의 JSON object 가 아니다", CONNECTOR_ROOTS_ENV)
        return {}
    roots = {}
    for name, entry in value.items():
        if isinstance(entry, str):
            entry = {"root": entry}
        root, command, env = entry.get("root"), entry.get("command"), entry.get("env", {})
        if (set(entry) - {"root", "command", "env"} or not isinstance(root, str)
                or not (command is None or isinstance(command, str)) or not isinstance(env, dict)
                or any(not isinstance(key, str) or not isinstance(item, str) for key, item in env.items())):
            logger.warning("dashboard-profile-api: %s 의 %s 항목 모양이 올바르지 않아 버렸다", CONNECTOR_ROOTS_ENV, name)
            continue
        if not os.path.isabs(root) or (command is not None and not os.path.isabs(command)):
            logger.warning("dashboard-profile-api: %s 의 %s 경로가 절대 경로가 아니라 버렸다", CONNECTOR_ROOTS_ENV, name)
            continue
        roots[name] = {"root": pathlib.Path(root), "command": command, "env": dict(env)}
    return roots


def _connector_command() -> str | None:
    """커넥터 MCP 서버를 실행할 파일의 기본 절대 경로다. 없거나 절대 경로가 아니면 None 이다."""
    command = os.environ.get(CONNECTOR_COMMAND_ENV, "").strip()
    if not command:
        return None
    if not os.path.isabs(command):
        logger.warning("dashboard-profile-api: %s 가 절대 경로가 아니다", CONNECTOR_COMMAND_ENV)
        return None
    return command


def _read_connector_json(root: pathlib.Path, relative: str):
    path = root / relative
    if path.resolve() != path or not path.is_file():
        raise ValueError("%s 가 없거나 링크다" % relative)
    return json.loads(path.read_text(encoding="utf-8"))


def _connector_fields(declared) -> list:
    """`connector.json` 의 `fields` 를 검증한다. 틀리면 예외다.

    빈 목록도 받는다. 값을 받지 않는 일반 MCP 서버가 입력 칸 없이 카탈로그에 오르는 길이다(ADR-083).
    """
    if not isinstance(declared, list):
        raise ValueError("fields 는 목록이다")
    for field in declared:
        if (not isinstance(field, dict)
                or not isinstance(field.get("key"), str) or not FIELD_KEY_RE.match(field["key"])
                or not isinstance(field.get("env"), str) or not ENV_NAME_RE.match(field["env"])
                or field["env"] in BASE_ENV_KEYS
                or not isinstance(field.get("label"), str)
                or not isinstance(field.get("description", ""), str)
                or not isinstance(field.get("secret", False), bool)
                or not isinstance(field.get("required", True), bool)):
            raise ValueError("fields 의 칸 모양이 올바르지 않다")
        if "pattern" in field:
            if not isinstance(field["pattern"], str):
                raise ValueError("pattern 은 문자열이다")
            try:
                re.compile(field["pattern"])
            except re.error:
                raise ValueError("pattern 을 정규식으로 읽지 못한다") from None
        if "options" in field:
            options = field["options"]
            if (not isinstance(options, dict)
                    or not isinstance(options.get("tool"), str) or not TOOL_NAME_RE.match(options["tool"])
                    or any(not isinstance(options.get(name), str) for name in ("items", "value", "label"))
                    or not isinstance(options.get("auto_select_single", False), bool)):
                raise ValueError("options 모양이 올바르지 않다")
    for name in ("key", "env"):
        if len({field[name] for field in declared}) != len(declared):
            raise ValueError("fields 의 %s 가 겹친다" % name)
    return declared


def _canonical_server_name(server: str) -> str:
    """MCP 서버 이름을 견줄 수 있게 맞춘다. Hermes 등록 규칙대로 글자를 `_` 로 바꾸고 소문자로 맞춘다.

    등록 규칙만 쓰면 대소문자만 다른 이름이 다른 서버로 읽힌다. 이름을 대소문자 없이 다루는 자리가
    하나라도 있으면 두 서버의 도구가 섞이므로 가장 넓게 같은 이름으로 본다.
    """
    return re.sub(r"[^A-Za-z0-9_]", "_", server).lower()


def _hermes_tool_name(server: str, tool: str) -> str:
    """Hermes 가 MCP 도구에 붙이는 등록 이름이다. `tools/mcp_tool_schema.py` 의 `mcp_prefixed_tool_name` 과 같은 규칙이다.

    규칙은 `docs/hermes/connector-policy.md` 의 「MCP 도구의 등록 이름」 이 갖는다.
    글자를 바꾸고 줄이므로 서로 다른 도구가 같은 등록 이름이 될 수 있다.
    """
    full = "mcp__%s__%s" % (re.sub(r"[^A-Za-z0-9_]", "_", server), re.sub(r"[^A-Za-z0-9_]", "_", tool))
    if len(full) <= HERMES_TOOL_NAME_MAX_CHARS:
        return full
    return full[:HERMES_TOOL_NAME_MAX_CHARS - 9] + "_" + hashlib.sha256(full.encode("utf-8")).hexdigest()[:8]


def _tool_identifiers(declared_tool: dict, approval: str) -> list:
    """`tools.<이름>.identifiers` 를 검증해 선언한 순서대로 낸다. 틀리면 예외다.

    승인 카드가 있는 도구에만 뜻이 있어 `approval` 이 `required` 인 도구에만 받는다.
    비밀 키로 읽히는 이름은 Control Plane 이 어차피 가리므로, 선언한 사람이 잘못 안 것으로 보고 거절한다.
    """
    if "identifiers" not in declared_tool:
        return []
    identifiers = declared_tool["identifiers"]
    if approval != "required":
        raise ValueError("identifiers 는 approval 이 required 인 도구에만 선언한다")
    if not isinstance(identifiers, list) or not all(
            isinstance(item, str) and TOOL_IDENTIFIER_RE.fullmatch(item) for item in identifiers):
        raise ValueError("identifiers 는 인자 이름의 배열이다")
    if len(set(identifiers)) != len(identifiers):
        raise ValueError("identifiers 에 같은 이름이 두 번 있다")
    for item in identifiers:
        normalized = item.replace("_", "").lower()
        if normalized in SECRET_ARGUMENT_NAMES or normalized.endswith(SECRET_ARGUMENT_SUFFIXES):
            raise ValueError("identifiers 에 비밀 키로 읽히는 이름을 둘 수 없다")
    return list(identifiers)


def _connector_tools(declared: dict, verify_tool: str, option_tools: set, mcp_server: str) -> dict:
    """`connector.json` 의 도구 정책을 검증해 `{도구 이름: {"risk", "approval", "title", "grant", "outbound", "identifiers"}}` 로 낸다.

    틀리면 예외다.

    하한보다 느슨한 선언은 고쳐서 받지 않고 거절한다. 조용히 엄격하게 읽으면 선언이 틀린 것을 만든 사람이 모른다.
    `schema: 1` 은 도구 정책을 선언하지 않는다. 대시보드가 부르는 읽기 전용 도구만 정책으로 낸다.
    `grant` 는 그 도구에 상시 허락을 줄 수 있는지다(ADR-065). 기본값을 채운 값이고,
    `approval` 이 `required` 이고 선언이 닫지 않았을 때만 참이다.
    `outbound` 는 그 도구가 데이터를 계정 밖의 사람에게 보낸다는 선언이다. 참인 도구는 상시 허락이 닫혀 있어야 한다.
    `identifiers` 는 승인 카드가 길이로 가리지 않을 식별자 인자의 이름이다(ADR-089). 기본값은 빈 배열이다.
    """
    call_tools = {verify_tool} | set(option_tools)
    if declared["schema"] == 1:
        if "tools" in declared or "default_tool_policy" in declared:
            raise ValueError("tools 와 default_tool_policy 는 schema 2 에서만 선언한다")
        return {name: {"risk": "READ", "approval": "none", "title": None, "grant": False, "outbound": False,
                       "identifiers": []}
                for name in sorted(call_tools)}
    if declared["schema"] != 2:
        raise ValueError("schema 는 1 이나 2 만 받는다")

    tools = declared.get("tools")
    if not isinstance(tools, dict) or not tools:
        raise ValueError("schema 2 의 tools 는 비어 있지 않은 객체다")
    if "default_tool_policy" in declared and declared["default_tool_policy"] != "deny":
        raise ValueError("default_tool_policy 는 deny 만 받는다")
    policies = {}
    for name, declared_tool in tools.items():
        if not isinstance(name, str) or not TOOL_NAME_RE.match(name):
            raise ValueError("tools 의 키는 도구 이름이다")
        if not isinstance(declared_tool, dict) or set(declared_tool) - {
                "risk", "approval", "title", "grant", "outbound", "identifiers"}:
            raise ValueError("tools 의 값은 risk, approval, title, grant, outbound, identifiers 만 갖는 객체다")
        risk = declared_tool.get("risk")
        if not isinstance(risk, str) or risk not in TOOL_RISKS:
            raise ValueError("risk 는 정해 둔 위험도 가운데 하나다")
        default, floor = TOOL_RISK_DEFAULTS[risk]
        approval = declared_tool.get("approval", default)
        if not isinstance(approval, str) or approval not in TOOL_APPROVALS:
            raise ValueError("approval 은 none, required, always 가운데 하나다")
        if TOOL_APPROVALS.index(approval) < TOOL_APPROVALS.index(floor):
            raise ValueError("approval 이 그 위험도의 하한보다 느슨하다")
        title = declared_tool.get("title")
        if "title" in declared_tool and (not isinstance(title, str) or not 1 <= len(title) <= TOOL_TITLE_MAX_CHARS):
            raise ValueError("title 은 1자에서 %d자까지의 문자열이다" % TOOL_TITLE_MAX_CHARS)
        if "grant" in declared_tool:
            # `1` 이나 `0` 을 boolean 으로 받지 않는다.
            if type(declared_tool["grant"]) is not bool:
                raise ValueError("grant 는 true 나 false 다")
            if approval != "required":
                raise ValueError("grant 는 approval 이 required 인 도구에만 선언한다")
        grant = approval == "required" and declared_tool.get("grant") is not False
        outbound = declared_tool.get("outbound", False)
        if type(outbound) is not bool:
            raise ValueError("outbound 는 true 나 false 다")
        # 밖으로 나가는 도구는 호출마다 사람이 본다. 상시 허락이 열려 있으면 고쳐 읽지 않고 거절한다.
        if outbound and (approval != "required" or grant):
            raise ValueError("outbound 가 참인 도구는 approval 이 required 이고 grant 가 false 여야 한다")
        identifiers = _tool_identifiers(declared_tool, approval)
        policies[name] = {"risk": risk, "approval": approval, "title": title, "grant": grant, "outbound": outbound,
                          "identifiers": identifiers}
    for name in call_tools:
        # 대시보드가 승인 없이 부르는 도구다. 읽기 전용이고 승인이 없는 선언만 맞는다.
        if policies.get(name, {}).get("risk") != "READ" or policies[name]["approval"] != "none":
            raise ValueError("확인 도구와 선택지 도구는 tools 에 READ 와 none 으로 선언한다")
    # 판정은 등록 이름으로 도구를 찾는다. 두 도구의 등록 이름이 같으면 어느 정책인지 알 수 없다.
    if len({_hermes_tool_name(mcp_server, name) for name in policies}) != len(policies):
        raise ValueError("tools 의 두 도구가 같은 Hermes 등록 이름이 된다")
    return policies


def _connector_persona(skill_dirs: list) -> str | None:
    """스킬 디렉터리들의 `<스킬>/SKILL.md` 본문을 이름 순으로 이어 붙인다. 스킬이 없으면 None 이다.

    `SKILL.md` 밖의 파일은 읽지 않는다. 스킬 디렉터리나 `SKILL.md` 가 링크이면 읽지 않고 예외를 낸다.
    링크가 plugin 밖의 파일을 가리키면 그 내용이 모델의 지침으로 들어가기 때문이다.
    앞머리(frontmatter)는 뗀다. 합친 본문이 상한을 넘으면 예외다. 본문은 로그에 싣지 않는다.
    """
    bodies = []
    for directory in skill_dirs:
        for skill in sorted(directory.iterdir()):
            source = skill / "SKILL.md"
            if skill.is_symlink() or source.is_symlink():
                raise ValueError("스킬 디렉터리나 SKILL.md 가 링크다")
            if not skill.is_dir() or not source.is_file():
                continue
            # BOM 과 CRLF 가 있어도 앞머리를 알아보게 맞춘다.
            text = source.read_text(encoding="utf-8-sig").replace("\r\n", "\n")
            if text.startswith("---\n"):
                head, separator, rest = text[4:].partition("\n---\n")
                if not separator:
                    raise ValueError("SKILL.md 의 앞머리가 닫히지 않았다")
                text = rest
            if text.strip():
                bodies.append(text.strip())
    if not bodies:
        return None
    persona = "\n\n".join(bodies) + "\n"
    if len(persona) > CONNECTOR_PERSONA_MAX_CHARS:
        raise ValueError("스킬 본문이 %d자를 넘는다" % CONNECTOR_PERSONA_MAX_CHARS)
    return persona


def _skill_name(skill_md: str, fallback: str) -> str:
    """`SKILL.md` 앞머리의 `name` 이다. 없으면 디렉터리 이름이다. 경로 조각으로 쓸 수 없는 이름이면 예외다.

    Hermes 가 스킬 목록을 만들 때 같은 규칙으로 이름을 정한다(`tools/skills_tool.py` 의 `_find_all_skills`).

    앞머리가 환경 값이나 자격 증명 파일을 요청하면 예외다. Hermes 는 스킬을 읽을 때 그 칸의 이름으로
    profile `.env` 의 값과 profile 안의 파일을 셸 실행 공간에 넣는다. 바인딩 설치는 커넥터 값을 그 `.env` 에
    복사하므로 실행 공간이 커넥터 비밀을 받게 된다. 칸 목록은 Control Plane 이 올린 스킬에 거는 것과 같다(ADR-086).
    """
    import yaml
    text = skill_md.lstrip("﻿").replace("\r\n", "\n")
    name = fallback
    # 앞머리를 알아보는 규칙도 Hermes 와 같게 둔다(`agent/skill_utils.py` 의 `parse_frontmatter`).
    # 첫 줄이 `--- ` 처럼 정확히 `---` 가 아니어도 Hermes 는 앞머리로 읽으므로 여기서도 읽어 검사한다.
    if text.startswith("---"):
        closing = re.search(r"\n---\s*\n", text[3:])
        if closing is None:
            raise ValueError("SKILL.md 의 앞머리가 닫히지 않았다")
        head = text[3:3 + closing.start()]
        front = yaml.safe_load(head) if head.strip() else {}
        if not isinstance(front, dict):
            raise ValueError("SKILL.md 의 앞머리가 객체가 아니다")
        setup = front.get("setup")
        prerequisites = front.get("prerequisites")
        if ("required_environment_variables" in front or "required_credential_files" in front
                or (isinstance(setup, dict) and "collect_secrets" in setup)
                or (isinstance(prerequisites, dict) and "env_vars" in prerequisites)):
            raise ValueError("SKILL.md 의 앞머리가 환경 값이나 자격 증명 파일을 요청한다")
        name = front.get("name", fallback)
    # 설치와 떼기가 이 이름을 profile 의 스킬 디렉터리 이름으로 쓴다.
    if not isinstance(name, str) or not SKILL_NAME_RE.match(name) or ".." in name:
        raise ValueError("스킬 이름이 경로로 쓸 수 없는 모양이다")
    return name


def _connector_skills(skill_dirs: list) -> dict:
    """바인딩 설치가 profile 에 복사할 스킬이다. `{스킬 이름: {상대 경로: 파일 바이트}}` 다. 틀리면 예외다.

    스킬마다 `SKILL.md` 와 `references/`, `templates/` 아래 정규 파일을 읽는다.
    스킬 디렉터리 아래 어느 항목이든 링크이면 거절한다. 링크가 plugin 밖의 파일을 가리키면 그 내용이 profile 로 복사된다.
    UTF-8 로 읽히지 않는 파일, 상한을 넘는 스킬, 이름이 겹치는 스킬도 거절한다. 본문은 로그에 싣지 않는다.
    """
    skills = {}
    for directory in skill_dirs:
        for skill in sorted(directory.iterdir()):
            if skill.is_symlink():
                raise ValueError("스킬 디렉터리 아래에 링크가 있다")
            source = skill / "SKILL.md"
            if not skill.is_dir() or not source.is_file():
                continue
            for current, dirs, names in os.walk(skill):
                if any(os.path.islink(os.path.join(current, child)) for child in dirs + names):
                    raise ValueError("스킬 디렉터리 아래에 링크가 있다")
            paths = [source]
            for part in CONNECTOR_SKILL_PARTS:
                if (skill / part).is_dir():
                    for current, dirs, names in os.walk(skill / part):
                        dirs.sort()
                        paths.extend(pathlib.Path(current) / name for name in sorted(names)
                                     if (pathlib.Path(current) / name).is_file())
            if len(paths) > CONNECTOR_SKILL_MAX_FILES:
                raise ValueError("스킬 하나의 파일이 %d개를 넘는다" % CONNECTOR_SKILL_MAX_FILES)
            files = {}
            for path in paths:
                relative = path.relative_to(skill)
                if any(part in ("", ".", "..") for part in relative.parts):
                    raise ValueError("스킬 파일 경로에 . 이나 .. 이 있다")
                data = path.read_bytes()
                try:
                    text = data.decode("utf-8")
                except UnicodeDecodeError:
                    raise ValueError("스킬 파일이 UTF-8 이 아니다") from None
                if len(text) > CONNECTOR_SKILL_MAX_CHARS:
                    raise ValueError("스킬 파일이 %d자를 넘는다" % CONNECTOR_SKILL_MAX_CHARS)
                files[relative.as_posix()] = data
            name = _skill_name(files["SKILL.md"].decode("utf-8"), skill.name)
            if name in skills:
                raise ValueError("스킬 이름이 겹친다")
            skills[name] = files
    return skills


def _load_connector(connector_id: str, entry: dict) -> dict:
    """plugin 디렉터리의 `connector.json`, `.mcp.json`, `plugin.json` 을 읽어 검증한다. 틀리면 예외다.

    형식 규칙은 `docs/connectors.md` 의 「connector.json」 이 소유한다(ADR-043).
    """
    root = entry["root"]
    if not CONNECTOR_ID_RE.match(connector_id):
        raise ValueError("커넥터 이름이 올바르지 않다")
    if root.resolve() != root:
        raise ValueError("connector 경로에 심볼릭 링크가 있다")
    plugin = _read_connector_json(root, ".claude-plugin/plugin.json")
    if not isinstance(plugin, dict) or plugin.get("name") != connector_id:
        raise ValueError("plugin.json 의 이름이 운영 목록과 다르다")

    declared = _read_connector_json(root, "connector.json")
    if not isinstance(declared, dict) or type(declared.get("schema")) is not int or declared["schema"] not in (1, 2):
        raise ValueError("schema 는 1 이나 2 만 받는다")
    if declared.get("id") != connector_id:
        raise ValueError("id 가 운영 목록의 이름과 다르다")
    if (not isinstance(declared.get("title"), str) or not declared["title"]
            or not isinstance(declared.get("description", ""), str)):
        raise ValueError("title 과 description 은 문자열이다")
    fields = _connector_fields(declared.get("fields"))
    verify = declared.get("verify")
    if (not isinstance(verify, dict) or not isinstance(verify.get("tool"), str)
            or not TOOL_NAME_RE.match(verify["tool"])):
        raise ValueError("verify.tool 은 도구 이름이다")
    field_env = {field["env"] for field in fields}
    operator_env = declared.get("operator_env", [])
    if (not isinstance(operator_env, list)
            or any(not isinstance(name, str) or not ENV_NAME_RE.match(name) or name in BASE_ENV_KEYS
                   for name in operator_env)
            or len(set(operator_env)) != len(operator_env) or set(operator_env) & field_env):
        raise ValueError("operator_env 는 칸과 겹치지 않는 env 이름 목록이다")
    if set(operator_env) - set(entry["env"]):
        raise ValueError("operator_env 의 값이 운영 목록에 없다")
    # 사용자 첨부를 읽는 커넥터가 받을 env 이름이다. 값은 바인딩 설치가 그 에이전트 주인의 디렉터리로 넣는다(ADR-20261007 connector-owner-attachments).
    owner_attachments_env = declared.get("owner_attachments_env")
    if owner_attachments_env is not None and (
            not isinstance(owner_attachments_env, str) or not OWNER_ATTACHMENTS_ENV_RE.match(owner_attachments_env)
            or owner_attachments_env in BASE_ENV_KEYS or owner_attachments_env in field_env
            or owner_attachments_env in operator_env):
        raise ValueError("owner_attachments_env 는 칸과 운영자 env 와 겹치지 않는 env 이름 하나다")
    owner_env = {owner_attachments_env} if owner_attachments_env is not None else set()
    operator_secrets = declared.get("operator_secrets", [])
    if not isinstance(operator_secrets, list) or any(not isinstance(name, str) for name in operator_secrets):
        raise ValueError("operator_secrets 는 env 이름 목록이다")
    if operator_secrets:
        # 조용히 무시하면 비밀이 필요한 커넥터의 확인이 까닭 없이 실패한다. 받지 못하는 칸임을 밝힌다(ADR-046).
        raise ValueError("operator_secrets 는 아직 지원하지 않는다")
    errors, error_contracts = _connector_errors(declared.get("errors", {}))
    toolsets = declared.get("toolsets", [])
    if (not isinstance(toolsets, list) or any(not isinstance(name, str) for name in toolsets)
            or len(set(toolsets)) != len(toolsets) or set(toolsets) - CONNECTOR_TOOLSETS):
        raise ValueError("toolsets 는 허용한 내장 toolset 이름의 겹치지 않는 목록이다")
    attachments = declared.get("attachments", False)
    if not isinstance(attachments, bool) or (attachments and "vision" not in toolsets):
        raise ValueError("attachments 는 boolean 이고 참이면 toolsets 에 vision 이 있어야 한다")

    mcp = _read_connector_json(root, ".mcp.json")
    if not isinstance(mcp, dict):
        raise ValueError("MCP manifest 는 객체여야 한다")
    if "mcpServers" in mcp:
        if set(mcp) != {"mcpServers"}:
            raise ValueError("MCP manifest 에 다른 필드가 있다")
        mcp = mcp["mcpServers"]
    if not isinstance(mcp, dict) or len(mcp) != 1:
        raise ValueError("MCP 서버 하나만 허용한다")
    mcp_server, server = next(iter(mcp.items()))
    # 맞춘 이름으로 견준다. `fos_assistant` 나 `FOS-Assistant` 처럼 글자만 다른 이름도 Control Plane MCP 의 이름으로 읽힐 수 있어
    # 그 커넥터의 도구가 Control Plane 도구와 같은 이름 공간에 놓인다.
    if (not SERVER_NAME_RE.match(mcp_server)
            or _canonical_server_name(mcp_server) == _canonical_server_name(CONTROL_PLANE_MCP)):
        raise ValueError("MCP 서버 이름이 올바르지 않다")
    if len(_hermes_tool_name(mcp_server, "")) > HERMES_TOOL_PREFIX_MAX_CHARS:
        raise ValueError("MCP 서버 이름이 길어 등록 이름의 앞부분이 %d자를 넘는다" % HERMES_TOOL_PREFIX_MAX_CHARS)
    if (not isinstance(server, dict) or set(server) - {"command", "args", "env"}
            or not isinstance(server.get("args"), list) or not isinstance(server.get("env"), dict)):
        raise ValueError("MCP 서버 정의 모양이 올바르지 않다")
    optional_env = frozenset(field["env"] for field in fields if field.get("required", True) is False)
    if set(server["env"]) != field_env | set(operator_env) | owner_env:
        raise ValueError("MCP 서버 env 가 fields 와 operator_env, owner_attachments_env 의 합과 다르다")
    for name, value in server["env"].items():
        # 비밀값 원문이나 다른 변수의 참조를 받지 않는다. 선택 칸만 빈 기본값 참조를 쓸 수 있다.
        if value != "${%s}" % name and not (name in optional_env and value == "${%s:-}" % name):
            raise ValueError("MCP 서버 env 는 자기 이름의 참조만 허용한다")
    args = []
    for arg in server["args"]:
        if not isinstance(arg, str):
            raise ValueError("MCP 서버 인자는 문자열이다")
        if arg.startswith(PLUGIN_ROOT_REF + "/"):
            target = root / arg[len(PLUGIN_ROOT_REF) + 1:]
            if target.resolve() != target or not target.is_relative_to(root) or not target.is_file():
                raise ValueError("MCP 서버 인자의 파일이 plugin 안의 링크 없는 파일이 아니다")
            arg = str(target)
        elif "$" in arg:
            raise ValueError("MCP 서버 인자에 plugin root 밖의 치환이 있다")
        args.append(arg)
    # 실행 파일은 manifest 가 정하지 못한다. 운영 목록의 값이나 기본 실행 파일만 쓴다.
    command = entry["command"] or _connector_command()
    if command is None or not os.access(command, os.X_OK):
        raise ValueError("커넥터 실행 파일이 없거나 실행할 수 없다")

    # 옛 설치는 스킬 본문을 `SOUL.md` 에 써 지침으로 넣고 skills toolset 은 열지 않는다.
    # 바인딩 설치는 스킬 디렉터리를 그 profile 의 스킬로 복사한다. 둘 다 같은 디렉터리를 읽는다.
    skills = plugin.get("skills", "./skills")
    if isinstance(skills, str):
        skills = [skills]
    if not isinstance(skills, list) or any(not isinstance(item, str) for item in skills):
        raise ValueError("스킬 경로 목록이 올바르지 않다")
    skill_dirs = []
    for item in skills:
        path = root / item
        if not path.resolve().is_relative_to(root) or not path.is_dir() or path.resolve() != path.absolute():
            raise ValueError("스킬은 plugin 안의 링크 없는 디렉터리여야 한다")
        skill_dirs.append(path.resolve())
    persona = _connector_persona(skill_dirs)
    installed_skills = _connector_skills(skill_dirs)

    env = {name: "${%s}" % name for name in server["env"]}
    # 운영자 env 는 profile `.env` 를 거치지 않는다. 운영 목록의 값을 서버 정의에 직접 넣는다.
    env.update({name: entry["env"][name] for name in operator_env})
    # 주인의 첨부 디렉터리도 profile `.env` 를 거치지 않는다. 바인딩 설치가 이 빈 값을 그 주인의 디렉터리로 바꾼다.
    # 주인을 모르는 설치는 빈 값 그대로 두어 커넥터가 사용자 첨부를 읽지 않는다.
    env.update({name: "" for name in owner_env})
    option_tools = {field["options"]["tool"] for field in fields if "options" in field}
    tools = _connector_tools(declared, verify["tool"], option_tools, mcp_server)
    definition = {"command": command, "args": args, "env": env, "enabled": True}
    # 늘 승인이 필요한 도구는 모델에 등록하지 않는다. 도구 이름에는 glob 글자가 없어 그 이름만 빠진다.
    excluded = sorted(name for name, policy in tools.items() if policy["approval"] == "always")
    if excluded:
        definition["tools"] = {"exclude": excluded}
    return {
        "id": connector_id,
        "schema": declared["schema"],
        "title": declared["title"],
        "description": declared.get("description", ""),
        "fields": fields,
        "verify": {"tool": verify["tool"]},
        "mcp_server": mcp_server,
        "operator_env": frozenset(operator_env),
        "owner_attachments_env": owner_attachments_env,
        "optional_env": optional_env,
        "errors": errors,
        "error_contracts": error_contracts,
        "toolsets": list(toolsets),
        "attachments": attachments,
        "persona": persona,
        "skills": installed_skills,
        # 대시보드가 `call` 로 부를 수 있는 도구다. 도구 정책인 `tools` 와 뜻이 다르다.
        "call_tools": frozenset({verify["tool"]} | option_tools),
        "tools": tools,
        "server": definition,
    }


def _connector_errors(declared) -> tuple:
    """`errors` 표를 `{코드: 공통 어휘}` 와 `{코드: 복구 계약}` 으로 나눠 검증한다(ADR-092).

    항목은 공통 어휘 글이거나 `{category, recovery, details}` 객체다. 객체의 `recovery` 는 복구 어휘 하나이고
    `details` 는 도구 오류에서 넘길 칸 이름의 목록이다. 칸 값의 모양은 실행 경로가 본다.
    """
    if not isinstance(declared, dict):
        raise ValueError("errors 는 객체다")
    words, contracts = {}, {}
    for code, entry in declared.items():
        if not isinstance(code, str) or not ERROR_CODE_RE.fullmatch(code):
            raise ValueError("errors 의 코드는 대문자, 숫자, 밑줄로 64자까지다")
        if isinstance(entry, dict):
            if "category" not in entry or set(entry) - {"category", "recovery", "details"}:
                raise ValueError("errors 의 객체 항목은 category 와 선택 칸 recovery, details 만 갖는다")
            recovery = entry.get("recovery")
            details = entry.get("details", [])
            if recovery is not None and recovery not in ERROR_RECOVERIES:
                raise ValueError("errors 의 recovery 는 복구 어휘 가운데 하나다")
            if (not isinstance(details, list) or len(details) > ERROR_DETAILS_MAX
                    or any(not isinstance(key, str) or not FIELD_KEY_RE.fullmatch(key) for key in details)
                    or len(set(details)) != len(details)):
                raise ValueError("errors 의 details 는 겹치지 않는 칸 이름 %d개까지다" % ERROR_DETAILS_MAX)
            entry = entry["category"]
            if entry == OUTCOME_UNKNOWN:
                # 실행 경로가 504 로 답하는 코드다. 실패로 기록되지 않으므로 복구 정보를 실을 자리가 없다.
                raise ValueError("outcome_unknown 인 코드는 복구 계약을 갖지 않는다")
            contracts[code] = {"recovery": recovery, "details": tuple(details)}
        if not isinstance(entry, str) or entry not in ERROR_WORDS:
            raise ValueError("errors 의 값은 공통 어휘 다섯 가운데 하나다")
        words[code] = entry
    return words, contracts


def _connector_manifest(connector_id: str) -> dict | None:
    """운영 목록에 있고 검증을 통과한 커넥터의 manifest 다. 아니면 경고 한 줄을 남기고 None 이다."""
    entry = _connector_roots().get(connector_id)
    if entry is None:
        logger.warning("dashboard-profile-api: 커넥터 %r 가 운영 목록에 없다", connector_id)
        return None
    try:
        return _load_connector(connector_id, entry)
    except Exception as error:
        # 파일 내용과 운영 경로를 로그에 싣지 않는다. 직접 낸 사유만 그대로 적는다.
        reason = str(error) if type(error) is ValueError else type(error).__name__
        logger.warning("dashboard-profile-api: 커넥터 %r 를 쓸 수 없다: %s", connector_id, reason)
        return None


def _connector_catalog() -> dict[str, dict]:
    """운영 목록의 커넥터 가운데 검증을 통과한 manifest 다."""
    manifests = {name: _connector_manifest(name) for name in _connector_roots()}
    return {name: manifest for name, manifest in manifests.items() if manifest is not None}


def _connector_env_keys() -> tuple[frozenset, frozenset]:
    """카탈로그 manifest 의 칸 env 이름과 운영자 env 이름이다. 칸 이름과 겹치는 운영자 이름은 칸으로 본다."""
    fields, operator = set(), set()
    for manifest in _connector_catalog().values():
        fields.update(field["env"] for field in manifest["fields"])
        operator.update(manifest["operator_env"])
    return frozenset(fields), frozenset(operator - fields)


def _entry_mode(entry) -> str:
    """소유 기록 항목의 설치 방식이다. 칸이 없으면 옛 설치다."""
    return entry.get("mode", ISOLATED_MODE) if isinstance(entry, dict) else ISOLATED_MODE


def _server_matches(manifest: dict, server: dict) -> bool:
    """소유 기록의 서버 정의가 지금 manifest 의 실행 정의와 같은지 본다.

    칸의 env 는 자기 이름의 참조이고, 선택 칸은 명시한 빈 값도 된다.
    운영자 env 는 옛 기록의 `${이름}` 참조와 지금 정의의 직접 값을 같다고 본다.
    옛 판이 남긴 기록을 그대로 인정해야 이미 설치한 연결이 끊기지 않는다(ADR-041).
    `tools` 는 견주지 않는다. 옛 기록에는 그 키가 없고, 다시 보낸 설치가 지금 manifest 의 값으로 덮어쓴다.
    주인의 첨부 디렉터리 env 는 설치마다 그 주인의 값이라 manifest 와 견주지 않는다. 빈 값이거나
    `<루트>/users/<64자리 16진수>` 모양인지만 본다. 참조(`${...}`)를 받으면 profile `.env` 가 경로를 정하게 된다(ADR-20261007 connector-owner-attachments).
    """
    expected = manifest["server"]
    if (server["command"] != expected["command"] or server["args"] != expected["args"]
            or set(server["env"]) != set(expected["env"])):
        return False
    for name, value in server["env"].items():
        reference = "${%s}" % name
        if name == manifest["owner_attachments_env"]:
            if value != "" and not OWNER_ATTACHMENTS_VALUE_RE.match(value):
                return False
        elif name in manifest["operator_env"]:
            if value not in (reference, expected["env"][name]):
                return False
        elif value != reference and not (value == "" and name in manifest["optional_env"]):
            return False
    return True


def _connector_catalog_response():
    """검증을 통과한 커넥터의 카탈로그다. 운영자 env 의 이름과 값, 오류 대응 표, 스킬 본문은 담지 않는다.

    `skills` 는 바인딩 설치가 profile 에 복사할 스킬의 이름이다. 이름 순이다.
    """
    from starlette.responses import JSONResponse

    return JSONResponse(
        [{"id": manifest["id"], "schema": manifest["schema"], "title": manifest["title"],
          "description": manifest["description"],
          "fields": manifest["fields"], "verify": manifest["verify"], "mcp_server": manifest["mcp_server"],
          "toolsets": manifest["toolsets"], "attachments": manifest["attachments"],
          "skills": sorted(manifest["skills"]),
          # 사람 말 제목이 없는 도구는 `title` 을 내지 않는다. 읽는 쪽이 도구 이름을 보인다.
          "tools": {name: {key: value for key, value in policy.items() if value is not None}
                    for name, policy in manifest["tools"].items()}}
         for manifest in _connector_catalog().values()],
        status_code=200,
    )
