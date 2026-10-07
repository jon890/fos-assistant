"""## 만든 자리에서 설정 틀을 쓴다

clone 없이 만든 profile 은 `config.yaml` 에 `model` 만 받는다.
그대로 두면 API 경로가 `hermes-api-server` 복합 toolset 으로 떨어져
`terminal`, `file`, `memory` 를 포함한 거의 모든 toolset 이 열린다.
그래서 토큰으로 부른 `POST /api/profiles` 는 처리기가 성공한 뒤 같은 요청 안에서 아래를 한다.

1. 새로 생긴 이름이 정확히 하나인지 본다
2. 새 profile 의 `model` 블록만 남기고, 같은 디렉터리의 `default-config.yaml.template` 의
   나머지 키를 쓴다. 틀의 `model` 은 자리표시자라 쓰지 않는다.
   틀에는 Control Plane MCP 등록과 켤 profile plugin 목록이 들어 있다
3. `.no-bundled-skills` 표식을 쓴다
4. 틀의 `plugins.enabled` 에 있는 plugin 을 `profile-plugins/<이름>/` 에서 그 profile 로 복사한다
5. 쓴 파일을 다시 읽어 `_get_platform_tools(config, "api_server")` 로 계산하고,
   `FORBIDDEN_TOOLSETS` 가 하나도 없는지 본다
6. 관리 표식 `MANAGED_MARKER` 를 쓴다
7. 공유 gateway 에 그 profile 의 plugin 을 다시 읽으라고 알린다. 실패해도 만들기는 성공이다

1~6 에서 하나라도 실패하면 새로 생긴 이름을 모두 지우고 500 을 돌려준다.
틀이 없거나, 복사할 plugin 이 없거나, 계산 함수를 읽어 오지 못하거나, 계산이 예외를 내는 경우가 모두 여기 해당한다.
`_get_platform_tools` 는 밑줄로 시작하는 내부 함수라 Hermes 를 올릴 때 이름이 바뀔 수 있다.
그때 넓게 열린 profile 이 남지 않고 만들기가 거절되게 하려는 것이다.

MCP 토큰은 틀에 넣지 않는다. 틀은 `${MCP_FOS_ASSISTANT_API_KEY}` 참조만 두고,
Control Plane 이 토큰을 발급해 `PUT /api/env` 로 넣는다.

**만들기 전 목록을 읽지 못하면 처리기를 부르지 않는다.**
전후 목록의 차이로 새 이름을 찾으므로, 앞의 목록이 비면 운영 profile 전부가 새 이름으로 보여
되돌리기가 그것을 지운다.

## 지우기

`DELETE /api/profiles/<이름>` 은 그 profile 에 관리 표식이 있을 때만 토큰으로 받는다.
표식은 위 6 에서만 쓴다. 사람이 대시보드나 CLI 로 만든 profile 과 기본 profile 에는 없다.
그래서 이 토큰으로 지울 수 있는 것은 이 토큰으로 만든 profile 뿐이다.
표식은 파일이라 대시보드를 다시 띄워도 남는다.
"""

from __future__ import annotations

import datetime
import json
import os
import pathlib
import re
import shutil
from typing import Optional
from .common import (
    MANAGED_MARKER,
    PLUGIN_DIR,
    PROFILE_NAME_RE,
    SKILL_NAME_RE,
    _json_object,
    _missing_profile,
    _profile_rejection,
    _rejected,
    logger,
)


TEMPLATE_PATH = PLUGIN_DIR / "default-config.yaml.template"
# 틀의 plugins.enabled 에 있는 profile plugin 의 원본이다. `hermes/bundle.sh` 가 함께 복사한다.
PROFILE_PLUGIN_DIR = PLUGIN_DIR / "profile-plugins"

# 틀을 쓴 뒤 API 경로에 하나라도 남으면 만든 것을 지운다.
FORBIDDEN_TOOLSETS = frozenset({"memory", "terminal", "file", "code_execution", "browser"})
# 기존 단일 파일 plugin 을 갱신할 때 하위 모듈을 모두 둔 뒤 진입점을 바꾼다.
PROFILE_PLUGIN_FILES = ("plugin.yaml", "context.py", "connector_policy.py", "subagent.py", "hooks.py", "__init__.py")
# POST /api/profiles 본문에 둘 수 있는 키다. clone_from 처럼 다른 profile 의 파일을 끌어오는 키를 막는다.
PROFILE_CREATE_KEYS = frozenset({"name", "no_skills", "description"})


def _profile_names() -> Optional[frozenset]:
    """지금 있는 profile 이름을 돌려준다. 읽지 못하면 None 이다."""
    try:
        from hermes_cli.profiles import list_profile_names

        return frozenset(list_profile_names())
    except Exception:
        logger.exception("dashboard-profile-api: profile 목록을 읽지 못했다")
        return None


def _copy_profile_plugin(name: str, profile_dir: pathlib.Path) -> None:
    """profile plugin 하나를 그 profile 의 plugins/ 로 복사한다. 파일 644, 디렉터리 755 다."""
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", name):
        raise ValueError("profile plugin 이름이 올바르지 않다: %r" % name)
    source = PROFILE_PLUGIN_DIR / name
    if not (source / "plugin.yaml").is_file() or not (source / "__init__.py").is_file():
        raise FileNotFoundError("%s 에 plugin 이 온전하지 않다" % source)
    target = profile_dir / "plugins" / name
    # 처리기가 만든 profile 이라 plugin 이 있을 리 없다. 있으면 무엇이 둔 것인지 모르므로 멈춘다.
    if target.exists():
        raise FileExistsError("%s 가 이미 있다" % target)
    shutil.copytree(source, target, ignore=shutil.ignore_patterns("__pycache__"))
    for current, dirs, files in os.walk(target):
        os.chmod(current, 0o755)
        for file_name in files:
            os.chmod(os.path.join(current, file_name), 0o644)
    os.chmod(target.parent, 0o755)


def _profile_plugin_files(name: str) -> dict[str, bytes] | None:
    """설치 묶음에 든 profile plugin 의 파일 이름과 바이트다. 디렉터리나 파일 하나가 없으면 None 이다.

    저장소에서 바로 읽은 plugin 에는 묶음 디렉터리가 없다. 그때는 견줄 판이 없다.
    """
    source = PROFILE_PLUGIN_DIR / name
    if not source.is_dir() or any(not (source / file_name).is_file() for file_name in PROFILE_PLUGIN_FILES):
        return None
    return {file_name: (source / file_name).read_bytes() for file_name in PROFILE_PLUGIN_FILES}


def _write_managed_marker(profile_dir: pathlib.Path) -> None:
    marker = profile_dir / MANAGED_MARKER
    marker.write_text(
        json.dumps({
            "created_by": "fos-assistant-control-plane",
            "created_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        }) + "\n",
        encoding="utf-8",
    )
    os.chmod(marker, 0o644)


def _apply_template(name: str) -> None:
    """새 profile 에 설정 틀과 plugin 과 표식을 쓰고 API 경로 도구를 계산한다. 실패하면 예외를 낸다."""
    import yaml
    from hermes_cli import profiles as profiles_mod
    from hermes_cli.config import save_config
    from hermes_cli.tools_config import _get_platform_tools
    from hermes_constants import reset_hermes_home_override, set_hermes_home_override

    template = yaml.safe_load(TEMPLATE_PATH.read_text(encoding="utf-8"))
    if not isinstance(template, dict):
        raise ValueError("%s 가 mapping 이 아니다" % TEMPLATE_PATH)
    plugins = (template.get("plugins") or {}).get("enabled") or []
    if not isinstance(plugins, list):
        raise ValueError("틀의 plugins.enabled 가 목록이 아니다")

    profile_dir = profiles_mod.get_profile_dir(name)
    config_path = profile_dir / "config.yaml"
    current = {}
    if config_path.is_file():
        current = yaml.safe_load(config_path.read_text(encoding="utf-8")) or {}

    config = {key: value for key, value in template.items() if key != "model"}
    if current.get("model"):
        config = {"model": current["model"], **config}

    token = set_hermes_home_override(str(profile_dir))
    try:
        # 틀의 값이 Hermes 기본값과 같아도 파일에 남긴다. 운영 검사가 파일을 읽어 판정한다.
        save_config(config, strip_defaults=False)
        (profile_dir / profiles_mod.NO_BUNDLED_SKILLS_MARKER).touch()
        for plugin in plugins:
            _copy_profile_plugin(str(plugin), profile_dir)
        written = yaml.safe_load(config_path.read_text(encoding="utf-8")) or {}
        enabled = _get_platform_tools(written, "api_server")
    finally:
        reset_hermes_home_override(token)

    leaked = FORBIDDEN_TOOLSETS & set(enabled)
    if leaked:
        raise ValueError("API 경로에 %s 가 열린다" % ", ".join(sorted(leaked)))
    # 표식은 마지막에 쓴다. 표식이 있는 profile 은 모든 검사를 지난 것이다.
    _write_managed_marker(profile_dir)
    logger.info(
        "dashboard-profile-api: %s 에 설정 틀과 plugin %s 을 썼다. API 경로 도구는 %s 다",
        name, ", ".join(str(p) for p in plugins) or "없음", ", ".join(sorted(enabled)) or "없음",
    )


def _reload_profile_plugins(name: str) -> None:
    """공유 gateway 에 그 profile 의 plugin 을 다시 읽게 한다. 실패해도 만들기는 그대로 둔다.

    Hermes 는 profile plugin 을 그 profile 의 첫 hook 호출 때 읽는다.
    처리기가 만든 직후 gateway 가 profile 을 받으면서 plugin 을 한 번 찾으므로,
    그 뒤에 넣은 plugin 이 빠진 목록이 남지 않게 다시 찾게 한다.
    """
    try:
        from gateway.control_socket import reload_gateway_plugins
        from hermes_cli.profiles import get_profile_dir
        from hermes_constants import get_default_hermes_root

        answer = reload_gateway_plugins(
            pathlib.Path(get_default_hermes_root()), profile_home=get_profile_dir(name)
        )
    except Exception:
        logger.exception("dashboard-profile-api: %s 의 plugin 을 다시 읽게 하지 못했다", name)
        return
    if answer and answer.get("reloaded"):
        logger.info(
            "dashboard-profile-api: gateway 가 %s 의 plugin %s 을 다시 읽었다",
            name, ", ".join(answer.get("plugins") or []) or "없음",
        )
    else:
        logger.warning(
            "dashboard-profile-api: gateway 가 %s 의 plugin 을 다시 읽지 않았다: %s",
            name, (answer or {}).get("error", "응답 없음"),
        )


def _remove_created(names) -> None:
    """틀을 쓰지 못한 profile 을 지운다. 지우지 못한 것은 로그로 남긴다."""
    from hermes_cli import profiles as profiles_mod

    for name in sorted(names):
        try:
            profiles_mod.delete_profile(name, yes=True)
            logger.warning("dashboard-profile-api: 틀을 쓰지 못한 %s 를 지웠다", name)
        except Exception:
            logger.exception(
                "dashboard-profile-api: 틀을 쓰지 못한 %s 를 지우지 못했다. 사람이 지운다", name
            )


def _provision(created) -> bool:
    """만든 profile 에 틀을 쓴다. 실패하면 만든 것을 모두 지우고 False 를 돌려준다."""
    try:
        if len(created) != 1:
            raise ValueError("새로 생긴 profile 이 하나가 아니다: %d개" % len(created))
        _apply_template(next(iter(created)))
    except Exception:
        logger.exception("dashboard-profile-api: 새 profile 에 설정 틀을 쓰지 못했다")
        _remove_created(created)
        return False
    _reload_profile_plugins(next(iter(created)))
    return True


async def _check_profile_create(request):
    """만들기 본문의 키를 제한한다. 이름 규칙과 중복은 Hermes 처리기가 판정한다."""
    body = await _json_object(request)
    if body is None:
        return _rejected("JSON 객체가 필요하다")
    if not set(body) <= PROFILE_CREATE_KEYS or not isinstance(body.get("name"), str):
        return _rejected("name, no_skills, description 만 받는다")
    if "no_skills" in body and not isinstance(body["no_skills"], bool):
        return _rejected("no_skills 는 true 나 false 다")
    if "description" in body and not isinstance(body["description"], str):
        return _rejected("description 은 문자열이다")
    return None


async def _check_skills_list(request):
    """스킬 목록은 profile 하나를 query 로 정확히 받는다. 없으면 대시보드 자기 profile 이 읽힌다."""
    params = request.query_params
    profiles = params.getlist("profile")
    if set(params.keys()) != {"profile"} or len(profiles) != 1:
        return _rejected("query 에 profile 하나만 필요하다")
    rejected = _profile_rejection(profiles[0], request)
    if rejected is not None:
        return rejected
    try:
        return _missing_profile(profiles[0])
    except Exception:
        logger.exception("dashboard-profile-api: profile 을 확인하지 못했다")
        return _rejected("profile 을 확인하지 못했다", 500)


async def _check_skill_toggle(request):
    """스킬 켜고 끄기를 지정한 profile 의 이름 하나로 제한한다."""
    body = await _json_object(request)
    if body is None or set(body) != {"profile", "name", "enabled"}:
        return _rejected("profile, name, enabled 만 필요하다")
    rejected = _profile_rejection(body["profile"], request)
    if rejected is not None:
        return rejected
    if not isinstance(body["name"], str) or not SKILL_NAME_RE.match(body["name"]):
        return _rejected("스킬 이름이 올바르지 않다")
    if not isinstance(body["enabled"], bool):
        return _rejected("enabled 는 true 나 false 다")
    try:
        return _missing_profile(body["profile"])
    except Exception:
        logger.exception("dashboard-profile-api: profile 을 확인하지 못했다")
        return _rejected("profile 을 확인하지 못했다", 500)


def _delete_check(name: str):
    """지우기 검사를 만든다. 관리 표식이 있는 profile 만 받는다."""

    async def check(request):
        if name == "default" or not PROFILE_NAME_RE.match(name):
            return _rejected("지울 수 없는 profile 이다", 401)
        try:
            from hermes_cli.profiles import get_profile_dir, profile_exists

            if not profile_exists(name):
                return _rejected("없는 profile 이다", 404)
            if not (get_profile_dir(name) / MANAGED_MARKER).is_file():
                return _rejected("이 토큰으로 만든 profile 이 아니다", 401)
        except Exception:
            logger.exception("dashboard-profile-api: 지울 profile 을 확인하지 못했다")
            return _rejected("profile 을 확인하지 못했다", 500)
        return None

    return check


def _model_defaults_response(name):
    """profile 설정에서 공개 가능한 모델 기본값 세 칸만 돌려준다."""
    if not isinstance(name, str) or not PROFILE_NAME_RE.fullmatch(name):
        return _rejected("profile 이름이 올바르지 않다", 400)
    try:
        from hermes_cli.profiles import get_profile_dir, profile_exists
        from starlette.responses import JSONResponse
        import yaml

        if not profile_exists(name):
            return _rejected("없는 profile 이다", 404)
        config = yaml.safe_load((get_profile_dir(name) / "config.yaml").read_text(encoding="utf-8")) or {}
        model = config.get("model") or {}
        agent = config.get("agent") or {}
        if not isinstance(model, dict) or not isinstance(agent, dict):
            return _rejected("profile 설정을 읽지 못했다", 503)

        def public_text(value):
            return value if isinstance(value, str) and value.strip() else None

        return JSONResponse({"provider": public_text(model.get("provider")),
                             "model": public_text(model.get("default")),
                             "reasoningEffort": public_text(agent.get("reasoning_effort"))}, status_code=200)
    except Exception:
        logger.warning("dashboard-profile-api: 모델 기본값을 읽지 못했다")
        return _rejected("profile 설정을 읽지 못했다", 503)


def _decision_readiness_response(name):
    """판단 profile 의 도구와 기억 차단만 확인한다. 설정과 파일 내용은 돌려주지 않는다."""
    if not isinstance(name, str) or not PROFILE_NAME_RE.fullmatch(name):
        return _rejected("profile 이름이 올바르지 않다", 400)
    try:
        from hermes_cli.profiles import get_profile_dir, profile_exists
        from hermes_cli.tools_config import _get_platform_tools
        from hermes_cli.web_server_profiles import _config_profile_scope
        from starlette.responses import JSONResponse
        import yaml

        if not profile_exists(name):
            return _rejected("없는 profile 이다", 404)
        with _config_profile_scope(name):
            config = yaml.safe_load((get_profile_dir(name) / "config.yaml").read_text(encoding="utf-8")) or {}
            toolsets = _get_platform_tools(config, "api_server")
        memory = config.get("memory") or {}
        ready = (not toolsets
                 and (config.get("platform_toolsets") or {}).get("api_server") == ["no_mcp"]
                 and memory.get("provider") == "none"
                 and memory.get("memory_enabled") is False
                 and memory.get("user_profile_enabled") is False
                 and config.get("fallback_providers") == [])
        return JSONResponse({"version": 1, "ready": ready}, status_code=200)
    except Exception:
        logger.warning("dashboard-profile-api: 판단 profile 설정을 읽지 못했다")
        return _rejected("profile 설정을 읽지 못했다", 503)
