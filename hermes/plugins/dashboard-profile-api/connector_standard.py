"""`assistant.json` 이 없는 표준 플러그인의 선언을 플러그인 manifest 와 MCP 정의에서 만든다(ADR-20261101 plugin-without-assistant-json).

만든 선언은 `assistant.json` 을 읽은 것과 같은 검증을 거친다. 여기서는 모양을 추론만 하고 검사는 부르는 쪽에 맡긴다.
"""

from __future__ import annotations

from .connector_remote import REMOTE_ENV_NAME_RE, REMOTE_GLOBAL_ENV, REMOTE_GLOBAL_PREFIXES
from .connector_schema import REMOTE_HEADER_VALUE_RE

# 카탈로그의 schema 값이다. `assistant.json` 은 이 값을 선언하지 못한다. 도구 정책을 선언하지 않고, 모든 도구를 호출마다 승인받는다.
STANDARD_SCHEMA = 3

# 칸의 key 는 env 이름을 소문자로 바꾼 것이다. `FIELD_KEY_RE` 의 길이에 맞춘다. 어긋나면 칸 검증이 거절한다.
_FIELD_KEY_MAX_CHARS = 32


def _single_server(mcp):
    """MCP 정의에서 서버 정의 하나를 꺼낸다. 모양이 틀리면 None 이다. 틀린 모양은 뒤의 검증이 사유를 낸다."""
    if isinstance(mcp, dict) and set(mcp) == {"mcpServers"}:
        mcp = mcp["mcpServers"]
    if not isinstance(mcp, dict) or len(mcp) != 1:
        return None
    server = next(iter(mcp.values()))
    return server if isinstance(server, dict) else None


def _field(env: str, required: bool) -> dict:
    # 누가 만들었는지 모르는 플러그인이다. 원격 칸과 같은 규칙으로 OS, Python, Hermes 전역 이름을 칸으로 받지 않는다.
    if (not REMOTE_ENV_NAME_RE.fullmatch(env) or env in REMOTE_GLOBAL_ENV
            or env.startswith(REMOTE_GLOBAL_PREFIXES)):
        raise ValueError("표준 플러그인의 칸 env 이름이 올바르지 않거나 Hermes 전역 이름이다")
    # 값이 비밀인지 알 수 없어 가린다. 사람 말 이름이 없어 env 이름을 그대로 보인다.
    return {"key": env.lower()[:_FIELD_KEY_MAX_CHARS], "env": env, "label": env, "secret": True, "required": required}


def _inferred_fields(server) -> list:
    """서버 정의의 env 와 헤더 참조에서 입력 칸을 만든다.

    로컬 서버는 env 마다 칸 하나다. `${이름}` 은 필수 칸, `${이름:-}` 은 선택 칸이다.
    다른 값(리터럴, 다른 이름의 참조, 다른 기본값)은 받지 않는다. 플러그인 파일의 값이 그대로 자식 env 로 가거나
    다른 칸의 값이 섞이기 때문이다. 원격 서버는 헤더 값의 `${이름}` 마다 필수 칸 하나다. 헤더 모양은 원격 검증이 본다.
    """
    if server is None:
        return []
    if server.get("type", "stdio") == "http":
        headers = server.get("headers") or {}
        if not isinstance(headers, dict):
            return []
        names = []
        for value in headers.values():
            match = REMOTE_HEADER_VALUE_RE.fullmatch(value) if isinstance(value, str) else None
            if match and match.group(2) not in names:
                names.append(match.group(2))
        return [_field(name, True) for name in names]
    env = server.get("env", {})
    if not isinstance(env, dict):
        return []
    fields = []
    for name, value in env.items():
        if value == "${%s}" % name:
            fields.append(_field(name, True))
        elif value == "${%s:-}" % name:
            fields.append(_field(name, False))
        else:
            raise ValueError("표준 플러그인의 MCP 서버 env 는 자기 이름의 참조만 받는다")
    return fields


def _standard_declaration(connector_id: str, plugin: dict, mcp) -> dict:
    """`assistant.json` 대신 쓸 선언이다. 제목은 manifest 의 `displayName`, 없으면 이름이다.

    서버의 env 를 비워 두는 MCP 정의는 `env` 칸이 없을 수 있다. 로컬 서버의 검증이 `env` 를 객체로 요구하므로 빈 객체를 채운다.
    """
    server = _single_server(mcp)
    if server is not None and server.get("type", "stdio") != "http":
        server.setdefault("env", {})
        server.setdefault("args", [])
    title = plugin.get("displayName")
    description = plugin.get("description", "")
    return {
        "schema": STANDARD_SCHEMA,
        "id": connector_id,
        "title": title if isinstance(title, str) and title else connector_id,
        "description": description if isinstance(description, str) else "",
        "fields": _inferred_fields(server),
    }
