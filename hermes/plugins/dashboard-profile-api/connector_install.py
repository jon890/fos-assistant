"""커넥터 조회, 설치, 떼기와 MCP 서버 probe 요청을 검사한다."""

from __future__ import annotations

import asyncio
import json
import os
import pathlib
import re

# 옛 기능 모듈의 import 계약을 유지하려고 이동한 이름도 다시 내보낸다.

from .common import (
    CONNECTOR_HOST_MARKER,
    CONTROL_PLANE_MCP,
    MANAGED_MARKER,
    _json_object,
    _missing_profile,
    _profile_rejection,
    _rejected,
    logger,
)

from .connector_binding import (
    _connector_bind_config,
)

from .connector_isolated import (
    _connector_config,
)

from .connector_manifest import (
    _connector_manifest,
    _connector_roots,
    _entry_mode,
)

from .connector_schema import (
    BIND_MODE,
    CONNECTOR_ID_RE,
    CONNECTOR_STATE,
)

from .connector_state import (
    CONNECTOR_DETACHED,
    CONNECTOR_TOOL_MAP,
    POLICY_PLUGIN,
    PROFILE_SKILLS_DIR,
    SOUL_FILE,
    _bind_entry_env,
    _bind_entry_shape,
    _connector_allowlist,
    _connector_server,
    _connector_state,
    _connector_tool_map,
    _detached_bytes,
    _detached_servers,
    _entry_field_env,
    _entry_matches_manifest,
    _owned_mode,
    _remove_backup_env_copies,
    _skill_tree_files,
    _tool_map_bytes,
)

from .connector_status import (
    _bind_skills_installed,
    _policy_hook_active,
    _policy_plugin_enabled,
)

from .connector_vault import (
    VAULT_ID_RE,
    _read_vault,
    _vault_values,
)

from .profiles import (
    _check_skills_list,
)

from .connector_output import (
    _sandbox_connector_output_directory,
    _sandbox_prepare_connector_output,
)

from .sandbox import (
    SANDBOX_OWNER_RE,
    SANDBOX_TOOLSETS,
    SandboxAttachmentError,
    _sandbox_attachment_agent_directory,
    _sandbox_attachment_path_identity,
    _sandbox_policy,
    _sandbox_terminal,
    _sandbox_unavailable,
    _sandbox_verify_attachment_directories,
    _sandbox_workspace,
)


# 설치한 커넥터의 MCP 서버 probe 다. 서버 이름은 그 profile 의 소유 기록과 manifest 로 확인한다.
PROBE_ROUTE_RE = re.compile(r"^/api/mcp/servers/([^/]+)/test$")


async def _connector_request(request):
    from hermes_cli.profiles import get_profile_dir
    from starlette.responses import JSONResponse
    roots = _connector_roots()
    if request.method.upper() == "GET":
        profiles = request.query_params.getlist("profile")
        if len(profiles) != 1 or set(request.query_params.keys()) != {"profile"}:
            return _rejected("query 에 profile 하나만 필요하다")
        body = {"profile": profiles[0]}
    else:
        body = await _json_object(request)
        bind = body.get("bind") if isinstance(body, dict) else None
        # 운영 목록에 없는 이름은 끄기만 받는다. 소유 기록이 있으면 설치를 끄고, 없으면 바꾸지 않고 성공이다.
        # `bind` 는 바인딩 설치에서만 뜻이 있다. 떼기는 소유 기록의 설치 방식을 따른다.
        # `sandbox_owner` 는 사진 도구를 여는 옛 설치와, 사용자 첨부를 읽는 커넥터의 바인딩 설치에서 쓴다(ADR-091, ADR-20261007 connector-owner-attachments).
        # 바인딩 설치는 API 도구 목록의 내장 toolset 을 바꾸지 않는다. 그 값으로 주인의 첨부 디렉터리만 정한다.
        if (body is None
                or not {"profile", "plugin", "enabled"} <= set(body) <= {"profile", "plugin", "enabled", "bind", "sandbox_owner"}
                or not isinstance(body["plugin"], str) or not CONNECTOR_ID_RE.match(body["plugin"])
                or not isinstance(body["enabled"], bool)
                or (body["enabled"] and body["plugin"] not in roots)
                or ("bind" in body and (not isinstance(bind, dict) or set(bind) != {"vault"}
                                        or not isinstance(bind["vault"], str) or not VAULT_ID_RE.match(bind["vault"])))):
            return _rejected("profile, 알려진 plugin, enabled 와 바인딩이면 bind.vault, 그리고 sandbox_owner 만 받는다")
        owner = body.get("sandbox_owner")
        if owner is not None and not (isinstance(owner, str) and SANDBOX_OWNER_RE.match(owner)):
            return _rejected("sandbox_owner 형식이 올바르지 않다")
    profile = body["profile"]
    rejected = _profile_rejection(profile, request)
    if rejected is not None:
        return rejected
    missing = _missing_profile(body["profile"])
    if missing is not None:
        return missing
    profile_dir = get_profile_dir(body["profile"])
    # 표식은 대상의 방식으로 판정한다. 커넥터 표식만 있는 사람이 만든 profile 은 바인딩 설치만 받는다.
    managed = (profile_dir / MANAGED_MARKER).is_file()
    host = (profile_dir / CONNECTOR_HOST_MARKER).is_file()
    owned_mode = None if request.method.upper() == "GET" else _owned_mode(profile_dir, body["plugin"])
    # 커넥터 표식만 있는 profile 의 떼기는 그 항목이 없어도 바인딩 떼기로 다룬다. 다시 보낸 떼기가 바꾸지 않고 성공한다.
    unbind = (request.method.upper() != "GET" and not body["enabled"]
              and (owned_mode == BIND_MODE or (owned_mode is None and not managed)))
    bind_target = request.method.upper() == "GET" or ("bind" in body if body["enabled"] else unbind)
    if not (managed or (host and bind_target)):
        return _rejected("관리 표식이 없는 profile 이다", 401)
    stage = "status" if request.method.upper() == "GET" else "manifest"
    try:
        if request.method.upper() == "GET":
            import yaml
            config = yaml.safe_load((profile_dir / "config.yaml").read_text(encoding="utf-8")) or {}
            state_path = profile_dir / CONNECTOR_STATE
            # 바인딩 항목은 모양만 보고, manifest 와 맞는지는 아래에서 항목마다 따로 판정한다.
            state = _connector_state(json.loads(state_path.read_text(encoding="utf-8"))) if state_path.exists() else {}
            servers = config.get("mcp_servers") or {}
            try:
                expected = _connector_allowlist(state, servers)
            except FileExistsError:
                expected = None
            # 설치가 쓰는 목록과 같고 Control Plane MCP 등록이 없어야 설치가 끝난 것이다.
            # 이 값은 profile 단위다. 목록이 profile 하나에 하나뿐이라 서버 이름을 찾지 못하는 기록이 하나라도 있으면
            # 그 profile 의 커넥터가 모두 `configured: false` 다.
            allowed = (config.get("platform_toolsets") or {}).get("api_server")
            isolated = (expected is not None and CONTROL_PLANE_MCP not in servers and allowed == expected)
            connectors = []
            for plugin in roots:
                manifest = _connector_manifest(plugin)
                entry = state.get(plugin)
                # 설치하지 않은 커넥터는 소유 기록의 기본값처럼 옛 설치로 답한다. 읽는 쪽은 설치한 항목의 값만 쓴다.
                mode = _entry_mode(entry)
                if entry is None or manifest is None or servers.get(manifest["mcp_server"]) != entry["server"]:
                    configured = False
                elif mode == BIND_MODE:
                    # Control Plane MCP 등록이 있어도 된다. 바인딩 설치는 그 등록과 다른 도구 이름을 그대로 둔다.
                    # 기록이 지금 manifest 와 다르면 그 항목만 거짓이다. 같은 profile 의 다른 항목은 따로 판정한다.
                    configured = (isinstance(allowed, list) and manifest["mcp_server"] in allowed
                                  and _entry_matches_manifest(plugin, entry)
                                  and _bind_skills_installed(profile_dir, manifest, entry))
                else:
                    configured = isolated
                connectors.append({"plugin": plugin, "enabled": entry is not None, "configured": configured,
                                   "mode": mode})
            # 운영 목록에서 빠진 커넥터의 기록은 설치를 끌 수 있게 보이되 쓸 수 있다고 답하지 않는다.
            connectors.extend({"plugin": plugin, "enabled": True, "configured": False, "mode": _entry_mode(entry)}
                              for plugin, entry in state.items() if plugin not in roots)
            return JSONResponse({"profile": body["profile"], "connectors": connectors,
                                 "policy_hook": _policy_hook_active(profile_dir, config, state)}, status_code=200)
        response = {key: body[key] for key in ("profile", "plugin", "enabled", "bind") if key in body}
        if body["enabled"] and "bind" in body:
            # 붙이는 요청 안에서 보관 파일을 읽는다. 보관 파일 쓰기와 같은 잠금 안이라 그 사이에 바뀌지 않는다.
            # 파일을 읽는 동안 이벤트 루프를 막지 않는다.
            stage = "manifest"
            manifest = await asyncio.to_thread(_connector_manifest, body["plugin"])
            if manifest is None:
                raise ValueError("쓸 수 없는 connector 는 설치하지 않는다")
            owner_attachments = None
            if manifest["owner_attachments_env"] is not None:
                # 경로는 운영 정책의 루트와 Control Plane 이 정한 주인에서만 만든다. 모델, 요청의 다른 칸, manifest 는
                # 경로를 정하지 못한다. 선언하지 않은 커넥터는 `sandbox_owner` 를 받아도 쓰지 않는다(ADR-20261007 connector-owner-attachments).
                if owner is None:
                    return _rejected("사용자 첨부를 읽는 connector 에는 sandbox_owner 가 필요하다")
                sandbox = _sandbox_policy()
                if sandbox is None:
                    return _sandbox_unavailable()
                try:
                    # Control Plane 이 만든 그 주인의 디렉터리를 조각마다 링크 없이 확인한다. 만들지 않는다(ADR-091).
                    await asyncio.to_thread(_sandbox_attachment_path_identity,
                                            pathlib.Path(sandbox["attachment_agent_root"]), owner)
                except (OSError, ValueError, RuntimeError):
                    return _sandbox_unavailable()
                owner_attachments = _sandbox_attachment_agent_directory(sandbox, owner)
            stage = "vault"
            stored = await asyncio.to_thread(_read_vault, body["bind"]["vault"])
            if stored is None or stored["connector"] != body["plugin"]:
                return _rejected("그 connector 의 보관 파일이 없다")
            values = _vault_values(manifest, stored["values"])
            if values is None:
                return _rejected("보관 파일의 값이 지금 칸 선언과 맞지 않는다")
            owner_output = None
            if manifest["owner_output_env"] is not None and owner is not None:
                # 경로는 운영 정책의 루트와 Control Plane 이 정한 주인, profile, 커넥터 id 로만 만든다.
                # 실행 공간 정책에 등록된 profile 에만 준다. 미등록 profile 은 그 디렉터리를 붙이지 않는다.
                # 정책이나 키가 없거나 만들지 못하면 빈 값으로 붙인다. 커넥터가 파일 출력만 거절한다(ADR-20261008 connector-output-files).
                sandbox = _sandbox_policy()
                if sandbox is not None and profile in sandbox["profiles"]:
                    try:
                        owner_output = await asyncio.to_thread(_sandbox_connector_output_directory, sandbox,
                                                               profile, owner, body["plugin"])
                    except (OSError, ValueError, RuntimeError) as error:
                        logger.warning("dashboard-profile-api: 커넥터 출력 디렉터리를 만들지 못해 빈 값으로 붙인다: %s",
                                       type(error).__name__)
            stage = "bind"
            result = await asyncio.to_thread(_connector_bind_config, profile_dir, body["plugin"], True,
                                             body["bind"]["vault"], values, owner_attachments, owner_output)
            return JSONResponse({**response, **result}, status_code=200)
        if unbind:
            stage = "bind"
            result = await asyncio.to_thread(_connector_bind_config, profile_dir, body["plugin"], False)
            return JSONResponse({**response, **result}, status_code=200)
        # 옛 설치가 사진 도구를 열면 그 에이전트 주인의 격리 실행 공간을 쓴다(ADR-091).
        sandbox_terminal = None
        attachment_guard = None
        local_execution = False
        manifest = _connector_manifest(body["plugin"])
        if body["enabled"] and manifest is not None and SANDBOX_TOOLSETS & set(manifest["toolsets"]):
            sandbox = _sandbox_policy()
            if sandbox is None:
                return _sandbox_unavailable()
            if profile in sandbox["profiles"]:
                if owner is None:
                    return _rejected("격리할 사진 도구에는 sandbox_owner 가 필요하다")
                try:
                    os.makedirs(_sandbox_workspace(sandbox, owner), exist_ok=True)
                    _sandbox_prepare_connector_output(sandbox, profile, owner)
                    prepared = _sandbox_verify_attachment_directories(sandbox, owner)
                    sandbox_terminal = _sandbox_terminal(sandbox, profile, owner, prepared)
                    attachment_guard = (sandbox, owner, prepared)
                except OSError:
                    return _sandbox_unavailable()
            else:
                return _sandbox_unavailable()
        stage = "isolated"
        result = await asyncio.to_thread(
            _connector_config, profile_dir, body["plugin"], body["enabled"], sandbox_terminal, local_execution,
            attachment_guard)
        return JSONResponse({**response, **result}, status_code=200)
    except SandboxAttachmentError:
        return _sandbox_unavailable()
    except FileExistsError as error:
        logger.warning("dashboard-profile-api: connector 요청 실패 단계=%s id=%s exception=%s",
                       stage, body.get("plugin", "unknown"), type(error).__name__)
        return _rejected("운영자 설정과 충돌한다", 409)
    except Exception as error:
        # manifest 내용이나 profile 환경 변수를 응답과 로그에 싣지 않는다.
        logger.warning("dashboard-profile-api: connector 요청 실패 단계=%s id=%s exception=%s",
                       stage, body.get("plugin", "unknown"), type(error).__name__)
        return _rejected("connector 파일 또는 설정을 확인하지 못했다", 503)


async def _check_connector_probe(request):
    """probe 는 그 profile 에 설치한 커넥터의 MCP 서버 이름일 때만 넘긴다."""
    rejected = await _check_skills_list(request)
    if rejected is not None:
        return rejected
    from hermes_cli.profiles import get_profile_dir
    server = PROBE_ROUTE_RE.match(request.url.path).group(1)
    profile_dir = get_profile_dir(request.query_params.getlist("profile")[0])
    managed = (profile_dir / MANAGED_MARKER).is_file()
    if not managed and not (profile_dir / CONNECTOR_HOST_MARKER).is_file():
        return _rejected("관리 표식이 없는 profile 이다", 401)
    state_path = profile_dir / CONNECTOR_STATE
    try:
        import yaml
        if not state_path.is_file():
            return _rejected("설치하지 않은 connector 다", 404)
        state = _connector_state(json.loads(state_path.read_text(encoding="utf-8")))
        roots = _connector_roots()
        target = next((plugin for plugin in state if plugin in roots
                       and (_connector_manifest(plugin) or {}).get("mcp_server") == server), None)
        if target is None:
            return _rejected("설치하지 않은 connector 다", 404)
        # 이 서버의 커넥터 항목만 지금 manifest 의 실행 정의와 맞는지 본다.
        owned = _connector_state(state, target)[target]
        # 커넥터 표식만 있는 profile 은 바인딩 설치만 받는다.
        if not managed and _entry_mode(owned) != BIND_MODE:
            return _rejected("관리 표식이 없는 profile 이다", 401)
        config = yaml.safe_load((profile_dir / "config.yaml").read_text(encoding="utf-8")) or {}
        if (config.get("mcp_servers") or {}).get(server) != owned["server"]:
            return _rejected("운영자 설정과 충돌한다", 409)
        if server not in (config.get("platform_toolsets") or {}).get("api_server", []):
            return _rejected("현재 manifest 와 profile 설정이 다르다", 409)
    except Exception:
        return _rejected("connector 설정을 확인하지 못했다", 503)
    return None
