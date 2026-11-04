from __future__ import annotations

import asyncio
import json
import os
import pathlib
import re
import tempfile
from .common import (
    _json_object,
    _rejected,
    logger,
)

from .connector_manifest import (
    CONNECTOR_ID_RE,
    _connector_manifest,
    _connector_roots,
)

from .connector_vault_key import (
    KEY_ID_RE,
    VaultKeyUnavailable,
    _open,
    _seal,
    _vault_keys,
)


# 연결의 칸 값을 두는 보관 파일의 디렉터리다. 대시보드의 Hermes 루트 아래에 둔다(ADR-083).
CONNECTOR_VAULT_DIR = "connector-vault"
# 보관 파일 이름이다. Control Plane 의 연결 id 앞에 `c` 를 붙인다. 경로 조각으로 쓰기 전에 본다.
VAULT_ID_RE = re.compile(r"^c[1-9][0-9]{0,18}\Z")
VAULT_PATH = "/api/connector-vault"
VAULT_ROUTES = frozenset({(VAULT_PATH, "PUT"), (VAULT_PATH, "DELETE")})
# 보관 파일 쓰기의 임시 파일 이름 앞부분이다. 쓰는 중에 프로세스가 죽으면 평문이 남을 수 있어 기동 이관이 지운다.
VAULT_TEMP_PREFIX = ".connector-"


def _vault_dir() -> pathlib.Path:
    """보관 파일 디렉터리다. 대시보드의 Hermes 루트 아래다."""
    from hermes_constants import get_default_hermes_root

    return pathlib.Path(get_default_hermes_root()) / CONNECTOR_VAULT_DIR


def _vault_path(vault: str) -> pathlib.Path:
    """보관 파일 경로다. 이름은 부르는 쪽이 `VAULT_ID_RE` 로 본 것이어야 한다. 디렉터리나 파일이 링크이면 예외다."""
    if not VAULT_ID_RE.match(vault):
        raise ValueError("보관 파일 이름이 올바르지 않다")
    directory = _vault_dir()
    path = directory / ("%s.json" % vault)
    if directory.is_symlink() or path.is_symlink():
        raise ValueError("보관 파일 경로에 심볼릭 링크가 있다")
    return path


def _vault_aad(vault: str, connector: str) -> bytes:
    """암호문을 그 연결 이름과 플러그인에 묶는 글이다. 파일을 다른 연결로 옮기거나 `connector` 를 바꾸면 풀리지 않는다."""
    return ("connector-vault:%s:%s" % (vault, connector)).encode("utf-8")


def _parse_vault(raw: bytes) -> dict:
    """보관 파일 글의 모양을 엄격히 본다. 풀지 않는다. 틀리면 예외다. 값은 예외 메시지에 싣지 않는다.

    v1 은 `{"v": 1, "connector", "values"}` 평문이고, v2 는 `{"v": 2, "connector", "key_id", "sealed"}` 다
    (ADR-20261103 connector-vault-key).
    """
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeError, ValueError):
        raise ValueError("보관 파일의 모양이 올바르지 않다") from None
    if (not isinstance(value, dict) or not isinstance(value.get("connector"), str)
            or not CONNECTOR_ID_RE.match(value["connector"])):
        raise ValueError("보관 파일의 모양이 올바르지 않다")
    version = value.get("v") if type(value.get("v")) is int else None
    if version == 1 and set(value) == {"v", "connector", "values"}:
        values = value["values"]
        if isinstance(values, dict) and all(isinstance(key, str) and isinstance(item, str)
                                            for key, item in values.items()):
            return value
    if (version == 2 and set(value) == {"v", "connector", "key_id", "sealed"}
            and isinstance(value["key_id"], str) and KEY_ID_RE.match(value["key_id"])
            and isinstance(value["sealed"], str)):
        return value
    raise ValueError("보관 파일의 모양이 올바르지 않다")


def _unseal_vault(vault: str, stored: dict, keys) -> dict:
    """v2 보관 파일을 풀어 v1 과 같은 모양으로 돌려준다. 그 key 가 없거나 풀지 못하면 `ValueError` 다."""
    # 활성 id 가 틀려도 파일의 key 로 푼다. 쓰기만 활성 key 를 요구한다.
    key = keys.keys.get(stored["key_id"])
    if key is None:
        raise ValueError("보관 파일을 풀 key 가 없다")
    try:
        values = json.loads(_open(key, stored["sealed"], _vault_aad(vault, stored["connector"])).decode("utf-8"))
    except (UnicodeError, ValueError):
        raise ValueError("보관 파일을 풀지 못했다") from None
    if not isinstance(values, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in values.items()):
        raise ValueError("보관 파일의 모양이 올바르지 않다")
    return {"v": 2, "connector": stored["connector"], "values": values}


def _read_vault(vault: str, keys=None) -> dict | None:
    """보관 파일을 읽는다. 없으면 None 이다. 모양이 틀리거나 풀지 못하면 예외다. 값은 예외 메시지에 싣지 않는다.

    반환은 버전과 상관없이 `{"v", "connector", "values"}` 다. `v` 는 파일의 버전이다.
    """
    path = _vault_path(vault)
    if not path.exists():
        return None
    stored = _parse_vault(path.read_bytes())
    if stored["v"] == 1:
        return stored
    return _unseal_vault(vault, stored, keys if keys is not None else _vault_keys())


def _vault_bytes(vault: str, connector: str, values: dict, keys) -> bytes:
    """보관 파일 글이다. key 가 준비됐으면 활성 key 의 v2 이고, 아니면 v1 평문이다."""
    if keys.ready:
        plain = json.dumps(values, sort_keys=True, ensure_ascii=False).encode("utf-8")
        stored = {"v": 2, "connector": connector, "key_id": keys.active_id,
                  "sealed": _seal(keys.keys[keys.active_id], plain, _vault_aad(vault, connector))}
    else:
        stored = {"v": 1, "connector": connector, "values": values}
    return (json.dumps(stored, sort_keys=True, ensure_ascii=False) + "\n").encode("utf-8")


def _write_vault(vault: str, connector: str, values: dict) -> None:
    """보관 파일을 쓴다. 같은 이름의 파일이 다른 플러그인의 것이면 `FileExistsError` 다.

    소유 확인은 풀지 않고 `connector` 만 읽는다. key 를 잃어 풀 수 없는 파일도 같은 플러그인의 새 값으로 덮어써야
    다시 등록이 된다. 「key 필수」 인데 key 를 쓸 수 없으면 `VaultKeyUnavailable` 이다. 아니면 평문으로 쓰고 경고한다.
    """
    keys = _vault_keys()
    if not keys.ready and keys.required:
        raise VaultKeyUnavailable()
    path = _vault_path(vault)
    path.parent.mkdir(mode=0o700, exist_ok=True)
    os.chmod(path.parent, 0o700)
    if path.exists():
        try:
            owner = _parse_vault(path.read_bytes())["connector"]
        except ValueError:
            # 주인을 알 수 없는 파일이다. 이름은 연결마다 하나라 덮어써야 다시 등록으로 고칠 수 있다.
            logger.warning("dashboard-profile-api: 모양을 읽지 못하는 보관 파일 %s 를 덮어쓴다", vault)
            owner = connector
        if owner != connector:
            raise FileExistsError("다른 플러그인의 보관 파일이다")
    if not keys.ready:
        logger.warning("dashboard-profile-api: 보관 파일 key 가 %s 라 보관 파일 %s 를 평문으로 쓴다", keys.state, vault)
    _durable_private_write(path, _vault_bytes(vault, connector, values, keys))


def _durable_private_write(path: pathlib.Path, value: bytes) -> None:
    """보관 파일을 600 으로 원자적으로 쓰고 디스크에 남긴다. 임시 파일을 `fsync` 한 뒤 바꿔 넣고 디렉터리도 `fsync` 한다."""
    fd, raw = tempfile.mkstemp(prefix=VAULT_TEMP_PREFIX, dir=path.parent)
    temp = pathlib.Path(raw)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(value)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temp, 0o600)
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)
    directory = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def _vault_values(manifest: dict, values) -> dict | None:
    """보관할 칸 값을 manifest 의 칸으로 검사한다. 맞으면 빈 선택 칸을 뺀 값이고, 틀리면 None 이다.

    모르는 키, 문자열이 아닌 값, 두 줄 이상인 값을 받지 않는다. 필수 칸은 비어 있지 않아야 한다.
    형식은 `call` 과 같게 본다. 비운 선택 칸은 형식을 보지 않는다.
    """
    if not isinstance(values, dict):
        return None
    fields = {field["key"]: field for field in manifest["fields"]}
    kept = {}
    for key, value in values.items():
        field = fields.get(key)
        if field is None or not isinstance(value, str) or any(ch in value for ch in "\r\n\0"):
            return None
        required = field.get("required", True)
        if "pattern" in field and (value or required) and not re.fullmatch(field["pattern"], value):
            return None
        if value:
            kept[key] = value
    if any(field.get("required", True) and key not in kept for key, field in fields.items()):
        return None
    return kept


async def _connector_vault_request(request):
    """보관 파일을 쓰고 지운다. 값과 경로를 응답과 로그에 싣지 않는다."""
    from starlette.responses import JSONResponse

    path = request.url.path
    method = request.method.upper()
    body = await _json_object(request)
    expected = {(VAULT_PATH, "PUT"): {"vault", "connector", "values"}, (VAULT_PATH, "DELETE"): {"vault"}}[(path, method)]
    if (body is None or set(body) != expected
            or not isinstance(body["vault"], str) or not VAULT_ID_RE.match(body["vault"])
            or ("connector" in body and (not isinstance(body["connector"], str)
                                         or not CONNECTOR_ID_RE.match(body["connector"])))):
        return _rejected("%s 만 필요하다" % ", ".join(sorted(expected)))
    vault = body["vault"]
    try:
        if method == "DELETE":
            def delete():
                target = _vault_path(vault)
                if not target.exists():
                    return False
                target.unlink()
                return True

            return JSONResponse({"changed": await asyncio.to_thread(delete)}, status_code=200)

        connector = body["connector"]
        manifest = _connector_manifest(connector) if connector in _connector_roots() else None
        if manifest is None:
            return _rejected("쓸 수 있는 connector 가 아니다")
        values = _vault_values(manifest, body["values"])
        if values is None:
            return _rejected("칸 값이 그 connector 의 칸 선언과 맞지 않는다")
        await asyncio.to_thread(_write_vault, vault, connector, values)
        return JSONResponse({"ok": True}, status_code=200)
    except FileExistsError:
        return _rejected("다른 connector 의 보관 파일이다", 409)
    except VaultKeyUnavailable:
        logger.warning("dashboard-profile-api: 보관 파일 key 를 쓸 수 없어 보관 파일을 쓰지 않았다: key=%s",
                       _vault_keys().state)
        return _rejected("보관 파일을 다루지 못했다", 503)
    except Exception as error:
        logger.warning("dashboard-profile-api: 보관 파일을 다루지 못했다: %s", type(error).__name__)
        return _rejected("보관 파일을 다루지 못했다", 503)
