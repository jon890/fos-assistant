from __future__ import annotations

import logging
import os
import pathlib
import re
import tempfile
from typing import Optional


logger = logging.getLogger(__package__)

# Hermes 의 `hermes_constants.PROFILE_ID_RE` 와 같은 규칙이다. 경로에서 온 이름을 파일 경로로 쓰기 전에 본다.
PROFILE_NAME_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")

# 토큰으로 만든 profile 에 쓰는 설정 틀이다. `hermes/bundle.sh` 가 이 파일 옆에 둔다.
PLUGIN_DIR = pathlib.Path(__file__).resolve().parent

# 이 토큰으로 만든 profile 이라는 표식이다. 지우기 판정이 이 파일 하나를 본다.
MANAGED_MARKER = ".fos-assistant-managed"
# 운영자가 사람이 만든 profile 에 두는 표식이다. 그 profile 이 커넥터의 바인딩 설치를 받는다는 뜻이다.
# 이 plugin 은 이 파일을 쓰지 않는다(ADR-083).
CONNECTOR_HOST_MARKER = ".fos-connector-host"
# Control Plane MCP 서버 이름이다.
CONTROL_PLANE_MCP = "fos-assistant"

# PUT /api/env 로 쓸 수 있는 기본 key 다. Control Plane 이 profile 마다 넣는 값만 둔다.
# 커넥터 key 는 여기 두지 않는다. 카탈로그 manifest 의 `fields[].env` 로 요청마다 계산한다.
BASE_ENV_KEYS = frozenset({"API_SERVER_KEY", "API_SERVER_MODEL_NAME", "MCP_FOS_ASSISTANT_API_KEY"})

# 올린 스킬 이름이다. fos-assistant 의 스킬 이름 규칙보다 넓어 Hermes 기본 스킬도 켜고 끌 수 있다.
SKILL_NAME_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")


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


def _env_value(env_text: str, key: str) -> str:
    """profile `.env` 본문에서 그 key 의 마지막 값을 읽는다. 없으면 빈 문자열이다.

    `_env_line` 이 쓴 줄을 그대로 되돌린다. 큰따옴표 안의 `\\"` 와 `\\\\` 를 풀고, 작은따옴표 안은 그대로 읽는다.
    이름을 찾는 규칙은 `_env_line_key` 와 같아 `export KEY=` 꼴과 `=` 둘레의 공백도 받는다.
    """
    values = []
    for line in env_text.splitlines():
        if _env_line_key(line) != key:
            continue
        raw = line.partition("=")[2].strip()
        if len(raw) >= 2 and raw[0] == '"':
            value, index = [], 1
            while index < len(raw) and raw[index] != '"':
                if raw[index] == "\\" and index + 1 < len(raw) and raw[index + 1] in '"\\':
                    index += 1
                value.append(raw[index])
                index += 1
            values.append("".join(value))
        elif len(raw) >= 2 and raw[0] == "'" and raw.find("'", 1) > 0:
            values.append(raw[1:raw.find("'", 1)])
        else:
            values.append(raw)
    return values[-1] if values else ""


def _atomic_private_write(path: pathlib.Path, value: bytes) -> None:
    fd, raw = tempfile.mkstemp(prefix=".connector-", dir=path.parent)
    temp = pathlib.Path(raw)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(value)
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


def _env_line_key(line: str) -> str:
    """`.env` 한 줄이 값을 주는 이름이다. Hermes 의 `.env` 읽기처럼 `export` 와 `=` 둘레의 공백을 받는다."""
    stripped = line.strip()
    if stripped.startswith("export "):
        stripped = stripped[7:].lstrip()
    key, separator, _ = stripped.partition("=")
    return key.strip() if separator else ""


def _env_line(key: str, value: str) -> str:
    """Hermes 의 `.env` 쓰기와 같은 모양의 한 줄이다. dotenv 에서 뜻이 있는 글자가 있으면 따옴표로 감싼다."""
    if value and ("#" in value or '"' in value or "'" in value or any(ch.isspace() for ch in value)):
        value = '"%s"' % value.replace("\\", "\\\\").replace('"', '\\"')
    return "%s=%s\n" % (key, value)
