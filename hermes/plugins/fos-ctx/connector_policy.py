from __future__ import annotations

import json
import os
import sqlite3
import urllib.request
from .context import (
    CTX_VERSION,
    _read_token,
    logger,
    root_session,
    sign_policy,
    signing_key,
)



# 커넥터 도구 호출의 판정을 물을 Control Plane 주소다. 경로는 fos-assistant 가 정한다.
POLICY_URL_ENV = "FOS_CTX_POLICY_URL"
# 판정을 기다리는 동안 run 의 스레드가 묶인다. 한 번만 부르고 이 시간 안에 답이 없으면 막는다.
POLICY_TIMEOUT = 3.0
# 판정에 보내는 인자 글의 UTF-8 바이트 상한이다. Control Plane 은 이보다 작은 글도 크다고 거절하므로,
# 이 상한은 판정할 수 없는 큰 본문을 보내 Control Plane 이 요청째로 버리는 일을 막는 값이다.
POLICY_ARGS_MAX_BYTES = 60 * 1024
# 커넥터를 설치한 profile 디렉터리에 설치가 쓰는 이름 대응 파일이다. 이 파일이 있어야 커넥터 정책이 걸린다.
CONNECTOR_TOOL_MAP = ".fos-connector-tools.json"
MCP_PREFIX = "mcp__"
# 코드 안에서 도구를 부르는 내장 도구다. 그 안의 호출은 이 hook 이 실행 맥락을 알 수 없다.
CODE_EXECUTION_TOOL = "execute_code"
POLICY_BLOCK_MESSAGE = "fos-ctx: 이 도구 호출의 사용 정책을 확인하지 못해 막았다. 잠시 뒤 다시 시도하라고 사용자에게 알린다."
CONTEXT_BLOCK_MESSAGE = "fos-ctx: 이 도구 호출의 실행 맥락이 없어 막았다."
UNKNOWN_SERVER_MESSAGE = "fos-ctx: 이 연결에 등록되지 않은 도구라 막았다. 다시 부르지 않는다."
ARGS_TOO_LARGE_MESSAGE = "fos-ctx: 인자가 너무 커서 실행하지 않았다. 나눠서 요청한다."
CODE_EXECUTION_MESSAGE = "fos-ctx: 이 연결에서는 코드 실행으로 도구를 부를 수 없다."


def read_tool_map(home=None):
    """이름 대응 파일의 `(servers, isolated)` 를 돌려준다. 파일이 없으면 `(None, True)` 다.

    `isolated` 는 옛 설치 profile 이면 참, 바인딩 profile 이면 거짓이다. 칸이 없으면 옛 설치로 읽는다.
    읽지 못하면 OSError, JSON 이 아니거나 모양이 틀리면 ValueError 다. 모양이 틀린 파일을 빈 대응으로
    읽으면 판정 없이 통과하는 도구가 생기므로 서버 하나의 모양까지 본다.
    """
    if home is None:
        from hermes_constants import get_hermes_home

        home = get_hermes_home()
    try:
        raw = (home / CONNECTOR_TOOL_MAP).read_bytes()
    except FileNotFoundError:
        return None, True
    # JSONDecodeError 와 UnicodeDecodeError 는 ValueError 다.
    parsed = json.loads(raw.decode("utf-8"))
    if not isinstance(parsed, dict) or type(parsed.get("v")) is not int or parsed["v"] != 1:
        raise ValueError("tool map version")
    isolated = parsed.get("isolated", True)
    # 거짓으로 읽히는 다른 값(0, 빈 글)을 바인딩으로 읽으면 대응에 없는 도구가 판정 없이 나간다.
    if type(isolated) is not bool:
        raise ValueError("tool map isolated")
    servers = parsed.get("servers")
    if not isinstance(servers, dict):
        raise ValueError("tool map servers")
    for server in servers.values():
        if not isinstance(server, dict):
            raise ValueError("tool map server")
        prefix, tools = server.get("prefix"), server.get("tools")
        # 서버 이름이 빈 접두사는 `mcp__` 도구 전체와 맞아 버린다.
        if not isinstance(prefix, str) or not prefix.startswith(MCP_PREFIX) or len(prefix) <= len(MCP_PREFIX):
            raise ValueError("tool map prefix")
        if not isinstance(tools, dict) or not all(
                isinstance(name, str) and isinstance(tool, str) for name, tool in tools.items()):
            raise ValueError("tool map tools")
    return servers, isolated


def _block(message: str) -> dict:
    return {"action": "block", "message": message}


def _post_json(url: str, body: dict, token: str, timeout: float):
    """한 번 POST 하고 `(상태 코드, 읽은 JSON)` 을 돌려준다. 본문이 JSON 이 아니면 둘째가 None 이다."""
    request = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as exc:
        exc.close()
        return exc.code, None
    try:
        return status, json.loads(raw.decode("utf-8"))
    except ValueError:
        return status, None


def _connector_server(tool_name: str, servers: dict):
    """그 도구를 낸 대응 파일의 서버를 고른다. 맞는 서버가 없으면 None 이다."""
    # 등록 이름을 가진 서버가 먼저다. 서버 이름이 다른 서버 이름의 앞부분이면 접두사만으로는 엉뚱한 서버가 잡힌다.
    server = next((value for value in servers.values() if tool_name in value["tools"]), None)
    if server is None:
        matched = [value for value in servers.values() if tool_name.startswith(value["prefix"])]
        server = max(matched, key=lambda value: len(value["prefix"]), default=None)
    return server


def connector_policy(tool_name: str, args, session_id: str, tool_call_id: str, servers: dict):
    """커넥터 도구 호출을 Control Plane 에 묻는다. 통과면 None, 아니면 글이 든 `block` 이다."""
    server = _connector_server(tool_name, servers)
    if server is None:
        return _block(UNKNOWN_SERVER_MESSAGE)
    if args is None:
        args = {}
    if not session_id or not tool_call_id or not isinstance(args, dict):
        return _block(CONTEXT_BLOCK_MESSAGE)
    tool = server["tools"].get(tool_name)
    url = os.environ.get(POLICY_URL_ENV, "").strip()
    token = _read_token()
    if not url or not token:
        return _block(POLICY_BLOCK_MESSAGE)
    try:
        root = root_session(session_id)
    except sqlite3.Error as exc:
        # `build_context` 와 같다. 최상위 run 이면 맞는 값이고, 아니면 서버가 소유 판정에서 막는다.
        logger.warning("fos-ctx: state.db 에서 루트 session 을 찾지 못했다: %s", type(exc).__name__)
        root = session_id
    args_json = json.dumps(args, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    if len(args_json.encode("utf-8")) > POLICY_ARGS_MAX_BYTES:
        return _block(ARGS_TOO_LARGE_MESSAGE)
    body = {
        "v": CTX_VERSION,
        "root_session_id": root,
        "session_id": session_id,
        "tool_call_id": tool_call_id,
        "hermes_tool": tool_name,
        "tool": tool,
        "args_json": args_json,
        "sig": sign_policy(signing_key(token), tool_name, root, session_id, tool_call_id, args_json),
    }
    status, answer = _post_json(url, body, token, POLICY_TIMEOUT)
    if status != 200 or not isinstance(answer, dict):
        logger.warning("fos-ctx: 커넥터 정책 질의가 쓸 수 있는 답을 주지 않았다: HTTP %d", status)
        return _block(POLICY_BLOCK_MESSAGE)
    decision = answer.get("decision")
    if decision == "allow":
        return None
    message = answer.get("message")
    if decision == "block" and isinstance(message, str) and message.strip():
        return _block(message)
    return _block(POLICY_BLOCK_MESSAGE)
