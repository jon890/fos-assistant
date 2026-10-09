"""profile 의 커넥터 스킬과 정책 hook 이 설치한 판인지 확인한다."""

from __future__ import annotations

import json
import pathlib

from .connector_state import (
    CONNECTOR_DETACHED,
    CONNECTOR_TOOL_MAP,
    POLICY_PLUGIN,
    PROFILE_SKILLS_DIR,
    _connector_tool_map,
    _detached_servers,
    _skill_tree_files,
    _tool_map_bytes,
)

from .profiles import (
    _profile_plugin_files,
)

# `_policy_hook_failure` 가 이름 대응의 차이를 받지 않았음을 나타낸다. 받은 값 `None` 은 모양이 다르다는 뜻이라 구분한다.
_UNSET = object()


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


def _tool_map_shape(tool_map: dict) -> bytes:
    """이름 대응에서 서버마다의 `tools` 값을 지운 직렬화다. 칸 이름은 남긴다."""
    servers = {name: {**server, "tools": None} for name, server in tool_map["servers"].items()}
    return _tool_map_bytes({**tool_map, "servers": servers})


def _tool_map_drift(profile_dir: pathlib.Path, state: dict) -> set[str] | None:
    """이름 대응 파일의 모양이 계산한 것과 같으면 `tools` 만 다른 서버 이름의 집합을 돌려준다.

    모양은 `v`, `isolated`, 서버 이름 목록, 서버마다의 `connector` 와 `prefix` 다. 하나라도 다르거나,
    대응 파일이나 뗀 서버 기록이 링크이거나, 읽다가 예외가 나면 `None` 이다.
    서버별 `tools` 가 낡아도 hook 은 접두사로 서버를 잡아 묻는다. 서버가 빠지거나 접두사가 다르면 판정 없이 나가므로
    모양만 profile 단위로 본다(ADR-20261009 connector-install-drift).
    """
    try:
        detached_path = profile_dir / CONNECTOR_DETACHED
        map_path = profile_dir / CONNECTOR_TOOL_MAP
        if detached_path.is_symlink() or map_path.is_symlink():
            return None
        detached = _detached_servers(detached_path.read_bytes() if detached_path.exists() else None)
        expected = _connector_tool_map(state, detached)
        found = json.loads(map_path.read_bytes())
        servers = found["servers"]
        # hook 은 서버 하나의 `tools` 모양이 틀려도 파일 전체를 읽지 못한다. 그런 파일은 서버별 차이로 다루지 않는다.
        if not isinstance(servers, dict) or not all(
                isinstance(server, dict) and isinstance(server.get("tools"), dict)
                and all(isinstance(key, str) and isinstance(value, str) for key, value in server["tools"].items())
                for server in servers.values()):
            return None
        # 모양은 `tools` 를 뺀 나머지다. 직렬화해 견주므로 칸이 빠지거나 더해지거나 `true` 와 `1` 처럼 형이 달라도 다르다.
        # 바인딩 profile 만 `isolated` 를 실으므로 없는 칸을 참으로 읽는 규칙도 양쪽에서 같다.
        if _tool_map_shape(found) != _tool_map_shape(expected):
            return None
        return {name for name, server in servers.items()
                if _tool_map_bytes(server["tools"]) != _tool_map_bytes(expected["servers"][name]["tools"])}
    except Exception:
        return None


def _policy_hook_failure(profile_dir: pathlib.Path, config: dict, state: dict, drift=_UNSET) -> str | None:
    """그 profile 에서 커넥터 도구 호출이 정책 hook 을 거치지 않는 첫 조건의 이름이다. 모두 통과하면 `None` 이다.

    조건은 `docs/backend/connector-tool-policy.md` 의 「hook 이 켜져 있는지」 가 갖는다. 확인한 시점의 파일만 본다.
    `drift` 는 같은 요청에서 이미 계산한 `_tool_map_drift` 의 값이다. 주지 않으면 여기서 계산한다.

    - `plugin_config`: 설정이 `fos-ctx` 를 켜지 않았거나 도구 덮어쓰기를 막지 않는다
    - `plugin_bundle`: 대시보드 묶음의 `fos-ctx` 를 읽지 못한다
    - `plugin_files`: 설치한 `fos-ctx` 파일이 묶음과 다르거나 링크다
    - `detached`: 뗀 서버 기록이 링크다
    - `tool_map`: 이름 대응 파일의 모양이 계산한 것과 다르거나 읽지 못한다. 서버마다의 `tools` 는 보지 않는다
    - `unreadable`: 읽다가 예외가 났다
    """
    try:
        if not _policy_plugin_enabled(config):
            return "plugin_config"
        bundled = _profile_plugin_files(POLICY_PLUGIN)
        if bundled is None:
            return "plugin_bundle"
        for file_name, value in bundled.items():
            installed = profile_dir / "plugins" / POLICY_PLUGIN / file_name
            if installed.is_symlink() or installed.read_bytes() != value:
                return "plugin_files"
        if (profile_dir / CONNECTOR_DETACHED).is_symlink():
            return "detached"
        if (_tool_map_drift(profile_dir, state) if drift is _UNSET else drift) is None:
            return "tool_map"
        return None
    except Exception:
        return "unreadable"


def _policy_hook_active(profile_dir: pathlib.Path, config: dict, state: dict) -> bool:
    """그 profile 에서 커넥터 도구 호출이 정책 hook 을 거치는지 본다. 조건은 `_policy_hook_failure` 가 갖는다."""
    return _policy_hook_failure(profile_dir, config, state) is None
