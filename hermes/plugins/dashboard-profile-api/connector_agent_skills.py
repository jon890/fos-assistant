"""플러그인의 에이전트용 스킬 가운데 그 profile 에 설치할 것과 빠질 것을 정한다(ADR-20261120 plugin-agent-skills)."""

from __future__ import annotations

import pathlib

from .connector_skills import _connector_skills
from .sandbox import SANDBOX_ALLOWED_ENV

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
    # `connector_state` 는 manifest 읽기를 거쳐 이 모듈을 부르므로 함수 안에서 읽는다.
    from .connector_state import PROFILE_SKILLS_DIR
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


def _plugin_and_agent_skills(root: pathlib.Path, declared: dict, skill_dirs: list) -> tuple[dict, dict]:
    """(플러그인 스킬과 에이전트용 스킬을 합친 파일 사전, {에이전트용 스킬 이름: frozenset(요구 env)}) 다. 틀리면 예외다.

    에이전트용 스킬은 `assistant.json` 의 `agent_skills` 가 가리키는 디렉터리다. 파일 규칙은 플러그인 스킬과 같다.
    `sandbox_env` 는 스킬마다 셸에 요구하는 env 이름이고 운영 정책 `profiles[*].env` 가 받는 이름만 쓸 수 있다.
    """
    plugin_skills = _connector_skills(skill_dirs)
    value = declared.get("agent_skills")
    if value is None:
        return plugin_skills, {}
    if not isinstance(value, dict) or "path" not in value or set(value) - {"path", "sandbox_env"}:
        raise ValueError("agent_skills 는 path 와 sandbox_env 만 받는 객체다")
    if not isinstance(value["path"], str):
        raise ValueError("agent_skills 의 path 는 문자열이다")
    path = root / value["path"]
    if not path.resolve().is_relative_to(root) or not path.is_dir() or path.resolve() != path.absolute():
        raise ValueError("agent_skills 는 plugin 안의 링크 없는 디렉터리여야 한다")
    files = _connector_skills([path.resolve()])
    if set(files) & set(plugin_skills):
        raise ValueError("스킬 이름이 겹친다")
    sandbox_env = value.get("sandbox_env", {})
    if not isinstance(sandbox_env, dict):
        raise ValueError("agent_skills 의 sandbox_env 는 객체다")
    required = {}
    for name, names in sandbox_env.items():
        if name not in files:
            raise ValueError("agent_skills 의 sandbox_env 가 없는 스킬을 가리킨다")
        if (not isinstance(names, list) or not names or len(set(map(str, names))) != len(names)
                or any(not isinstance(env, str) or env not in SANDBOX_ALLOWED_ENV for env in names)):
            raise ValueError("agent_skills 의 sandbox_env 는 실행 공간 정책이 받는 env 이름의 목록이다")
        required[name] = frozenset(names)
    return {**plugin_skills, **files}, {name: required.get(name, frozenset()) for name in files}


def _catalog_agent_skills(manifest: dict) -> dict:
    """카탈로그 항목에 더할 `agent_skills` 칸이다. 에이전트용 스킬이 없으면 빈 사전이라 칸이 생기지 않는다(rule-66)."""
    agent_skills = manifest.get("agent_skills") or {}
    if not agent_skills:
        return {}
    return {"agent_skills": [{"name": name, "sandbox_env": sorted(env)} for name, env in sorted(agent_skills.items())]}


def _bind_skill_names(profile_dir: pathlib.Path, config: dict, manifest: dict, previous_skills: list,
                      first: bool) -> tuple[list, dict]:
    """바인딩 설치가 쓸 스킬 이름(이름 순)과 붙이기 응답에 더할 `skipped_skills` 칸이다. 겹쳐서 설치할 수 없으면 FileExistsError.

    플러그인 스킬은 profile `skills/` 에 소유하지 않은 같은 이름이 있으면 거절한다. 에이전트용 스킬은 처음 붙일 때만
    겹침으로 거절하고, 붙인 뒤 생긴 겹침은 그 스킬만 빼고 바인딩은 그대로 둔다.
    """
    from .connector_state import PROFILE_SKILLS_DIR
    agent_skills = manifest.get("agent_skills") or {}
    install, skipped = _agent_skill_plan(profile_dir, config, manifest, previous_skills)
    if first and any(NAME_TAKEN in reasons for reasons in skipped.values()):
        raise FileExistsError("이미 있는 스킬 디렉터리와 이름이 겹친다")
    plugin_skills = [skill for skill in manifest["skills"] if skill not in agent_skills]
    for skill in plugin_skills:
        if (profile_dir / PROFILE_SKILLS_DIR / skill).exists() and skill not in previous_skills:
            raise FileExistsError("이미 있는 스킬 디렉터리와 이름이 겹친다")
    return (sorted(set(plugin_skills) | install),
            {"skipped_skills": [{"name": name, "reasons": reasons} for name, reasons in sorted(skipped.items())]})
