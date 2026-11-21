"""그 profile 에 설치한 서버 정의의 env 에서 주인 값을 읽는다. 직접 실행이 쓴다(`connector_run.py` 에서 옮겼다)."""

from __future__ import annotations

import pathlib
import re

from .connector_schema import OWNER_ATTACHMENTS_VALUE_RE, OWNER_BROWSER_VALUE_RE
from .sandbox import _sandbox_policy


def _installed_server_env(profile_dir: pathlib.Path, manifest: dict) -> dict:
    """그 profile 에 설치한 서버 정의(`config.yaml` 의 `mcp_servers`)가 가진 env 다.

    `config.yaml` 이 없거나 링크이거나, 그 서버 정의나 env 가 사전이 아니면 빈 사전이다.
    """
    import yaml
    config_path = profile_dir / "config.yaml"
    if config_path.is_symlink() or not config_path.is_file():
        return {}
    config = yaml.safe_load(config_path.read_text(encoding="utf-8")) or {}
    servers = config.get("mcp_servers") if isinstance(config, dict) else None
    server = servers.get(manifest["mcp_server"]) if isinstance(servers, dict) else None
    env = server.get("env") if isinstance(server, dict) else None
    return env if isinstance(env, dict) else {}


def _installed_owner_attachments(installed_env: dict, manifest: dict) -> str:
    """설치한 서버 정의의 env(`_installed_server_env`)가 가진 주인의 첨부 디렉터리다.

    바인딩 설치가 운영 정책의 `attachment_agent_root` 와 주인으로 넣은 값만 돌려준다.
    값이 없거나, 지금 정책의 루트 아래 `users/<64자리 16진수>` 모양이 아니거나, 정책이 없으면 빈 값이다.
    빈 값을 받은 플러그인은 사용자 첨부를 읽지 않는다(ADR-20261007 connector-owner-attachments).
    """
    value = installed_env.get(manifest["owner_attachments_env"])
    if not isinstance(value, str) or not OWNER_ATTACHMENTS_VALUE_RE.match(value):
        return ""
    policy = _sandbox_policy()
    if policy is None:
        return ""
    prefix = "%s/users/" % policy["attachment_agent_root"].rstrip("/")
    key = value[len(prefix):]
    if not value.startswith(prefix) or not re.fullmatch(r"[0-9a-f]{64}", key):
        return ""
    return value


def _installed_owner_browser(installed_env: dict, manifest: dict) -> str:
    """설치한 서버 정의의 env(`_installed_server_env`)가 가진 브라우저 중계 주소다.

    바인딩 설치가 그 바인딩의 표식으로 넣은 값이다. 값이 없거나 `<gateway-base-url>/<접근 표식>` 모양이 아니면 빈 값이다.
    빈 값을 받은 플러그인은 브라우저에 닿지 못한다고 답한다(ADR-20261008 browser-gateway-token).
    """
    value = installed_env.get(manifest["owner_browser_env"])
    if not isinstance(value, str) or not OWNER_BROWSER_VALUE_RE.fullmatch(value):
        return ""
    return value
