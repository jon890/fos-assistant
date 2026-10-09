from __future__ import annotations

import hashlib
import hmac
import logging
import sqlite3


logger = logging.getLogger(__package__)
# `hermes mcp add` 가 서버 이름에서 만드는 .env 키다. 운영 저장소의 등록 스크립트가 여기에 토큰을 쓴다.
KEY_NAME = "MCP_FOS_ASSISTANT_API_KEY"
CTX_VERSION = 1
# parent_session_id 사슬을 따라가는 한도다. 하위 에이전트와 압축 교체가 겹쳐도 이만큼 깊지 않다.
MAX_DEPTH = 16
REGISTER_VERSION = "v1-subagent"
POLICY_VERSION = "v1-connector-policy"


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


def top_level_session(session_id: str) -> bool:
    """압축 사슬과 subagent 사슬을 나눈다. 읽지 못한 사슬은 최상위로 증명하지 않는다."""
    con = sqlite3.connect(f"file:{_state_db_path()}?mode=ro", uri=True, timeout=1.0)
    try:
        seen = set()
        current = session_id
        for _ in range(MAX_DEPTH):
            if current in seen:
                return False
            seen.add(current)
            row = con.execute("SELECT parent_session_id, source FROM sessions WHERE id = ?", (current,)).fetchone()
            if not row or row[1] == "subagent":
                return False
            if not row[0]:
                return True
            current = row[0]
        return False
    finally:
        con.close()


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
