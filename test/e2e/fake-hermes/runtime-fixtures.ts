

/**
 * profile 의 기본 provider 와 모델이다. 모델 선택지 응답과, provider 와 모델을 빼고 온 실행의 세션 조회가
 * 함께 쓴다.
 */
export const DEFAULT_RUNTIME = { provider: "openai-codex", model: "example-model" };

/**
 * 대시보드가 기계에게 여는 토큰이다.
 *
 * <p>Hermes 쪽 plugin 이 경로 몇 개를 `Authorization: Bearer` 로 여는 것을 흉내 낸다. 실행 경로가
 * 쓰는 profile 별 key 와 다른 값이라, 둘을 바꿔 보내면 여기서 401 이 난다.
 */
export const FAKE_DASHBOARD_TOKEN = "fake-dashboard-token";

/**
 * 그 profile 의 gateway 가 요청마다 검사하는 값이 들어오는 `.env` 칸의 이름이다.
 *
 * <p>대역이 key 를 스스로 만들지 않고 이 칸으로 들어온 값을 그대로 쓴다. 스스로 만들면 Control Plane
 * 이 key 파일에 쓴 값과 어긋나 그 profile 의 실행이 401 을 받는다.
 */
export const API_KEY_ENV_NAME = "API_SERVER_KEY";

export const TOOLSET_CATALOG = [
  { name: "web", label: "Web", description: "웹을 검색한다" },
  { name: "vision", label: "Vision", description: "이미지를 읽는다" },
  { name: "todo", label: "Todo", description: "할 일을 관리한다" },
  { name: "clarify", label: "Clarify", description: "질문을 명확하게 한다" },
  { name: "session_search", label: "Session search", description: "대화를 검색한다" },
  { name: "skills", label: "Skills", description: "스킬을 쓴다" },
  { name: "tts", label: "Text to speech", description: "글을 읽는다" },
  { name: "delegation", label: "Delegation", description: "하위 작업을 맡긴다" },
  { name: "terminal", label: "Terminal", description: "명령을 실행한다" },
  { name: "file", label: "File", description: "파일을 읽고 쓴다" },
  { name: "code_execution", label: "Code execution", description: "코드를 실행한다" },
  { name: "browser", label: "Browser", description: "브라우저를 조작한다" },
  { name: "computer_use", label: "Computer use", description: "컴퓨터를 조작한다" },
  { name: "cronjob", label: "Cronjob", description: "일정을 실행한다" },
  { name: "image_gen", label: "Image generation", description: "이미지를 만든다" },
  { name: "video_gen", label: "Video generation", description: "동영상을 만든다" },
  { name: "homeassistant", label: "Home Assistant", description: "집 기기를 제어한다" },
  { name: "spotify", label: "Spotify", description: "음악을 제어한다" },
  { name: "discord", label: "Discord", description: "Discord를 제어한다" },
  { name: "fos-attachments", label: "Attachment inspection", description: "원본 사진을 다시 읽는다" },
] as const;

export const CONTROL_PLANE_MCP = "fos-assistant";

/** 허용 목록이 없는 API server의 v0.21.3 기본 toolset이다. */
export const DEFAULT_API_SERVER_TOOLSETS = [
  "browser", "code_execution", "cronjob", "delegation", "file", "image_gen", "memory",
  "session_search", "skills", "terminal", "todo", "vision", "web", CONTROL_PLANE_MCP,
];

/**
 * 대시보드 plugin 이 `POST /api/profiles` 안에서 새 profile 에 붙이는 안전한 도구 목록이다.
 *
 * <p>셸과 파일 등급이 없다. 미리 심어 둔 profile 은 이 경로를 거치지 않으므로 그대로 기본 목록으로 답한다.
 */
export const PLUGIN_TEMPLATE_TOOLSETS = ["web", "skills", "todo", CONTROL_PLANE_MCP, "fos-attachments"];

/**
 * 동시 실행 한도를 넘겼을 때 실제 Hermes 가 내는 본문이다.
 *
 * <p>공유 gateway 는 이 한도를 모든 profile 이 나눠 쓴다. 한 사람이 채우면 다른 사람이 이것을 받는다.
 */
export const RATE_LIMITED = {
  error: {
    message: "Too many concurrent runs (max 16)",
    type: "rate_limit_error",
    code: "rate_limit_exceeded",
  },
} as const;

/**
 * 실행 하나가 보고하는 토큰 수다.
 *
 * <p>입력 120 중 80 이 캐시이고 출력이 40 이다. 사용량 시나리오가 이 값으로 환산 금액을 계산한다.
 */
// 실제 v0.21.5 run 응답의 usage 모양이다. input_tokens 는 캐시 읽기와 쓰기를 포함한다.
export const FAKE_USAGE = {
  input_tokens: 120,
  output_tokens: 40,
  total_tokens: 160,
  cache_read_tokens: 80,
  cache_write_tokens: 0,
} as const;

/** 계정이 전부 막혔을 때 Hermes 가 붙이는 고정 접두사다. 실측한 문장이다. */
export const PROVIDER_AUTH_FAILED =
  "\u26a0\ufe0f Provider authentication failed: No Codex credentials stored. Run `hermes auth` to authenticate.";

export const RUN_PATH = /^\/p\/([a-z0-9-]+)\/v1\/runs$/;

export const RUN_STATUS_PATH = /^\/p\/([a-z0-9-]+)\/v1\/runs\/([A-Za-z0-9_-]+)$/;

export const RUN_EVENTS_PATH = /^\/p\/([a-z0-9-]+)\/v1\/runs\/([A-Za-z0-9_-]+)\/events$/;

export const RUN_STOP_PATH = /^\/p\/([a-z0-9-]+)\/v1\/runs\/([A-Za-z0-9_-]+)\/stop$/;

export const MODEL_OPTIONS_PATH = /^\/p\/([a-z0-9-]+)\/api\/model\/options$/;

export const SESSION_PATH = /^\/p\/([a-z0-9-]+)\/api\/sessions\/([A-Za-z0-9_-]+)$/;

/** Control Plane 이 주소를 저장하기 전에 닿는지 확인할 때 부른다. */
export const CAPABILITIES_PATH = /^\/p\/([a-z0-9-]+)\/v1\/capabilities$/;

/** 대시보드의 profile 관리 경로다. 실행 경로와 달리 profile 접두가 붙지 않는다. */
export const PROFILES_PATH = "/api/profiles";

export const PROFILE_PATH = /^\/api\/profiles\/(.+)$/;

/**
 * 성격 경로다. `PROFILE_PATH` 보다 앞에서 검사해야 한다. `PROFILE_PATH` 의 `.+` 가
 * `<이름>/soul` 까지 함께 먹어 이 경로를 DELETE 분기로 잘못 보낸다.
 */
export const SOUL_PATH = /^\/api\/profiles\/([^/]+)\/soul$/;

export const MODEL_DEFAULTS_PATH = /^\/api\/profiles\/([^/]+)\/model-defaults$/;

export const SESSION_PROVIDER_PATH = /^\/api\/profiles\/([^/]+)\/sessions\/([^/]+)\/provider$/;

export const ENV_PATH = "/api/env";

export const TOOLSET_CATALOG_PATH = "/api/tools/toolsets";

export const CONFIG_PATH = "/api/config";

/** 커넥터 plugin 의 경로다. 카탈로그, 도구 호출, 설치 상태, MCP 서버 확인이 있다. */
export const CONNECTOR_CATALOG_PATH = "/api/connectors/catalog";

export const CONNECTOR_CALL_PATH = /^\/api\/connectors\/([a-z0-9-]+)\/call$/;

export const CONNECTOR_EXECUTE_PATH = /^\/api\/connectors\/([a-z0-9-]+)\/execute$/;

export const CONNECTORS_PATH = "/api/connectors";

/** 연결의 칸 값을 연결마다 하나씩 두는 보관 파일 경로다. 이름 규칙은 plugin 과 같다. */
export const CONNECTOR_VAULT_PATH = "/api/connector-vault";

export const CONNECTOR_VAULT_IMPORT_PATH = "/api/connector-vault/import";

export const VAULT_NAME = /^c[1-9][0-9]{0,18}$/;

export const MCP_SERVER_TEST_PATH = /^\/api\/mcp\/servers\/([a-z0-9-]+)\/test$/;

/** 대시보드의 스킬 목록과 전역 켜고 끄기 경로다. */
export const SKILLS_PATH = "/api/skills";

export const SKILL_TOGGLE_PATH = "/api/skills/toggle";

/**
 * `skills.external_dirs` 에 올 수 있는 경로의 꼬리다. `<스킬 루트>/<profile>/<버전>` 이고 버전은 plugin 이
 * 받는 형식이다. 둘째 묶음의 profile 이 본문의 profile 과 같아야 한다.
 */
export const SKILL_VERSION_DIR = /\/([a-z0-9][a-z0-9-]{0,63})\/(v[0-9]{13}-[a-z0-9]{4})$/;

export const ENABLED_TOOLSETS_PATH = /^\/p\/([a-z0-9-]+)\/v1\/toolsets$/;

export const TEST_BLOCK_PROVIDER_PATH = /^\/__test\/block-provider\/([a-z0-9-]+)$/;

export const TEST_CLEAR_BLOCKED_PATH = "/__test/clear-blocked-providers";

export const TEST_HOLD_NEXT_RUN_PATH = "/__test/hold-next-run";

export const TEST_WAIT_HELD_RUN_PATH = "/__test/wait-held-run";

export const TEST_RELEASE_HELD_RUN_PATH = "/__test/release-held-run";

export const TEST_RELEASE_LONG_ACTIVITY_PATH = "/__test/release-long-activity";

/** 다음 `GET /api/profiles/{이름}/soul` 응답을 붙잡아 화면의 뼈대 검사가 서버를 실제로 늦출 수 있게 한다. */
export const TEST_HOLD_NEXT_SOUL_PATH = "/__test/hold-next-soul";

export const TEST_RELEASE_HELD_SOUL_PATH = "/__test/release-held-soul";

export const TEST_BUSY_PATH = "/__test/busy";

export const TEST_CLEAR_BUSY_PATH = "/__test/clear-busy";

/** 준비 상태가 읽는 API 실행 toolset 조회를 Hermes 장애처럼 실패하게 한다. */
export const TEST_READINESS_OUTAGE_PATH = "/__test/readiness-outage";

export const TEST_HOLD_NEXT_CONFIG_PATH = "/__test/hold-next-config";

export const TEST_RELEASE_HELD_CONFIG_PATH = "/__test/release-held-config";

/** 켜면 셸 도구가 든 설정 쓰기를 plugin 처럼 409 `sandbox_unavailable` 로 거절한다. 본문은 `{ unavailable: boolean }` 이다. */
export const TEST_SANDBOX_UNAVAILABLE_PATH = "/__test/sandbox-unavailable";

/** 마지막으로 받은 설정 쓰기의 `sandbox_owner` 를 돌려준다. */
export const TEST_LAST_SANDBOX_OWNER_PATH = "/__test/last-sandbox-owner";

/** plugin 이 셸 도구가 있는 설정 쓰기에서 실행 공간을 띄우는 도구들이다(ADR-086). */
export const SANDBOX_TOOLSETS = ["terminal", "file", "code_execution"];

/** plugin 이 받는 `sandbox_owner` 의 모양이다. */
export const SANDBOX_OWNER_PATTERN = /^[a-z][a-z0-9-]{0,63}$/;

/** 마지막 실행 요청이 실어 온 provider, 모델, effort 를 돌려준다. 브라우저 검사는 대역을 다른 프로세스에서 띄워 이 길로 묻는다. */
/** 입력 글과 그 입력에 줄 대본을 받는 경로다. `DemoScript` 를 본다. */
export const TEST_SCRIPT_PATH = "/__test/script";

/** 다음 살펴보기 실행의 마지막 답 글을 정한다. 본문은 `{ output }` 이다. 다른 프로세스에서 도는 브라우저 검사가 쓴다. */
export const TEST_PROACTIVE_OUTPUT_PATH = "/__test/proactive-output";

export const TEST_LAST_SUBMITTED_RUNTIME_PATH = "/__test/last-submitted-runtime";
