"""MCP SDK 판을 확인하고 커넥터 도구를 부른 뒤 결과를 해석한다."""

from __future__ import annotations

import importlib.metadata
import json
import os

from typing import (
    Optional,
)

from .connector_policy import (
    _hermes_tool_name,
)


ERROR_DETAIL_INT_MAX = 1_000_000_000


# 커넥터 도구 호출이 기대는 mcp SDK 의 주 판이다. 다른 판은 결과 속성 이름이 달라 호출하지 않는다.
MCP_SDK_MAJOR = 2


# `_mcp_sdk_version` 이 한 번 읽은 판 문자열이다. 설치된 패키지는 프로세스가 도는 동안 바뀌지 않는다.
_mcp_sdk_version_cache: Optional[str] = None


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
