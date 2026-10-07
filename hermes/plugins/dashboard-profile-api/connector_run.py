from __future__ import annotations

import asyncio
import importlib.metadata
import json
import os
import pathlib
import re
from typing import Optional
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

from .connector_install import (
    _connector_state,
)

from .connector_manifest import (
    BIND_MODE,
    CONNECTOR_ID_RE,
    CONNECTOR_STATE,
    OUTCOME_UNKNOWN,
    OWNER_ATTACHMENTS_VALUE_RE,
    _connector_manifest,
    _entry_mode,
    _hermes_tool_name,
)

from .connector_vault import (
    VAULT_ID_RE,
    _read_vault,
)

from .sandbox import (
    _sandbox_policy,
)


ERROR_DETAIL_INT_MAX = 1_000_000_000
# 커넥터 도구 호출 하나의 시간 제한과 대시보드 프로세스 전체의 동시 실행 수다.
CONNECTOR_CALL_TIMEOUT_SECONDS = 10
CONNECTOR_CALL_LIMIT = 4
# 승인한 호출을 실행하는 경로의 시간 제한이다. 쓰기 도구는 확인 도구보다 오래 걸릴 수 있다.
CONNECTOR_EXECUTE_TIMEOUT_SECONDS = 60
# 커넥터 도구 호출이 기대는 mcp SDK 의 주 판이다. 다른 판은 결과 속성 이름이 달라 호출하지 않는다.
MCP_SDK_MAJOR = 2
# `_mcp_sdk_version` 이 한 번 읽은 판 문자열이다. 설치된 패키지는 프로세스가 도는 동안 바뀌지 않는다.
_mcp_sdk_version_cache: Optional[str] = None
# 지금 돌고 있는 호출 수다. 이벤트 루프 하나에서만 바꾸므로 잠금이 필요 없다.
_connector_calls = 0


async def _run_connector_tool(manifest: dict, tool: str, env: dict):
    """커넥터 MCP 서버를 자식 프로세스로 한 번 띄워 도구 하나를 부르고 닫는다.

    도구가 `tools/list` 에서 읽기 전용이 아니면 부르지 않고 None 을 돌려준다.
    `mcp` 는 여기서 import 한다. SDK 가 없는 환경에서도 plugin 이 올라오고 이 경로만 실패한다.
    """
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client

    server = manifest["server"]
    params = StdioServerParameters(command=server["command"], args=list(server["args"]), env=env)
    # 자식의 stderr 에 무엇이 찍힐지 모른다. 후보 값이 대시보드 로그로 가지 않게 버린다.
    with open(os.devnull, "w", encoding="utf-8") as sink:
        async with stdio_client(params, errlog=sink) as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()
                listed = await session.list_tools()
                annotations = next((item.annotations for item in listed.tools if item.name == tool), None)
                if annotations is None or annotations.read_only_hint is not True:
                    return None
                return await session.call_tool(tool, {})


def _mcp_sdk_version() -> str:
    """설치된 `mcp` SDK 의 판이다. 읽지 못하면 `unknown` 이다. 프로세스에서 한 번만 읽는다."""
    global _mcp_sdk_version_cache
    if _mcp_sdk_version_cache is None:
        try:
            _mcp_sdk_version_cache = importlib.metadata.version("mcp")
        except importlib.metadata.PackageNotFoundError:
            _mcp_sdk_version_cache = "unknown"
    return _mcp_sdk_version_cache


def _mcp_sdk_problem() -> Optional[str]:
    """커넥터 도구 호출이 기대는 SDK 가 아니면 까닭 한 줄을 돌려주고, 지원 범위이면 None 이다."""
    if _mcp_sdk_version().split(".")[0] != str(MCP_SDK_MAJOR):
        return "지원 범위 mcp>=%d.0,<%d 밖이다" % (MCP_SDK_MAJOR, MCP_SDK_MAJOR + 1)
    try:
        from mcp import ClientSession, StdioServerParameters
        from mcp.client.stdio import stdio_client
        import mcp.types as mcp_types
    except ImportError:
        return "mcp SDK 를 읽어 오지 못했다"
    if "read_only_hint" not in mcp_types.ToolAnnotations.model_fields:
        return "필요한 속성 read_only_hint 가 없다"
    for name in ("structured_content", "is_error", "content"):
        if name not in mcp_types.CallToolResult.model_fields:
            return "필요한 속성 %s 가 없다" % name
    return None


def _leaf_error_types(error) -> list:
    """예외 묶음을 끝까지 풀어 가장 안쪽 예외의 종류 이름을 모은다."""
    inner = getattr(error, "exceptions", None)
    if not inner:
        return [type(error).__name__]
    return [name for item in inner for name in _leaf_error_types(item)]


_UNREADABLE = object()


def _tool_payload(result):
    """도구 결과의 구조화 값이나 첫 텍스트 칸의 JSON 이다. 둘 다 읽지 못하면 `_UNREADABLE` 이다."""
    if result.structured_content is not None:
        return result.structured_content
    try:
        text = next(item.text for item in result.content if item.type == "text")
        return json.loads(text)
    except (StopIteration, ValueError, TypeError):
        return _UNREADABLE


def _tool_error(payload) -> tuple:
    """오류 결과의 `error` 객체와 그 코드다. 모양이 맞지 않으면 빈 객체와 None 이다."""
    error = payload.get("error") if isinstance(payload, dict) else None
    if not isinstance(error, dict):
        return {}, None
    code = error.get("code")
    return error, code if isinstance(code, str) else None


def _connector_call_answer(manifest: dict, result) -> dict:
    """도구 결과를 `{ok, result}` 나 `{ok, error}` 로 바꾼다. 읽지 못한 결과는 `unavailable` 이다."""
    payload = _tool_payload(result)
    if payload is _UNREADABLE:
        return {"ok": False, "error": "unavailable"}
    if result.is_error:
        _, code = _tool_error(payload)
        return {"ok": False, "error": manifest["errors"].get(code, "unavailable") if code else "unavailable"}
    return {"ok": True, "result": payload}


def _safe_error_detail(value) -> bool:
    """오류 세부 칸으로 넘길 수 있는 값인가. 상한 안의 정수와 boolean 만이다. 글과 실수, 배열, 객체는 넘기지 않는다."""
    if isinstance(value, bool):
        return True
    return type(value) is int and abs(value) <= ERROR_DETAIL_INT_MAX


def _connector_error_answer(manifest: dict, result, answer: dict) -> dict:
    """실패 답에 커넥터가 선언한 오류 코드와 복구 계약을 더한다(ADR-092).

    `errors` 표에 있는 코드만 더한다. 세부 칸은 그 코드의 `details` 가 적은 이름이고 값이 정수나 boolean 인 것만 옮긴다.
    도구 오류의 다른 칸, 원문 메시지, 중첩 값은 옮기지 않는다. 복구 어휘는 도구 결과가 아니라 manifest 에서 꺼낸다.
    """
    payload = _tool_payload(result)
    error, code = _tool_error(payload) if payload is not _UNREADABLE else ({}, None)
    if code is None or code not in manifest["errors"]:
        return answer
    answer = {**answer, "code": code}
    contract = manifest["error_contracts"].get(code)
    if contract is None:
        return answer
    if contract["recovery"] is not None:
        answer["recovery"] = contract["recovery"]
    details = {key: error[key] for key in contract["details"] if key in error and _safe_error_detail(error[key])}
    if details:
        answer["details"] = details
    return answer


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


async def _run_connector_execute(manifest: dict, hermes_tool: str, args: dict, env: dict, progress: dict):
    """커넥터 MCP 서버를 자식 프로세스로 한 번 띄워 등록 이름이 `hermes_tool` 인 도구를 `args` 로 부르고 닫는다.

    등록 이름이 같은 도구가 정확히 하나가 아니거나, `schema: 2` 인데 그 도구가 선언에 없으면 부르지 않고 None 이다.
    도구를 부르기 직전에 `progress["sent"]` 를 참으로 둔다. 그 뒤의 실패는 도구가 실행됐는지 알 수 없다.
    """
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client

    server = manifest["server"]
    params = StdioServerParameters(command=server["command"], args=list(server["args"]), env=env)
    # 자식의 stderr 에 무엇이 찍힐지 모른다. profile 의 값과 인자가 대시보드 로그로 가지 않게 버린다.
    with open(os.devnull, "w", encoding="utf-8") as sink:
        async with stdio_client(params, errlog=sink) as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()
                listed = await session.list_tools()
                names = [item.name for item in listed.tools
                         if _hermes_tool_name(manifest["mcp_server"], item.name) == hermes_tool]
                if len(names) != 1 or (manifest["schema"] == 2 and names[0] not in manifest["tools"]):
                    return None
                progress["sent"] = True
                return await session.call_tool(names[0], args)


def _connector_execute_answer(manifest: dict, result) -> dict:
    """실행 결과를 `{ok, result}` 나 `{ok, error}` 로 바꾼다.

    오류 없이 끝났는데 구조화 결과도 JSON 텍스트도 없으면 첫 텍스트 칸의 글을 `{"text": ...}` 로 싣는다.
    `call` 처럼 `unavailable` 로 답하면 이미 실행된 쓰기가 실패로 기록된다.
    """
    answer = _connector_call_answer(manifest, result)
    if result.is_error:
        return _connector_error_answer(manifest, result, answer)
    if answer["ok"]:
        return answer
    text = next((item.text for item in result.content if item.type == "text"), "")
    return {"ok": True, "result": {"text": text if isinstance(text, str) else ""}}


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
