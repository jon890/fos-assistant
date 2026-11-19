# 코드 규칙: rule-64. 까닭과 예외는 docs/code-rules/INDEX.md를 본다.
"""plugin과 runtime addon이 기대는 Hermes 내부 지점을 한곳에 선언한다.

이 plugin 은 공개 확장점만으로는 열 수 없는 자리를 쓴다(ADR-088).
Hermes 를 올리면 이 지점이 조용히 바뀔 수 있다. v0.21.5 로 올릴 때 `PUT /api/config` 가 500 으로 깨진 것이 그 예다.

`test_hermes_contract.py` 가 두 가지를 본다.

- plugin 이 import 하는 Hermes 이름이 모두 여기 있는지. Hermes 없이 늘 돈다
- 지정한 Hermes 소스에서 여기 적은 지점이 그대로인지. `HERMES_SOURCE` 를 줄 때만 돈다

본문 없는 writer와 관리 bootstrap의 별도 source 계약은 `test_bodyless_*_contract.py`가 확인한다.

Hermes 를 올리기 전에 `scripts/checks/check-hermes-contract.sh <tag>` 로 새 버전의 소스를 확인한다.
plugin 이 새 지점에 기대게 되면 같은 커밋에서 여기에 더한다.
"""

from dataclasses import dataclass


# 연동 기준 Hermes 버전이다. 상류 저장소의 tag 이름이다. CI 가 이 버전의 소스로 계약을 확인한다.
HERMES_VERSION = "v0.23.2"
# 그 tag 가 가리키는 commit 이다. 상류가 tag 를 옮기면 확인이 실패한다.
HERMES_COMMIT = "ae2a81ccc00e9b7c06301523e5bb09608397149c"
HERMES_REPOSITORY = "https://github.com/NousResearch/hermes-agent"

# plugin 이 import 하는 최상위 이름 중 Hermes 의 것이다. 이 이름으로 시작하는 import 는 모두 아래 표에 있어야 한다.
HERMES_PACKAGES = frozenset({"hermes_cli", "hermes_constants", "hermes_state", "gateway", "toolsets", "plugins"})
# plugin 이 import 해도 되는 제3자 패키지다. 표준 라이브러리와 이것과 Hermes 밖의 import 가 생기면 시험이 실패한다.
# Hermes 의 다른 최상위 패키지(`agent`, `tools` 등)를 쓰기 시작하면 HERMES_PACKAGES 에 더하고 지점을 선언한다.
# GIF/WebP helper는 Hermes core가 이미 고정한 Pillow를 쓴다. 새 의존성을 추가하지 않는다.
NATIVE_IMAGE_PILLOW_VERSION = "13.0.0"
# profile별 적재와 reload가 공유 FIFO의 module 이름을 지우지 않는다는 경계다.
NATIVE_IMAGE_PLUGIN_LOADER = ("hermes_cli/plugins_loader.py", "_directory_module_name", "_evict_modules")

# `httpx2` 는 `mcp` SDK 2 가 함께 설치하는 HTTP 클라이언트다. 원격 MCP 플러그인의 클라이언트를 redirect 없이 직접 만든다.
# `cryptography` 는 Hermes core 가 고정한 의존이다. 보관 파일을 AES-256-GCM 으로 암호화한다(ADR-20261103 connector-vault-key).
# Hermes 를 올릴 때 core 의존에 남아 있는지 본다. 검사 환경에는 `mcp` 가 `pyjwt[crypto]` 로 함께 설치한다.
THIRD_PARTY_PACKAGES = frozenset({"yaml", "mcp", "httpx2", "starlette", "PIL", "cryptography"})


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
        positional=1, returns="tuple[Optional[TokenPrincipal], Optional[str]]"),
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
    ("hermes_cli.tools_config", "_get_platform_tools"): Call(positional=2, returns="set[str]"),
    ("hermes_cli.tools_config", "_get_plugin_toolset_keys"): Call(),
    ("hermes_cli.tools_config", "PLATFORMS"): Value(),
    ("hermes_cli.web_server_profiles", "_config_profile_scope"): Call(positional=1),
    ("toolsets", "TOOLSETS"): Value(),
}

# fos-ctx 원본 도구가 기대는 native 이미지 반환 경로다. 함수 본문을 분리 실행하는 검사가 판 변경을 거른다.
NATIVE_ATTACHMENT_REGISTRATION = ("hermes_cli/plugins_context.py", "register_tool",
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
# 셋째 칸이 있으면 그 상수의 lambda 가 부르는 같은 파일의 함수 이름이다.
NATIVE_TOOL_EVENT_FIELDS = ("gateway/platforms/api_server_runs.py", "_FIXED_EVENT_FIELDS", ("_call_id",))
# 실패 판정이 JSON 객체의 모양(`status`, `results`)까지 보는 함수와 그 상수다. 현재 기준 버전이 쓰는 함수다.
# 이 판정이 first-frame envelope 를 실패로 잘못 세지 않는지 실제 함수로 확인한다.
NATIVE_FAILURE_SHAPE = ("agent/tool_result_classification.py", "shape_failure_error", "_FAILED_STATUSES")

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
# v0.23.2 공식 commit의 위치·키워드 전용 인자, 기본값·annotation과 함수 AST를 선언한다.
# AST hash는 행 번호·공백·주석을 제외하고 함수 전체(시그니처·decorator·본문)를 제한한다.
# 큰 함수도 선언한 함수만 검사한다. 상류 변경 때 이 계약과 private owner를 함께 검토한다.
# profile 문맥은 `hermes_scope` 의 scope 상태 하나가 갖고, 묶는 진입점은 `bind_profile_scope` 다.
# 옛 setter(`set_secret_scope`, `set_hermes_home_override`)는 scope 가 묶여 있으면 scope 를 옮기거나 거절하므로 기대하지 않는다.
BODYLESS_MANAGED_FUNCTIONS = {
    'agent/agent_init.py': {
        'init_agent': (
            ('agent, base_url: str | None=None, api_key: str | None=None, provider: str | None=None, api_mode: str'
             ' | None=None, acp_command: str | None=None, acp_args: list[str] | None=None, command: str | '
             "None=None, args: list[str] | None=None, model: str='', max_iterations: int=sys.maxsize, "
             'enabled_toolsets: list[str] | None=None, disabled_toolsets: list[str] | None=None, '
             'save_trajectories: bool=False, verbose_logging: bool=False, quiet_mode: bool=False, '
             "tool_progress_mode: str='all', ephemeral_system_prompt: str | None=None, log_prefix_chars: int=100, "
             "log_prefix: str='', providers_allowed: list[str] | None=None, providers_ignored: list[str] | "
             'None=None, providers_order: list[str] | None=None, provider_sort: str | None=None, '
             'provider_require_parameters: bool=False, provider_data_collection: str | None=None, '
             'openrouter_min_coding_score: Optional[float]=None, session_id: str | None=None, '
             'tool_progress_callback: Callable[..., Any] | None=None, tool_start_callback: Callable[..., Any] | '
             'None=None, tool_complete_callback: Callable[..., Any] | None=None, thinking_callback: Callable[..., '
             'Any] | None=None, reasoning_callback: Callable[..., Any] | None=None, clarify_callback: '
             'Callable[..., Any] | None=None, read_terminal_callback: Callable[..., Any] | None=None, '
             'read_preview_callback: Callable[..., Any] | None=None, drive_preview_callback: Callable[..., Any] | '
             'None=None, read_window_below_callback: Callable[..., Any] | None=None, connection_callback: '
             'Callable[..., Any] | None=None, tour_callback: Callable[..., Any] | None=None, theme_callback: '
             'Callable[..., Any] | None=None, setup_beat_callback: Callable[..., Any] | None=None, step_callback: '
             'Callable[..., Any] | None=None, stream_delta_callback: Callable[..., Any] | None=None, '
             'interim_assistant_callback: Callable[..., Any] | None=None, tool_gen_callback: Callable[..., Any] | '
             'None=None, status_callback: Callable[..., Any] | None=None, notice_callback: Callable[..., Any] | '
             'None=None, notice_clear_callback: Callable[..., Any] | None=None, event_callback: '
             'Optional[Callable[[str, dict], None]]=None, reaction_callback: Optional[Callable[[str], None]]=None,'
             ' max_tokens: int | None=None, reasoning_config: dict[str, Any] | None=None, service_tier: str | '
             'None=None, request_overrides: dict[str, Any] | None=None, prefill_messages: list[dict[str, Any]] | '
             'None=None, platform: str | None=None, user_id: str | None=None, user_id_alt: str | None=None, '
             'user_name: str | None=None, chat_id: str | None=None, chat_name: str | None=None, chat_type: str | '
             'None=None, thread_id: str | None=None, gateway_session_key: str | None=None, skip_context_files: '
             'bool=False, load_soul_identity: bool=False, skip_memory: bool=False, skip_background_review: '
             'bool=False, session_db=None, parent_session_id: str | None=None, iteration_budget: '
             'IterationBudget=None, run_budget_seconds: Optional[float]=None, fallback_model: dict[str, Any] | '
             'None=None, credential_pool=None, checkpoints_enabled: bool=False, checkpoint_max_snapshots: int=20, '
             'checkpoint_max_total_size_mb: int=500, checkpoint_max_file_size_mb: int=10, pass_session_id: '
             'bool=False, requested_provider: str | None=None, capabilities: Optional[dict[str, bool]]=None, cwd: '
             'Optional[str]=None, side_agent: bool=False, memory_manager=None, memory_agent_context: str | '
             'None=None, tool_result_metadata_callback: Optional[Callable[..., dict]]=None'),
            None,
            '63d5c3efe5481128c5a9b7e060324c0a5b1c2a914f081761afdf732f2736755c'),
    },
    'run_agent.py': {
        'AIAgent.__init__': (
            ('self, base_url: str | None=None, api_key: str | None=None, provider: str | None=None, api_mode: str '
             '| None=None, acp_command: str | None=None, acp_args: list[str] | None=None, command: str | '
             "None=None, args: list[str] | None=None, model: str='', max_iterations: int=sys.maxsize, tool_delay: "
             'float | None=None, enabled_toolsets: list[str] | None=None, disabled_toolsets: list[str] | '
             'None=None, save_trajectories: bool=False, verbose_logging: bool=False, quiet_mode: bool=False, '
             "tool_progress_mode: str='all', ephemeral_system_prompt: str | None=None, log_prefix_chars: int=100, "
             "log_prefix: str='', providers_allowed: list[str] | None=None, providers_ignored: list[str] | "
             'None=None, providers_order: list[str] | None=None, provider_sort: str | None=None, '
             'provider_require_parameters: bool=False, provider_data_collection: str | None=None, '
             'openrouter_min_coding_score: Optional[float]=None, session_id: str | None=None, '
             'tool_progress_callback: Callable[..., Any] | None=None, tool_start_callback: Callable[..., Any] | '
             'None=None, tool_complete_callback: Callable[..., Any] | None=None, thinking_callback: Callable[..., '
             'Any] | None=None, reasoning_callback: Callable[..., Any] | None=None, clarify_callback: '
             'Callable[..., Any] | None=None, read_terminal_callback: Callable[..., Any] | None=None, '
             'read_preview_callback: Callable[..., Any] | None=None, drive_preview_callback: Callable[..., Any] | '
             'None=None, read_window_below_callback: Callable[..., Any] | None=None, connection_callback: '
             'Callable[..., Any] | None=None, tour_callback: Callable[..., Any] | None=None, theme_callback: '
             'Callable[..., Any] | None=None, setup_beat_callback: Callable[..., Any] | None=None, step_callback: '
             'Callable[..., Any] | None=None, stream_delta_callback: Callable[..., Any] | None=None, '
             'interim_assistant_callback: Callable[..., Any] | None=None, tool_gen_callback: Callable[..., Any] | '
             'None=None, status_callback: Callable[..., Any] | None=None, notice_callback: Callable[..., Any] | '
             'None=None, notice_clear_callback: Callable[..., Any] | None=None, event_callback: '
             'Optional[Callable[[str, dict], None]]=None, reaction_callback: Optional[Callable[[str], None]]=None,'
             ' max_tokens: int | None=None, reasoning_config: dict[str, Any] | None=None, service_tier: str | '
             'None=None, request_overrides: dict[str, Any] | None=None, prefill_messages: list[dict[str, Any]] | '
             'None=None, platform: str | None=None, user_id: str | None=None, user_id_alt: str | None=None, '
             'user_name: str | None=None, chat_id: str | None=None, chat_name: str | None=None, chat_type: str | '
             'None=None, thread_id: str | None=None, gateway_session_key: str | None=None, skip_context_files: '
             'bool=False, load_soul_identity: bool=False, skip_memory: bool=False, skip_background_review: '
             'bool=False, session_db=None, parent_session_id: str | None=None, iteration_budget: '
             'IterationBudget=None, run_budget_seconds: Optional[float]=None, fallback_model: dict[str, Any] | '
             'None=None, credential_pool=None, checkpoints_enabled: bool=False, checkpoint_max_snapshots: int=20, '
             'checkpoint_max_total_size_mb: int=500, checkpoint_max_file_size_mb: int=10, pass_session_id: '
             'bool=False, requested_provider: str | None=None, capabilities: dict[str, bool] | None=None, cwd: str'
             ' | None=None, side_agent: bool=False, memory_manager=None, memory_agent_context: str | None=None, '
             'tool_result_metadata_callback: Optional[Callable[..., dict]]=None'),
            None,
            'd173d4ca40b265e5139e5e44651fdbb57848bc6bdd06f2a9fceec32a66754334'),
        'AIAgent.close': (
            'self',
            'None',
            'ea964a0addfed3fef204bfb587b50ae45627500249707d8cf2abd71729e22015'),
    },
    'tools/delegate_tool.py': {
        '_build_child_agent': (
            ('task_index: int, goal: str, context: Optional[str], toolsets: Optional[list[str]], model: '
             'Optional[str], max_iterations: int, task_count: int, parent_agent, override_provider: '
             'Optional[str]=None, override_base_url: Optional[str]=None, override_api_key: Optional[str]=None, '
             'override_api_mode: Optional[str]=None, override_request_overrides: Optional[dict[str, Any]]=None, '
             'override_acp_command: Optional[str]=None, override_acp_args: Optional[list[str]]=None, routing_cfg: '
             "Optional[dict[str, Any]]=None, role: str='leaf', override_reasoning_effort: Any=None, profile_home: "
             'Optional[Path]=None, override_service_tier: Optional[str]=None, subagent_id: Optional[str]=None, '
             'session_id: Optional[str]=None, expect_endpoint: Optional[tuple]=None'),
            None,
            '89c56e59b7bf7eda630709bfb289dca84bc4d964c10c3a877f6816637b5afaa8'),
    },
    'hermes_scope.py': {
        'current_state': (
            '',
            'ScopeState',
            'b256cb0623c09e3289db1e5edb396eef61ec7137f086a6e248f06ad7cad297ae'),
        'current_scope': (
            '',
            'Scope | None',
            'cc68b08a70295d8e5ba9627bc114096491f870f7270291839905a1cf18c6165a'),
        '_fold': (
            'node: _Node | None',
            '_Node | None',
            'f0bc4fd4c213be83e43c002f7a23213341dcbe56c96c23cd263b865c0ab08033'),
        'push_state': (
            '**fields: Any',
            'StateToken',
            '9096fac161e23bd1c0577130bd0612b7168925257826aa3fac9de4f499bd7495'),
        'pop_state': (
            'token: StateToken',
            'None',
            '7de1b4a15f0380a648a29338f4fd10b94c278e09b5819d6f2a4ae66745956f9a'),
    },
    'agent/runtime_scope.py': {
        'scope_for_profile': (
            'home: str | Path',
            'Scope',
            '391ff9ab0c9f95d84f3ef60e3a7495fb3b20207565db696b9c06d24a32fd1d8e'),
        'bind_state': (
            ('scope: Scope, *, secrets: Optional[Mapping[str, str]]=None, hydrate_secrets: bool=True, stamp_home: '
             'bool=True, env_overlay: Optional[dict[str, str]]=None, home_override: bool=True, terminal: '
             'bool=True, terminal_policy: Any=None, defer_terminal: bool=False'),
            'StateToken',
            '3aebbb6c05e27f96604ecb8290ebc80052a3c4c1fcb7633c7ddbb2da15e8cfae'),
        '_bind_whole_profile': (
            ('scope: Scope, *, secrets: Optional[Mapping[str, str]]=None, hydrate_secrets: bool=True, env_overlay:'
             ' Optional[dict[str, str]]=None, defer_terminal: bool=False'),
            'StateToken',
            '70f8e072c193df6d9ec3e4afe4e50f9884ac6bf73393838934a98a56c9d95c24'),
        'bind_profile_scope': (
            'home: str | Path, *, hydrate_secrets: bool=True',
            'Iterator[Scope]',
            'aa43cac83b10fdacbb403e25c1566b818e0e88400b7561f903c7e31afcfb7af8'),
        # 위임의 `profile_home` 경로가 옛 setter 로 scope 를 옮길 때 지나는 함수다.
        'legacy_switch': (
            ('home: str | Path | None, *, secrets: Optional[Mapping[str, str]]=None, env_overlay: '
             'Optional[dict[str, str]]=None'),
            'StateToken | None',
            'a986ba0425fab53094dbcb56bb2e5631e14d719afb92fa1014e85f65056a829d'),
    },
    # `_build_child_agent` 가 자식을 다른 profile 의 scope 안에서 만드는 진입점이다.
    'tools/delegate_tool_routes.py': {
        'delegated_profile_scope': (
            'home: Any',
            'Iterator[None]',
            'fa4ce2a0318de46a08a3eb5ad3296d01f0feb33643e07cf7e7c61612c7a779d5'),
    },
    'tui_gateway/launch_profile_policy.py': {
        'owner_secret_scope': (
            'home: str | Path',
            'dict[str, str]',
            '0b8b7a370447854199f06d58cd1d561d62abb8b61357f7955add4f1c15adc658'),
        'launch_secret_scope': (
            'launch_home: str | Path',
            'dict[str, str]',
            'bd7aa19628e0c2119c2a64d0231021e4ad3c24490e8e4e85e3b90e52c3f1f1b0'),
    },
    'agent/secret_scope.py': {
        'load_env_file': (
            'env_path: Path',
            'dict[str, str]',
            'e7cf0e213d15921fb3a9444d1d3fed8cd3b5c3e256a1aa47dd3fcc9e558f2019'),
        'build_profile_secret_scope': (
            'hermes_home: Path',
            'dict[str, str]',
            '359085233254ee21d4b9854713254b857aec8cd95913b6bba6ff95496042dcd7'),
        '_is_process_home': (
            'hermes_home: Path',
            'bool',
            '5835fec20843d529a8c83c8c685f50d6c46bb2bbd0c16033b559b37442b6fccd'),
    },
    'cron/scheduler.py': {
        'run_one_job': (
            ('job: dict, *, adapters=None, loop=None, verbose: bool=False, extra_prompt: Optional[str]=None, '
             'cancel_event: Optional[_CancelEventLike]=None'),
            'bool',
            'c5be4bef1178f3434502499fd1df641b3a4ea4f0a5d5621eb652b875bb194146'),
        '_launch_external_cron_worker': (
            'job: dict',
            'bool',
            'f7595587b1bf070dfddd8b3abe00fc64cc80a747bfe5631a77b411f3e38a7c35'),
        '_run_external_worker_payload': (
            'payload_path: Path, ack_path: Path',
            'bool',
            '828ffebd9fef767b23221af8b873b732179b84f3bca04bd8ac9eb40a0ff07461'),
    },
    'cron/worker_bootstrap.py': {
        'finish_worker_boot': (
            '',
            'None',
            '667c99116f5f113004c1e3bb3323dae1f290dba153e8b27e9704d00d1a53b8bd'),
    },
}

# 문맥 저장소(scope 상태 하나와 그 값 타입)와 cron의 module-global sys 조회, child 진입점도 함수 밖 계약이다.
BODYLESS_MANAGED_MODULE_NODES = {
    'hermes_scope.py': {
        'from contextvars': '6588e8300f9de11fc19a7338e73f5cba5e5264abe31bdc98aaca6ec078376a63',
        '_STATE': '01aef359d7e1f1315dc2ec6c8c68f506550ffaea937234e9f6f697e76baa6613',
        '_EMPTY': '1b7be11cb89aa46dd1ee35bd87802cb4591a435c55b2afde01cbd38e47be699e',
        'Scope': '821d8a38747c0d8ec705299dbd698d5acfa77a4a2c9d62b6b4a81632d0b5cfce',
        'BoundSecrets': 'ea3d4bec133621ab0bb3d0ca253b2c4d27ce8c8be7d9e83bea10c43e112e6323',
        'ScopeState': '9f0750d901d033e66001171a543703ae2a7c238fbb58c3121431e535dd9688f5',
        '_Change': '2272ebbac221cc288d433bbcdc04a650982dce76a4be9cbf53a4ca0f0a68378a',
        '_Node': 'fe8a357f61c7c87868091965ea02047a9c1f9ea543352ad6fc9bf4cf923e2221',
        'StateToken': 'cbe9cbb74db33c731685206249fa034e3297c462b60131272917b608686f4d31',
    },
    'cron/scheduler.py': {
        'import sys': 'f7252e3c1dbe1d5edf1f34c020a58a05b55f706b2c1d9479eae91f9a9d4be275',
        '__main__': 'c50530c9fa1ed94f5f61a73de16ef9d1e72aff3114e48536b77d0b10696293bb',
    },
}
# 문맥 저장소를 가진 module 과 그 module 에서 한 번만 bind 해야 하는 import 이름이다.
BODYLESS_CONTEXT_MODULES = {"hermes_scope.py": ("ContextVar",)}

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

# fos-ctx 가 하위 에이전트 등록과 끝 소식에 기대는 lifecycle hook 이다(fos-assistant ADR-037, ADR-20261117 / native-delegation-wake).
# hook 이름마다 부르는 파일과, 그 호출이 넘겨야 하는 키워드 인자 가운데 fos-ctx 가 읽는 것이다.
# 이름이나 인자가 바뀌면 등록이 빠져 하위 에이전트의 호출이 거절되거나, 끝 소식이 빠져 결과로 답이 열리지 않는다.
SUBAGENT_HOOKS = {
    "subagent_start": ("tools/delegate_tool.py",
                       ("parent_session_id", "parent_subagent_id", "child_session_id", "child_subagent_id",
                        "child_goal")),
    "subagent_stop": ("tools/delegate_tool_results.py",
                      ("parent_session_id", "child_session_id", "child_status", "child_summary")),
}
# hook 이름 목록과, 제한 시간 worker 로 옮기지 않고 부르는 스레드에서 도는 hook 의 목록이다.
# `subagent_stop` 이 worker 로 옮겨지면 끝 소식과 부모 session 의 결과 줄 순서가 바뀔 수 있다. 설계의 「깨우는 때」 를 다시 본다.
HOOK_NAMES = ("hermes_cli/plugins.py", "VALID_HOOKS")
CALLER_THREAD_HOOKS = ("hermes_cli/plugins_dispatch.py", "_HOOK_CALLER_THREAD_HOOKS", frozenset({"subagent_stop"}))

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
    ("GET", "/api/profiles/{}/model-defaults"),
    ("GET", "/api/profiles/{}/sessions/{}/provider"),
})

# 바인딩 설치가 재시작 없이 반영되는 데 기대는 지점이다(ADR-20261007 connector-live-reload).
# 공유 gateway 의 housekeeping 이 profile 마다 `mcp_servers` 와 살아 있는 연결을 맞추고,
# 스킬 색인 캐시 키에 `skills.disabled` 가 들어 있어 plugin 이 바꾼 색인 표식이 그 profile 의 색인을 새로 만들게 한다.
# 이 지점이 바뀌면 붙인 플러그인이 재시작 전까지 보이지 않는데 Control Plane 은 반영됐다고 판정한다.
LIVE_RELOAD = {
    # housekeeping 작업 목록에 MCP 설정 맞추기 작업이 있다. 그 이름 문자열이 있는 파일이다.
    "reconcile_chore": ("gateway/run.py", "MCP config reconcile"),
    # 맞추기 작업을 만드는 함수와, 그 안에서 부르는 맞추기 함수다.
    "reconciler": ("gateway/run_profile_reconcile.py", "_mcp_config_reconciler", "reconcile_mcp_servers_with_config"),
    # 스킬 색인을 만드는 함수, 꺼진 스킬 이름을 읽는 함수, 그 결과를 담는 이름, 캐시 키를 담는 이름이다.
    "skill_index_key": ("agent/prompt_builder.py", "_build_skills_system_prompt_inner", "get_disabled_skill_names",
                        "disabled", "cache_key"),
}

# docker profile 의 `approvals.unattended_mode: approve` 를 읽는 지점과 호출 경로다.
# 셸 위험 명령과 승인 파일 쓰기, computer_use 는 APPROVAL_GUARD 를 써 fos-ctx 가 먼저 막는다.
# 실패하면 ADR-20261008 execute-code-unattended 를 다시 본다.
UNATTENDED_APPROVAL = {
    # gateway 가 프로세스 환경에 켜는 ask 표식이다
    "exec_ask": ("gateway/run_bootstrap.py", "_start_gateway_runtime", "HERMES_EXEC_ASK"),
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


# 사건 실행의 도구 판정(fos-assistant ADR-20261105 / event-watch)이 기대는 Hermes 의 도구 목록이다.
# `toolsets.py` 의 `_HERMES_CORE_TOOLS` 와 `TOOLSETS` 의 도구 이름을 모두 모은 것이다. Hermes 를 올려 도구가 생기거나
# 빠지면 `test_tool_guard_contract.py` 가 실패한다. 새 도구는 fos-ctx 의 안전 목록(`SAFE_TOOLS`)에 넣지 않는 한
# 사건 실행에서 막히므로, 넣을지만 정하고 이 목록을 고친다.
HERMES_BUILTIN_TOOLS = frozenset({
    "annotate_preview", "browser_cdp", "browser_dialog", "browser_exec", "browser_vault",
    "clarify", "close_terminal", "computer_use", "cronjob_manage", "delegate_task",
    "desktop_preview", "desktop_project", "discord", "discord_admin", "drive_preview",
    "execute_code", "feishu_doc_read", "feishu_drive_add_comment", "feishu_drive_list_comment_replies", "feishu_drive_list_comments",
    "feishu_drive_reply_comment", "focus_pane", "gui_tour", "image_generate", "kanban_attach",
    "kanban_attach_url", "kanban_attachments", "kanban_block", "kanban_comment", "kanban_complete",
    "kanban_create", "kanban_heartbeat", "kanban_link", "kanban_list", "kanban_request_changes",
    "kanban_request_review", "kanban_show", "kanban_unblock", "manage_catalog", "manage_connections",
    "memory", "patch", "process_manage", "react_to_message", "read_file",
    "read_terminal", "read_window_below", "search_files", "session_search", "set_theme",
    "setup_beat", "show_tip", "skill_manage", "skill_view", "skills_list",
    "terminal", "text_to_speech", "todo_list", "video_analyze", "video_generate",
    "vision_analyze", "web_extract", "web_search", "write_file", "x_search",
    "xai_video_edit", "xai_video_extend",
})
# 안전 목록의 내장 도구가 든 기본 toolset 이다. 읽기 경계 toolset 밖으로 옮겨지면 안전한지 다시 본다.
TOOL_GUARD_SAFE_TOOLSETS = {
    "web_search": "web", "web_extract": "web", "vision_analyze": "vision",
    "skills_list": "skills", "skill_view": "skills", "todo_list": "todo",
}
# 도구 찾기 중계 이름이다. `tool_call` 은 hook 에 안쪽 이름이 가므로 안전 목록에 넣지 않는다.
TOOL_GUARD_BRIDGE = ("tools/tool_search_catalog.py", "BRIDGE_TOOL_NAMES", frozenset({"tool_search", "tool_describe", "tool_call"}))
# 코드 실행 안의 도구 호출이 session 을 달고 `handle_function_call` 을 부르는 자리다.
TOOL_GUARD_CODE_CALLS = ("tools/code_execution_rpc.py", "tools/code_kernel.py")


# fos-ctx 가 Hermes 승인 관문(`_run_approval_gate`)이 여는 동작을 hook 에서 먼저 막는 데 기대는 지점이다(스펙 hermes-upgrade REQ5).
# v0.23 은 `/v1/runs` 실행에 승인 알림 경로가 없으면 이 관문을 `approvals.unattended_mode` 로 바로 정하고,
# docker profile 은 그 값이 `approve` 라 사람 없이 열린다. 그래서 fos-ctx 가 관문을 부르는 동작마다 막는다.
APPROVAL_GUARD = {
    # 셸 위험 명령 분류. fos-ctx 가 부르는 함수와, 같은 함수로 관문 대상을 고르는 Hermes 판정 함수다.
    "dangerous": ("tools/approval_detection.py", "detect_dangerous_command", Call(positional=1)),
    "dangerous_user": ("tools/approval.py", "detect_dangerous_command"),
    # 관문을 부르는 함수와 그 동작을 fos-ctx 의 어느 막기가 맡는지다. 함수가 늘면 fos-ctx 가 그 동작을 막는지 보고 더한다.
    # `request_tool_approval` 은 plugin 의 `approve` 답에서만 불린다. fos-ctx 는 `approve` 를 돌려주지 않는다.
    # 운영의 `terminal` 은 이 관문이 아니라 `check_all_command_guards` 가 사람 없는 실행을 스스로 정한다. 그 경로는
    # `UNATTENDED_APPROVAL["callers"]` 가 고정한다. `check_dangerous_command` 를 부르는 운영 코드는 없다(평가와 테스트만).
    "gate": ("tools/approval.py", "_run_approval_gate"),
    "gate_callers": {
        "check_dangerous_command": "terminal (운영 경로 밖)",
        "request_tool_approval": "plugin approve",
        "_check_approval_required_write": "file write",
        "_request_approval": "computer_use",
    },
}
# 승인이 필요한 파일 쓰기 판정이다. 함수를 읽지 못하거나 판정하지 못하면 fos-ctx 가 막는다.
APPROVAL_WRITE_POINTS = {
    "key": ("agent/file_safety.py", "write_approval_key", Call(positional=1)),
    "v4a": ("tools/file_tools.py", "_collect_v4a_header_paths", Call(positional=1)),
    "resolve": ("tools/file_tools_write_guards.py", "_resolved_or_raw", Call(positional=2)),
}
# 플러그인 의존성 경고가 클래스 메서드 밖에서 부르는 모듈 함수다.
PLUGIN_DEPENDENCY_HELPERS = ("missing_python_dependencies", "_declared_requirements")


# 만든 그림 올리기가 기대는 지점이다(fos-assistant ADR-20261119 / generated-image). plugin 은 이 이름들을 import 하지 않고 동작에만 기댄다.
# `test_generated_image_contract.py` 가 Hermes 소스에서 본다.
GENERATED_IMAGE_TOOL = "image_generate"
# `$HERMES_HOME` 아래에서 그림을 쓰는 폴더다. plugin 은 이 폴더 아래의 파일만 읽고 지운다.
GENERATED_IMAGE_CACHE_PARTS = ("cache", "generated", "images")
# provider 의 성공 응답을 만드는 함수와 plugin 이 읽는 키다.
GENERATED_IMAGE_RESULT = ("agent/image_gen_provider.py", "success_response", frozenset({"success", "image"}))
# 캐시 폴더를 정하는 파일, 그 안의 상수 이름, provider 쪽에서 `images` 를 붙이는 글이다.
GENERATED_IMAGE_CACHE_SOURCE = ("agent/provider_media.py", "GENERATED_SUBDIR", '{provider_media.GENERATED_SUBDIR}/images')
# `pre_tool_call` 의 `modify` 를 원래 인자 뒤에 병합하는 자리다.
PRE_TOOL_CALL_MODIFY = ("hermes_cli/plugins.py", 'if action == "modify":', "**partial}")
# `transform_tool_result` 에 도구 인자를 넘기는 호출이다.
TRANSFORM_RESULT_ARGS = ("model_tools.py", 'invoke_hook("transform_tool_result", tool_name=function_name, args=function_args,')
