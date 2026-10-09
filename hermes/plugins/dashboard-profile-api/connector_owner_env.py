"""커넥터가 선언한 주인 env 를 검증한다.

주인 env 는 바인딩 설치가 그 에이전트 주인의 값으로 채우는 env 이름이다.
첨부 디렉터리, 커넥터 출력 디렉터리, 브라우저 중계 주소가 있고, 브라우저 로그인 안내 주소도 여기서 본다.
"""

from __future__ import annotations

from .common import BASE_ENV_KEYS

from .connector_schema import (
    OWNER_ATTACHMENTS_ENV_RE,
    OWNER_BROWSER_LOGIN_URL_MAX_CHARS,
)


def _connector_owner_env(declared: dict, field_env: set, operator_env: list):
    """`connector.json` 의 주인 env 선언을 읽는다. 틀리면 예외다.

    `(owner_attachments_env, owner_output_env, owner_browser_env, owner_browser_login_url, owner_env)` 를 낸다.
    `owner_env` 는 선언한 주인 env 이름의 집합이다.
    """
    # 사용자 첨부를 읽는 커넥터가 받을 env 이름이다. 값은 바인딩 설치가 그 에이전트 주인의 디렉터리로 넣는다(ADR-20261007 connector-owner-attachments).
    owner_attachments_env = declared.get("owner_attachments_env")
    if owner_attachments_env is not None and (
            not isinstance(owner_attachments_env, str) or not OWNER_ATTACHMENTS_ENV_RE.match(owner_attachments_env)
            or owner_attachments_env in BASE_ENV_KEYS or owner_attachments_env in field_env
            or owner_attachments_env in operator_env):
        raise ValueError("owner_attachments_env 는 칸과 운영자 env 와 겹치지 않는 env 이름 하나다")
    owner_env = {owner_attachments_env} if owner_attachments_env is not None else set()
    # 목록 도구가 계산할 데이터를 파일로 쓸 디렉터리를 받는 env 이름이다. 값은 바인딩 설치가 넣는다(ADR-20261008 connector-output-files).
    owner_output_env = declared.get("owner_output_env")
    if owner_output_env is not None and (
            not isinstance(owner_output_env, str) or not OWNER_ATTACHMENTS_ENV_RE.match(owner_output_env)
            or owner_output_env in BASE_ENV_KEYS or owner_output_env in field_env
            or owner_output_env in operator_env or owner_output_env in owner_env):
        raise ValueError("owner_output_env 는 칸과 운영자 env, owner_attachments_env 와 겹치지 않는 env 이름 하나다")
    if owner_output_env is not None:
        owner_env.add(owner_output_env)
    # 사용자 브라우저 중계 주소를 받는 env 이름이다. 값은 바인딩 설치와 확인 호출이 넣는다(ADR-20261008 browser-gateway-token).
    owner_browser_env = declared.get("owner_browser_env")
    if owner_browser_env is not None and (
            not isinstance(owner_browser_env, str) or not OWNER_ATTACHMENTS_ENV_RE.match(owner_browser_env)
            or owner_browser_env in BASE_ENV_KEYS or owner_browser_env in field_env
            or owner_browser_env in operator_env or owner_browser_env in owner_env):
        raise ValueError("owner_browser_env 는 칸과 운영자 env, owner_attachments_env, owner_output_env 와 "
                         "겹치지 않는 env 이름 하나다")
    if owner_browser_env is not None:
        owner_env.add(owner_browser_env)
    # 사용자가 브라우저에서 먼저 로그인할 곳이다. 화면이 안내에만 쓴다.
    owner_browser_login_url = declared.get("owner_browser_login_url")
    if owner_browser_login_url is not None and (
            owner_browser_env is None or not isinstance(owner_browser_login_url, str)
            or not owner_browser_login_url.startswith("https://")
            or len(owner_browser_login_url) > OWNER_BROWSER_LOGIN_URL_MAX_CHARS
            or any(ch.isspace() or ord(ch) < 0x20 or 0x7f <= ord(ch) <= 0x9f for ch in owner_browser_login_url)):
        raise ValueError("owner_browser_login_url 은 owner_browser_env 가 있을 때만 받는 https 주소다")
    return owner_attachments_env, owner_output_env, owner_browser_env, owner_browser_login_url, owner_env
