"""커넥터 입력 칸, 도구 정책, 오류 계약을 검증한다."""

from __future__ import annotations

import hashlib
import re

from .common import (
    BASE_ENV_KEYS,
)

from .connector_schema import (
    ENV_NAME_RE,
    ERROR_CODE_RE,
    ERROR_DETAILS_MAX,
    ERROR_RECOVERIES,
    ERROR_WORDS,
    FIELD_KEY_RE,
    HERMES_TOOL_NAME_MAX_CHARS,
    OUTCOME_UNKNOWN,
    SECRET_ARGUMENT_NAMES,
    SECRET_ARGUMENT_SUFFIXES,
    TOOL_APPROVALS,
    TOOL_IDENTIFIER_RE,
    TOOL_NAME_RE,
    TOOL_RISKS,
    TOOL_RISK_DEFAULTS,
    TOOL_TITLE_MAX_CHARS,
)


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

    규칙은 `hermes/docs/hermes-contract.md` 의 「MCP 도구의 등록 이름」 이 갖는다.
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
