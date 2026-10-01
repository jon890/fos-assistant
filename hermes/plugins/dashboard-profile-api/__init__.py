"""Control Plane 이 Hermes 대시보드의 profile 관리 경로를 토큰으로 부르게 연다.

대시보드의 `/api/*` 는 기본 상태에서 사람용 로그인 쿠키만 받는다.
`hermes_cli/dashboard_auth/token_auth.py` 가 기계용 자리를 따로 두고,
`register_token_route(path)` 로 등록한 경로만 `Authorization: Bearer` 를 받는다.
배포본에서 그 함수를 부르는 것은 번들 plugin `plugins/dashboard_auth/drain` 하나뿐이라
profile 관리 경로는 등록돼 있지 않다.

이 plugin 은 provider 하나를 등록하고 `token_auth_middleware` 를 감싼다.
Hermes core 는 고치지 않는다.

## 여는 것

| 요청 | 쓰임 |
| --- | --- |
| `GET /api/profiles` | 이름이 이미 있는지 본다 |
| `POST /api/profiles` | profile 을 만든다. 아래 「만든 자리에서 설정 틀을 쓴다」 를 거친다 |
| `DELETE /api/profiles/<이름>` | 관리 표식이 있는 profile 을 지운다 |
| `PUT /api/env` | 그 profile 의 `.env` 에 정해 둔 key 한 줄을 쓴다 |
| `DELETE /api/env` | 관리 profile 의 가계부 key 만 지운다 |
| `GET PUT /api/connectors` | 알려진 connector 의 상태를 읽거나 관리 profile 에 설치하고 제거한다 |
| `POST /api/mcp/servers/accountbook/test` | 설치한 가계부 MCP 서버만 probe 한다 |
| `GET /api/profiles/<이름>/soul` | profile 의 SOUL.md 를 읽는다 |
| `PUT /api/profiles/<이름>/soul` | profile 의 SOUL.md 를 쓴다 |
| `GET /api/tools/toolsets` | 도구 이름과 설명을 읽는다 |
| `PUT /api/config` | 지정한 profile 의 API 도구 목록과 올린 스킬 경로만 쓴다 |
| `GET /api/skills` | 지정한 profile 의 스킬 목록을 읽는다 |
| `PUT /api/skills/toggle` | 지정한 profile 의 스킬 하나를 켜고 끈다 |

`PUT /api/profiles/<이름>/soul` 과 `GET /api/tools/toolsets` 를 뺀 요청은 토큰 요청의 본문이나 query 를
먼저 검사한다. 검사 규칙은 각 `_check_*` 함수가 소유한다. 공통으로 지키는 것은 셋이다.

- 기본 profile 은 거절한다. 운영자가 쓰는 profile 이고 provider credential 이 있다
- 없는 profile 은 404 다
- 본문과 query 에 profile 이 둘 다 있으면 같아야 한다

사람의 쿠키 요청은 기존 Hermes 처리기가 맡는다. 검사하지 않는다.

## 만든 자리에서 설정 틀을 쓴다

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

## 경로를 등록하지 않는 이유

`register_token_route` 로 경로를 등록하면 두 가지가 함께 따라온다.

`is_token_route` 는 경로 문자열만 보고 메서드는 보지 않는다.
`/api/env` 를 등록하면 같은 경로의 `DELETE` 까지 토큰으로 열리고,
그 요청은 그 profile 의 credential 한 줄을 지운다.

`token_auth_middleware` 는 등록된 경로의 인증을 혼자 판정한다.
토큰이 없으면 쿠키를 보지 않고 401 로 끝내므로,
사람이 브라우저로 여는 대시보드의 같은 경로가 함께 막힌다.
`GET /api/profiles` 가 여기 해당한다. 대시보드의 profile 목록이 그 경로를 쓴다.

그래서 경로를 등록하지 않고 `token_auth_middleware` 를 감싼 것이 직접 판정한다.
감싼 것이 지키는 규칙은 하나다.

**들어오는 길을 더하기만 하고, 사람이 쓰던 길을 막지 않는다.**

- 우리가 연 요청이고 토큰이 맞으면 검사를 거쳐 인증된 것으로 표시하고 통과시킨다
- 그 밖의 모든 경우는 다음으로 그대로 넘긴다. 쿠키 검사가 판정한다
- 우리가 다루지 않는 경로는 원래 미들웨어에 그대로 넘긴다. drain plugin 이 계속 돈다

토큰이 없거나 틀린 요청은 쿠키도 없으므로 결국 401 을 받는다.
사람의 브라우저는 쿠키가 있으므로 지금까지대로 200 을 받는다.

`web_server.py` 는 요청마다 `from ... import token_auth_middleware` 를 다시 하므로
모듈 속성을 바꿔 두면 그다음 요청부터 감싼 것이 쓰인다.

미들웨어에서 본문을 읽어도 그 뒤의 처리기가 같은 본문을 다시 읽는다.
`PUT /api/config` 가 운영에서 그렇게 돈다.

**감싸지 못하면 provider 도 등록하지 않는다.** 여는 자리가 하나뿐이라 그것이 없으면 닫힌 채로 남는다.

## 비밀값

`HERMES_DASHBOARD_PROFILE_API_SECRET` 하나를 받는다.
값이 없으면 아무것도 등록하지 않고 끝난다.

엔트로피 판정은 번들 drain plugin 의 `assess_secret_strength` 를 그대로 쓴다.
같은 기준을 두 벌 두지 않기 위해서다. 그 함수를 읽어 오지 못하면 등록하지 않는다.

비교는 `hmac.compare_digest` 로 한다.
"""

from __future__ import annotations

import asyncio
import datetime
import hmac
import json
import logging
import os
import pathlib
import re
import shutil
import tempfile
import time
from typing import Optional

from hermes_cli.dashboard_auth import (
    DashboardAuthProvider,
    LoginStart,
    Session,
    TokenPrincipal,
)

logger = logging.getLogger(__name__)

ENV_VAR = "HERMES_DASHBOARD_PROFILE_API_SECRET"
# Control Plane 이 올린 스킬을 두는 루트의 Hermes 컨테이너 쪽 경로다. Compose 가 준다.
SKILL_ROOT_ENV = "FOS_ASSISTANT_SKILL_AGENT_ROOT"

# 토큰으로 인증할 요청이다. 여기 없는 것은 모두 쿠키 검사로 넘어간다.
# 값은 그 요청에서 먼저 돌릴 검사 함수의 이름이다. None 은 토큰만 본다.
ALLOWED_ROUTES = {
    ("/api/profiles", "GET"): None,
    ("/api/profiles", "POST"): "_check_profile_create",
    ("/api/env", "PUT"): "_check_env_update",
    ("/api/env", "DELETE"): "_check_env_delete",
    ("/api/mcp/servers/accountbook/test", "POST"): "_check_connector_probe",
    ("/api/tools/toolsets", "GET"): None,
    ("/api/config", "PUT"): "_check_config_update",
    ("/api/skills", "GET"): "_check_skills_list",
    ("/api/skills/toggle", "PUT"): "_check_skill_toggle",
}

PROFILES_PATH = "/api/profiles"
PROFILE_PREFIX = "/api/profiles/"
SOUL_SUFFIX = "/soul"
SOUL_METHODS = frozenset({"GET", "PUT"})

# Hermes 의 `hermes_constants.PROFILE_ID_RE` 와 같은 규칙이다. 경로에서 온 이름을 파일 경로로 쓰기 전에 본다.
PROFILE_NAME_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")

# 토큰으로 만든 profile 에 쓰는 설정 틀이다. `hermes/bundle.sh` 가 이 파일 옆에 둔다.
PLUGIN_DIR = pathlib.Path(__file__).resolve().parent
TEMPLATE_PATH = PLUGIN_DIR / "default-config.yaml.template"
# 틀의 plugins.enabled 에 있는 profile plugin 의 원본이다. `hermes/bundle.sh` 가 함께 복사한다.
PROFILE_PLUGIN_DIR = PLUGIN_DIR / "profile-plugins"

# 이 토큰으로 만든 profile 이라는 표식이다. 지우기 판정이 이 파일 하나를 본다.
MANAGED_MARKER = ".fos-assistant-managed"

# 틀을 쓴 뒤 API 경로에 하나라도 남으면 만든 것을 지운다.
FORBIDDEN_TOOLSETS = frozenset({"memory", "terminal", "file", "code_execution", "browser"})
# Control Plane MCP 서버 이름이다.
CONTROL_PLANE_MCP = "fos-assistant"

# PUT /api/env 로 쓸 수 있는 key 다. Control Plane 이 profile 마다 넣는 값만 둔다.
ACCOUNTBOOK_ENV_KEYS = frozenset({"ACCOUNTBOOK_API_BASE_URL", "ACCOUNTBOOK_API_TOKEN", "ACCOUNTBOOK_FAMILY_UUID"})
ENV_KEYS = frozenset({"API_SERVER_KEY", "API_SERVER_MODEL_NAME", "MCP_FOS_ASSISTANT_API_KEY"}) | ACCOUNTBOOK_ENV_KEYS
# 요청은 이름만 받는다. 실행 정의는 커넥터 checkout 의 manifest 가 소유한다.
# 커넥터 이름과 plugin 디렉터리를 묶은 JSON 을 대시보드 프로세스의 환경 변수로 받는다. 근거는 ADR-041 이 갖는다.
CONNECTOR_ROOTS_ENV = "FOS_ASSISTANT_CONNECTOR_ROOTS"
# 커넥터 MCP 서버를 실행할 파일의 절대 경로다. 같은 방식으로 받는다.
CONNECTOR_COMMAND_ENV = "FOS_ASSISTANT_CONNECTOR_COMMAND"
CONNECTOR_SERVER = "accountbook"
CONNECTOR_STATE = ".fos-connectors.json"
PROFILE_WRITE_LOCK = asyncio.Lock()
# POST /api/profiles 본문에 둘 수 있는 키다. clone_from 처럼 다른 profile 의 파일을 끌어오는 키를 막는다.
PROFILE_CREATE_KEYS = frozenset({"name", "no_skills", "description"})

# 올린 스킬 이름이다. fos-assistant 의 스킬 이름 규칙보다 넓어 Hermes 기본 스킬도 켜고 끌 수 있다.
SKILL_NAME_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
# 올린 스킬의 버전 디렉터리 이름이다. `.` 과 `..` 은 첫 글자 규칙에서 걸린다.
SKILL_VERSION_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
# 버전 디렉터리 아래에서 심볼릭 링크를 찾을 때 볼 항목 수의 상한이다.
# Control Plane 은 스킬마다 파일 20개까지만 받으므로 이 수에 닿으면 정상적인 디렉터리가 아니다.
SKILL_TREE_LIMIT = 5000


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


def _rejected(detail: str, status_code: int = 400):
    from starlette.responses import JSONResponse

    return JSONResponse({"detail": detail}, status_code=status_code)


async def _json_object(request):
    """본문을 JSON 객체로 읽는다. 객체가 아니면 None 이다."""
    try:
        body = await request.json()
    except (ValueError, UnicodeDecodeError):
        return None
    return body if isinstance(body, dict) else None


def _profile_rejection(profile, request) -> Optional[object]:
    """profile 이름과 query 를 본다. 문제가 있으면 거절 응답이다. 존재 여부는 보지 않는다."""
    if not isinstance(profile, str) or profile == "default" or not PROFILE_NAME_RE.match(profile):
        return _rejected("기본 profile 또는 잘못된 profile 이다")
    query_profiles = request.query_params.getlist("profile")
    if len(query_profiles) > 1 or (query_profiles and query_profiles[0] != profile):
        return _rejected("query 와 본문의 profile 이 다르다")
    return None


def _missing_profile(profile: str) -> Optional[object]:
    """없는 profile 이면 404 응답이다. 확인하지 못하면 예외를 낸다."""
    from hermes_cli.profiles import profile_exists

    return None if profile_exists(profile) else _rejected("없는 profile 이다", 404)


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


async def _check_env_update(request):
    """`.env` 쓰기를 허용한 key 와 Control Plane 이 쓰는 profile 로 제한한다."""
    body = await _json_object(request)
    if body is None or set(body) != {"profile", "key", "value"}:
        return _rejected("profile, key, value 만 필요하다")
    rejected = _profile_rejection(body["profile"], request)
    if rejected is not None:
        return rejected
    if not isinstance(body["key"], str) or body["key"] not in ENV_KEYS:
        return _rejected("쓸 수 없는 key 다")
    value = body["value"]
    if not isinstance(value, str) or any(ch in value for ch in "\r\n\0"):
        return _rejected("value 는 한 줄 문자열이다")
    try:
        missing = _missing_profile(body["profile"])
        if missing is not None:
            return missing
        if body["key"] in ACCOUNTBOOK_ENV_KEYS:
            from hermes_cli.profiles import get_profile_dir
            if not (get_profile_dir(body["profile"]) / MANAGED_MARKER).is_file():
                return _rejected("관리 표식이 없는 profile 이다", 401)
        return None
    except Exception:
        logger.exception("dashboard-profile-api: profile 을 확인하지 못했다")
        return _rejected("profile 을 확인하지 못했다", 500)


async def _check_env_delete(request):
    """연결 해제는 가계부 key 만 지운다. 모델과 Control Plane credential 은 보존한다."""
    body = await _json_object(request)
    if body is None or set(body) != {"profile", "key"}:
        return _rejected("profile 과 key 만 필요하다")
    rejected = _profile_rejection(body["profile"], request)
    if rejected is not None:
        return rejected
    if not isinstance(body["key"], str) or body["key"] not in ACCOUNTBOOK_ENV_KEYS:
        return _rejected("가계부 환경 변수만 지울 수 있다")
    missing = _missing_profile(body["profile"])
    if missing is not None:
        return missing
    from hermes_cli.profiles import get_profile_dir
    if not (get_profile_dir(body["profile"]) / MANAGED_MARKER).is_file():
        return _rejected("관리 표식이 없는 profile 이다", 401)
    return None


def _connector_roots() -> dict[str, pathlib.Path]:
    """`{"<커넥터 이름>": "<plugin 디렉터리>"}` JSON 을 읽는다. 없거나 틀리면 빈 dict 다."""
    raw = os.environ.get(CONNECTOR_ROOTS_ENV, "").strip()
    if not raw:
        return {}
    try:
        value = json.loads(raw)
    except ValueError:
        value = None
    if not isinstance(value, dict) or any(not isinstance(entry, str) for entry in value.values()):
        # 값에는 운영 경로가 들어 있어 로그에 싣지 않는다.
        logger.warning("dashboard-profile-api: %s 가 문자열 값의 JSON object 가 아니다", CONNECTOR_ROOTS_ENV)
        return {}
    roots = {}
    for name, entry in value.items():
        path = pathlib.Path(entry)
        if not path.is_absolute():
            logger.warning("dashboard-profile-api: %s 의 %s 경로가 절대 경로가 아니라 버렸다", CONNECTOR_ROOTS_ENV, name)
            continue
        roots[name] = path
    return roots


def _connector_command() -> str | None:
    """커넥터 MCP 서버를 실행할 파일의 절대 경로다. 없거나 절대 경로가 아니면 None 이다."""
    command = os.environ.get(CONNECTOR_COMMAND_ENV, "").strip()
    if not command:
        return None
    if not os.path.isabs(command):
        logger.warning("dashboard-profile-api: %s 가 절대 경로가 아니다", CONNECTOR_COMMAND_ENV)
        return None
    return command


def _connector_definition(plugin: str) -> dict:
    """신뢰한 checkout 의 고정 manifest 만 읽고 stdio 실행을 bundle 하나로 제한한다."""
    root = _connector_roots()[plugin]
    if root.resolve() != root:
        raise ValueError("connector 경로에 심볼릭 링크가 있다")
    def read(relative):
        path = root / relative
        if path.resolve() != path or not path.is_file():
            raise ValueError("connector manifest 가 없거나 링크다")
        return json.loads(path.read_text(encoding="utf-8"))
    manifest = read(".claude-plugin/plugin.json")
    if not isinstance(manifest, dict) or manifest.get("name") != plugin:
        raise ValueError("connector 이름이 manifest 와 다르다")
    mcp = read(".mcp.json")
    if not isinstance(mcp, dict):
        raise ValueError("MCP manifest 는 객체여야 한다")
    if "mcpServers" in mcp:
        if set(mcp) != {"mcpServers"}:
            raise ValueError("MCP manifest 에 다른 필드가 있다")
        mcp = mcp["mcpServers"]
    if not isinstance(mcp, dict) or set(mcp) != {CONNECTOR_SERVER}:
        raise ValueError("알려진 MCP 서버 하나만 허용한다")
    server = mcp[CONNECTOR_SERVER]
    if (not isinstance(server, dict) or set(server) - {"command", "args", "env"}
            or server.get("command") != "bun"
            or server.get("args") != ["${CLAUDE_PLUGIN_ROOT}/dist/accountbook-mcp.js"]):
        raise ValueError("bun 으로 connector bundle 하나만 실행한다")
    env = server.get("env")
    if (not isinstance(env, dict) or not {"ACCOUNTBOOK_API_BASE_URL", "ACCOUNTBOOK_API_TOKEN"} <= set(env)
            or set(env) - ACCOUNTBOOK_ENV_KEYS
            or any(value != "${%s}" % key and not
                   (key == "ACCOUNTBOOK_FAMILY_UUID" and value == "${ACCOUNTBOOK_FAMILY_UUID:-}")
                   for key, value in env.items())):
        raise ValueError("MCP 환경 변수는 자기 이름의 참조만 허용한다")
    env = {key: "${%s}" % key for key in env}
    bundle = root / "dist/accountbook-mcp.js"
    if bundle.resolve() != bundle or not bundle.is_file():
        raise ValueError("MCP bundle 이 없거나 링크다")
    command = _connector_command()
    if command is None or not os.access(command, os.X_OK):
        raise ValueError("bun 실행 파일이 없다")
    # 스킬은 읽되 등록하지 않는다. persona 로 지침을 넣고 skills toolset 은 열지 않는다.
    skills = manifest.get("skills", "./skills")
    if isinstance(skills, str):
        skills = [skills]
    if not isinstance(skills, list) or any(not isinstance(entry, str) for entry in skills):
        raise ValueError("스킬 경로 목록이 올바르지 않다")
    for entry in skills:
        path = root / entry
        if not path.resolve().is_relative_to(root) or not path.is_dir() or path.resolve() != path.absolute():
            raise ValueError("스킬은 plugin 안의 링크 없는 디렉터리여야 한다")
    return {"command": command, "args": [str(bundle)], "env": env, "enabled": True}


def _atomic_private_write(path: pathlib.Path, value: bytes) -> None:
    fd, raw = tempfile.mkstemp(prefix=".connector-", dir=path.parent)
    temp = pathlib.Path(raw)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(value)
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


def _connector_state(value) -> dict:
    """소유 기록도 고정 실행 계약과 boolean 필드로 검증한다."""
    roots = _connector_roots()
    command = _connector_command()
    if not isinstance(value, dict) or set(value) - set(roots):
        raise ValueError("connector 소유 기록이 올바르지 않다")
    for plugin, entry in value.items():
        if (not isinstance(entry, dict) or set(entry) != {"server", "allowlist_added"}
                or not isinstance(entry["allowlist_added"], bool)):
            raise ValueError("connector 소유 기록의 필드가 올바르지 않다")
        server = entry["server"]
        if (not isinstance(server, dict) or set(server) != {"command", "args", "env", "enabled"}
                or command is None or server["command"] != command or server["enabled"] is not True
                or server["args"] != [str(roots[plugin] / "dist/accountbook-mcp.js")]):
            raise ValueError("소유 기록의 실행 정의가 알려진 connector 가 아니다")
        env = server["env"]
        if (not isinstance(env, dict) or set(env) - ACCOUNTBOOK_ENV_KEYS
                or not {"ACCOUNTBOOK_API_BASE_URL", "ACCOUNTBOOK_API_TOKEN"} <= set(env)
                or any(v != "${%s}" % k and not (k == "ACCOUNTBOOK_FAMILY_UUID" and v == "")
                       for k, v in env.items())):
            raise ValueError("소유 기록의 환경 변수가 알려진 참조가 아니다")
    return value


def _connector_config(profile_dir: pathlib.Path, plugin: str, enabled: bool) -> dict:
    """관리 표식 profile 의 설정을 바꾸고 실패하면 같은 요청 안에서 되돌린다."""
    import yaml
    if profile_dir.resolve() != profile_dir:
        raise ValueError("profile 경로에 심볼릭 링크가 있다")
    config_path = profile_dir / "config.yaml"
    state_path = profile_dir / CONNECTOR_STATE
    env_path = profile_dir / ".env"
    for path in (config_path, state_path, env_path):
        if path.is_symlink():
            raise ValueError("profile 설정에 심볼릭 링크가 있다")
    originals = {path: path.read_bytes() if path.exists() else None
                 for path in (config_path, state_path, env_path)}
    saved = yaml.safe_load(originals[config_path]) or {}
    state = _connector_state(json.loads(originals[state_path])) if originals[state_path] else {}
    servers = dict(saved.get("mcp_servers") or {})
    owned = state.get(plugin)
    if CONNECTOR_SERVER in servers and (not owned or servers[CONNECTOR_SERVER] != owned["server"]):
        raise FileExistsError("운영자가 등록하거나 바꾼 MCP 서버가 있다")
    if owned and CONNECTOR_SERVER not in servers:
        raise FileExistsError("설치한 MCP 서버가 밖에서 지워졌다")
    allowed = list((saved.get("platform_toolsets") or {}).get("api_server") or [])
    if CONTROL_PLANE_MCP not in allowed or "memory" in allowed or "no_mcp" in allowed:
        raise ValueError("Control Plane MCP 를 허용한 API 도구 목록이 필요하다")
    env_text = originals[env_path].decode("utf-8") if originals[env_path] else ""
    if enabled:
        server = _connector_definition(plugin)
        # Hermes 는 빈 변수의 참조를 그대로 남긴다. 선택한 가족이 없으면 빈 값을 명시한다.
        family_lines = [line.partition("=")[2].strip().strip("\"'")
                        for line in env_text.splitlines() if line.startswith("ACCOUNTBOOK_FAMILY_UUID=")]
        if not family_lines or not family_lines[-1]:
            if "ACCOUNTBOOK_FAMILY_UUID" in server["env"]:
                server["env"]["ACCOUNTBOOK_FAMILY_UUID"] = ""
        added = owned["allowlist_added"] if owned else CONNECTOR_SERVER not in allowed
        servers[CONNECTOR_SERVER] = server
        if CONNECTOR_SERVER not in allowed:
            allowed.append(CONNECTOR_SERVER)
        state[plugin] = {"server": server, "allowlist_added": added}
    else:
        if owned:
            servers.pop(CONNECTOR_SERVER, None)
            if owned["allowlist_added"]:
                allowed = [name for name in allowed if name != CONNECTOR_SERVER]
            state.pop(plugin, None)
    updated = {**saved, "mcp_servers": servers,
               "platform_toolsets": {**(saved.get("platform_toolsets") or {}), "api_server": allowed}}
    values = {config_path: yaml.safe_dump(updated, sort_keys=False, allow_unicode=True).encode(),
              state_path: (json.dumps(state) + "\n").encode()}
    if all(originals[path] == value for path, value in values.items()):
        return {"changed": False, "restart_required": bool(owned)}
    backup = profile_dir / "connector-backups" / str(time.time_ns())
    backup.mkdir(parents=True, mode=0o700)
    os.chmod(backup.parent, 0o700)
    for path, value in originals.items():
        if value is not None:
            _atomic_private_write(backup / path.name, value)
    written = []
    try:
        if any((path.read_bytes() if path.exists() else None) != value for path, value in originals.items()):
            raise FileExistsError("저장 전 profile 설정이 밖에서 바뀌었다")
        for path, value in values.items():
            if (path.read_bytes() if path.exists() else None) != originals[path]:
                raise FileExistsError("profile 설정이 밖에서 바뀌었다")
            _atomic_private_write(path, value)
            written.append(path)
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
        raise
    return {"changed": True, "restart_required": bool(owned)}


async def _connector_request(request):
    from hermes_cli.profiles import get_profile_dir
    from starlette.responses import JSONResponse
    if request.method.upper() == "GET":
        profiles = request.query_params.getlist("profile")
        if len(profiles) != 1 or set(request.query_params.keys()) != {"profile"}:
            return _rejected("query 에 profile 하나만 필요하다")
        body = {"profile": profiles[0]}
    else:
        body = await _json_object(request)
        if (body is None or set(body) != {"profile", "plugin", "enabled"}
                or not isinstance(body["plugin"], str) or body["plugin"] not in _connector_roots()
                or not isinstance(body["enabled"], bool)):
            return _rejected("profile, 알려진 plugin, enabled 만 필요하다")
    rejected = _profile_rejection(body["profile"], request)
    if rejected is not None:
        return rejected
    missing = _missing_profile(body["profile"])
    if missing is not None:
        return missing
    profile_dir = get_profile_dir(body["profile"])
    if not (profile_dir / MANAGED_MARKER).is_file():
        return _rejected("관리 표식이 없는 profile 이다", 401)
    try:
        if request.method.upper() == "GET":
            import yaml
            config = yaml.safe_load((profile_dir / "config.yaml").read_text(encoding="utf-8")) or {}
            state_path = profile_dir / CONNECTOR_STATE
            state = _connector_state(json.loads(state_path.read_text(encoding="utf-8"))) if state_path.exists() else {}
            servers = config.get("mcp_servers") or {}
            connectors = [{"plugin": plugin, "enabled": plugin in state,
                           "configured": plugin in state and servers.get(CONNECTOR_SERVER) == state[plugin]["server"]}
                          for plugin in _connector_roots()]
            return JSONResponse({"profile": body["profile"], "connectors": connectors}, status_code=200)
        result = await asyncio.to_thread(_connector_config, profile_dir, body["plugin"], body["enabled"])
        return JSONResponse({**body, **result}, status_code=200)
    except FileExistsError:
        return _rejected("운영자 설정과 충돌한다", 409)
    except Exception:
        # manifest 내용이나 profile 환경 변수를 응답과 로그에 싣지 않는다.
        return _rejected("connector 파일 또는 설정을 확인하지 못했다", 503)


async def _check_connector_probe(request):
    rejected = await _check_skills_list(request)
    if rejected is not None:
        return rejected
    from hermes_cli.profiles import get_profile_dir
    profile_dir = get_profile_dir(request.query_params.getlist("profile")[0])
    if not (profile_dir / MANAGED_MARKER).is_file():
        return _rejected("관리 표식이 없는 profile 이다", 401)
    state_path = profile_dir / CONNECTOR_STATE
    try:
        import yaml
        if not state_path.is_file():
            return _rejected("설치하지 않은 connector 다", 404)
        owned = _connector_state(json.loads(state_path.read_text(encoding="utf-8"))).get("fos-accountbook")
        if not owned:
            return _rejected("설치하지 않은 connector 다", 404)
        config = yaml.safe_load((profile_dir / "config.yaml").read_text(encoding="utf-8")) or {}
        if (config.get("mcp_servers") or {}).get(CONNECTOR_SERVER) != owned["server"]:
            return _rejected("운영자 설정과 충돌한다", 409)
        definition = _connector_definition("fos-accountbook")
        if owned["server"]["env"].get("ACCOUNTBOOK_FAMILY_UUID") == "":
            definition["env"]["ACCOUNTBOOK_FAMILY_UUID"] = ""
        if definition != owned["server"] or CONNECTOR_SERVER not in (config.get("platform_toolsets") or {}).get("api_server", []):
            return _rejected("현재 manifest 와 profile 설정이 다르다", 409)
    except Exception:
        return _rejected("connector 설정을 확인하지 못했다", 503)
    return None


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


async def _check_config_update(request):
    """공유 토큰의 설정 쓰기를 profile 별 API 도구 목록과 올린 스킬 경로로 제한한다."""
    body = await _json_object(request)
    if body is None or set(body) != {"profile", "config"}:
        return _rejected("profile 과 config 만 필요하다")
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
        profile_dir = get_profile_dir(profile)
        saved = yaml.safe_load((profile_dir / "config.yaml").read_text(encoding="utf-8")) or {}
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
            if CONTROL_PLANE_MCP not in mcp_names and not (set(allowed) & builtins):
                return _rejected("내장 도구가 하나 이상 필요하다")
            updated["platform_toolsets"] = {**(saved.get("platform_toolsets") or {}), **platform}
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
            effective = set(_get_platform_tools(updated, "api_server"))
            other_changed = any(_get_platform_tools(saved, name) != _get_platform_tools(updated, name)
                                for name in platforms)
        if platform is not None and ("memory" in effective or effective - set(platform["api_server"])):
            return _rejected("요청 목록에 없는 API 도구가 열린다")
        if other_changed:
            return _rejected("다른 platform 의 도구 목록이 바뀐다")
        if skill_dirs and "skills" not in effective:
            return _rejected("skills 도구가 꺼진 채로 스킬을 게시할 수 없다")
    except Exception:
        logger.exception("dashboard-profile-api: profile 설정을 검증하지 못했다")
        return _rejected("profile 설정을 검증하지 못했다", 500)
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


def _profile_segment(path: str, suffix: str = "") -> Optional[str]:
    """`/api/profiles/<이름><suffix>` 의 이름이다. 한 단계 아래가 아니면 None 이다."""
    if not path.startswith(PROFILE_PREFIX) or not path.endswith(suffix):
        return None
    name = path[len(PROFILE_PREFIX):len(path) - len(suffix)]
    return name if name and "/" not in name else None


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


class ProfileApiProvider(DashboardAuthProvider):
    """Control Plane 이 보내는 Bearer 토큰 하나를 검사한다."""

    name = "fos-profile-api"
    display_name = "fos-assistant Control Plane (service credential)"
    supports_token = True
    supports_session = False

    def __init__(self, *, secret: str) -> None:
        self._secret = secret

    def verify_token(self, *, token: str) -> Optional[TokenPrincipal]:
        if not token:
            return None
        if hmac.compare_digest(token.encode("utf-8"), self._secret.encode("utf-8")):
            return TokenPrincipal(
                principal="fos-assistant-control-plane",
                provider=self.name,
                scopes=("profile-provision",),
            )
        return None

    # 로그인과 세션은 이 provider 가 맡지 않는다. 기계용 credential 하나뿐이다.

    def start_login(self, *, redirect_uri: str) -> LoginStart:
        raise NotImplementedError(
            "ProfileApiProvider 는 기계용 credential 이라 로그인 흐름이 없다."
        )

    def complete_login(
        self, *, code: str, state: str, code_verifier: str, redirect_uri: str
    ) -> Session:
        raise NotImplementedError(
            "ProfileApiProvider 는 기계용 credential 이라 로그인 흐름이 없다."
        )

    def verify_session(self, *, access_token: str) -> Optional[Session]:
        # 쿠키 검사 반복문이 이 provider 도 부른다. 세션을 만든 적이 없으므로 없다고 답한다.
        return None

    def refresh_session(self, *, refresh_token: str) -> Session:
        raise NotImplementedError(
            "ProfileApiProvider 는 기계용 credential 이라 세션이 없다."
        )

    def revoke_session(self, *, refresh_token: str) -> None:
        return None


def _install_gate() -> bool:
    """`token_auth_middleware` 를 감싼다. 감싸지 못하면 False 를 돌려준다."""
    try:
        from hermes_cli.dashboard_auth import token_auth as seam
    except Exception:
        logger.exception("dashboard-profile-api: token_auth 를 읽어 오지 못했다")
        return False

    original = getattr(seam, "token_auth_middleware", None)
    if original is None:
        logger.error("dashboard-profile-api: token_auth_middleware 가 없다")
        return False
    if getattr(original, "_fos_profile_api", False):
        return True

    async def machine_gate(request, call_next, check=None, record_created=False):
        principal, _ = seam.authenticate_token(request)
        if principal is None or getattr(principal, "provider", None) != ProfileApiProvider.name:
            # 기계가 아니거나 토큰이 틀렸다. 쿠키 검사가 판정하게 그대로 넘긴다.
            return await call_next(request)

        request.state.token_principal = principal
        request.state.token_authenticated = True

        if check is not None:
            rejected = await check(request)
            if rejected is not None:
                return rejected

        before = None
        if record_created:
            before = _profile_names()
            if before is None:
                return _rejected("profile 목록을 읽지 못해 만들지 않았다", 500)

        response = await call_next(request)
        if request.url.path == "/api/env" and response.status_code < 400:
            body = await _json_object(request)
            if body and body.get("key") in ACCOUNTBOOK_ENV_KEYS:
                from hermes_cli.profiles import get_profile_dir
                from starlette.responses import JSONResponse
                state_path = get_profile_dir(body["profile"]) / CONNECTOR_STATE
                installed = state_path.exists() and "fos-accountbook" in json.loads(state_path.read_text(encoding="utf-8"))
                if installed and body["key"] == "ACCOUNTBOOK_FAMILY_UUID":
                    try:
                        # 선택하지 않은 가족의 명시적 빈 값도 갱신한다. 재시작만으로는 바뀌지 않는다.
                        await asyncio.to_thread(_connector_config, get_profile_dir(body["profile"]), "fos-accountbook", True)
                    except FileExistsError:
                        return _rejected("환경 변수는 저장했지만 connector 설정과 충돌한다", 409)
                    except (ValueError, OSError, KeyError, TypeError):
                        return _rejected("환경 변수는 저장했지만 connector 설정을 갱신하지 못했다", 503)
                # 삭제와 교체는 떠 있는 stdio 자식의 env 를 바꾸지 못한다.
                return JSONResponse({"profile": body["profile"], "key": body["key"],
                                     "restart_required": bool(installed)}, status_code=200)
        if response.status_code >= 400 or not record_created:
            return response
        after = _profile_names()
        if after is None:
            return _rejected("profile 을 만들었지만 목록을 읽지 못했다. 사람이 확인한다", 500)
        # 틀을 쓰고 지우는 일은 파일과 프로세스를 다뤄 오래 걸릴 수 있다. 이벤트 루프를 막지 않는다.
        if not await asyncio.to_thread(_provision, after - before):
            return _rejected("새 profile 에 안전한 설정 틀을 쓰지 못해 지웠다", 500)
        return response

    async def token_auth_middleware(request, call_next):
        path = request.url.path
        method = request.method.upper()

        if path == "/api/connectors" and method in {"GET", "PUT"}:
            principal, _ = seam.authenticate_token(request)
            if principal is None or getattr(principal, "provider", None) != ProfileApiProvider.name:
                return _rejected("Control Plane 토큰이 필요하다", 401)
            async with PROFILE_WRITE_LOCK:
                return await _connector_request(request)

        if method == "DELETE":
            name = _profile_segment(path)
            if name is not None:
                async with PROFILE_WRITE_LOCK:
                    return await machine_gate(request, call_next, _delete_check(name))

        if method in SOUL_METHODS and _profile_segment(path, SOUL_SUFFIX) is not None:
            return await machine_gate(request, call_next)

        if (path, method) in ALLOWED_ROUTES:
            check_name = ALLOWED_ROUTES[(path, method)]
            check = globals()[check_name] if check_name else None
            async with PROFILE_WRITE_LOCK:
                return await machine_gate(
                    request, call_next, check, path == PROFILES_PATH and method == "POST"
                )

        # 우리가 여는 자리가 아니다. drain plugin 이 등록한 경로가 여기로 간다.
        return await original(request, call_next)

    token_auth_middleware._fos_profile_api = True
    seam.token_auth_middleware = token_auth_middleware
    logger.info("dashboard-profile-api: token_auth_middleware 를 감쌌다")
    return True


def register(ctx) -> None:
    """비밀값이 있고 충분히 강할 때만 provider 를 등록하고 미들웨어를 감싼다."""
    secret = os.environ.get(ENV_VAR, "").strip()
    if not secret:
        logger.info(
            "dashboard-profile-api: %s 가 없어 profile 관리 경로를 열지 않는다", ENV_VAR
        )
        return

    try:
        from plugins.dashboard_auth.drain import assess_secret_strength
    except Exception:
        logger.exception(
            "dashboard-profile-api: 엔트로피 판정 함수를 읽어 오지 못해 열지 않는다"
        )
        return

    reason = assess_secret_strength(secret)
    if reason is not None:
        logger.warning(
            "dashboard-profile-api: %s 를 거절한다. %s. profile 관리 경로는 닫힌 채로 둔다",
            ENV_VAR, reason,
        )
        return

    if not _install_gate():
        logger.error(
            "dashboard-profile-api: 미들웨어를 감싸지 못해 provider 를 등록하지 않는다"
        )
        return

    ctx.register_dashboard_auth_provider(ProfileApiProvider(secret=secret))

    opened = {}
    for path, method in ALLOWED_ROUTES:
        opened.setdefault(path, []).append(method)
    opened["/api/connectors"] = ["GET", "PUT"]
    logger.info(
        "dashboard-profile-api: %s 를 토큰으로 연다. 스킬 루트는 %s 다",
        ", ".join(
            ["%s %s" % (" ".join(sorted(methods)), path) for path, methods in sorted(opened.items())]
            + ["GET PUT /api/profiles/<이름>/soul", "DELETE /api/profiles/<관리 표식 profile>"]
        ),
        _skill_root() or "설정되지 않음",
    )
