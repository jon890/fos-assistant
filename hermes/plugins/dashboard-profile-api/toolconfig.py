from __future__ import annotations

import json
import os
import pathlib
import re
from typing import Optional
from .common import (
    CONTROL_PLANE_MCP,
    _atomic_private_write,
    _json_object,
    _missing_profile,
    _profile_rejection,
    _rejected,
    logger,
)

from .connector_manifest import (
    BIND_MODE,
    CONNECTOR_STATE,
    _entry_mode,
)

from .connector_output import (
    _sandbox_prepare_connector_output,
)

from .sandbox import (
    IMAGE_FILE_TOOLSETS,
    SANDBOX_OWNER_RE,
    SANDBOX_TOOLSETS,
    _sandbox_policy,
    _sandbox_terminal,
    _sandbox_unavailable,
    _sandbox_validate_attachment_snapshot,
    _sandbox_verify_attachment_directories,
    _sandbox_workspace,
)
from .sandbox_approvals import _with_sandbox_approvals


# Control Plane 이 올린 스킬을 두는 루트의 Hermes 컨테이너 쪽 경로다. Compose 가 준다.
SKILL_ROOT_ENV = "FOS_ASSISTANT_SKILL_AGENT_ROOT"
# 올린 스킬의 버전 디렉터리 이름이다. `.` 과 `..` 은 첫 글자 규칙에서 걸린다.
SKILL_VERSION_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
# 버전 디렉터리 아래에서 심볼릭 링크를 찾을 때 볼 항목 수의 상한이다.
# Control Plane 은 스킬마다 파일 20개까지만 받으므로 이 수에 닿으면 정상적인 디렉터리가 아니다.
SKILL_TREE_LIMIT = 5000


def _unlock_api_toolsets(config: dict, allowed: list, platforms: set, calculate) -> dict:
    """요청한 API 도구를 `agent.disabled_toolsets` 에서 뺀 설정이다.

    Hermes 는 허용 목록을 계산한 뒤 `disabled_toolsets` 를 마지막에 빼므로, 거기 남은 이름은 목록에 있어도 열리지 않는다.
    `disabled_toolsets` 는 모든 platform 에 걸린다. 빼면 목록이 없어 기본 toolset 을 쓰는 platform 에
    그 도구가 열리므로, 계산 결과가 바뀌는 platform 은 지금 계산 결과를 명시 목록으로 먼저 고정한다.
    목록이 이미 있는 platform 이 바뀌면 부르는 쪽의 다른 platform 검사가 거절한다.
    """
    agent = config.get("agent") or {}
    disabled = agent.get("disabled_toolsets")
    if not isinstance(disabled, list) or not set(allowed) & set(disabled):
        return config
    unlocked = {**config, "agent": {**agent, "disabled_toolsets": [name for name in disabled if name not in allowed]}}
    lists = dict(config.get("platform_toolsets") or {})
    for name in sorted(platforms - set(lists)):
        before = calculate(config, name)
        if calculate(unlocked, name) != before:
            lists[name] = sorted(before)
    unlocked["platform_toolsets"] = lists
    return unlocked


def _write_checked_config(path: pathlib.Path, original: bytes, config: dict,
                          attachment_guard: Optional[tuple] = None) -> bytes:
    """검사를 마친 설정을 처리기보다 먼저 쓴다. 읽은 뒤 파일이 바뀌었으면 쓰지 않는다."""
    import yaml
    if path.is_symlink() or path.read_bytes() != original:
        raise FileExistsError("검사 뒤 profile 설정이 밖에서 바뀌었다")
    value = yaml.safe_dump(config, sort_keys=False, allow_unicode=True).encode()
    if attachment_guard is not None:
        _sandbox_validate_attachment_snapshot(*attachment_guard)
    _atomic_private_write(path, value)
    return value


def _restore_config(path: pathlib.Path, original: bytes, written: bytes) -> None:
    # 이 요청이 쓴 값일 때만 복원한다. 바깥의 새 수정은 덮어쓰지 않는다.
    if path.exists() and path.read_bytes() == written:
        _atomic_private_write(path, original)


def _toolset_rejection(allowed) -> Optional[object]:
    """요청한 API 도구 목록의 모양만 본다. 계산은 `_check_config_update` 가 한다."""
    if not isinstance(allowed, list) or any(not isinstance(name, str) for name in allowed):
        return _rejected("도구 이름은 문자열 목록이어야 한다")
    if "memory" in allowed or CONTROL_PLANE_MCP not in allowed:
        return _rejected("memory 는 끄고 Control Plane MCP 는 허용해야 한다")
    return None


def _skill_root() -> Optional[pathlib.Path]:
    raw = os.environ.get(SKILL_ROOT_ENV, "").strip()
    if not raw or not os.path.isabs(raw):
        return None
    return pathlib.Path(raw)


def _skill_dir_rejection(profile: str, entry, root: pathlib.Path) -> Optional[object]:
    """게시할 버전 디렉터리 경로 하나를 본다. `<root>/<profile>/<version>` 이어야 한다."""
    if not isinstance(entry, str) or not entry:
        return _rejected("스킬 경로는 문자열이다")
    if any(ch in entry for ch in "~$\\\0") or not entry.startswith("/"):
        return _rejected("스킬 경로는 치환 없는 절대 경로다")
    parts = entry.split("/")[1:]
    if any(part in ("", ".", "..") for part in parts):
        return _rejected("스킬 경로에 빈 조각이나 . 이나 .. 이 있다")
    root_parts = str(root).rstrip("/").split("/")[1:]
    if (len(parts) != len(root_parts) + 2 or parts[:len(root_parts)] != root_parts
            or parts[len(root_parts)] != profile
            or not SKILL_VERSION_RE.match(parts[len(root_parts) + 1])):
        return _rejected("스킬 경로는 %s/<profile>/<version> 이어야 한다" % root)

    path = pathlib.Path(entry)
    # Hermes 는 없는 디렉터리를 오류 없이 건너뛴다. 잘못 게시하면 올린 스킬이 말없이 사라진다.
    if not path.is_dir():
        return _rejected("스킬 디렉터리가 없다")
    # 링크를 따라간 경로가 원래 문자열과 같아야 한다. 루트나 profile 디렉터리가 링크여도 걸린다.
    if str(path.resolve()) != entry:
        return _rejected("스킬 경로에 심볼릭 링크가 있다")
    # skill_view 는 파일을 그대로 읽는다. 링크가 다른 profile 의 .env 를 가리키면 그 토큰이 모델에게 간다.
    seen = 0
    for current, dirs, files in os.walk(path):
        for child in dirs + files:
            seen += 1
            if seen > SKILL_TREE_LIMIT:
                return _rejected("스킬 디렉터리의 항목이 너무 많다")
            if os.path.islink(os.path.join(current, child)):
                return _rejected("스킬 디렉터리 안에 심볼릭 링크가 있다")
    return None


def _operator_skill_dirs(saved: dict, profile: str, root: pathlib.Path) -> list:
    """저장된 external_dirs 가운데 Control Plane 이 게시한 것이 아닌 항목이다."""
    raw = (saved.get("skills") or {}).get("external_dirs") or []
    if isinstance(raw, str):
        raw = [raw]
    prefix = "%s/%s/" % (str(root).rstrip("/"), profile)
    return [entry for entry in raw if not (isinstance(entry, str) and entry.startswith(prefix))]


def _bound_servers(profile_dir: pathlib.Path) -> list:
    """소유 기록의 바인딩 항목이 설치한 서버 이름이다. 기록이 없으면 빈 목록이다.

    기록은 JSON 모양만 본다. `_connector_state` 는 manifest 의 실행 정의가 바뀌면 예외를 내어,
    운영자가 커넥터를 바꾸면 그 커넥터가 붙은 모든 에이전트의 도구 저장이 실패하기 때문이다.
    """
    path = profile_dir / CONNECTOR_STATE
    if not path.is_file():
        return []
    state = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(state, dict):
        raise ValueError("connector 소유 기록이 올바르지 않다")
    return [entry["mcp_server"] for entry in state.values()
            if _entry_mode(entry) == BIND_MODE and isinstance(entry.get("mcp_server"), str)]


async def _check_config_update(request):
    """공유 토큰의 설정 쓰기를 profile 별 API 도구 목록과 올린 스킬 경로로 제한한다.

    처리기는 API 도구 목록을 통째로 바꾼다. 그래서 바인딩 설치가 더한 커넥터 서버 이름은
    Control Plane 이 목록에 함께 보낸다는 계약이다. 그 이름이 하나라도 빠진 요청은 409 로 거절한다(ADR-083).
    조용히 지워지면 붙은 커넥터의 도구가 말없이 사라진다.
    """
    body = await _json_object(request)
    if body is None or set(body) - {"sandbox_owner"} != {"profile", "config"}:
        return _rejected("profile 과 config 와 sandbox_owner 만 받는다")
    # Hermes 처리기는 config 와 profile 만 읽으므로 sandbox_owner 는 이 검사만 쓴다.
    owner = body.get("sandbox_owner")
    if "sandbox_owner" in body and not (isinstance(owner, str) and SANDBOX_OWNER_RE.match(owner)):
        return _rejected("sandbox_owner 형식이 올바르지 않다")
    profile = body["profile"]
    rejected = _profile_rejection(profile, request)
    if rejected is not None:
        return rejected

    config = body["config"]
    if (not isinstance(config, dict) or not config
            or not set(config) <= {"platform_toolsets", "skills"}):
        return _rejected("도구 목록과 스킬 경로 설정만 쓸 수 있다")
    platform = config.get("platform_toolsets")
    if platform is not None:
        if not isinstance(platform, dict) or set(platform) != {"api_server"}:
            return _rejected("api_server 목록만 쓸 수 있다")
        rejected = _toolset_rejection(platform["api_server"])
        if rejected is not None:
            return rejected
    sandbox = None
    local_execution = False
    if platform is not None and SANDBOX_TOOLSETS & set(platform["api_server"]):
        sandbox = _sandbox_policy()
        if sandbox is None:
            return _sandbox_unavailable()
        if profile not in sandbox["profiles"]:
            if IMAGE_FILE_TOOLSETS & set(platform["api_server"]):
                return _sandbox_unavailable()
            sandbox = None
            local_execution = True
        elif owner is None:
            return _rejected("격리할 셸 도구에는 sandbox_owner 가 필요하다")
    skills = config.get("skills")
    skill_dirs = None
    if skills is not None:
        # create_dir 같은 다른 skills.* 키는 받지 않는다. 모델이 스킬을 쓰는 자리를 바꾼다.
        if not isinstance(skills, dict) or set(skills) != {"external_dirs"}:
            return _rejected("skills 는 external_dirs 만 쓸 수 있다")
        skill_dirs = skills["external_dirs"]
        if not isinstance(skill_dirs, list) or len(skill_dirs) > 1:
            return _rejected("external_dirs 는 경로 0개나 1개의 목록이다")
        root = _skill_root()
        if root is None:
            logger.error("dashboard-profile-api: %s 가 없거나 절대 경로가 아니다", SKILL_ROOT_ENV)
            return _rejected("스킬 루트가 설정되지 않았다", 500)
        # 경로 검사는 profile 설정을 읽지 않는다. 운영 검사가 없는 profile 이름으로 이 분기를 본다.
        for entry in skill_dirs:
            rejected = _skill_dir_rejection(profile, entry, root)
            if rejected is not None:
                return rejected

    try:
        import yaml
        from hermes_cli.profiles import get_profile_dir
        from hermes_cli.tools_config import PLATFORMS, _get_platform_tools, _get_plugin_toolset_keys
        from hermes_cli.web_server_profiles import _config_profile_scope
        from toolsets import TOOLSETS

        missing = _missing_profile(profile)
        if missing is not None:
            return missing
        config_path = get_profile_dir(profile) / "config.yaml"
        original = config_path.read_bytes()
        saved = yaml.safe_load(original) or {}
        if not isinstance(saved, dict):
            raise ValueError("profile 설정이 객체가 아니다")
        mcp_names = set((saved.get("mcp_servers") or {}).keys())
        builtins = set(TOOLSETS)

        updated = dict(saved)
        if platform is not None:
            allowed = platform["api_server"]
            known = builtins | _get_plugin_toolset_keys() | mcp_names | {CONTROL_PLANE_MCP}
            if set(allowed) - known:
                return _rejected("모르는 도구 이름이 있다")
            if set(_bound_servers(get_profile_dir(profile))) - set(allowed):
                return _rejected("연결된 커넥터의 도구 이름이 빠졌다", 409)
            if CONTROL_PLANE_MCP not in mcp_names and not (set(allowed) & builtins):
                return _rejected("내장 도구가 하나 이상 필요하다")
            updated["platform_toolsets"] = {**(saved.get("platform_toolsets") or {}), **platform}
        if sandbox is not None:
            # 칸 일부만 고치면 운영자가 남긴 local 설정이나 값 전달 칸이 섞인다. 통째로 바꾼다.
            try:
                os.makedirs(_sandbox_workspace(sandbox, owner), exist_ok=True)
                _sandbox_prepare_connector_output(sandbox, profile, owner)
                prepared = _sandbox_verify_attachment_directories(sandbox, owner)
                updated["terminal"] = _sandbox_terminal(sandbox, profile, owner, prepared)
                updated = _with_sandbox_approvals(saved, updated, True)
                request.state.fos_checked_attachments = (sandbox, owner, prepared)
            except OSError:
                return _sandbox_unavailable()
        elif local_execution:
            # 정책에서 빠진 profile 도 다음 도구 저장부터 local 로 돌아간다.
            # 이미 local 인 설정은 유지하되 .env 의 backend 값보다 명시한 local 값이 이기게 한다.
            previous = saved.get("terminal") or {}
            if not isinstance(previous, dict):
                raise ValueError("terminal 설정이 객체가 아니다")
            terminal = dict(previous) if previous.get("backend", "local") == "local" else {}
            terminal["backend"] = "local"
            updated["terminal"] = terminal
            updated = _with_sandbox_approvals(saved, updated, False)
        if skill_dirs is not None:
            # PUT /api/config 는 목록을 통째로 바꾼다. 운영자가 넣은 경로가 있으면 지우지 않고 멈춘다.
            if _operator_skill_dirs(saved, profile, root):
                return _rejected("Control Plane 이 게시하지 않은 스킬 경로가 이미 있다", 409)
            updated["skills"] = {**(saved.get("skills") or {}), "external_dirs": list(skill_dirs)}

        existing_platforms = saved.get("platform_toolsets") or {}
        platforms = (set(PLATFORMS) | set(existing_platforms) |
                     set(saved.get("platforms") or {})) - {"api_server"}
        # 계산 함수가 기본 toolset 을 고를 때 그 profile 의 비밀값을 읽는다(XAI_API_KEY 등).
        # 공유 gateway 는 multiplex 로 돌아 profile scope 밖에서 읽으면 UnscopedSecretError 가 난다.
        # 대시보드의 설정 처리기와 같은 scope 를 쓴다.
        with _config_profile_scope(profile):
            if platform is not None:
                updated = _unlock_api_toolsets(updated, platform["api_server"], platforms, _get_platform_tools)
            effective = set(_get_platform_tools(updated, "api_server"))
            other_changed = any(_get_platform_tools(saved, name) != _get_platform_tools(updated, name)
                                for name in platforms)
        if platform is not None and ("memory" in effective or effective - set(platform["api_server"])):
            return _rejected("요청 목록에 없는 API 도구가 열린다")
        if other_changed:
            return _rejected("다른 platform 의 도구 목록이 바뀐다")
        if skill_dirs and "skills" not in effective:
            return _rejected("skills 도구가 꺼진 채로 스킬을 게시할 수 없다")
        # 처리기의 병합은 본문의 키만 쓴다. 본문에 없는 disabled_toolsets, 고정 목록, terminal, approvals 는 plugin 이 먼저 쓴다.
        # terminal 이 그대로여도 approvals 만 바뀌면 쓴다. 이미 docker 인 profile 을 다시 저장해 승인 값을 넣는다.
        if (updated.get("agent") != saved.get("agent") or updated.get("terminal") != saved.get("terminal")
                or updated.get("approvals") != saved.get("approvals")):
            request.state.fos_checked_config = (config_path, original, updated)
    except Exception:
        logger.exception("dashboard-profile-api: profile 설정을 검증하지 못했다")
        return _rejected("profile 설정을 검증하지 못했다", 500)
    return None
