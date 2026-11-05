from __future__ import annotations

import os
import sqlite3
import urllib.parse

from .connector_policy import POLICY_TIMEOUT, POLICY_URL_ENV, _block, _post_json
from .context import (
    CTX_VERSION,
    _read_token,
    logger,
    root_session,
    sign_tool_guard,
    signing_key,
)


# 사건 실행에서도 묻지 않는 도구다. 읽기 경계의 toolset(`web`, `vision`, `todo`, `skills`)의 읽기 도구와
# 부작용 없는 도구 찾기다. 이 밖의 내장 도구, 붙지 않은 MCP 서버의 도구, 다른 plugin 의 도구는 모두 묻는다.
# Hermes 를 올릴 때 `hermes/tests/test_tool_guard_contract.py` 가 이 이름들의 toolset 을 다시 본다.
SAFE_TOOLS = frozenset({
    "web_search", "web_extract", "vision_analyze", "skills_list", "skill_view", "todo_list",
    "tool_search", "tool_describe",
})
# 인자에 따라 안전하지 않은 안전 목록 도구다. `vision_analyze` 는 로컬 경로도 받아 Hermes 쪽 사진 파일을 읽는다.
# 원격 주소와 직접 실은 이미지만 묻지 않는다.
VISION_TOOL = "vision_analyze"
# 판정 경로의 마지막 조각이다. `FOS_CTX_POLICY_URL` 의 마지막 조각을 이것으로 바꿔 같은 Control Plane 에 묻는다.
GUARD_PATH = "tool-guard"
GUARD_BLOCK_MESSAGE = ("fos-ctx: 이 도구를 지금 쓸 수 있는지 확인하지 못해 막았다. "
                       "잠시 뒤 다시 시도하라고 사용자에게 알린다.")


def guard_url() -> str:
    """판정을 물을 주소다. 플러그인 정책 주소가 없거나 http(s) 가 아니면 빈 글이다."""
    policy = os.environ.get(POLICY_URL_ENV, "").strip().rstrip("/")
    if not policy:
        return ""
    parsed = urllib.parse.urlsplit(policy)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        return ""
    return urllib.parse.urljoin(policy, GUARD_PATH)


def is_safe(tool_name: str, args, remote_only) -> bool:
    """묻지 않아도 되는 호출인가. `remote_only` 는 이미지 출처가 원격 주소나 data URL 뿐인지 보는 함수다."""
    if tool_name not in SAFE_TOOLS:
        return False
    if tool_name == VISION_TOOL:
        return isinstance(args, dict) and remote_only([args.get("image_url")])
    return True


def tool_guard(tool_name: str, args, session_id: str, tool_call_id: str, remote_only):
    """안전 목록 밖의 도구 호출을 Control Plane 에 묻는다. 통과면 None, 아니면 글이 든 `block` 이다.

    session 이 없는 호출은 코드 실행(`execute_code`, 상주 kernel) 안의 호출이다. Hermes 가 그 호출에는 `task_id` 만
    넘긴다. 그 코드를 연 `execute_code` 호출이 session 을 들고 판정을 받았고 사건 실행 트리에서는 막히므로,
    여기서는 묻지 않고 통과시킨다. 예외는 부르는 쪽(`hooks.event_guard`)이 잡는다.
    """
    if is_safe(tool_name, args, remote_only) or not session_id:
        return None
    url = guard_url()
    token = _read_token()
    if not url or not token or not tool_call_id:
        return _block(GUARD_BLOCK_MESSAGE)
    try:
        root = root_session(session_id)
    except sqlite3.Error as exc:
        # 최상위 run 이면 맞는 값이다. 아니면 Control Plane 이 풀지 못한 session 으로 보고, 그 profile 에 사건
        # 살펴보기가 돌고 있으면 막는다.
        logger.warning("fos-ctx: state.db 에서 루트 session 을 찾지 못했다: %s", type(exc).__name__)
        root = session_id
    body = {
        "v": CTX_VERSION,
        "root_session_id": root,
        "session_id": session_id,
        "tool_call_id": tool_call_id,
        "hermes_tool": tool_name,
        "sig": sign_tool_guard(signing_key(token), tool_name, root, session_id, tool_call_id),
    }
    status, answer = _post_json(url, body, token, POLICY_TIMEOUT)
    if status == 404:
        # 이 경로가 없는 옛 Control Plane 이다. 옛 Control Plane 은 사건 실행을 읽기 경계의 toolset 으로만 열어
        # 판정 전과 같게 통과시킨다(rule-66). 다음 배포에서 이 예외를 뺀다.
        return None
    if status != 200 or not isinstance(answer, dict):
        logger.warning("fos-ctx: 도구 판정 질의가 쓸 수 있는 답을 주지 않았다: HTTP %d", status)
        return _block(GUARD_BLOCK_MESSAGE)
    decision = answer.get("decision")
    if decision == "allow":
        return None
    message = answer.get("message")
    if decision == "block" and isinstance(message, str) and message.strip():
        return _block(message)
    return _block(GUARD_BLOCK_MESSAGE)
