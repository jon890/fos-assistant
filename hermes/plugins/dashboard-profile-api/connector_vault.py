from __future__ import annotations

import asyncio
import json
import os
import pathlib
import re
from .common import (
    MANAGED_MARKER,
    _atomic_private_write,
    _env_value,
    _json_object,
    _missing_profile,
    _profile_rejection,
    _rejected,
    logger,
)

from .connector_manifest import (
    CONNECTOR_ID_RE,
    CONNECTOR_STATE,
    _connector_manifest,
    _connector_roots,
)


# 연결의 칸 값을 두는 보관 파일의 디렉터리다. 대시보드의 Hermes 루트 아래에 둔다(ADR-083).
CONNECTOR_VAULT_DIR = "connector-vault"
# 보관 파일 이름이다. Control Plane 의 연결 id 앞에 `c` 를 붙인다. 경로 조각으로 쓰기 전에 본다.
VAULT_ID_RE = re.compile(r"^c[1-9][0-9]{0,18}$")
VAULT_PATH = "/api/connector-vault"
VAULT_IMPORT_PATH = "/api/connector-vault/import"
VAULT_ROUTES = frozenset({(VAULT_PATH, "PUT"), (VAULT_PATH, "DELETE"), (VAULT_IMPORT_PATH, "POST")})


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


def _read_vault(vault: str) -> dict | None:
    """보관 파일을 읽는다. 없으면 None 이다. 모양이 틀리면 예외다. 값은 예외 메시지에 싣지 않는다."""
    path = _vault_path(vault)
    if not path.exists():
        return None
    value = json.loads(path.read_text(encoding="utf-8"))
    values = value.get("values") if isinstance(value, dict) else None
    if (not isinstance(value, dict) or set(value) != {"v", "connector", "values"} or value["v"] != 1
            or not isinstance(value["connector"], str) or not CONNECTOR_ID_RE.match(value["connector"])
            or not isinstance(values, dict)
            or any(not isinstance(key, str) or not isinstance(item, str) for key, item in values.items())):
        raise ValueError("보관 파일의 모양이 올바르지 않다")
    return value


def _write_vault(vault: str, connector: str, values: dict) -> None:
    """보관 파일을 쓴다. 같은 이름의 파일이 다른 커넥터의 것이면 `FileExistsError` 다."""
    path = _vault_path(vault)
    path.parent.mkdir(mode=0o700, exist_ok=True)
    os.chmod(path.parent, 0o700)
    stored = _read_vault(vault)
    if stored is not None and stored["connector"] != connector:
        raise FileExistsError("다른 커넥터의 보관 파일이다")
    _atomic_private_write(path, (json.dumps({"v": 1, "connector": connector, "values": values},
                                            sort_keys=True, ensure_ascii=False) + "\n").encode("utf-8"))
    os.chmod(path, 0o600)


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
    """보관 파일을 쓰고 지우고, 관리 profile 의 `.env` 에서 옮긴다. 값과 경로를 응답과 로그에 싣지 않는다."""
    from starlette.responses import JSONResponse

    path = request.url.path
    method = request.method.upper()
    body = await _json_object(request)
    expected = {(VAULT_PATH, "PUT"): {"vault", "connector", "values"}, (VAULT_PATH, "DELETE"): {"vault"},
                (VAULT_IMPORT_PATH, "POST"): {"vault", "connector", "profile"}}[(path, method)]
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
        if method == "PUT":
            values = _vault_values(manifest, body["values"])
            if values is None:
                return _rejected("칸 값이 그 connector 의 칸 선언과 맞지 않는다")
        else:
            rejected = _profile_rejection(body["profile"], request)
            if rejected is not None:
                return rejected
            missing = _missing_profile(body["profile"])
            if missing is not None:
                return missing
            from hermes_cli.profiles import get_profile_dir
            profile_dir = get_profile_dir(body["profile"])
            if not (profile_dir / MANAGED_MARKER).is_file():
                return _rejected("관리 표식이 없는 profile 이다", 401)
            state_path = profile_dir / CONNECTOR_STATE
            state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.is_file() else {}
            if not isinstance(state, dict) or connector not in state:
                return _rejected("설치하지 않은 connector 다", 404)
            env_path = profile_dir / ".env"
            if env_path.is_symlink():
                raise ValueError("profile 의 .env 가 링크다")
            env_text = env_path.read_text(encoding="utf-8") if env_path.is_file() else ""
            # 빈 선택 칸은 넣지 않는다. 필수 칸이 비면 아래 검사가 거절한다.
            values = _vault_values(manifest, {field["key"]: _env_value(env_text, field["env"])
                                              for field in manifest["fields"]
                                              if _env_value(env_text, field["env"])})
            if values is None:
                return _rejected("profile 의 값이 그 connector 의 칸 선언과 맞지 않는다")
        await asyncio.to_thread(_write_vault, vault, connector, values)
        return JSONResponse({"ok": True}, status_code=200)
    except FileExistsError:
        return _rejected("다른 connector 의 보관 파일이다", 409)
    except Exception as error:
        logger.warning("dashboard-profile-api: 보관 파일을 다루지 못했다: %s", type(error).__name__)
        return _rejected("보관 파일을 다루지 못했다", 503)
