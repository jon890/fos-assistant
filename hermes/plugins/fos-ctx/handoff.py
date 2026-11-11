"""플러그인 도구 결과의 화면 넘기기 칸을 Control Plane 에 알린다.

계약의 정본은 fos-assistant `docs/adr/ADR-20261111-connector-handoff.md` 의 「결정」 과
`hermes/plugins/fos-ctx/README.md` 의 「화면 넘기기를 알린다」 다. 서비스 이름을 모른다. 결과 글이 정해 둔 모양일 때만 보낸다.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import re
import sqlite3
import urllib.parse

from .connector_policy import POLICY_TIMEOUT, _post_json
from .context import CTX_VERSION, _read_token, logger, root_session, signing_key
from .tool_guard import guard_url

HANDOFF_VERSION = "v1-connector-handoff"
HANDOFF_PATH = "connector-handoff"
# 이 PR 이 받는 어휘다. Control Plane 의 `HandoffNeed` 와 같아야 한다. 모르는 값은 보내지 않는다.
NEEDS = frozenset({"login", "captcha", "device_check"})
CODE_RE = re.compile(r"^[A-Z0-9_]{1,64}$")
# 화면 입력 `tab` 의 `id` 와 같은 모양이다.
TAB_RE = re.compile(r"^[A-Za-z0-9]{1,128}$")


def sign_handoff(key: str, hermes_tool: str, root_session_id: str, session_id: str, tool_call_id: str,
                 code: str, need: str, tab: str) -> str:
    """화면 넘기기 알림의 서명을 소문자 16진수로 돌려준다. key 는 `_fos_ctx` 와 같다."""
    message = "\n".join([HANDOFF_VERSION, hermes_tool, root_session_id, session_id, tool_call_id, code, need, tab])
    return hmac.new(key.encode("utf-8"), message.encode("utf-8"), hashlib.sha256).hexdigest()


def handoff_of(result: str):
    """결과 글에서 `(code, need, tab)` 을 꺼낸다. 정해 둔 모양이 아니면 None 이다.

    Hermes 는 MCP 의 `isError` 결과를 `{"error": "<플러그인 글>"}` 로 바꾸므로 바깥 `error` 가 글이면 한 번 더 읽는다.
    """
    try:
        parsed = json.loads(result)
        error = parsed.get("error") if isinstance(parsed, dict) else None
        if isinstance(error, str):
            inner = json.loads(error)
            error = inner.get("error") if isinstance(inner, dict) else None
    except ValueError:
        return None
    if not isinstance(error, dict) or not isinstance(error.get("handoff"), dict):
        return None
    code, need, tab = error.get("code"), error["handoff"].get("need"), error["handoff"].get("tab")
    if not (isinstance(code, str) and CODE_RE.fullmatch(code) and need in NEEDS
            and isinstance(tab, str) and TAB_RE.fullmatch(tab)):
        return None
    return code, need, tab


def handoff_url() -> str:
    """알릴 주소다. 플러그인 정책 주소의 마지막 조각을 바꾼 것이고, 없거나 http(s) 가 아니면 빈 글이다."""
    guard = guard_url()
    return urllib.parse.urljoin(guard, HANDOFF_PATH) if guard else ""


def report_handoff(tool_name: str, result, session_id, tool_call_id) -> bool:
    """결과에 넘기기 칸이 있으면 한 번 알린다. 알렸으면 참이다. 실패는 로그만 남기고 결과를 바꾸지 않는다.

    카드가 뜨지 않아도 모델은 오류를 받아 사람에게 「내 브라우저」 에서 풀어 달라고 안내한다.
    """
    try:
        found = handoff_of(result) if isinstance(result, str) else None
        if found is None or not session_id or not tool_call_id:
            return False
        url, token = handoff_url(), _read_token()
        if not url or not token:
            logger.warning("fos-ctx: 화면 넘기기를 알릴 주소나 토큰이 없다")
            return False
        try:
            root = root_session(session_id)
        except sqlite3.Error as exc:
            logger.warning("fos-ctx: state.db 에서 루트 session 을 찾지 못했다: %s", type(exc).__name__)
            root = session_id
        code, need, tab = found
        body = {
            "v": CTX_VERSION, "root_session_id": root, "session_id": session_id, "tool_call_id": tool_call_id,
            "hermes_tool": tool_name, "code": code, "need": need, "tab": tab,
            "sig": sign_handoff(signing_key(token), tool_name, root, session_id, tool_call_id, code, need, tab),
        }
        status, _ = _post_json(url, body, token, POLICY_TIMEOUT)
        if status != 200:
            logger.warning("fos-ctx: 화면 넘기기 알림이 받아지지 않았다: HTTP %d", status)
            return False
        return True
    except Exception as exc:  # noqa: BLE001 - 알리지 못해도 결과는 그대로 간다
        # 예외 본문에 비밀값이 섞일 수 있어 종류만 남긴다.
        logger.warning("fos-ctx: 화면 넘기기를 알리지 못했다: %s", type(exc).__name__)
        return False
