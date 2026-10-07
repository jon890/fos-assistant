"""일반 에이전트의 profile 에 커넥터를 붙이거나 뗀다."""

from __future__ import annotations

import json
import os
import pathlib
import time

from .common import (
    BASE_ENV_KEYS,
    CONTROL_PLANE_MCP,
    _atomic_private_write,
    _env_line,
    _env_line_key,
)

from .connector_manifest import (
    _connector_manifest,
    _connector_roots,
    _entry_mode,
)

from .connector_schema import (
    BIND_MODE,
    CONNECTOR_STATE,
    OWNER_ATTACHMENTS_VALUE_RE,
)

from .connector_state import (
    CONNECTOR_DETACHED,
    CONNECTOR_TOOL_MAP,
    POLICY_PLUGIN,
    PROFILE_SKILLS_DIR,
    _bind_entry_env,
    _bind_entry_shape,
    _connector_server,
    _connector_state,
    _connector_tool_map,
    _detached_bytes,
    _detached_servers,
    _entry_field_env,
    _remove_backup_env_copies,
    _skill_tree_files,
    _tool_map_bytes,
)

from .connector_status import (
    _policy_plugin_enabled,
)

from .profiles import (
    _profile_plugin_files,
)


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
