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
| `POST /api/profiles` | profile 을 만든다. `profiles.py` 의 「만든 자리에서 설정 틀을 쓴다」 를 거친다 |
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

커넥터 경로의 계약은 `docs/features/connector.md` 의 「대시보드 plugin 계약」 이 소유한다(ADR-043).
이 plugin 은 커넥터의 이름을 코드에 두지 않는다. 운영 목록의 plugin 디렉터리마다 `connector.json` 을 읽는다.

## 비밀값

`HERMES_DASHBOARD_PROFILE_API_SECRET` 하나를 받는다.
값이 없으면 아무것도 등록하지 않고 끝난다.

엔트로피 판정은 번들 drain plugin 의 `assess_secret_strength` 를 그대로 쓴다.
같은 기준을 두 벌 두지 않기 위해서다. 그 함수를 읽어 오지 못하면 등록하지 않는다.

비교는 `hmac.compare_digest` 로 한다.
"""

from __future__ import annotations

import os

# 시험과 기존 호출부가 쓰는 이름을 진입점에서 다시 내보낸다.
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

from .connector_output import (
    _sandbox_connector_output_directory,
    _sandbox_make_private_directory,
    _sandbox_prepare_connector_output,
    _sandbox_remove_connector_output,
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
    _connector_error_answer,
    _connector_execute_answer,
    _connector_execute_request,
    _installed_owner_attachments,
    _installed_owner_browser,
    _installed_server_env,
    _leaf_error_types,
    _mcp_sdk_problem,
    _mcp_sdk_version,
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
    SANDBOX_TOOLSETS,
    SandboxAttachmentError,
    _sandbox_attachment_agent_directory,
    _sandbox_attachment_directory,
    _sandbox_attachment_key,
    _sandbox_attachment_path_identity,
    _sandbox_attachment_snapshot,
    _sandbox_connector_output_profile_directory,
    _sandbox_env_ok,
    _sandbox_policy,
    _sandbox_profiles,
    _sandbox_terminal,
    _sandbox_unavailable,
    _sandbox_validate_attachment_snapshot,
    _sandbox_verify_attachment_directories,
    _sandbox_workspace,
)

from .sandbox_paths import (
    SANDBOX_RESERVED_PATHS,
    _sandbox_attachment_mount_overlaps,
    _sandbox_attachment_roots_ok,
    _sandbox_connector_output_mount_overlaps,
    _sandbox_connector_output_root_ok,
    _sandbox_mount_ok,
    _sandbox_mount_overlaps,
    _sandbox_mounts_ok,
    _sandbox_path_ok,
    _sandbox_paths_overlap,
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
