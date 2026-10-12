"""서비스 토큰으로만 열리는 private snapshot transport다."""

from __future__ import annotations

import asyncio
import json
import os

from .common import (CONNECTOR_HOST_MARKER, MANAGED_MARKER, _env_value, _env_line_key,
                     _missing_profile, _profile_rejection)
from .connector_guard import _strict_json
from .connector_diagnostics import snapshot_diagnostics_scope
from .connector_guard_validation import _same_json
from .connector_manifest import _connector_manifest, _entry_mode
from .connector_mcp import _mcp_sdk_problem
from .connector_run import (_installed_server_env, _installed_owner_attachments,
                            _installed_owner_browser)
from .connector_schema import BIND_MODE, CONNECTOR_ID_RE, CONNECTOR_STATE
from .connector_snapshot_manifest import SNAPSHOT_ENV, SNAPSHOT_FLAG
from .connector_snapshot_job import JOB_ERRORS, _job_args, _job_result, _authorize_url
from .connector_state import _connector_state

ENVELOPE_CAP = 524288
DRAFT_CAP = 65536
COMPILED_CAP = 262144


def _rejected(detail, status_code=400):
    from starlette.responses import JSONResponse
    return JSONResponse({"ok": False, "error": detail}, status_code=status_code)


def _json_bytes(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


async def _snapshot_body(request):
    # Content-Length는 믿지 않는다. chunked도 tree를 만들기 전에 같은 상한으로 끊는다.
    raw = bytearray()
    async for chunk in request.stream():
        if len(raw) + len(chunk) > ENVELOPE_CAP:
            raise ValueError("snapshot envelope 상한 초과")
        raw.extend(chunk)
    return _strict_json(raw)


def _snapshot_args(args):
    if (not isinstance(args, dict) or set(args) != {"canonicalDraft", "resolvedAssets"}
            or not isinstance(args["canonicalDraft"], dict)
            or len(_json_bytes(args["canonicalDraft"])) > DRAFT_CAP
            or not isinstance(args["resolvedAssets"], list) or len(args["resolvedAssets"]) > 50):
        raise ValueError("snapshot 인자가 올바르지 않다")
    seen = set()
    for asset in args["resolvedAssets"]:
        if not isinstance(asset, dict):
            raise ValueError("asset이 아니다")
        identifier = asset.get("assetId")
        if (not isinstance(identifier, str) or not identifier.isascii() or not identifier.isdecimal()
                or not 1 <= len(identifier) <= 19 or identifier.startswith("0")
                or int(identifier) > 9223372036854775807 or identifier in seen):
            raise ValueError("asset ID가 올바르지 않다")
        seen.add(identifier)
        for key, cap in (("photoDir", 4096), ("fileName", 255)):
            if not isinstance(asset.get(key), str) or len(asset[key].encode("utf-8")) > cap:
                raise ValueError("asset 경로 상한 초과")


def _snapshot_env(profile, manifest):
    from hermes_cli.profiles import get_profile_dir
    directory = get_profile_dir(profile)
    managed = (directory / MANAGED_MARKER).is_file()
    if not managed and not (directory / CONNECTOR_HOST_MARKER).is_file():
        raise PermissionError
    state_file = directory / CONNECTOR_STATE
    state = _connector_state(_strict_json(state_file.read_bytes()), manifest["id"]) if state_file.is_file() else {}
    if manifest["id"] not in state:
        raise FileNotFoundError
    if not managed and _entry_mode(state[manifest["id"]]) != BIND_MODE:
        raise PermissionError
    env_file = directory / ".env"
    text = env_file.read_text(encoding="utf-8") if env_file.is_file() else ""
    installed = _installed_server_env(directory, manifest)
    if set(installed) & SNAPSHOT_ENV or {_env_line_key(line) for line in text.splitlines()} & SNAPSHOT_ENV:
        raise PermissionError
    server = manifest["server"]
    env = {field["env"]: _env_value(text, field["env"]) for field in manifest["fields"]}
    env.update({name: server["env"][name] for name in manifest["operator_env"]})
    if manifest["owner_attachments_env"] is not None:
        env[manifest["owner_attachments_env"]] = _installed_owner_attachments(installed, manifest)
    if manifest["owner_browser_env"] is not None:
        env[manifest["owner_browser_env"]] = _installed_owner_browser(installed, manifest)
    if manifest["owner_output_env"] is not None:
        env[manifest["owner_output_env"]] = ""
    env["PATH"] = os.path.dirname(server["command"])
    return env


async def _run_connector_snapshot(manifest, args, env, operation="compile"):
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client
    server = manifest["server"]
    params = StdioServerParameters(command=server["command"],
                                  args=[*server["args"], SNAPSHOT_FLAG, operation], env=env)
    tool = manifest["snapshot"][operation + "_tool"]
    with snapshot_diagnostics_scope(), open(os.devnull, "w", encoding="utf-8") as sink:
        async with stdio_client(params, errlog=sink) as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()
                listed = await session.list_tools()
                # private process에는 구현한 operation 하나만 있다. 선언과 실제 목록이 다르면 호출하지 않는다.
                if (len(listed.tools) != 1 or listed.tools[0].name != tool
                        or listed.tools[0].annotations is None
                        or listed.tools[0].annotations.read_only_hint is not (operation != "start")):
                    raise ValueError("snapshot 선언 불일치")
                result = await session.call_tool(tool, args)
                if operation == "compile":
                    return _snapshot_tool_result(result)
                return _job_result(operation, args, _snapshot_tool_result(result, job=True))


def _snapshot_tool_result(result, job=False):
    if len(result.model_dump_json(by_alias=True).encode("utf-8")) > ENVELOPE_CAP or result.is_error and not job:
        raise ValueError("snapshot 결과 거절")
    texts = [_strict_json(item.text) for item in result.content if item.type == "text"]
    if result.is_error:
        # start·status 의 고정 오류 code 는 isError 결과의 text 하나로만 온다. 그 밖의 오류는 원문을 버린다.
        if (result.structured_content is not None or len(result.content) != 1 or len(texts) != 1
                or not isinstance(texts[0], dict) or set(texts[0]) != {"error"} or texts[0]["error"] not in JOB_ERRORS):
            raise ValueError("snapshot 결과 거절")
        return texts[0]
    payload = result.structured_content
    if payload is None:
        if len(texts) != 1:
            raise ValueError("snapshot 결과가 아니다")
        payload = texts[0]
    if (not isinstance(payload, dict) or any(not _same_json(value, payload) for value in texts)
            or len(_json_bytes(payload)) > COMPILED_CAP):
        raise ValueError("snapshot 결과 상한 초과")
    return payload


async def _connector_snapshot_request(request, connector_id):
    from hermes_cli.dashboard_auth import token_auth
    from .routes import ProfileApiProvider
    from . import connector_run
    from starlette.responses import JSONResponse
    principal, _ = token_auth.authenticate_token(request)
    if principal is None or getattr(principal, "provider", None) != ProfileApiProvider.name:
        return _rejected("Control Plane 토큰이 필요하다", 401)
    try:
        body = await _snapshot_body(request)
        if (not isinstance(body, dict) or set(body) != {"profile", "operation", "args"}
                or not isinstance(body["operation"], str)):
            return _rejected("profile, operation, args만 필요하다")
        manifest = _connector_manifest(connector_id) if CONNECTOR_ID_RE.fullmatch(connector_id) else None
        if manifest is None:
            return _rejected("없는 connector다", 404)
        declaration = manifest.get("snapshot")
        operation = body["operation"]
        if (declaration is None or operation not in {"compile", "start", "status"}
                or declaration["capabilities"][operation] is not True):
            return _rejected("지원하지 않는 snapshot operation이다", 409)
        if operation == "compile":
            _snapshot_args(body["args"])
        else:
            _job_args(body["profile"], operation, body["args"])
            if operation == "start" and len(_json_bytes(body["args"]["snapshot"])) > COMPILED_CAP:
                raise ValueError("snapshot 상한 초과")
    except (ValueError, UnicodeError, TypeError, OverflowError):
        return _rejected("snapshot 입력이 올바르지 않다")
    rejected = _profile_rejection(body["profile"], request)
    if rejected is not None:
        return _rejected("snapshot profile이 올바르지 않다", rejected.status_code)
    try:
        missing = _missing_profile(body["profile"])
        if missing is not None:
            return _rejected("없는 snapshot profile이다", missing.status_code)
        env = _snapshot_env(body["profile"], manifest)
        if operation == "start":
            env["FOS_CONTENT_SAVE_AUTHORIZE_URL"] = _authorize_url()
    except PermissionError:
        return _rejected("snapshot profile을 허용하지 않는다", 401)
    except FileNotFoundError:
        return _rejected("설치하지 않은 connector다", 404)
    except Exception:
        return _rejected("snapshot 설치를 확인하지 못했다", 503)
    if _mcp_sdk_problem() is not None or connector_run._connector_calls >= connector_run.CONNECTOR_CALL_LIMIT:
        return _rejected("snapshot을 실행할 수 없다", 503)
    connector_run._connector_calls += 1
    try:
        result = await asyncio.wait_for(_run_connector_snapshot(manifest, body["args"], env, operation),
                                        60 if operation == "start" else 5)
        if len(_json_bytes(result)) > COMPILED_CAP:
            raise ValueError("snapshot result 상한 초과")
        answer = {"ok": True, "result": result, "capabilities": declaration["capabilities"]}
        if len(_json_bytes(answer)) > ENVELOPE_CAP:
            raise ValueError("snapshot envelope 상한 초과")
        return JSONResponse(answer)
    except asyncio.TimeoutError:
        return _rejected("snapshot 시간이 초과됐다", 504)
    except Exception:
        return JSONResponse({"ok": False, "error": "unavailable"}, status_code=503)
    finally:
        connector_run._connector_calls -= 1
        env.clear()
