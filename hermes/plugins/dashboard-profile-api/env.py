from __future__ import annotations


from .common import (
    BASE_ENV_KEYS,
    MANAGED_MARKER,
    _json_object,
    _missing_profile,
    _profile_rejection,
    _rejected,
    logger,
)

from .connector_manifest import (
    _connector_env_keys,
)




def _operator_env_ignored(body: dict):
    """운영자 env 이름의 쓰기와 지우기에 주는 답이다. 성공으로 답하고 아무것도 쓰지 않는다.

    그 값은 운영 목록이 갖고 설치할 때 서버 정의에 직접 들어간다.
    옛 Control Plane 이 한 배포 동안 이 이름을 쓰려 하므로 거절하지 않는다(ADR-041).
    """
    from starlette.responses import JSONResponse

    return JSONResponse({"profile": body["profile"], "key": body["key"], "restart_required": False},
                        status_code=200)


async def _check_env_update(request):
    """`.env` 쓰기를 허용한 key 와 Control Plane 이 쓰는 profile 로 제한한다."""
    body = await _json_object(request)
    if body is None or set(body) != {"profile", "key", "value"}:
        return _rejected("profile, key, value 만 필요하다")
    rejected = _profile_rejection(body["profile"], request)
    if rejected is not None:
        return rejected
    field_keys, operator_keys = _connector_env_keys()
    if not isinstance(body["key"], str) or body["key"] not in BASE_ENV_KEYS | field_keys | operator_keys:
        return _rejected("쓸 수 없는 key 다")
    value = body["value"]
    if not isinstance(value, str) or any(ch in value for ch in "\r\n\0"):
        return _rejected("value 는 한 줄 문자열이다")
    try:
        missing = _missing_profile(body["profile"])
        if missing is not None:
            return missing
        if body["key"] not in BASE_ENV_KEYS:
            from hermes_cli.profiles import get_profile_dir
            if not (get_profile_dir(body["profile"]) / MANAGED_MARKER).is_file():
                return _rejected("관리 표식이 없는 profile 이다", 401)
            if body["key"] in operator_keys:
                return _operator_env_ignored(body)
        return None
    except Exception:
        logger.exception("dashboard-profile-api: profile 을 확인하지 못했다")
        return _rejected("profile 을 확인하지 못했다", 500)


async def _check_env_delete(request):
    """연결 해제는 커넥터 칸의 key 만 지운다. 모델과 Control Plane credential 은 보존한다."""
    body = await _json_object(request)
    if body is None or set(body) != {"profile", "key"}:
        return _rejected("profile 과 key 만 필요하다")
    rejected = _profile_rejection(body["profile"], request)
    if rejected is not None:
        return rejected
    field_keys, operator_keys = _connector_env_keys()
    if not isinstance(body["key"], str) or body["key"] not in field_keys | operator_keys:
        return _rejected("커넥터 환경 변수만 지울 수 있다")
    missing = _missing_profile(body["profile"])
    if missing is not None:
        return missing
    from hermes_cli.profiles import get_profile_dir
    if not (get_profile_dir(body["profile"]) / MANAGED_MARKER).is_file():
        return _rejected("관리 표식이 없는 profile 이다", 401)
    if body["key"] in operator_keys:
        return _operator_env_ignored(body)
    return None
