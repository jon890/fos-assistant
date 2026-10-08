"""커넥터 call 과 execute 요청을 처리하고 동시 호출 한도를 함께 지킨다."""

from __future__ import annotations

import asyncio
import json
import os
import pathlib
import re

# 옛 기능 모듈의 import 계약을 유지하려고 이동한 이름도 다시 내보낸다.

from .common import (
    CONNECTOR_HOST_MARKER,
    MANAGED_MARKER,
    _env_value,
    _json_object,
    _missing_profile,
    _profile_rejection,
    _rejected,
    logger,
)

from .connector_manifest import (
    _connector_manifest,
    _entry_mode,
)

from .connector_mcp import (
    ERROR_DETAIL_INT_MAX,
    MCP_SDK_MAJOR,
    _UNREADABLE,
    _connector_call_answer,
    _connector_error_answer,
    _connector_execute_answer,
    _leaf_error_types,
    _mcp_sdk_problem,
    _mcp_sdk_version,
    _run_connector_execute,
    _run_connector_tool,
    _safe_error_detail,
    _tool_error,
    _tool_payload,
)

from .connector_schema import (
    BIND_MODE,
    CONNECTOR_ID_RE,
    CONNECTOR_STATE,
    OUTCOME_UNKNOWN,
    OWNER_ATTACHMENTS_VALUE_RE,
)

from .connector_state import (
    _connector_state,
)

from .connector_vault import (
    VAULT_ID_RE,
    _read_vault,
)

from .sandbox import (
    _sandbox_policy,
)


# 커넥터 도구 호출 하나의 시간 제한과 대시보드 프로세스 전체의 동시 실행 수다.
CONNECTOR_CALL_TIMEOUT_SECONDS = 10


CONNECTOR_CALL_LIMIT = 4


# 승인한 호출을 실행하는 경로의 시간 제한이다. 쓰기 도구는 확인 도구보다 오래 걸릴 수 있다.
CONNECTOR_EXECUTE_TIMEOUT_SECONDS = 60


# 지금 돌고 있는 호출 수다. 이벤트 루프 하나에서만 바꾸므로 잠금이 필요 없다.
_connector_calls = 0


async def _connector_call_request(request, connector_id: str):
    """후보 값이나 보관 파일의 값으로 선택지 도구나 확인 도구를 한 번 부른다. 값을 디스크와 응답과 로그에 남기지 않는다.

    본문은 `values` 와 `vault` 가운데 정확히 하나를 갖는다. `vault` 는 그 커넥터의 보관 파일이어야 한다.
    """
    from starlette.responses import JSONResponse
    global _connector_calls

    def failed(word):
        return JSONResponse({"ok": False, "error": word}, status_code=200)

    manifest = _connector_manifest(connector_id) if CONNECTOR_ID_RE.match(connector_id) else None
    if manifest is None:
        return _rejected("없는 connector 다", 404)
    body = await _json_object(request)
    if (body is None or set(body) not in ({"tool", "values"}, {"tool", "vault"})
            or ("values" in body and not isinstance(body["values"], dict))
            or ("vault" in body and (not isinstance(body["vault"], str) or not VAULT_ID_RE.match(body["vault"])))):
        return _rejected("tool 과, values 나 vault 가운데 하나만 필요하다")
    tool = body["tool"]
    if not isinstance(tool, str) or tool not in manifest["call_tools"]:
        return _rejected("이 커넥터가 선택지나 확인에 쓰는 도구가 아니다")
    if "vault" in body:
        try:
            stored = await asyncio.to_thread(_read_vault, body["vault"])
        except Exception as error:
            logger.warning("dashboard-profile-api: 커넥터 %s 의 보관 파일을 읽지 못했다: %s",
                           connector_id, type(error).__name__)
            return failed("unavailable")
        if stored is None or stored["connector"] != connector_id:
            return _rejected("이 커넥터의 보관 파일이 없다")
        candidates = stored["values"]
    else:
        candidates = body["values"]
    fields = {field["key"]: field for field in manifest["fields"]}
    env = {}
    for key, value in candidates.items():
        field = fields.get(key)
        if field is None or not isinstance(value, str) or any(ch in value for ch in "\r\n\0"):
            return failed("invalid_input")
        # 비운 선택 칸은 형식을 보지 않는다. 필수 칸은 빈 값도 형식에 맞아야 한다.
        if "pattern" in field and (value or field.get("required", True)) and not re.fullmatch(field["pattern"], value):
            return failed("invalid_input")
        env[field["env"]] = value
    server = manifest["server"]
    env.update({name: server["env"][name] for name in manifest["operator_env"]})
    if manifest["owner_attachments_env"] is not None:
        # 이 경로에는 바인딩 주인이 없다. 빈 값을 주어 커넥터가 사용자 첨부를 읽지 않게 한다(ADR-20261007 connector-owner-attachments).
        env[manifest["owner_attachments_env"]] = ""
    if manifest["owner_output_env"] is not None:
        # 확인 도구와 선택지는 파일을 내지 않는다(ADR-20261008 connector-output-files).
        env[manifest["owner_output_env"]] = ""
    # 대시보드 프로세스의 PATH 를 물려주지 않는다. 실행 파일이 있는 디렉터리만 준다.
    env["PATH"] = os.path.dirname(server["command"])

    problem = _mcp_sdk_problem()
    if problem is not None:
        logger.warning("dashboard-profile-api: mcp SDK %s 로는 커넥터 도구를 부르지 않는다: %s",
                       _mcp_sdk_version(), problem)
        return failed("unavailable")

    # 줄을 세우지 않는다. 가득 차 있으면 기다리는 동안 요청이 쌓여 대시보드가 느려진다.
    if _connector_calls >= CONNECTOR_CALL_LIMIT:
        logger.warning("dashboard-profile-api: 커넥터 도구 호출이 %d개 돌고 있어 받지 않았다", _connector_calls)
        return failed("unavailable")
    _connector_calls += 1
    try:
        # 시간을 넘기면 취소가 SDK 의 정리 구간을 돌려 자식 프로세스를 끝낸 뒤에 돌아온다.
        result = await asyncio.wait_for(_run_connector_tool(manifest, tool, env), CONNECTOR_CALL_TIMEOUT_SECONDS)
        answer = None if result is None else _connector_call_answer(manifest, result)
        if answer is not None and answer.get("error") == OUTCOME_UNKNOWN:
            # 선택지와 확인 도구는 읽기 전용이다. 결과를 모르는 쓰기가 없으므로 `unavailable` 과 같다.
            answer = {"ok": False, "error": "unavailable"}
    except ImportError:
        logger.warning("dashboard-profile-api: mcp SDK 를 읽어 오지 못해 커넥터 도구를 부르지 못했다")
        return failed("unavailable")
    except asyncio.TimeoutError:
        logger.warning("dashboard-profile-api: 커넥터 %s 의 도구 %s 가 시간 제한을 넘겼다", connector_id, tool)
        return failed("unavailable")
    except Exception as error:
        # 예외 본문에는 자식의 출력이 섞일 수 있다. 가장 안쪽 예외의 종류만 남긴다.
        logger.warning("dashboard-profile-api: 커넥터 %s 의 도구 %s 를 부르지 못했다: %s (mcp SDK %s)",
                       connector_id, tool, ", ".join(sorted(set(_leaf_error_types(error)))), _mcp_sdk_version())
        return failed("unavailable")
    finally:
        _connector_calls -= 1
    if answer is None:
        return _rejected("읽기 전용 도구가 아니다")
    return JSONResponse(answer, status_code=200)


def _installed_owner_attachments(profile_dir: pathlib.Path, manifest: dict) -> str:
    """그 profile 에 설치한 서버 정의(`config.yaml` 의 `mcp_servers`)가 가진 주인의 첨부 디렉터리다.

    바인딩 설치가 운영 정책의 `attachment_agent_root` 와 주인으로 넣은 값만 돌려준다.
    값이 없거나, 지금 정책의 루트 아래 `users/<64자리 16진수>` 모양이 아니거나, 정책이 없으면 빈 값이다.
    빈 값을 받은 커넥터는 사용자 첨부를 읽지 않는다(ADR-20261007 connector-owner-attachments).
    """
    import yaml
    config_path = profile_dir / "config.yaml"
    if config_path.is_symlink() or not config_path.is_file():
        return ""
    config = yaml.safe_load(config_path.read_text(encoding="utf-8")) or {}
    servers = config.get("mcp_servers") if isinstance(config, dict) else None
    server = servers.get(manifest["mcp_server"]) if isinstance(servers, dict) else None
    env = server.get("env") if isinstance(server, dict) else None
    value = env.get(manifest["owner_attachments_env"]) if isinstance(env, dict) else None
    if not isinstance(value, str) or not OWNER_ATTACHMENTS_VALUE_RE.match(value):
        return ""
    policy = _sandbox_policy()
    if policy is None:
        return ""
    prefix = "%s/users/" % policy["attachment_agent_root"].rstrip("/")
    key = value[len(prefix):]
    if not value.startswith(prefix) or not re.fullmatch(r"[0-9a-f]{64}", key):
        return ""
    return value


async def _connector_execute_request(request, connector_id: str):
    """Control Plane 이 승인한 호출을 그 profile 의 값과 받은 인자로 한 번 실행한다(ADR-050).

    승인 여부는 다시 확인하지 않는다. 서비스 토큰을 가진 Control Plane 이 승인한 줄로만 부른다.
    인자와 결과를 로그에 싣지 않는다.
    실행되지 않은 것이 분명한 실패는 `{"ok": false}` 로, 실행됐는지 모르는 실패는 504 로 답한다.
    도구가 `errors` 표에서 `outcome_unknown` 인 코드로 끝난 것도 실행됐는지 모르는 실패다.
    Control Plane 이 앞의 것은 실패로, 뒤의 것은 결과를 모르는 것으로 읽어 다시 돌리지 않는다.
    """
    from starlette.responses import JSONResponse
    global _connector_calls

    def failed(word):
        return JSONResponse({"ok": False, "error": word}, status_code=200)

    manifest = _connector_manifest(connector_id) if CONNECTOR_ID_RE.match(connector_id) else None
    if manifest is None:
        return _rejected("없는 connector 다", 404)
    body = await _json_object(request)
    if (body is None or set(body) != {"profile", "hermes_tool", "args"} or not isinstance(body["args"], dict)
            or not isinstance(body["hermes_tool"], str) or not 1 <= len(body["hermes_tool"]) <= 128):
        return _rejected("profile, hermes_tool, args 만 필요하다")
    rejected = _profile_rejection(body["profile"], request)
    if rejected is not None:
        return rejected
    hermes_tool = body["hermes_tool"]
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
        # 기록을 검증하면서 이 커넥터의 항목이 지금 manifest 의 실행 정의와 맞는지도 함께 본다.
        state = (_connector_state(json.loads(state_path.read_text(encoding="utf-8")), connector_id)
                 if state_path.is_file() else {})
        if connector_id not in state:
            return _rejected("설치하지 않은 connector 다", 404)
        # 커넥터 표식만 있는 profile 은 바인딩 설치만 받는다. 그 밖의 항목으로는 실행하지 않는다.
        if not managed and _entry_mode(state[connector_id]) != BIND_MODE:
            return _rejected("관리 표식이 없는 profile 이다", 401)
        env_path = profile_dir / ".env"
        env_text = env_path.read_text(encoding="utf-8") if env_path.is_file() else ""
        owner_attachments = (_installed_owner_attachments(profile_dir, manifest)
                             if manifest["owner_attachments_env"] is not None else None)
    except Exception as error:
        # 자식을 띄우기 전이다. 실행되지 않았다. profile 의 값이 섞일 수 있어 예외의 종류만 남긴다.
        logger.warning("dashboard-profile-api: 커넥터 %s 를 실행할 profile 을 확인하지 못했다: %s",
                       connector_id, type(error).__name__)
        return failed("unavailable")
    # 그 profile 의 값 가운데 이 커넥터의 칸만 넘긴다. 비운 선택 칸은 빈 문자열이다.
    env = {field["env"]: _env_value(env_text, field["env"]) for field in manifest["fields"]}
    server = manifest["server"]
    env.update({name: server["env"][name] for name in manifest["operator_env"]})
    if owner_attachments is not None:
        env[manifest["owner_attachments_env"]] = owner_attachments
    if manifest["owner_output_env"] is not None:
        # 승인한 쓰기는 파일을 내지 않는다. 파일 출력은 승인 없이 도는 읽기 도구만 쓴다(ADR-20261008 connector-output-files).
        env[manifest["owner_output_env"]] = ""
    # 대시보드 프로세스의 PATH 를 물려주지 않는다. 실행 파일이 있는 디렉터리만 준다.
    env["PATH"] = os.path.dirname(server["command"])

    problem = _mcp_sdk_problem()
    if problem is not None:
        logger.warning("dashboard-profile-api: mcp SDK %s 로는 커넥터 도구를 부르지 않는다: %s",
                       _mcp_sdk_version(), problem)
        return failed("unavailable")

    # `call` 과 한도를 함께 쓴다. 줄을 세우지 않는다.
    if _connector_calls >= CONNECTOR_CALL_LIMIT:
        logger.warning("dashboard-profile-api: 커넥터 도구 호출이 %d개 돌고 있어 받지 않았다", _connector_calls)
        return failed("unavailable")
    _connector_calls += 1
    progress = {"sent": False}
    try:
        # 시간을 넘기면 취소가 SDK 의 정리 구간을 돌려 자식 프로세스를 끝낸 뒤에 돌아온다.
        result = await asyncio.wait_for(
            _run_connector_execute(manifest, hermes_tool, body["args"], env, progress),
            CONNECTOR_EXECUTE_TIMEOUT_SECONDS)
        answer = None if result is None else _connector_execute_answer(manifest, result)
    except asyncio.TimeoutError:
        logger.warning("dashboard-profile-api: 커넥터 %s 의 도구 %s 가 시간 제한을 넘겼다", connector_id, hermes_tool)
        return _rejected("도구가 시간 제한을 넘겨 실행 결과를 모른다", 504)
    except Exception as error:
        # 예외 본문에는 자식의 출력이 섞일 수 있다. 가장 안쪽 예외의 종류만 남긴다.
        logger.warning("dashboard-profile-api: 커넥터 %s 의 도구 %s 를 실행하지 못했다: %s (mcp SDK %s)",
                       connector_id, hermes_tool, ", ".join(sorted(set(_leaf_error_types(error)))),
                       _mcp_sdk_version())
        if progress["sent"]:
            # 도구 호출을 보낸 뒤다. 실행됐는지 알 수 없다.
            return _rejected("도구 호출 뒤에 실패해 실행 결과를 모른다", 504)
        return failed("unavailable")
    finally:
        _connector_calls -= 1
    if answer is None:
        return _rejected("실행할 수 없는 도구다")
    if answer.get("error") == OUTCOME_UNKNOWN:
        # 도구가 쓰기를 보낸 뒤 답을 받지 못했다고 알렸다. 실패로 답하면 이미 나간 쓰기가 실패로 기록된다.
        logger.warning("dashboard-profile-api: 커넥터 %s 의 도구 %s 가 실행 결과를 모른다고 답했다",
                       connector_id, hermes_tool)
        return _rejected("도구가 실행 결과를 모른다고 답했다", 504)
    return JSONResponse(answer, status_code=200)
