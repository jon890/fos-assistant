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
| `DELETE /api/env` | 관리 profile 의 커넥터 칸 key 만 지운다 |
| `GET /api/connectors/catalog` | 운영 목록에 있고 검증을 통과한 커넥터의 manifest 를 낸다. `schema` 와 도구마다의 위험도와 승인 방식(`tools`)을 함께 낸다 |
| `POST /api/connectors/<id>/call` | 후보 값이나 보관 파일의 값으로 그 커넥터의 선택지 도구나 확인 도구를 한 번 부른다 |
| `POST /api/connectors/<id>/execute` | Control Plane 이 승인한 호출을 그 profile 의 값과 받은 인자로 한 번 실행한다 |
| `GET PUT /api/connectors` | 커넥터의 상태를 읽거나 profile 에 설치하고 제거한다. 옛 설치는 plugin 의 스킬 본문을 그 profile 의 SOUL.md 에 쓴다. 바인딩 설치(`bind`)는 보관 파일의 값과 plugin 의 스킬을 그 profile 에 복사한다 |
| `PUT /api/connector-vault` | 연결의 칸 값을 보관 파일 하나에 쓴다 |
| `DELETE /api/connector-vault` | 보관 파일 하나를 지운다 |
| `POST /api/connector-vault/import` | 커넥터를 설치한 관리 profile 의 `.env` 에서 그 커넥터의 칸 값을 보관 파일로 옮긴다 |
| `POST /api/mcp/servers/<서버>/test` | 그 profile 에 설치한 커넥터의 MCP 서버만 probe 한다 |
| `GET /api/profiles/<이름>/soul` | profile 의 SOUL.md 를 읽는다 |
| `PUT /api/profiles/<이름>/soul` | profile 의 SOUL.md 를 쓴다 |
| `GET /api/tools/toolsets` | 도구 이름과 설명을 읽는다 |
| `PUT /api/config` | 지정한 profile 의 API 도구 목록과 올린 스킬 경로만 쓴다. 켠 도구는 `agent.disabled_toolsets` 에서 뺀다. 셸 계열 도구를 켤 때 신뢰한 정책에 등록된 profile 만 `sandbox_owner` 로 docker 실행 공간 설정을 쓴다. 미등록 profile 은 local 이다. 정책이 없거나 잘못됐으면 409 다(ADR-086) |
| `GET /api/skills` | 지정한 profile 의 스킬 목록을 읽는다 |
| `PUT /api/skills/toggle` | 지정한 profile 의 스킬 하나를 켜고 끈다 |
| `GET /api/profiles/<이름>/sessions/<session id>/provider` | 그 profile 의 자식 session 한 줄에서 provider 와 모델만 읽는다 |

`PUT /api/profiles/<이름>/soul` 과 `GET /api/tools/toolsets` 를 뺀 요청은 토큰 요청의 본문이나 query 를
먼저 검사한다. 검사 규칙은 각 `_check_*` 함수가 소유한다. 공통으로 지키는 것은 셋이다.

- 기본 profile 은 거절한다. 운영자가 쓰는 profile 이고 provider credential 이 있다
- 없는 profile 은 404 다
- 본문과 query 에 profile 이 둘 다 있으면 같아야 한다

사람의 쿠키 요청은 기존 Hermes 처리기가 맡는다. 검사하지 않는다.

커넥터 경로의 계약은 `docs/backend/connector-install.md` 의 「대시보드 plugin 계약」 이 소유한다(ADR-043).
이 plugin 은 커넥터의 이름을 코드에 두지 않는다. 운영 목록의 plugin 디렉터리마다 `connector.json` 을 읽는다.

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
import hashlib
import hmac
import importlib.metadata
import json
import logging
import os
import pathlib
import re
import shutil
import sqlite3
import tempfile
import time
from typing import Optional
from urllib.parse import urlsplit

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
# 셸과 파일 도구를 돌릴 docker 실행 공간 설정 JSON 이다. 모양은 `hermes/README.md` 의 「셸 실행 공간」 이 갖는다(ADR-086).
SANDBOX_ENV = "FOS_ASSISTANT_SANDBOX"
# 사진과 영상 파일을 Hermes host에서 직접 읽으면 다른 사용자의 첨부가 보일 수 있다. 실행 공간에서만 연다.
IMAGE_FILE_TOOLSETS = frozenset({"vision", "image_gen", "video_gen"})
# 켜면 profile 의 `terminal:` 을 실행 공간 설정으로 바꿔야 하는 도구다. 설정이 없으면 켜지 않는다.
SANDBOX_TOOLSETS = frozenset({"terminal", "file", "code_execution"}) | IMAGE_FILE_TOOLSETS
# 실행 공간 사용자 디렉터리 이름이다. Control Plane 이 사용자마다 정해 보낸다.
SANDBOX_OWNER_RE = re.compile(r"^[a-z][a-z0-9-]{0,63}$")
SANDBOX_NETWORK_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
# 실행 공간이 직접 쓰는 컨테이너 경로다. 운영 마운트가 이 자리를 가리면 사용자 공간이 바뀐다.
SANDBOX_RESERVED_PATHS = ("/workspace", "/root")
# hermes/README.md 의 「셸 실행 공간」 계약 표에 있는 최상위 키다. 그 밖의 키가 있으면 정책 전체를 틀린 것으로 본다.
SANDBOX_POLICY_KEYS = frozenset({
    "image", "workspace_root", "attachment_root", "attachment_agent_root", "network", "cpu",
    "memory_mb", "read_only_mounts", "profiles",
})
SANDBOX_PROFILE_KEYS = frozenset({"read_only_mounts", "env", "network"})
# 비밀값은 넣지 않는다. 운영 정책이 경로와 Backend 주소만 명시한다.
SANDBOX_PATH_ENV = frozenset({
    "CAREER_BACKEND_TOKEN_FILE", "CLAUDE_PLUGIN_ROOT", "CAREER_EVIDENCE_DIR",
    "CAREER_WORKSPACE_ROOT", "CAREER_DART_API_KEY_FILE",
})

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

# 설치한 커넥터의 MCP 서버 probe 다. 서버 이름은 그 profile 의 소유 기록과 manifest 로 확인한다.
PROBE_ROUTE_RE = re.compile(r"^/api/mcp/servers/([^/]+)/test$")
CONNECTORS_PATH = "/api/connectors"
CATALOG_PATH = "/api/connectors/catalog"
CALL_ROUTE_RE = re.compile(r"^/api/connectors/([^/]+)/call$")
EXECUTE_ROUTE_RE = re.compile(r"^/api/connectors/([^/]+)/execute$")
MODEL_DEFAULTS_RE = re.compile(r"^/api/profiles/([^/]+)/model-defaults$")
# native 하위 에이전트가 쓴 자식 session 의 provider 를 읽는 경로다(ADR-067).
SESSION_PROVIDER_RE = re.compile(r"^/api/profiles/([^/]+)/sessions/([^/]+)/provider$")
# 경로에서 온 session id 다. 저장소 조회의 인자로만 쓰고 파일 경로에는 쓰지 않는다.
SESSION_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
# profile 디렉터리 아래 Hermes 의 session 저장소 파일이다.
SESSION_DB_FILE = "state.db"
# 저장소가 잠겨 있을 때 기다리는 시간이다. 넘으면 503 으로 답한다.
SESSION_DB_TIMEOUT_SECONDS = 2

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
# 운영자가 사람이 만든 profile 에 두는 표식이다. 그 profile 이 커넥터의 바인딩 설치를 받는다는 뜻이다.
# 이 plugin 은 이 파일을 쓰지 않는다(ADR-083).
CONNECTOR_HOST_MARKER = ".fos-connector-host"

# 틀을 쓴 뒤 API 경로에 하나라도 남으면 만든 것을 지운다.
FORBIDDEN_TOOLSETS = frozenset({"memory", "terminal", "file", "code_execution", "browser"})
# Control Plane MCP 서버 이름이다.
CONTROL_PLANE_MCP = "fos-assistant"

# PUT /api/env 로 쓸 수 있는 기본 key 다. Control Plane 이 profile 마다 넣는 값만 둔다.
# 커넥터 key 는 여기 두지 않는다. 카탈로그 manifest 의 `fields[].env` 로 요청마다 계산한다.
BASE_ENV_KEYS = frozenset({"API_SERVER_KEY", "API_SERVER_MODEL_NAME", "MCP_FOS_ASSISTANT_API_KEY"})
# 요청은 이름만 받는다. 실행 정의는 커넥터 checkout 의 manifest 가 소유한다.
# 커넥터 이름과 plugin 디렉터리를 묶은 JSON 을 대시보드 프로세스의 환경 변수로 받는다. 근거는 ADR-041 이 갖는다.
CONNECTOR_ROOTS_ENV = "FOS_ASSISTANT_CONNECTOR_ROOTS"
# 커넥터 MCP 서버를 실행할 파일의 절대 경로다. 운영 목록 항목에 `command` 가 없을 때 쓴다.
CONNECTOR_COMMAND_ENV = "FOS_ASSISTANT_CONNECTOR_COMMAND"
CONNECTOR_STATE = ".fos-connectors.json"
# 연결의 칸 값을 두는 보관 파일의 디렉터리다. 대시보드의 Hermes 루트 아래에 둔다(ADR-083).
CONNECTOR_VAULT_DIR = "connector-vault"
# 보관 파일 이름이다. Control Plane 의 연결 id 앞에 `c` 를 붙인다. 경로 조각으로 쓰기 전에 본다.
VAULT_ID_RE = re.compile(r"^c[1-9][0-9]{0,18}$")
VAULT_PATH = "/api/connector-vault"
VAULT_IMPORT_PATH = "/api/connector-vault/import"
VAULT_ROUTES = frozenset({(VAULT_PATH, "PUT"), (VAULT_PATH, "DELETE"), (VAULT_IMPORT_PATH, "POST")})
# 소유 기록 항목의 설치 방식이다. 칸이 없으면 옛 설치다.
BIND_MODE = "bind"
ISOLATED_MODE = "isolated"
# 바인딩 설치가 커넥터의 스킬을 복사하는 profile 안의 디렉터리와, 스킬 디렉터리 아래에서 복사하는 하위 디렉터리다.
PROFILE_SKILLS_DIR = "skills"
CONNECTOR_SKILL_PARTS = ("references", "templates")
# 커넥터 스킬 하나의 상한이다. Control Plane 이 올린 스킬에 거는 제한과 같다(`docs/backend/skill.md`).
CONNECTOR_SKILL_MAX_FILES = 20
CONNECTOR_SKILL_MAX_CHARS = 100_000
# 설치가 profile 에 쓰는 이름 대응 파일이다. 정책 hook 이 Hermes 등록 이름으로 원래 도구 이름을 찾는다(ADR-049).
CONNECTOR_TOOL_MAP = ".fos-connector-tools.json"
# 바인딩 떼기가 뗀 서버 이름을 남기는 기록이다. `{커넥터 id: 서버 이름}` 이다. 소유 기록 곁에 두고 이름 대응을 만들 때 함께 읽는다.
# 떼기 전에 시작한 실행은 그 서버를 쥔 채 돌므로, 대응에서 서버가 빠지면 그 호출이 판정 없이 나간다.
CONNECTOR_DETACHED = ".fos-connector-detached.json"
# 커넥터 도구 호출을 판정하는 hook 을 가진 profile plugin 과, 묶음의 판과 견주는 그 파일들이다.
POLICY_PLUGIN = "fos-ctx"
PROFILE_PLUGIN_FILES = ("plugin.yaml", "__init__.py")
# `connector.json` 의 형식 규칙이다. `docs/connectors.md` 의 「connector.json」 표와 같다.
CONNECTOR_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
FIELD_KEY_RE = re.compile(r"^[a-z][a-z0-9_]{0,31}$")
ENV_NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,127}$")
TOOL_NAME_RE = re.compile(r"^[A-Za-z0-9_.-]{1,128}$")
SERVER_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
ERROR_WORDS = frozenset({"credential_rejected", "forbidden", "invalid_input", "unavailable", "outcome_unknown"})
# 쓰기를 보냈는데 됐는지 모른다는 어휘다. 실행 경로만 504 로 답하고 `call` 은 `unavailable` 로 읽는다.
OUTCOME_UNKNOWN = "outcome_unknown"
# `connector.json` 의 `toolsets` 가 열 수 있는 내장 toolset 이다. 읽기 전용 이미지 도구만 둔다(ADR-044).
# 셸, 파일, 기억, 스킬, 위임 도구는 manifest 로 열리지 않는다.
CONNECTOR_TOOLSETS = frozenset({"vision"})
# `connector.json` 의 `schema: 2` 가 도구마다 선언하는 위험도와 승인 방식이다(ADR-049).
# 표는 `docs/backend/connector-tool-policy.md` 의 「도구 정책」 과 같다.
TOOL_RISKS = ("READ", "SENSITIVE", "WRITE", "DESTRUCTIVE", "FINANCIAL")
# 느슨한 것에서 엄격한 것의 순서다. 하한 비교가 이 순서의 자리를 쓴다.
TOOL_APPROVALS = ("none", "required", "always")
# 위험도마다 (`approval` 이 없을 때의 기본값, 선언이 내려갈 수 없는 하한) 이다.
TOOL_RISK_DEFAULTS = {"READ": ("none", "none"), "SENSITIVE": ("required", "required"),
                      "WRITE": ("required", "required"), "DESTRUCTIVE": ("always", "always"),
                      "FINANCIAL": ("always", "always")}
TOOL_TITLE_MAX_CHARS = 80
# `tools.<이름>.identifiers` 가 가리키는 인자 이름이다(ADR-089). 도구 인자 객체의 맨 위 칸만 가리킨다.
# 31자까지다. 32자 이상인 이름은 Control Plane 이 키 이름 자체를 긴 덩어리로 보고 가린다.
TOOL_IDENTIFIER_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,30}")
# 비밀 키로 읽히는 인자 이름이다. Control Plane 의 `ToolDetailRedactor` 의 `SECRET_KEYS` 와 `isSecretKey` 와 같다.
# 한쪽을 바꾸면 다른 쪽도 바꾼다. 어긋나도 Control Plane 이 그 칸을 다시 가리므로 비밀이 보이지는 않는다.
SECRET_ARGUMENT_NAMES = frozenset({"token", "secret", "password", "passwd", "apikey", "authorization", "cookie",
                                   "credential", "credentials", "privatekey", "accesskey", "clientsecret"})
SECRET_ARGUMENT_SUFFIXES = ("token", "secret", "password", "privatekey")
# Hermes 가 MCP 도구의 등록 이름에 허용하는 길이다. 넘으면 앞부분에 해시를 붙여 줄인다.
HERMES_TOOL_NAME_MAX_CHARS = 64
# 서버 이름으로 계산한 등록 이름의 앞부분(`mcp__<서버>__`)이 넘지 못하는 길이다.
# 앞부분이 길면 등록 이름이 잘릴 때 앞부분까지 잘려, Control Plane 이 그 서버의 도구임을 알아보지 못한다.
HERMES_TOOL_PREFIX_MAX_CHARS = 40
# 설치가 연결용 profile 의 `SOUL.md` 에 쓰는 스킬 본문의 상한이다. Control Plane 의 성격 본문 상한과 같다.
CONNECTOR_PERSONA_MAX_CHARS = 8000
SOUL_FILE = "SOUL.md"
# `.mcp.json` 의 인자가 plugin 디렉터리를 가리키는 자리다. 그 밖의 치환은 받지 않는다.
PLUGIN_ROOT_REF = "${CLAUDE_PLUGIN_ROOT}"
# 커넥터 도구 호출 하나의 시간 제한과 대시보드 프로세스 전체의 동시 실행 수다.
CONNECTOR_CALL_TIMEOUT_SECONDS = 10
CONNECTOR_CALL_LIMIT = 4
# 승인한 호출을 실행하는 경로의 시간 제한이다. 쓰기 도구는 확인 도구보다 오래 걸릴 수 있다.
CONNECTOR_EXECUTE_TIMEOUT_SECONDS = 60
# 커넥터 도구 호출이 기대는 mcp SDK 의 주 판이다. 다른 판은 결과 속성 이름이 달라 호출하지 않는다.
MCP_SDK_MAJOR = 2
# `_mcp_sdk_version` 이 한 번 읽은 판 문자열이다. 설치된 패키지는 프로세스가 도는 동안 바뀌지 않는다.
_mcp_sdk_version_cache: Optional[str] = None
# 지금 돌고 있는 호출 수다. 이벤트 루프 하나에서만 바꾸므로 잠금이 필요 없다.
_connector_calls = 0
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


def _profile_plugin_files(name: str) -> dict[str, bytes] | None:
    """설치 묶음에 든 profile plugin 의 파일 이름과 바이트다. 디렉터리나 파일 하나가 없으면 None 이다.

    저장소에서 바로 읽은 plugin 에는 묶음 디렉터리가 없다. 그때는 견줄 판이 없다.
    """
    source = PROFILE_PLUGIN_DIR / name
    if not source.is_dir() or any(not (source / file_name).is_file() for file_name in PROFILE_PLUGIN_FILES):
        return None
    return {file_name: (source / file_name).read_bytes() for file_name in PROFILE_PLUGIN_FILES}


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


def _sandbox_unavailable():
    from starlette.responses import JSONResponse

    return JSONResponse({"detail": "실행 공간이 설정되지 않았다", "code": "sandbox_unavailable"}, status_code=409)


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


def _operator_env_ignored(body: dict):
    """운영자 env 이름의 쓰기와 지우기에 주는 답이다. 성공으로 답하고 아무것도 쓰지 않는다.

    그 값은 운영 목록이 갖고 설치할 때 서버 정의에 직접 들어간다.
    옛 Control Plane 이 한 배포 동안 이 이름을 쓰려 하므로 거절하지 않는다(ADR-041).
    """
    from starlette.responses import JSONResponse

    return JSONResponse({"profile": body["profile"], "key": body["key"], "restart_required": False},
                        status_code=200)


async def _check_env_update(request):
    """`.env` 쓰기를 허용한 key 와 Control Plane 이 쓰는 profile 로 제한한다."""
    body = await _json_object(request)
    if body is None or set(body) != {"profile", "key", "value"}:
        return _rejected("profile, key, value 만 필요하다")
    rejected = _profile_rejection(body["profile"], request)
    if rejected is not None:
        return rejected
    field_keys, operator_keys = _connector_env_keys()
    if not isinstance(body["key"], str) or body["key"] not in BASE_ENV_KEYS | field_keys | operator_keys:
        return _rejected("쓸 수 없는 key 다")
    value = body["value"]
    if not isinstance(value, str) or any(ch in value for ch in "\r\n\0"):
        return _rejected("value 는 한 줄 문자열이다")
    try:
        missing = _missing_profile(body["profile"])
        if missing is not None:
            return missing
        if body["key"] not in BASE_ENV_KEYS:
            from hermes_cli.profiles import get_profile_dir
            if not (get_profile_dir(body["profile"]) / MANAGED_MARKER).is_file():
                return _rejected("관리 표식이 없는 profile 이다", 401)
            if body["key"] in operator_keys:
                return _operator_env_ignored(body)
        return None
    except Exception:
        logger.exception("dashboard-profile-api: profile 을 확인하지 못했다")
        return _rejected("profile 을 확인하지 못했다", 500)


async def _check_env_delete(request):
    """연결 해제는 커넥터 칸의 key 만 지운다. 모델과 Control Plane credential 은 보존한다."""
    body = await _json_object(request)
    if body is None or set(body) != {"profile", "key"}:
        return _rejected("profile 과 key 만 필요하다")
    rejected = _profile_rejection(body["profile"], request)
    if rejected is not None:
        return rejected
    field_keys, operator_keys = _connector_env_keys()
    if not isinstance(body["key"], str) or body["key"] not in field_keys | operator_keys:
        return _rejected("커넥터 환경 변수만 지울 수 있다")
    missing = _missing_profile(body["profile"])
    if missing is not None:
        return missing
    from hermes_cli.profiles import get_profile_dir
    if not (get_profile_dir(body["profile"]) / MANAGED_MARKER).is_file():
        return _rejected("관리 표식이 없는 profile 이다", 401)
    if body["key"] in operator_keys:
        return _operator_env_ignored(body)
    return None


def _connector_roots() -> dict[str, dict]:
    """운영 목록을 읽는다. `{"<커넥터 이름>": {"root", "command", "env"}}` 다. 없거나 틀리면 빈 dict 다.

    환경 변수의 값은 이름마다 문자열(plugin 디렉터리)이나
    `{"root": "<plugin 디렉터리>", "command": "<실행 파일>", "env": {"<이름>": "<값>"}}` 다.
    문자열은 `{"root": 그 값}` 으로 읽는다.
    """
    raw = os.environ.get(CONNECTOR_ROOTS_ENV, "").strip()
    if not raw:
        return {}
    try:
        value = json.loads(raw)
    except ValueError:
        value = None
    if not isinstance(value, dict) or any(not isinstance(entry, (str, dict)) for entry in value.values()):
        # 값에는 운영 경로가 들어 있어 로그에 싣지 않는다.
        logger.warning("dashboard-profile-api: %s 가 문자열이나 object 값의 JSON object 가 아니다", CONNECTOR_ROOTS_ENV)
        return {}
    roots = {}
    for name, entry in value.items():
        if isinstance(entry, str):
            entry = {"root": entry}
        root, command, env = entry.get("root"), entry.get("command"), entry.get("env", {})
        if (set(entry) - {"root", "command", "env"} or not isinstance(root, str)
                or not (command is None or isinstance(command, str)) or not isinstance(env, dict)
                or any(not isinstance(key, str) or not isinstance(item, str) for key, item in env.items())):
            logger.warning("dashboard-profile-api: %s 의 %s 항목 모양이 올바르지 않아 버렸다", CONNECTOR_ROOTS_ENV, name)
            continue
        if not os.path.isabs(root) or (command is not None and not os.path.isabs(command)):
            logger.warning("dashboard-profile-api: %s 의 %s 경로가 절대 경로가 아니라 버렸다", CONNECTOR_ROOTS_ENV, name)
            continue
        roots[name] = {"root": pathlib.Path(root), "command": command, "env": dict(env)}
    return roots


def _connector_command() -> str | None:
    """커넥터 MCP 서버를 실행할 파일의 기본 절대 경로다. 없거나 절대 경로가 아니면 None 이다."""
    command = os.environ.get(CONNECTOR_COMMAND_ENV, "").strip()
    if not command:
        return None
    if not os.path.isabs(command):
        logger.warning("dashboard-profile-api: %s 가 절대 경로가 아니다", CONNECTOR_COMMAND_ENV)
        return None
    return command


def _read_connector_json(root: pathlib.Path, relative: str):
    path = root / relative
    if path.resolve() != path or not path.is_file():
        raise ValueError("%s 가 없거나 링크다" % relative)
    return json.loads(path.read_text(encoding="utf-8"))


def _connector_fields(declared) -> list:
    """`connector.json` 의 `fields` 를 검증한다. 틀리면 예외다.

    빈 목록도 받는다. 값을 받지 않는 일반 MCP 서버가 입력 칸 없이 카탈로그에 오르는 길이다(ADR-083).
    """
    if not isinstance(declared, list):
        raise ValueError("fields 는 목록이다")
    for field in declared:
        if (not isinstance(field, dict)
                or not isinstance(field.get("key"), str) or not FIELD_KEY_RE.match(field["key"])
                or not isinstance(field.get("env"), str) or not ENV_NAME_RE.match(field["env"])
                or field["env"] in BASE_ENV_KEYS
                or not isinstance(field.get("label"), str)
                or not isinstance(field.get("description", ""), str)
                or not isinstance(field.get("secret", False), bool)
                or not isinstance(field.get("required", True), bool)):
            raise ValueError("fields 의 칸 모양이 올바르지 않다")
        if "pattern" in field:
            if not isinstance(field["pattern"], str):
                raise ValueError("pattern 은 문자열이다")
            try:
                re.compile(field["pattern"])
            except re.error:
                raise ValueError("pattern 을 정규식으로 읽지 못한다") from None
        if "options" in field:
            options = field["options"]
            if (not isinstance(options, dict)
                    or not isinstance(options.get("tool"), str) or not TOOL_NAME_RE.match(options["tool"])
                    or any(not isinstance(options.get(name), str) for name in ("items", "value", "label"))
                    or not isinstance(options.get("auto_select_single", False), bool)):
                raise ValueError("options 모양이 올바르지 않다")
    for name in ("key", "env"):
        if len({field[name] for field in declared}) != len(declared):
            raise ValueError("fields 의 %s 가 겹친다" % name)
    return declared


def _canonical_server_name(server: str) -> str:
    """MCP 서버 이름을 견줄 수 있게 맞춘다. Hermes 등록 규칙대로 글자를 `_` 로 바꾸고 소문자로 맞춘다.

    등록 규칙만 쓰면 대소문자만 다른 이름이 다른 서버로 읽힌다. 이름을 대소문자 없이 다루는 자리가
    하나라도 있으면 두 서버의 도구가 섞이므로 가장 넓게 같은 이름으로 본다.
    """
    return re.sub(r"[^A-Za-z0-9_]", "_", server).lower()


def _hermes_tool_name(server: str, tool: str) -> str:
    """Hermes 가 MCP 도구에 붙이는 등록 이름이다. `tools/mcp_tool_schema.py` 의 `mcp_prefixed_tool_name` 과 같은 규칙이다.

    규칙은 `docs/hermes/connector-policy.md` 의 「MCP 도구의 등록 이름」 이 갖는다.
    글자를 바꾸고 줄이므로 서로 다른 도구가 같은 등록 이름이 될 수 있다.
    """
    full = "mcp__%s__%s" % (re.sub(r"[^A-Za-z0-9_]", "_", server), re.sub(r"[^A-Za-z0-9_]", "_", tool))
    if len(full) <= HERMES_TOOL_NAME_MAX_CHARS:
        return full
    return full[:HERMES_TOOL_NAME_MAX_CHARS - 9] + "_" + hashlib.sha256(full.encode("utf-8")).hexdigest()[:8]


def _tool_identifiers(declared_tool: dict, approval: str) -> list:
    """`tools.<이름>.identifiers` 를 검증해 선언한 순서대로 낸다. 틀리면 예외다.

    승인 카드가 있는 도구에만 뜻이 있어 `approval` 이 `required` 인 도구에만 받는다.
    비밀 키로 읽히는 이름은 Control Plane 이 어차피 가리므로, 선언한 사람이 잘못 안 것으로 보고 거절한다.
    """
    if "identifiers" not in declared_tool:
        return []
    identifiers = declared_tool["identifiers"]
    if approval != "required":
        raise ValueError("identifiers 는 approval 이 required 인 도구에만 선언한다")
    if not isinstance(identifiers, list) or not all(
            isinstance(item, str) and TOOL_IDENTIFIER_RE.fullmatch(item) for item in identifiers):
        raise ValueError("identifiers 는 인자 이름의 배열이다")
    if len(set(identifiers)) != len(identifiers):
        raise ValueError("identifiers 에 같은 이름이 두 번 있다")
    for item in identifiers:
        normalized = item.replace("_", "").lower()
        if normalized in SECRET_ARGUMENT_NAMES or normalized.endswith(SECRET_ARGUMENT_SUFFIXES):
            raise ValueError("identifiers 에 비밀 키로 읽히는 이름을 둘 수 없다")
    return list(identifiers)


def _connector_tools(declared: dict, verify_tool: str, option_tools: set, mcp_server: str) -> dict:
    """`connector.json` 의 도구 정책을 검증해 `{도구 이름: {"risk", "approval", "title", "grant", "outbound", "identifiers"}}` 로 낸다.

    틀리면 예외다.

    하한보다 느슨한 선언은 고쳐서 받지 않고 거절한다. 조용히 엄격하게 읽으면 선언이 틀린 것을 만든 사람이 모른다.
    `schema: 1` 은 도구 정책을 선언하지 않는다. 대시보드가 부르는 읽기 전용 도구만 정책으로 낸다.
    `grant` 는 그 도구에 상시 허락을 줄 수 있는지다(ADR-065). 기본값을 채운 값이고,
    `approval` 이 `required` 이고 선언이 닫지 않았을 때만 참이다.
    `outbound` 는 그 도구가 데이터를 계정 밖의 사람에게 보낸다는 선언이다. 참인 도구는 상시 허락이 닫혀 있어야 한다.
    `identifiers` 는 승인 카드가 길이로 가리지 않을 식별자 인자의 이름이다(ADR-089). 기본값은 빈 배열이다.
    """
    call_tools = {verify_tool} | set(option_tools)
    if declared["schema"] == 1:
        if "tools" in declared or "default_tool_policy" in declared:
            raise ValueError("tools 와 default_tool_policy 는 schema 2 에서만 선언한다")
        return {name: {"risk": "READ", "approval": "none", "title": None, "grant": False, "outbound": False,
                       "identifiers": []}
                for name in sorted(call_tools)}
    if declared["schema"] != 2:
        raise ValueError("schema 는 1 이나 2 만 받는다")

    tools = declared.get("tools")
    if not isinstance(tools, dict) or not tools:
        raise ValueError("schema 2 의 tools 는 비어 있지 않은 객체다")
    if "default_tool_policy" in declared and declared["default_tool_policy"] != "deny":
        raise ValueError("default_tool_policy 는 deny 만 받는다")
    policies = {}
    for name, declared_tool in tools.items():
        if not isinstance(name, str) or not TOOL_NAME_RE.match(name):
            raise ValueError("tools 의 키는 도구 이름이다")
        if not isinstance(declared_tool, dict) or set(declared_tool) - {
                "risk", "approval", "title", "grant", "outbound", "identifiers"}:
            raise ValueError("tools 의 값은 risk, approval, title, grant, outbound, identifiers 만 갖는 객체다")
        risk = declared_tool.get("risk")
        if not isinstance(risk, str) or risk not in TOOL_RISKS:
            raise ValueError("risk 는 정해 둔 위험도 가운데 하나다")
        default, floor = TOOL_RISK_DEFAULTS[risk]
        approval = declared_tool.get("approval", default)
        if not isinstance(approval, str) or approval not in TOOL_APPROVALS:
            raise ValueError("approval 은 none, required, always 가운데 하나다")
        if TOOL_APPROVALS.index(approval) < TOOL_APPROVALS.index(floor):
            raise ValueError("approval 이 그 위험도의 하한보다 느슨하다")
        title = declared_tool.get("title")
        if "title" in declared_tool and (not isinstance(title, str) or not 1 <= len(title) <= TOOL_TITLE_MAX_CHARS):
            raise ValueError("title 은 1자에서 %d자까지의 문자열이다" % TOOL_TITLE_MAX_CHARS)
        if "grant" in declared_tool:
            # `1` 이나 `0` 을 boolean 으로 받지 않는다.
            if type(declared_tool["grant"]) is not bool:
                raise ValueError("grant 는 true 나 false 다")
            if approval != "required":
                raise ValueError("grant 는 approval 이 required 인 도구에만 선언한다")
        grant = approval == "required" and declared_tool.get("grant") is not False
        outbound = declared_tool.get("outbound", False)
        if type(outbound) is not bool:
            raise ValueError("outbound 는 true 나 false 다")
        # 밖으로 나가는 도구는 호출마다 사람이 본다. 상시 허락이 열려 있으면 고쳐 읽지 않고 거절한다.
        if outbound and (approval != "required" or grant):
            raise ValueError("outbound 가 참인 도구는 approval 이 required 이고 grant 가 false 여야 한다")
        identifiers = _tool_identifiers(declared_tool, approval)
        policies[name] = {"risk": risk, "approval": approval, "title": title, "grant": grant, "outbound": outbound,
                          "identifiers": identifiers}
    for name in call_tools:
        # 대시보드가 승인 없이 부르는 도구다. 읽기 전용이고 승인이 없는 선언만 맞는다.
        if policies.get(name, {}).get("risk") != "READ" or policies[name]["approval"] != "none":
            raise ValueError("확인 도구와 선택지 도구는 tools 에 READ 와 none 으로 선언한다")
    # 판정은 등록 이름으로 도구를 찾는다. 두 도구의 등록 이름이 같으면 어느 정책인지 알 수 없다.
    if len({_hermes_tool_name(mcp_server, name) for name in policies}) != len(policies):
        raise ValueError("tools 의 두 도구가 같은 Hermes 등록 이름이 된다")
    return policies


def _connector_persona(skill_dirs: list) -> str | None:
    """스킬 디렉터리들의 `<스킬>/SKILL.md` 본문을 이름 순으로 이어 붙인다. 스킬이 없으면 None 이다.

    `SKILL.md` 밖의 파일은 읽지 않는다. 스킬 디렉터리나 `SKILL.md` 가 링크이면 읽지 않고 예외를 낸다.
    링크가 plugin 밖의 파일을 가리키면 그 내용이 모델의 지침으로 들어가기 때문이다.
    앞머리(frontmatter)는 뗀다. 합친 본문이 상한을 넘으면 예외다. 본문은 로그에 싣지 않는다.
    """
    bodies = []
    for directory in skill_dirs:
        for skill in sorted(directory.iterdir()):
            source = skill / "SKILL.md"
            if skill.is_symlink() or source.is_symlink():
                raise ValueError("스킬 디렉터리나 SKILL.md 가 링크다")
            if not skill.is_dir() or not source.is_file():
                continue
            # BOM 과 CRLF 가 있어도 앞머리를 알아보게 맞춘다.
            text = source.read_text(encoding="utf-8-sig").replace("\r\n", "\n")
            if text.startswith("---\n"):
                head, separator, rest = text[4:].partition("\n---\n")
                if not separator:
                    raise ValueError("SKILL.md 의 앞머리가 닫히지 않았다")
                text = rest
            if text.strip():
                bodies.append(text.strip())
    if not bodies:
        return None
    persona = "\n\n".join(bodies) + "\n"
    if len(persona) > CONNECTOR_PERSONA_MAX_CHARS:
        raise ValueError("스킬 본문이 %d자를 넘는다" % CONNECTOR_PERSONA_MAX_CHARS)
    return persona


def _skill_name(skill_md: str, fallback: str) -> str:
    """`SKILL.md` 앞머리의 `name` 이다. 없으면 디렉터리 이름이다. 경로 조각으로 쓸 수 없는 이름이면 예외다.

    Hermes 가 스킬 목록을 만들 때 같은 규칙으로 이름을 정한다(`tools/skills_tool.py` 의 `_find_all_skills`).

    앞머리가 환경 값이나 자격 증명 파일을 요청하면 예외다. Hermes 는 스킬을 읽을 때 그 칸의 이름으로
    profile `.env` 의 값과 profile 안의 파일을 셸 실행 공간에 넣는다. 바인딩 설치는 커넥터 값을 그 `.env` 에
    복사하므로 실행 공간이 커넥터 비밀을 받게 된다. 칸 목록은 Control Plane 이 올린 스킬에 거는 것과 같다(ADR-086).
    """
    import yaml
    text = skill_md.lstrip("﻿").replace("\r\n", "\n")
    name = fallback
    # 앞머리를 알아보는 규칙도 Hermes 와 같게 둔다(`agent/skill_utils.py` 의 `parse_frontmatter`).
    # 첫 줄이 `--- ` 처럼 정확히 `---` 가 아니어도 Hermes 는 앞머리로 읽으므로 여기서도 읽어 검사한다.
    if text.startswith("---"):
        closing = re.search(r"\n---\s*\n", text[3:])
        if closing is None:
            raise ValueError("SKILL.md 의 앞머리가 닫히지 않았다")
        head = text[3:3 + closing.start()]
        front = yaml.safe_load(head) if head.strip() else {}
        if not isinstance(front, dict):
            raise ValueError("SKILL.md 의 앞머리가 객체가 아니다")
        setup = front.get("setup")
        prerequisites = front.get("prerequisites")
        if ("required_environment_variables" in front or "required_credential_files" in front
                or (isinstance(setup, dict) and "collect_secrets" in setup)
                or (isinstance(prerequisites, dict) and "env_vars" in prerequisites)):
            raise ValueError("SKILL.md 의 앞머리가 환경 값이나 자격 증명 파일을 요청한다")
        name = front.get("name", fallback)
    # 설치와 떼기가 이 이름을 profile 의 스킬 디렉터리 이름으로 쓴다.
    if not isinstance(name, str) or not SKILL_NAME_RE.match(name) or ".." in name:
        raise ValueError("스킬 이름이 경로로 쓸 수 없는 모양이다")
    return name


def _connector_skills(skill_dirs: list) -> dict:
    """바인딩 설치가 profile 에 복사할 스킬이다. `{스킬 이름: {상대 경로: 파일 바이트}}` 다. 틀리면 예외다.

    스킬마다 `SKILL.md` 와 `references/`, `templates/` 아래 정규 파일을 읽는다.
    스킬 디렉터리 아래 어느 항목이든 링크이면 거절한다. 링크가 plugin 밖의 파일을 가리키면 그 내용이 profile 로 복사된다.
    UTF-8 로 읽히지 않는 파일, 상한을 넘는 스킬, 이름이 겹치는 스킬도 거절한다. 본문은 로그에 싣지 않는다.
    """
    skills = {}
    for directory in skill_dirs:
        for skill in sorted(directory.iterdir()):
            if skill.is_symlink():
                raise ValueError("스킬 디렉터리 아래에 링크가 있다")
            source = skill / "SKILL.md"
            if not skill.is_dir() or not source.is_file():
                continue
            for current, dirs, names in os.walk(skill):
                if any(os.path.islink(os.path.join(current, child)) for child in dirs + names):
                    raise ValueError("스킬 디렉터리 아래에 링크가 있다")
            paths = [source]
            for part in CONNECTOR_SKILL_PARTS:
                if (skill / part).is_dir():
                    for current, dirs, names in os.walk(skill / part):
                        dirs.sort()
                        paths.extend(pathlib.Path(current) / name for name in sorted(names)
                                     if (pathlib.Path(current) / name).is_file())
            if len(paths) > CONNECTOR_SKILL_MAX_FILES:
                raise ValueError("스킬 하나의 파일이 %d개를 넘는다" % CONNECTOR_SKILL_MAX_FILES)
            files = {}
            for path in paths:
                relative = path.relative_to(skill)
                if any(part in ("", ".", "..") for part in relative.parts):
                    raise ValueError("스킬 파일 경로에 . 이나 .. 이 있다")
                data = path.read_bytes()
                try:
                    text = data.decode("utf-8")
                except UnicodeDecodeError:
                    raise ValueError("스킬 파일이 UTF-8 이 아니다") from None
                if len(text) > CONNECTOR_SKILL_MAX_CHARS:
                    raise ValueError("스킬 파일이 %d자를 넘는다" % CONNECTOR_SKILL_MAX_CHARS)
                files[relative.as_posix()] = data
            name = _skill_name(files["SKILL.md"].decode("utf-8"), skill.name)
            if name in skills:
                raise ValueError("스킬 이름이 겹친다")
            skills[name] = files
    return skills


def _load_connector(connector_id: str, entry: dict) -> dict:
    """plugin 디렉터리의 `connector.json`, `.mcp.json`, `plugin.json` 을 읽어 검증한다. 틀리면 예외다.

    형식 규칙은 `docs/connectors.md` 의 「connector.json」 이 소유한다(ADR-043).
    """
    root = entry["root"]
    if not CONNECTOR_ID_RE.match(connector_id):
        raise ValueError("커넥터 이름이 올바르지 않다")
    if root.resolve() != root:
        raise ValueError("connector 경로에 심볼릭 링크가 있다")
    plugin = _read_connector_json(root, ".claude-plugin/plugin.json")
    if not isinstance(plugin, dict) or plugin.get("name") != connector_id:
        raise ValueError("plugin.json 의 이름이 운영 목록과 다르다")

    declared = _read_connector_json(root, "connector.json")
    if not isinstance(declared, dict) or type(declared.get("schema")) is not int or declared["schema"] not in (1, 2):
        raise ValueError("schema 는 1 이나 2 만 받는다")
    if declared.get("id") != connector_id:
        raise ValueError("id 가 운영 목록의 이름과 다르다")
    if (not isinstance(declared.get("title"), str) or not declared["title"]
            or not isinstance(declared.get("description", ""), str)):
        raise ValueError("title 과 description 은 문자열이다")
    fields = _connector_fields(declared.get("fields"))
    verify = declared.get("verify")
    if (not isinstance(verify, dict) or not isinstance(verify.get("tool"), str)
            or not TOOL_NAME_RE.match(verify["tool"])):
        raise ValueError("verify.tool 은 도구 이름이다")
    field_env = {field["env"] for field in fields}
    operator_env = declared.get("operator_env", [])
    if (not isinstance(operator_env, list)
            or any(not isinstance(name, str) or not ENV_NAME_RE.match(name) or name in BASE_ENV_KEYS
                   for name in operator_env)
            or len(set(operator_env)) != len(operator_env) or set(operator_env) & field_env):
        raise ValueError("operator_env 는 칸과 겹치지 않는 env 이름 목록이다")
    if set(operator_env) - set(entry["env"]):
        raise ValueError("operator_env 의 값이 운영 목록에 없다")
    operator_secrets = declared.get("operator_secrets", [])
    if not isinstance(operator_secrets, list) or any(not isinstance(name, str) for name in operator_secrets):
        raise ValueError("operator_secrets 는 env 이름 목록이다")
    if operator_secrets:
        # 조용히 무시하면 비밀이 필요한 커넥터의 확인이 까닭 없이 실패한다. 받지 못하는 칸임을 밝힌다(ADR-046).
        raise ValueError("operator_secrets 는 아직 지원하지 않는다")
    errors = declared.get("errors", {})
    if not isinstance(errors, dict) or any(
            not isinstance(code, str) or not isinstance(word, str) or word not in ERROR_WORDS
            for code, word in errors.items()):
        raise ValueError("errors 의 값은 공통 어휘 다섯 가운데 하나다")
    toolsets = declared.get("toolsets", [])
    if (not isinstance(toolsets, list) or any(not isinstance(name, str) for name in toolsets)
            or len(set(toolsets)) != len(toolsets) or set(toolsets) - CONNECTOR_TOOLSETS):
        raise ValueError("toolsets 는 허용한 내장 toolset 이름의 겹치지 않는 목록이다")
    attachments = declared.get("attachments", False)
    if not isinstance(attachments, bool) or (attachments and "vision" not in toolsets):
        raise ValueError("attachments 는 boolean 이고 참이면 toolsets 에 vision 이 있어야 한다")

    mcp = _read_connector_json(root, ".mcp.json")
    if not isinstance(mcp, dict):
        raise ValueError("MCP manifest 는 객체여야 한다")
    if "mcpServers" in mcp:
        if set(mcp) != {"mcpServers"}:
            raise ValueError("MCP manifest 에 다른 필드가 있다")
        mcp = mcp["mcpServers"]
    if not isinstance(mcp, dict) or len(mcp) != 1:
        raise ValueError("MCP 서버 하나만 허용한다")
    mcp_server, server = next(iter(mcp.items()))
    # 맞춘 이름으로 견준다. `fos_assistant` 나 `FOS-Assistant` 처럼 글자만 다른 이름도 Control Plane MCP 의 이름으로 읽힐 수 있어
    # 그 커넥터의 도구가 Control Plane 도구와 같은 이름 공간에 놓인다.
    if (not SERVER_NAME_RE.match(mcp_server)
            or _canonical_server_name(mcp_server) == _canonical_server_name(CONTROL_PLANE_MCP)):
        raise ValueError("MCP 서버 이름이 올바르지 않다")
    if len(_hermes_tool_name(mcp_server, "")) > HERMES_TOOL_PREFIX_MAX_CHARS:
        raise ValueError("MCP 서버 이름이 길어 등록 이름의 앞부분이 %d자를 넘는다" % HERMES_TOOL_PREFIX_MAX_CHARS)
    if (not isinstance(server, dict) or set(server) - {"command", "args", "env"}
            or not isinstance(server.get("args"), list) or not isinstance(server.get("env"), dict)):
        raise ValueError("MCP 서버 정의 모양이 올바르지 않다")
    optional_env = frozenset(field["env"] for field in fields if field.get("required", True) is False)
    if set(server["env"]) != field_env | set(operator_env):
        raise ValueError("MCP 서버 env 가 fields 와 operator_env 의 합과 다르다")
    for name, value in server["env"].items():
        # 비밀값 원문이나 다른 변수의 참조를 받지 않는다. 선택 칸만 빈 기본값 참조를 쓸 수 있다.
        if value != "${%s}" % name and not (name in optional_env and value == "${%s:-}" % name):
            raise ValueError("MCP 서버 env 는 자기 이름의 참조만 허용한다")
    args = []
    for arg in server["args"]:
        if not isinstance(arg, str):
            raise ValueError("MCP 서버 인자는 문자열이다")
        if arg.startswith(PLUGIN_ROOT_REF + "/"):
            target = root / arg[len(PLUGIN_ROOT_REF) + 1:]
            if target.resolve() != target or not target.is_relative_to(root) or not target.is_file():
                raise ValueError("MCP 서버 인자의 파일이 plugin 안의 링크 없는 파일이 아니다")
            arg = str(target)
        elif "$" in arg:
            raise ValueError("MCP 서버 인자에 plugin root 밖의 치환이 있다")
        args.append(arg)
    # 실행 파일은 manifest 가 정하지 못한다. 운영 목록의 값이나 기본 실행 파일만 쓴다.
    command = entry["command"] or _connector_command()
    if command is None or not os.access(command, os.X_OK):
        raise ValueError("커넥터 실행 파일이 없거나 실행할 수 없다")

    # 옛 설치는 스킬 본문을 `SOUL.md` 에 써 지침으로 넣고 skills toolset 은 열지 않는다.
    # 바인딩 설치는 스킬 디렉터리를 그 profile 의 스킬로 복사한다. 둘 다 같은 디렉터리를 읽는다.
    skills = plugin.get("skills", "./skills")
    if isinstance(skills, str):
        skills = [skills]
    if not isinstance(skills, list) or any(not isinstance(item, str) for item in skills):
        raise ValueError("스킬 경로 목록이 올바르지 않다")
    skill_dirs = []
    for item in skills:
        path = root / item
        if not path.resolve().is_relative_to(root) or not path.is_dir() or path.resolve() != path.absolute():
            raise ValueError("스킬은 plugin 안의 링크 없는 디렉터리여야 한다")
        skill_dirs.append(path.resolve())
    persona = _connector_persona(skill_dirs)
    installed_skills = _connector_skills(skill_dirs)

    env = {name: "${%s}" % name for name in server["env"]}
    # 운영자 env 는 profile `.env` 를 거치지 않는다. 운영 목록의 값을 서버 정의에 직접 넣는다.
    env.update({name: entry["env"][name] for name in operator_env})
    option_tools = {field["options"]["tool"] for field in fields if "options" in field}
    tools = _connector_tools(declared, verify["tool"], option_tools, mcp_server)
    definition = {"command": command, "args": args, "env": env, "enabled": True}
    # 늘 승인이 필요한 도구는 모델에 등록하지 않는다. 도구 이름에는 glob 글자가 없어 그 이름만 빠진다.
    excluded = sorted(name for name, policy in tools.items() if policy["approval"] == "always")
    if excluded:
        definition["tools"] = {"exclude": excluded}
    return {
        "id": connector_id,
        "schema": declared["schema"],
        "title": declared["title"],
        "description": declared.get("description", ""),
        "fields": fields,
        "verify": {"tool": verify["tool"]},
        "mcp_server": mcp_server,
        "operator_env": frozenset(operator_env),
        "optional_env": optional_env,
        "errors": errors,
        "toolsets": list(toolsets),
        "attachments": attachments,
        "persona": persona,
        "skills": installed_skills,
        # 대시보드가 `call` 로 부를 수 있는 도구다. 도구 정책인 `tools` 와 뜻이 다르다.
        "call_tools": frozenset({verify["tool"]} | option_tools),
        "tools": tools,
        "server": definition,
    }


def _connector_manifest(connector_id: str) -> dict | None:
    """운영 목록에 있고 검증을 통과한 커넥터의 manifest 다. 아니면 경고 한 줄을 남기고 None 이다."""
    entry = _connector_roots().get(connector_id)
    if entry is None:
        logger.warning("dashboard-profile-api: 커넥터 %r 가 운영 목록에 없다", connector_id)
        return None
    try:
        return _load_connector(connector_id, entry)
    except Exception as error:
        # 파일 내용과 운영 경로를 로그에 싣지 않는다. 직접 낸 사유만 그대로 적는다.
        reason = str(error) if type(error) is ValueError else type(error).__name__
        logger.warning("dashboard-profile-api: 커넥터 %r 를 쓸 수 없다: %s", connector_id, reason)
        return None


def _connector_catalog() -> dict[str, dict]:
    """운영 목록의 커넥터 가운데 검증을 통과한 manifest 다."""
    manifests = {name: _connector_manifest(name) for name in _connector_roots()}
    return {name: manifest for name, manifest in manifests.items() if manifest is not None}


def _connector_env_keys() -> tuple[frozenset, frozenset]:
    """카탈로그 manifest 의 칸 env 이름과 운영자 env 이름이다. 칸 이름과 겹치는 운영자 이름은 칸으로 본다."""
    fields, operator = set(), set()
    for manifest in _connector_catalog().values():
        fields.update(field["env"] for field in manifest["fields"])
        operator.update(manifest["operator_env"])
    return frozenset(fields), frozenset(operator - fields)


def _connector_server(manifest: dict) -> dict:
    """profile 설정에 쓸 서버 정의의 사본이다."""
    server = manifest["server"]
    copied = {**server, "args": list(server["args"]), "env": dict(server["env"])}
    if "tools" in server:
        copied["tools"] = {"exclude": list(server["tools"]["exclude"])}
    return copied


def _connector_tool_map(state: dict, detached: dict | None = None) -> dict:
    """소유 기록의 커넥터와 뗀 서버 기록으로 만든 이름 대응이다. 형식은 `docs/backend/connector-tool-policy.md` 의 「이름 대응」 이 갖는다.

    옛 설치는 운영 목록에서 빠졌거나 manifest 를 읽을 수 없는 커넥터를 싣지 않는다. 대응이 없는 도구는 hook 이 막는다.
    바인딩 설치는 `isolated: false` 를 싣고 소유 기록의 모든 서버를 싣는다. manifest 를 읽지 못한 서버는 빈 `tools` 다.
    바인딩 항목의 서버 이름은 기록의 이름이다. 기록의 이름이나 실행 정의가 지금 manifest 와 다르면 그 서버도 빈 `tools` 다.
    떼기는 남는 항목을 manifest 와 견주지 않으므로, 운영자가 manifest 를 바꾼 뒤에도 `config.yaml` 에 남은 서버가 대응에 있어야 한다.
    뗀 서버(`detached`)도 빈 `tools` 로 싣는다. 떼기 전에 시작한 실행이 그 서버를 쥐고 있어도 그 호출을 판정이 막는다.
    같은 이름을 지금 붙은 커넥터가 쓰면 붙은 쪽이 이긴다. 뗀 서버만 남아도 바인딩 profile 이다.
    바인딩 profile 의 hook 은 대응에 없는 서버를 통과시키므로, 서버가 빠지면 그 도구가 판정 없이 나간다(ADR-083).
    """
    roots = _connector_roots()
    detached = detached or {}
    bound = bool(detached) or any(_entry_mode(entry) == BIND_MODE for entry in state.values())
    servers = {name: {"connector": plugin, "prefix": _hermes_tool_name(name, ""), "tools": {}}
               for plugin, name in detached.items()}
    for plugin, entry in state.items():
        manifest = _connector_manifest(plugin) if plugin in roots else None
        if manifest is None:
            if bound:
                name = entry["mcp_server"]
                servers[name] = {"connector": plugin, "prefix": _hermes_tool_name(name, ""), "tools": {}}
            continue
        name = manifest["mcp_server"]
        tools = {_hermes_tool_name(name, tool): tool for tool in manifest["tools"]}
        if _entry_mode(entry) == BIND_MODE:
            recorded = entry.get("mcp_server") or name
            try:
                matches = _server_matches(manifest, entry["server"])
            except (KeyError, TypeError, AttributeError):
                matches = False
            if recorded != name or not matches:
                name, tools = recorded, {}
        servers[name] = {"connector": plugin, "prefix": _hermes_tool_name(name, ""), "tools": tools}
    if bound:
        return {"v": 1, "isolated": False, "servers": servers}
    return {"v": 1, "servers": servers}


def _detached_servers(raw: bytes | None) -> dict:
    """뗀 서버 기록의 본문을 읽는다. 파일이 없으면(`None`) 빈 객체다. 모양이 틀리면 ValueError 다.

    서버 이름은 대응 파일의 접두사가 되므로 `_connector_state` 의 서버 이름 규칙으로 본다.
    """
    if raw is None:
        return {}
    value = json.loads(raw)
    if not isinstance(value, dict) or any(
            not isinstance(plugin, str) or not CONNECTOR_ID_RE.match(plugin)
            or not isinstance(name, str) or not SERVER_NAME_RE.match(name) for plugin, name in value.items()):
        raise ValueError("뗀 서버 기록이 올바르지 않다")
    return value


def _detached_bytes(detached: dict) -> bytes | None:
    """뗀 서버 기록 파일의 본문이다. 남은 것이 없으면 None 이고 파일을 지운다."""
    return (json.dumps(detached, sort_keys=True) + "\n").encode("utf-8") if detached else None


def _entry_mode(entry) -> str:
    """소유 기록 항목의 설치 방식이다. 칸이 없으면 옛 설치다."""
    return entry.get("mode", ISOLATED_MODE) if isinstance(entry, dict) else ISOLATED_MODE


def _tool_map_bytes(tool_map: dict) -> bytes:
    """이름 대응 파일의 본문이다. hook 상태 판정이 바이트로 견주므로 직렬화를 하나로 고정한다."""
    return (json.dumps(tool_map, sort_keys=True, ensure_ascii=False) + "\n").encode("utf-8")


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


def _server_matches(manifest: dict, server: dict) -> bool:
    """소유 기록의 서버 정의가 지금 manifest 의 실행 정의와 같은지 본다.

    칸의 env 는 자기 이름의 참조이고, 선택 칸은 명시한 빈 값도 된다.
    운영자 env 는 옛 기록의 `${이름}` 참조와 지금 정의의 직접 값을 같다고 본다.
    옛 판이 남긴 기록을 그대로 인정해야 이미 설치한 연결이 끊기지 않는다(ADR-041).
    `tools` 는 견주지 않는다. 옛 기록에는 그 키가 없고, 다시 보낸 설치가 지금 manifest 의 값으로 덮어쓴다.
    """
    expected = manifest["server"]
    if (server["command"] != expected["command"] or server["args"] != expected["args"]
            or set(server["env"]) != set(expected["env"])):
        return False
    for name, value in server["env"].items():
        reference = "${%s}" % name
        if name in manifest["operator_env"]:
            if value not in (reference, expected["env"][name]):
                return False
        elif value != reference and not (value == "" and name in manifest["optional_env"]):
            return False
    return True


def _connector_state(value, target: str | None = None) -> dict:
    """소유 기록의 모양을 보고, 운영 목록의 커넥터는 지금 manifest 의 실행 정의와 맞는지 본다.

    운영 목록에서 빠진 커넥터의 기록은 모양만 본다. 그 기록으로는 설치를 끄는 것만 한다.
    바인딩 항목(`mode: bind`)은 서버 이름, 보관 파일 이름, 설치한 스킬 이름을 갖는다.
    스킬 이름은 떼기가 지울 디렉터리 이름이라 경로 조각으로 쓸 수 있는지 여기서 본다.
    바인딩 항목은 요청이 가리키는 커넥터(`target`)의 것만 manifest 와 견주고 나머지는 모양만 본다.
    운영자가 커넥터 하나를 바꿔도 같은 profile 에 붙은 다른 커넥터의 실행, probe, 조회, 붙이기가 실패하지 않게 한다.
    맞지 않는 다른 항목은 이름 대응이 빈 `tools` 로 싣는다. 옛 설치 항목은 지금처럼 모두 견준다.
    """
    if not isinstance(value, dict):
        raise ValueError("connector 소유 기록이 올바르지 않다")
    roots = _connector_roots()
    for plugin, entry in value.items():
        if (not isinstance(entry, dict) or not {"server", "allowlist_added"} <= set(entry)
                or set(entry) - {"server", "allowlist_added", "mcp_server", "mode", "vault", "skills"}
                or not isinstance(entry["allowlist_added"], bool)
                or not isinstance(entry.get("mcp_server", ""), str)
                or entry.get("mode", ISOLATED_MODE) not in (ISOLATED_MODE, BIND_MODE)):
            raise ValueError("connector 소유 기록의 필드가 올바르지 않다")
        if entry.get("mode") == BIND_MODE:
            skills = entry.get("skills")
            if (not {"mcp_server", "vault", "skills"} <= set(entry)
                    or not SERVER_NAME_RE.match(entry["mcp_server"])
                    or not isinstance(entry["vault"], str) or not VAULT_ID_RE.match(entry["vault"])
                    or not isinstance(skills, list) or len(set(map(str, skills))) != len(skills)
                    or any(not isinstance(name, str) or not SKILL_NAME_RE.match(name) or ".." in name
                           for name in skills)):
                raise ValueError("connector 소유 기록의 바인딩 필드가 올바르지 않다")
        elif "vault" in entry or "skills" in entry:
            raise ValueError("옛 설치의 소유 기록에 바인딩 필드가 있다")
        server = entry["server"]
        tools = server.get("tools") if isinstance(server, dict) else None
        if (not isinstance(server, dict) or set(server) - {"tools"} != {"command", "args", "env", "enabled"}
                or ("tools" in server and (
                    not isinstance(tools, dict) or set(tools) != {"exclude"} or not isinstance(tools["exclude"], list)
                    or any(not isinstance(item, str) for item in tools["exclude"])))
                or not isinstance(server["command"], str) or server["enabled"] is not True
                or not isinstance(server["args"], list) or any(not isinstance(arg, str) for arg in server["args"])
                or not isinstance(server["env"], dict)
                or any(not isinstance(name, str) or not isinstance(item, str)
                       for name, item in server["env"].items())):
            raise ValueError("소유 기록의 실행 정의 모양이 올바르지 않다")
        if plugin not in roots or (entry.get("mode") == BIND_MODE and plugin != target):
            continue
        if not _entry_matches_manifest(plugin, entry):
            raise ValueError("소유 기록의 실행 정의가 지금 connector 와 다르다")
    return value


def _entry_matches_manifest(plugin: str, entry: dict) -> bool:
    """모양을 본 소유 기록 항목이 지금 manifest 의 서버 이름과 실행 정의와 맞는지 본다. manifest 가 없으면 거짓이다."""
    manifest = _connector_manifest(plugin)
    return (manifest is not None and _server_matches(manifest, entry["server"])
            and entry.get("mcp_server", manifest["mcp_server"]) == manifest["mcp_server"])


def _connector_allowlist(state: dict, servers: dict) -> list:
    """소유 기록의 커넥터 서버 이름과 그 커넥터들이 선언한 내장 toolset 으로 만든 API 도구 목록이다.

    서버 이름을 먼저 두고 manifest 의 `toolsets` 를 뒤에 둔다. 겹친 이름은 한 번만 싣는다.
    manifest 가 열 수 있는 내장 toolset 은 읽기 전용 이미지 도구뿐이다(ADR-044).
    운영 목록에서 빠졌거나 검증에 실패해 manifest 를 읽을 수 없는 커넥터는 toolset 을 더하지 않는다.
    기록이 없으면 MCP 를 전부 막는 값이다.
    목록에 등록된 MCP 서버 이름이 하나도 없으면 Hermes 가 등록된 서버를 모두 통과시킨다.
    그래서 빈 목록 대신 `no_mcp` 를 쓴다(ADR-045).
    옛 기록에는 서버 이름 칸이 없어 기록과 같은 서버 정의를 설정에서 찾는다.
    """
    roots = _connector_roots()
    names, toolsets = [], []
    for plugin, entry in state.items():
        name = entry.get("mcp_server") or next(
            (key for key, value in servers.items() if value == entry["server"]), None)
        if name is None:
            raise FileExistsError("설치한 MCP 서버가 밖에서 지워졌거나 바뀌었다")
        names.append(name)
        manifest = _connector_manifest(plugin) if plugin in roots else None
        if manifest is not None:
            toolsets.extend(manifest["toolsets"])
    if not names:
        return ["no_mcp"]
    return list(dict.fromkeys(names + toolsets))


def _remove_backup_env_copies(profile_dir: pathlib.Path) -> None:
    """이전 판이 백업에 남긴 `.env` 사본을 지운다. 백업 디렉터리가 없으면 아무것도 하지 않는다."""
    backups = profile_dir / "connector-backups"
    if not backups.is_dir():
        return
    for stale in backups.glob("*/.env"):
        try:
            stale.unlink()
        except OSError as error:
            logger.warning("dashboard-profile-api: 백업의 옛 env 사본을 지우지 못했다: %s", type(error).__name__)


def _connector_config(profile_dir: pathlib.Path, plugin: str, enabled: bool,
                      sandbox_terminal: Optional[dict] = None, local_execution: bool = False,
                      attachment_guard: Optional[tuple] = None) -> dict:
    """관리 표식 profile 의 설정을 바꾸고 실패하면 같은 요청 안에서 되돌린다.

    설치와 제거는 API 도구 목록을 커넥터 서버 이름과 manifest 가 선언한 내장 toolset 으로 다시 쓰고,
    설치는 Control Plane MCP 등록도 지운다(ADR-045).
    둘 다 이름 대응 파일을 바뀐 소유 기록으로 다시 쓰고, 설치는 정책 hook 을 가진 profile plugin 을 묶음의 판으로 맞춘다(ADR-049).
    `plugin_updated` 는 그 plugin 파일이 바뀌었는지다. 떠 있는 gateway 가 옛 코드를 쥐고 있을 수 있어 따로 답한다.
    이 설치는 커넥터 전용 profile 을 전제한다. Control Plane 이 커넥터 에이전트의 profile 로만 부른다.
    일반 에이전트의 profile 에 설치하면 그 profile 의 Control Plane MCP 등록과 도구 목록이 사라지고 제거해도 돌아오지 않는다.
    그래서 바인딩 설치가 있는 profile 에는 설치하지 않는다. 일반 에이전트에 붙이는 것은 `_connector_bind_config` 다.
    """
    import yaml
    if attachment_guard is not None:
        _sandbox_validate_attachment_snapshot(*attachment_guard)
    if profile_dir.resolve() != profile_dir:
        raise ValueError("profile 경로에 심볼릭 링크가 있다")
    config_path = profile_dir / "config.yaml"
    state_path = profile_dir / CONNECTOR_STATE
    env_path = profile_dir / ".env"
    soul_path = profile_dir / SOUL_FILE
    tool_map_path = profile_dir / CONNECTOR_TOOL_MAP
    plugin_dir = profile_dir / "plugins" / POLICY_PLUGIN
    # 묶음에 그 plugin 이 없으면 건드리지 않는다. 설치는 그대로 되고 hook 상태 조회가 거짓으로 답한다.
    bundled = (_profile_plugin_files(POLICY_PLUGIN) if enabled else None) or {}
    plugin_values = {plugin_dir / file_name: value for file_name, value in bundled.items()}
    plugin_dirs = (plugin_dir.parent, plugin_dir) if plugin_values else ()
    for path in (config_path, state_path, env_path, soul_path, tool_map_path, *plugin_dirs, *plugin_values):
        if path.is_symlink():
            raise ValueError("profile 설정에 심볼릭 링크가 있다")
    # 바뀐 것이 없어 일찍 돌아가는 요청에서도 옛 사본은 지운다.
    _remove_backup_env_copies(profile_dir)
    originals = {path: path.read_bytes() if path.exists() else None
                 for path in (config_path, state_path, env_path, soul_path, tool_map_path, *plugin_values)}
    saved = yaml.safe_load(originals[config_path]) or {}
    state = _connector_state(json.loads(originals[state_path]), plugin) if originals[state_path] else {}
    detached_path = profile_dir / CONNECTOR_DETACHED
    if enabled and (any(_entry_mode(entry) == BIND_MODE for entry in state.values())
                    or detached_path.exists() or detached_path.is_symlink()):
        # 두 설치를 섞으면 이 설치가 지우는 Control Plane MCP 등록을 바인딩 설치가 전제하므로 서로를 깬다.
        # 뗀 서버 기록이 남은 profile 은 바인딩 profile 이다. 옛 설치의 이름 대응이 그 기록을 싣지 않는다.
        raise FileExistsError("바인딩 설치가 있는 profile 에 옛 설치를 하지 않는다")
    servers = dict(saved.get("mcp_servers") or {})
    owned = state.get(plugin)
    listed = plugin in _connector_roots()
    manifest = _connector_manifest(plugin) if listed else None
    if manifest is not None:
        name = manifest["mcp_server"]
    elif enabled:
        raise ValueError("쓸 수 없는 connector 는 설치하지 않는다")
    elif not owned:
        # 끌 것이 없다. 운영 목록에서 빠진 연결의 해제가 끝까지 가도록 성공으로 답한다.
        return {"changed": False, "restart_required": False, "plugin_updated": False}
    else:
        # 운영 목록에서 빠진 커넥터다. 옛 기록에는 서버 이름이 없어 기록과 같은 정의를 설정에서 찾는다.
        name = owned.get("mcp_server") or next(
            (key for key, value in servers.items() if value == owned["server"]), None)
        if name is None:
            raise FileExistsError("설치한 MCP 서버가 밖에서 지워졌거나 바뀌었다")
    if name in servers and (not owned or servers[name] != owned["server"]):
        raise FileExistsError("운영자가 등록하거나 바꾼 MCP 서버가 있다")
    if owned and name not in servers:
        raise FileExistsError("설치한 MCP 서버가 밖에서 지워졌다")
    allowed = list((saved.get("platform_toolsets") or {}).get("api_server") or [])
    env_text = originals[env_path].decode("utf-8") if originals[env_path] else ""
    values = {}
    if enabled:
        server = _connector_server(manifest)
        # Hermes 는 빈 변수의 참조를 그대로 남긴다. 값이 없는 선택 칸은 빈 값을 명시한다.
        for env_name in manifest["optional_env"]:
            if not _env_value(env_text, env_name):
                server["env"][env_name] = ""
        # `allowlist_added` 는 더 읽지 않는다. 옛 판이 남긴 기록을 읽을 수 있게 칸만 남긴다.
        added = owned["allowlist_added"] if owned else True
        servers[name] = server
        # 커넥터 에이전트는 Control Plane 도구를 받지 않는다. 제거는 이 등록을 되살리지 않는다.
        servers.pop(CONTROL_PLANE_MCP, None)
        state[plugin] = {"server": server, "allowlist_added": added, "mcp_server": name}
        allowed = _connector_allowlist(state, servers)
        if manifest["persona"] is not None:
            # 지침은 이 커넥터의 소유 기록과 함께, 관리 표식이 있는 profile 에만 쓴다.
            # 사람이 만든 profile 과 이 요청이 가리키지 않은 profile 의 `SOUL.md` 는 건드리지 않는다.
            # 연결용 profile 인지는 여기서 알 수 없다. Control Plane 이 연결용 에이전트의 profile 만 보낸다.
            if not (profile_dir / MANAGED_MARKER).is_file():
                raise ValueError("관리 표식이 없는 profile 에는 지침을 쓰지 않는다")
            values[soul_path] = manifest["persona"].encode("utf-8")
    elif owned:
        servers.pop(name, None)
        state.pop(plugin, None)
        allowed = _connector_allowlist(state, servers)
        if manifest is None and originals[env_path] is not None:
            # Control Plane 은 목록에서 빠진 커넥터의 env 이름을 모른다. 기록이 참조하던 key 를 여기서 지운다.
            referenced = {key for key, value in owned["server"]["env"].items() if value == "${%s}" % key}
            kept = [line for line in env_text.splitlines(keepends=True)
                    if line.partition("=")[0] not in referenced]
            values[env_path] = "".join(kept).encode("utf-8")
    updated = {**saved, "mcp_servers": servers,
               "platform_toolsets": {**(saved.get("platform_toolsets") or {}), "api_server": allowed}}
    if sandbox_terminal is not None:
        updated["terminal"] = sandbox_terminal
    elif local_execution:
        previous = saved.get("terminal") or {}
        if not isinstance(previous, dict):
            raise ValueError("terminal 설정이 객체가 아니다")
        terminal = dict(previous) if previous.get("backend", "local") == "local" else {}
        terminal["backend"] = "local"
        updated["terminal"] = terminal
    values = {config_path: yaml.safe_dump(updated, sort_keys=False, allow_unicode=True).encode(),
              state_path: (json.dumps(state) + "\n").encode(), **values}
    if enabled or owned:
        # 설정만 바뀌고 대응이 옛것으로 남으면 hook 이 새 도구를 모두 막는다. 같은 묶음 안에서 쓴다.
        values[tool_map_path] = _tool_map_bytes(_connector_tool_map(state))
    # plugin 파일은 맨 뒤에 쓴다. 앞의 쓰기가 실패하면 plugin 은 손대지 않은 채 남는다.
    values.update(plugin_values)
    plugin_updated = any(originals[path] != value for path, value in plugin_values.items())
    if all(originals[path] == value for path, value in values.items()):
        return {"changed": False, "restart_required": bool(owned), "plugin_updated": False}
    backup = profile_dir / "connector-backups" / str(time.time_ns())
    backup.mkdir(parents=True, mode=0o700)
    os.chmod(backup.parent, 0o700)
    for path, value in originals.items():
        # profile `.env` 에는 사용자의 비밀 원문이 있다. 백업에 넣으면 연결을 해제한 뒤에도 남는다.
        if value is None or path == env_path:
            continue
        if path not in plugin_values:
            _atomic_private_write(backup / path.name, value)
        elif value != plugin_values[path]:
            # 묶음의 판과 같은 plugin 파일은 뜨지 않는다. 다른 것만 plugin 이름을 붙여 남긴다.
            _atomic_private_write(backup / ("%s.%s" % (POLICY_PLUGIN, path.name)), value)
    written = []
    created_dirs = []
    try:
        if any((path.read_bytes() if path.exists() else None) != value for path, value in originals.items()):
            raise FileExistsError("저장 전 profile 설정이 밖에서 바뀌었다")
        for directory in plugin_dirs:
            if not directory.is_dir():
                directory.mkdir()
                created_dirs.append(directory)
                os.chmod(directory, 0o755)
        for path, value in values.items():
            if (path.read_bytes() if path.exists() else None) != originals[path]:
                raise FileExistsError("profile 설정이 밖에서 바뀌었다")
            if path == config_path and attachment_guard is not None:
                _sandbox_validate_attachment_snapshot(*attachment_guard)
            _atomic_private_write(path, value)
            written.append(path)
            if path in plugin_values:
                # 임시 파일은 600 으로 생긴다. plugin 파일은 새 profile 에 복사할 때와 같은 644 로 둔다.
                os.chmod(path, 0o644)
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
                if path in plugin_values:
                    os.chmod(path, 0o644)
        for directory in reversed(created_dirs):
            # 이 요청이 만든 디렉터리만 지운다. 그 사이 밖에서 무엇이 생겼으면 비어 있지 않아 남는다.
            try:
                directory.rmdir()
            except OSError:
                pass
        raise
    return {"changed": True, "restart_required": bool(owned), "plugin_updated": plugin_updated}


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


def _entry_field_env(plugin: str, entry: dict) -> set:
    """소유 기록 항목의 커넥터가 profile `.env` 에 두는 이름이다.

    manifest 가 있으면 칸의 env 이름이고, 운영 목록에서 빠졌으면 기록의 서버 정의가 `${이름}` 으로 참조하던 이름이다.
    """
    manifest = _connector_manifest(plugin) if plugin in _connector_roots() else None
    if manifest is not None:
        return {field["env"] for field in manifest["fields"]}
    return {key for key, value in entry["server"]["env"].items() if value == "${%s}" % key}


def _bind_entry_shape(entry) -> None:
    """떼기가 쓰는 바인딩 항목의 모양만 본다. 지금 manifest 의 실행 정의와는 견주지 않는다.

    항목이 객체이고 `mode` 가 `bind` 여야 한다. 서버 이름과 스킬 이름은 설정에서 지울 키와 지울 디렉터리 이름이라
    경로 조각으로 쓸 수 있는지 `_connector_state` 와 같은 규칙으로 본다.
    """
    skills = entry.get("skills") if isinstance(entry, dict) else None
    if (not isinstance(entry, dict) or entry.get("mode") != BIND_MODE
            or not isinstance(entry.get("mcp_server"), str) or not SERVER_NAME_RE.match(entry["mcp_server"])
            or not isinstance(skills, list) or len(set(map(str, skills))) != len(skills)
            or any(not isinstance(name, str) or not SKILL_NAME_RE.match(name) or ".." in name for name in skills)):
        raise ValueError("connector 소유 기록의 바인딩 필드가 올바르지 않다")


def _bind_entry_env(manifest: dict | None, entry: dict) -> set:
    """떼기가 profile `.env` 에서 지울 이름이다.

    기록의 서버 정의가 `${이름}` 으로 참조하는 이름은 늘 지운다. 운영 목록에서 빠졌거나 실행 정의가 바뀌어도 설치할 때 쓴
    이름이 거기 남아 있다. manifest 가 있고 기록의 실행 정의가 지금과 같으면 칸의 env 이름도 지운다.
    Control Plane 이 쓰는 이름은 지우지 않는다.
    """
    server = entry.get("server")
    env = server.get("env") if isinstance(server, dict) else None
    names = {key for key, value in env.items() if isinstance(key, str) and value == "${%s}" % key} \
        if isinstance(env, dict) else set()
    if manifest is not None:
        try:
            matches = _server_matches(manifest, server)
        except (KeyError, TypeError, AttributeError):
            matches = False
        if matches:
            names |= {field["env"] for field in manifest["fields"]}
    return names - BASE_ENV_KEYS


def _skill_tree_files(directory: pathlib.Path) -> dict | None:
    """profile 에 설치한 스킬 디렉터리 하나의 정규 파일이다. `{상대 경로: 경로}` 다. 없으면 None 이다.

    그 아래 어느 항목이든 링크이면 예외다. 링크를 따라 profile 밖의 파일을 지우거나 읽지 않는다.
    """
    if directory.is_symlink():
        raise ValueError("스킬 디렉터리가 링크다")
    if not directory.is_dir():
        return None
    files = {}
    for current, dirs, names in os.walk(directory):
        for child in dirs + names:
            if os.path.islink(os.path.join(current, child)):
                raise ValueError("스킬 디렉터리 아래에 링크가 있다")
        for name in names:
            path = pathlib.Path(current) / name
            files[path.relative_to(directory).as_posix()] = path
    return files


def _connector_bind_config(profile_dir: pathlib.Path, plugin: str, enabled: bool,
                           vault: str | None = None, values: dict | None = None) -> dict:
    """일반 에이전트의 profile 에 커넥터를 붙이거나 뗀다. 실패하면 같은 요청 안에서 이 요청이 쓴 파일만 되돌린다.

    붙이기는 보관 파일의 값(`values`)을 그 profile `.env` 에 쓰고, 서버를 더하고, API 도구 목록에 서버 이름을 더하고,
    plugin 의 스킬을 그 profile 의 스킬로 복사한다. Control Plane MCP 등록, 다른 도구 이름, `SOUL.md` 는 건드리지 않는다.
    새로 붙이기는 그 profile 에서 정책 hook plugin 이 켜져 있어야 한다. 꺼진 profile 에 붙이면 도구 호출이 판정 없이 나간다.
    이미 붙은 커넥터를 다시 설치하는 것은 hook 이 꺼져 있어도 받는다. 그때 그 바인딩은 hook 상태로 `PENDING` 에 남는다.
    떼기는 그 서버와 이름과 env 와 스킬만 지운다(ADR-083).
    떼기는 서버 이름을 뗀 서버 기록에 남기고 이름 대응에 빈 `tools` 로 남긴다. 대응 파일은 지우지 않는다.
    같은 커넥터를 다시 붙이면 그 기록을 지운다.
    떠 있는 profile 에 더한 서버는 gateway 를 다시 띄워야 보이므로 붙이기는 바뀐 것이 있으면 재시작이 필요하다고 답한다.
    떼기는 재시작이 필요 없다고 답한다. 다음 실행은 도구 목록에서 이름이 빠져 그 서버를 받지 않고,
    떼기 전에 시작해 그 서버를 쥔 실행의 호출은 대응에 남은 서버를 보고 hook 이 묻고 판정이 막는다.
    """
    import yaml
    if profile_dir.resolve() != profile_dir:
        raise ValueError("profile 경로에 심볼릭 링크가 있다")
    config_path = profile_dir / "config.yaml"
    state_path = profile_dir / CONNECTOR_STATE
    env_path = profile_dir / ".env"
    tool_map_path = profile_dir / CONNECTOR_TOOL_MAP
    detached_path = profile_dir / CONNECTOR_DETACHED
    skills_dir = profile_dir / PROFILE_SKILLS_DIR
    plugin_dir = profile_dir / "plugins" / POLICY_PLUGIN
    bundled = (_profile_plugin_files(POLICY_PLUGIN) if enabled else None) or {}
    plugin_values = {plugin_dir / file_name: value for file_name, value in bundled.items()}
    plugin_dirs = (plugin_dir.parent, plugin_dir) if plugin_values else ()
    for path in (config_path, state_path, env_path, tool_map_path, detached_path, skills_dir, *plugin_dirs,
                 *plugin_values):
        if path.is_symlink():
            raise ValueError("profile 설정에 심볼릭 링크가 있다")
    _remove_backup_env_copies(profile_dir)
    originals = {path: path.read_bytes() if path.exists() else None
                 for path in (config_path, state_path, env_path, tool_map_path, detached_path, *plugin_values)}
    saved = yaml.safe_load(originals[config_path]) or {}
    detached = _detached_servers(originals[detached_path])
    if not originals[state_path]:
        state = {}
    elif enabled:
        state = _connector_state(json.loads(originals[state_path]), plugin)
    else:
        # 떼기는 기록 전체를 지금 manifest 와 견주지 않는다. 운영자가 실행 정의를 바꾼 뒤에도 떼야 `.env` 의 비밀이 남지 않는다.
        state = json.loads(originals[state_path])
        if not isinstance(state, dict):
            raise ValueError("connector 소유 기록이 올바르지 않다")
    if any(_entry_mode(entry) != BIND_MODE for entry in state.values()):
        raise FileExistsError("옛 설치가 있는 profile 에 바인딩을 하지 않는다")
    servers = dict(saved.get("mcp_servers") or {})
    platform = dict(saved.get("platform_toolsets") or {})
    allowed = platform.get("api_server")
    owned = state.get(plugin)
    if not enabled and owned is not None:
        _bind_entry_shape(owned)
    manifest = _connector_manifest(plugin) if plugin in _connector_roots() else None
    env_lines = originals[env_path].decode("utf-8").splitlines(keepends=True) if originals[env_path] else []
    previous_skills = list(owned["skills"]) if owned else []

    if enabled:
        if manifest is None:
            raise ValueError("쓸 수 없는 connector 는 설치하지 않는다")
        # 목록이 없는 profile 에 이름 하나만 든 목록을 만들면 내장 도구와 Control Plane MCP 가 모두 닫히고,
        # MCP 이름이 하나도 없던 목록에 이름을 더하면 운영자의 다른 MCP 서버가 막힌다.
        if not isinstance(allowed, list) or CONTROL_PLANE_MCP not in allowed:
            raise FileExistsError("API 도구 목록에 Control Plane MCP 가 없는 profile 이다")
        # 이 설치는 plugin 파일만 맞추고 profile 의 plugin 설정은 쓰지 않는다. 운영자가 끈 hook 을 대신 켜지 않는다.
        # 이미 붙은 커넥터를 다시 설치하는 것은 받는다. 연결 확인이 다시 설치하고, hook 상태가 거짓이라 반영 완료가 `READY` 로 두지 않는다.
        if not owned and not _policy_plugin_enabled(saved):
            raise FileExistsError("정책 hook plugin 이 켜져 있지 않은 profile 이다")
        name = manifest["mcp_server"]
        if name in servers and (not owned or servers[name] != owned["server"]):
            raise FileExistsError("운영자가 등록하거나 바꾼 MCP 서버가 있다")
        if owned and name not in servers:
            raise FileExistsError("설치한 MCP 서버가 밖에서 지워졌다")
        field_env = [field["env"] for field in manifest["fields"]]
        others = set()
        for other, entry in state.items():
            if other != plugin:
                others |= _entry_field_env(other, entry)
        present = {_env_line_key(line) for line in env_lines}
        for env_name in field_env:
            if env_name in BASE_ENV_KEYS or env_name in others or (env_name in present and not owned):
                raise FileExistsError("다른 설정이 쓰는 환경 변수와 겹친다")
        server = _connector_server(manifest)
        # Hermes 는 빈 변수의 참조를 그대로 남긴다. 값이 없는 선택 칸은 빈 값을 명시한다.
        for field in manifest["fields"]:
            if field["env"] in manifest["optional_env"] and not values.get(field["key"]):
                server["env"][field["env"]] = ""
        servers[name] = server
        allowed = [item for item in allowed if item != "no_mcp"]
        if name not in allowed:
            allowed.append(name)
        # 있던 줄은 그 자리에서 바꾸고 새 줄은 뒤에 붙인다. 같은 값으로 다시 붙이면 파일이 그대로다.
        wanted = {field["env"]: _env_line(field["env"], values[field["key"]]) for field in manifest["fields"]
                  if values.get(field["key"])}
        kept = []
        for line in env_lines:
            key = _env_line_key(line)
            if key not in field_env:
                kept.append(line)
            elif key in wanted:
                kept.append(wanted.pop(key))
        if kept and not kept[-1].endswith("\n"):
            kept[-1] += "\n"
        kept.extend(wanted.values())
        new_env = "".join(kept).encode("utf-8") if kept or originals[env_path] is not None else None
        skills = sorted(manifest["skills"])
        for skill in skills:
            if (skills_dir / skill).exists() and skill not in previous_skills:
                raise FileExistsError("이미 있는 스킬 디렉터리와 이름이 겹친다")
        state[plugin] = {"server": server, "allowlist_added": True, "mcp_server": name,
                         "mode": BIND_MODE, "vault": vault, "skills": skills}
        # 뗀 기록은 같은 이름일 때만 지운다. 그 사이 서버 이름이 바뀌었으면 옛 이름을 쥔 실행이 아직 있을 수 있다.
        if detached.get(plugin) == name:
            detached.pop(plugin)
        desired = {skills_dir / skill / relative: data
                   for skill in skills for relative, data in manifest["skills"][skill].items()}
    else:
        if not owned:
            return {"changed": False, "restart_required": False, "plugin_updated": False}
        name = owned["mcp_server"]
        # 기록과 다른 정의는 이 설치가 쓴 것이 아니다. 지우지 않고 멈춘다. 밖에서 이미 지워졌으면 지울 것이 없다.
        if name in servers and servers[name] != owned["server"]:
            raise FileExistsError("운영자가 바꾼 MCP 서버가 있다")
        servers.pop(name, None)
        if isinstance(allowed, list):
            allowed = [item for item in allowed if item != name]
        field_env = _bind_entry_env(manifest, owned)
        kept = [line for line in env_lines if _env_line_key(line) not in field_env]
        new_env = "".join(kept).encode("utf-8") if originals[env_path] is not None else None
        state.pop(plugin)
        detached[plugin] = name
        skills = []
        desired = {}

    # 이 커넥터가 전에 설치한 스킬의 파일 가운데 이번에 쓰지 않는 것은 지운다.
    stale = []
    for skill in previous_skills:
        found = _skill_tree_files(skills_dir / skill) or {}
        stale.extend(path for path in found.values() if path not in desired)
    for path in desired:
        for parent in path.relative_to(skills_dir).parents:
            if (skills_dir / parent).is_symlink():
                raise ValueError("스킬 경로에 심볼릭 링크가 있다")
        if path.is_symlink():
            raise ValueError("스킬 경로에 심볼릭 링크가 있다")
    for path in (*desired, *stale):
        originals[path] = path.read_bytes() if path.exists() else None

    if isinstance(allowed, list):
        platform["api_server"] = allowed
    updated = {**saved, "mcp_servers": servers, "platform_toolsets": platform}
    targets = {config_path: yaml.safe_dump(updated, sort_keys=False, allow_unicode=True).encode(),
               state_path: (json.dumps(state) + "\n").encode(), env_path: new_env,
               detached_path: _detached_bytes(detached),
               # 마지막 바인딩을 떼도 대응 파일은 뗀 서버를 싣고 남는다. 떼기 전에 시작한 실행이 그 서버를 쥐고 있다.
               tool_map_path: _tool_map_bytes(_connector_tool_map(state, detached)) if state or detached else None,
               **desired, **{path: None for path in stale}}
    # plugin 파일은 맨 뒤에 쓴다. 앞의 쓰기가 실패하면 plugin 은 손대지 않은 채 남는다.
    targets.update(plugin_values)
    plugin_updated = any(originals[path] != value for path, value in plugin_values.items())
    if all(originals[path] == value for path, value in targets.items()):
        return {"changed": False, "restart_required": False, "plugin_updated": False}
    backup = profile_dir / "connector-backups" / str(time.time_ns())
    backup.mkdir(parents=True, mode=0o700)
    os.chmod(backup.parent, 0o700)
    for path in (config_path, state_path, tool_map_path, detached_path):
        # `.env` 와 스킬 파일은 뜨지 않는다. `.env` 에는 사용자의 비밀 원문이 있다.
        if originals[path] is not None:
            _atomic_private_write(backup / path.name, originals[path])
    for path, value in plugin_values.items():
        if originals[path] is not None and originals[path] != value:
            _atomic_private_write(backup / ("%s.%s" % (POLICY_PLUGIN, path.name)), originals[path])

    def shared(path):
        # 스킬과 plugin 파일은 gateway 가 읽는 파일이다. 새 profile 에 plugin 을 복사할 때와 같은 644 로 둔다.
        return path in plugin_values or skills_dir in path.parents

    written = []
    created_dirs = []
    try:
        if any((path.read_bytes() if path.exists() else None) != value for path, value in originals.items()):
            raise FileExistsError("저장 전 profile 설정이 밖에서 바뀌었다")
        for path, value in targets.items():
            current = path.read_bytes() if path.exists() else None
            if current != originals[path]:
                raise FileExistsError("profile 설정이 밖에서 바뀌었다")
            if current == value:
                continue
            if value is None:
                path.unlink()
            else:
                for directory in reversed(path.parents):
                    if directory in profile_dir.parents or directory == profile_dir or directory.is_dir():
                        continue
                    directory.mkdir()
                    created_dirs.append(directory)
                    os.chmod(directory, 0o755)
                _atomic_private_write(path, value)
                if shared(path):
                    os.chmod(path, 0o644)
            written.append(path)
    except Exception:
        for path in reversed(written):
            # 이 요청이 쓴 값일 때만 복원한다. 바깥의 새 수정은 덮어쓰지 않는다.
            if (path.read_bytes() if path.exists() else None) != targets[path]:
                continue
            value = originals[path]
            if value is None:
                path.unlink(missing_ok=True)
            else:
                _atomic_private_write(path, value)
                if shared(path):
                    os.chmod(path, 0o644)
        for directory in reversed(created_dirs):
            # 이 요청이 만든 디렉터리만 지운다. 그 사이 밖에서 무엇이 생겼으면 비어 있지 않아 남는다.
            try:
                directory.rmdir()
            except OSError:
                pass
        raise
    # 파일을 모두 지운 스킬 디렉터리는 빈 디렉터리만 남는다. 지우지 못해도 설치는 끝난 것이다.
    for skill in previous_skills:
        for current, dirs, names in os.walk(skills_dir / skill, topdown=False):
            try:
                os.rmdir(current)
            except OSError:
                pass
    return {"changed": True, "restart_required": enabled, "plugin_updated": plugin_updated}


def _bind_skills_installed(profile_dir: pathlib.Path, manifest: dict, entry: dict) -> bool:
    """소유 기록의 스킬이 plugin 의 스킬과 같고, 설치한 파일이 plugin 의 본문과 같은지 본다. 읽다가 예외가 나면 거짓이다."""
    try:
        if sorted(entry["skills"]) != sorted(manifest["skills"]):
            return False
        skills_dir = profile_dir / PROFILE_SKILLS_DIR
        if skills_dir.is_symlink():
            return False
        for skill, files in manifest["skills"].items():
            found = _skill_tree_files(skills_dir / skill)
            if found is None or {key: path.read_bytes() for key, path in found.items()} != files:
                return False
        return True
    except Exception:
        return False


def _policy_plugin_enabled(config: dict) -> bool:
    """profile 설정이 정책 hook plugin 을 켜고 도구 덮어쓰기를 막는지 본다. 읽다가 예외가 나면 거짓이다.

    `plugins.enabled` 에 있고 `plugins.disabled` 에 없으며 `plugins.entries.fos-ctx.allow_tool_override` 가 `false` 여야 한다.
    """
    try:
        plugins = config["plugins"]
        disabled = plugins.get("disabled") or []
        return (isinstance(plugins["enabled"], list) and POLICY_PLUGIN in plugins["enabled"]
                and isinstance(disabled, list) and POLICY_PLUGIN not in disabled
                and plugins["entries"][POLICY_PLUGIN]["allow_tool_override"] is False)
    except Exception:
        return False


def _policy_hook_active(profile_dir: pathlib.Path, config: dict, state: dict) -> bool:
    """그 profile 에서 커넥터 도구 호출이 정책 hook 을 거치는지 본다. 읽다가 예외가 나면 거짓이다.

    조건은 `docs/backend/connector-tool-policy.md` 의 「hook 이 켜져 있는지」 가 갖는다. 확인한 시점의 파일만 본다.
    """
    try:
        if not _policy_plugin_enabled(config):
            return False
        bundled = _profile_plugin_files(POLICY_PLUGIN)
        if bundled is None:
            return False
        for file_name, value in bundled.items():
            installed = profile_dir / "plugins" / POLICY_PLUGIN / file_name
            if installed.is_symlink() or installed.read_bytes() != value:
                return False
        detached_path = profile_dir / CONNECTOR_DETACHED
        if detached_path.is_symlink():
            return False
        detached = _detached_servers(detached_path.read_bytes() if detached_path.exists() else None)
        if (profile_dir / CONNECTOR_TOOL_MAP).read_bytes() != _tool_map_bytes(_connector_tool_map(state, detached)):
            return False
        roots = _connector_roots()
        servers = config.get("mcp_servers") or {}
        for plugin, entry in state.items():
            manifest = _connector_manifest(plugin) if plugin in roots else None
            # 늘 승인이 필요한 도구가 모델에 등록된 채이면 hook 이 켜져 있어도 설치가 덜 된 것이다.
            # manifest 와 맞지 않는 바인딩 항목은 대응에 빈 `tools` 로 실려 모든 호출을 묻는다. 그 항목만 쓸 수 없다.
            if manifest is None or (_entry_mode(entry) == BIND_MODE and not _entry_matches_manifest(plugin, entry)):
                continue
            if servers[manifest["mcp_server"]].get("tools") != manifest["server"].get("tools"):
                return False
        return True
    except Exception:
        return False


def _owned_mode(profile_dir: pathlib.Path, plugin: str) -> str | None:
    """소유 기록에서 그 커넥터 항목의 설치 방식만 읽는다. 항목이 없거나 기록을 읽지 못하면 None 이다.

    표식 판정과 처리 경로를 고르는 데만 쓴다. 기록 전체의 검증은 설치와 떼기가 한다.
    """
    try:
        state = json.loads((profile_dir / CONNECTOR_STATE).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(state, dict) or not isinstance(state.get(plugin), dict):
        return None
    return _entry_mode(state[plugin])


async def _connector_request(request):
    from hermes_cli.profiles import get_profile_dir
    from starlette.responses import JSONResponse
    roots = _connector_roots()
    if request.method.upper() == "GET":
        profiles = request.query_params.getlist("profile")
        if len(profiles) != 1 or set(request.query_params.keys()) != {"profile"}:
            return _rejected("query 에 profile 하나만 필요하다")
        body = {"profile": profiles[0]}
    else:
        body = await _json_object(request)
        bind = body.get("bind") if isinstance(body, dict) else None
        # 운영 목록에 없는 이름은 끄기만 받는다. 소유 기록이 있으면 설치를 끄고, 없으면 바꾸지 않고 성공이다.
        # `bind` 는 바인딩 설치에서만 뜻이 있다. 떼기는 소유 기록의 설치 방식을 따른다.
        # `sandbox_owner` 는 사진 도구를 여는 옛 설치에서만 쓴다. 바인딩 설치는 API 도구 목록의 내장 toolset 을 바꾸지 않는다.
        if (body is None
                or not {"profile", "plugin", "enabled"} <= set(body) <= {"profile", "plugin", "enabled", "bind", "sandbox_owner"}
                or not isinstance(body["plugin"], str) or not CONNECTOR_ID_RE.match(body["plugin"])
                or not isinstance(body["enabled"], bool)
                or (body["enabled"] and body["plugin"] not in roots)
                or ("bind" in body and (not isinstance(bind, dict) or set(bind) != {"vault"}
                                        or not isinstance(bind["vault"], str) or not VAULT_ID_RE.match(bind["vault"])))):
            return _rejected("profile, 알려진 plugin, enabled 와 바인딩이면 bind.vault, 옛 설치면 sandbox_owner 만 필요하다")
        owner = body.get("sandbox_owner")
        if owner is not None and not (isinstance(owner, str) and SANDBOX_OWNER_RE.match(owner)):
            return _rejected("sandbox_owner 형식이 올바르지 않다")
    profile = body["profile"]
    rejected = _profile_rejection(profile, request)
    if rejected is not None:
        return rejected
    missing = _missing_profile(body["profile"])
    if missing is not None:
        return missing
    profile_dir = get_profile_dir(body["profile"])
    # 표식은 대상의 방식으로 판정한다. 커넥터 표식만 있는 사람이 만든 profile 은 바인딩 설치만 받는다.
    managed = (profile_dir / MANAGED_MARKER).is_file()
    host = (profile_dir / CONNECTOR_HOST_MARKER).is_file()
    owned_mode = None if request.method.upper() == "GET" else _owned_mode(profile_dir, body["plugin"])
    # 커넥터 표식만 있는 profile 의 떼기는 그 항목이 없어도 바인딩 떼기로 다룬다. 다시 보낸 떼기가 바꾸지 않고 성공한다.
    unbind = (request.method.upper() != "GET" and not body["enabled"]
              and (owned_mode == BIND_MODE or (owned_mode is None and not managed)))
    bind_target = request.method.upper() == "GET" or ("bind" in body if body["enabled"] else unbind)
    if not (managed or (host and bind_target)):
        return _rejected("관리 표식이 없는 profile 이다", 401)
    try:
        if request.method.upper() == "GET":
            import yaml
            config = yaml.safe_load((profile_dir / "config.yaml").read_text(encoding="utf-8")) or {}
            state_path = profile_dir / CONNECTOR_STATE
            # 바인딩 항목은 모양만 보고, manifest 와 맞는지는 아래에서 항목마다 따로 판정한다.
            state = _connector_state(json.loads(state_path.read_text(encoding="utf-8"))) if state_path.exists() else {}
            servers = config.get("mcp_servers") or {}
            try:
                expected = _connector_allowlist(state, servers)
            except FileExistsError:
                expected = None
            # 설치가 쓰는 목록과 같고 Control Plane MCP 등록이 없어야 설치가 끝난 것이다.
            # 이 값은 profile 단위다. 목록이 profile 하나에 하나뿐이라 서버 이름을 찾지 못하는 기록이 하나라도 있으면
            # 그 profile 의 커넥터가 모두 `configured: false` 다.
            allowed = (config.get("platform_toolsets") or {}).get("api_server")
            isolated = (expected is not None and CONTROL_PLANE_MCP not in servers and allowed == expected)
            connectors = []
            for plugin in roots:
                manifest = _connector_manifest(plugin)
                entry = state.get(plugin)
                # 설치하지 않은 커넥터는 소유 기록의 기본값처럼 옛 설치로 답한다. 읽는 쪽은 설치한 항목의 값만 쓴다.
                mode = _entry_mode(entry)
                if entry is None or manifest is None or servers.get(manifest["mcp_server"]) != entry["server"]:
                    configured = False
                elif mode == BIND_MODE:
                    # Control Plane MCP 등록이 있어도 된다. 바인딩 설치는 그 등록과 다른 도구 이름을 그대로 둔다.
                    # 기록이 지금 manifest 와 다르면 그 항목만 거짓이다. 같은 profile 의 다른 항목은 따로 판정한다.
                    configured = (isinstance(allowed, list) and manifest["mcp_server"] in allowed
                                  and _entry_matches_manifest(plugin, entry)
                                  and _bind_skills_installed(profile_dir, manifest, entry))
                else:
                    configured = isolated
                connectors.append({"plugin": plugin, "enabled": entry is not None, "configured": configured,
                                   "mode": mode})
            # 운영 목록에서 빠진 커넥터의 기록은 설치를 끌 수 있게 보이되 쓸 수 있다고 답하지 않는다.
            connectors.extend({"plugin": plugin, "enabled": True, "configured": False, "mode": _entry_mode(entry)}
                              for plugin, entry in state.items() if plugin not in roots)
            return JSONResponse({"profile": body["profile"], "connectors": connectors,
                                 "policy_hook": _policy_hook_active(profile_dir, config, state)}, status_code=200)
        response = {key: body[key] for key in ("profile", "plugin", "enabled", "bind") if key in body}
        if body["enabled"] and "bind" in body:
            # 붙이는 요청 안에서 보관 파일을 읽는다. 보관 파일 쓰기와 같은 잠금 안이라 그 사이에 바뀌지 않는다.
            # 파일을 읽는 동안 이벤트 루프를 막지 않는다.
            manifest = await asyncio.to_thread(_connector_manifest, body["plugin"])
            if manifest is None:
                raise ValueError("쓸 수 없는 connector 는 설치하지 않는다")
            stored = await asyncio.to_thread(_read_vault, body["bind"]["vault"])
            if stored is None or stored["connector"] != body["plugin"]:
                return _rejected("그 connector 의 보관 파일이 없다")
            values = _vault_values(manifest, stored["values"])
            if values is None:
                return _rejected("보관 파일의 값이 지금 칸 선언과 맞지 않는다")
            result = await asyncio.to_thread(_connector_bind_config, profile_dir, body["plugin"], True,
                                             body["bind"]["vault"], values)
            return JSONResponse({**response, **result}, status_code=200)
        if unbind:
            result = await asyncio.to_thread(_connector_bind_config, profile_dir, body["plugin"], False)
            return JSONResponse({**response, **result}, status_code=200)
        # 옛 설치가 사진 도구를 열면 그 에이전트 주인의 격리 실행 공간을 쓴다(ADR-090).
        sandbox_terminal = None
        attachment_guard = None
        local_execution = False
        manifest = _connector_manifest(body["plugin"])
        if body["enabled"] and manifest is not None and SANDBOX_TOOLSETS & set(manifest["toolsets"]):
            sandbox = _sandbox_policy()
            if sandbox is None:
                return _sandbox_unavailable()
            if profile in sandbox["profiles"]:
                if owner is None:
                    return _rejected("격리할 사진 도구에는 sandbox_owner 가 필요하다")
                try:
                    os.makedirs(_sandbox_workspace(sandbox, owner), exist_ok=True)
                    prepared = _sandbox_verify_attachment_directories(sandbox, owner)
                    sandbox_terminal = _sandbox_terminal(sandbox, profile, owner, prepared)
                    attachment_guard = (sandbox, owner, prepared)
                except OSError:
                    return _sandbox_unavailable()
            else:
                return _sandbox_unavailable()
        result = await asyncio.to_thread(
            _connector_config, profile_dir, body["plugin"], body["enabled"], sandbox_terminal, local_execution,
            attachment_guard)
        return JSONResponse({**response, **result}, status_code=200)
    except SandboxAttachmentError:
        return _sandbox_unavailable()
    except FileExistsError:
        return _rejected("운영자 설정과 충돌한다", 409)
    except Exception:
        # manifest 내용이나 profile 환경 변수를 응답과 로그에 싣지 않는다.
        return _rejected("connector 파일 또는 설정을 확인하지 못했다", 503)


def _connector_catalog_response():
    """검증을 통과한 커넥터의 카탈로그다. 운영자 env 의 이름과 값, 오류 대응 표, 스킬 본문은 담지 않는다.

    `skills` 는 바인딩 설치가 profile 에 복사할 스킬의 이름이다. 이름 순이다.
    """
    from starlette.responses import JSONResponse

    return JSONResponse(
        [{"id": manifest["id"], "schema": manifest["schema"], "title": manifest["title"],
          "description": manifest["description"],
          "fields": manifest["fields"], "verify": manifest["verify"], "mcp_server": manifest["mcp_server"],
          "toolsets": manifest["toolsets"], "attachments": manifest["attachments"],
          "skills": sorted(manifest["skills"]),
          # 사람 말 제목이 없는 도구는 `title` 을 내지 않는다. 읽는 쪽이 도구 이름을 보인다.
          "tools": {name: {key: value for key, value in policy.items() if value is not None}
                    for name, policy in manifest["tools"].items()}}
         for manifest in _connector_catalog().values()],
        status_code=200,
    )


async def _run_connector_tool(manifest: dict, tool: str, env: dict):
    """커넥터 MCP 서버를 자식 프로세스로 한 번 띄워 도구 하나를 부르고 닫는다.

    도구가 `tools/list` 에서 읽기 전용이 아니면 부르지 않고 None 을 돌려준다.
    `mcp` 는 여기서 import 한다. SDK 가 없는 환경에서도 plugin 이 올라오고 이 경로만 실패한다.
    """
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client

    server = manifest["server"]
    params = StdioServerParameters(command=server["command"], args=list(server["args"]), env=env)
    # 자식의 stderr 에 무엇이 찍힐지 모른다. 후보 값이 대시보드 로그로 가지 않게 버린다.
    with open(os.devnull, "w", encoding="utf-8") as sink:
        async with stdio_client(params, errlog=sink) as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()
                listed = await session.list_tools()
                annotations = next((item.annotations for item in listed.tools if item.name == tool), None)
                if annotations is None or annotations.read_only_hint is not True:
                    return None
                return await session.call_tool(tool, {})


def _mcp_sdk_version() -> str:
    """설치된 `mcp` SDK 의 판이다. 읽지 못하면 `unknown` 이다. 프로세스에서 한 번만 읽는다."""
    global _mcp_sdk_version_cache
    if _mcp_sdk_version_cache is None:
        try:
            _mcp_sdk_version_cache = importlib.metadata.version("mcp")
        except importlib.metadata.PackageNotFoundError:
            _mcp_sdk_version_cache = "unknown"
    return _mcp_sdk_version_cache


def _mcp_sdk_problem() -> Optional[str]:
    """커넥터 도구 호출이 기대는 SDK 가 아니면 까닭 한 줄을 돌려주고, 지원 범위이면 None 이다."""
    if _mcp_sdk_version().split(".")[0] != str(MCP_SDK_MAJOR):
        return "지원 범위 mcp>=%d.0,<%d 밖이다" % (MCP_SDK_MAJOR, MCP_SDK_MAJOR + 1)
    try:
        from mcp import ClientSession, StdioServerParameters
        from mcp.client.stdio import stdio_client
        import mcp.types as mcp_types
    except ImportError:
        return "mcp SDK 를 읽어 오지 못했다"
    if "read_only_hint" not in mcp_types.ToolAnnotations.model_fields:
        return "필요한 속성 read_only_hint 가 없다"
    for name in ("structured_content", "is_error", "content"):
        if name not in mcp_types.CallToolResult.model_fields:
            return "필요한 속성 %s 가 없다" % name
    return None


def _leaf_error_types(error) -> list:
    """예외 묶음을 끝까지 풀어 가장 안쪽 예외의 종류 이름을 모은다."""
    inner = getattr(error, "exceptions", None)
    if not inner:
        return [type(error).__name__]
    return [name for item in inner for name in _leaf_error_types(item)]


def _connector_call_answer(manifest: dict, result) -> dict:
    """도구 결과를 `{ok, result}` 나 `{ok, error}` 로 바꾼다. 읽지 못한 결과는 `unavailable` 이다."""
    payload = result.structured_content
    if payload is None:
        try:
            text = next(item.text for item in result.content if item.type == "text")
            payload = json.loads(text)
        except (StopIteration, ValueError, TypeError):
            return {"ok": False, "error": "unavailable"}
    if result.is_error:
        error = payload.get("error") if isinstance(payload, dict) else None
        code = error.get("code") if isinstance(error, dict) else None
        word = manifest["errors"].get(code, "unavailable") if isinstance(code, str) else "unavailable"
        return {"ok": False, "error": word}
    return {"ok": True, "result": payload}


async def _connector_call_request(request, connector_id: str):
    """후보 값이나 보관 파일의 값으로 선택지 도구나 확인 도구를 한 번 부른다. 값을 디스크와 응답과 로그에 남기지 않는다.

    본문은 `values` 와 `vault` 가운데 정확히 하나를 갖는다. `vault` 는 그 커넥터의 보관 파일이어야 한다.
    """
    from starlette.responses import JSONResponse
    global _connector_calls

    def failed(word):
        return JSONResponse({"ok": False, "error": word}, status_code=200)

    manifest = _connector_manifest(connector_id) if CONNECTOR_ID_RE.match(connector_id) else None
    if manifest is None:
        return _rejected("없는 connector 다", 404)
    body = await _json_object(request)
    if (body is None or set(body) not in ({"tool", "values"}, {"tool", "vault"})
            or ("values" in body and not isinstance(body["values"], dict))
            or ("vault" in body and (not isinstance(body["vault"], str) or not VAULT_ID_RE.match(body["vault"])))):
        return _rejected("tool 과, values 나 vault 가운데 하나만 필요하다")
    tool = body["tool"]
    if not isinstance(tool, str) or tool not in manifest["call_tools"]:
        return _rejected("이 커넥터가 선택지나 확인에 쓰는 도구가 아니다")
    if "vault" in body:
        try:
            stored = await asyncio.to_thread(_read_vault, body["vault"])
        except Exception as error:
            logger.warning("dashboard-profile-api: 커넥터 %s 의 보관 파일을 읽지 못했다: %s",
                           connector_id, type(error).__name__)
            return failed("unavailable")
        if stored is None or stored["connector"] != connector_id:
            return _rejected("이 커넥터의 보관 파일이 없다")
        candidates = stored["values"]
    else:
        candidates = body["values"]
    fields = {field["key"]: field for field in manifest["fields"]}
    env = {}
    for key, value in candidates.items():
        field = fields.get(key)
        if field is None or not isinstance(value, str) or any(ch in value for ch in "\r\n\0"):
            return failed("invalid_input")
        # 비운 선택 칸은 형식을 보지 않는다. 필수 칸은 빈 값도 형식에 맞아야 한다.
        if "pattern" in field and (value or field.get("required", True)) and not re.fullmatch(field["pattern"], value):
            return failed("invalid_input")
        env[field["env"]] = value
    server = manifest["server"]
    env.update({name: server["env"][name] for name in manifest["operator_env"]})
    # 대시보드 프로세스의 PATH 를 물려주지 않는다. 실행 파일이 있는 디렉터리만 준다.
    env["PATH"] = os.path.dirname(server["command"])

    problem = _mcp_sdk_problem()
    if problem is not None:
        logger.warning("dashboard-profile-api: mcp SDK %s 로는 커넥터 도구를 부르지 않는다: %s",
                       _mcp_sdk_version(), problem)
        return failed("unavailable")

    # 줄을 세우지 않는다. 가득 차 있으면 기다리는 동안 요청이 쌓여 대시보드가 느려진다.
    if _connector_calls >= CONNECTOR_CALL_LIMIT:
        logger.warning("dashboard-profile-api: 커넥터 도구 호출이 %d개 돌고 있어 받지 않았다", _connector_calls)
        return failed("unavailable")
    _connector_calls += 1
    try:
        # 시간을 넘기면 취소가 SDK 의 정리 구간을 돌려 자식 프로세스를 끝낸 뒤에 돌아온다.
        result = await asyncio.wait_for(_run_connector_tool(manifest, tool, env), CONNECTOR_CALL_TIMEOUT_SECONDS)
        answer = None if result is None else _connector_call_answer(manifest, result)
        if answer is not None and answer.get("error") == OUTCOME_UNKNOWN:
            # 선택지와 확인 도구는 읽기 전용이다. 결과를 모르는 쓰기가 없으므로 `unavailable` 과 같다.
            answer = {"ok": False, "error": "unavailable"}
    except ImportError:
        logger.warning("dashboard-profile-api: mcp SDK 를 읽어 오지 못해 커넥터 도구를 부르지 못했다")
        return failed("unavailable")
    except asyncio.TimeoutError:
        logger.warning("dashboard-profile-api: 커넥터 %s 의 도구 %s 가 시간 제한을 넘겼다", connector_id, tool)
        return failed("unavailable")
    except Exception as error:
        # 예외 본문에는 자식의 출력이 섞일 수 있다. 가장 안쪽 예외의 종류만 남긴다.
        logger.warning("dashboard-profile-api: 커넥터 %s 의 도구 %s 를 부르지 못했다: %s (mcp SDK %s)",
                       connector_id, tool, ", ".join(sorted(set(_leaf_error_types(error)))), _mcp_sdk_version())
        return failed("unavailable")
    finally:
        _connector_calls -= 1
    if answer is None:
        return _rejected("읽기 전용 도구가 아니다")
    return JSONResponse(answer, status_code=200)


async def _run_connector_execute(manifest: dict, hermes_tool: str, args: dict, env: dict, progress: dict):
    """커넥터 MCP 서버를 자식 프로세스로 한 번 띄워 등록 이름이 `hermes_tool` 인 도구를 `args` 로 부르고 닫는다.

    등록 이름이 같은 도구가 정확히 하나가 아니거나, `schema: 2` 인데 그 도구가 선언에 없으면 부르지 않고 None 이다.
    도구를 부르기 직전에 `progress["sent"]` 를 참으로 둔다. 그 뒤의 실패는 도구가 실행됐는지 알 수 없다.
    """
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client

    server = manifest["server"]
    params = StdioServerParameters(command=server["command"], args=list(server["args"]), env=env)
    # 자식의 stderr 에 무엇이 찍힐지 모른다. profile 의 값과 인자가 대시보드 로그로 가지 않게 버린다.
    with open(os.devnull, "w", encoding="utf-8") as sink:
        async with stdio_client(params, errlog=sink) as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()
                listed = await session.list_tools()
                names = [item.name for item in listed.tools
                         if _hermes_tool_name(manifest["mcp_server"], item.name) == hermes_tool]
                if len(names) != 1 or (manifest["schema"] == 2 and names[0] not in manifest["tools"]):
                    return None
                progress["sent"] = True
                return await session.call_tool(names[0], args)


def _connector_execute_answer(manifest: dict, result) -> dict:
    """실행 결과를 `{ok, result}` 나 `{ok, error}` 로 바꾼다.

    오류 없이 끝났는데 구조화 결과도 JSON 텍스트도 없으면 첫 텍스트 칸의 글을 `{"text": ...}` 로 싣는다.
    `call` 처럼 `unavailable` 로 답하면 이미 실행된 쓰기가 실패로 기록된다.
    """
    answer = _connector_call_answer(manifest, result)
    if result.is_error or answer["ok"]:
        return answer
    text = next((item.text for item in result.content if item.type == "text"), "")
    return {"ok": True, "result": {"text": text if isinstance(text, str) else ""}}


async def _connector_execute_request(request, connector_id: str):
    """Control Plane 이 승인한 호출을 그 profile 의 값과 받은 인자로 한 번 실행한다(ADR-050).

    승인 여부는 다시 확인하지 않는다. 서비스 토큰을 가진 Control Plane 이 승인한 줄로만 부른다.
    인자와 결과를 로그에 싣지 않는다.
    실행되지 않은 것이 분명한 실패는 `{"ok": false}` 로, 실행됐는지 모르는 실패는 504 로 답한다.
    도구가 `errors` 표에서 `outcome_unknown` 인 코드로 끝난 것도 실행됐는지 모르는 실패다.
    Control Plane 이 앞의 것은 실패로, 뒤의 것은 결과를 모르는 것으로 읽어 다시 돌리지 않는다.
    """
    from starlette.responses import JSONResponse
    global _connector_calls

    def failed(word):
        return JSONResponse({"ok": False, "error": word}, status_code=200)

    manifest = _connector_manifest(connector_id) if CONNECTOR_ID_RE.match(connector_id) else None
    if manifest is None:
        return _rejected("없는 connector 다", 404)
    body = await _json_object(request)
    if (body is None or set(body) != {"profile", "hermes_tool", "args"} or not isinstance(body["args"], dict)
            or not isinstance(body["hermes_tool"], str) or not 1 <= len(body["hermes_tool"]) <= 128):
        return _rejected("profile, hermes_tool, args 만 필요하다")
    rejected = _profile_rejection(body["profile"], request)
    if rejected is not None:
        return rejected
    hermes_tool = body["hermes_tool"]
    try:
        missing = _missing_profile(body["profile"])
        if missing is not None:
            return missing
        from hermes_cli.profiles import get_profile_dir
        profile_dir = get_profile_dir(body["profile"])
        managed = (profile_dir / MANAGED_MARKER).is_file()
        if not managed and not (profile_dir / CONNECTOR_HOST_MARKER).is_file():
            return _rejected("관리 표식이 없는 profile 이다", 401)
        state_path = profile_dir / CONNECTOR_STATE
        # 기록을 검증하면서 이 커넥터의 항목이 지금 manifest 의 실행 정의와 맞는지도 함께 본다.
        state = (_connector_state(json.loads(state_path.read_text(encoding="utf-8")), connector_id)
                 if state_path.is_file() else {})
        if connector_id not in state:
            return _rejected("설치하지 않은 connector 다", 404)
        # 커넥터 표식만 있는 profile 은 바인딩 설치만 받는다. 그 밖의 항목으로는 실행하지 않는다.
        if not managed and _entry_mode(state[connector_id]) != BIND_MODE:
            return _rejected("관리 표식이 없는 profile 이다", 401)
        env_path = profile_dir / ".env"
        env_text = env_path.read_text(encoding="utf-8") if env_path.is_file() else ""
    except Exception as error:
        # 자식을 띄우기 전이다. 실행되지 않았다. profile 의 값이 섞일 수 있어 예외의 종류만 남긴다.
        logger.warning("dashboard-profile-api: 커넥터 %s 를 실행할 profile 을 확인하지 못했다: %s",
                       connector_id, type(error).__name__)
        return failed("unavailable")
    # 그 profile 의 값 가운데 이 커넥터의 칸만 넘긴다. 비운 선택 칸은 빈 문자열이다.
    env = {field["env"]: _env_value(env_text, field["env"]) for field in manifest["fields"]}
    server = manifest["server"]
    env.update({name: server["env"][name] for name in manifest["operator_env"]})
    # 대시보드 프로세스의 PATH 를 물려주지 않는다. 실행 파일이 있는 디렉터리만 준다.
    env["PATH"] = os.path.dirname(server["command"])

    problem = _mcp_sdk_problem()
    if problem is not None:
        logger.warning("dashboard-profile-api: mcp SDK %s 로는 커넥터 도구를 부르지 않는다: %s",
                       _mcp_sdk_version(), problem)
        return failed("unavailable")

    # `call` 과 한도를 함께 쓴다. 줄을 세우지 않는다.
    if _connector_calls >= CONNECTOR_CALL_LIMIT:
        logger.warning("dashboard-profile-api: 커넥터 도구 호출이 %d개 돌고 있어 받지 않았다", _connector_calls)
        return failed("unavailable")
    _connector_calls += 1
    progress = {"sent": False}
    try:
        # 시간을 넘기면 취소가 SDK 의 정리 구간을 돌려 자식 프로세스를 끝낸 뒤에 돌아온다.
        result = await asyncio.wait_for(
            _run_connector_execute(manifest, hermes_tool, body["args"], env, progress),
            CONNECTOR_EXECUTE_TIMEOUT_SECONDS)
        answer = None if result is None else _connector_execute_answer(manifest, result)
    except asyncio.TimeoutError:
        logger.warning("dashboard-profile-api: 커넥터 %s 의 도구 %s 가 시간 제한을 넘겼다", connector_id, hermes_tool)
        return _rejected("도구가 시간 제한을 넘겨 실행 결과를 모른다", 504)
    except Exception as error:
        # 예외 본문에는 자식의 출력이 섞일 수 있다. 가장 안쪽 예외의 종류만 남긴다.
        logger.warning("dashboard-profile-api: 커넥터 %s 의 도구 %s 를 실행하지 못했다: %s (mcp SDK %s)",
                       connector_id, hermes_tool, ", ".join(sorted(set(_leaf_error_types(error)))),
                       _mcp_sdk_version())
        if progress["sent"]:
            # 도구 호출을 보낸 뒤다. 실행됐는지 알 수 없다.
            return _rejected("도구 호출 뒤에 실패해 실행 결과를 모른다", 504)
        return failed("unavailable")
    finally:
        _connector_calls -= 1
    if answer is None:
        return _rejected("실행할 수 없는 도구다")
    if answer.get("error") == OUTCOME_UNKNOWN:
        # 도구가 쓰기를 보낸 뒤 답을 받지 못했다고 알렸다. 실패로 답하면 이미 나간 쓰기가 실패로 기록된다.
        logger.warning("dashboard-profile-api: 커넥터 %s 의 도구 %s 가 실행 결과를 모른다고 답했다",
                       connector_id, hermes_tool)
        return _rejected("도구가 실행 결과를 모른다고 답했다", 504)
    return JSONResponse(answer, status_code=200)


async def _check_connector_probe(request):
    """probe 는 그 profile 에 설치한 커넥터의 MCP 서버 이름일 때만 넘긴다."""
    rejected = await _check_skills_list(request)
    if rejected is not None:
        return rejected
    from hermes_cli.profiles import get_profile_dir
    server = PROBE_ROUTE_RE.match(request.url.path).group(1)
    profile_dir = get_profile_dir(request.query_params.getlist("profile")[0])
    managed = (profile_dir / MANAGED_MARKER).is_file()
    if not managed and not (profile_dir / CONNECTOR_HOST_MARKER).is_file():
        return _rejected("관리 표식이 없는 profile 이다", 401)
    state_path = profile_dir / CONNECTOR_STATE
    try:
        import yaml
        if not state_path.is_file():
            return _rejected("설치하지 않은 connector 다", 404)
        state = _connector_state(json.loads(state_path.read_text(encoding="utf-8")))
        roots = _connector_roots()
        target = next((plugin for plugin in state if plugin in roots
                       and (_connector_manifest(plugin) or {}).get("mcp_server") == server), None)
        if target is None:
            return _rejected("설치하지 않은 connector 다", 404)
        # 이 서버의 커넥터 항목만 지금 manifest 의 실행 정의와 맞는지 본다.
        owned = _connector_state(state, target)[target]
        # 커넥터 표식만 있는 profile 은 바인딩 설치만 받는다.
        if not managed and _entry_mode(owned) != BIND_MODE:
            return _rejected("관리 표식이 없는 profile 이다", 401)
        config = yaml.safe_load((profile_dir / "config.yaml").read_text(encoding="utf-8")) or {}
        if (config.get("mcp_servers") or {}).get(server) != owned["server"]:
            return _rejected("운영자 설정과 충돌한다", 409)
        if server not in (config.get("platform_toolsets") or {}).get("api_server", []):
            return _rejected("현재 manifest 와 profile 설정이 다르다", 409)
    except Exception:
        return _rejected("connector 설정을 확인하지 못했다", 503)
    return None


def _unlock_api_toolsets(config: dict, allowed: list, platforms: set, calculate) -> dict:
    """요청한 API 도구를 `agent.disabled_toolsets` 에서 뺀 설정이다.

    Hermes 는 허용 목록을 계산한 뒤 `disabled_toolsets` 를 마지막에 빼므로, 거기 남은 이름은 목록에 있어도 열리지 않는다.
    `disabled_toolsets` 는 모든 platform 에 걸린다. 빼면 목록이 없어 기본 toolset 을 쓰는 platform 에
    그 도구가 열리므로, 계산 결과가 바뀌는 platform 은 지금 계산 결과를 명시 목록으로 먼저 고정한다.
    목록이 이미 있는 platform 이 바뀌면 부르는 쪽의 다른 platform 검사가 거절한다.
    """
    agent = config.get("agent") or {}
    disabled = agent.get("disabled_toolsets")
    if not isinstance(disabled, list) or not set(allowed) & set(disabled):
        return config
    unlocked = {**config, "agent": {**agent, "disabled_toolsets": [name for name in disabled if name not in allowed]}}
    lists = dict(config.get("platform_toolsets") or {})
    for name in sorted(platforms - set(lists)):
        before = calculate(config, name)
        if calculate(unlocked, name) != before:
            lists[name] = sorted(before)
    unlocked["platform_toolsets"] = lists
    return unlocked


def _write_checked_config(path: pathlib.Path, original: bytes, config: dict,
                          attachment_guard: Optional[tuple] = None) -> bytes:
    """검사를 마친 설정을 처리기보다 먼저 쓴다. 읽은 뒤 파일이 바뀌었으면 쓰지 않는다."""
    import yaml
    if path.is_symlink() or path.read_bytes() != original:
        raise FileExistsError("검사 뒤 profile 설정이 밖에서 바뀌었다")
    value = yaml.safe_dump(config, sort_keys=False, allow_unicode=True).encode()
    if attachment_guard is not None:
        _sandbox_validate_attachment_snapshot(*attachment_guard)
    _atomic_private_write(path, value)
    return value


def _restore_config(path: pathlib.Path, original: bytes, written: bytes) -> None:
    # 이 요청이 쓴 값일 때만 복원한다. 바깥의 새 수정은 덮어쓰지 않는다.
    if path.exists() and path.read_bytes() == written:
        _atomic_private_write(path, original)


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


def _bound_servers(profile_dir: pathlib.Path) -> list:
    """소유 기록의 바인딩 항목이 설치한 서버 이름이다. 기록이 없으면 빈 목록이다.

    기록은 JSON 모양만 본다. `_connector_state` 는 manifest 의 실행 정의가 바뀌면 예외를 내어,
    운영자가 커넥터를 바꾸면 그 커넥터가 붙은 모든 에이전트의 도구 저장이 실패하기 때문이다.
    """
    path = profile_dir / CONNECTOR_STATE
    if not path.is_file():
        return []
    state = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(state, dict):
        raise ValueError("connector 소유 기록이 올바르지 않다")
    return [entry["mcp_server"] for entry in state.values()
            if _entry_mode(entry) == BIND_MODE and isinstance(entry.get("mcp_server"), str)]


def _sandbox_path_ok(value) -> bool:
    """`:` 없는 절대 경로이고 빈 조각과 `..` 이 없는지 본다."""
    if (not isinstance(value, str) or not value.startswith("/") or ":" in value
            or any(ord(ch) < 32 or ord(ch) == 127 for ch in value)):
        return False
    return all(part not in ("", ".", "..") for part in value.split("/")[1:])


def _sandbox_mount_ok(value) -> bool:
    """`<원본 절대 경로>:<컨테이너 절대 경로>` 하나를 본다. 컨테이너 경로가 `/workspace`, `/root` 자리면 틀리다."""
    if not isinstance(value, str):
        return False
    pieces = value.split(":")
    if len(pieces) != 2 or not all(_sandbox_path_ok(piece) for piece in pieces):
        return False
    target = pieces[1]
    # 경로 조각 기준으로 본다. `/rootfs` 는 `/root` 아래가 아니다.
    return not any(target == reserved or target.startswith(reserved + "/") for reserved in SANDBOX_RESERVED_PATHS)


def _sandbox_mounts_ok(value) -> bool:
    return isinstance(value, list) and all(_sandbox_mount_ok(entry) for entry in value)


def _sandbox_paths_overlap(first: str, second: str) -> bool:
    """두 절대 경로가 같거나 한쪽이 다른 쪽 아래인지 경로 조각 기준으로 본다."""
    first = first.rstrip("/") or "/"
    second = second.rstrip("/") or "/"

    def under(child, parent):
        return child == parent or parent == "/" or child.startswith(parent + "/")

    return under(first, second) or under(second, first)


def _sandbox_mount_overlaps(mount: str, workspace_root: str) -> bool:
    """마운트 원본이 `workspace_root` 와 겹치는지 경로 조각 기준으로 본다.

    겹치면 다른 사용자의 `/workspace` 가 읽기 전용 마운트로 함께 보인다.
    """
    source = mount.split(":")[0]
    return _sandbox_paths_overlap(source, workspace_root)


def _sandbox_attachment_mount_overlaps(mount: str, attachment_root: str, attachment_agent_root: str) -> bool:
    """운영 마운트가 첨부 원본이나 실행 공간 안의 첨부 경로 전체를 보이게 하는지 본다."""
    source, target = mount.split(":")
    return (_sandbox_paths_overlap(source, attachment_root)
            or _sandbox_paths_overlap(target, attachment_agent_root))


def _sandbox_attachment_roots_ok(attachment_root: str, attachment_agent_root: str, workspace_root: str) -> bool:
    """첨부 원본과 실행 공간 경로가 사용자 workspace 나 예약 경로와 겹치지 않는지 본다."""
    if _sandbox_paths_overlap(attachment_root, workspace_root):
        return False
    return not any(_sandbox_paths_overlap(attachment_agent_root, reserved)
                   for reserved in SANDBOX_RESERVED_PATHS)


def _sandbox_env_ok(value) -> bool:
    """운영 정책의 환경 값은 허용한 경로와 인증정보 없는 URL 만 받는다."""
    if not isinstance(value, dict):
        return False
    for name, entry in value.items():
        if name in SANDBOX_PATH_ENV:
            if not _sandbox_path_ok(entry):
                return False
        elif name == "CAREER_BACKEND_URL":
            if (not isinstance(entry, str) or not entry
                    or any(ch.isspace() or ord(ch) < 32 or ord(ch) == 127 for ch in entry)):
                return False
            try:
                parsed = urlsplit(entry)
                port = parsed.port
            except ValueError:
                return False
            if (parsed.scheme not in {"http", "https"} or not parsed.hostname
                    or parsed.username is not None or parsed.password is not None
                    or parsed.query or parsed.fragment or (port is not None and port <= 0)):
                return False
        else:
            return False
    return True


def _sandbox_profiles(value, workspace_root: str, attachment_root: str, attachment_agent_root: str,
                      default_network) -> Optional[dict]:
    """정책에 등록된 profile 만 검증한다. 빈 목록은 모두 기존 실행을 유지한다."""
    if not isinstance(value, dict):
        return None
    profiles = {}
    for name, settings in value.items():
        if (not isinstance(name, str) or name == "default" or not PROFILE_NAME_RE.fullmatch(name)
                or not isinstance(settings, dict) or set(settings) - SANDBOX_PROFILE_KEYS):
            return None
        mounts = settings.get("read_only_mounts", [])
        if (not _sandbox_mounts_ok(mounts)
                or any(_sandbox_mount_overlaps(mount, workspace_root)
                       or _sandbox_attachment_mount_overlaps(mount, attachment_root, attachment_agent_root)
                       for mount in mounts)):
            return None
        env = settings.get("env", {})
        if not _sandbox_env_ok(env):
            return None
        network = settings.get("network", default_network)
        if network is not None and not (isinstance(network, str) and SANDBOX_NETWORK_RE.fullmatch(network)):
            return None
        profiles[name] = {"read_only_mounts": list(mounts), "env": dict(env), "network": network}
    return profiles


def _sandbox_policy() -> Optional[dict]:
    """`SANDBOX_ENV` 의 JSON 을 읽어 검증한다. 없거나 하나라도 틀리면 None 이다.

    None 이면 셸과 파일 도구 저장을 거절한다. 유효한 정책의 profiles 에 없는 profile 은 기존 실행을 유지한다(ADR-086).
    """
    raw = os.environ.get(SANDBOX_ENV, "").strip()
    if not raw:
        logger.error("dashboard-profile-api: %s 가 없다", SANDBOX_ENV)
        return None
    try:
        value = json.loads(raw)
    except ValueError:
        logger.error("dashboard-profile-api: %s 가 JSON 이 아니다", SANDBOX_ENV)
        return None
    if not isinstance(value, dict):
        logger.error("dashboard-profile-api: %s 가 JSON object 가 아니다", SANDBOX_ENV)
        return None

    def invalid(key):
        logger.error("dashboard-profile-api: %s 의 %s 가 올바르지 않다", SANDBOX_ENV, key)
        return None

    unknown = sorted(set(value) - SANDBOX_POLICY_KEYS)
    if unknown:
        # 계약에 없는 키를 조용히 버리면 운영자가 걸었다고 믿는 제한이 빠진 채로 돈다.
        logger.error("dashboard-profile-api: %s 에 계약에 없는 키가 있다 %s", SANDBOX_ENV, unknown)
        return None

    image = value.get("image")
    if (not isinstance(image, str) or not image
            or any(ch.isspace() or ord(ch) < 32 or ord(ch) == 127 for ch in image)):
        return invalid("image")
    if not _sandbox_path_ok(value.get("workspace_root")):
        return invalid("workspace_root")
    if not _sandbox_path_ok(value.get("attachment_root")):
        return invalid("attachment_root")
    if not _sandbox_path_ok(value.get("attachment_agent_root")):
        return invalid("attachment_agent_root")
    network = value.get("network")
    if network is not None and not (isinstance(network, str) and SANDBOX_NETWORK_RE.fullmatch(network)):
        return invalid("network")
    cpu = value.get("cpu", 1)
    if isinstance(cpu, bool) or not isinstance(cpu, (int, float)) or not 0 < cpu <= 8:
        return invalid("cpu")
    memory_mb = value.get("memory_mb", 1024)
    if isinstance(memory_mb, bool) or not isinstance(memory_mb, int) or not 256 <= memory_mb <= 16384:
        return invalid("memory_mb")
    read_only_mounts = value.get("read_only_mounts", [])
    if not _sandbox_mounts_ok(read_only_mounts):
        return invalid("read_only_mounts")
    workspace_root = value["workspace_root"]
    attachment_root = value["attachment_root"]
    attachment_agent_root = value["attachment_agent_root"]
    if not _sandbox_attachment_roots_ok(attachment_root, attachment_agent_root, workspace_root):
        return invalid("attachment_root 또는 attachment_agent_root")
    if any(_sandbox_mount_overlaps(mount, workspace_root)
           or _sandbox_attachment_mount_overlaps(mount, attachment_root, attachment_agent_root)
           for mount in read_only_mounts):
        return invalid("read_only_mounts")
    profiles = _sandbox_profiles(value.get("profiles"), workspace_root, attachment_root,
                                 attachment_agent_root, network)
    if profiles is None:
        return invalid("profiles")
    return {
        "image": image,
        "workspace_root": value["workspace_root"],
        "attachment_root": attachment_root,
        "attachment_agent_root": attachment_agent_root,
        "network": network,
        "cpu": cpu,
        "memory_mb": memory_mb,
        "read_only_mounts": list(read_only_mounts),
        "profiles": profiles,
    }


def _sandbox_workspace(policy: dict, owner: str) -> str:
    return "%s/%s" % (policy["workspace_root"].rstrip("/"), owner)


def _sandbox_attachment_key(owner: str) -> str:
    """실행 주인을 디스크 경로에 드러내지 않는 안정된 사용자 디렉터리 이름이다."""
    return hashlib.sha256(owner.encode("utf-8")).hexdigest()


def _sandbox_attachment_directory(policy: dict, owner: str) -> str:
    return "%s/users/%s" % (policy["attachment_root"].rstrip("/"), _sandbox_attachment_key(owner))


def _sandbox_attachment_agent_directory(policy: dict, owner: str) -> str:
    return "%s/users/%s" % (policy["attachment_agent_root"].rstrip("/"), _sandbox_attachment_key(owner))


class SandboxAttachmentError(OSError):
    """첨부 mount를 안전하게 준비하거나 다시 검증하지 못했다."""


def _sandbox_attachment_path_identity(root: pathlib.Path, owner: str) -> tuple:
    """경로를 fd 기준으로 내려가며 모든 중간 링크를 거절하고 디렉터리 식별자를 기록한다.

    사용자 디렉터리는 Control Plane 이 이 요청 전에 만든다. Hermes 는 첨부 루트를 읽기 전용으로 보므로
    여기서는 만들지 않고, 없으면 거절한다(ADR-090).
    """
    directory = root / "users" / _sandbox_attachment_key(owner)
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    descriptor = os.open("/", flags)
    identities = []
    try:
        for component in directory.parts[1:]:
            child = os.open(component, flags, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
            metadata = os.fstat(descriptor)
            identities.append((metadata.st_dev, metadata.st_ino))

        resolved_root = root.resolve(strict=True)
        resolved_directory = directory.resolve(strict=True)
        if resolved_root != root or resolved_directory != resolved_root / "users" / _sandbox_attachment_key(owner):
            raise SandboxAttachmentError("attachment directory resolves outside the execution owner")
        return tuple(identities)
    finally:
        os.close(descriptor)


def _sandbox_attachment_snapshot(policy: dict, owner: str) -> dict:
    """source와 target이 모두 보이고 허용된 사용자 경로 그대로인지 확인한다."""
    try:
        return {
            key: _sandbox_attachment_path_identity(pathlib.Path(policy[key]), owner)
            for key in ("attachment_root", "attachment_agent_root")
        }
    except (OSError, ValueError, RuntimeError) as error:
        raise SandboxAttachmentError("attachment mount paths cannot be verified") from error


def _sandbox_validate_attachment_snapshot(policy: dict, owner: str, expected: dict) -> None:
    if _sandbox_attachment_snapshot(policy, owner) != expected:
        raise SandboxAttachmentError("attachment directories changed after validation")


def _sandbox_verify_attachment_directories(policy: dict, owner: str) -> dict:
    """Control Plane 이 만든 양쪽 사용자 디렉터리를 확인하고 그 식별자를 돌려준다."""
    return _sandbox_attachment_snapshot(policy, owner)


def _sandbox_terminal(policy: dict, profile: str, owner: str, attachment_snapshot: Optional[dict] = None) -> dict:
    """profile 의 `terminal:` 전체다. 모양은 `hermes/README.md` 의 「셸 실행 공간」 과 같다.

    값을 건네는 칸(`docker_forward_env`, `env_passthrough`, `credential_files`)은 비워 둔다.
    profile 의 비밀값이 실행 공간에 들어가지 않게 하려는 것이다.
    """
    if attachment_snapshot is None:
        attachment_snapshot = _sandbox_verify_attachment_directories(policy, owner)
    _sandbox_validate_attachment_snapshot(policy, owner, attachment_snapshot)
    settings = policy["profiles"][profile]
    mounts = policy["read_only_mounts"] + settings["read_only_mounts"]
    network = settings["network"]
    extra_args = ["--label=fos-sandbox-profile=%s" % profile]
    if network:
        extra_args.insert(0, "--network=%s" % network)
    terminal = {
        "backend": "docker",
        "cwd": "/workspace",
        "docker_image": policy["image"],
        "container_persistent": True,
        "docker_persist_across_processes": True,
        "docker_orphan_reaper": True,
        "docker_mount_cwd_to_workspace": False,
        "docker_run_as_host_user": False,
        "docker_network": True,
        "docker_extra_args": extra_args,
        "docker_volumes": [
            "%s:/workspace" % _sandbox_workspace(policy, owner),
            "%s:%s:ro" % (_sandbox_attachment_directory(policy, owner),
                            _sandbox_attachment_agent_directory(policy, owner)),
        ] + ["%s:ro" % m for m in mounts],
        "docker_forward_env": [],
        "docker_env": settings["env"],
        "env_passthrough": [],
        "credential_files": [],
        "container_cpu": policy["cpu"],
        "container_memory": policy["memory_mb"],
    }
    # Hermes 의 docker backend 는 컨테이너를 label 로만 찾아 다시 쓰고, 프로세스 안의 캐시는
    # 지워진 컨테이너를 옛 run 인자로 다시 만든다. 마운트나 이미지가 바뀌어도 새 컨테이너가 생기지 않는다.
    # 이 키가 label 과 캐시 키와 /root 의 디렉터리 이름을 정하므로, 주인이나 실행 공간 설정이 바뀌면
    # 키를 바꿔 컨테이너와 프로세스 캐시와 /root 를 새로 쓰게 한다. 한 키는 이 profile 만 쓴다.
    fingerprint = hashlib.sha256(json.dumps(terminal, sort_keys=True).encode("utf-8")).hexdigest()[:12]
    terminal["docker_shared_container_key"] = "%s-%s-%s" % (profile, owner, fingerprint)
    return terminal


async def _check_config_update(request):
    """공유 토큰의 설정 쓰기를 profile 별 API 도구 목록과 올린 스킬 경로로 제한한다.

    처리기는 API 도구 목록을 통째로 바꾼다. 그래서 바인딩 설치가 더한 커넥터 서버 이름은
    Control Plane 이 목록에 함께 보낸다는 계약이다. 그 이름이 하나라도 빠진 요청은 409 로 거절한다(ADR-083).
    조용히 지워지면 붙은 커넥터의 도구가 말없이 사라진다.
    """
    body = await _json_object(request)
    if body is None or set(body) - {"sandbox_owner"} != {"profile", "config"}:
        return _rejected("profile 과 config 와 sandbox_owner 만 받는다")
    # Hermes 처리기는 config 와 profile 만 읽으므로 sandbox_owner 는 이 검사만 쓴다.
    owner = body.get("sandbox_owner")
    if "sandbox_owner" in body and not (isinstance(owner, str) and SANDBOX_OWNER_RE.match(owner)):
        return _rejected("sandbox_owner 형식이 올바르지 않다")
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
    sandbox = None
    local_execution = False
    if platform is not None and SANDBOX_TOOLSETS & set(platform["api_server"]):
        sandbox = _sandbox_policy()
        if sandbox is None:
            return _sandbox_unavailable()
        if profile not in sandbox["profiles"]:
            if IMAGE_FILE_TOOLSETS & set(platform["api_server"]):
                return _sandbox_unavailable()
            sandbox = None
            local_execution = True
        elif owner is None:
            return _rejected("격리할 셸 도구에는 sandbox_owner 가 필요하다")
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
        config_path = get_profile_dir(profile) / "config.yaml"
        original = config_path.read_bytes()
        saved = yaml.safe_load(original) or {}
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
            if set(_bound_servers(get_profile_dir(profile))) - set(allowed):
                return _rejected("연결된 커넥터의 도구 이름이 빠졌다", 409)
            if CONTROL_PLANE_MCP not in mcp_names and not (set(allowed) & builtins):
                return _rejected("내장 도구가 하나 이상 필요하다")
            updated["platform_toolsets"] = {**(saved.get("platform_toolsets") or {}), **platform}
        if sandbox is not None:
            # 칸 일부만 고치면 운영자가 남긴 local 설정이나 값 전달 칸이 섞인다. 통째로 바꾼다.
            try:
                os.makedirs(_sandbox_workspace(sandbox, owner), exist_ok=True)
                prepared = _sandbox_verify_attachment_directories(sandbox, owner)
                updated["terminal"] = _sandbox_terminal(sandbox, profile, owner, prepared)
                request.state.fos_checked_attachments = (sandbox, owner, prepared)
            except OSError:
                return _sandbox_unavailable()
        elif local_execution:
            # 정책에서 빠진 profile 도 다음 도구 저장부터 local 로 돌아간다.
            # 이미 local 인 설정은 유지하되 .env 의 backend 값보다 명시한 local 값이 이기게 한다.
            previous = saved.get("terminal") or {}
            if not isinstance(previous, dict):
                raise ValueError("terminal 설정이 객체가 아니다")
            terminal = dict(previous) if previous.get("backend", "local") == "local" else {}
            terminal["backend"] = "local"
            updated["terminal"] = terminal
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
            if platform is not None:
                updated = _unlock_api_toolsets(updated, platform["api_server"], platforms, _get_platform_tools)
            effective = set(_get_platform_tools(updated, "api_server"))
            other_changed = any(_get_platform_tools(saved, name) != _get_platform_tools(updated, name)
                                for name in platforms)
        if platform is not None and ("memory" in effective or effective - set(platform["api_server"])):
            return _rejected("요청 목록에 없는 API 도구가 열린다")
        if other_changed:
            return _rejected("다른 platform 의 도구 목록이 바뀐다")
        if skill_dirs and "skills" not in effective:
            return _rejected("skills 도구가 꺼진 채로 스킬을 게시할 수 없다")
        # 처리기의 병합은 본문의 키만 쓴다. 본문에 없는 disabled_toolsets, 고정 목록, terminal 은 plugin 이 먼저 쓴다.
        if updated.get("agent") != saved.get("agent") or updated.get("terminal") != saved.get("terminal"):
            request.state.fos_checked_config = (config_path, original, updated)
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


def _model_defaults_response(name):
    """profile 설정에서 공개 가능한 모델 기본값 세 칸만 돌려준다."""
    if not isinstance(name, str) or not PROFILE_NAME_RE.fullmatch(name):
        return _rejected("profile 이름이 올바르지 않다", 400)
    try:
        from hermes_cli.profiles import get_profile_dir, profile_exists
        from starlette.responses import JSONResponse
        import yaml

        if not profile_exists(name):
            return _rejected("없는 profile 이다", 404)
        config = yaml.safe_load((get_profile_dir(name) / "config.yaml").read_text(encoding="utf-8")) or {}
        model = config.get("model") or {}
        agent = config.get("agent") or {}
        if not isinstance(model, dict) or not isinstance(agent, dict):
            return _rejected("profile 설정을 읽지 못했다", 503)

        def public_text(value):
            return value if isinstance(value, str) and value.strip() else None

        return JSONResponse({"provider": public_text(model.get("provider")),
                             "model": public_text(model.get("default")),
                             "reasoningEffort": public_text(agent.get("reasoning_effort"))}, status_code=200)
    except Exception:
        logger.warning("dashboard-profile-api: 모델 기본값을 읽지 못했다")
        return _rejected("profile 설정을 읽지 못했다", 503)


def _session_provider_response(name, session_id):
    """자식 session 한 줄에서 provider 와 모델만 돌려준다.

    Hermes 의 session 저장소를 읽기 전용으로 연다. Hermes 의 저장소 모듈은 스키마가 낡았으면
    쓰기 연결을 열 수 있어 쓰지 않고 표준 `sqlite3` 만 쓴다(ADR-067).
    주 호출이 쓴 모델과 provider 의 짝이 둘 이상이면 어느 것으로 환산할지 알 수 없어 provider 를 주지 않는다.
    짝이 하나여도 그 provider 가 session 줄의 값과 다르면 주지 않는다.
    """
    if (not isinstance(name, str) or not PROFILE_NAME_RE.fullmatch(name)
            or not isinstance(session_id, str) or not SESSION_ID_RE.fullmatch(session_id)):
        return _rejected("profile 이름이나 session id 가 올바르지 않다", 400)
    try:
        from hermes_cli.profiles import get_profile_dir, profile_exists
        from starlette.responses import JSONResponse

        if not profile_exists(name):
            return _rejected("없는 profile 이다", 404)
        database = get_profile_dir(name) / SESSION_DB_FILE
        if not database.is_file():
            return _rejected("없는 session 이다", 404)
        connection = sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True,
                                     timeout=SESSION_DB_TIMEOUT_SECONDS)
        try:
            row = connection.execute(
                "SELECT source, model, billing_provider FROM sessions WHERE id = ?", (session_id,)).fetchone()
            if row is None or row[0] != "subagent":
                return _rejected("없는 session 이다", 404)
            pairs = connection.execute(
                "SELECT DISTINCT model, billing_provider FROM session_model_usage"
                " WHERE session_id = ? AND task = ''", (session_id,)).fetchall()
        finally:
            connection.close()

        def public_text(value):
            return value if isinstance(value, str) and value else None

        provider = public_text(row[2])
        if len(pairs) > 1:
            provider = None
        elif pairs:
            # 짝이 하나여도 그 provider 가 session 줄과 다르면 어느 쪽이 맞는지 알 수 없다.
            used = public_text(pairs[0][1])
            if used is not None and used != provider:
                provider = None
        return JSONResponse({"provider": provider, "model": public_text(row[1])}, status_code=200)
    except Exception:
        logger.warning("dashboard-profile-api: session 저장소를 읽지 못했다")
        return _rejected("session 저장소를 읽지 못했다", 503)


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

    # 판이 범위 밖이어도 등록은 한다. profile 관리 경로까지 닫으면 사용자 추가가 멈춘다.
    problem = _mcp_sdk_problem()
    if problem is not None:
        logger.warning("dashboard-profile-api: mcp SDK %s 로는 커넥터 도구를 부르지 않는다: %s",
                       _mcp_sdk_version(), problem)

    opened = {}
    for path, method in ALLOWED_ROUTES:
        opened.setdefault(path, []).append(method)
    opened[CONNECTORS_PATH] = ["GET", "PUT"]
    opened[CATALOG_PATH] = ["GET"]
    for path, method in VAULT_ROUTES:
        opened.setdefault(path, []).append(method)
    opened["/api/profiles/<이름>/model-defaults"] = ["GET"]
    opened["/api/profiles/<이름>/sessions/<session id>/provider"] = ["GET"]
    logger.info(
        "dashboard-profile-api: %s 를 토큰으로 연다. 스킬 루트는 %s 다",
        ", ".join(
            ["%s %s" % (" ".join(sorted(methods)), path) for path, methods in sorted(opened.items())]
            + ["GET PUT /api/profiles/<이름>/soul", "DELETE /api/profiles/<관리 표식 profile>",
               "POST /api/connectors/<id>/call", "POST /api/mcp/servers/<설치한 커넥터의 서버>/test"]
        ),
        _skill_root() or "설정되지 않음",
    )
