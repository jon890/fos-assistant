"""plugin과 runtime addon이 기대는 Hermes 내부 지점을 한곳에 선언한다.

이 plugin 은 공개 확장점만으로는 열 수 없는 자리를 쓴다(ADR-088).
Hermes 를 올리면 이 지점이 조용히 바뀔 수 있다. v0.21.5 로 올릴 때 `PUT /api/config` 가 500 으로 깨진 것이 그 예다.

`test_hermes_contract.py` 가 두 가지를 본다.

- plugin 이 import 하는 Hermes 이름이 모두 여기 있는지. Hermes 없이 늘 돈다
- 지정한 Hermes 소스에서 여기 적은 지점이 그대로인지. `HERMES_SOURCE` 를 줄 때만 돈다

본문 없는 writer와 관리 bootstrap의 별도 source 계약은 `test_bodyless_*_contract.py`가 확인한다.

Hermes 를 올리기 전에 `scripts/checks/check-hermes-contract.sh <tag>` 로 새 판의 소스를 확인한다.
plugin 이 새 지점에 기대게 되면 같은 커밋에서 여기에 더한다.
"""

from dataclasses import dataclass


# 연동 기준 Hermes 버전이다. 상류 저장소의 tag 이름이다. CI 가 이 버전의 소스로 계약을 확인한다.
HERMES_VERSION = "v0.21.6"
# 그 tag 가 가리키는 commit 이다. 상류가 tag 를 옮기면 확인이 실패한다.
HERMES_COMMIT = "818c13be1dc4fd28987e1e881a9408224afd4535"
HERMES_REPOSITORY = "https://github.com/NousResearch/hermes-agent"

# plugin 이 import 하는 최상위 이름 중 Hermes 의 것이다. 이 이름으로 시작하는 import 는 모두 아래 표에 있어야 한다.
HERMES_PACKAGES = frozenset({"hermes_cli", "hermes_constants", "hermes_state", "gateway", "toolsets", "plugins"})
# plugin 이 import 해도 되는 제3자 패키지다. 표준 라이브러리와 이것과 Hermes 밖의 import 가 생기면 시험이 실패한다.
# Hermes 의 다른 최상위 패키지(`agent`, `tools` 등)를 쓰기 시작하면 HERMES_PACKAGES 에 더하고 지점을 선언한다.
# GIF/WebP helper는 Hermes core가 이미 고정한 Pillow를 쓴다. 새 의존성을 추가하지 않는다.
NATIVE_IMAGE_PILLOW_VERSION = "12.3.0"
# profile별 적재와 reload가 공유 FIFO의 module 이름을 지우지 않는다는 경계다.
NATIVE_IMAGE_PLUGIN_LOADER = ("hermes_cli/plugins_loader.py", "_directory_module_name", "_evict_modules")

THIRD_PARTY_PACKAGES = frozenset({"yaml", "mcp", "starlette", "PIL"})


@dataclass(frozen=True)
class Call:
    """plugin 이 그 함수를 부르는 모양이다. Hermes 의 시그니처가 이 호출을 받아야 한다."""

    positional: int = 0
    keywords: tuple = ()
    is_async: bool = False
    # plugin 이 돌려받은 값의 모양에 기대면 반환 annotation 을 적는다. 바뀌면 plugin 의 쓰는 곳을 다시 본다.
    returns: str | None = None
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

# 관리 bootstrap이 교체하는 내부 writer다. 공식 upstream 확장 API가 아니다.
BODYLESS_REQUEST_WRITER = ("agent/agent_runtime_helpers.py", "dump_api_request_debug",
                          Call(positional=2, keywords=("reason", "error"), exact=True))
BODYLESS_REQUEST_FORWARD = ("agent/session_persistence.py", "SessionPersistenceMixin",
                           "_dump_api_request_debug", "agent.agent_runtime_helpers", "dump_api_request_debug")
BODYLESS_REQUEST_LAZY = ("agent/lazy_forward.py", ("lazy_attr", "forward"))
BODYLESS_REQUEST_CALLS = {
    "agent/turn_api_request.py": {"build_api_request": "preflight"},
    "agent/turn_recovery.py": {
        "nonretryable_client_error_result": "non_retryable_client_error",
        "max_retries_exhausted_result": "max_retries_exhausted",
    },
}
BODYLESS_REQUEST_FILES = (
    BODYLESS_REQUEST_WRITER[0], BODYLESS_REQUEST_LAZY[0], BODYLESS_REQUEST_FORWARD[0],
    *BODYLESS_REQUEST_CALLS,
)

# 관리 bootstrap의 추가 의존이다. writer 설치 API와 다섯 파일 지문에 합치지 않는다.
# 공식 고정 commit의 위치·키워드 전용 인자, 기본값·annotation과 함수 AST를 선언한다.
# AST hash는 행 번호·공백·주석을 제외하고 함수 전체(시그니처·decorator·본문)를 제한한다.
# 큰 함수도 선언한 함수만 검사한다. 상류 변경 때 이 계약과 private owner를 함께 검토한다.
BODYLESS_MANAGED_FUNCTIONS = {
    'agent/agent_init.py': {
        'init_agent': (
            ('agent, base_url: str=None, api_key: str=None, provider: str=None, api_mode: str=None, acp_command: '
             'str=None, acp_args: list[str] | None=None, command: str=None, args: list[str] | None=None, model: '
             "str='', max_iterations: int=sys.maxsize, enabled_toolsets: List[str]=None, disabled_toolsets: "
             'List[str]=None, save_trajectories: bool=False, verbose_logging: bool=False, quiet_mode: bool=False, '
             "tool_progress_mode: str='all', ephemeral_system_prompt: str=None, log_prefix_chars: int=100, "
             "log_prefix: str='', providers_allowed: List[str]=None, providers_ignored: List[str]=None, "
             'providers_order: List[str]=None, provider_sort: str=None, provider_require_parameters: bool=False, '
             'provider_data_collection: str=None, openrouter_min_coding_score: Optional[float]=None, session_id: '
             'str=None, tool_progress_callback: callable=None, tool_start_callback: callable=None, '
             'tool_complete_callback: callable=None, thinking_callback: callable=None, reasoning_callback: '
             'callable=None, clarify_callback: callable=None, read_terminal_callback: callable=None, '
             'read_preview_callback: callable=None, drive_preview_callback: callable=None, '
             'read_window_below_callback: callable=None, connection_callback: callable=None, tour_callback: '
             'callable=None, setup_choose_callback: callable=None, step_callback: callable=None, '
             'stream_delta_callback: callable=None, '
             'interim_assistant_callback: callable=None, tool_gen_callback: callable=None, status_callback: '
             'callable=None, notice_callback: callable=None, notice_clear_callback: callable=None, event_callback:'
             ' Optional[Callable[[str, dict], None]]=None, reaction_callback: Optional[Callable[[str], '
             'None]]=None, max_tokens: int=None, reasoning_config: Dict[str, Any]=None, service_tier: str=None, '
             'request_overrides: Dict[str, Any]=None, prefill_messages: List[Dict[str, Any]]=None, platform: '
             'str=None, user_id: str=None, user_id_alt: str=None, user_name: str=None, chat_id: str=None, '
             'chat_name: str=None, chat_type: str=None, thread_id: str=None, gateway_session_key: str=None, '
             'skip_context_files: bool=False, load_soul_identity: bool=False, skip_memory: bool=False, '
             'skip_background_review: bool=False, session_db=None, parent_session_id: str=None, iteration_budget: '
             "'IterationBudget'=None, run_budget_seconds: Optional[float]=None, fallback_model: Dict[str, "
             'Any]=None, credential_pool=None, checkpoints_enabled: bool=False, checkpoint_max_snapshots: int=20, '
             'checkpoint_max_total_size_mb: int=500, checkpoint_max_file_size_mb: int=10, pass_session_id: '
             'bool=False, requested_provider: str=None, capabilities: Optional[Dict[str, bool]]=None, cwd: '
             'Optional[str]=None, side_agent: bool=False, memory_manager=None, tool_result_metadata_callback: '
             'Optional[Callable[..., dict]]=None'),
            None,
            'ee793abc8771756a84cbbd28bf70ff787a14ae31c65bdc1af69dd3ed93a7c6c6'),
    },
    'run_agent.py': {
        'AIAgent.__init__': (
            ('self, base_url: str=None, api_key: str=None, provider: str=None, api_mode: str=None, acp_command: '
             'str=None, acp_args: list[str] | None=None, command: str=None, args: list[str] | None=None, model: '
             "str='', max_iterations: int=sys.maxsize, tool_delay: float=None, enabled_toolsets: List[str]=None, "
             'disabled_toolsets: List[str]=None, save_trajectories: bool=False, verbose_logging: bool=False, '
             "quiet_mode: bool=False, tool_progress_mode: str='all', ephemeral_system_prompt: str=None, "
             "log_prefix_chars: int=100, log_prefix: str='', providers_allowed: List[str]=None, providers_ignored:"
             ' List[str]=None, providers_order: List[str]=None, provider_sort: str=None, '
             'provider_require_parameters: bool=False, provider_data_collection: str=None, '
             'openrouter_min_coding_score: Optional[float]=None, session_id: str=None, tool_progress_callback: '
             'callable=None, tool_start_callback: callable=None, tool_complete_callback: callable=None, '
             'thinking_callback: callable=None, reasoning_callback: callable=None, clarify_callback: '
             'callable=None, read_terminal_callback: callable=None, read_preview_callback: callable=None, '
             'drive_preview_callback: callable=None, read_window_below_callback: callable=None, '
             'connection_callback: callable=None, tour_callback: callable=None, '
             'setup_choose_callback: callable=None, step_callback: callable=None, '
             'stream_delta_callback: callable=None, interim_assistant_callback: callable=None, tool_gen_callback: '
             'callable=None, status_callback: callable=None, notice_callback: callable=None, '
             'notice_clear_callback: callable=None, event_callback: Optional[Callable[[str, dict], None]]=None, '
             'reaction_callback: Optional[Callable[[str], None]]=None, max_tokens: int=None, reasoning_config: '
             'Dict[str, Any]=None, service_tier: str=None, request_overrides: Dict[str, Any]=None, '
             'prefill_messages: List[Dict[str, Any]]=None, platform: str=None, user_id: str=None, user_id_alt: '
             'str=None, user_name: str=None, chat_id: str=None, chat_name: str=None, chat_type: str=None, '
             'thread_id: str=None, gateway_session_key: str=None, skip_context_files: bool=False, '
             'load_soul_identity: bool=False, skip_memory: bool=False, skip_background_review: bool=False, '
             "session_db=None, parent_session_id: str=None, iteration_budget: 'IterationBudget'=None, "
             'run_budget_seconds: Optional[float]=None, fallback_model: Dict[str, Any]=None, credential_pool=None,'
             ' checkpoints_enabled: bool=False, checkpoint_max_snapshots: int=20, checkpoint_max_total_size_mb: '
             'int=500, checkpoint_max_file_size_mb: int=10, pass_session_id: bool=False, requested_provider: '
             'str=None, capabilities: Dict[str, bool] | None=None, cwd: str | None=None, side_agent: bool=False, '
             'memory_manager=None, tool_result_metadata_callback: Optional[Callable[..., dict]]=None'),
            None,
            '81effa5c4102f83a66acbc2cbb5ef578eef27ae689c1c38208fa9cf881f3929f'),
        'AIAgent.close': (
            ('self'),
            'None',
            'd30df6ba79ed730261abc8973319dddcfdb442ecd07c7033ba80a30a0df249c5'),
    },
    'tools/delegate_tool.py': {
        '_build_child_agent': (
            ('task_index: int, goal: str, context: Optional[str], toolsets: Optional[List[str]], model: '
             'Optional[str], max_iterations: int, task_count: int, parent_agent, override_provider: '
             'Optional[str]=None, override_base_url: Optional[str]=None, override_api_key: Optional[str]=None, '
             'override_api_mode: Optional[str]=None, override_request_overrides: Optional[Dict[str, Any]]=None, '
             'override_acp_command: Optional[str]=None, override_acp_args: Optional[List[str]]=None, routing_cfg: '
             "Optional[Dict[str, Any]]=None, role: str='leaf'"),
            None,
            'b32c188da5f322e03285711ffa18520d65278f0edb636bb69aada9571ebf9ae5'),
    },
    'agent/secret_scope.py': {
        'load_env_file': (
            ('env_path: Path'),
            'Dict[str, str]',
            'b3512b6c0ddf893b8b895bcc518efb8bf7e0a85f326e0a6702a83b61c5988bec'),
        'current_secret_scope_home': (
            (''),
            'Optional[str]',
            '1e9bb7e35c02dcac823465bc5683cec951a9caf0d0a9de8d9c3f42b5240c8bd5'),
        'build_profile_secret_scope': (
            ('hermes_home: Path'),
            'Dict[str, str]',
            '8a1580b149d71982c2a2c86133703f820c8c9b02d3d06d8fe20a82cdf0d8d59c'),
        'set_secret_scope': (
            ('secrets: Optional[Mapping[str, str]], *, profile_home: Optional[str]=None'),
            'Token',
            'b03ae1c99d79c007893d6cac6cefe35225d47abec486d7949ae9c6df4146c0e0'),
        'reset_secret_scope': (
            ('token: Token'),
            'None',
            'b9fca45295012a6a357c2798774479f293d9d0aa0ad815eabb4912b3a4b4a35a'),
    },
    'hermes_constants.py': {
        'set_hermes_home_override': (
            ('path: str | Path | None'),
            'Token',
            'b79f4b1af9d55ff229bffbd6ae1ef92f8dd84a41f5efd8df9f47e9b2111a16d0'),
        'get_hermes_home_override': (
            (''),
            'str | None',
            '08a5940033cc96d04c96b6c8f1131f1ab5cb4c224f04603a550f839530ee8cc2'),
        'reset_hermes_home_override': (
            ('token: Token'),
            'None',
            'cf9bfcd238776707dda832877e43e06204b016ad44143361e87f9f832731549b'),
    },
    'cron/scheduler.py': {
        'run_one_job': (
            ('job: dict, *, adapters=None, loop=None, verbose: bool=False, '
             'extra_prompt: Optional[str]=None, cancel_event: Optional[_CancelEventLike]=None'),
            'bool',
            'c5be4bef1178f3434502499fd1df641b3a4ea4f0a5d5621eb652b875bb194146'),
        '_launch_external_cron_worker': (
            ('job: dict'),
            'bool',
            '94c831733d9faa0424f95425be317c79e8598c06f3005ccb91796eb743100287'),
        '_run_external_worker_payload': (
            ('payload_path: Path, ack_path: Path'),
            'bool',
            '828ffebd9fef767b23221af8b873b732179b84f3bca04bd8ac9eb40a0ff07461'),
    },
}

# context-local 저장소와 cron의 module-global sys 조회, child 진입점도 함수 밖 계약이다.
BODYLESS_MANAGED_MODULE_NODES = {
    'agent/secret_scope.py': {
        'from contextvars': '4d0b69a21e07c09772f472c6b0411bc712725d65289d6dbe78af988bf4f22819',
        '_SECRET_SCOPE': 'e1d8ad0021ac4a95106cc9ca4eebf23585a9ed588e615423ecc9d03eb47924e8',
        '_BoundScope': '77f0d9930ea6754d082810686e2be8e15c76712533f7767d07ca758f18c4d910',
    },
    'hermes_constants.py': {
        'from contextvars': '4d0b69a21e07c09772f472c6b0411bc712725d65289d6dbe78af988bf4f22819',
        '_HERMES_HOME_OVERRIDE': '98eaa1371fae960a502aed95e91d5d64b69e64a1d94702c00db8b5fcc309fd7c',
        '_UNSET': '6eaf8669f559cd824f5088f162c8ea4f1edab6686f51f7fcea9fbaa6ad635ccb',
    },
    'cron/scheduler.py': {
        'import sys': 'f7252e3c1dbe1d5edf1f34c020a58a05b55f706b2c1d9479eae91f9a9d4be275',
        '__main__': 'a17f73ab00b24c1f21e1ba7dcd46af2cf598ddea7e0cb9356846919b3b0b842a',
    },
}

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
