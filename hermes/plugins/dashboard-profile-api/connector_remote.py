"""원격 MCP 서버 정의를 검증하고 소유 기록의 원격 정의를 읽는다(ADR-20261026 remote-mcp-plugin).

MCP 정의의 서버가 `type: http` 인 커넥터는 자식 프로세스 대신 Streamable HTTP 로 붙는다.
주소는 플러그인 파일이나 운영 목록에서만 오고, 사용자의 칸 값은 헤더 값의 `${이름}` 자리에만 들어간다.
예외 사유와 로그에 주소와 헤더 값을 싣지 않는다.
"""

from __future__ import annotations

from urllib.parse import urlsplit

from .connector_schema import REMOTE_FORBIDDEN_KEYS, REMOTE_HEADER_NAME_RE, REMOTE_HEADER_VALUE_RE


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
    return dict(value)


def _remote_server(entry: dict, server: dict, declared: dict, fields: list) -> dict:
    """원격 MCP 서버 정의를 검증하고 설치할 서버 정의 `{"url", "headers", "enabled"}` 를 낸다. 틀리면 ValueError 다."""
    if set(server) - {"type", "url", "headers"}:
        raise ValueError("원격 MCP 서버 정의 모양이 올바르지 않다")
    for key in REMOTE_FORBIDDEN_KEYS:
        if key in declared:
            raise ValueError("원격 MCP 서버는 %s 를 선언하지 않는다" % key)
    # 실행 파일과 운영자 env 는 자식 프로세스에 쓰는 값이다. 원격 커넥터의 항목에 있으면 운영 목록이 틀린 것이다.
    if entry.get("command") is not None or entry.get("env"):
        raise ValueError("원격 MCP 서버의 운영 목록 항목에 command 나 env 가 있다")
    # 플러그인 파일의 주소는 https 만 받는다. 운영 목록의 http 는 운영자가 믿은 내부망 구간이다.
    if "url" in server and not _remote_url_ok(server["url"], ("https",)):
        raise ValueError("원격 MCP 서버 정의의 url 이 https 주소가 아니거나 받지 않는 부분이 있다")
    operator_url = entry.get("url")
    if operator_url is not None and not _remote_url_ok(operator_url, ("http", "https")):
        raise ValueError("운영 목록의 url 이 http 나 https 주소가 아니거나 받지 않는 부분이 있다")
    url = operator_url if operator_url is not None else server.get("url")
    if url is None:
        raise ValueError("원격 MCP 서버의 url 이 없다")
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
    return {"url": url, "headers": headers, "enabled": True}


def _remote_server_matches(expected: dict, recorded: dict) -> bool:
    """소유 기록의 원격 서버 정의가 지금 manifest 의 정의와 같은지 본다. `tools` 는 견주지 않는다."""
    return recorded.get("url") == expected["url"] and recorded.get("headers") == expected["headers"]


def _remote_record_ok(server) -> bool:
    """소유 기록의 원격 서버 정의 모양을 본다. `tools` 의 모양은 부르는 쪽이 본다."""
    return (isinstance(server, dict) and set(server) - {"tools"} == {"url", "headers", "enabled"}
            and isinstance(server["url"], str) and server["enabled"] is True and isinstance(server["headers"], dict)
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
