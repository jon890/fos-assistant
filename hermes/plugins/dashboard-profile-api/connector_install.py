from __future__ import annotations

import asyncio
import json
import os
import pathlib
import re
import time
from typing import Optional
from .common import (
    BASE_ENV_KEYS,
    CONNECTOR_HOST_MARKER,
    CONTROL_PLANE_MCP,
    MANAGED_MARKER,
    SKILL_NAME_RE,
    _atomic_private_write,
    _env_line,
    _env_line_key,
    _env_value,
    _json_object,
    _missing_profile,
    _profile_rejection,
    _rejected,
    logger,
)

from .connector_manifest import (
    BIND_MODE,
    CONNECTOR_ID_RE,
    CONNECTOR_STATE,
    ISOLATED_MODE,
    OWNER_ATTACHMENTS_VALUE_RE,
    SERVER_NAME_RE,
    _connector_manifest,
    _connector_roots,
    _entry_mode,
    _hermes_tool_name,
    _server_matches,
)

from .connector_vault import (
    VAULT_ID_RE,
    _read_vault,
    _vault_values,
)

from .profiles import (
    _check_skills_list,
    _profile_plugin_files,
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
    _sandbox_validate_attachment_snapshot,
    _sandbox_verify_attachment_directories,
    _sandbox_workspace,
)



# 설치한 커넥터의 MCP 서버 probe 다. 서버 이름은 그 profile 의 소유 기록과 manifest 로 확인한다.
PROBE_ROUTE_RE = re.compile(r"^/api/mcp/servers/([^/]+)/test$")
# 바인딩 설치가 커넥터의 스킬을 복사하는 profile 안의 디렉터리와, 스킬 디렉터리 아래에서 복사하는 하위 디렉터리다.
PROFILE_SKILLS_DIR = "skills"
# 설치가 profile 에 쓰는 이름 대응 파일이다. 정책 hook 이 Hermes 등록 이름으로 원래 도구 이름을 찾는다(ADR-049).
CONNECTOR_TOOL_MAP = ".fos-connector-tools.json"
# 바인딩 떼기가 뗀 서버 이름을 남기는 기록이다. `{커넥터 id: 서버 이름}` 이다. 소유 기록 곁에 두고 이름 대응을 만들 때 함께 읽는다.
# 떼기 전에 시작한 실행은 그 서버를 쥔 채 돌므로, 대응에서 서버가 빠지면 그 호출이 판정 없이 나간다.
CONNECTOR_DETACHED = ".fos-connector-detached.json"
# 커넥터 도구 호출을 판정하는 hook 을 가진 profile plugin 과, 묶음의 판과 견주는 그 파일들이다.
POLICY_PLUGIN = "fos-ctx"
SOUL_FILE = "SOUL.md"


def _connector_server(manifest: dict) -> dict:
    """profile 설정에 쓸 서버 정의의 사본이다."""
    server = manifest["server"]
    copied = {**server, "args": list(server["args"]), "env": dict(server["env"])}
    if "tools" in server:
        copied["tools"] = {"exclude": list(server["tools"]["exclude"])}
    return copied


def _connector_tool_map(state: dict, detached: dict | None = None) -> dict:
    """소유 기록의 커넥터와 뗀 서버 기록으로 만든 이름 대응이다. 형식은 `docs/backend/connector-tool-policy.md` 의 「이름 대응」 이 갖는다.

    옛 설치는 운영 목록에서 빠졌거나 manifest 를 읽을 수 없는 커넥터를 싣지 않는다. 대응이 없는 도구는 hook 이 막는다.
    바인딩 설치는 `isolated: false` 를 싣고 소유 기록의 모든 서버를 싣는다. manifest 를 읽지 못한 서버는 빈 `tools` 다.
    바인딩 항목의 서버 이름은 기록의 이름이다. 기록의 이름이나 실행 정의가 지금 manifest 와 다르면 그 서버도 빈 `tools` 다.
    떼기는 남는 항목을 manifest 와 견주지 않으므로, 운영자가 manifest 를 바꾼 뒤에도 `config.yaml` 에 남은 서버가 대응에 있어야 한다.
    뗀 서버(`detached`)도 빈 `tools` 로 싣는다. 떼기 전에 시작한 실행이 그 서버를 쥐고 있어도 그 호출을 판정이 막는다.
    같은 이름을 지금 붙은 커넥터가 쓰면 붙은 쪽이 이긴다. 뗀 서버만 남아도 바인딩 profile 이다.
    바인딩 profile 의 hook 은 대응에 없는 서버를 통과시키므로, 서버가 빠지면 그 도구가 판정 없이 나간다(ADR-083).
    """
    roots = _connector_roots()
    detached = detached or {}
    bound = bool(detached) or any(_entry_mode(entry) == BIND_MODE for entry in state.values())
    servers = {name: {"connector": plugin, "prefix": _hermes_tool_name(name, ""), "tools": {}}
               for plugin, name in detached.items()}
    for plugin, entry in state.items():
        manifest = _connector_manifest(plugin) if plugin in roots else None
        if manifest is None:
            if bound:
                name = entry["mcp_server"]
                servers[name] = {"connector": plugin, "prefix": _hermes_tool_name(name, ""), "tools": {}}
            continue
        name = manifest["mcp_server"]
        tools = {_hermes_tool_name(name, tool): tool for tool in manifest["tools"]}
        if _entry_mode(entry) == BIND_MODE:
            recorded = entry.get("mcp_server") or name
            try:
                matches = _server_matches(manifest, entry["server"])
            except (KeyError, TypeError, AttributeError):
                matches = False
            if recorded != name or not matches:
                name, tools = recorded, {}
        servers[name] = {"connector": plugin, "prefix": _hermes_tool_name(name, ""), "tools": tools}
    if bound:
        return {"v": 1, "isolated": False, "servers": servers}
    return {"v": 1, "servers": servers}


def _detached_servers(raw: bytes | None) -> dict:
    """뗀 서버 기록의 본문을 읽는다. 파일이 없으면(`None`) 빈 객체다. 모양이 틀리면 ValueError 다.

    서버 이름은 대응 파일의 접두사가 되므로 `_connector_state` 의 서버 이름 규칙으로 본다.
    """
    if raw is None:
        return {}
    value = json.loads(raw)
    if not isinstance(value, dict) or any(
            not isinstance(plugin, str) or not CONNECTOR_ID_RE.match(plugin)
            or not isinstance(name, str) or not SERVER_NAME_RE.match(name) for plugin, name in value.items()):
        raise ValueError("뗀 서버 기록이 올바르지 않다")
    return value


def _detached_bytes(detached: dict) -> bytes | None:
    """뗀 서버 기록 파일의 본문이다. 남은 것이 없으면 None 이고 파일을 지운다."""
    return (json.dumps(detached, sort_keys=True) + "\n").encode("utf-8") if detached else None


def _tool_map_bytes(tool_map: dict) -> bytes:
    """이름 대응 파일의 본문이다. hook 상태 판정이 바이트로 견주므로 직렬화를 하나로 고정한다."""
    return (json.dumps(tool_map, sort_keys=True, ensure_ascii=False) + "\n").encode("utf-8")


def _connector_state(value, target: str | None = None) -> dict:
    """소유 기록의 모양을 보고, 운영 목록의 커넥터는 지금 manifest 의 실행 정의와 맞는지 본다.

    운영 목록에서 빠진 커넥터의 기록은 모양만 본다. 그 기록으로는 설치를 끄는 것만 한다.
    바인딩 항목(`mode: bind`)은 서버 이름, 보관 파일 이름, 설치한 스킬 이름을 갖는다.
    스킬 이름은 떼기가 지울 디렉터리 이름이라 경로 조각으로 쓸 수 있는지 여기서 본다.
    바인딩 항목은 요청이 가리키는 커넥터(`target`)의 것만 manifest 와 견주고 나머지는 모양만 본다.
    운영자가 커넥터 하나를 바꿔도 같은 profile 에 붙은 다른 커넥터의 실행, probe, 조회, 붙이기가 실패하지 않게 한다.
    맞지 않는 다른 항목은 이름 대응이 빈 `tools` 로 싣는다. 옛 설치 항목은 지금처럼 모두 견준다.
    """
    if not isinstance(value, dict):
        raise ValueError("connector 소유 기록이 올바르지 않다")
    roots = _connector_roots()
    for plugin, entry in value.items():
        if (not isinstance(entry, dict) or not {"server", "allowlist_added"} <= set(entry)
                or set(entry) - {"server", "allowlist_added", "mcp_server", "mode", "vault", "skills"}
                or not isinstance(entry["allowlist_added"], bool)
                or not isinstance(entry.get("mcp_server", ""), str)
                or entry.get("mode", ISOLATED_MODE) not in (ISOLATED_MODE, BIND_MODE)):
            raise ValueError("connector 소유 기록의 필드가 올바르지 않다")
        if entry.get("mode") == BIND_MODE:
            skills = entry.get("skills")
            if (not {"mcp_server", "vault", "skills"} <= set(entry)
                    or not SERVER_NAME_RE.match(entry["mcp_server"])
                    or not isinstance(entry["vault"], str) or not VAULT_ID_RE.match(entry["vault"])
                    or not isinstance(skills, list) or len(set(map(str, skills))) != len(skills)
                    or any(not isinstance(name, str) or not SKILL_NAME_RE.match(name) or ".." in name
                           for name in skills)):
                raise ValueError("connector 소유 기록의 바인딩 필드가 올바르지 않다")
        elif "vault" in entry or "skills" in entry:
            raise ValueError("옛 설치의 소유 기록에 바인딩 필드가 있다")
        server = entry["server"]
        tools = server.get("tools") if isinstance(server, dict) else None
        if (not isinstance(server, dict) or set(server) - {"tools"} != {"command", "args", "env", "enabled"}
                or ("tools" in server and (
                    not isinstance(tools, dict) or set(tools) != {"exclude"} or not isinstance(tools["exclude"], list)
                    or any(not isinstance(item, str) for item in tools["exclude"])))
                or not isinstance(server["command"], str) or server["enabled"] is not True
                or not isinstance(server["args"], list) or any(not isinstance(arg, str) for arg in server["args"])
                or not isinstance(server["env"], dict)
                or any(not isinstance(name, str) or not isinstance(item, str)
                       for name, item in server["env"].items())):
            raise ValueError("소유 기록의 실행 정의 모양이 올바르지 않다")
        if plugin not in roots or (entry.get("mode") == BIND_MODE and plugin != target):
            continue
        if not _entry_matches_manifest(plugin, entry):
            raise ValueError("소유 기록의 실행 정의가 지금 connector 와 다르다")
    return value


def _entry_matches_manifest(plugin: str, entry: dict) -> bool:
    """모양을 본 소유 기록 항목이 지금 manifest 의 서버 이름과 실행 정의와 맞는지 본다. manifest 가 없으면 거짓이다."""
    manifest = _connector_manifest(plugin)
    return (manifest is not None and _server_matches(manifest, entry["server"])
            and entry.get("mcp_server", manifest["mcp_server"]) == manifest["mcp_server"])


def _connector_allowlist(state: dict, servers: dict) -> list:
    """소유 기록의 커넥터 서버 이름과 그 커넥터들이 선언한 내장 toolset 으로 만든 API 도구 목록이다.

    서버 이름을 먼저 두고 manifest 의 `toolsets` 를 뒤에 둔다. 겹친 이름은 한 번만 싣는다.
    manifest 가 열 수 있는 내장 toolset 은 읽기 전용 이미지 도구뿐이다(ADR-044).
    운영 목록에서 빠졌거나 검증에 실패해 manifest 를 읽을 수 없는 커넥터는 toolset 을 더하지 않는다.
    기록이 없으면 MCP 를 전부 막는 값이다.
    목록에 등록된 MCP 서버 이름이 하나도 없으면 Hermes 가 등록된 서버를 모두 통과시킨다.
    그래서 빈 목록 대신 `no_mcp` 를 쓴다(ADR-045).
    옛 기록에는 서버 이름 칸이 없어 기록과 같은 서버 정의를 설정에서 찾는다.
    """
    roots = _connector_roots()
    names, toolsets = [], []
    for plugin, entry in state.items():
        name = entry.get("mcp_server") or next(
            (key for key, value in servers.items() if value == entry["server"]), None)
        if name is None:
            raise FileExistsError("설치한 MCP 서버가 밖에서 지워졌거나 바뀌었다")
        names.append(name)
        manifest = _connector_manifest(plugin) if plugin in roots else None
        if manifest is not None:
            toolsets.extend(manifest["toolsets"])
    if not names:
        return ["no_mcp"]
    return list(dict.fromkeys(names + toolsets))


def _remove_backup_env_copies(profile_dir: pathlib.Path) -> None:
    """이전 판이 백업에 남긴 `.env` 사본을 지운다. 백업 디렉터리가 없으면 아무것도 하지 않는다."""
    backups = profile_dir / "connector-backups"
    if not backups.is_dir():
        return
    for stale in backups.glob("*/.env"):
        try:
            stale.unlink()
        except OSError as error:
            logger.warning("dashboard-profile-api: 백업의 옛 env 사본을 지우지 못했다: %s", type(error).__name__)


def _connector_config(profile_dir: pathlib.Path, plugin: str, enabled: bool,
                      sandbox_terminal: Optional[dict] = None, local_execution: bool = False,
                      attachment_guard: Optional[tuple] = None) -> dict:
    """관리 표식 profile 의 설정을 바꾸고 실패하면 같은 요청 안에서 되돌린다.

    설치와 제거는 API 도구 목록을 커넥터 서버 이름과 manifest 가 선언한 내장 toolset 으로 다시 쓰고,
    설치는 Control Plane MCP 등록도 지운다(ADR-045).
    둘 다 이름 대응 파일을 바뀐 소유 기록으로 다시 쓰고, 설치는 정책 hook 을 가진 profile plugin 을 묶음의 판으로 맞춘다(ADR-049).
    `plugin_updated` 는 그 plugin 파일이 바뀌었는지다. 떠 있는 gateway 가 옛 코드를 쥐고 있을 수 있어 따로 답한다.
    이 설치는 커넥터 전용 profile 을 전제한다. Control Plane 이 커넥터 에이전트의 profile 로만 부른다.
    일반 에이전트의 profile 에 설치하면 그 profile 의 Control Plane MCP 등록과 도구 목록이 사라지고 제거해도 돌아오지 않는다.
    그래서 바인딩 설치가 있는 profile 에는 설치하지 않는다. 일반 에이전트에 붙이는 것은 `_connector_bind_config` 다.
    """
    import yaml
    if attachment_guard is not None:
        _sandbox_validate_attachment_snapshot(*attachment_guard)
    if profile_dir.resolve() != profile_dir:
        raise ValueError("profile 경로에 심볼릭 링크가 있다")
    config_path = profile_dir / "config.yaml"
    state_path = profile_dir / CONNECTOR_STATE
    env_path = profile_dir / ".env"
    soul_path = profile_dir / SOUL_FILE
    tool_map_path = profile_dir / CONNECTOR_TOOL_MAP
    plugin_dir = profile_dir / "plugins" / POLICY_PLUGIN
    # 묶음에 그 plugin 이 없으면 건드리지 않는다. 설치는 그대로 되고 hook 상태 조회가 거짓으로 답한다.
    bundled = (_profile_plugin_files(POLICY_PLUGIN) if enabled else None) or {}
    plugin_values = {plugin_dir / file_name: value for file_name, value in bundled.items()}
    plugin_dirs = (plugin_dir.parent, plugin_dir) if plugin_values else ()
    for path in (config_path, state_path, env_path, soul_path, tool_map_path, *plugin_dirs, *plugin_values):
        if path.is_symlink():
            raise ValueError("profile 설정에 심볼릭 링크가 있다")
    # 바뀐 것이 없어 일찍 돌아가는 요청에서도 옛 사본은 지운다.
    _remove_backup_env_copies(profile_dir)
    originals = {path: path.read_bytes() if path.exists() else None
                 for path in (config_path, state_path, env_path, soul_path, tool_map_path, *plugin_values)}
    saved = yaml.safe_load(originals[config_path]) or {}
    state = _connector_state(json.loads(originals[state_path]), plugin) if originals[state_path] else {}
    detached_path = profile_dir / CONNECTOR_DETACHED
    if enabled and (any(_entry_mode(entry) == BIND_MODE for entry in state.values())
                    or detached_path.exists() or detached_path.is_symlink()):
        # 두 설치를 섞으면 이 설치가 지우는 Control Plane MCP 등록을 바인딩 설치가 전제하므로 서로를 깬다.
        # 뗀 서버 기록이 남은 profile 은 바인딩 profile 이다. 옛 설치의 이름 대응이 그 기록을 싣지 않는다.
        raise FileExistsError("바인딩 설치가 있는 profile 에 옛 설치를 하지 않는다")
    servers = dict(saved.get("mcp_servers") or {})
    owned = state.get(plugin)
    listed = plugin in _connector_roots()
    manifest = _connector_manifest(plugin) if listed else None
    if manifest is not None:
        name = manifest["mcp_server"]
    elif enabled:
        raise ValueError("쓸 수 없는 connector 는 설치하지 않는다")
    elif not owned:
        # 끌 것이 없다. 운영 목록에서 빠진 연결의 해제가 끝까지 가도록 성공으로 답한다.
        return {"changed": False, "restart_required": False, "plugin_updated": False}
    else:
        # 운영 목록에서 빠진 커넥터다. 옛 기록에는 서버 이름이 없어 기록과 같은 정의를 설정에서 찾는다.
        name = owned.get("mcp_server") or next(
            (key for key, value in servers.items() if value == owned["server"]), None)
        if name is None:
            raise FileExistsError("설치한 MCP 서버가 밖에서 지워졌거나 바뀌었다")
    if name in servers and (not owned or servers[name] != owned["server"]):
        raise FileExistsError("운영자가 등록하거나 바꾼 MCP 서버가 있다")
    if owned and name not in servers:
        raise FileExistsError("설치한 MCP 서버가 밖에서 지워졌다")
    allowed = list((saved.get("platform_toolsets") or {}).get("api_server") or [])
    env_text = originals[env_path].decode("utf-8") if originals[env_path] else ""
    values = {}
    if enabled:
        server = _connector_server(manifest)
        # Hermes 는 빈 변수의 참조를 그대로 남긴다. 값이 없는 선택 칸은 빈 값을 명시한다.
        for env_name in manifest["optional_env"]:
            if not _env_value(env_text, env_name):
                server["env"][env_name] = ""
        # `allowlist_added` 는 더 읽지 않는다. 옛 판이 남긴 기록을 읽을 수 있게 칸만 남긴다.
        added = owned["allowlist_added"] if owned else True
        servers[name] = server
        # 커넥터 에이전트는 Control Plane 도구를 받지 않는다. 제거는 이 등록을 되살리지 않는다.
        servers.pop(CONTROL_PLANE_MCP, None)
        state[plugin] = {"server": server, "allowlist_added": added, "mcp_server": name}
        allowed = _connector_allowlist(state, servers)
        if manifest["persona"] is not None:
            # 지침은 이 커넥터의 소유 기록과 함께, 관리 표식이 있는 profile 에만 쓴다.
            # 사람이 만든 profile 과 이 요청이 가리키지 않은 profile 의 `SOUL.md` 는 건드리지 않는다.
            # 연결용 profile 인지는 여기서 알 수 없다. Control Plane 이 연결용 에이전트의 profile 만 보낸다.
            if not (profile_dir / MANAGED_MARKER).is_file():
                raise ValueError("관리 표식이 없는 profile 에는 지침을 쓰지 않는다")
            values[soul_path] = manifest["persona"].encode("utf-8")
    elif owned:
        servers.pop(name, None)
        state.pop(plugin, None)
        allowed = _connector_allowlist(state, servers)
        if manifest is None and originals[env_path] is not None:
            # Control Plane 은 목록에서 빠진 커넥터의 env 이름을 모른다. 기록이 참조하던 key 를 여기서 지운다.
            referenced = {key for key, value in owned["server"]["env"].items() if value == "${%s}" % key}
            kept = [line for line in env_text.splitlines(keepends=True)
                    if line.partition("=")[0] not in referenced]
            values[env_path] = "".join(kept).encode("utf-8")
    updated = {**saved, "mcp_servers": servers,
               "platform_toolsets": {**(saved.get("platform_toolsets") or {}), "api_server": allowed}}
    if sandbox_terminal is not None:
        updated["terminal"] = sandbox_terminal
    elif local_execution:
        previous = saved.get("terminal") or {}
        if not isinstance(previous, dict):
            raise ValueError("terminal 설정이 객체가 아니다")
        terminal = dict(previous) if previous.get("backend", "local") == "local" else {}
        terminal["backend"] = "local"
        updated["terminal"] = terminal
    values = {config_path: yaml.safe_dump(updated, sort_keys=False, allow_unicode=True).encode(),
              state_path: (json.dumps(state) + "\n").encode(), **values}
    if enabled or owned:
        # 설정만 바뀌고 대응이 옛것으로 남으면 hook 이 새 도구를 모두 막는다. 같은 묶음 안에서 쓴다.
        values[tool_map_path] = _tool_map_bytes(_connector_tool_map(state))
    # plugin 파일은 맨 뒤에 쓴다. 앞의 쓰기가 실패하면 plugin 은 손대지 않은 채 남는다.
    values.update(plugin_values)
    plugin_updated = any(originals[path] != value for path, value in plugin_values.items())
    if all(originals[path] == value for path, value in values.items()):
        return {"changed": False, "restart_required": bool(owned), "plugin_updated": False}
    backup = profile_dir / "connector-backups" / str(time.time_ns())
    backup.mkdir(parents=True, mode=0o700)
    os.chmod(backup.parent, 0o700)
    for path, value in originals.items():
        # profile `.env` 에는 사용자의 비밀 원문이 있다. 백업에 넣으면 연결을 해제한 뒤에도 남는다.
        if value is None or path == env_path:
            continue
        if path not in plugin_values:
            _atomic_private_write(backup / path.name, value)
        elif value != plugin_values[path]:
            # 묶음의 판과 같은 plugin 파일은 뜨지 않는다. 다른 것만 plugin 이름을 붙여 남긴다.
            _atomic_private_write(backup / ("%s.%s" % (POLICY_PLUGIN, path.name)), value)
    written = []
    created_dirs = []
    try:
        if any((path.read_bytes() if path.exists() else None) != value for path, value in originals.items()):
            raise FileExistsError("저장 전 profile 설정이 밖에서 바뀌었다")
        for directory in plugin_dirs:
            if not directory.is_dir():
                directory.mkdir()
                created_dirs.append(directory)
                os.chmod(directory, 0o755)
        for path, value in values.items():
            if (path.read_bytes() if path.exists() else None) != originals[path]:
                raise FileExistsError("profile 설정이 밖에서 바뀌었다")
            if path == config_path and attachment_guard is not None:
                _sandbox_validate_attachment_snapshot(*attachment_guard)
            _atomic_private_write(path, value)
            written.append(path)
            if path in plugin_values:
                # 임시 파일은 600 으로 생긴다. plugin 파일은 새 profile 에 복사할 때와 같은 644 로 둔다.
                os.chmod(path, 0o644)
    except Exception:
        for path in reversed(written):
            # 이 요청이 쓴 값일 때만 복원한다. 바깥의 새 수정은 덮어쓰지 않는다.
            if not path.exists() or path.read_bytes() != values[path]:
                continue
            value = originals[path]
            if value is None:
                path.unlink(missing_ok=True)
            else:
                _atomic_private_write(path, value)
                if path in plugin_values:
                    os.chmod(path, 0o644)
        for directory in reversed(created_dirs):
            # 이 요청이 만든 디렉터리만 지운다. 그 사이 밖에서 무엇이 생겼으면 비어 있지 않아 남는다.
            try:
                directory.rmdir()
            except OSError:
                pass
        raise
    return {"changed": True, "restart_required": bool(owned), "plugin_updated": plugin_updated}


def _entry_field_env(plugin: str, entry: dict) -> set:
    """소유 기록 항목의 커넥터가 profile `.env` 에 두는 이름이다.

    manifest 가 있으면 칸의 env 이름이고, 운영 목록에서 빠졌으면 기록의 서버 정의가 `${이름}` 으로 참조하던 이름이다.
    """
    manifest = _connector_manifest(plugin) if plugin in _connector_roots() else None
    if manifest is not None:
        return {field["env"] for field in manifest["fields"]}
    return {key for key, value in entry["server"]["env"].items() if value == "${%s}" % key}


def _bind_entry_shape(entry) -> None:
    """떼기가 쓰는 바인딩 항목의 모양만 본다. 지금 manifest 의 실행 정의와는 견주지 않는다.

    항목이 객체이고 `mode` 가 `bind` 여야 한다. 서버 이름과 스킬 이름은 설정에서 지울 키와 지울 디렉터리 이름이라
    경로 조각으로 쓸 수 있는지 `_connector_state` 와 같은 규칙으로 본다.
    """
    skills = entry.get("skills") if isinstance(entry, dict) else None
    if (not isinstance(entry, dict) or entry.get("mode") != BIND_MODE
            or not isinstance(entry.get("mcp_server"), str) or not SERVER_NAME_RE.match(entry["mcp_server"])
            or not isinstance(skills, list) or len(set(map(str, skills))) != len(skills)
            or any(not isinstance(name, str) or not SKILL_NAME_RE.match(name) or ".." in name for name in skills)):
        raise ValueError("connector 소유 기록의 바인딩 필드가 올바르지 않다")


def _bind_entry_env(manifest: dict | None, entry: dict) -> set:
    """떼기가 profile `.env` 에서 지울 이름이다.

    기록의 서버 정의가 `${이름}` 으로 참조하는 이름은 늘 지운다. 운영 목록에서 빠졌거나 실행 정의가 바뀌어도 설치할 때 쓴
    이름이 거기 남아 있다. manifest 가 있고 기록의 실행 정의가 지금과 같으면 칸의 env 이름도 지운다.
    Control Plane 이 쓰는 이름은 지우지 않는다.
    """
    server = entry.get("server")
    env = server.get("env") if isinstance(server, dict) else None
    names = {key for key, value in env.items() if isinstance(key, str) and value == "${%s}" % key} \
        if isinstance(env, dict) else set()
    if manifest is not None:
        try:
            matches = _server_matches(manifest, server)
        except (KeyError, TypeError, AttributeError):
            matches = False
        if matches:
            names |= {field["env"] for field in manifest["fields"]}
    return names - BASE_ENV_KEYS


def _skill_tree_files(directory: pathlib.Path) -> dict | None:
    """profile 에 설치한 스킬 디렉터리 하나의 정규 파일이다. `{상대 경로: 경로}` 다. 없으면 None 이다.

    그 아래 어느 항목이든 링크이면 예외다. 링크를 따라 profile 밖의 파일을 지우거나 읽지 않는다.
    """
    if directory.is_symlink():
        raise ValueError("스킬 디렉터리가 링크다")
    if not directory.is_dir():
        return None
    files = {}
    for current, dirs, names in os.walk(directory):
        for child in dirs + names:
            if os.path.islink(os.path.join(current, child)):
                raise ValueError("스킬 디렉터리 아래에 링크가 있다")
        for name in names:
            path = pathlib.Path(current) / name
            files[path.relative_to(directory).as_posix()] = path
    return files


def _connector_bind_config(profile_dir: pathlib.Path, plugin: str, enabled: bool,
                           vault: str | None = None, values: dict | None = None,
                           owner_attachments: str | None = None) -> dict:
    """일반 에이전트의 profile 에 커넥터를 붙이거나 뗀다. 실패하면 같은 요청 안에서 이 요청이 쓴 파일만 되돌린다.

    붙이기는 보관 파일의 값(`values`)을 그 profile `.env` 에 쓰고, 서버를 더하고, API 도구 목록에 서버 이름을 더하고,
    plugin 의 스킬을 그 profile 의 스킬로 복사한다. Control Plane MCP 등록, 다른 도구 이름, `SOUL.md` 는 건드리지 않는다.
    새로 붙이기는 그 profile 에서 정책 hook plugin 이 켜져 있어야 한다. 꺼진 profile 에 붙이면 도구 호출이 판정 없이 나간다.
    이미 붙은 커넥터를 다시 설치하는 것은 hook 이 꺼져 있어도 받는다. 그때 그 바인딩은 hook 상태로 `PENDING` 에 남는다.
    떼기는 그 서버와 이름과 env 와 스킬만 지운다(ADR-083).
    떼기는 서버 이름을 뗀 서버 기록에 남기고 이름 대응에 빈 `tools` 로 남긴다. 대응 파일은 지우지 않는다.
    같은 커넥터를 다시 붙이면 그 기록을 지운다.
    떠 있는 profile 에 더한 서버는 gateway 를 다시 띄워야 보이므로 붙이기는 바뀐 것이 있으면 재시작이 필요하다고 답한다.
    떼기는 재시작이 필요 없다고 답한다. 다음 실행은 도구 목록에서 이름이 빠져 그 서버를 받지 않고,
    떼기 전에 시작해 그 서버를 쥔 실행의 호출은 대응에 남은 서버를 보고 hook 이 묻고 판정이 막는다.
    manifest 가 `owner_attachments_env` 를 선언했으면 `owner_attachments` 를 그 이름으로 서버 정의에 직접 넣는다.
    다시 설치할 때마다 받은 주인의 값으로 다시 쓴다(ADR-20261007 connector-owner-attachments).
    """
    import yaml
    if profile_dir.resolve() != profile_dir:
        raise ValueError("profile 경로에 심볼릭 링크가 있다")
    config_path = profile_dir / "config.yaml"
    state_path = profile_dir / CONNECTOR_STATE
    env_path = profile_dir / ".env"
    tool_map_path = profile_dir / CONNECTOR_TOOL_MAP
    detached_path = profile_dir / CONNECTOR_DETACHED
    skills_dir = profile_dir / PROFILE_SKILLS_DIR
    plugin_dir = profile_dir / "plugins" / POLICY_PLUGIN
    bundled = (_profile_plugin_files(POLICY_PLUGIN) if enabled else None) or {}
    plugin_values = {plugin_dir / file_name: value for file_name, value in bundled.items()}
    plugin_dirs = (plugin_dir.parent, plugin_dir) if plugin_values else ()
    for path in (config_path, state_path, env_path, tool_map_path, detached_path, skills_dir, *plugin_dirs,
                 *plugin_values):
        if path.is_symlink():
            raise ValueError("profile 설정에 심볼릭 링크가 있다")
    _remove_backup_env_copies(profile_dir)
    originals = {path: path.read_bytes() if path.exists() else None
                 for path in (config_path, state_path, env_path, tool_map_path, detached_path, *plugin_values)}
    saved = yaml.safe_load(originals[config_path]) or {}
    detached = _detached_servers(originals[detached_path])
    if not originals[state_path]:
        state = {}
    elif enabled:
        state = _connector_state(json.loads(originals[state_path]), plugin)
    else:
        # 떼기는 기록 전체를 지금 manifest 와 견주지 않는다. 운영자가 실행 정의를 바꾼 뒤에도 떼야 `.env` 의 비밀이 남지 않는다.
        state = json.loads(originals[state_path])
        if not isinstance(state, dict):
            raise ValueError("connector 소유 기록이 올바르지 않다")
    if any(_entry_mode(entry) != BIND_MODE for entry in state.values()):
        raise FileExistsError("옛 설치가 있는 profile 에 바인딩을 하지 않는다")
    servers = dict(saved.get("mcp_servers") or {})
    platform = dict(saved.get("platform_toolsets") or {})
    allowed = platform.get("api_server")
    owned = state.get(plugin)
    if not enabled and owned is not None:
        _bind_entry_shape(owned)
    manifest = _connector_manifest(plugin) if plugin in _connector_roots() else None
    env_lines = originals[env_path].decode("utf-8").splitlines(keepends=True) if originals[env_path] else []
    previous_skills = list(owned["skills"]) if owned else []

    if enabled:
        if manifest is None:
            raise ValueError("쓸 수 없는 connector 는 설치하지 않는다")
        # 목록이 없는 profile 에 이름 하나만 든 목록을 만들면 내장 도구와 Control Plane MCP 가 모두 닫히고,
        # MCP 이름이 하나도 없던 목록에 이름을 더하면 운영자의 다른 MCP 서버가 막힌다.
        if not isinstance(allowed, list) or CONTROL_PLANE_MCP not in allowed:
            raise FileExistsError("API 도구 목록에 Control Plane MCP 가 없는 profile 이다")
        # 이 설치는 plugin 파일만 맞추고 profile 의 plugin 설정은 쓰지 않는다. 운영자가 끈 hook 을 대신 켜지 않는다.
        # 이미 붙은 커넥터를 다시 설치하는 것은 받는다. 연결 확인이 다시 설치하고, hook 상태가 거짓이라 반영 완료가 `READY` 로 두지 않는다.
        if not owned and not _policy_plugin_enabled(saved):
            raise FileExistsError("정책 hook plugin 이 켜져 있지 않은 profile 이다")
        name = manifest["mcp_server"]
        if name in servers and (not owned or servers[name] != owned["server"]):
            raise FileExistsError("운영자가 등록하거나 바꾼 MCP 서버가 있다")
        if owned and name not in servers:
            raise FileExistsError("설치한 MCP 서버가 밖에서 지워졌다")
        field_env = [field["env"] for field in manifest["fields"]]
        others = set()
        for other, entry in state.items():
            if other != plugin:
                others |= _entry_field_env(other, entry)
        present = {_env_line_key(line) for line in env_lines}
        for env_name in field_env:
            if env_name in BASE_ENV_KEYS or env_name in others or (env_name in present and not owned):
                raise FileExistsError("다른 설정이 쓰는 환경 변수와 겹친다")
        server = _connector_server(manifest)
        # Hermes 는 빈 변수의 참조를 그대로 남긴다. 값이 없는 선택 칸은 빈 값을 명시한다.
        for field in manifest["fields"]:
            if field["env"] in manifest["optional_env"] and not values.get(field["key"]):
                server["env"][field["env"]] = ""
        if manifest["owner_attachments_env"] is not None:
            # 경로는 요청 처리가 운영 정책과 `sandbox_owner` 로 만든 값만 받는다. 없으면 설치하지 않는다.
            if not isinstance(owner_attachments, str) or not OWNER_ATTACHMENTS_VALUE_RE.match(owner_attachments):
                raise ValueError("주인의 첨부 디렉터리 없이 사용자 첨부를 읽는 connector 를 설치하지 않는다")
            server["env"][manifest["owner_attachments_env"]] = owner_attachments
        servers[name] = server
        allowed = [item for item in allowed if item != "no_mcp"]
        if name not in allowed:
            allowed.append(name)
        # 있던 줄은 그 자리에서 바꾸고 새 줄은 뒤에 붙인다. 같은 값으로 다시 붙이면 파일이 그대로다.
        wanted = {field["env"]: _env_line(field["env"], values[field["key"]]) for field in manifest["fields"]
                  if values.get(field["key"])}
        kept = []
        for line in env_lines:
            key = _env_line_key(line)
            if key not in field_env:
                kept.append(line)
            elif key in wanted:
                kept.append(wanted.pop(key))
        if kept and not kept[-1].endswith("\n"):
            kept[-1] += "\n"
        kept.extend(wanted.values())
        new_env = "".join(kept).encode("utf-8") if kept or originals[env_path] is not None else None
        skills = sorted(manifest["skills"])
        for skill in skills:
            if (skills_dir / skill).exists() and skill not in previous_skills:
                raise FileExistsError("이미 있는 스킬 디렉터리와 이름이 겹친다")
        state[plugin] = {"server": server, "allowlist_added": True, "mcp_server": name,
                         "mode": BIND_MODE, "vault": vault, "skills": skills}
        # 뗀 기록은 같은 이름일 때만 지운다. 그 사이 서버 이름이 바뀌었으면 옛 이름을 쥔 실행이 아직 있을 수 있다.
        if detached.get(plugin) == name:
            detached.pop(plugin)
        desired = {skills_dir / skill / relative: data
                   for skill in skills for relative, data in manifest["skills"][skill].items()}
    else:
        if not owned:
            return {"changed": False, "restart_required": False, "plugin_updated": False}
        name = owned["mcp_server"]
        # 기록과 다른 정의는 이 설치가 쓴 것이 아니다. 지우지 않고 멈춘다. 밖에서 이미 지워졌으면 지울 것이 없다.
        if name in servers and servers[name] != owned["server"]:
            raise FileExistsError("운영자가 바꾼 MCP 서버가 있다")
        servers.pop(name, None)
        if isinstance(allowed, list):
            allowed = [item for item in allowed if item != name]
        field_env = _bind_entry_env(manifest, owned)
        kept = [line for line in env_lines if _env_line_key(line) not in field_env]
        new_env = "".join(kept).encode("utf-8") if originals[env_path] is not None else None
        state.pop(plugin)
        detached[plugin] = name
        skills = []
        desired = {}

    # 이 커넥터가 전에 설치한 스킬의 파일 가운데 이번에 쓰지 않는 것은 지운다.
    stale = []
    for skill in previous_skills:
        found = _skill_tree_files(skills_dir / skill) or {}
        stale.extend(path for path in found.values() if path not in desired)
    for path in desired:
        for parent in path.relative_to(skills_dir).parents:
            if (skills_dir / parent).is_symlink():
                raise ValueError("스킬 경로에 심볼릭 링크가 있다")
        if path.is_symlink():
            raise ValueError("스킬 경로에 심볼릭 링크가 있다")
    for path in (*desired, *stale):
        originals[path] = path.read_bytes() if path.exists() else None

    if isinstance(allowed, list):
        platform["api_server"] = allowed
    updated = {**saved, "mcp_servers": servers, "platform_toolsets": platform}
    targets = {config_path: yaml.safe_dump(updated, sort_keys=False, allow_unicode=True).encode(),
               state_path: (json.dumps(state) + "\n").encode(), env_path: new_env,
               detached_path: _detached_bytes(detached),
               # 마지막 바인딩을 떼도 대응 파일은 뗀 서버를 싣고 남는다. 떼기 전에 시작한 실행이 그 서버를 쥐고 있다.
               tool_map_path: _tool_map_bytes(_connector_tool_map(state, detached)) if state or detached else None,
               **desired, **{path: None for path in stale}}
    # plugin 파일은 맨 뒤에 쓴다. 앞의 쓰기가 실패하면 plugin 은 손대지 않은 채 남는다.
    targets.update(plugin_values)
    plugin_updated = any(originals[path] != value for path, value in plugin_values.items())
    if all(originals[path] == value for path, value in targets.items()):
        return {"changed": False, "restart_required": False, "plugin_updated": False}
    backup = profile_dir / "connector-backups" / str(time.time_ns())
    backup.mkdir(parents=True, mode=0o700)
    os.chmod(backup.parent, 0o700)
    for path in (config_path, state_path, tool_map_path, detached_path):
        # `.env` 와 스킬 파일은 뜨지 않는다. `.env` 에는 사용자의 비밀 원문이 있다.
        if originals[path] is not None:
            _atomic_private_write(backup / path.name, originals[path])
    for path, value in plugin_values.items():
        if originals[path] is not None and originals[path] != value:
            _atomic_private_write(backup / ("%s.%s" % (POLICY_PLUGIN, path.name)), originals[path])

    def shared(path):
        # 스킬과 plugin 파일은 gateway 가 읽는 파일이다. 새 profile 에 plugin 을 복사할 때와 같은 644 로 둔다.
        return path in plugin_values or skills_dir in path.parents

    written = []
    created_dirs = []
    try:
        if any((path.read_bytes() if path.exists() else None) != value for path, value in originals.items()):
            raise FileExistsError("저장 전 profile 설정이 밖에서 바뀌었다")
        for path, value in targets.items():
            current = path.read_bytes() if path.exists() else None
            if current != originals[path]:
                raise FileExistsError("profile 설정이 밖에서 바뀌었다")
            if current == value:
                continue
            if value is None:
                path.unlink()
            else:
                for directory in reversed(path.parents):
                    if directory in profile_dir.parents or directory == profile_dir or directory.is_dir():
                        continue
                    directory.mkdir()
                    created_dirs.append(directory)
                    os.chmod(directory, 0o755)
                _atomic_private_write(path, value)
                if shared(path):
                    os.chmod(path, 0o644)
            written.append(path)
    except Exception:
        for path in reversed(written):
            # 이 요청이 쓴 값일 때만 복원한다. 바깥의 새 수정은 덮어쓰지 않는다.
            if (path.read_bytes() if path.exists() else None) != targets[path]:
                continue
            value = originals[path]
            if value is None:
                path.unlink(missing_ok=True)
            else:
                _atomic_private_write(path, value)
                if shared(path):
                    os.chmod(path, 0o644)
        for directory in reversed(created_dirs):
            # 이 요청이 만든 디렉터리만 지운다. 그 사이 밖에서 무엇이 생겼으면 비어 있지 않아 남는다.
            try:
                directory.rmdir()
            except OSError:
                pass
        raise
    # 파일을 모두 지운 스킬 디렉터리는 빈 디렉터리만 남는다. 지우지 못해도 설치는 끝난 것이다.
    for skill in previous_skills:
        for current, dirs, names in os.walk(skills_dir / skill, topdown=False):
            try:
                os.rmdir(current)
            except OSError:
                pass
    return {"changed": True, "restart_required": enabled, "plugin_updated": plugin_updated}


def _bind_skills_installed(profile_dir: pathlib.Path, manifest: dict, entry: dict) -> bool:
    """소유 기록의 스킬이 plugin 의 스킬과 같고, 설치한 파일이 plugin 의 본문과 같은지 본다. 읽다가 예외가 나면 거짓이다."""
    try:
        if sorted(entry["skills"]) != sorted(manifest["skills"]):
            return False
        skills_dir = profile_dir / PROFILE_SKILLS_DIR
        if skills_dir.is_symlink():
            return False
        for skill, files in manifest["skills"].items():
            found = _skill_tree_files(skills_dir / skill)
            if found is None or {key: path.read_bytes() for key, path in found.items()} != files:
                return False
        return True
    except Exception:
        return False


def _policy_plugin_enabled(config: dict) -> bool:
    """profile 설정이 정책 hook plugin 을 켜고 도구 덮어쓰기를 막는지 본다. 읽다가 예외가 나면 거짓이다.

    `plugins.enabled` 에 있고 `plugins.disabled` 에 없으며 `plugins.entries.fos-ctx.allow_tool_override` 가 `false` 여야 한다.
    """
    try:
        plugins = config["plugins"]
        disabled = plugins.get("disabled") or []
        return (isinstance(plugins["enabled"], list) and POLICY_PLUGIN in plugins["enabled"]
                and isinstance(disabled, list) and POLICY_PLUGIN not in disabled
                and plugins["entries"][POLICY_PLUGIN]["allow_tool_override"] is False)
    except Exception:
        return False


def _policy_hook_active(profile_dir: pathlib.Path, config: dict, state: dict) -> bool:
    """그 profile 에서 커넥터 도구 호출이 정책 hook 을 거치는지 본다. 읽다가 예외가 나면 거짓이다.

    조건은 `docs/backend/connector-tool-policy.md` 의 「hook 이 켜져 있는지」 가 갖는다. 확인한 시점의 파일만 본다.
    """
    try:
        if not _policy_plugin_enabled(config):
            return False
        bundled = _profile_plugin_files(POLICY_PLUGIN)
        if bundled is None:
            return False
        for file_name, value in bundled.items():
            installed = profile_dir / "plugins" / POLICY_PLUGIN / file_name
            if installed.is_symlink() or installed.read_bytes() != value:
                return False
        detached_path = profile_dir / CONNECTOR_DETACHED
        if detached_path.is_symlink():
            return False
        detached = _detached_servers(detached_path.read_bytes() if detached_path.exists() else None)
        if (profile_dir / CONNECTOR_TOOL_MAP).read_bytes() != _tool_map_bytes(_connector_tool_map(state, detached)):
            return False
        roots = _connector_roots()
        servers = config.get("mcp_servers") or {}
        for plugin, entry in state.items():
            manifest = _connector_manifest(plugin) if plugin in roots else None
            # 늘 승인이 필요한 도구가 모델에 등록된 채이면 hook 이 켜져 있어도 설치가 덜 된 것이다.
            # manifest 와 맞지 않는 바인딩 항목은 대응에 빈 `tools` 로 실려 모든 호출을 묻는다. 그 항목만 쓸 수 없다.
            if manifest is None or (_entry_mode(entry) == BIND_MODE and not _entry_matches_manifest(plugin, entry)):
                continue
            if servers[manifest["mcp_server"]].get("tools") != manifest["server"].get("tools"):
                return False
        return True
    except Exception:
        return False


def _owned_mode(profile_dir: pathlib.Path, plugin: str) -> str | None:
    """소유 기록에서 그 커넥터 항목의 설치 방식만 읽는다. 항목이 없거나 기록을 읽지 못하면 None 이다.

    표식 판정과 처리 경로를 고르는 데만 쓴다. 기록 전체의 검증은 설치와 떼기가 한다.
    """
    try:
        state = json.loads((profile_dir / CONNECTOR_STATE).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(state, dict) or not isinstance(state.get(plugin), dict):
        return None
    return _entry_mode(state[plugin])


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
            stored = await asyncio.to_thread(_read_vault, body["bind"]["vault"])
            if stored is None or stored["connector"] != body["plugin"]:
                return _rejected("그 connector 의 보관 파일이 없다")
            values = _vault_values(manifest, stored["values"])
            if values is None:
                return _rejected("보관 파일의 값이 지금 칸 선언과 맞지 않는다")
            result = await asyncio.to_thread(_connector_bind_config, profile_dir, body["plugin"], True,
                                             body["bind"]["vault"], values, owner_attachments)
            return JSONResponse({**response, **result}, status_code=200)
        if unbind:
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
                    prepared = _sandbox_verify_attachment_directories(sandbox, owner)
                    sandbox_terminal = _sandbox_terminal(sandbox, profile, owner, prepared)
                    attachment_guard = (sandbox, owner, prepared)
                except OSError:
                    return _sandbox_unavailable()
            else:
                return _sandbox_unavailable()
        result = await asyncio.to_thread(
            _connector_config, profile_dir, body["plugin"], body["enabled"], sandbox_terminal, local_execution,
            attachment_guard)
        return JSONResponse({**response, **result}, status_code=200)
    except SandboxAttachmentError:
        return _sandbox_unavailable()
    except FileExistsError:
        return _rejected("운영자 설정과 충돌한다", 409)
    except Exception:
        # manifest 내용이나 profile 환경 변수를 응답과 로그에 싣지 않는다.
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
