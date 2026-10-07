from __future__ import annotations

import re
import sqlite3
from .common import (
    PROFILE_NAME_RE,
    _rejected,
    logger,
)


# 경로에서 온 session id 다. 저장소 조회의 인자로만 쓰고 파일 경로에는 쓰지 않는다.
SESSION_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
# profile 디렉터리 아래 Hermes 의 session 저장소 파일이다.
SESSION_DB_FILE = "state.db"
# 저장소가 잠겨 있을 때 기다리는 시간이다. 넘으면 503 으로 답한다.
SESSION_DB_TIMEOUT_SECONDS = 2


def _session_provider_response(name, session_id):
    """자식 session 한 줄에서 provider 와 모델만 돌려준다.

    Hermes 의 session 저장소를 읽기 전용으로 연다. Hermes 의 저장소 모듈은 스키마가 낡았으면
    쓰기 연결을 열 수 있어 쓰지 않고 표준 `sqlite3` 만 쓴다(ADR-067).
    주 호출이 쓴 모델과 provider 의 짝이 둘 이상이면 어느 것으로 환산할지 알 수 없어 provider 를 주지 않는다.
    짝이 하나여도 그 provider 가 session 줄의 값과 다르면 주지 않는다.
    """
    if (not isinstance(name, str) or not PROFILE_NAME_RE.fullmatch(name)
            or not isinstance(session_id, str) or not SESSION_ID_RE.fullmatch(session_id)):
        return _rejected("profile 이름이나 session id 가 올바르지 않다", 400)
    try:
        from hermes_cli.profiles import get_profile_dir, profile_exists
        from starlette.responses import JSONResponse

        if not profile_exists(name):
            return _rejected("없는 profile 이다", 404)
        database = get_profile_dir(name) / SESSION_DB_FILE
        if not database.is_file():
            return _rejected("없는 session 이다", 404)
        connection = sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True,
                                     timeout=SESSION_DB_TIMEOUT_SECONDS)
        try:
            row = connection.execute(
                "SELECT source, model, billing_provider FROM sessions WHERE id = ?", (session_id,)).fetchone()
            if row is None or row[0] != "subagent":
                return _rejected("없는 session 이다", 404)
            pairs = connection.execute(
                "SELECT DISTINCT model, billing_provider FROM session_model_usage"
                " WHERE session_id = ? AND task = ''", (session_id,)).fetchall()
        finally:
            connection.close()

        def public_text(value):
            return value if isinstance(value, str) and value else None

        provider = public_text(row[2])
        if len(pairs) > 1:
            provider = None
        elif pairs:
            # 짝이 하나여도 그 provider 가 session 줄과 다르면 어느 쪽이 맞는지 알 수 없다.
            used = public_text(pairs[0][1])
            if used is not None and used != provider:
                provider = None
        return JSONResponse({"provider": provider, "model": public_text(row[1])}, status_code=200)
    except Exception:
        logger.warning("dashboard-profile-api: session 저장소를 읽지 못했다")
        return _rejected("session 저장소를 읽지 못했다", 503)
