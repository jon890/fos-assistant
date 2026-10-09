"""커넥터 소유 기록과 도구 이름 대응, profile 파일의 공통 규칙이다."""

from __future__ import annotations

import json
import os
import pathlib

from .common import (
    BASE_ENV_KEYS,
    SKILL_NAME_RE,
    logger,
)

from .connector_manifest import (
    _connector_manifest,
    _connector_roots,
    _entry_mode,
    _server_matches,
)

from .connector_policy import (
    _hermes_tool_name,
)

from .connector_schema import (
    BIND_MODE,
    CONNECTOR_ID_RE,
    CONNECTOR_STATE,
    ISOLATED_MODE,
    SERVER_NAME_RE,
)

from .connector_vault import (
    VAULT_ID_RE,
)


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
    """소유 기록의 커넥터와 뗀 서버 기록으로 만든 이름 대응이다. 형식은 `docs/features/connector-policy.md` 의 「이름 대응」 이 갖는다.

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


def _entry_manifest_mismatches(plugin: str, entry: dict) -> tuple[str, ...]:
    """소유 기록과 현재 manifest 가 다른 공개된 정의 칸 이름만 돌려준다."""
    manifest = _connector_manifest(plugin)
    if manifest is None or not isinstance(entry.get("server"), dict):
        return ()
    recorded = entry["server"]
    expected = manifest["server"]
    mismatches = [name for name in ("command", "args") if recorded.get(name) != expected.get(name)]
    # `_server_matches` 가 선택 칸의 빈 값, 옛 운영자 env 참조, 주인별 디렉터리 값을 호환으로 인정한다.
    # command 와 args 를 현재 값으로 맞춘 뒤에도 다르면 env 정의가 실제로 다르다.
    comparable = {**recorded, "command": expected["command"], "args": expected["args"]}
    if not _server_matches(manifest, comparable):
        mismatches.append("env")
    if entry.get("mcp_server", manifest["mcp_server"]) != manifest["mcp_server"]:
        mismatches.append("mcp_server")
    return tuple(mismatches)


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
