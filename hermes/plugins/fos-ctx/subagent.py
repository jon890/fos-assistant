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
    signing_key,
)



# 자식 session 을 등록할 Control Plane 주소다. 경로는 fos-assistant 가 정한다.
REGISTER_URL_ENV = "FOS_CTX_SUBAGENT_URL"
# 부모 스레드가 자식을 만드는 동안 기다리는 시간이다. 두 번 불러도 자식 시작이 몇 초만 늦는다.
REGISTER_TIMEOUT = 3.0


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
