"""기동 때 이미 붙은 로컬 바인딩을 실행기 정의로 다시 설치하고 profile `.env` 사본을 지운다(스펙 REQ7, DECISION10).

순서와 까닭은 `docs/features/connector/design.md` 의 「기동 이관(REQ7, DECISION10)」 이 갖는다.
`register` 가 보관 파일 이관 뒤, 경로를 감싸기 전에 부른다. 그래서 요청과 겹치지 않고 profile 쓰기 잠금이 필요 없다.
보관 파일을 풀지 못하거나 설치가 거절한 바인딩은 옛 정의와 사본을 그대로 둔다. 값과 profile 이름, 경로는 로그에 싣지 않는다.
"""

from __future__ import annotations

import json
import time

from .common import (
    CONNECTOR_HOST_MARKER,
    MANAGED_MARKER,
    _atomic_private_write,
    _env_line_key,
    logger,
)

from .connector_launch import _is_launcher, _launcher_current, _launcher_inner, _launcher_server, _vault_revision
from .connector_manifest import _connector_manifest, _connector_roots, _server_matches
from .connector_remote import _is_remote
from .connector_schema import CONNECTOR_STATE
from .connector_state import (CONNECTOR_DETACHED, CONNECTOR_TOOL_MAP, _bind_entry_env, _connector_state,
                              _connector_tool_map, _detached_servers, _restore_binding_files, _tool_map_bytes)
from .connector_vault import _read_vault
from .connector_vault_migration import _migrate_vault_files


# 기동 로그에 남기는 수의 이름이다. 운영은 배포 뒤 `left` 가 0 인지 본다(REQ7).
# `moved` 는 이번 기동에 실행기로 옮긴 바인딩, `kept` 는 이미 실행기로 돌던 바인딩(남은 사본만 지운 것 포함), `remote` 는 예외로 `.env` 를 쓰는 원격 바인딩이다.
# `unreadable` 은 소유 기록을 읽지 못해 들여다보지 못한 profile 수다.
BINDING_MIGRATION_COUNTS = ("moved", "kept", "left", "remote", "unreadable")


def _migrate_connectors() -> None:
    """보관 파일 이관 뒤 바인딩 이관을 돌린다. 하나가 멈춰도 다른 것은 돈다. 예외를 밖으로 내지 않는다."""
    try:
        _migrate_vault_files()
    except Exception as error:
        logger.warning("dashboard-profile-api: 보관 파일 이관이 멈췄다: %s", type(error).__name__)
    try:
        _migrate_bindings()
    except Exception as error:
        logger.warning("dashboard-profile-api: 바인딩 이관이 멈췄다: %s", type(error).__name__)


def _migrate_bindings() -> dict:
    """모든 profile 의 소유 기록을 보고 옛 정의의 로컬 바인딩을 실행기 정의로 다시 설치한다. 수를 담은 dict 를 돌려준다."""
    from hermes_cli.profiles import get_profile_dir, list_profile_names

    counts = dict.fromkeys(BINDING_MIGRATION_COUNTS, 0)
    left = {}
    for name in sorted(list_profile_names()):
        profile_dir = get_profile_dir(name)
        state_path = profile_dir / CONNECTOR_STATE
        if (not ((profile_dir / MANAGED_MARKER).is_file() or (profile_dir / CONNECTOR_HOST_MARKER).is_file())
                or state_path.is_symlink() or not state_path.is_file()):
            continue
        try:
            state = _connector_state(json.loads(state_path.read_text(encoding="utf-8")))
        except Exception as error:
            logger.warning("dashboard-profile-api: 바인딩 이관이 소유 기록을 읽지 못했다: %s", type(error).__name__)
            counts["unreadable"] += 1
            continue
        for plugin, entry in sorted(state.items()):
            try:
                outcome = _migrate_binding(profile_dir, plugin, entry)
            except Exception as error:
                logger.warning("dashboard-profile-api: 바인딩 이관 실패 id=%s exception=%s", plugin, type(error).__name__)
                outcome = "left"
            counts[outcome] += 1
            if outcome == "left":
                left[plugin] = left.get(plugin, 0) + 1
    logger.info("dashboard-profile-api: 바인딩 이관: 실행기로 옮김 %d, 그대로 둔 실행기 바인딩 %d, 남은 사본 %d, 원격 %d, "
                "읽지 못한 소유 기록 %d", counts["moved"], counts["kept"], counts["left"], counts["remote"],
                counts["unreadable"])
    if left:
        logger.warning("dashboard-profile-api: 실행기로 옮기지 못하고 남은 바인딩: %s",
                       ", ".join("%s %d" % item for item in sorted(left.items())))
    if counts["moved"]:
        logger.warning("dashboard-profile-api: 바인딩 %d 개를 실행기 정의로 옮겼다. gateway 재시작이 필요하다", counts["moved"])
    return counts


def _migrate_binding(profile_dir, plugin: str, entry: dict) -> str:
    """바인딩 하나를 분류하고 필요하면 서버 정의만 실행기 정의로 바꾸고 `.env` 사본을 지운다.

    결과는 `BINDING_MIGRATION_COUNTS` 의 이름 하나다. 바인딩 설치(`_connector_bind_config`)를 다시 돌리지 않는다.
    스킬, hook plugin 파일, 도구 목록의 검사가 거절해 풀 수 있는 바인딩의 사본이 남는 일이 없게 하려는 것이다.
    바꾸는 파일은 `config.yaml` 의 그 서버, 소유 기록의 그 항목, 이름 대응, `.env` 의 그 칸 줄뿐이다.
    """
    import yaml

    server = entry["server"]
    if _is_remote(server):
        return "remote"
    config_path, state_path = profile_dir / "config.yaml", profile_dir / CONNECTOR_STATE
    env_path, tool_map_path = profile_dir / ".env", profile_dir / CONNECTOR_TOOL_MAP
    detached_path = profile_dir / CONNECTOR_DETACHED
    if any(path.is_symlink() for path in (config_path, state_path, env_path, tool_map_path, detached_path)):
        return "left"
    originals = {path: path.read_bytes() if path.exists() else None
                 for path in (config_path, state_path, env_path, tool_map_path)}
    copies = _bind_entry_env(None, entry)
    env_lines = originals[env_path].decode("utf-8").splitlines(keepends=True) if originals[env_path] else []
    kept_lines = [line for line in env_lines if _env_line_key(line) not in copies]
    manifest = _connector_manifest(plugin) if plugin in _connector_roots() else None
    if (_is_launcher(server) and len(kept_lines) == len(env_lines)
            and (manifest is None or _launcher_current(manifest, server))):
        return "kept"
    legacy = _launcher_inner(server)
    if manifest is None or manifest["transport"] != "stdio" or not _server_matches(manifest, legacy):
        # 지금 manifest 와 다른 기록은 실행기 정의로 펼 칸 대응을 알 수 없다. 다시 연결하면 새 정의로 설치된다.
        return "left"
    config = yaml.safe_load(originals[config_path]) or {}
    state = json.loads(originals[state_path])
    servers = config.get("mcp_servers") if isinstance(config, dict) else None
    if not isinstance(servers, dict) or servers.get(entry["mcp_server"]) != server or state.get(plugin) != entry:
        # 운영자가 바꾼 정의는 그 정의가 `.env` 를 참조할 수 있어 손대지 않는다.
        return "left"
    try:
        stored = _read_vault(entry["vault"])
    except ValueError:
        # 풀지 못한 바인딩은 옛 정의와 사본으로 계속 돈다(스펙 DECISION10).
        return "left"
    if stored is None or stored["connector"] != plugin:
        return "left"
    target = (server if _is_launcher(server) and _launcher_current(manifest, server)
              else _launcher_server(manifest, legacy, entry["vault"], _vault_revision(entry["vault"])))
    servers[entry["mcp_server"]] = target
    state[plugin] = {**entry, "server": target}
    tool_map = _tool_map_bytes(_connector_tool_map(state, _detached_servers(
        detached_path.read_bytes() if detached_path.exists() else None)))
    targets = {config_path: yaml.safe_dump(config, sort_keys=False, allow_unicode=True).encode(),
               state_path: (json.dumps(state) + "\n").encode(), tool_map_path: tool_map,
               env_path: "".join(kept_lines).encode("utf-8") if originals[env_path] is not None else None}
    backup = profile_dir / "connector-backups" / str(time.time_ns())
    backup.mkdir(parents=True, mode=0o700)
    for path in (config_path, state_path, tool_map_path):
        # `.env` 는 뜨지 않는다. 사본의 원문이 있다.
        if originals[path] is not None:
            _atomic_private_write(backup / path.name, originals[path])
    written = []
    try:
        # `.env` 를 맨 뒤에 쓴다. 앞에서 멈추면 사본이 남아 다음 기동이 다시 지운다.
        for path in (config_path, state_path, tool_map_path, env_path):
            if targets[path] == originals[path]:
                continue
            written.append(path)
            if targets[path] is None:
                path.unlink()
            else:
                _atomic_private_write(path, targets[path])
    except Exception:
        _restore_binding_files(written, originals, targets, lambda path: False)
        raise
    # 정의가 그대로이고 사본만 지웠으면 gateway 가 쥔 정의도 그대로라 재시작이 필요 없다.
    return "kept" if target is server else "moved"
