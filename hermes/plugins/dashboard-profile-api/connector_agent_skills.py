"""플러그인의 에이전트용 스킬 가운데 그 profile 에 설치할 것과 빠질 것을 정한다(ADR-20261120 plugin-agent-skills)."""

from __future__ import annotations

import pathlib

from .connector_state import PROFILE_SKILLS_DIR

# 빠진 까닭의 이름과 응답에 넣는 순서다. Control Plane 과 화면이 이 이름으로 안내를 고른다.
TERMINAL_OFF = "terminal_off"
SANDBOX_UNREGISTERED = "sandbox_unregistered"
SANDBOX_ENV_MISSING = "sandbox_env_missing"
NAME_TAKEN = "name_taken"


def _external_skill_dirs(config: dict) -> list:
    """`skills.external_dirs` 를 경로 목록으로 읽는다. 문자열 하나는 그 하나, 다른 모양은 빈 목록이다."""
    skills = config.get("skills") if isinstance(config, dict) else None
    raw = skills.get("external_dirs") if isinstance(skills, dict) else None
    if isinstance(raw, str):
        raw = [raw]
    if not isinstance(raw, list):
        return []
    return [pathlib.Path(item) for item in raw if isinstance(item, str) and item]


def _shell_reasons(config: dict, required: frozenset) -> list:
    """요구 env 가 있는 스킬이 그 셸 설정에서 돌 수 없는 까닭이다. 판정은 셸이 실제로 받는 설정만 본다(운영 정책은 읽지 않는다)."""
    reasons = []
    platform = config.get("platform_toolsets") if isinstance(config, dict) else None
    allowed = platform.get("api_server") if isinstance(platform, dict) else None
    if not isinstance(allowed, list) or "terminal" not in allowed:
        reasons.append(TERMINAL_OFF)
    terminal = config.get("terminal") if isinstance(config, dict) else None
    if not isinstance(terminal, dict) or terminal.get("backend") != "docker":
        reasons.append(SANDBOX_UNREGISTERED)
    docker_env = terminal.get("docker_env") if isinstance(terminal, dict) else None
    if not isinstance(docker_env, dict) or not required <= set(docker_env):
        reasons.append(SANDBOX_ENV_MISSING)
    return reasons


def _name_taken(profile_dir: pathlib.Path, config: dict, name: str, owned_skills) -> bool:
    """이 플러그인이 설치하지 않은 같은 이름의 스킬이 있는가. 읽지 못하는 자리는 없는 것으로 본다.

    profile `skills/` 의 같은 이름은 소유 기록에 있으면 이 플러그인이 설치한 것이다.
    `external_dirs` 에는 플러그인이 설치하지 않으므로 같은 이름이 있으면 늘 다른 스킬이다(올린 스킬).
    """
    try:
        if name not in owned_skills and (profile_dir / PROFILE_SKILLS_DIR / name).exists():
            return True
    except OSError:
        pass
    for directory in _external_skill_dirs(config):
        try:
            if (directory / name).is_dir():
                return True
        except OSError:
            continue
    return False


def _agent_skill_plan(profile_dir: pathlib.Path, config: dict, manifest: dict, owned_skills) -> tuple[set, dict]:
    """(설치할 에이전트용 스킬 이름, {빠진 이름: [까닭]}) 다. 예외를 내지 않는다.

    바인딩 설치와 설치 상태 판정이 같은 함수를 불러, 조건이 바뀌면 상태 판정이 어긋남으로 보고 정의 어긋남 점검이 다시 설치한다.
    까닭은 터미널, 실행 공간, env, 이름 겹침 차례로 해당하는 것을 모두 낸다. 셸 조건은 요구 env 가 있는 스킬만 본다.
    플러그인 스킬은 보지 않는다. 그 겹침은 바인딩 설치가 지금처럼 거절한다.
    """
    owned = set(owned_skills or ())
    install = set()
    skipped = {}
    for name, required in (manifest.get("agent_skills") or {}).items():
        reasons = _shell_reasons(config, required) if required else []
        if _name_taken(profile_dir, config, name, owned):
            reasons.append(NAME_TAKEN)
        if reasons:
            skipped[name] = reasons
        else:
            install.add(name)
    return install, skipped
