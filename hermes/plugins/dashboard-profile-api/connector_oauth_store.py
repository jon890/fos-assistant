"""OAuth 파일과 대기 파일, 보관 파일 id 마다의 잠금이다(docs/features/connector-oauth/design.md 의 「OAuth 파일」).

보관 파일 `<id>.json` 옆에 세 파일을 둔다.

- `<id>.oauth`: refresh token 과 토큰 통로 주소. 실행기 정의의 리비전에 들어가지 않아 바뀌어도 재시작 대기가 아니다
- `<id>.oauth.pending`: 단추 흐름 하나의 대기 OAuth 파일. `nonce` 가 그 흐름을 가리킨다
- `<id>.pending`: 단추 밖 칸 값을 담은 대기 보관 파일. 보관 파일과 같은 형식이다

잠금 순서는 보관 파일 잠금이 먼저, 전역 `PROFILE_WRITE_LOCK` 이 나중이다. 값과 경로, 토큰은 로그와 예외에 싣지 않는다.
"""

from __future__ import annotations

import asyncio
import hmac
import json
import os
import pathlib
import secrets
import time

from .common import logger
from .connector_vault import _durable_private_write, _vault_bytes, _vault_dir, _write_vault
from .connector_vault_format import (
    VAULT_ID_RE,
    _oauth_aad,
    _parse_oauth,
    _parse_vault,
    _unseal_oauth,
    _unseal_vault,
)
from .connector_vault_key import VaultKeyUnavailable, _seal, _vault_keys

# 대기 파일을 남겨 두는 시간(초)이다. 단추 흐름의 상태 값 수명과 같다.
PENDING_MAX_AGE = 600
OAUTH_SUFFIX = ".oauth"
OAUTH_PENDING_SUFFIX = ".oauth.pending"
VAULT_PENDING_SUFFIX = ".pending"

VAULT_LOCKS: dict[str, asyncio.Lock] = {}


class NonceMismatch(Exception):
    """대기 파일이 없거나 다른 흐름의 것이다. 나중에 시작한 흐름이 이긴다(스펙 DECISION16)."""


def vault_lock(vault: str) -> asyncio.Lock:
    """보관 파일 id 하나의 잠금이다. 전역 잠금보다 먼저 잡는다."""
    return VAULT_LOCKS.setdefault(vault, asyncio.Lock())


def _side_path(vault: str, suffix: str) -> pathlib.Path:
    """보관 파일 옆 파일의 경로다. 디렉터리나 파일이 링크이면 예외다."""
    if not VAULT_ID_RE.match(vault):
        raise ValueError("보관 파일 이름이 올바르지 않다")
    directory = _vault_dir()
    path = directory / (vault + suffix)
    if directory.is_symlink() or path.is_symlink():
        raise ValueError("보관 파일 경로에 심볼릭 링크가 있다")
    return path


def _oauth_bytes(vault: str, connector: str, data: dict, keys) -> bytes:
    """OAuth 파일 글이다. key 가 준비됐으면 활성 key 의 v2 이고, 아니면 v1 평문이다."""
    if keys.ready:
        plain = json.dumps(data, sort_keys=True, ensure_ascii=False).encode("utf-8")
        stored = {"v": 2, "connector": connector, "key_id": keys.active_id,
                  "sealed": _seal(keys.keys[keys.active_id], plain, _oauth_aad(vault, connector))}
    else:
        stored = {"v": 1, "connector": connector, "oauth": data}
    return (json.dumps(stored, sort_keys=True, ensure_ascii=False) + "\n").encode("utf-8")


def _writable_keys():
    """쓰기에 쓸 key 다. 「key 필수」 인데 key 를 쓸 수 없으면 `VaultKeyUnavailable` 이다."""
    keys = _vault_keys()
    if not keys.ready and keys.required:
        raise VaultKeyUnavailable()
    if not keys.ready:
        logger.warning("dashboard-profile-api: 보관 파일 key 가 %s 라 OAuth 파일을 평문으로 쓴다", keys.state)
    return keys


def _read_oauth(vault: str, pending: bool = False, keys=None) -> dict | None:
    """OAuth 파일을 풀어 `{"connector", **OAuth 칸}` 으로 돌려준다. 없으면 None, 풀지 못하면 `ValueError` 다."""
    path = _side_path(vault, OAUTH_PENDING_SUFFIX if pending else OAUTH_SUFFIX)
    if not path.exists():
        return None
    opened = _unseal_oauth(vault, _parse_oauth(path.read_bytes()), keys if keys is not None else _vault_keys())
    return {"connector": opened["connector"], **opened["oauth"]}


def _write_oauth(vault: str, connector: str, data: dict, pending: bool = False) -> None:
    """OAuth 파일을 600 으로 원자적으로 쓴다."""
    keys = _writable_keys()
    path = _side_path(vault, OAUTH_PENDING_SUFFIX if pending else OAUTH_SUFFIX)
    path.parent.mkdir(mode=0o700, exist_ok=True)
    os.chmod(path.parent, 0o700)
    _durable_private_write(path, _oauth_bytes(vault, connector, data, keys))


def _read_pending_values(vault: str, keys=None) -> dict | None:
    """대기 보관 파일의 `{"connector", "values"}` 다. 없으면 None 이다."""
    path = _side_path(vault, VAULT_PENDING_SUFFIX)
    if not path.exists():
        return None
    stored = _parse_vault(path.read_bytes())
    if stored["v"] != 1:
        stored = _unseal_vault(vault, stored, keys if keys is not None else _vault_keys())
    return {"connector": stored["connector"], "values": stored["values"]}


def _owner_matches(vault: str, connector: str) -> None:
    """본 자리 보관 파일이 있으면 같은 플러그인의 것이어야 한다. 아니면 `FileExistsError` 다."""
    path = _side_path(vault, ".json")
    if path.exists():
        try:
            owner = _parse_vault(path.read_bytes())["connector"]
        except ValueError:
            return
        if owner != connector:
            raise FileExistsError("다른 플러그인의 보관 파일이다")


def _pending_with(vault: str, nonce: str) -> dict:
    """그 `nonce` 의 대기 OAuth 파일이다. 없거나 다르면 `NonceMismatch` 다."""
    pending = _read_oauth(vault, pending=True)
    if pending is None or not hmac.compare_digest(pending.get("nonce", ""), nonce):
        raise NonceMismatch()
    return pending


def _begin(vault: str, connector: str, values: dict, provider: str) -> str:
    """대기 보관 파일과 토큰 없는 대기 OAuth 파일을 쓰고 새 `nonce` 를 돌려준다. 앞 흐름의 대기 파일은 덮는다."""
    _sweep_pending()
    _owner_matches(vault, connector)
    keys = _writable_keys()
    nonce = secrets.token_urlsafe(24)
    path = _side_path(vault, VAULT_PENDING_SUFFIX)
    path.parent.mkdir(mode=0o700, exist_ok=True)
    os.chmod(path.parent, 0o700)
    _durable_private_write(path, _vault_bytes(vault, connector, values, keys))
    _write_oauth(vault, connector, {"provider": provider, "refresh_token": "", "granted_scopes": [],
                                    "token_url": "", "nonce": nonce}, pending=True)
    return nonce


def _fill_pending(vault: str, nonce: str, data: dict) -> None:
    """그 `nonce` 의 대기 OAuth 파일에 교환 결과(refresh token, 받은 scope, 통로 주소)를 채운다."""
    pending = _pending_with(vault, nonce)
    connector = pending.pop("connector")
    _write_oauth(vault, connector, {**pending, **data, "nonce": nonce}, pending=True)


def _commit(vault: str, nonce: str, token_url: str) -> None:
    """대기 파일 둘을 본 자리로 옮긴다. 보관 파일은 `_write_vault` 로 다시 써 값이 같으면 파일과 리비전이 그대로다.

    본 자리 OAuth 파일의 통로 주소는 `token_url`(본 연결 표식 `k...`)이다. 대기 파일의 주소는 대기 표식(`p...`)이라
    그대로 옮기면 흐름이 끝난 뒤 토큰을 받지 못한다(docs/features/connector-oauth/design.md 의 「Control Plane API」).
    OAuth 파일을 먼저 쓰고 보관 파일을 쓴다. 보관 파일 쓰기가 실패하면 OAuth 파일을 앞 상태로 되돌려
    붙여 넣기로 쓰던 연결의 칸 값과 OAuth 파일이 함께 사라지지 않게 한다.
    """
    if not token_url:
        raise ValueError("본 연결 통로 주소가 없다")
    pending = _pending_with(vault, nonce)
    values = _read_pending_values(vault)
    if not pending["refresh_token"] or values is None or values["connector"] != pending["connector"]:
        raise NonceMismatch()
    connector = pending.pop("connector")
    pending.pop("nonce")
    pending["token_url"] = token_url
    path = _side_path(vault, OAUTH_SUFFIX)
    previous = path.read_bytes() if path.exists() else None
    _write_oauth(vault, connector, pending)
    try:
        _write_vault(vault, connector, values["values"])
    except BaseException:
        if previous is None:
            path.unlink(missing_ok=True)
        else:
            _durable_private_write(path, previous)
        raise
    _discard_files(vault)


def _discard(vault: str, nonce: str) -> None:
    """그 `nonce` 의 대기 파일을 지운다. 다른 흐름의 대기 파일이면 `NonceMismatch` 로 두고, 없으면 아무 일도 하지 않는다."""
    if _side_path(vault, OAUTH_PENDING_SUFFIX).exists():
        _pending_with(vault, nonce)
    _discard_files(vault)


def _discard_files(vault: str) -> None:
    for suffix in (OAUTH_PENDING_SUFFIX, VAULT_PENDING_SUFFIX):
        _side_path(vault, suffix).unlink(missing_ok=True)


def _has_oauth(vault: str) -> bool:
    """본 자리 OAuth 파일이 있는가."""
    return _side_path(vault, OAUTH_SUFFIX).exists()


def _delete_oauth(vault: str) -> bool:
    """본 자리 OAuth 파일만 지운다(붙여 넣기로 바꾸기, 스펙 REQ9). 지웠으면 참이다."""
    path = _side_path(vault, OAUTH_SUFFIX)
    existed = path.exists()
    path.unlink(missing_ok=True)
    return existed


def _delete_all(vault: str) -> None:
    """보관 파일 빼고 OAuth 파일과 대기 파일을 모두 지운다. 해제가 취소를 건너뛰어도 refresh token 이 남지 않는다."""
    _delete_oauth(vault)
    _discard_files(vault)


def _sweep_pending(max_age: int = PENDING_MAX_AGE) -> int:
    """`max_age` 초보다 오래된 대기 파일을 지운다. 지운 수를 돌려준다. 예외를 밖으로 내지 않는다."""
    directory = _vault_dir()
    if directory.is_symlink() or not directory.is_dir():
        return 0
    removed = 0
    cutoff = time.time() - max_age
    for entry in directory.iterdir():
        name = entry.name
        if not name.endswith(VAULT_PENDING_SUFFIX) or not VAULT_ID_RE.match(name.split(".", 1)[0]):
            continue
        try:
            if not entry.is_symlink() and entry.stat().st_mtime < cutoff:
                entry.unlink()
                removed += 1
        except OSError as error:
            logger.warning("dashboard-profile-api: 대기 파일을 지우지 못했다: %s", type(error).__name__)
    return removed
