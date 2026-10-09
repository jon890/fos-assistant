"""profile 의 커넥터 스킬과 정책 hook 이 설치한 판인지 확인한다."""

from __future__ import annotations

import pathlib

from .connector_manifest import (
    _connector_manifest,
    _connector_roots,
    _entry_mode,
)

from .connector_schema import (
    BIND_MODE,
)

from .connector_state import (
    CONNECTOR_DETACHED,
    CONNECTOR_TOOL_MAP,
    POLICY_PLUGIN,
    PROFILE_SKILLS_DIR,
    _connector_tool_map,
    _detached_servers,
    _entry_matches_manifest,
    _skill_tree_files,
    _tool_map_bytes,
)

from .profiles import (
    _profile_plugin_files,
)


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

    조건은 `docs/features/connector-policy.md` 의 「hook 이 켜져 있는지」 가 갖는다. 확인한 시점의 파일만 본다.
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
