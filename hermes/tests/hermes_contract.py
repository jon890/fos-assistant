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
HERMES_REPOSITORY = "https://github.com/NousResearch/hermes-agent"

# plugin 이 import 하는 최상위 이름 중 Hermes 의 것이다. 이 이름으로 시작하는 import 는 모두 아래 표에 있어야 한다.
HERMES_PACKAGES = frozenset({"hermes_cli", "hermes_constants", "hermes_state", "gateway", "toolsets", "plugins"})


@dataclass(frozen=True)
class Call:
    """plugin 이 그 함수를 부르는 모양이다. Hermes 의 시그니처가 이 호출을 받아야 한다."""

    positional: int = 0
    keywords: tuple = ()
    is_async: bool = False


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
    ("hermes_cli.dashboard_auth.token_auth", "token_auth_middleware"): Call(positional=2, is_async=True),
    ("hermes_cli.dashboard_auth.token_auth", "authenticate_token"): Call(positional=1),
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
    ("gateway.control_socket", "reload_gateway_plugins"): Call(positional=1, keywords=("profile_home",)),
    # 도구와 실행 공간 설정
    ("hermes_cli.tools_config", "_get_platform_tools"): Call(positional=2),
    ("hermes_cli.tools_config", "_get_plugin_toolset_keys"): Call(),
    ("hermes_cli.tools_config", "PLATFORMS"): Value(),
    ("hermes_cli.web_server_profiles", "_config_profile_scope"): Call(positional=1),
    ("toolsets", "TOOLSETS"): Value(),
}

# 대시보드가 요청마다 이 import 를 다시 해야 바꿔 끼운 함수가 쓰인다.
# 모듈 머리에서 한 번만 import 하게 바뀌면 plugin 이 감싼 것이 쓰이지 않고 토큰 경로가 모두 닫힌다.
PER_REQUEST_IMPORT = ("hermes_cli/web_server.py", "hermes_cli.dashboard_auth.token_auth", "token_auth_middleware")

# plugin 이 토큰 요청에 다는 `request.state` 칸이다. 대시보드의 쿠키 검사가 이 칸을 보고 넘어가야 한다.
STATE_FLAG = ("token_authenticated", ("hermes_cli/dashboard_auth/middleware.py", "hermes_cli/web_server.py"))

# 자식 session 의 provider 를 읽는 표와 칸이다(ADR-067). profile 디렉터리 아래 `SESSION_DB_FILE` 에 있다.
SESSION_DB_FILE = "state.db"
SESSION_DB_MODULE = "hermes_state.py"
SESSION_COLUMNS = {
    "sessions": ("id", "source", "model", "billing_provider"),
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

# 바꿔 끼우기를 공개 확장점으로 대체하지 못하게 막는 지점이다(ADR-088).
# 상류가 이 시그니처를 바꾸면 메서드 단위 등록이나 쿠키로 넘기기가 생겼을 수 있다. 실패하면 ADR-088 의 판정을 다시 본다.
REVISIT_SIGNALS = {
    ("hermes_cli.dashboard_auth.token_auth", "register_token_route"): ("path",),
    ("hermes_cli.dashboard_auth.token_auth", "is_token_route"): ("path",),
}
