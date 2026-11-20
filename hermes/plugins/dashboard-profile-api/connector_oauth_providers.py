"""플러그인 OAuth 연결의 제공자 표와 `assistant.json` 의 `oauth` 선언 검사다.

제공자 주소와 오류 낱말은 플러그인 선언이 아니라 이 표가 갖는다(docs/adr/ADR-20261120-connector-oauth.md).
테스트가 주소를 가짜 서버로 바꿀 수 있게 표를 모듈 변수로 둔다. 표준 라이브러리만 쓴다.
"""

from __future__ import annotations

from .common import BASE_ENV_KEYS
from .connector_schema import ENV_NAME_RE

# 실행기가 자기 일에 쓰는 env 앞머리다. 플러그인 env 이름으로 받지 않는다(connector_launch.py 의 LAUNCHER_ENV_PREFIX).
_LAUNCHER_PREFIX = "FOS_ASSISTANT_CONNECTOR_VAULT_"

PROVIDERS: dict[str, dict] = {
    "github": {
        # 앱을 플러그인마다 하나씩 등록한다(스펙 DECISION9). 권한은 앱 등록이 정해 scope 를 받지 않는다.
        "app_scope": "plugin",
        "accepts_scopes": False,
        "install_url": "https://github.com/apps/{slug}/installations/new",
        "authorize_url": "https://github.com/login/oauth/authorize",
        # 오류도 HTTP 200 본문의 `error` 로 오고, `Accept: application/json` 이 없으면 폼 글로 답한다.
        "token_url": "https://github.com/login/oauth/access_token",
        "revoke_token_url": "https://api.github.com/applications/{client_id}/token",
        "revoke_grant_url": "https://api.github.com/applications/{client_id}/grant",
        "refresh_rotates": True,
        "grant_errors": frozenset({"bad_refresh_token", "bad_verification_code"}),
        "app_errors": frozenset({"incorrect_client_credentials", "redirect_uri_mismatch"}),
        "settings_url": "https://github.com/settings/apps/authorizations",
        "install_settings_url": "https://github.com/settings/installations",
    },
    "google": {
        # 모든 Google 플러그인이 앱 하나를 함께 쓴다.
        "app_scope": "shared",
        "accepts_scopes": True,
        "install_url": None,
        "authorize_url": "https://accounts.google.com/o/oauth2/v2/auth",
        "token_url": "https://oauth2.googleapis.com/token",
        "revoke_url": "https://oauth2.googleapis.com/revoke",
        "refresh_rotates": False,
        "grant_errors": frozenset({"invalid_grant"}),
        "app_errors": frozenset({"invalid_client", "unauthorized_client", "redirect_uri_mismatch"}),
        "settings_url": "https://myaccount.google.com/permissions",
        "install_settings_url": None,
    },
}


def _normalize_oauth(declared_oauth, fields: list[dict], taken_env: set, remote: bool) -> dict:
    """`oauth` 선언을 검사해 `{provider, scopes, fields, token_env}` 로 맞춘다. 틀리면 까닭을 담은 ValueError 다.

    `taken_env` 는 칸 env, 운영자 env, 주인 env 의 합이다. 규칙은 docs/features/connector-oauth/design.md 의 「선언」 이 갖는다.
    """
    if remote:
        raise ValueError("원격 MCP 플러그인은 oauth 를 선언하지 못한다")
    if not isinstance(declared_oauth, dict) or set(declared_oauth) - {"provider", "scopes", "fields", "token_env"}:
        raise ValueError("oauth 는 provider, scopes, fields, token_env 만 받는 객체다")
    provider = PROVIDERS.get(declared_oauth.get("provider")) if isinstance(declared_oauth.get("provider"), str) else None
    if provider is None:
        raise ValueError("oauth.provider 가 제공자 표에 없다")
    scopes = declared_oauth.get("scopes")
    if provider["accepts_scopes"]:
        if (not isinstance(scopes, list) or not scopes
                or any(not isinstance(scope, str) or not scope or " " in scope for scope in scopes)
                or len(set(scopes)) != len(scopes)):
            raise ValueError("oauth.scopes 는 비지 않은 scope 목록이다")
    elif scopes is not None:
        raise ValueError("이 제공자는 oauth.scopes 를 받지 않는다")
    secret_keys = {field["key"] for field in fields if field.get("secret") is True}
    keys = declared_oauth.get("fields")
    if (not isinstance(keys, list) or not keys or any(not isinstance(key, str) for key in keys)
            or len(set(keys)) != len(keys) or set(keys) - secret_keys):
        raise ValueError("oauth.fields 는 비밀 칸 key 의 비지 않은 목록이다")
    token_env = declared_oauth.get("token_env")
    if (not isinstance(token_env, str) or not ENV_NAME_RE.match(token_env) or token_env in taken_env
            or token_env in BASE_ENV_KEYS or token_env.startswith(_LAUNCHER_PREFIX)):
        raise ValueError("oauth.token_env 는 다른 env 와 겹치지 않는 env 이름이다")
    return {"provider": declared_oauth["provider"], "scopes": list(scopes) if scopes else [],
            "fields": list(keys), "token_env": token_env}


def _catalog_oauth(oauth: dict) -> dict:
    """카탈로그 응답에 내는 `oauth` 다. 제공자의 앱 단위와 설정 화면 주소를 더한다."""
    provider = PROVIDERS[oauth["provider"]]
    return {**oauth, "app_scope": provider["app_scope"], "settings_url": provider["settings_url"],
            "install_settings_url": provider["install_settings_url"]}
