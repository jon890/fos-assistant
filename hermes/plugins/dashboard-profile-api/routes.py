"""## 경로를 등록하지 않는 이유

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
"""

from __future__ import annotations

import asyncio
import hmac
import json
import re
from typing import Optional
from hermes_cli.dashboard_auth import (
    DashboardAuthProvider,
    LoginStart,
    Session,
    TokenPrincipal,
)
from .common import (
    _json_object,
    _rejected,
    logger,
)

from .connector_install import (
    PROBE_ROUTE_RE,
    _check_connector_probe,
    _connector_config,
    _connector_request,
)

from .connector_manifest import (
    BIND_MODE,
    CONNECTOR_STATE,
    _connector_catalog,
    _connector_catalog_response,
    _entry_mode,
)

from .connector_run import (
    _connector_call_request,
    _connector_execute_request,
)

from .connector_vault import (
    VAULT_ROUTES,
    _connector_vault_request,
)

from .env import (
    _check_env_delete,
    _check_env_update,
)

from .profiles import (
    _check_profile_create,
    _check_skill_toggle,
    _check_skills_list,
    _decision_readiness_response,
    _delete_check,
    _model_defaults_response,
    _profile_names,
    _provision,
)

from .sandbox import (
    SandboxAttachmentError,
    _sandbox_unavailable,
    _sandbox_validate_attachment_snapshot,
)

from .session import (
    _session_provider_response,
)

from .toolconfig import (
    _check_config_update,
    _restore_config,
    _write_checked_config,
)



# 토큰으로 인증할 요청이다. 여기 없는 것은 모두 쿠키 검사로 넘어간다.
# 값은 그 요청에서 먼저 돌릴 검사 함수의 이름이다. None 은 토큰만 본다.
ALLOWED_ROUTES = {
    ("/api/profiles", "GET"): None,
    ("/api/profiles", "POST"): "_check_profile_create",
    ("/api/env", "PUT"): "_check_env_update",
    ("/api/env", "DELETE"): "_check_env_delete",
    ("/api/tools/toolsets", "GET"): None,
    ("/api/config", "PUT"): "_check_config_update",
    ("/api/skills", "GET"): "_check_skills_list",
    ("/api/skills/toggle", "PUT"): "_check_skill_toggle",
}
CONNECTORS_PATH = "/api/connectors"
CATALOG_PATH = "/api/connectors/catalog"
CALL_ROUTE_RE = re.compile(r"^/api/connectors/([^/]+)/call$")
EXECUTE_ROUTE_RE = re.compile(r"^/api/connectors/([^/]+)/execute$")
MODEL_DEFAULTS_RE = re.compile(r"^/api/profiles/([^/]+)/model-defaults$")
DECISION_READINESS_RE = re.compile(r"^/api/profiles/([^/]+)/decision-readiness$")
# native 하위 에이전트가 쓴 자식 session 의 provider 를 읽는 경로다(ADR-067).
SESSION_PROVIDER_RE = re.compile(r"^/api/profiles/([^/]+)/sessions/([^/]+)/provider$")

PROFILES_PATH = "/api/profiles"
PROFILE_PREFIX = "/api/profiles/"
SOUL_SUFFIX = "/soul"
SOUL_METHODS = frozenset({"GET", "PUT"})
PROFILE_WRITE_LOCK = asyncio.Lock()


def _profile_segment(path: str, suffix: str = "") -> Optional[str]:
    """`/api/profiles/<이름><suffix>` 의 이름이다. 한 단계 아래가 아니면 None 이다."""
    if not path.startswith(PROFILE_PREFIX) or not path.endswith(suffix):
        return None
    name = path[len(PROFILE_PREFIX):len(path) - len(suffix)]
    return name if name and "/" not in name else None


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

        checked = getattr(request.state, "fos_checked_config", None)
        attachment_guard = getattr(request.state, "fos_checked_attachments", None)
        written = None
        if attachment_guard is not None:
            try:
                _sandbox_validate_attachment_snapshot(*attachment_guard)
            except SandboxAttachmentError:
                return _sandbox_unavailable()
        if checked is not None:
            try:
                written = await asyncio.to_thread(_write_checked_config, *checked, attachment_guard)
            except SandboxAttachmentError:
                return _sandbox_unavailable()
            except FileExistsError:
                return _rejected("검사 뒤 profile 설정이 밖에서 바뀌었다", 409)
            except Exception:
                logger.exception("dashboard-profile-api: 검사한 profile 설정을 쓰지 못했다")
                return _rejected("profile 설정을 쓰지 못했다", 500)

        before = None
        if record_created:
            before = _profile_names()
            if before is None:
                return _rejected("profile 목록을 읽지 못해 만들지 않았다", 500)

        try:
            response = await call_next(request)
        except BaseException:
            if written is not None:
                _restore_config(checked[0], checked[1], written)
            raise
        if written is not None and response.status_code >= 400:
            _restore_config(checked[0], checked[1], written)
        if request.url.path == "/api/env" and response.status_code < 400:
            body = await _json_object(request)
            # 그 key 를 칸으로 가진 커넥터다. 기본 key 는 여기 걸리지 않는다.
            owners = [manifest for manifest in _connector_catalog().values()
                      if body and any(field["env"] == body.get("key") for field in manifest["fields"])]
            if owners:
                from hermes_cli.profiles import get_profile_dir
                from starlette.responses import JSONResponse
                state_path = get_profile_dir(body["profile"]) / CONNECTOR_STATE
                state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {}
                installed = [manifest for manifest in owners if manifest["id"] in state]
                for manifest in installed:
                    # 바인딩 설치의 env 는 설치 묶음이 쓰므로 옛 설치만 다시 쓴다.
                    if (body["key"] not in manifest["optional_env"]
                            or (isinstance(state, dict) and _entry_mode(state[manifest["id"]]) == BIND_MODE)):
                        continue
                    try:
                        # 비운 선택 칸의 명시적 빈 값도 갱신한다. 재시작만으로는 바뀌지 않는다.
                        await asyncio.to_thread(
                            _connector_config, get_profile_dir(body["profile"]), manifest["id"], True)
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

        decision_match = DECISION_READINESS_RE.match(path) if method == "GET" else None
        if decision_match is not None:
            principal, _ = seam.authenticate_token(request)
            if principal is None or getattr(principal, "provider", None) != ProfileApiProvider.name:
                return _rejected("Control Plane 토큰이 필요하다", 401)
            return await asyncio.to_thread(_decision_readiness_response, decision_match.group(1))

        defaults_match = MODEL_DEFAULTS_RE.match(path) if method == "GET" else None
        if defaults_match is not None:
            principal, _ = seam.authenticate_token(request)
            if principal is None or getattr(principal, "provider", None) != ProfileApiProvider.name:
                return _rejected("Control Plane 토큰이 필요하다", 401)
            return await asyncio.to_thread(_model_defaults_response, defaults_match.group(1))

        provider_match = SESSION_PROVIDER_RE.match(path) if method == "GET" else None
        if provider_match is not None:
            principal, _ = seam.authenticate_token(request)
            if principal is None or getattr(principal, "provider", None) != ProfileApiProvider.name:
                return _rejected("Control Plane 토큰이 필요하다", 401)
            return await asyncio.to_thread(
                _session_provider_response, provider_match.group(1), provider_match.group(2))

        if (path, method) in VAULT_ROUTES:
            principal, _ = seam.authenticate_token(request)
            if principal is None or getattr(principal, "provider", None) != ProfileApiProvider.name:
                return _rejected("Control Plane 토큰이 필요하다", 401)
            # 바인딩 설치가 보관 파일을 읽는 것과 같은 잠금 안에서 쓰고 지운다.
            async with PROFILE_WRITE_LOCK:
                return await _connector_vault_request(request)

        call = CALL_ROUTE_RE.match(path) if method == "POST" else None
        execute = EXECUTE_ROUTE_RE.match(path) if method == "POST" else None
        if ((path == CONNECTORS_PATH and method in {"GET", "PUT"})
                or (path == CATALOG_PATH and method == "GET") or call is not None or execute is not None):
            principal, _ = seam.authenticate_token(request)
            if principal is None or getattr(principal, "provider", None) != ProfileApiProvider.name:
                return _rejected("Control Plane 토큰이 필요하다", 401)
            if path == CATALOG_PATH:
                return _connector_catalog_response()
            if call is not None:
                # profile 쓰기 잠금 밖에서 돈다. 안에서 돌면 도구를 기다리는 동안 모든 profile 요청이 멈춘다.
                return await _connector_call_request(request, call.group(1))
            if execute is not None:
                # `call` 과 같은 까닭으로 profile 쓰기 잠금 밖에서 돈다.
                return await _connector_execute_request(request, execute.group(1))
            async with PROFILE_WRITE_LOCK:
                return await _connector_request(request)

        if method == "POST" and PROBE_ROUTE_RE.match(path):
            async with PROFILE_WRITE_LOCK:
                return await machine_gate(request, call_next, _check_connector_probe)

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
