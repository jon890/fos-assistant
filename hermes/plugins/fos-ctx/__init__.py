"""Control Plane MCP 호출 인자에 run 맥락 `_fos_ctx` 를 덮어쓰고 HMAC 으로 서명한다.

Control Plane 의 `agent_*` 도구는 이 값으로 부모 실행을 찾는다. 계약의 정본은 fos-assistant
`docs/hermes/fos-ctx.md` 의 「`_fos_ctx` 계약」 이고, 이 파일은 그 계약을 그대로 따른다.

- key 는 그 profile 의 MCP 토큰을 SHA-256 한 소문자 16진수 문자열의 UTF-8 바이트다.
  서버는 토큰 원문 대신 이 해시만 저장하므로 같은 key 를 갖는다.
- 서명할 글은 `v1`, 서버 쪽 도구 이름, 루트 session, session, tool_call_id 를 줄바꿈 하나로 잇는다.
  도구 인자는 넣지 않는다. Python 과 Java 의 JSON 직렬화를 글자까지 맞추기 어렵다.

Hermes 는 hook 이 돌려준 `args` 를 원래 인자에 얕게 병합하고 hook 의 키가 뒤에 온다.
그래서 모델이 같은 키를 넣어도 이 값이 이긴다. hook 이 `None` 을 돌려주면 서명 없이 나가고,
hook 이 예외를 던지면 호출이 막힌다.

`agent_*` 와 `memory_read`, `artifact_write` 는 서명하지 못하면 막는다.
서버도 서명 없는 호출을 거절하지만, 여기서 막으면 모델이 받는 오류가 원인을 말한다.
그 밖의 Control Plane 도구는 서명할 수 있으면 붙이고 없으면 원래 인자 그대로 보낸다.

## 자식 session 등록

`delegate_task` 의 자식은 부모 run 이 끝난 뒤에도 백그라운드로 돌 수 있다.
그때 Control Plane 은 자식의 MCP 호출에서 요청자를 부모 run 으로 찾지 못하므로,
자식을 만드는 순간 `subagent_start` hook 이 Control Plane 에 자식 session 의 부모와 루트를 등록한다.

- Hermes 는 자식을 만드는 `_build_children` 안에서 부모 스레드로 이 hook 을 동기로 부른다.
  그래서 등록은 자식의 첫 도구 호출보다 먼저 끝난다
- 주소는 환경 변수 `FOS_CTX_SUBAGENT_URL` 이 갖는다. 없으면 등록하지 않는다
- 서명 key 는 `_fos_ctx` 와 같다. 서명할 글은 `v1-subagent`, 부모의 루트 session, 부모 session,
  자식 session 을 줄바꿈 하나로 잇는다. 인증 헤더는 MCP 와 같은 profile 토큰이다
- 제한 시간 `REGISTER_TIMEOUT` 초로 부르고, 연결 실패와 5xx 에만 한 번 더 부른다
- 실패하면 로그만 남긴다. 예외를 내지 않는다. 등록이 없는 자식의 호출은 Control Plane 이 거절한다
- 토큰, 서명, 본문은 로그에 남기지 않는다

## 커넥터 정책

profile 디렉터리에 이름 대응 파일 `.fos-connector-tools.json` 이 있으면 그 profile 은 연결용 profile 이다.
그 profile 에서는 커넥터 MCP 도구 호출마다 Control Plane 에 묻고 답대로 한다(fos-assistant ADR-049).
계약의 정본은 fos-assistant `docs/backend/connector-tool-policy.md` 의 「도구 호출 판정」 이고, 이 파일은 그 계약을 그대로 따른다.

- 대응 파일이 없으면 이 절의 처리를 하지 않는다. 일반 에이전트의 도구는 건드리지 않는다
- 대응 파일을 읽지 못하면 `mcp__` 도구와 `execute_code` 를 모두 막는다. Control Plane MCP 의 도구도 막는다.
  연결용 profile 일 수 있고, 연결용 profile 에는 Control Plane MCP 가 없다
- 대응 파일의 서버를 Control Plane MCP 의 접두사보다 먼저 본다. 대응 파일의 서버와 맞는 도구는 등록 이름이
  Control Plane MCP 의 접두사로 시작해도 판정으로 보내고 `_fos_ctx` 를 붙이지 않는다
- 대응 파일의 어느 서버와도 맞지 않는 Control Plane MCP 도구는 위와 같이 `_fos_ctx` 를 붙인다
- `execute_code` 는 막는다. 실행 맥락 없이 도구를 부르는 경로다
- 서버는 등록 이름이 `tools` 에 있는 서버를 먼저 고르고, 없을 때만 `prefix` 가 맞는 서버를 고른다.
  `prefix` 가 여럿 맞으면 가장 긴 것을 고른다. 서버 `a` 와 `a__b` 가 함께 있을 때 `a__b` 의 도구가 `a` 로 읽히지 않는다
- 대응 파일의 어느 서버 `prefix` 와도 맞지 않는 `mcp__` 도구는 막는다
- session 이나 tool_call_id 가 없으면 막는다
- 주소는 환경 변수 `FOS_CTX_POLICY_URL` 이 갖는다. 주소나 토큰이 없으면 막는다
- 인자는 키를 정렬하고 공백 없이 직렬화한 글로 보내고 그 글을 서명한다. 서명할 글은 `v1-connector-policy`,
  등록 이름, 루트 session, session, tool_call_id, 인자 글의 SHA-256 을 줄바꿈 하나로 잇는다
- 인자 글의 UTF-8 바이트가 `POLICY_ARGS_MAX_BYTES` 를 넘으면 Control Plane 에 보내지 않고 막는다
- 제한 시간 `POLICY_TIMEOUT` 초로 한 번만 부른다. 기다리는 동안 run 의 스레드가 묶이므로 다시 부르지 않는다
- 답이 200 의 `allow` 일 때만 통과한다. 200 의 `block` 이고 글이 있으면 그 글로 막고, 그 밖은 정해 둔 글로 막는다
- 막을 때는 늘 비지 않은 글이 든 `block` 을 돌려준다. Hermes 는 글이 없는 `block` 과 `None` 을 통과로 읽는다
- 어떤 예외든 잡아 정해 둔 글로 막는다. 예외를 던지면 본문 일부가 모델에게 간다
- 토큰, 서명, 인자, 응답 본문은 로그에 남기지 않는다

`skill_manage` 는 막는다. 올린 스킬은 Control Plane 이 쓰고 Hermes 는 읽기만 하는데,
모델이 같은 이름의 로컬 스킬을 만들면 로컬이 먼저 선택되어 올린 스킬이 가려진다.
읽기 전용 마운트로는 이것을 막지 못한다. 근거는 fos-assistant ADR-034 가 갖는다.

이 plugin 은 profile 마다 `profiles/<이름>/plugins/fos-ctx/` 에 두고 그 profile 에서 켠다.
Hermes 는 plugin 을 HERMES_HOME 마다 따로 읽어, root 에 두면 기본 profile 에만 걸린다.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import os
import sqlite3
import urllib.error
import urllib.request

logger = logging.getLogger(__name__)

# Hermes 가 MCP 도구에 붙이는 이름은 `mcp__<서버>__<도구>` 이고, 서버 이름의 `-` 는 `_` 로 바뀐다.
TOOL_PREFIX = "mcp__fos_assistant__"
# `hermes mcp add` 가 서버 이름에서 만드는 .env 키다. 운영 저장소의 등록 스크립트가 여기에 토큰을 쓴다.
KEY_NAME = "MCP_FOS_ASSISTANT_API_KEY"
# 서명이 없으면 막는 도구다.
REQUIRED_PREFIX = "agent_"
REQUIRED_TOOLS = frozenset({"memory_read", "artifact_write"})
CTX_VERSION = 1
# parent_session_id 사슬을 따라가는 한도다. 하위 에이전트와 압축 교체가 겹쳐도 이만큼 깊지 않다.
MAX_DEPTH = 16

# 모델이 스킬을 만들고 고치는 도구다. Hermes `tools/skill_manager_tool.py` 가 이 이름으로 등록한다.
SKILL_MANAGE_TOOL = "skill_manage"
SKILL_MANAGE_MESSAGE = "이 환경에서는 스킬을 대화로 만들거나 고칠 수 없다. 에이전트 관리 화면에서 올린다"

# 자식 session 을 등록할 Control Plane 주소다. 경로는 fos-assistant 가 정한다.
REGISTER_URL_ENV = "FOS_CTX_SUBAGENT_URL"
REGISTER_VERSION = "v1-subagent"
# 부모 스레드가 자식을 만드는 동안 기다리는 시간이다. 두 번 불러도 자식 시작이 몇 초만 늦는다.
REGISTER_TIMEOUT = 3.0

# 커넥터 도구 호출의 판정을 물을 Control Plane 주소다. 경로는 fos-assistant 가 정한다.
POLICY_URL_ENV = "FOS_CTX_POLICY_URL"
POLICY_VERSION = "v1-connector-policy"
# 판정을 기다리는 동안 run 의 스레드가 묶인다. 한 번만 부르고 이 시간 안에 답이 없으면 막는다.
POLICY_TIMEOUT = 3.0
# 판정에 보내는 인자 글의 UTF-8 바이트 상한이다. Control Plane 은 이보다 작은 글도 크다고 거절하므로,
# 이 상한은 판정할 수 없는 큰 본문을 보내 Control Plane 이 요청째로 버리는 일을 막는 값이다.
POLICY_ARGS_MAX_BYTES = 60 * 1024
# 연결용 profile 디렉터리에 설치가 쓰는 이름 대응 파일이다. 이 파일이 있어야 커넥터 정책이 걸린다.
CONNECTOR_TOOL_MAP = ".fos-connector-tools.json"
MCP_PREFIX = "mcp__"
# 코드 안에서 도구를 부르는 내장 도구다. 그 안의 호출은 이 hook 이 실행 맥락을 알 수 없다.
CODE_EXECUTION_TOOL = "execute_code"
POLICY_BLOCK_MESSAGE = "fos-ctx: 이 도구 호출의 사용 정책을 확인하지 못해 막았다. 잠시 뒤 다시 시도하라고 사용자에게 알린다."
CONTEXT_BLOCK_MESSAGE = "fos-ctx: 이 도구 호출의 실행 맥락이 없어 막았다."
UNKNOWN_SERVER_MESSAGE = "fos-ctx: 이 연결에 등록되지 않은 도구라 막았다. 다시 부르지 않는다."
ARGS_TOO_LARGE_MESSAGE = "fos-ctx: 인자가 너무 커서 실행하지 않았다. 나눠서 요청한다."
CODE_EXECUTION_MESSAGE = "fos-ctx: 이 연결에서는 코드 실행으로 도구를 부를 수 없다."

BLOCK_MESSAGE = (
    "fos-ctx: 이 호출의 run 맥락을 서명하지 못해 막았다. "
    "profile 의 MCP 토큰이나 session 정보가 없다."
)


def signing_key(token: str) -> str:
    """MCP 토큰에서 서명 key 를 만든다. 서버가 저장한 토큰 해시와 같은 문자열이다."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def sign(key: str, tool: str, root_session_id: str, session_id: str, tool_call_id: str) -> str:
    """계약의 서명을 소문자 16진수로 돌려준다. key 는 16진수를 풀지 않고 문자열 그대로 쓴다."""
    message = "\n".join(["v1", tool, root_session_id, session_id, tool_call_id])
    return hmac.new(key.encode("utf-8"), message.encode("utf-8"), hashlib.sha256).hexdigest()


def sign_subagent(key: str, parent_root_session_id: str, parent_session_id: str,
                  child_session_id: str) -> str:
    """자식 session 등록의 서명을 소문자 16진수로 돌려준다. key 는 `sign` 과 같다."""
    message = "\n".join([REGISTER_VERSION, parent_root_session_id, parent_session_id, child_session_id])
    return hmac.new(key.encode("utf-8"), message.encode("utf-8"), hashlib.sha256).hexdigest()


def sign_policy(key: str, hermes_tool: str, root_session_id: str, session_id: str, tool_call_id: str,
                args_json: str) -> str:
    """커넥터 정책 질의의 서명을 소문자 16진수로 돌려준다. key 는 `sign` 과 같다.

    인자는 글 그대로가 아니라 그 글의 SHA-256 을 넣는다. 받는 쪽도 받은 글을 그대로 해시한다.
    """
    digest = hashlib.sha256(args_json.encode("utf-8")).hexdigest()
    message = "\n".join([POLICY_VERSION, hermes_tool, root_session_id, session_id, tool_call_id, digest])
    return hmac.new(key.encode("utf-8"), message.encode("utf-8"), hashlib.sha256).hexdigest()


def _state_db_path():
    from hermes_constants import get_hermes_home

    return get_hermes_home() / "state.db"


def root_session(session_id: str, db_path=None) -> str:
    """state.db 의 parent_session_id 를 따라 처음 session 을 찾는다.

    delegate_task 하위 에이전트(source=subagent)와 압축으로 바뀐 session 을 모두 지난다.
    읽기 전용으로 열고, 사슬이 돌거나 한도를 넘으면 거기서 멈춘다.
    """
    path = db_path if db_path is not None else _state_db_path()
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=1.0)
    try:
        current = session_id
        seen = {current}
        for _ in range(MAX_DEPTH):
            row = con.execute(
                "SELECT parent_session_id FROM sessions WHERE id = ?", (current,)
            ).fetchone()
            if not row or not row[0] or row[0] in seen:
                break
            current = row[0]
            seen.add(current)
        return current
    finally:
        con.close()


def _read_token():
    from agent.secret_scope import get_secret

    return get_secret(KEY_NAME)


def build_context(tool: str, session_id: str, tool_call_id: str):
    """서명한 `_fos_ctx` 를 만든다. 재료가 하나라도 없으면 None 이다."""
    if not session_id or not tool_call_id:
        return None
    token = _read_token()
    if not token:
        return None
    try:
        root = root_session(session_id)
    except sqlite3.Error as exc:
        # 루트를 못 찾으면 이 session 을 루트로 쓴다. 최상위 run 이면 맞는 값이고,
        # 하위 에이전트라면 서버가 소유 판정에서 거절한다.
        logger.warning("fos-ctx: state.db 에서 루트 session 을 찾지 못했다: %s", type(exc).__name__)
        root = session_id
    return {
        "v": CTX_VERSION,
        "session_id": session_id,
        "root_session_id": root,
        "tool_call_id": tool_call_id,
        "sig": sign(signing_key(token), tool, root, session_id, tool_call_id),
    }


def read_tool_map(home=None):
    """이름 대응 파일의 `servers` 를 돌려준다. 파일이 없으면 None 이다.

    읽지 못하면 OSError, JSON 이 아니거나 모양이 틀리면 ValueError 다. 모양이 틀린 파일을 빈 대응으로
    읽으면 판정 없이 통과하는 도구가 생기므로 서버 하나의 모양까지 본다.
    """
    if home is None:
        from hermes_constants import get_hermes_home

        home = get_hermes_home()
    try:
        raw = (home / CONNECTOR_TOOL_MAP).read_bytes()
    except FileNotFoundError:
        return None
    # JSONDecodeError 와 UnicodeDecodeError 는 ValueError 다.
    parsed = json.loads(raw.decode("utf-8"))
    if not isinstance(parsed, dict) or type(parsed.get("v")) is not int or parsed["v"] != 1:
        raise ValueError("tool map version")
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
    return servers


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


def pre_tool_call(tool_name="", args=None, session_id="", tool_call_id="", **_):
    if tool_name == SKILL_MANAGE_TOOL:
        return {"action": "block", "message": SKILL_MANAGE_MESSAGE}
    if not isinstance(tool_name, str):
        return None
    control_plane = tool_name.startswith(TOOL_PREFIX)
    guarded = tool_name.startswith(MCP_PREFIX) or tool_name == CODE_EXECUTION_TOOL
    # 대응 파일을 Control Plane MCP 의 접두사보다 먼저 본다. 접두사를 먼저 보면 등록 이름이 그 접두사로 시작하는
    # 커넥터 도구가 판정 없이 `_fos_ctx` 를 받는다.
    try:
        servers = read_tool_map()
    except Exception as exc:  # noqa: BLE001 - 읽지 못한 까닭을 가리지 않고 커넥터로 갈 수 있는 호출을 막는다
        # 연결용 profile 일 수 있다. 그 profile 에는 Control Plane MCP 가 없으므로 그 접두사의 도구도 막는다.
        logger.warning("fos-ctx: 이름 대응 파일을 읽지 못했다: %s", type(exc).__name__)
        return _block(POLICY_BLOCK_MESSAGE) if guarded else None
    if servers is None:
        # 연결용 profile 이 아니다. 여기까지가 커넥터 정책이 없던 때와 같은 동작이다.
        return _control_plane_context(tool_name, session_id, tool_call_id) if control_plane else None
    if control_plane and _connector_server(tool_name, servers) is None:
        return _control_plane_context(tool_name, session_id, tool_call_id)
    if tool_name == CODE_EXECUTION_TOOL:
        return _block(CODE_EXECUTION_MESSAGE)
    if not tool_name.startswith(MCP_PREFIX):
        return None
    try:
        return connector_policy(tool_name, args, session_id or "", tool_call_id or "", servers)
    except Exception as exc:  # noqa: BLE001 - 예외를 던지면 본문 일부가 모델에게 간다
        # 예외 본문에 비밀값이 섞일 수 있어 종류만 남긴다.
        logger.warning("fos-ctx: 커넥터 정책을 확인하지 못했다: %s", type(exc).__name__)
        return _block(POLICY_BLOCK_MESSAGE)


def _control_plane_context(tool_name: str, session_id, tool_call_id):
    tool = tool_name[len(TOOL_PREFIX):]
    required = tool.startswith(REQUIRED_PREFIX) or tool in REQUIRED_TOOLS
    try:
        ctx = build_context(tool, session_id or "", tool_call_id or "")
    except Exception as exc:  # noqa: BLE001 - 서명하지 않아도 되는 도구는 예외로 막지 않는다
        # 예외 본문에 비밀값이 섞일 수 있어 종류만 남긴다.
        logger.warning("fos-ctx: %s 의 run 맥락을 만들지 못했다: %s", tool, type(exc).__name__)
        ctx = None
    if ctx is None:
        return {"action": "block", "message": BLOCK_MESSAGE} if required else None
    return {"action": "modify", "args": {"_fos_ctx": ctx}}


def build_registration(parent_session_id: str, child_session_id: str, child_subagent_id,
                       parent_subagent_id):
    """자식 session 등록 본문과 토큰을 돌려준다. 재료가 없으면 None 이다."""
    if not parent_session_id or not child_session_id:
        return None
    token = _read_token()
    if not token:
        return None
    try:
        root = root_session(parent_session_id)
    except sqlite3.Error as exc:
        logger.warning("fos-ctx: state.db 에서 부모의 루트 session 을 찾지 못했다: %s", type(exc).__name__)
        root = parent_session_id
    body = {
        "v": CTX_VERSION,
        "parent_session_id": parent_session_id,
        "parent_root_session_id": root,
        "child_session_id": child_session_id,
        "child_subagent_id": child_subagent_id,
        "parent_subagent_id": parent_subagent_id,
        "sig": sign_subagent(signing_key(token), root, parent_session_id, child_session_id),
    }
    return body, token


def _post(url: str, body: dict, token: str) -> int:
    request = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
    )
    try:
        with urllib.request.urlopen(request, timeout=REGISTER_TIMEOUT) as response:
            return response.status
    except urllib.error.HTTPError as exc:
        exc.close()
        return exc.code


def subagent_start(parent_session_id="", child_session_id="", child_subagent_id=None,
                   parent_subagent_id=None, **_):
    url = os.environ.get(REGISTER_URL_ENV, "").strip()
    if not url:
        logger.warning("fos-ctx: %s 가 없어 자식 session 을 등록하지 않았다", REGISTER_URL_ENV)
        return
    try:
        built = build_registration(parent_session_id or "", child_session_id or "",
                                   child_subagent_id, parent_subagent_id)
    except Exception as exc:  # noqa: BLE001 - hook 은 자식 실행을 막지 않는다
        logger.warning("fos-ctx: 자식 session 등록을 만들지 못했다: %s", type(exc).__name__)
        return
    if built is None:
        logger.warning("fos-ctx: MCP 토큰이나 session 정보가 없어 자식 session 을 등록하지 않았다")
        return
    body, token = built
    outcome = None
    for attempt in (1, 2):
        try:
            status = _post(url, body, token)
        except (urllib.error.URLError, OSError) as exc:
            outcome = type(exc).__name__
            continue
        if 200 <= status < 300:
            logger.info("fos-ctx: 자식 session 을 등록했다 (시도 %d)", attempt)
            return
        outcome = "HTTP %d" % status
        if status < 500:
            break
    logger.warning("fos-ctx: 자식 session 을 등록하지 못했다: %s", outcome)


def register(ctx):
    ctx.register_hook("pre_tool_call", pre_tool_call)
    ctx.register_hook("subagent_start", subagent_start)
    logger.info("fos-ctx: Control Plane MCP 호출에 _fos_ctx 를 서명해 붙이고 skill_manage 를 막고 자식 session 을 등록하고 "
                "연결용 profile 의 커넥터 도구 호출을 Control Plane 에 묻는다")
