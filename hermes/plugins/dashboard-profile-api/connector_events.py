"""사건 감지의 목록 조회(`POST /api/connectors/<id>/events`)를 처리한다(ADR-20261105 / event-watch).

Control Plane 이 모델 없이 새 항목이 있는지만 본다. 그 profile 에 설치한 연결의 값으로 `event_watch` 가 선언한 조회 도구를
쪽마다 부르고, 항목마다 식별자, 받은 시각, 라벨만 돌려준다. 제목, 보낸 사람, 요약은 응답과 로그에 싣지 않는다.
"""

from __future__ import annotations

import asyncio
import json
import os
import re
from datetime import timezone
from email.utils import parsedate_to_datetime

from . import connector_run
from .common import CONNECTOR_HOST_MARKER, MANAGED_MARKER, _json_object, _missing_profile, _profile_rejection, _rejected, logger
from .connector_launch import _binding_env
from .connector_manifest import _connector_manifest, _entry_mode
from .connector_mcp import (_UNREADABLE, _leaf_error_types, _mcp_sdk_problem, _mcp_sdk_version, _open_connector_session,
                            _tool_error, _tool_payload)
from .connector_schema import BIND_MODE, CONNECTOR_ID_RE, CONNECTOR_STATE
from .connector_state import _connector_state

# 한 번의 조회가 부르는 쪽 수의 상한이다. 넘으면 최근 쪽까지만 돌려주고 `truncated` 를 참으로 둔다.
EVENT_PAGES = 4

# 한 번의 조회 전체의 시간 제한이다. 도구 호출 한 번의 제한은 `call` 과 같다.
EVENT_TIMEOUT_SECONDS = 30

# 돌려줄 식별자의 모양이다. 모양이 다른 항목은 버린다.
EVENT_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
EVENT_LABELS_MAX = 50


async def _connector_events_request(request, connector_id: str):
    """그 profile 의 값으로 조회 도구를 불러 `{"ok": true, "items", "truncated"}` 나 `{"ok": false, "error"}` 로 답한다.

    본문은 `profile` 과 `since`(0 이상의 정수 초)만 갖는다. 확인은 `execute` 와 같다. 관리 표식이 없는 profile 은 바인딩 설치만 받는다.
    동시 호출 한도는 `call`, `execute` 와 함께 쓴다.
    """
    from starlette.responses import JSONResponse

    def failed(word):
        return JSONResponse({"ok": False, "error": word}, status_code=200)

    manifest = _connector_manifest(connector_id) if CONNECTOR_ID_RE.match(connector_id) else None
    if manifest is None or manifest.get("event_watch") is None:
        return _rejected("사건 감지를 선언한 connector 가 아니다", 404)
    body = await _json_object(request)
    if (body is None or set(body) != {"profile", "since"} or type(body["since"]) is not int
            or not 0 <= body["since"] < 2 ** 40):
        return _rejected("profile 과 0 이상의 정수 since 만 필요하다")
    rejected = _profile_rejection(body["profile"], request)
    if rejected is not None:
        return rejected
    try:
        missing = _missing_profile(body["profile"])
        if missing is not None:
            return missing
        from hermes_cli.profiles import get_profile_dir
        profile_dir = get_profile_dir(body["profile"])
        managed = (profile_dir / MANAGED_MARKER).is_file()
        if not managed and not (profile_dir / CONNECTOR_HOST_MARKER).is_file():
            return _rejected("관리 표식이 없는 profile 이다", 401)
        state_path = profile_dir / CONNECTOR_STATE
        state = (_connector_state(json.loads(state_path.read_text(encoding="utf-8")), connector_id)
                 if state_path.is_file() else {})
        if connector_id not in state:
            return _rejected("설치하지 않은 connector 다", 404)
        if not managed and _entry_mode(state[connector_id]) != BIND_MODE:
            return _rejected("관리 표식이 없는 profile 이다", 401)
        env = dict(_binding_env(profile_dir, manifest, state[connector_id]))
    except Exception as error:
        # profile 의 값이 섞일 수 있어 예외의 종류만 남긴다.
        logger.warning("dashboard-profile-api: 플러그인 %s 의 사건 조회 profile 을 확인하지 못했다: %s",
                       connector_id, type(error).__name__)
        return failed("unavailable")
    server = manifest["server"]
    env.update({name: server["env"][name] for name in manifest["operator_env"]})
    # 조회는 첨부를 읽지 않고 파일을 내지 않으며 사용자 브라우저에 닿지 않는다.
    for key in ("owner_attachments_env", "owner_output_env", "owner_browser_env"):
        if manifest[key] is not None:
            env[manifest[key]] = ""
    if manifest["transport"] == "http":
        if any(ch in value for value in env.values() for ch in "\r\n\0"):
            return failed("unavailable")
    else:
        env["PATH"] = os.path.dirname(server["command"])
    problem = _mcp_sdk_problem()
    if problem is not None:
        logger.warning("dashboard-profile-api: mcp SDK %s 로는 플러그인 도구를 부르지 않는다: %s", _mcp_sdk_version(), problem)
        return failed("unavailable")
    if connector_run._connector_calls >= connector_run.CONNECTOR_CALL_LIMIT:
        return failed("unavailable")
    connector_run._connector_calls += 1
    try:
        answer = await asyncio.wait_for(_list_events(manifest, env, body["since"]), EVENT_TIMEOUT_SECONDS)
    except asyncio.TimeoutError:
        logger.warning("dashboard-profile-api: 플러그인 %s 의 사건 조회가 시간 제한을 넘겼다", connector_id)
        return failed("unavailable")
    except Exception as error:
        logger.warning("dashboard-profile-api: 플러그인 %s 의 사건 조회가 실패했다: %s (mcp SDK %s)",
                       connector_id, ", ".join(sorted(set(_leaf_error_types(error)))), _mcp_sdk_version())
        return failed("unavailable")
    finally:
        connector_run._connector_calls -= 1
    return JSONResponse(answer, status_code=200)


async def _list_events(manifest: dict, env: dict, since: int) -> dict:
    """한 번 붙어 조회 도구를 쪽마다 부르고 항목을 고른다. 도구가 `tools/list` 에서 읽기 전용이 아니면 부르지 않는다."""
    watch = manifest["event_watch"]
    args = {key: value.replace("{since}", str(since)) if isinstance(value, str) else value
            for key, value in watch["args"].items()}
    items, seen, truncated = [], set(), False
    async with _open_connector_session(manifest, env, connector_run.CONNECTOR_CALL_TIMEOUT_SECONDS) as session:
        listed = await session.list_tools()
        annotations = next((item.annotations for item in listed.tools if item.name == watch["tool"]), None)
        if annotations is None or annotations.read_only_hint is not True:
            return {"ok": False, "error": "unavailable"}
        for page in range(EVENT_PAGES):
            result = await session.call_tool(watch["tool"], args)
            payload = _tool_payload(result)
            if payload is _UNREADABLE or not isinstance(payload, dict):
                return {"ok": False, "error": "unavailable"}
            if result.is_error:
                _, code = _tool_error(payload)
                return {"ok": False, "error": manifest["errors"].get(code, "unavailable") if code else "unavailable"}
            for raw in payload.get(watch["items"]) or []:
                item = _event_item(raw, watch)
                if item is not None and item["id"] not in seen:
                    seen.add(item["id"])
                    items.append(item)
            token = payload.get(watch["page_token"]["result"]) if watch["page_token"] else None
            if not isinstance(token, str) or not token:
                break
            if page == EVENT_PAGES - 1:
                truncated = True
                break
            args = {**args, watch["page_token"]["arg"]: token}
    return {"ok": True, "items": items, "truncated": truncated}


def _event_item(raw, watch: dict):
    """항목 하나에서 식별자, 받은 시각, 라벨만 꺼낸다. 식별자가 틀리거나 뺄 라벨이 붙었으면 None 이다."""
    if not isinstance(raw, dict) or not isinstance(raw.get(watch["id"]), str) or not EVENT_ID_RE.match(raw[watch["id"]]):
        return None
    labels = raw.get(watch["labels"])
    labels = [label for label in labels if isinstance(label, str) and len(label) <= 64][:EVENT_LABELS_MAX] \
        if isinstance(labels, list) else []
    if watch["skip_labels"] & set(labels):
        return None
    return {"id": raw[watch["id"]], "received_at": _received_at(raw.get(watch["received_at"])), "labels": labels}


def _received_at(value):
    """받은 시각을 UTC ISO-8601 로 맞춘다. 정수는 밀리초 epoch, 글은 RFC 2822 나 ISO-8601 로 읽는다. 읽지 못하면 None 이다."""
    from datetime import datetime
    try:
        if type(value) is int:
            parsed = datetime.fromtimestamp(value / 1000, tz=timezone.utc)
        elif isinstance(value, str) and value:
            try:
                parsed = datetime.fromisoformat(value)
            except ValueError:
                parsed = parsedate_to_datetime(value)
        else:
            return None
    except (TypeError, ValueError, OverflowError, OSError):
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
