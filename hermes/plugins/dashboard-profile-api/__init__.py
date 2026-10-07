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

import os
from .common import (
    BASE_ENV_KEYS,
    CONNECTOR_HOST_MARKER,
    CONTROL_PLANE_MCP,
    MANAGED_MARKER,
    PLUGIN_DIR,
    PROFILE_NAME_RE,
    SKILL_NAME_RE,
    _atomic_private_write,
    _env_line,
    _env_line_key,
    _env_value,
    _json_object,
    _missing_profile,
    _profile_rejection,
    _rejected,
    logger,
)

from .connector_install import (
    CONNECTOR_DETACHED,
    CONNECTOR_TOOL_MAP,
    POLICY_PLUGIN,
    PROBE_ROUTE_RE,
    PROFILE_SKILLS_DIR,
    SOUL_FILE,
    _bind_entry_env,
    _bind_entry_shape,
    _bind_skills_installed,
    _check_connector_probe,
    _connector_allowlist,
    _connector_bind_config,
    _connector_config,
    _connector_request,
    _connector_server,
    _connector_state,
    _connector_tool_map,
    _detached_bytes,
    _detached_servers,
    _entry_field_env,
    _entry_matches_manifest,
    _owned_mode,
    _policy_hook_active,
    _policy_plugin_enabled,
    _remove_backup_env_copies,
    _skill_tree_files,
    _tool_map_bytes,
)

from .connector_manifest import (
    BIND_MODE,
    CONNECTOR_COMMAND_ENV,
    CONNECTOR_ID_RE,
    CONNECTOR_PERSONA_MAX_CHARS,
    CONNECTOR_ROOTS_ENV,
    CONNECTOR_SKILL_MAX_CHARS,
    CONNECTOR_SKILL_MAX_FILES,
    CONNECTOR_SKILL_PARTS,
    CONNECTOR_STATE,
    CONNECTOR_TOOLSETS,
    ENV_NAME_RE,
    ERROR_CODE_RE,
    ERROR_DETAILS_MAX,
    ERROR_RECOVERIES,
    ERROR_WORDS,
    FIELD_KEY_RE,
    HERMES_TOOL_NAME_MAX_CHARS,
    HERMES_TOOL_PREFIX_MAX_CHARS,
    ISOLATED_MODE,
    OUTCOME_UNKNOWN,
    OWNER_ATTACHMENTS_ENV_RE,
    OWNER_ATTACHMENTS_VALUE_RE,
    PLUGIN_ROOT_REF,
    SECRET_ARGUMENT_NAMES,
    SECRET_ARGUMENT_SUFFIXES,
    SERVER_NAME_RE,
    TOOL_APPROVALS,
    TOOL_IDENTIFIER_RE,
    TOOL_NAME_RE,
    TOOL_RISKS,
    TOOL_RISK_DEFAULTS,
    TOOL_TITLE_MAX_CHARS,
    _canonical_server_name,
    _connector_catalog,
    _connector_catalog_response,
    _connector_command,
    _connector_env_keys,
    _connector_errors,
    _connector_fields,
    _connector_manifest,
    _connector_persona,
    _connector_roots,
    _connector_skills,
    _connector_tools,
    _entry_mode,
    _hermes_tool_name,
    _load_connector,
    _read_connector_json,
    _server_matches,
    _skill_name,
    _tool_identifiers,
)

from .connector_run import (
    CONNECTOR_CALL_LIMIT,
    CONNECTOR_CALL_TIMEOUT_SECONDS,
    CONNECTOR_EXECUTE_TIMEOUT_SECONDS,
    ERROR_DETAIL_INT_MAX,
    MCP_SDK_MAJOR,
    _UNREADABLE,
    _connector_call_answer,
    _connector_call_request,
    _connector_calls,
    _connector_error_answer,
    _connector_execute_answer,
    _connector_execute_request,
    _installed_owner_attachments,
    _leaf_error_types,
    _mcp_sdk_problem,
    _mcp_sdk_version,
    _mcp_sdk_version_cache,
    _run_connector_execute,
    _run_connector_tool,
    _safe_error_detail,
    _tool_error,
    _tool_payload,
)

from .connector_vault import (
    CONNECTOR_VAULT_DIR,
    VAULT_ID_RE,
    VAULT_IMPORT_PATH,
    VAULT_PATH,
    VAULT_ROUTES,
    _connector_vault_request,
    _read_vault,
    _vault_dir,
    _vault_path,
    _vault_values,
    _write_vault,
)

from .env import (
    _check_env_delete,
    _check_env_update,
    _operator_env_ignored,
)

from .profiles import (
    FORBIDDEN_TOOLSETS,
    PROFILE_CREATE_KEYS,
    PROFILE_PLUGIN_DIR,
    PROFILE_PLUGIN_FILES,
    TEMPLATE_PATH,
    _apply_template,
    _check_profile_create,
    _check_skill_toggle,
    _check_skills_list,
    _copy_profile_plugin,
    _decision_readiness_response,
    _delete_check,
    _model_defaults_response,
    _profile_names,
    _profile_plugin_files,
    _provision,
    _reload_profile_plugins,
    _remove_created,
    _write_managed_marker,
)

from .routes import (
    ALLOWED_ROUTES,
    CALL_ROUTE_RE,
    CATALOG_PATH,
    CONNECTORS_PATH,
    DECISION_READINESS_RE,
    EXECUTE_ROUTE_RE,
    MODEL_DEFAULTS_RE,
    PROFILES_PATH,
    PROFILE_PREFIX,
    PROFILE_WRITE_LOCK,
    ProfileApiProvider,
    SESSION_PROVIDER_RE,
    SOUL_METHODS,
    SOUL_SUFFIX,
    _install_gate,
    _profile_segment,
)

from .sandbox import (
    IMAGE_FILE_TOOLSETS,
    SANDBOX_ENV,
    SANDBOX_NETWORK_RE,
    SANDBOX_OWNER_RE,
    SANDBOX_PATH_ENV,
    SANDBOX_POLICY_KEYS,
    SANDBOX_PROFILE_KEYS,
    SANDBOX_RESERVED_PATHS,
    SANDBOX_TOOLSETS,
    SandboxAttachmentError,
    _sandbox_attachment_agent_directory,
    _sandbox_attachment_directory,
    _sandbox_attachment_key,
    _sandbox_attachment_mount_overlaps,
    _sandbox_attachment_path_identity,
    _sandbox_attachment_roots_ok,
    _sandbox_attachment_snapshot,
    _sandbox_env_ok,
    _sandbox_mount_ok,
    _sandbox_mount_overlaps,
    _sandbox_mounts_ok,
    _sandbox_path_ok,
    _sandbox_paths_overlap,
    _sandbox_policy,
    _sandbox_profiles,
    _sandbox_terminal,
    _sandbox_unavailable,
    _sandbox_validate_attachment_snapshot,
    _sandbox_verify_attachment_directories,
    _sandbox_workspace,
)

from .session import (
    SESSION_DB_FILE,
    SESSION_DB_TIMEOUT_SECONDS,
    SESSION_ID_RE,
    _session_provider_response,
)

from .toolconfig import (
    SKILL_ROOT_ENV,
    SKILL_TREE_LIMIT,
    SKILL_VERSION_RE,
    _bound_servers,
    _check_config_update,
    _operator_skill_dirs,
    _restore_config,
    _skill_dir_rejection,
    _skill_root,
    _toolset_rejection,
    _unlock_api_toolsets,
    _write_checked_config,
)



ENV_VAR = "HERMES_DASHBOARD_PROFILE_API_SECRET"


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
    opened["/api/profiles/<이름>/decision-readiness"] = ["GET"]
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
