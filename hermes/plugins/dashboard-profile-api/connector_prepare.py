"""금융 준비와 실행의 범용 소비 경계다. 지원 확인 실패는 자식 실행 전에 막는다."""

from __future__ import annotations

import asyncio
import os

from .connector_guard import EXECUTION_ENV, _guard_endpoint, _guard_support, _strict_json, _guard_hide_financial
from .connector_guard_validation import _bounded, _execution_env, _prepare_result, _require
from .connector_policy import _hermes_tool_name


async def _guard_operation(profile, manifest, entry, body, env, prepare=False):
    names = [name for name in manifest["tools"] if _hermes_tool_name(manifest["mcp_server"], name) == body["hermes_tool"]]
    if not names and not prepare and "execution" not in body:
        return None
    _require(len(names) == 1)
    tool = names[0]
    policy = manifest["tools"][tool]
    if prepare or policy["risk"] == "FINANCIAL":
        from .routes import PROFILE_WRITE_LOCK
        from hermes_cli.profiles import get_profile_dir
        async with PROFILE_WRITE_LOCK:
            await asyncio.to_thread(_guard_hide_financial, get_profile_dir(profile), {manifest["id"]: entry})
    if not prepare and "execution" not in body:
        _require(policy["risk"] not in {"FINANCIAL", "DESTRUCTIVE"})
        return None
    guard = manifest.get("execution_guard")
    _require(guard is not None and tool in guard["operations"] and policy["risk"] == "FINANCIAL"
             and policy["approval"] == "always" and entry.get("mode") == "bind")
    _bounded(body["args"], 16384)
    support = await asyncio.to_thread(_guard_support, profile, manifest, entry)
    _require(support["state"] == "verified")
    # HTTP 대기 중 재설치·철회된 항목과 이전 manifest를 다시 쓰지 않는다.
    from .connector_manifest import _connector_manifest
    from .connector_schema import CONNECTOR_STATE
    from hermes_cli.profiles import get_profile_dir
    current = _strict_json((get_profile_dir(profile) / CONNECTOR_STATE).read_text(encoding="utf-8"))
    latest = _connector_manifest(manifest["id"])
    _require(current.get(manifest["id"]) == entry and latest is not None
             and latest["execution_guard_manifest_sha256"] == manifest["execution_guard_manifest_sha256"])
    fields = {field["key"]: field for field in manifest["fields"]}
    from .common import _env_value
    fresh_env = (get_profile_dir(profile) / ".env").read_text(encoding="utf-8")
    for scope in guard["scope_fields"]:
        name = fields[scope["field"]]["env"]
        _require(bool(env.get(name)) and _env_value(fresh_env, name) == env[name])
        if not prepare:
            _require(body["args"].get(scope["arg"]) == env[name])
    if not prepare:
        env.update(_execution_env(body["execution"], body["args"], {
            "profile": profile, "connectorId": manifest["id"], "tool": tool,
            "bindingId": int(entry["guard"]["bindingId"]), "connectionId": int(entry["guard"]["connectionId"])}))
        claim = _guard_endpoint("claim")
        _require(claim is not None)
        env["FOS_APPROVAL_CLAIM_URL"] = claim
    return tool


async def _run_connector_prepare(manifest, tool, args, env):
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client
    server = manifest["server"]
    with open(os.devnull, "w", encoding="utf-8") as sink:
        async with stdio_client(StdioServerParameters(command=server["command"], args=list(server["args"]), env=env), errlog=sink) as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()
                listed = await session.list_tools()
                targets = [item for item in listed.tools if _hermes_tool_name(manifest["mcp_server"], item.name)
                           == _hermes_tool_name(manifest["mcp_server"], tool)]
                prepares = [item for item in listed.tools if item.name == manifest["execution_guard"]["prepare_tool"]]
                if (len(targets) != 1 or targets[0].name != tool or len(prepares) != 1
                        or prepares[0].annotations is None or prepares[0].annotations.read_only_hint is not True):
                    return None
                return await session.call_tool(prepares[0].name, {"v": 1, "tool": tool, "args": args})


def _prepare_answer(result, args, manifest, tool, env):
    _require(result is not None and result.is_error is not True)
    # 텍스트 JSON은 중복 키 검증 전까지 structured_content에 합치지 않는다.
    texts = [item.text for item in result.content if item.type == "text"]
    parsed = [_strict_json(text) for text in texts]
    payload = result.structured_content
    if payload is None:
        _require(len(parsed) == 1)
        payload = parsed[0]
    elif parsed:
        from .connector_guard_validation import _same_json
        _require(all(_same_json(payload, value) for value in parsed))
    return {"ok": True, "result": _prepare_result(payload, args, manifest, tool, env)}


def _guard_result_safe(answer, env):
    # JSON escape가 풀린 문자열 값과 키를 검사해 권한 원문이 결과·모델에 가지 않게 한다.
    secrets = tuple(env[name] for name in EXECUTION_ENV if env.get(name))

    def visit(value):
        if isinstance(value, str):
            _require(all(secret not in value for secret in secrets))
        elif isinstance(value, dict):
            for key, item in value.items():
                visit(key)
                visit(item)
        elif isinstance(value, list):
            for item in value:
                visit(item)
        else:
            _require(value is None or type(value) in (bool, int, float))

    visit(answer)
