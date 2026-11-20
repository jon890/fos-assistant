"""Control Plane 이 서비스 토큰으로 부르는 `connector-oauth` 경로와 제공자 HTTP 다.

경로의 뜻은 docs/features/connector-oauth/design.md 의 「대시보드 plugin 경로(서비스 토큰)」 가, 취소 범위는 같은 문서의
「취소 범위」 가 갖는다. 제공자마다 다른 주소와 오류 낱말은 `connector_oauth_providers.py` 의 표에서 읽는다.

client secret 은 요청 본문으로만 받고 파일과 로그에 쓰지 않는다. 서비스 응답 원문과 토큰도 로그에 쓰지 않는다.
로그에는 경로, 플러그인 id, `outcome` 과 까닭 낱말만 둔다. 서비스 HTTP 는 표준 라이브러리로 하고 스레드에서 부른다.
"""

from __future__ import annotations

import asyncio
import base64
import json
import time
import urllib.error
import urllib.parse
import urllib.request

from .common import _json_object, _rejected, logger
from .connector_manifest import CONNECTOR_ID_RE, _connector_manifest, _connector_roots
from .connector_oauth_providers import PROVIDERS
from .connector_oauth_store import (
    NonceMismatch,
    _begin,
    _commit,
    _discard,
    _fill_pending,
    _pending_with,
    _read_oauth,
    _write_oauth,
    vault_lock,
)
from .connector_vault import _vault_values
from .connector_vault_format import VAULT_ID_RE
from .connector_vault_key import VaultKeyUnavailable

OAUTH_PREFIX = "/api/connector-oauth/"
OAUTH_ACTIONS = frozenset({"authorize-url", "begin", "exchange", "commit", "discard", "refresh", "revoke", "token-url"})
# 서비스 호출 하나의 시간 제한(초)이다.
HTTP_TIMEOUT = 10
TEXT_MAX = 4096

# 경로마다 받는 본문 키다. 두 번째 집합은 선택 키다.
BODIES = {
    "authorize-url": ({"connector", "mode", "client_id", "redirect_uri", "state"}, {"app_slug", "code_challenge"}),
    "begin": ({"vault", "connector", "values"}, set()),
    "exchange": ({"vault", "connector", "nonce", "client_id", "client_secret", "code", "redirect_uri", "token_url"},
                 {"code_verifier"}),
    "commit": ({"vault", "nonce"}, {"token_url"}),
    "discard": ({"vault", "connector", "nonce", "client_id", "client_secret"}, {"access_token"}),
    "refresh": ({"vault", "connector", "client_id", "client_secret"}, set()),
    "revoke": ({"vault", "connector", "client_id", "client_secret"}, {"access_token"}),
    "token-url": ({"vault", "token_url"}, set()),
}
URL_KEYS = frozenset({"redirect_uri", "token_url"})


def _text(value) -> bool:
    return (isinstance(value, str) and 0 < len(value) <= TEXT_MAX
            and not any(ord(ch) < 0x20 or ord(ch) == 0x7f for ch in value))


def _body_problem(action: str, body) -> str | None:
    """본문 모양이 틀렸으면 까닭이고, 맞으면 None 이다."""
    required, optional = BODIES[action]
    if body is None or not required <= set(body) <= required | optional:
        return "%s 만 필요하다" % ", ".join(sorted(required))
    for key, value in body.items():
        if key == "values":
            continue
        if key in ("access_token", "code_verifier", "code_challenge", "app_slug", "token_url") and value in (None, ""):
            continue
        if not _text(value):
            return "%s 가 올바르지 않다" % key
        if key in URL_KEYS and not value.startswith(("https://", "http://")):
            return "%s 는 http 주소다" % key
    if "vault" in body and not VAULT_ID_RE.match(body["vault"]):
        return "vault 가 올바르지 않다"
    if "connector" in body and not CONNECTOR_ID_RE.match(body["connector"]):
        return "connector 가 올바르지 않다"
    return None


def _http(method: str, url: str, form: dict | None = None, json_body: dict | None = None,
          basic: tuple[str, str] | None = None) -> tuple[int | None, dict | None]:
    """서비스에 요청 하나를 보낸다. `(상태 코드, JSON 본문)` 이고, 연결 실패나 시간 초과면 `(None, None)` 이다."""
    headers = {"Accept": "application/json"}
    data = None
    if form is not None:
        data = urllib.parse.urlencode(form).encode("utf-8")
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif json_body is not None:
        data = json.dumps(json_body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if basic is not None:
        headers["Authorization"] = "Basic " + base64.b64encode(("%s:%s" % basic).encode("utf-8")).decode("ascii")
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read()
    except (urllib.error.URLError, TimeoutError, OSError):
        return None, None
    try:
        body = json.loads(raw.decode("utf-8")) if raw else {}
    except (UnicodeError, ValueError):
        body = None
    return status, body if isinstance(body, dict) else None


def _outcome(provider: dict, status: int | None, body: dict | None) -> str:
    """서비스 응답을 `outcome` 낱말로 바꾼다. GitHub 은 오류도 200 본문으로 오므로 본문의 `error` 를 먼저 본다."""
    error = body.get("error") if isinstance(body, dict) else None
    if isinstance(error, str):
        if error in provider["grant_errors"]:
            return "grant_revoked"
        if error in provider["app_errors"]:
            return "app_rejected"
        return "unavailable"
    if status is None or body is None or not 200 <= status < 300:
        return "unavailable"
    return "ok"


def _expires_at(body: dict) -> int | None:
    expires_in = body.get("expires_in")
    if type(expires_in) is not int or expires_in <= 0:
        return None
    return int(time.time()) + expires_in


def _log(action: str, connector: str, outcome: str, reason: str | None = None) -> None:
    if reason is None:
        logger.info("dashboard-profile-api: connector-oauth %s %s: %s", action, connector, outcome)
    else:
        logger.warning("dashboard-profile-api: connector-oauth %s %s: %s (%s)", action, connector, outcome, reason)


def _authorize_url(manifest: dict, body: dict) -> str | None:
    """제공자의 로그인 주소다. 모양이 틀리면 None 이다."""
    oauth = manifest["oauth"]
    provider = PROVIDERS[oauth["provider"]]
    mode = body["mode"]
    if mode == "install":
        # GitHub App 설치 화면은 `state` 만 돌려준다. PKCE challenge 를 받을 자리가 없다.
        if provider["install_url"] is None or not body.get("app_slug"):
            return None
        slug = urllib.parse.quote(body["app_slug"], safe="")
        return provider["install_url"].format(slug=slug) + "?" + urllib.parse.urlencode({"state": body["state"]})
    if mode != "authorize" or not body.get("code_challenge"):
        return None
    query = {"client_id": body["client_id"], "redirect_uri": body["redirect_uri"], "state": body["state"],
             "code_challenge": body["code_challenge"], "code_challenge_method": "S256"}
    if provider["accepts_scopes"]:
        query.update(scope=" ".join(oauth["scopes"]), access_type="offline", prompt="consent",
                     include_granted_scopes="false", response_type="code")
    return provider["authorize_url"] + "?" + urllib.parse.urlencode(query)


def _token_request(provider: dict, form: dict) -> tuple[str, dict]:
    """토큰 주소에 폼을 보낸다. `(outcome, 응답 본문)` 이다."""
    status, body = _http("POST", provider["token_url"], form=form)
    outcome = _outcome(provider, status, body)
    return outcome, body if outcome == "ok" else {}


def _refresh_locked(vault: str, connector: str, client_id: str, client_secret: str) -> dict:
    """보관 파일 잠금 안에서 부른다. 갱신하고, 바뀐 refresh token 을 OAuth 파일에 쓴 뒤 결과를 돌려준다."""
    stored = _read_oauth(vault)
    if stored is None or not stored["refresh_token"]:
        return {"outcome": "missing"}
    if stored["connector"] != connector:
        return {"outcome": "missing"}
    provider = PROVIDERS[stored["provider"]]
    outcome, body = _token_request(provider, {
        "grant_type": "refresh_token", "refresh_token": stored["refresh_token"],
        "client_id": client_id, "client_secret": client_secret})
    if outcome != "ok":
        return {"outcome": outcome}
    access = body.get("access_token")
    if not isinstance(access, str) or not access:
        return {"outcome": "unavailable"}
    rotated = body.get("refresh_token")
    if isinstance(rotated, str) and rotated and rotated != stored["refresh_token"]:
        stored.pop("connector")
        try:
            _write_oauth(vault, connector, {**stored, "refresh_token": rotated})
        except Exception as error:
            # 옛 refresh token 은 이미 무효다. access token 을 주지 않아 다음 갱신이 grant_revoked 로 드러난다.
            _log("refresh", connector, "unavailable", "rotated_write_failed:%s" % type(error).__name__)
            return {"outcome": "unavailable"}
    return {"outcome": "ok", "access_token": access, "expires_at": _expires_at(body)}


def _revoke_github(provider: dict, url_key: str, client_id: str, client_secret: str, access_token: str) -> str:
    status, body = _http("DELETE", provider[url_key].format(client_id=urllib.parse.quote(client_id, safe="")),
                         json_body={"access_token": access_token}, basic=(client_id, client_secret))
    if status is not None and 200 <= status < 300:
        return "ok"
    if status in (401, 403):
        return "app_rejected"
    if status in (404, 422):
        return "grant_revoked"
    return "unavailable"


def _revoke_locked(vault: str, connector: str, body: dict) -> str:
    stored = _read_oauth(vault)
    if stored is None or stored["connector"] != connector or not stored["refresh_token"]:
        return "missing"
    provider = PROVIDERS[stored["provider"]]
    if stored["provider"] == "github":
        access = body.get("access_token")
        if not access:
            refreshed = _refresh_locked(vault, connector, body["client_id"], body["client_secret"])
            if refreshed["outcome"] != "ok":
                return refreshed["outcome"]
            access = refreshed["access_token"]
        return _revoke_github(provider, "revoke_grant_url", body["client_id"], body["client_secret"], access)
    status, response = _http("POST", provider["revoke_url"], form={"token": stored["refresh_token"]})
    if status is not None and 200 <= status < 300:
        return "ok"
    if isinstance(response, dict) and response.get("error") == "invalid_token":
        return "grant_revoked"
    return _outcome(provider, status, response) if status is not None and status < 500 else "unavailable"


def _exchange_locked(manifest: dict, body: dict) -> dict:
    """보관 파일 잠금 안에서 부른다. 코드를 토큰으로 바꾸고 대기 OAuth 파일을 채운다."""
    vault, nonce, connector = body["vault"], body["nonce"], body["connector"]
    pending = _pending_with(vault, nonce)
    if pending["connector"] != connector:
        raise NonceMismatch()
    oauth = manifest["oauth"]
    provider = PROVIDERS[oauth["provider"]]
    form = {"grant_type": "authorization_code", "code": body["code"], "client_id": body["client_id"],
            "client_secret": body["client_secret"], "redirect_uri": body["redirect_uri"]}
    if body.get("code_verifier"):
        form["code_verifier"] = body["code_verifier"]
    outcome, response = _token_request(provider, form)
    if outcome != "ok":
        _log("exchange", connector, outcome)
        return {"outcome": outcome}
    access, refresh = response.get("access_token"), response.get("refresh_token")
    if not isinstance(access, str) or not access:
        _log("exchange", connector, "unavailable", "no_access_token")
        return {"outcome": "unavailable"}
    if not isinstance(refresh, str) or not refresh:
        # GitHub App 의 사용자 토큰 만료를 꺼 둔 경우처럼 앱 설정 문제다(설계 「대시보드 plugin 경로」).
        _log("exchange", connector, "app_rejected", "no_refresh_token")
        return {"outcome": "app_rejected"}
    granted = response.get("scope", "")
    granted = [scope for scope in granted.replace(",", " ").split() if scope] if isinstance(granted, str) else []
    if provider["accepts_scopes"] and set(oauth["scopes"]) - set(granted):
        _log("exchange", connector, "scope_denied")
        return {"outcome": "scope_denied"}
    _fill_pending(vault, nonce, {"refresh_token": refresh, "granted_scopes": granted, "token_url": body["token_url"]})
    _log("exchange", connector, "ok")
    return {"outcome": "ok", "access_token": access, "expires_at": _expires_at(response)}


def _discard_locked(manifest: dict, body: dict) -> None:
    vault, nonce = body["vault"], body["nonce"]
    pending = _read_oauth(vault, pending=True)
    if pending is not None:
        _pending_with(vault, nonce)
    provider = PROVIDERS[manifest["oauth"]["provider"]]
    # Google 은 토큰 하나를 취소해도 그 앱의 허락 전체가 사라져 다시 연결하던 기존 연결까지 끊긴다(설계 「취소 범위」).
    if manifest["oauth"]["provider"] == "github" and body.get("access_token"):
        outcome = _revoke_github(provider, "revoke_token_url", body["client_id"], body["client_secret"],
                                 body["access_token"])
        _log("discard", body["connector"], outcome)
    _discard(vault, nonce)


async def _connector_oauth_request(request, action: str, write_lock: asyncio.Lock):
    """`/api/connector-oauth/<action>` 하나를 처리한다. 토큰 검사는 부르는 쪽이 했다."""
    from starlette.responses import JSONResponse

    body = await _json_object(request)
    problem = _body_problem(action, body)
    if problem is not None:
        return _rejected(problem)
    manifest = None
    if "connector" in body:
        connector = body["connector"]
        manifest = _connector_manifest(connector) if connector in _connector_roots() else None
        if manifest is None or manifest.get("oauth") is None:
            return _rejected("단추로 연결하는 connector 가 아니다", 404)
    try:
        if action == "authorize-url":
            url = _authorize_url(manifest, body)
            if url is None:
                return _rejected("mode 와 app_slug, code_challenge 가 그 제공자와 맞지 않는다")
            return JSONResponse({"url": url}, status_code=200)
        vault = body["vault"]
        if action == "begin":
            values = _vault_values(manifest, body["values"], oauth=True)
            if values is None:
                return _rejected("칸 값이 그 connector 의 칸 선언과 맞지 않는다")
            async with vault_lock(vault):
                nonce = await asyncio.to_thread(_begin, vault, body["connector"], values, manifest["oauth"]["provider"])
            return JSONResponse({"nonce": nonce}, status_code=200)
        if action == "commit":
            # 본 보관 파일을 쓰므로 보관 파일 잠금 다음 전역 잠금을 잡는다. 서비스를 부르지 않아 짧다.
            async with vault_lock(vault), write_lock:
                await asyncio.to_thread(_commit, vault, body["nonce"], body.get("token_url"))
            _log("commit", vault, "ok")
            return JSONResponse({"ok": True}, status_code=200)
        async with vault_lock(vault):
            if action == "exchange":
                result = await asyncio.to_thread(_exchange_locked, manifest, body)
            elif action == "discard":
                await asyncio.to_thread(_discard_locked, manifest, body)
                result = {"ok": True}
            elif action == "refresh":
                result = await asyncio.to_thread(_refresh_locked, vault, body["connector"], body["client_id"],
                                                 body["client_secret"])
                _log("refresh", body["connector"], result["outcome"])
            elif action == "revoke":
                result = {"outcome": await asyncio.to_thread(_revoke_locked, vault, body["connector"], body)}
                _log("revoke", body["connector"], result["outcome"])
            else:
                result = {"outcome": await asyncio.to_thread(_replace_token_url, vault, body["token_url"])}
        return JSONResponse(result, status_code=200)
    except NonceMismatch:
        return _rejected("다른 연결 흐름이 시작됐다", 409)
    except FileExistsError:
        return _rejected("다른 connector 의 보관 파일이다", 409)
    except VaultKeyUnavailable:
        return _rejected("OAuth 파일을 다루지 못했다", 503)
    except Exception as error:
        logger.warning("dashboard-profile-api: connector-oauth %s 를 다루지 못했다: %s", action, type(error).__name__)
        return _rejected("OAuth 파일을 다루지 못했다", 503)


def _replace_token_url(vault: str, token_url: str) -> str:
    """OAuth 파일의 통로 주소만 바꾼다(서명 비밀 교체). 파일이 없으면 `missing` 이다."""
    stored = _read_oauth(vault)
    if stored is None:
        return "missing"
    connector = stored.pop("connector")
    _write_oauth(vault, connector, {**stored, "token_url": token_url})
    return "ok"
