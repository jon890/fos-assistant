"""원격 MCP 서버 정의를 검증하고 소유 기록의 원격 정의를 읽는다(ADR-20261026 remote-mcp-plugin).

MCP 정의의 서버가 `type: http` 인 플러그인은 자식 프로세스 대신 Streamable HTTP 로 붙는다.
주소는 운영 목록에서만 오고, 사용자의 칸 값은 헤더 값의 `${이름}` 자리에만 들어간다.
예외 사유와 로그에 주소와 헤더 값을 싣지 않는다.
"""

from __future__ import annotations

import contextlib
import contextvars
import logging
import re
from typing import Optional
from urllib.parse import urlsplit

from .connector_schema import REMOTE_FORBIDDEN_KEYS, REMOTE_HEADER_NAME_RE, REMOTE_HEADER_VALUE_RE
from .connector_remote_body import _cap_remote_response


# Hermes v0.21.6 agent/secret_scope.py 의 _GLOBAL_ENV_EXACT, _GLOBAL_ENV_PREFIXES 가 근거다.
# 배포·중계 관련 이름은 같은 계열의 비밀값도 칸으로 받지 않도록 접두 전체를 막는다.
REMOTE_ENV_NAME_RE = re.compile(r"[A-Z][A-Z0-9_]{0,127}")
REMOTE_GLOBAL_ENV = frozenset({"PATH", "HOME", "USER", "LANG", "LC_ALL", "TZ", "PWD", "SHELL", "TMPDIR",
                               "VIRTUAL_ENV", "PYTHONPATH", "SSL_CERT_FILE"})
REMOTE_GLOBAL_PREFIXES = ("HERMES_", "TERMINAL_", "GATEWAY_RELAY_", "API_SERVER_")


# 대시보드 plugin 이나 MCP 전송이 직접 정하는 헤더다. 플러그인이 선언하면 요청 모양이 바뀌므로 받지 않는다.
REMOTE_RESERVED_HEADERS = frozenset({"host", "content-length", "content-type", "transfer-encoding", "connection",
                                     "accept", "accept-encoding", "mcp-session-id", "mcp-protocol-version", "last-event-id"})


# 원격 호출 동안 기록을 버리는 logger 다. 요청 주소, 보낸 메시지, 예외 문자열에 주소와 헤더 값과 인자가 섞인다.
# 기록은 그 기록을 남긴 logger 의 필터만 거치므로 하위 logger 이름까지 적는다.
REMOTE_QUIET_LOGGERS = ("httpx2", "httpcore2.connection", "httpcore2.http11", "httpcore2.http2", "httpcore2.proxy",
                        "httpcore2.socks", "mcp.client.streamable_http", "mcp.shared.jsonrpc_dispatcher",
                        "mcp.shared.dispatcher", "client")


# 지금 실행 문맥이 원격 호출 안인가. 같은 logger 를 쓰는 로컬 플러그인 호출의 기록은 그대로 남긴다.
_remote_call = contextvars.ContextVar("connector_remote_call", default=False)


class _RemoteCallFilter(logging.Filter):
    """원격 호출 문맥에서 남은 기록을 수준과 상관없이 버린다."""

    def filter(self, record):
        return not _remote_call.get()


_REMOTE_CALL_FILTER = _RemoteCallFilter()


def _remote_url_ok(url, schemes: tuple) -> bool:
    """주소가 그 scheme 의 호스트 주소이고 계정 정보, 쿼리, fragment, `$` 치환, 공백과 제어 글자가 없는지 본다."""
    if (not isinstance(url, str) or "$" in url or "@" in url or "?" in url or "#" in url
            or any(ch.isspace() or ord(ch) < 0x20 or ord(ch) >= 0x7f for ch in url)):
        return False
    try:
        parts = urlsplit(url)
        # 틀린 포트는 `port` 를 읽을 때 ValueError 다.
        port_ok = parts.port is None or parts.port > 0
    except ValueError:
        return False
    return parts.scheme in schemes and bool(parts.hostname) and port_ok


def _remote_headers(value, where: str) -> dict:
    """헤더 선언 하나를 읽는다. 없으면 빈 dict 다."""
    if value is None:
        return {}
    if not isinstance(value, dict) or any(
            not isinstance(name, str) or not REMOTE_HEADER_NAME_RE.fullmatch(name)
            or not isinstance(item, str) or not REMOTE_HEADER_VALUE_RE.fullmatch(item) for name, item in value.items()):
        raise ValueError("%s 의 headers 는 이름과 `${이름}` 이나 `Bearer ${이름}` 값의 object 다" % where)
    if any(name.lower() in REMOTE_RESERVED_HEADERS for name in value):
        raise ValueError("%s 의 headers 에 대시보드나 MCP 전송이 정하는 헤더 이름이 있다" % where)
    return dict(value)


def _portable_remote_server(name: str, server: dict) -> dict:
    """이식형 전송과 API key 인증을 기존 원격 정의로 바꾼다. UI 메타데이터는 실행하지 않는다."""
    result = {key: value for key, value in server.items() if key not in ("extensions", "_meta")}
    result["type"] = "http"
    extensions = server.get("extensions", {})
    if not isinstance(extensions, dict) or not isinstance(extensions.get("com.openai", {}), dict):
        raise ValueError("MCP 서버의 extensions 모양이 올바르지 않다")
    openai = extensions.get("com.openai", {})
    if "auth" not in openai:
        return result
    auth = openai["auth"]
    if not isinstance(auth, dict):
        raise ValueError("MCP 서버의 auth 는 객체다")
    if auth == {"type": "none"}:
        return result
    scheme = auth.get("headerScheme")
    allowed = {"type", "headerScheme"} | ({"headerName"} if scheme == "custom_header" else set())
    if (auth.get("type") != "api_key" or scheme not in ("bearer", "custom_header")
            or set(auth) - allowed):
        raise ValueError("MCP 서버의 인증 방식을 받지 않는다")
    env = re.sub(r"[^A-Z0-9_]", "_", name.upper()) + "_API_KEY"
    header = "Authorization" if scheme == "bearer" else auth.get("headerName")
    template = ("Bearer " if scheme == "bearer" else "") + "${%s}" % env
    inferred = _remote_headers({header: template}, "MCP 서버 인증")
    headers = _remote_headers(result.get("headers"), "MCP 서버 정의")
    if any(key.lower() == header.lower() for key in headers):
        raise ValueError("같은 헤더 이름이 두 번 선언됐다")
    result["headers"] = {**headers, **inferred}
    return result


def _remote_server(entry: dict, server: dict, declared: dict, fields: list) -> dict:
    """원격 MCP 서버 정의를 검증하고 설치할 서버 정의 `{"url", "headers", "strict_redirect_headers", "enabled"}` 를 낸다.

    틀리면 ValueError 다.
    """
    if set(server) - {"type", "url", "headers"}:
        raise ValueError("원격 MCP 서버 정의 모양이 올바르지 않다")
    for key in REMOTE_FORBIDDEN_KEYS:
        if key in declared:
            raise ValueError("원격 MCP 서버는 %s 를 선언하지 않는다" % key)
    # 실행 파일과 운영자 env 는 자식 프로세스에 쓰는 값이다. 원격 플러그인의 항목에 있으면 운영 목록이 틀린 것이다.
    if entry.get("command") is not None or entry.get("env"):
        raise ValueError("원격 MCP 서버의 운영 목록 항목에 command 나 env 가 있다")
    # 플러그인 갱신으로 토큰의 목적지가 바뀌지 않게 파일의 url 은 무시한다.
    operator_url = entry.get("url")
    if operator_url is None:
        raise ValueError("원격 MCP 서버의 운영 목록에 url 이 없다")
    if not _remote_url_ok(operator_url, ("http", "https")):
        raise ValueError("운영 목록의 url 이 http 나 https 주소가 아니거나 받지 않는 부분이 있다")
    if any(not REMOTE_ENV_NAME_RE.fullmatch(field["env"]) or field["env"] in REMOTE_GLOBAL_ENV
           or field["env"].startswith(REMOTE_GLOBAL_PREFIXES) for field in fields):
        raise ValueError("원격 MCP 서버의 칸 env 이름이 올바르지 않거나 Hermes 전역 이름이다")
    headers = _remote_headers(server.get("headers"), "MCP 서버 정의")
    declared_headers = _remote_headers(declared.get("headers"), "assistant.json")
    names = [name.lower() for name in (*headers, *declared_headers)]
    if len(set(names)) != len(names):
        raise ValueError("같은 헤더 이름이 두 번 선언됐다")
    headers.update(declared_headers)
    # 선택 칸은 빈 값이 헤더에 그대로 실리므로 받지 않는다. 칸과 헤더 참조는 서로 빠짐없이 맞아야 한다.
    if any(field.get("required", True) is False for field in fields):
        raise ValueError("원격 MCP 서버의 칸은 모두 필수 칸이다")
    if {field["env"] for field in fields} != _remote_header_env({"headers": headers}):
        raise ValueError("원격 MCP 서버의 칸과 헤더가 참조하는 이름이 다르다")
    # Hermes 는 redirect 를 따라간다. 이 키가 참이면 다른 origin 으로 갈 때 `Authorization` 밖의 설정 헤더도 지운다.
    return {"url": operator_url, "headers": headers, "strict_redirect_headers": True, "enabled": True}


def _remote_server_matches(expected: dict, recorded: dict) -> bool:
    """소유 기록의 원격 서버 정의가 지금 manifest 의 정의와 같은지 본다. `tools` 는 견주지 않는다."""
    return (recorded.get("url") == expected["url"] and recorded.get("headers") == expected["headers"]
            and recorded.get("strict_redirect_headers") is expected["strict_redirect_headers"])


def _remote_record_ok(server) -> bool:
    """소유 기록의 원격 서버 정의 모양을 본다. `tools` 의 모양은 부르는 쪽이 본다."""
    return (isinstance(server, dict) and set(server) - {"tools"} == {"url", "headers", "strict_redirect_headers", "enabled"}
            and isinstance(server["url"], str) and server["enabled"] is True and server["strict_redirect_headers"] is True
            and isinstance(server["headers"], dict)
            and all(isinstance(name, str) and isinstance(value, str) for name, value in server["headers"].items()))


def _remote_header_env(server) -> set:
    """원격 서버 정의의 헤더 값이 `${이름}` 으로 참조하는 이름이다."""
    headers = server.get("headers") if isinstance(server, dict) else None
    if not isinstance(headers, dict):
        return set()
    return {match.group(2) for value in headers.values()
            if isinstance(value, str) and (match := REMOTE_HEADER_VALUE_RE.fullmatch(value))}


def _is_remote(server) -> bool:
    """서버 정의가 원격 MCP 의 것인지 본다. 소유 기록과 manifest 의 정의 모두 `url` 을 가지면 원격이다."""
    return isinstance(server, dict) and "url" in server


def _remote_request_headers(server: dict, values: dict) -> dict:
    """서버 정의의 헤더 값에서 `${이름}` 을 칸 값으로 바꾼 요청 헤더다.

    참조한 칸 값이 없거나 비었거나 CR, LF, NUL 이 있으면 ValueError 다. 사유에 값을 싣지 않는다.
    부르는 쪽(`connector_run`)도 부르기 전에 같은 글자를 보고 까닭을 로그로 남긴다. 여기 검사는 헤더를 만드는 쪽의 마지막 방어다.
    """
    headers = {}
    for name, template in server["headers"].items():
        match = REMOTE_HEADER_VALUE_RE.fullmatch(template)
        value = values.get(match.group(2)) if match else None
        if not isinstance(value, str) or not value or any(ch in value for ch in "\r\n\0"):
            raise ValueError("원격 MCP 서버의 헤더에 넣을 칸 값이 없거나 받지 않는 글자가 있다")
        headers[name] = (match.group(1) or "") + value
    return headers


# 원격 서버가 칸 값을 받지 않았다는 응답 상태다. 도구 목록 경로가 `credential_rejected` 로 옮긴다.
REMOTE_REJECTED_STATUSES = frozenset({401, 403})


@contextlib.asynccontextmanager
async def _remote_streams(server: dict, values: dict, timeout: float, seen: Optional[dict] = None):
    """원격 MCP 서버에 Streamable HTTP 로 붙어 읽기와 쓰기 스트림을 내준다(ADR-20261026 remote-mcp-plugin).

    redirect 는 따라가지 않는다. 토큰 헤더가 다른 호스트로 가지 않고, redirect 응답은 호출 실패가 된다.
    SDK 의 기본 클라이언트는 redirect 를 따라가므로 클라이언트를 직접 만들어 넘긴다. 넘긴 클라이언트는 SDK 가 닫지 않는다.
    `seen` 을 주면 서버가 401 이나 403 으로 답했을 때 `seen["rejected"]` 를 참으로 둔다. 응답 본문과 주소는 담지 않는다.
    """
    import httpx2
    from mcp.client.streamable_http import streamable_http_client

    headers = _remote_request_headers(server, values)
    headers["Accept-Encoding"] = "identity"
    for name in REMOTE_QUIET_LOGGERS:
        # 같은 필터 객체는 한 번만 달린다. 원격 호출 문맥 밖의 기록은 그대로 지난다.
        logging.getLogger(name).addFilter(_REMOTE_CALL_FILTER)
    token = _remote_call.set(True)
    try:
        hooks = [_cap_remote_response]
        if seen is not None:
            async def note_rejected(response):
                if response.status_code in REMOTE_REJECTED_STATUSES:
                    seen["rejected"] = True
            # 크기 제한보다 먼저 본다. 거절 응답의 본문이 커도 상태는 남는다.
            hooks.insert(0, note_rejected)
        async with httpx2.AsyncClient(headers=headers, follow_redirects=False, timeout=timeout,
                                     event_hooks={"response": hooks}) as client:
            async with streamable_http_client(server["url"], http_client=client) as streams:
                yield streams
    finally:
        try:
            _remote_call.reset(token)
        except ValueError:
            # 정리가 다른 문맥에서 돌면 token 을 되돌리지 못한다. 그 문맥은 곧 버려지므로 그대로 둔다.
            pass
