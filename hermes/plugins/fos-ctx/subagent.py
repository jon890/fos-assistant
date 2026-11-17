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
    sign_subagent,
    sign_subagent_stop,
    signing_key,
)



# 자식 session 을 등록할 Control Plane 주소다. 경로는 fos-assistant 가 정한다.
REGISTER_URL_ENV = "FOS_CTX_SUBAGENT_URL"
# 끝 소식 경로는 등록 주소 끝에 이 조각을 붙인 것이다. 운영 값을 따로 두지 않는다.
STOP_PATH = "/stop"
# 부모 스레드가 자식을 만드는 동안 기다리는 시간이다. 두 번 불러도 자식 시작이 몇 초만 늦는다.
# 끝 소식도 같은 값을 쓴다. 그동안 부모 session 의 결과 줄이 늦게 남는다.
REGISTER_TIMEOUT = 3.0


def _text_or_none(value):
    """글이면 그대로, 아니면 None 이다. JSON 으로 보낼 수 없는 값은 보내지 않는다."""
    return value if isinstance(value, str) else None


def _parent_root(parent_session_id: str) -> str:
    """부모의 루트 session 이다. state.db 를 읽지 못하면 부모 session 을 루트로 쓴다."""
    try:
        return root_session(parent_session_id)
    except sqlite3.Error as exc:
        logger.warning("fos-ctx: state.db 에서 부모의 루트 session 을 찾지 못했다: %s", type(exc).__name__)
        return parent_session_id


def build_registration(parent_session_id: str, child_session_id: str, child_subagent_id,
                       parent_subagent_id, child_goal=None):
    """자식 session 등록 본문과 토큰을 돌려준다. 재료가 없으면 None 이다."""
    if not parent_session_id or not child_session_id:
        return None
    token = _read_token()
    if not token:
        return None
    root = _parent_root(parent_session_id)
    goal = _text_or_none(child_goal)
    body = {
        "v": CTX_VERSION,
        "parent_session_id": parent_session_id,
        "parent_root_session_id": root,
        "child_session_id": child_session_id,
        "child_subagent_id": child_subagent_id,
        "parent_subagent_id": parent_subagent_id,
        # 이 플러그인은 등록하는 자식마다 subagent_stop 으로 끝 소식을 보낸다.
        "reports_stop": True,
        "child_goal": goal if goal and goal.strip() else None,
        "sig": sign_subagent(signing_key(token), root, parent_session_id, child_session_id),
    }
    return body, token


def build_stop(parent_session_id: str, child_session_id: str, child_status=None, child_summary=None):
    """하위 에이전트 끝 소식 본문과 토큰을 돌려준다. 재료가 없으면 None 이다."""
    if not parent_session_id or not child_session_id:
        return None
    token = _read_token()
    if not token:
        return None
    root = _parent_root(parent_session_id)
    body = {
        "v": CTX_VERSION,
        "parent_session_id": parent_session_id,
        "parent_root_session_id": root,
        "child_session_id": child_session_id,
        "child_status": _text_or_none(child_status),
        # 결과 글은 자르지 않는다. Control Plane 이 위임 답과 같은 상한으로 자른다.
        "child_summary": _text_or_none(child_summary),
        "sig": sign_subagent_stop(signing_key(token), root, parent_session_id, child_session_id),
    }
    return body, token


def stop_url() -> str:
    """끝 소식을 보낼 주소다. 등록 주소가 없으면 빈 글이다."""
    base = os.environ.get(REGISTER_URL_ENV, "").strip().rstrip("/")
    return base + STOP_PATH if base else ""


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


def _deliver(url: str, body: dict, token: str):
    """두 번까지 보낸다. 연결 실패와 5xx 에만 다시 보낸다. 성공한 시도 번호, 실패면 (None, 원인) 이다."""
    outcome = None
    for attempt in (1, 2):
        try:
            status = _post(url, body, token)
        except (urllib.error.URLError, OSError) as exc:
            outcome = type(exc).__name__
            continue
        if 200 <= status < 300:
            return attempt, None
        outcome = "HTTP %d" % status
        if status < 500:
            break
    return None, outcome


def subagent_start(parent_session_id="", child_session_id="", child_subagent_id=None,
                   parent_subagent_id=None, child_goal=None, **_):
    url = os.environ.get(REGISTER_URL_ENV, "").strip()
    if not url:
        logger.warning("fos-ctx: %s 가 없어 자식 session 을 등록하지 않았다", REGISTER_URL_ENV)
        return
    try:
        built = build_registration(parent_session_id or "", child_session_id or "",
                                   child_subagent_id, parent_subagent_id, child_goal)
    except Exception as exc:  # noqa: BLE001 - hook 은 자식 실행을 막지 않는다
        logger.warning("fos-ctx: 자식 session 등록을 만들지 못했다: %s", type(exc).__name__)
        return
    if built is None:
        logger.warning("fos-ctx: MCP 토큰이나 session 정보가 없어 자식 session 을 등록하지 않았다")
        return
    body, token = built
    attempt, outcome = _deliver(url, body, token)
    if attempt is not None:
        logger.info("fos-ctx: 자식 session 을 등록했다 (시도 %d)", attempt)
        return
    logger.warning("fos-ctx: 자식 session 을 등록하지 못했다: %s", outcome)


def subagent_stop(parent_session_id="", child_session_id="", child_status=None, child_summary=None, **_):
    """자식이 끝난 소식을 Control Plane 에 보낸다. 무엇이 잘못돼도 로그만 남기고 돌아간다.

    Hermes 는 부모의 마무리 잠금 안에서 자식마다 이 hook 을 잇달아 부르고, 반환값은 버린다.
    보내지 못한 자식은 Control Plane 의 기한 알림이 맡고 결과는 Hermes 이력에 남는다.
    """
    try:
        url = stop_url()
        if not url:
            logger.warning("fos-ctx: %s 가 없어 하위 에이전트 끝 소식을 보내지 않았다", REGISTER_URL_ENV)
            return
        built = build_stop(parent_session_id or "", child_session_id or "", child_status, child_summary)
        if built is None:
            logger.warning("fos-ctx: MCP 토큰이나 session 정보가 없어 하위 에이전트 끝 소식을 보내지 않았다")
            return
        body, token = built
        attempt, outcome = _deliver(url, body, token)
    except Exception as exc:  # noqa: BLE001 - hook 은 부모의 결과 마무리를 막지 않는다
        logger.warning("fos-ctx: 하위 에이전트 끝 소식을 보내지 못했다: %s", type(exc).__name__)
        return
    if attempt is not None:
        logger.info("fos-ctx: 하위 에이전트 끝 소식을 보냈다 (시도 %d)", attempt)
        return
    logger.warning("fos-ctx: 하위 에이전트 끝 소식을 보내지 못했다: %s", outcome)
