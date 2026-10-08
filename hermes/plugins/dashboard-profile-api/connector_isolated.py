"""커넥터 전용 profile 의 옛 설치를 쓰고 실패하면 되돌린다."""

from __future__ import annotations

import json
import os
import pathlib
import time

from typing import (
    Optional,
)

from .common import (
    CONTROL_PLANE_MCP,
    MANAGED_MARKER,
    _atomic_private_write,
    _env_value,
)

from .connector_manifest import (
    _connector_manifest,
    _connector_roots,
    _entry_mode,
)

from .connector_schema import (
    BIND_MODE,
    CONNECTOR_STATE,
)

from .connector_state import (
    CONNECTOR_DETACHED,
    CONNECTOR_TOOL_MAP,
    POLICY_PLUGIN,
    SOUL_FILE,
    _connector_allowlist,
    _connector_server,
    _connector_state,
    _connector_tool_map,
    _remove_backup_env_copies,
    _tool_map_bytes,
)

from .profiles import (
    _profile_plugin_files,
)

from .sandbox import (
    _sandbox_validate_attachment_snapshot,
)
from .sandbox_approvals import _with_sandbox_approvals


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
        updated = _with_sandbox_approvals(saved, updated, True)
    elif local_execution:
        previous = saved.get("terminal") or {}
        if not isinstance(previous, dict):
            raise ValueError("terminal 설정이 객체가 아니다")
        terminal = dict(previous) if previous.get("backend", "local") == "local" else {}
        terminal["backend"] = "local"
        updated["terminal"] = terminal
        updated = _with_sandbox_approvals(saved, updated, False)
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
