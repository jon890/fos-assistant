"""dashboard-profile-api 가 기대는 Hermes 내부 지점을 한곳에 선언한다.

이 plugin 은 공개 확장점만으로는 열 수 없는 자리를 쓴다(ADR-088).
Hermes 를 올리면 이 지점이 조용히 바뀔 수 있다. v0.21.5 로 올릴 때 `PUT /api/config` 가 500 으로 깨진 것이 그 예다.

`test_hermes_contract.py` 가 두 가지를 본다.

- plugin 이 import 하는 Hermes 이름이 모두 여기 있는지. Hermes 없이 늘 돈다
- 지정한 Hermes 소스에서 여기 적은 지점이 그대로인지. `HERMES_SOURCE` 를 줄 때만 돈다

Hermes 를 올리기 전에 `scripts/check-hermes-contract.sh <tag>` 로 새 판의 소스를 확인한다.
plugin 이 새 지점에 기대게 되면 같은 커밋에서 여기에 더한다.
"""

from dataclasses import dataclass


# 운영이 쓰는 Hermes 판이다. 상류 저장소의 tag 이름이다. CI 가 이 판의 소스로 계약을 확인한다.
HERMES_VERSION = "v2026.9.24"
# 그 tag 가 가리키는 commit 이다. 상류가 tag 를 옮기면 확인이 실패한다.
HERMES_COMMIT = "f97608f178d1ffeca59860195ab7da295f7c8e5f"
HERMES_REPOSITORY = "https://github.com/NousResearch/hermes-agent"

# plugin 이 import 하는 최상위 이름 중 Hermes 의 것이다. 이 이름으로 시작하는 import 는 모두 아래 표에 있어야 한다.
HERMES_PACKAGES = frozenset({"hermes_cli", "hermes_constants", "hermes_state", "gateway", "toolsets", "plugins"})
# plugin 이 import 해도 되는 제3자 패키지다. 표준 라이브러리와 이것과 Hermes 밖의 import 가 생기면 시험이 실패한다.
# Hermes 의 다른 최상위 패키지(`agent`, `tools` 등)를 쓰기 시작하면 HERMES_PACKAGES 에 더하고 지점을 선언한다.
# GIF/WebP helper는 Hermes core가 이미 고정한 Pillow를 쓴다. 새 의존성을 추가하지 않는다.
NATIVE_IMAGE_PILLOW_VERSION = "12.3.0"

THIRD_PARTY_PACKAGES = frozenset({"yaml", "mcp", "starlette", "PIL"})


@dataclass(frozen=True)
class Call:
    """plugin 이 그 함수를 부르는 모양이다. Hermes 의 시그니처가 이 호출을 받아야 한다."""

    positional: int = 0
    keywords: tuple = ()
    is_async: bool = False
    # plugin 이 돌려받은 값의 모양에 기대면 반환 annotation 을 적는다. 바뀌면 plugin 의 쓰는 곳을 다시 본다.
    returns: str = None
    # plugin 이 이 함수를 바꿔 끼우면 Hermes 정의의 인자가 정확히 이 모양이어야 한다.
    exact: bool = False


@dataclass(frozen=True)
class Value:
    """부르지 않고 값이나 타입으로만 쓰는 이름이다. 있기만 하면 된다."""


@dataclass(frozen=True)
class Base:
    """plugin 이 상속하는 class 다.

    `methods` 는 plugin 이 구현한 메서드와 그 키워드 인자다.
    Hermes 가 추상 메서드를 더하면 plugin 의 class 를 만들 수 없게 된다.
    `attributes` 는 plugin 이 class 속성으로 덮어쓰는 이름이다.
    """

    methods: dict
    attributes: tuple = ()


# provider 가 구현하는 메서드다. Hermes 가 키워드 인자로 부른다.
PROVIDER_METHODS = {
    "verify_token": ("token",),
    "start_login": ("redirect_uri",),
    "complete_login": ("code", "state", "code_verifier", "redirect_uri"),
    "verify_session": ("access_token",),
    "refresh_session": ("refresh_token",),
    "revoke_session": ("refresh_token",),
}

# (모듈, 이름) 마다 plugin 이 쓰는 모양이다. 이름에 `.` 이 있으면 class 안의 메서드다.
SYMBOLS = {
    # 인증. provider 를 등록하고 `token_auth_middleware` 를 바꿔 끼운다.
    ("hermes_cli.dashboard_auth", "DashboardAuthProvider"): Base(
        methods=PROVIDER_METHODS, attributes=("name", "display_name", "supports_token", "supports_session")),
    ("hermes_cli.dashboard_auth", "TokenPrincipal"): Call(keywords=("principal", "provider", "scopes")),
    ("hermes_cli.dashboard_auth", "LoginStart"): Value(),
    ("hermes_cli.dashboard_auth", "Session"): Value(),
    ("hermes_cli.dashboard_auth.token_auth", "token_auth_middleware"): Call(positional=2, is_async=True, exact=True),
    ("hermes_cli.dashboard_auth.token_auth", "authenticate_token"): Call(
        positional=1, returns="Tuple[Optional[TokenPrincipal], Optional[str]]"),
    ("hermes_cli.plugins", "PluginContext.register_dashboard_auth_provider"): Call(positional=1),
    ("plugins.dashboard_auth.drain", "assess_secret_strength"): Call(positional=1),
    # profile
    ("hermes_cli.profiles", "list_profile_names"): Call(),
    ("hermes_cli.profiles", "get_profile_dir"): Call(positional=1),
    ("hermes_cli.profiles", "profile_exists"): Call(positional=1),
    ("hermes_cli.profiles", "delete_profile"): Call(positional=1, keywords=("yes",)),
    ("hermes_cli.profiles", "NO_BUNDLED_SKILLS_MARKER"): Value(),
    ("hermes_cli.config", "save_config"): Call(positional=1, keywords=("strip_defaults",)),
    ("hermes_constants", "get_default_hermes_root"): Call(),
    ("hermes_constants", "set_hermes_home_override"): Call(positional=1),
    ("hermes_constants", "reset_hermes_home_override"): Call(positional=1),
    ("gateway.control_socket", "reload_gateway_plugins"): Call(
        positional=1, keywords=("profile_home",), returns="Optional[dict[str, Any]]"),
    # 도구와 실행 공간 설정
    ("hermes_cli.tools_config", "_get_platform_tools"): Call(positional=2, returns="Set[str]"),
    ("hermes_cli.tools_config", "_get_plugin_toolset_keys"): Call(),
    ("hermes_cli.tools_config", "PLATFORMS"): Value(),
    ("hermes_cli.web_server_profiles", "_config_profile_scope"): Call(positional=1),
    ("toolsets", "TOOLSETS"): Value(),
}

# fos-ctx 원본 도구가 기대는 native 이미지 반환 경로다. 함수 본문을 분리 실행하는 검사가 판 변경을 거른다.
NATIVE_ATTACHMENT_REGISTRATION = ("hermes_cli/plugins.py", "register_tool",
                                Call(keywords=("name", "toolset", "schema", "handler")))
NATIVE_ATTACHMENT_FUNCTIONS = {
    "tools/registry.py": ("_normalize_handler_result",),
    "agent/tool_dispatch_helpers.py": ("_is_multimodal_tool_result", "_is_text_part", "_multimodal_text_summary"),
    "agent/vision_message_prep.py": ("_is_image_part", "_provider_model_key", "_tool_result_content_for_active_model"),
    "agent/codex_responses_adapter.py": ("_nonblank", "_nonempty_str", "_as_list", "_part_type", "_text_type_for",
        "_iter_content_parts", "_input_image_part", "_chat_content_to_responses_parts", "_split_responses_tool_id",
        "_canonical_call_id_from_fc", "_clamp_responses_call_id", "_tool_output_items"),
    "agent/display.py": ("_detect_tool_failure",),
    "agent/tool_executor.py": ("_persist_multimodal_text_parts",),
}
NATIVE_TOOL_EVENT_FIELDS = ("gateway/platforms/api_server_runs.py", "_FIXED_EVENT_FIELDS")

# 대시보드가 요청마다 이 import 를 다시 해야 바꿔 끼운 함수가 쓰인다.
# 모듈 머리에서 한 번만 import 하게 바뀌면 plugin 이 감싼 것이 쓰이지 않고 토큰 경로가 모두 닫힌다.
# 이 import 를 하는 미들웨어는 `token_authenticated` 를 읽는 미들웨어보다 바깥(나중 등록)이어야 한다.
PER_REQUEST_IMPORT = ("hermes_cli/web_server.py", "hermes_cli.dashboard_auth.token_auth", "token_auth_middleware")

# plugin 이 토큰 요청에 다는 `request.state` 칸이다. 대시보드의 쿠키 검사가 이 칸을 보고 넘어가야 한다.
STATE_FLAG = ("token_authenticated", ("hermes_cli/dashboard_auth/middleware.py", "hermes_cli/web_server.py"))

# 자식 session 의 provider 를 읽는 표와 칸이다(ADR-067). profile 디렉터리 아래 `SESSION_DB_FILE` 에 있다.
# Hermes 는 `SESSION_DB_MODULE` 에서 `<SESSION_DB_HOME>() / SESSION_DB_FILE` 로 그 경로를 만든다.
SESSION_DB_FILE = "state.db"
SESSION_DB_MODULE = "hermes_state.py"
SESSION_DB_HOME = "get_hermes_home"
SESSION_COLUMNS = {
    "sessions": ("id", "source", "parent_session_id", "model", "billing_provider"),
    "session_model_usage": ("session_id", "model", "billing_provider", "task"),
}
# `sessions.source` 에서 native 하위 에이전트를 가리키는 값이다.
# 위임 도구가 자식 에이전트를 만들 때 `platform=` 으로 넘기고, 그 값이 자식 session 의 `source` 가 된다.
SUBAGENT_SOURCE = ("subagent", "tools/delegate_tool.py")

# plugin 이 감싸 Hermes 처리기로 넘기는 대시보드 경로다. Hermes 가 이 경로를 없애거나 옮기면 감싼 검사가 헛돈다.
HERMES_ROUTES = frozenset({
    ("GET", "/api/profiles"),
    ("POST", "/api/profiles"),
    ("DELETE", "/api/profiles/{}"),
    ("GET", "/api/profiles/{}/soul"),
    ("PUT", "/api/profiles/{}/soul"),
    ("PUT", "/api/env"),
    ("DELETE", "/api/env"),
    ("GET", "/api/tools/toolsets"),
    ("PUT", "/api/config"),
    ("GET", "/api/skills"),
    ("PUT", "/api/skills/toggle"),
    ("POST", "/api/mcp/servers/{}/test"),
})

# 감싸는 경로마다 plugin 이 검사하고 보내는 칸이다. 처리기의 인자나 본문 model 의 칸에 있어야 한다.
# 이름이 바뀌면 pydantic 이 모르는 칸을 버려, plugin 이 검사한 profile 과 처리기가 쓰는 profile 이 갈라진다.
HANDLER_FIELDS = {
    ("POST", "/api/profiles"): ("name", "no_skills", "description"),
    ("PUT", "/api/profiles/{}/soul"): ("name", "content"),
    ("PUT", "/api/env"): ("profile", "key", "value"),
    ("DELETE", "/api/env"): ("profile", "key"),
    ("PUT", "/api/config"): ("profile", "config"),
    ("GET", "/api/skills"): ("profile",),
    ("PUT", "/api/skills/toggle"): ("profile", "name", "enabled"),
    ("POST", "/api/mcp/servers/{}/test"): ("name", "profile"),
}

# plugin 이 처리기 없이 직접 답하는 경로다. Hermes 가 같은 경로를 만들면 그 기능이 plugin 에 가려진다.
OWN_ROUTES = frozenset({
    ("GET", "/api/connectors"),
    ("PUT", "/api/connectors"),
    ("GET", "/api/connectors/catalog"),
    ("POST", "/api/connectors/{}/call"),
    ("POST", "/api/connectors/{}/execute"),
    ("PUT", "/api/connector-vault"),
    ("DELETE", "/api/connector-vault"),
    ("POST", "/api/connector-vault/import"),
    ("GET", "/api/profiles/{}/model-defaults"),
    ("GET", "/api/profiles/{}/sessions/{}/provider"),
})

# 바인딩 설치가 재시작 없이 반영되는 데 기대는 지점이다(ADR-20261007 connector-live-reload).
# 공유 gateway 의 housekeeping 이 profile 마다 `mcp_servers` 와 살아 있는 연결을 맞추고,
# 스킬 색인 캐시 키에 `skills.disabled` 가 들어 있어 plugin 이 바꾼 색인 표식이 그 profile 의 색인을 새로 만들게 한다.
# 이 지점이 바뀌면 붙인 커넥터가 재시작 전까지 보이지 않는데 Control Plane 은 반영됐다고 판정한다.
LIVE_RELOAD = {
    # housekeeping 작업 목록에 MCP 설정 맞추기 작업이 있다. 그 이름 문자열이 있는 파일이다.
    "reconcile_chore": ("gateway/run.py", "MCP config reconcile"),
    # 맞추기 작업을 만드는 함수와, 그 안에서 부르는 맞추기 함수다.
    "reconciler": ("gateway/run_profile_reconcile.py", "_mcp_config_reconciler", "reconcile_mcp_servers_with_config"),
    # 스킬 색인을 만드는 함수, 꺼진 스킬 이름을 읽는 함수, 그 결과를 담는 이름, 캐시 키를 담는 이름이다.
    "skill_index_key": ("agent/prompt_builder.py", "_build_skills_system_prompt_inner", "get_disabled_skill_names",
                        "disabled", "cache_key"),
}

# docker profile 의 `approvals.unattended_mode: approve` 가 `execute_code` 만 열고 셸 위험 명령과 plugin 승인 요청은 승인 카드로 남는 데 기대는 지점이다.
# 이 지점이 바뀌면 같은 설정이 셸과 커넥터 승인까지 사람 없이 여는데 Control Plane 은 알지 못한다.
# 실패하면 ADR-20261008 execute-code-unattended 를 다시 본다.
UNATTENDED_APPROVAL = {
    # gateway 가 프로세스 환경에 켜는 ask 표식이다
    "exec_ask": ("gateway/run.py", "start_gateway", "HERMES_EXEC_ASK"),
    # ask 문맥에서는 unattended 모드를 보지 않는 판정 함수들이다
    "ask_first": ("tools/approval.py", ("check_all_command_guards", "_run_approval_gate"),
                  "_unattended_contexts", "is_ask"),
    # is_ask 를 그 환경 변수에서 계산하는 함수다
    "ask_source": ("tools/approval.py", "_presence", "is_ask", "HERMES_EXEC_ASK"),
    # _unattended_contexts 를 부르는 함수 전부다. 첫 칸은 대표 정의 위치이며 호출을 찾는 범위는 tools, agent, gateway 전체다.
    # 늘면 그 함수가 ask 문맥을 먼저 보는지 확인하고 더한다
    "callers": ("tools/approval.py", "_unattended_contexts",
                frozenset({"check_all_command_guards", "_run_approval_gate", "check_execute_code_guard"})),
    # 컨테이너 예외를 unattended 판정보다 먼저 보는 execute_code 판정이다
    "execute_code": ("tools/approval.py", "check_execute_code_guard",
                     "_should_skip_container_guards", "_unattended_contexts"),
    # plugin 이 쓰는 키 이름과 그 키가 적용되는 플랫폼이다
    "mode_key": ("tools/approval_context.py", "_get_unattended_approval_mode", "unattended_mode"),
    "platform": ("tools/approval_context.py", "_UNATTENDED_APPROVAL_PLATFORMS", "api_server"),
    # 모드를 읽는 getter 를 직접 부르는 함수다. 지금은 `getattr` 로 이름을 조립해서만 불려 직접 호출이 없다.
    # 직접 부르는 함수가 생기면 그 호출이 ask 문맥을 먼저 보는지 확인하고 더한다. 첫 칸은 대표 정의 위치다
    "getter_callers": ("tools/approval_context.py", "_get_unattended_approval_mode", frozenset()),
}

# 바꿔 끼우기를 공개 확장점으로 대체하지 못하게 막는 지점이다(ADR-088).
# 상류가 이 시그니처를 바꾸면 메서드 단위 등록이나 쿠키로 넘기기가 생겼을 수 있다. 실패하면 ADR-088 의 판정을 다시 본다.
REVISIT_SIGNALS = {
    ("hermes_cli.dashboard_auth.token_auth", "register_token_route"): ("path",),
    ("hermes_cli.dashboard_auth.token_auth", "is_token_route"): ("path",),
}
