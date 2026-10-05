/**
 * 홈서버 없이 돌리기 위한 Hermes Runs API 대역이다.
 *
 * <p>Control Plane 이 실제로 부르는 것만 구현한다. profile 경로로 실행을 제출하고, 그 실행의 상태와
 * 토큰 수를 돌려준다. profile 마다 bearer key 도 검사한다. 라우팅을 잘못하면 조용히 성공하는 대신
 * 여기서 401 이 나게 하기 위해서다.
 */
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { randomUUID } from "node:crypto";
import { existsSync, lstatSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { signedCallContext, signedPolicyRequest, signedSubagentRegistration } from "./mcp-context.ts";

const RUN_PATH = /^\/p\/([a-z0-9-]+)\/v1\/runs$/;
const RUN_STATUS_PATH = /^\/p\/([a-z0-9-]+)\/v1\/runs\/([A-Za-z0-9_-]+)$/;
const RUN_EVENTS_PATH = /^\/p\/([a-z0-9-]+)\/v1\/runs\/([A-Za-z0-9_-]+)\/events$/;
const RUN_STOP_PATH = /^\/p\/([a-z0-9-]+)\/v1\/runs\/([A-Za-z0-9_-]+)\/stop$/;
const MODEL_OPTIONS_PATH = /^\/p\/([a-z0-9-]+)\/api\/model\/options$/;
/**
 * profile 의 기본 provider 와 모델이다. 모델 선택지 응답과, provider 와 모델을 빼고 온 실행의 세션 조회가
 * 함께 쓴다.
 */
const DEFAULT_RUNTIME = { provider: "openai-codex", model: "example-model" };
const SESSION_PATH = /^\/p\/([a-z0-9-]+)\/api\/sessions\/([A-Za-z0-9_-]+)$/;
/** Control Plane 이 주소를 저장하기 전에 닿는지 확인할 때 부른다. */
const CAPABILITIES_PATH = /^\/p\/([a-z0-9-]+)\/v1\/capabilities$/;
/** 대시보드의 profile 관리 경로다. 실행 경로와 달리 profile 접두가 붙지 않는다. */
const PROFILES_PATH = "/api/profiles";
const PROFILE_PATH = /^\/api\/profiles\/(.+)$/;
/**
 * 성격 경로다. `PROFILE_PATH` 보다 앞에서 검사해야 한다. `PROFILE_PATH` 의 `.+` 가
 * `<이름>/soul` 까지 함께 먹어 이 경로를 DELETE 분기로 잘못 보낸다.
 */
const SOUL_PATH = /^\/api\/profiles\/([^/]+)\/soul$/;
const MODEL_DEFAULTS_PATH = /^\/api\/profiles\/([^/]+)\/model-defaults$/;
const SESSION_PROVIDER_PATH = /^\/api\/profiles\/([^/]+)\/sessions\/([^/]+)\/provider$/;
const ENV_PATH = "/api/env";
const TOOLSET_CATALOG_PATH = "/api/tools/toolsets";
const CONFIG_PATH = "/api/config";
/** 커넥터 plugin 의 경로다. 카탈로그, 도구 호출, 설치 상태, MCP 서버 확인이 있다. */
const CONNECTOR_CATALOG_PATH = "/api/connectors/catalog";
const CONNECTOR_CALL_PATH = /^\/api\/connectors\/([a-z0-9-]+)\/call$/;
const CONNECTOR_EXECUTE_PATH = /^\/api\/connectors\/([a-z0-9-]+)\/execute$/;
const CONNECTORS_PATH = "/api/connectors";
/** 연결의 칸 값을 연결마다 하나씩 두는 보관 파일 경로다. 이름 규칙은 plugin 과 같다. */
const CONNECTOR_VAULT_PATH = "/api/connector-vault";
const CONNECTOR_VAULT_IMPORT_PATH = "/api/connector-vault/import";
const VAULT_NAME = /^c[1-9][0-9]{0,18}$/;
const MCP_SERVER_TEST_PATH = /^\/api\/mcp\/servers\/([a-z0-9-]+)\/test$/;
/** 대시보드의 스킬 목록과 전역 켜고 끄기 경로다. */
const SKILLS_PATH = "/api/skills";
const SKILL_TOGGLE_PATH = "/api/skills/toggle";
/** Control Plane 이 스킬 커맨드를 바꿔 보낸 입력에 든 `skill_view` 호출이다. 이름 규칙은 올린 스킬과 같다. */
const SKILL_VIEW_CALL = /skill_view\(name="([a-z0-9][a-z0-9-]{0,63})"\)/;
/**
 * `skills.external_dirs` 에 올 수 있는 경로의 꼬리다. `<스킬 루트>/<profile>/<버전>` 이고 버전은 plugin 이
 * 받는 형식이다. 둘째 묶음의 profile 이 본문의 profile 과 같아야 한다.
 */
const SKILL_VERSION_DIR = /\/([a-z0-9][a-z0-9-]{0,63})\/(v[0-9]{13}-[a-z0-9]{4})$/;
/**
 * Hermes 가 스스로 가진 스킬이다. 올린 스킬과 이름이 같으면 올린 것이 가려지므로 Control Plane 이 같은
 * 이름을 거절해야 한다. 목록의 `HERMES` 출처도 이것으로 본다.
 */
const BUILTIN_SKILLS = [
  { name: "hermes-help", description: "Hermes 사용법을 안내한다" },
  // Hermes 이름 규칙은 올린 스킬 규칙과 달리 점과 밑줄을 받는다. 켜고 끄기 검사가 이 이름을 쓴다.
  { name: "note_taking.v2", description: "메모를 정리한다" },
] as const;
const ENABLED_TOOLSETS_PATH = /^\/p\/([a-z0-9-]+)\/v1\/toolsets$/;
const TEST_BLOCK_PROVIDER_PATH = /^\/__test\/block-provider\/([a-z0-9-]+)$/;
const TEST_CLEAR_BLOCKED_PATH = "/__test/clear-blocked-providers";
const TEST_HOLD_NEXT_RUN_PATH = "/__test/hold-next-run";
const TEST_WAIT_HELD_RUN_PATH = "/__test/wait-held-run";
const TEST_RELEASE_HELD_RUN_PATH = "/__test/release-held-run";
const TEST_RELEASE_LONG_ACTIVITY_PATH = "/__test/release-long-activity";
/** 다음 `GET /api/profiles/{이름}/soul` 응답을 붙잡아 화면의 뼈대 검사가 서버를 실제로 늦출 수 있게 한다. */
const TEST_HOLD_NEXT_SOUL_PATH = "/__test/hold-next-soul";
const TEST_RELEASE_HELD_SOUL_PATH = "/__test/release-held-soul";
const TEST_BUSY_PATH = "/__test/busy";
const TEST_CLEAR_BUSY_PATH = "/__test/clear-busy";
const TEST_HOLD_NEXT_CONFIG_PATH = "/__test/hold-next-config";
const TEST_RELEASE_HELD_CONFIG_PATH = "/__test/release-held-config";
/** 마지막 실행 요청이 실어 온 provider, 모델, effort 를 돌려준다. 브라우저 검사는 대역을 다른 프로세스에서 띄워 이 길로 묻는다. */
/** 입력 글과 그 입력에 줄 대본을 받는 경로다. `DemoScript` 를 본다. */
const TEST_SCRIPT_PATH = "/__test/script";
/** 다음 살펴보기 실행의 마지막 답 글을 정한다. 본문은 `{ output }` 이다. 다른 프로세스에서 도는 브라우저 검사가 쓴다. */
const TEST_PROACTIVE_OUTPUT_PATH = "/__test/proactive-output";
const TEST_LAST_SUBMITTED_RUNTIME_PATH = "/__test/last-submitted-runtime";

/** 도구 가리기 검사만 쓰는 가짜 값이다. 실제 연결 값이 아니다. */
export const TOOL_DETAIL_SECRETS = [
  "12345678-1234-5678-9012-123456789abc",
  "short-test-secret",
  "ghp_redactionexample",
] as const;
export const TOOL_DETAIL_SAMPLE = JSON.stringify({
  id: TOOL_DETAIL_SECRETS[0],
  token: TOOL_DETAIL_SECRETS[1],
  note: TOOL_DETAIL_SECRETS[2],
  price: 12000,
});

/**
 * 대역이 카탈로그로 내는 시험 커넥터다. 선언 모양은 plugin 이 읽는 `connector.json` 과 같고, 카탈로그 응답에는
 * 거기에 `mcp_server` 가 더해진다. 칸의 이름과 env 이름을 어느 서비스의 것과도 다르게 둔다.
 */
export const DEMO_CONNECTOR = {
  id: "demo-notes",
  schema: 2,
  title: "검사용 메모",
  description: "검사에서만 쓰는 커넥터입니다.",
  fields: [
    {
      key: "token", env: "DEMO_TOKEN", label: "토큰", description: "검사용 토큰입니다.",
      secret: true, required: true, pattern: "^demo_[a-z]+_[0-9]{10}$",
    },
    {
      key: "scope", env: "DEMO_SCOPE", label: "범위", required: false,
      options: { tool: "list_scopes", items: "scopes", value: "id", label: "name", auto_select_single: true },
    },
  ],
  verify: { tool: "list_scopes" },
  mcp_server: "demo",
  // manifest 가 선언한 내장 toolset 이다. 설치가 도구 목록에서 서버 이름 다음에 둔다.
  toolsets: [] as string[],
  attachments: false,
  // 도구마다의 정책이다. 확인 도구이자 선택지 도구인 `list_scopes` 는 읽기 전용이고 승인이 없다.
  // `grant` 는 대시보드 plugin 이 기본값을 채워 내는 값이다. 승인이 `required` 인 도구만 참이다.
  // `outbound` 도 기본값을 채워 낸다. 밖으로 나간다고 선언한 도구만 참이다.
  tools: {
    list_scopes: { risk: "READ", approval: "none", grant: false, outbound: false },
    write_note: { risk: "WRITE", approval: "required", title: "메모 쓰기", grant: true, outbound: false },
    purge_notes: { risk: "DESTRUCTIVE", approval: "always", grant: false, outbound: false },
  },
};
export const DEMO_TOKEN_OK = "demo_ok_0123456789";
export const DEMO_TOKEN_BAD = "demo_bad_0123456789";

/**
 * 대역이 카탈로그로 내는 둘째 시험 커넥터다. 입력 칸이 없어 연결에 값을 받지 않고 확인 도구는 늘 통과한다.
 *
 * <p>한 에이전트에 연결 둘을 붙였을 때 도구마다 제 연결로 판정하는지 보는 데 쓴다. 서버 이름이 첫째 커넥터와 다르다.
 */
export const AGENDA_CONNECTOR = {
  id: "demo-agenda",
  schema: 2,
  title: "검사용 일정",
  description: "검사에서만 쓰는 입력 칸 없는 커넥터입니다.",
  fields: [] as FakeConnectorField[],
  verify: { tool: "list_events" },
  mcp_server: "agenda",
  toolsets: [] as string[],
  attachments: false,
  tools: {
    list_events: { risk: "READ", approval: "none", grant: false, outbound: false },
    add_event: { risk: "WRITE", approval: "required", title: "일정 쓰기", grant: true, outbound: false },
  },
};

/** 대역이 흉내 내는 커넥터 칸이다. 두 시험 커넥터의 선언이 함께 맞는 모양이다. */
type FakeConnectorField = {
  key: string;
  env: string;
  required: boolean;
  secret?: boolean;
  pattern?: string;
};

/** 대역이 흉내 내는 커넥터 선언이다. 칸, 확인 도구, 서버 이름, 도구 이름만 본다. */
type FakeConnector = {
  id: string;
  fields: readonly FakeConnectorField[];
  verify: { tool: string };
  mcp_server: string;
  toolsets: readonly string[];
  tools: Record<string, unknown>;
};

/** 카탈로그에 내는 커넥터들이다. 옛 설치(`isolated`)와 보관 파일 가져오기는 첫째 커넥터만 흉내 낸다. */
const FAKE_CONNECTORS: readonly FakeConnector[] = [DEMO_CONNECTOR, AGENDA_CONNECTOR];

/** MCP 서버가 실제로 내는 도구다. `hidden_tool` 은 manifest 가 선언하지 않은 도구다. */
const SERVER_TOOLS: Record<string, string[]> = {
  [DEMO_CONNECTOR.mcp_server]: ["list_scopes", "write_note", "purge_notes", "hidden_tool"],
  [AGENDA_CONNECTOR.mcp_server]: ["list_events", "add_event"],
};

function fakeConnector(id: string | undefined): FakeConnector | undefined {
  return FAKE_CONNECTORS.find((connector) => connector.id === id);
}

/** 확인 도구의 답이다. 첫째 커넥터는 토큰을 보고, 칸이 없는 둘째 커넥터는 늘 통과한다. */
function verifyAnswer(connector: FakeConnector, values: Record<string, string> | undefined): unknown {
  if (connector.id === AGENDA_CONNECTOR.id) return { ok: true, result: { events: [] } };
  return values?.token === DEMO_TOKEN_OK
    ? { ok: true, result: { scopes: [{ id: "a", name: "A" }] } }
    : { ok: false, error: "credential_rejected" };
}

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
const API_KEY_ENV_NAME = "API_SERVER_KEY";

const TOOLSET_CATALOG = [
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
] as const;
const CONTROL_PLANE_MCP = "fos-assistant";
/** 허용 목록이 없는 API server의 v0.21.3 기본 toolset이다. */
const DEFAULT_API_SERVER_TOOLSETS = [
  "browser", "code_execution", "cronjob", "delegation", "file", "image_gen", "memory",
  "session_search", "skills", "terminal", "todo", "vision", "web", CONTROL_PLANE_MCP,
];
/**
 * 대시보드 plugin 이 `POST /api/profiles` 안에서 새 profile 에 붙이는 안전한 도구 목록이다.
 *
 * <p>셸과 파일 등급이 없다. 미리 심어 둔 profile 은 이 경로를 거치지 않으므로 그대로 기본 목록으로 답한다.
 */
const PLUGIN_TEMPLATE_TOOLSETS = ["web", "skills", "todo", "vision", CONTROL_PLANE_MCP];

/**
 * 동시 실행 한도를 넘겼을 때 실제 Hermes 가 내는 본문이다.
 *
 * <p>공유 gateway 는 이 한도를 모든 profile 이 나눠 쓴다. 한 사람이 채우면 다른 사람이 이것을 받는다.
 */
const RATE_LIMITED = {
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

type Run = {
  run_id: string;
  status: string;
  session_id: string;
  /** 실제 Hermes 와 같이 요청 본문의 값을 그대로 되돌려 준다. 실제로 돈 모델이 아니다. */
  model: string;
  provider: string | null;
  error?: string;
  output: string;
  input: string;
  interruptEvents: boolean;
  usage: typeof FAKE_USAGE;
  /** v0.21.5 실행 조회의 실제로 돈 provider 와 모델이다. 끝난 실행에만 있다. */
  runtime?: { provider: string; model: string; route_source: string };
  /** 살펴보기 실행이면 사건 스트림이 흘릴 도구 사건과 기다릴 자리다. */
  proactive?: { tools: string[]; gate?: Promise<void> };
};

/**
 * 세션 하나가 마지막으로 실제로 쓴 provider 와 모델이다.
 *
 * <p>실제 Hermes 에서 이 값만 넘김이 일어난 뒤의 모델을 담는다. `GET /v1/runs/{id}` 는 담지 않는다.
 */
type Session = { model: string; provider: string | null };

/** 이 글을 입력으로 보내면 세션 조회가 실행에 실어 보낸 것과 다른 모델을 답한다. */
const SESSION_MODEL_PROBE = "세션 모델 검사";

/**
 * 그 실행이 실제로 쓴 모델을 정한다. 세션 행만 이 값을 갖는다.
 *
 * <p>가격을 검사하는 화면 검사들이 입력으로 모델을 고른다. 요청에 실어 보낸 모델과 다르게 답해야 실제로
 * 돈 모델을 읽고 있는지 알 수 있다.
 */
function actualModelFor(input: string, requested: string): string {
  if (input === SESSION_MODEL_PROBE) return "example-provider/example-model-c";
  if (input === "가격 없음 검사") return "unknown-model";
  if (input === "무료 모델 검사") return "gpt-zero";
  return requested;
}

/**
 * 입력 글 하나에 줄 답과 사건 스트림이다. README 에 싣는 화면을 찍는 스크립트가 `POST /__test/script` 로 넣는다.
 *
 * <p>대본이 있는 입력은 다른 분기를 타지 않는다. 답은 `output` 그대로이고, 스트림은 `events` 를 받은 순서대로 보낸 뒤
 * 닫는다. `pause` 가 참이면 `events` 를 보낸 자리에서 `releaseLongActivity` 를 기다려, 도는 중인 화면을 찍을 수 있다.
 * `subagent.start` 사건에 `child_session_id` 가 있으면 그 session 의 사용량 조회에도 답한다.
 */
export type DemoScript = {
  input: string;
  output: string;
  events?: Record<string, unknown>[];
  pause?: boolean;
};

/** 계정이 전부 막혔을 때 Hermes 가 붙이는 고정 접두사다. 실측한 문장이다. */
const PROVIDER_AUTH_FAILED =
  "\u26a0\ufe0f Provider authentication failed: No Codex credentials stored. Run `hermes auth` to authenticate.";

/**
 * Chief 에게만 주는 지시에 들어 있는 말이다.
 *
 * <p>Control Plane 의 `ResearchAndBuildFlow` 가 만드는 지시와 같아야 한다. 어긋나면 흐름 검사가
 * 계약을 지키지 않는 답을 받아 실패한다.
 */
const CHIEF_MARK = "조사할 것과 만들 것을 나눈다";

/**
 * 흐름의 첫 단계가 돌려줄 답을 정한다.
 *
 * <p>Chief 의 지시에는 사용자가 보낸 글이 그대로 들어 있어, 그 글로 어떤 답을 줄지 고른다.
 */
function chiefOutputFor(input: string): string {
  if (input.includes("흐름 계약 위반 검사")) return "JSON 이 아니라 그냥 문장이다";
  if (input.includes("흐름 단독 검사")) return '{"research":"","build":""}';
  return '{"research":"전기차 보조금을 조사한다","build":"비교 표를 만든다"}';
}

/**
 * Control Plane 이 모든 대화 실행에 붙이는 묻는 형식 안내의 첫 줄이다. `AskFormat.GUIDE` 와 같아야 한다.
 *
 * <p>대역은 받은 instructions 를 답에 되돌려 준다. 안내에는 `<ask>` 예시가 있어서 그대로 두면 모든 답에 카드가 그려진다.
 * 그래서 되돌릴 때 그 구역만 뺀다. 안내가 붙었는지는 `lastSubmittedInstructions()` 로 따로 본다.
 */
export const ASK_GUIDE_HEADER = "# 사용자에게 물을 때";

/** 안내의 마지막 줄 앞머리다. 안내 구역이 어디서 끝나는지 이것으로 안다. */
const ASK_GUIDE_LAST_LINE = "- 왜 묻는지는";

function withoutAskGuide(instructions: string): string {
  const start = instructions.indexOf(ASK_GUIDE_HEADER);
  if (start < 0) return instructions;
  const last = instructions.indexOf(ASK_GUIDE_LAST_LINE, start);
  if (last < 0) {
    // 안내의 마지막 줄이 바뀐 것이다. 끝을 짐작해 뒤에 붙은 다시 생성 지시까지 지우지 말고 곧바로 드러낸다.
    throw new Error(`묻는 형식 안내의 끝 줄 "${ASK_GUIDE_LAST_LINE}" 을 찾지 못했다. AskFormat.GUIDE 와 맞춘다`);
  }
  const lineEnd = instructions.indexOf("\n", last);
  const end = lineEnd < 0 ? instructions.length : lineEnd;
  return (instructions.slice(0, start).replace(/\n+$/, "") + instructions.slice(end)).replace(/^\n+/, "");
}

/** 모델 지침은 답에 복사하지 않고, Memory 본문은 기존처럼 되돌려 검증한다. */
function withoutResponseGuide(instructions: string): string {
  const header = "# 답변 형식\n\n";
  if (!instructions.startsWith(header)) return instructions;

  const nextSection = instructions.indexOf("\n\n", header.length);
  return nextSection < 0 ? "" : instructions.slice(nextSection + 2);
}

/** Control Plane 이 모든 실행 입력 맨 앞에 붙이는 결과물 폴더 단락의 첫 줄이다. `ArtifactService` 와 같아야 한다. */
const ARTIFACT_HEADER = "[결과물 폴더]";

/** 결과물 폴더 단락 바로 뒤에 붙는 스킬 관리 단락의 첫 줄이다. `SkillAgentNotice` 와 같아야 한다. */
const SKILL_NOTICE_HEADER = "[스킬 관리]";

/**
 * 입력 맨 앞의 결과물 폴더 단락과 그 뒤의 스킬 관리 단락을 떼고, 결과물 폴더 단락의 둘째 줄인 폴더를 함께 돌려준다.
 *
 * <p>단락은 각각 빈 줄 하나로 끝난다. 맨 앞에 결과물 폴더 단락이 없으면 아무것도 떼지 않는다. Chief 는 요청 본문 안에서 이 단락을
 * 받아 맨 앞이 아니므로 그대로 둔다. 떼지 않으면 입력을 글자 그대로 견주는 분기와 입력을 되돌려 주는 답이 모두
 * 어긋난다.
 */
function splitArtifactPreamble(input: string): { folder?: string; conversationId?: string; rest: string } {
  if (!input.startsWith(`${ARTIFACT_HEADER}\n`)) return { rest: input };
  const end = input.indexOf("\n\n");
  if (end < 0) return { rest: input };
  const lines = input.slice(0, end).split("\n");
  const identifier = lines.find((line) => line.startsWith("대화 식별자: "));
  let rest = input.slice(end + 2);
  if (rest.startsWith(`${SKILL_NOTICE_HEADER}\n`)) {
    const noticeEnd = rest.indexOf("\n\n");
    if (noticeEnd >= 0) rest = rest.slice(noticeEnd + 2);
  }
  return { folder: lines[1], conversationId: identifier?.slice("대화 식별자: ".length), rest };
}

/** 이 글을 보내면 결과물 폴더에 HTML 과 그것이 부르는 사진을 쓰고 답한다. */
export const ARTIFACT_PROBE = "결과물 파일 검사";

/** 이 글을 보내면 폴더 이름이 같은 `초안/index.html` 을 두 상위 폴더에 하나씩 쓰고 답한다. */
export const ARTIFACT_SAME_NAME_PROBE = "같은 이름 결과물 파일 검사";

/** 실제 MCP 호출로 HTML과 CSS를 저장하는지 보는 입력이다. */
export const ARTIFACT_WRITE_PROBE = "MCP 결과물 파일 검사";

/**
 * 이 글 뒤에 공백과 Memory 번호를 붙여 보내면 그 run 안에서 `memory_read` 를 부르고, 도구 결과의 text 를 답으로 돌려준다.
 */
export const MEMORY_READ_PROBE = "MCP Memory 읽기 검사";
/** 이 글과 빈칸 뒤의 JSON 을 `follow_up_propose` 의 인자로 실어 부르고, 도구 결과의 text 를 답으로 돌려준다. */
export const FOLLOW_UP_PROPOSE_PROBE = "MCP 할 일 제안 검사";

/**
 * 이 글로 시작하는 입력을 받으면 그 run 안에서 하위 에이전트 session 을 등록하고, 답으로 자식 session 을 돌려준다.
 *
 * <p>실제 플러그인의 `subagent_start` hook 처럼 부모 run 이 끝나기 전에 등록을 마친다. 자식의 도구 호출은 시나리오가
 * 부모가 끝난 뒤 `readMemoryAsSubagent` 로 부른다.
 */
export const SUBAGENT_MEMORY_PROBE = "MCP 하위 에이전트 검사";

/**
 * 기존 자식 검사와 같은 흐름이되, 자식 session 응답에 부모 바인딩과 다른 provider 와 모델을 싣는 입력이다.
 * 자식 금액이 부모의 가격이 아니라 자식의 provider 와 모델로 계산되는지 본다.
 */
export const SUBAGENT_PROVIDER_PROBE = "자식 provider 확인 검사";

/**
 * 이 글로 시작하는 입력을 받으면 그 뒤의 줄마다 `<등록 이름> <JSON 인자>` 를 읽어, profile 플러그인의 hook 처럼
 * 차례로 Control Plane 에 판정을 묻는다. 답은 줄마다 `<등록 이름>: allow` 나 `<등록 이름>: block <글>` 이다.
 * `allow` 인 호출만 커넥터 서버에 닿은 것으로 치고 `connectorToolCalls` 에 남긴다.
 */
export const CONNECTOR_TOOL_PROBE = "커넥터 도구 검사";

export type ConnectorToolCall = { profile: string; hermesTool: string; argsJson: string; via: "hook" | "execute" };

/**
 * 이 글을 보내면 `terminal` 도구 사건을 많이 보내고, 도구 줄 하나를 시작만 한 채 `releaseLongActivity` 를
 * 기다린다. 첫 번째로 풀리면 그 줄을 끝내고 열 쌍과 시작만 한 줄 하나를 더 보내 다시 기다린다. 두 번째로 풀리면 그 줄을
 * 끝내고 열 쌍을 더 보낸 뒤 스트림을 닫는다. 펼친 작업 과정 목록의 높이와 스크롤, 맨 아래를 따라가는 동작을 보려는 입력이다.
 */
export const LONG_ACTIVITY_PROBE = "긴 작업 과정 검사";

/**
 * Control Plane 이 먼저 살펴보기 입력에 넣는 지침 읽기 호출이다. 입력에 이것이 있으면 살펴보기 실행으로 본다.
 *
 * <p>`ProactiveCheckRun.OPENING` 과 같아야 한다. 어긋나면 살펴보기 실행이 보통 실행으로 답을 받아 결과 블록이 없다.
 */
const PROACTIVE_CHECK_CALL = 'skill_view(name="proactive-check")';

/**
 * 다음 살펴보기 실행 하나에 줄 각본이다. `setProactiveScript` 로 넣고, 살펴보기 실행 하나가 받으면 지운다.
 *
 * <p>각본이 없는 살펴보기는 곧바로 `defaultProactiveOutput` 의 답을 준다.
 */
export type ProactiveScript = {
  /** 흘릴 도구 이름이다. 이름마다 `tool.started` 와 `tool.completed` 를 차례로 보낸다. */
  tools?: string[];
  /** 참이면 시나리오가 `releaseHeldRun` 을 부를 때까지 실행을 `running` 으로 둔다. 그 사이 시나리오가 플러그인 역할로 MCP 를 부른다. */
  hold?: boolean;
  /**
   * 참이면 사건 스트림이 도구 사건을 보내기 전에 `releaseProactiveEvents` 를 기다린다. 시나리오가 대화 SSE 를 연 뒤에 도구 사건이
   * 흐르게 하려는 것이다. 점검 대화가 시작 요청에서 처음 생기면 SSE 를 그 전에 열 수 없다.
   */
  waitBeforeEvents?: boolean;
  /** 마지막 답 글이다. `message.delta` 로도 흘린다. */
  output: string;
};

/** 각본 없는 살펴보기가 내는 발견 하나의 주제 키와 원문 주소다. 브라우저 검사가 두 번째 답의 「이미 알린 것이에요」 를 이것으로 안다. */
export const PROACTIVE_DEFAULT_FINDING = {
  topicKey: "study:e2e-sample",
  title: "검사용 공부 자료",
  sourceUrl: "https://example.com/e2e/study",
} as const;

/** 결과 블록 JSON 을 답 끝에 둔 답 글을 만든다. 블록 앞의 글은 Control Plane 이 버린다. */
export function proactiveOutput(block: Record<string, unknown>): string {
  return `살펴본 결과를 정리했다.\n\n<fos-check-result>\n${JSON.stringify(block, null, 2)}\n</fos-check-result>`;
}

/**
 * 각본 없는 살펴보기의 답이다. 발견 하나가 「새로 알릴 것」 의 조건을 모두 갖춘다.
 *
 * <p>확인 시각은 답을 만드는 지금이다. 고정 시각이면 Control Plane 이 「이번에 다시 확인하지 않았어요」 로 내린다.
 */
function defaultProactiveOutput(): string {
  return proactiveOutput({
    version: 1,
    outcome: "FINDINGS",
    summary: "공부 자료 하나를 찾았어요",
    findings: [
      {
        area: "study",
        topicKey: PROACTIVE_DEFAULT_FINDING.topicKey,
        title: PROACTIVE_DEFAULT_FINDING.title,
        sourceUrl: PROACTIVE_DEFAULT_FINDING.sourceUrl,
        checkedAt: new Date().toISOString(),
        freshness: "CURRENT",
        whyItMatters: "검사에서만 쓰는 합성 발견이에요",
        facts: ["검사용 원문에 적힌 합성 사실"],
        next: { type: "QUESTION", text: "이 자료를 이번 주에 읽어 볼까요" },
      },
    ],
  });
}

/** 각본 없는 살펴보기가 흘리는 도구 사건이다. */
const DEFAULT_PROACTIVE_TOOLS = ["web_search", "web_extract"];

/** HTML 이 부르는 사진이다. 1픽셀짜리 PNG 다. */
const ONE_PIXEL_PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
  "base64",
);

/**
 * 에이전트가 초안을 만든 것처럼 폴더에 쓴다.
 *
 * <p>스크립트 한 줄을 품는다. 화면 검사가 제목이 바뀌지 않은 것으로 스크립트가 돌지 않았음을 본다.
 */
function writeArtifactDraft(folder: string): void {
  const draft = join(folder, "초안");
  mkdirSync(draft, { recursive: true });
  writeFileSync(
    join(draft, "index.html"),
    [
      "<!doctype html>",
      "<html><head><meta charset=\"utf-8\"><title>초안</title></head>",
      "<body><h1>초안</h1><img src=\"photo.png\" alt=\"사진\">",
      "<script>document.title=\"스크립트가 돌았다\"</script>",
      "</body></html>",
    ].join("\n"),
  );
  writeFileSync(join(draft, "photo.png"), ONE_PIXEL_PNG);
}

/** 이름이 같은 두 결과물을 서로 다른 상위 폴더에 쓴다. 답 아래 줄과 패널 머리가 같은 이름을 쓰는지 보려는 입력이다. */
function writeSameNameArtifacts(folder: string): void {
  for (const parent of ["가", "나"]) {
    const draft = join(folder, parent, "초안");
    mkdirSync(draft, { recursive: true });
    writeFileSync(join(draft, "index.html"),
      `<!doctype html><html><head><meta charset="utf-8"><title>초안</title></head><body><h1>${parent}</h1></body></html>`);
  }
}

/** 묻는 카드를 그리는지 보는 검사가 보내는 글이다. 이 글에는 답 끝에 `<ask>` 를 둔 답을 준다. */
export const ASK_CARD_PROBE = "묻는 카드 검사";

/** Control Plane 이 추천 질문을 만들 때 입력 첫 줄에 두는 표지다. */
const STARTER_MARK = "[추천 질문 만들기]";

/** 추천 질문을 만드는 입력에 주는 답이다. 실제 모델처럼 JSON 문자열 배열 하나만 답한다. */
export const FAKE_STARTER_PROMPTS = ["이번 주 일정 정리해 줘", "장보기 목록 만들어 줘", "오늘 날씨 알려 줘", "가계부 요약해 줘"];

function specialOutputFor(input: string): string | null {
  if (input.startsWith(STARTER_MARK)) {
    return JSON.stringify(FAKE_STARTER_PROMPTS);
  }
  if (input === ASK_CARD_PROBE) {
    return [
      "사진을 다 봤어. 두 가지만 알려 줘.",
      "",
      "<ask>",
      '<question header="식당 이름">어느 식당에 다녀왔어?</question>',
      "<option>행복담</option>",
      '<option description="사진의 간판 글자">행복한 담벼락</option>',
      '<question header="먹은 메뉴" multiple="true">무엇을 먹었어?</question>',
      "<option>국밥</option>",
      "<option>수육</option>",
      "</ask>",
    ].join("\n");
  }
  if (input.includes(CHIEF_MARK)) {
    return chiefOutputFor(input);
  }
  if (input === ARTIFACT_PROBE || input === ARTIFACT_SAME_NAME_PROBE) {
    return "초안을 만들었다.";
  }
  if (input === "마크다운 보안 검사") {
    return [
      "| 항목 | 값 |",
      "| --- | --- |",
      "| 표 | 정상 |",
      "",
      "<script>window.__unsafeAgentHtml = true</script>",
    ].join("\n");
  }
  if (input === "구분 줄 없는 표 검사") {
    return "번호 | 구분 | 금액\n1 | 식비 | 100\n2 | 교통 | 200\n3 | 기타 | 300";
  }
  if (input === "긴 답 스트림 검사") {
    return Array.from({ length: 80 }, (_, index) => `${index + 1}번째 긴 답 줄`).join("\n\n");
  }
  if (input === "코드 블록 검사") {
    return [
      "```java",
      "public class Greeting {",
      "  // 인사 횟수",
      "  private static final int COUNT = 3;",
      "  void greet() {",
      "    System.out.println(\"안녕하세요\");",
      "  }",
      "}",
      "```",
    ].join("\n");
  }
  return null;
}

/**
 * 게시된 디렉터리에서 스킬 이름과 설명을 읽는다.
 *
 * <p>실제 Hermes 처럼 `SKILL.md` 앞머리의 `name` 과 `description` 을 쓴다. 없는 디렉터리는 오류 없이
 * 건너뛴다. 게시 전에 디렉터리가 있는지 보는 것은 설정 쓰기 쪽이다.
 */
function readPublishedSkills(dirs: readonly string[]): { name: string; description: string }[] {
  const found: { name: string; description: string }[] = [];
  for (const dir of dirs) {
    if (!existsSync(dir)) continue;
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      const skillMd = join(dir, entry.name, "SKILL.md");
      if (!existsSync(skillMd)) continue;
      const frontmatter = readFileSync(skillMd, "utf-8").match(/^---\r?\n([\s\S]*?)\r?\n---/)?.[1] ?? "";
      found.push({
        name: frontmatter.match(/^name:\s*(.+)$/m)?.[1]?.trim() ?? entry.name,
        description: frontmatter.match(/^description:\s*(.+)$/m)?.[1]?.trim() ?? "",
      });
    }
  }
  return found;
}

function wait(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function shortId(): string {
  return randomUUID().replaceAll("-", "").slice(0, 12);
}

/**
 * 본문을 읽는다.
 *
 * <p>Spring 의 `RestClient` 는 요청 본문을 chunked 로 흘려보낸다. Node 의 요청 스트림은 두 인코딩을
 * 모두 같은 방식으로 내주므로, 여기서는 조각을 모으기만 하면 된다.
 */
async function readBody(request: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks).toString("utf-8");
}

function send(response: ServerResponse, status: number, payload: unknown): void {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    "Content-Type": "application/json",
    "Content-Length": Buffer.byteLength(body),
  });
  response.end(body);
}

function event(response: ServerResponse, payload: unknown): void {
  response.write(`data: ${JSON.stringify(payload)}\n\n`);
}

export type FakeHermes = {
  readonly baseUrl: string;
  lastSubmittedInstructions(): string | undefined;
  /** 마지막 실행 요청의 `input`. Control Plane 이 사용자가 쓴 글 앞에 덧붙인 것까지 담는다 */
  lastSubmittedInput(): string | undefined;
  /** 모델 목록 조회를 받은 횟수다. 들고 있는 동안 다시 묻지 않는 것을 이 수로 본다. */
  modelOptionsCalls(): number;
  /** 마지막 실행 요청이 실어 온 provider, 모델, reasoning effort */
  lastSubmittedRuntime(): { provider?: string; model?: string; reasoningEffort?: string };
  blockProvider(provider: string): void;
  clearBlockedProviders(): void;
  /** 실행 제출을 429 로 거절하게 한다. 공유 gateway 가 한도에 닿은 상태를 흉내 낸다. */
  busy(): void;
  clearBusy(): void;
  /** 실행 제출을 받은 횟수다. 429 뒤에 다시 보내지 않는 것을 이 수로 본다. */
  submitCount(): number;
  /** 대시보드로 만들어져 아직 남아 있는 profile 이름들 */
  profiles(): string[];
  /** 그 profile 의 `.env` 에 들어간 값이다. 없는 profile 이면 비어 있다. */
  profileEnv(name: string): Record<string, string>;
  /** 그 profile 의 `SOUL.md` 본문을 대역이 실제로 받은 그대로 돌려준다. 쓴 적이 없으면 `undefined` 다. */
  soulOf(name: string): string | undefined;
  /** 그 profile 에 마지막으로 게시된 `skills.external_dirs` 다. 게시한 적이 없으면 비어 있다. */
  skillDirsOf(name: string): string[];
  /** 그 profile 에 마지막으로 기록된 API 도구 목록이다. 기록한 적이 없으면 `undefined` 다. */
  apiServerToolsetsOf(name: string): string[] | undefined;
  /**
   * 커넥터 경로로 받은 요청을 받은 순서대로 적은 줄이다. `call <도구>`, `env put <profile> <key>`,
   * `env delete <profile> <key>`, `toolsets <profile>`, `install <profile> <on|off>`, `probe <profile>`,
   * `bind <profile>`, `unbind <profile>`, `vault put`, `vault delete`, `vault import <profile>` 이다.
   * 비밀 값과 보관 파일 이름은 적지 않는다.
   */
  connectorRequests(): readonly string[];
  /**
   * 그 profile 의 정책 hook 이 켜져 있다고 답할지 정한다. `false` 로 두면 설치를 다시 보내도 고쳐지지 않는
   * 상태가 되고, `true` 로 풀 때까지 설치 목록이 `policy_hook: false` 로 답한다.
   */
  setPolicyHook(profile: string, active: boolean): void;
  /** 커넥터 도구 호출의 판정을 물을 Control Plane 주소를 준다. 주지 않으면 `CONNECTOR_TOOL_PROBE` 의 호출은 모두 막힌다. */
  setConnectorPolicy(endpoint: string): void;
  /**
   * 커넥터 서버에 닿은 도구 호출이다. 막힌 호출은 없다. `via` 가 `hook` 이면 판정이 `allow` 여서 지나간 호출이고,
   * `execute` 이면 Control Plane 이 승인한 뒤 대시보드의 실행 경로로 보낸 호출이다. 실행 경로의 `argsJson` 은 받은
   * `args` 를 다시 직렬화한 글이다.
   */
  connectorToolCalls(): readonly ConnectorToolCall[];
  /**
   * 그 profile 의 실행이 커넥터 도구를 직접 부른 것처럼 줄마다 Control Plane 에 판정을 묻고 답 줄을 돌려준다. 줄과 답의 모양은
   * `CONNECTOR_TOOL_PROBE` 와 같다. 살펴보기처럼 입력을 정할 수 없는 실행을 붙잡아 둔 동안 시나리오가 그 실행의 모델 역할을
   * 할 때 쓴다. 사람이 만든 profile 의 `.env` 에는 대역이 아는 MCP 토큰이 없으므로 운영자가 넣었을 토큰을 받는다.
   */
  callConnectorTools(profile: string, sessionId: string, lines: readonly string[], token: string): Promise<string>;
  /** 그 profile 에 바인딩 설치로 붙은 커넥터 id 들이다. 옛 설치는 담지 않는다. */
  boundConnectorsOf(profile: string): string[];
  holdNextRun(): void;
  /**
   * 값이 있으면 추천 질문이 아닌 새 실행을 제출 시각에서 `ms` 가 지날 때까지 `running` 으로 답하고 그 뒤 `completed`
   * 로 답한다. `undefined` 면 끈다. `holdNextRun` 으로 붙잡은 실행은 이 지연 없이 붙잡힌 채로 있다.
   */
  slowRuns(ms: number | undefined): void;
  /**
   * `slowRuns` 가 켜진 동안 제출된 실행이 제출부터 완료나 중지까지 나란히 돈 최댓값이다. 전체와 profile 별로 적는다.
   */
  runConcurrency(): { maxTotal: number; maxByProfile: Record<string, number> };
  resetRunConcurrency(): void;
  waitForHeldRun(): Promise<void>;
  releaseHeldRun(): void;
  /** 지금 붙잡아 둔 실행의 번호와 session 이다. 붙잡은 것이 없으면 `undefined` 다. 플러그인처럼 `_fos_ctx` 를 서명할 때 쓴다. */
  heldRun(): { runId: string; sessionId: string } | undefined;
  /** 그 run 을 잊는다. 이 뒤 조회와 중지는 404 다. gateway 가 다시 떴거나 종료 뒤 1시간이 지난 것과 같다. */
  forgetRun(runId: string): void;
  /**
   * Control Plane 이 그 run 의 사건 스트림을 열 때까지 기다린다. Control Plane 은 run 번호를 실행 줄에 적은 뒤에 연다.
   * 제출을 받은 것만으로는 run 번호가 아직 적히지 않았을 수 있어, 재시작 검사가 이것으로 그 순간을 지난 뒤 내린다.
   */
  waitForRunEvents(runId: string): Promise<void>;
  /**
   * `LONG_ACTIVITY_PROBE` 스트림이 시작만 한 도구 줄을 남기고 기다리는 멈춤 지점 하나를 푼다. 실행 상태는 바꾸지 않는다.
   * 멈춤 지점이 둘이므로 끝까지 흘리려면 두 번 부른다. 기다리는 스트림이 없으면 아무것도 하지 않는다.
   */
  releaseLongActivity(): void;
  /** 다음 성격 읽기 응답을 붙잡는다. `releaseHeldSoul` 을 부를 때까지 요청이 끝나지 않는다. */
  holdNextSoul(): void;
  releaseHeldSoul(): void;
  /** 중지 요청을 받은 실행 번호들이다. */
  stoppedRuns(): readonly string[];
  holdNextConfig(): void;
  waitForHeldConfig(): Promise<void>;
  releaseHeldConfig(): void;
  dropNextAppliedToolset(name: string): void;
  close(): Promise<void>;
  setArtifactWriteMcp(endpoint: string, token: string): void;
  setMemoryReadMcp(endpoint: string, token: string): void;
  /** 하위 에이전트 검사 입력으로 등록한 자식 session 과 그 응답 상태다. 등록이 거절돼도 run 은 실패하지 않고 여기에만 남는다. */
  subagentRegistrations(): readonly { childSessionId: string; rootSessionId: string; status: number }[];
  /** 등록한 자식 session 이 부모의 루트로 서명해 `memory_read` 를 부르고 도구 결과의 text 를 돌려준다. */
  readMemoryAsSubagent(childSessionId: string, memoryId: number): Promise<string>;
  /** 등록하지 않은 자식 session 으로 그 루트에 서명해 `memory_read` 를 부른다. */
  readMemoryAsUnregisteredSubagent(rootSessionId: string, memoryId: number): Promise<string>;
  /** 다음 살펴보기 실행 하나의 각본을 넣는다. 앞에 넣고 아직 쓰지 않은 각본은 바뀐다. */
  setProactiveScript(script: ProactiveScript): void;
  /** `waitBeforeEvents` 각본의 사건 스트림이 도구 사건을 보내게 한다. 스트림이 열리기 전에 불러도 풀린다. */
  releaseProactiveEvents(): void;
  /** 받은 살펴보기 실행의 profile 과 입력 원문을 받은 순서대로 돌려준다. */
  proactiveInputs(): readonly { profile: string; input: string }[];
};

/**
 * fake Hermes 를 띄우고 그 주소를 돌려준다.
 *
 * @param profileKeys profile 이름과 그 profile 의 API server key
 * @param label 이 대역을 다른 대역과 구분하는 이름. 실행의 답에 그대로 실린다. 주소를 옮기는 검사가
 *     답이 어느 대역에서 왔는지 보는 데 쓴다
 * @param skillRoot Control Plane 이 스킬 버전 디렉터리를 쓰는 루트. 주면 그 아래 경로만 게시로 받는다.
 *     Hermes 쪽 루트와 같은 경로여야 대역이 게시된 `SKILL.md` 를 읽을 수 있다
 */
export function startFakeHermes(
  profileKeys: Record<string, string>,
  label?: string,
  initialApiServerToolsets: Record<string, string[]> = {},
  skillRoot?: string,
): Promise<FakeHermes> {
  const who = label === undefined ? "fake hermes" : `fake hermes ${label}`;
  /**
   * profile 이름과 그 profile 의 key 다.
   *
   * <p>미리 고정해 넘긴 것으로 시작하고, 대시보드로 새로 만들어진 profile 은 그 profile 의 `.env` 로
   * 들어온 key 가 여기 더해진다. 받은 표를 그대로 쓰지 않고 복사하는 것은 부르는 쪽의 상수를 대역이
   * 고치지 않게 하기 위해서다.
   */
  const keys: Record<string, string> = { ...profileKeys };
  /** 실제 Hermes 가 모르는 run 에 주는 404 본문이다. 지운 run 과 한 번도 없던 run 이 같다. */
  const runNotFound = (runId: string) => ({
    error: { message: `Run not found: ${runId}`, type: "invalid_request_error", code: "run_not_found" },
  });
  const runs = new Map<string, Run>();
  const sessions = new Map<string, Session>();
  /** 자식 session 의 모델과 provider 다. provider 는 session 응답이 아니라 대시보드의 provider 경로가 준다. 비우면 provider 없이 `example-fast` 를 준다. */
  const childUsages = new Map<string, { profile: string; parent: string; reads: number; delayed: boolean;
    model?: string; provider?: string }>();
  /** 대시보드로 만든 profile 과 그 profile 의 `.env` 다. */
  const profiles = new Map<string, Record<string, string>>();
  const apiServerToolsets = new Map(
    Object.entries(initialApiServerToolsets).map(([profile, toolsets]) => [profile, [...toolsets]]),
  );
  /**
   * profile 이름과 그 profile 의 `SOUL.md` 본문이다.
   *
   * <p>`profiles` 와 따로 둔다. 씨 뿌린 에이전트는 `profiles` 에 없고 `keys` 에만 있어서, `profiles`
   * 로 존재를 판정하면 기존 에이전트가 404 를 받아 `HERMES_UNAVAILABLE` 이 된다.
   */
  const souls = new Map<string, string>();
  /** profile 이름과 그 profile 에 게시된 `skills.external_dirs` 다. */
  const externalDirs = new Map<string, string[]>();
  /** profile 이름과 그 profile 에 설치된 커넥터 id 들이다. */
  const installedConnectors = new Map<string, Set<string>>();
  const connectorRequests: string[] = [];
  /** 설치가 정책 hook 을 지금 판으로 맞춘 profile 이다. 설치한 적이 없는 profile 의 hook 은 꺼져 있다. */
  const policyHookInstalled = new Set<string>();
  /** 설치해도 정책 hook 이 고쳐지지 않게 둔 profile 이다. */
  const policyHookOff = new Set<string>();
  const connectorEnvNames = new Set(FAKE_CONNECTORS.flatMap((connector) => connector.fields.map((field) => field.env)));
  /** 보관 파일 이름과 그 커넥터와 칸 값이다. 값은 메모리에만 두고 어느 응답에도 싣지 않는다. */
  const vaults = new Map<string, { connector: string; values: Record<string, string> }>();
  /**
   * profile 이름과 그 profile 에 바인딩 설치한 커넥터 id 와 보관 파일 이름이다.
   *
   * <p>`installedConnectors` 는 옛 설치(소유 기록의 `isolated`)만 갖는다. 한 profile 에 두 방식이 함께 있지 않다.
   */
  const boundConnectors = new Map<string, Map<string, string>>();
  /** 대시보드로 만들지 않은 profile 의 `.env` 다. 바인딩 설치가 칸 값을 여기 쓴다. */
  const hostEnv = new Map<string, Record<string, string>>();
  /** 그 profile 의 `.env` 다. 대시보드로 만든 profile 은 `profiles` 의 것을 쓴다. */
  const envOf = (profile: string): Record<string, string> => {
    const managed = profiles.get(profile);
    if (managed !== undefined) return managed;
    const host = hostEnv.get(profile) ?? {};
    hostEnv.set(profile, host);
    return host;
  };
  /** 그 profile 에 바인딩 설치한 커넥터의 MCP 서버 이름이다. 도구 목록을 쓰는 요청이 이 이름을 모두 실어야 한다. */
  const boundServers = (profile: string): string[] =>
    [...(boundConnectors.get(profile)?.keys() ?? [])].flatMap((id) => fakeConnector(id)?.mcp_server ?? []);
  /** 그 커넥터의 칸 선언과 맞는 값인가. 모르는 키, 필수 칸 누락, `pattern` 위반, 두 줄 이상인 값은 받지 않는다. */
  const vaultValuesValid = (connector: FakeConnector, values: unknown): values is Record<string, string> => {
    if (typeof values !== "object" || values === null || Array.isArray(values)) return false;
    const given = values as Record<string, unknown>;
    const declared = new Set(connector.fields.map((field) => field.key));
    if (Object.entries(given).some(([key, value]) =>
      !declared.has(key) || typeof value !== "string" || value === "" || value.includes("\n"))) {
      return false;
    }
    return connector.fields.every((field) => {
      const value = given[field.key] as string | undefined;
      if (value === undefined) return !field.required;
      return field.pattern === undefined || new RegExp(field.pattern).test(value);
    });
  };

  /**
   * `PUT /api/connectors` 의 바인딩 설치와 그 떼기다. 받는 조건과 바꾸는 것은 `docs/backend/connector-install.md` 의
   * 「바인딩 설치」 를 따른다.
   *
   * <p>붙이기는 보관 파일의 값을 그 profile 의 `.env` 에 쓰고 서버 이름을 API 도구 목록에 더한다. 있던 이름은 그대로 둔다.
   * 바뀐 것이 있으면 `restart_required` 가 참이다. 떠 있는 profile 에 더한 MCP 서버는 재시작해야 보이기 때문이다. 떼기는 그
   * 이름만 빼고 env 를 지우며 재시작을 요구하지 않는다. 스킬 복사는 흉내 내지 않는다. 시험 커넥터에 스킬이 없다.
   */
  const bindingInstall = (
    response: ServerResponse,
    profile: string,
    plugin: string,
    enabled: boolean,
    bind: { vault?: unknown } | undefined,
  ) => {
    if (!profiles.has(profile) && keys[profile] === undefined) {
      send(response, 401, { reason: "not_marked" });
      return;
    }
    const answer = (changed: boolean, restartRequired: boolean) => send(response, 200, {
      profile, plugin, enabled, changed, restart_required: restartRequired, plugin_updated: false,
    });
    const bound = boundConnectors.get(profile) ?? new Map<string, string>();
    const toolsets = apiServerToolsets.get(profile);
    const connector = fakeConnector(plugin);
    if (!enabled) {
      if (bind !== undefined || connector === undefined) {
        send(response, 400, { error: "invalid connector request" });
        return;
      }
      const env = envOf(profile);
      for (const field of connector.fields) delete env[field.env];
      if (toolsets !== undefined) {
        apiServerToolsets.set(profile, toolsets.filter((name) => name !== connector.mcp_server));
      }
      bound.delete(plugin);
      connectorRequests.push(`unbind ${profile}`);
      answer(true, false);
      return;
    }
    // 바인딩 항목이 있는 profile 은 옛 설치를 받지 않는다.
    if (bind === undefined) {
      send(response, 409, { error: "this profile has bound connectors" });
      return;
    }
    const stored = typeof bind.vault === "string" ? vaults.get(bind.vault) : undefined;
    if (connector === undefined || stored === undefined || stored.connector !== plugin) {
      send(response, 400, { error: "no such vault for this connector" });
      return;
    }
    // 도구 목록이 없거나 Control Plane MCP 가 없는 목록, 옛 설치가 있는 profile 은 파일을 하나도 바꾸지 않고 거절한다.
    if (toolsets === undefined || !toolsets.includes(CONTROL_PLANE_MCP)
        || installedConnectors.get(profile)?.has(plugin) === true) {
      send(response, 409, { error: "the profile conflicts with this connector" });
      return;
    }
    const env = envOf(profile);
    let changed = bound.get(plugin) !== bind.vault;
    for (const field of connector.fields) {
      const value = stored.values[field.key];
      if (env[field.env] !== value) changed = true;
      if (value === undefined) delete env[field.env];
      else env[field.env] = value;
    }
    const next = toolsets.filter((name) => name !== "no_mcp");
    if (!next.includes(connector.mcp_server)) next.push(connector.mcp_server);
    if (next.join() !== toolsets.join()) changed = true;
    apiServerToolsets.set(profile, next);
    bound.set(plugin, bind.vault as string);
    boundConnectors.set(profile, bound);
    policyHookInstalled.add(profile);
    connectorRequests.push(`bind ${profile}`);
    answer(changed, changed);
  };
  /** profile 이름과 전역으로 끈 스킬 이름들이다. */
  const disabledSkills = new Map<string, Set<string>>();
  const blockedProviders = new Set<string>();
  /** 입력 글과 그 입력의 대본이다. */
  const scripts = new Map<string, DemoScript>();
  let busy = false;
  let submitCount = 0;
  let modelOptionsCalls = 0;
  let lastSubmittedRuntime: { provider?: string; model?: string; reasoningEffort?: string } = {};
  let holdNextRun = false;
  /** `slowRuns` 가 정한 지연이다. 없으면 실행이 제출 즉시 `completed` 다. */
  let slowRunMs: number | undefined;
  /** 지연 중인 실행의 번호와 그 profile 이다. 완료나 중지로 빠진다. */
  const slowActive = new Map<string, string>();
  let maxTotalConcurrency = 0;
  const maxProfileConcurrency = new Map<string, number>();
  /** 지연 중인 실행 하나를 끝낸다. 이미 중지된 실행의 상태는 덮어쓰지 않는다. */
  const finishSlowRun = (runId: string) => {
    if (!slowActive.delete(runId)) return;
    const run = runs.get(runId);
    if (run !== undefined && run.status === "running") run.status = "completed";
  };
  let heldRunId: string | undefined;
  let heldRunWaiter: (() => void) | undefined;
  let heldRunReady: Promise<void> | undefined;
  /** 사건 스트림이 한 번이라도 열린 run 과, 열리기를 기다리는 쪽이다. */
  const eventsOpened = new Set<string>();
  const eventsWaiters = new Map<string, (() => void)[]>();
  /** `LONG_ACTIVITY_PROBE` 스트림이 기다리는 자리다. 풀면 나머지 사건을 보낸다. */
  let longActivityGate: (() => void) | undefined;
  let holdNextSoul = false;
  /** 붙잡아 둔 성격 읽기 요청의 응답 객체와 보낼 본문이다. 풀릴 때 이것으로 응답을 끝낸다. */
  let heldSoul: { response: ServerResponse; payload: unknown } | undefined;
  const stoppedRuns: string[] = [];
  const emptyUntilStopped = new Map<string, ServerResponse>();
  let lastSubmittedInstructions: string | undefined;
  let lastSubmittedInput: string | undefined;
  let holdNextConfig = false;
  let heldConfigWaiter: (() => void) | undefined;
  let heldConfigReady: Promise<void> | undefined;
  let releaseConfig: (() => void) | undefined;
  let droppedToolset: string | undefined;
  let artifactWriteMcp: { endpoint: string; token: string } | undefined;
  let memoryReadMcp: { endpoint: string; token: string } | undefined;
  const subagentRegistrations: { childSessionId: string; rootSessionId: string; status: number }[] = [];
  let connectorPolicyEndpoint: string | undefined;
  const connectorToolCalls: ConnectorToolCall[] = [];
  /** `callConnectorTools` 를 부른 횟수다. 도구 호출 id 를 부를 때마다 다르게 만든다. */
  let directConnectorCalls = 0;
  /** 다음 살펴보기 실행의 각본이다. 살펴보기 실행 하나가 가져간다. */
  let proactiveScript: ProactiveScript | undefined;
  /** `waitBeforeEvents` 각본이 기다리는 자리와 그것을 푸는 함수다. 각본을 넣을 때 만든다. */
  let proactiveGate: Promise<void> | undefined;
  let openProactiveGate: (() => void) | undefined;
  const proactiveInputs: { profile: string; input: string }[] = [];

  /**
   * profile 플러그인의 `pre_tool_call` hook 처럼 커넥터 도구 호출마다 Control Plane 에 판정을 묻는다.
   *
   * <p>토큰은 그 profile 의 `.env` 에 든 MCP 토큰이고, 제출받은 run 의 session 이 곧 루트 session 이다.
   * 실제 hook 과 같이 주소나 토큰이 없거나 답이 200 의 `allow` 가 아니면 막는다. `block` 에 글이 없어도 막는다.
   * 인자는 입력의 글을 그대로 보낸다. 실제 hook 은 키를 정렬해 직렬화하지만 서버는 받은 글을 그대로 해시한다.
   * 원래 도구 이름은 등록 이름의 서버 앞부분으로 고른 커넥터가 선언한 도구일 때만 싣는다.
   *
   * @param callIdPrefix 도구 호출 id 의 앞부분. 한 session 에서 같은 id 를 다시 쓰면 앞선 판정이 되풀이되므로 부를 때마다 다르게 준다
   */
  const judgeConnectorCalls = async (
    profile: string,
    token: string | undefined,
    sessionId: string | undefined,
    lines: readonly string[],
    callIdPrefix: string,
  ): Promise<string> => {
    const output: string[] = [];
    for (const [index, line] of lines.entries()) {
      const space = line.indexOf(" ");
      const hermesTool = space < 0 ? line : line.slice(0, space);
      const argsJson = space < 0 ? "{}" : line.slice(space + 1).trim();
      const connector = FAKE_CONNECTORS.find((candidate) => hermesTool.startsWith(`mcp__${candidate.mcp_server}__`));
      const original = connector === undefined ? "" : hermesTool.slice(`mcp__${connector.mcp_server}__`.length);
      const tool = connector !== undefined && Object.hasOwn(connector.tools, original) ? original : null;
      let blocked = "정책을 확인하지 못했다";
      if (token !== undefined && connectorPolicyEndpoint !== undefined && sessionId !== undefined) {
        const response = await fetch(connectorPolicyEndpoint, {
          method: "POST",
          headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
          body: JSON.stringify(signedPolicyRequest(
            token, hermesTool, tool, sessionId, sessionId, `${callIdPrefix}-${index + 1}`, argsJson,
          )),
        });
        if (response.status === 200) {
          const answer = await response.json() as { decision?: unknown; message?: unknown };
          if (answer.decision === "allow") {
            connectorToolCalls.push({ profile, hermesTool, argsJson, via: "hook" });
            output.push(`${hermesTool}: allow`);
            continue;
          }
          if (answer.decision === "block" && typeof answer.message === "string" && answer.message.trim() !== "") {
            blocked = answer.message;
          }
        } else {
          blocked = `정책을 확인하지 못했다 (HTTP ${response.status})`;
        }
      }
      output.push(`${hermesTool}: block ${blocked}`);
    }
    return output.join("\n");
  };

  /**
   * profile 플러그인처럼 서명한 `_fos_ctx` 를 붙여 Control Plane MCP 도구 하나를 부르고 도구 결과의 text 를 돌려준다.
   *
   * <p>제출받은 run 의 session 이 곧 루트 session 이다. run 마다 자기 session 으로 서명하므로, 나란히 도는 두 run 이
   * 서로의 session 을 쓰면 요청자가 뒤섞여 검사가 실패한다. 하위 에이전트는 루트와 자기 session 을 따로 준다.
   * 주소와 토큰은 `setMemoryReadMcp` 로 받은 것을 쓴다.
   */
  const callControlPlaneToolViaMcp = async (
    name: string,
    args: Record<string, unknown>,
    sessionId: string | undefined,
    rootSessionId: string | undefined = sessionId,
  ): Promise<string> => {
    if (memoryReadMcp === undefined) throw new Error(`${name} MCP runtime is not configured`);
    if (sessionId === undefined || rootSessionId === undefined) {
      throw new Error(`${name} MCP needs the submitted session_id to sign _fos_ctx`);
    }
    const _fos_ctx = signedCallContext(memoryReadMcp.token, name, rootSessionId, sessionId, `call_${randomUUID()}`);
    const response = await fetch(memoryReadMcp.endpoint, {
      method: "POST",
      headers: { Authorization: `Bearer ${memoryReadMcp.token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: { ...args, _fos_ctx } } }),
    });
    if (!response.ok) throw new Error(`${name} MCP HTTP ${response.status}`);
    const body = await response.json() as { result?: { content?: { text?: string }[] } };
    const text = body.result?.content?.[0]?.text;
    if (text === undefined) throw new Error(`${name} MCP response has no text`);
    return text;
  };

  /** 서명한 `_fos_ctx` 를 붙여 `memory_read` 를 부르고 도구 결과의 text 를 돌려준다. */
  const readMemoryViaMcp = (
    memoryId: number,
    sessionId: string | undefined,
    rootSessionId: string | undefined = sessionId,
  ): Promise<string> => callControlPlaneToolViaMcp("memory_read", { id: memoryId }, sessionId, rootSessionId);

  /**
   * profile 플러그인의 `subagent_start` hook 처럼 자식 session 을 부모의 루트 아래 등록한다.
   *
   * <p>실제 hook 은 예외를 삼키므로 응답이 2xx 가 아니어도 던지지 않고 상태만 남긴다. 시나리오가 그 기록으로 실패를 안다.
   * 등록 경로는 `/mcp` 와 같은 서버에 있어 MCP 주소에서 `/mcp` 를 떼어 만든다.
   */
  const registerSubagent = async (rootSessionId: string | undefined): Promise<string> => {
    if (memoryReadMcp === undefined) throw new Error("subagent registration needs the MCP token");
    if (rootSessionId === undefined) throw new Error("subagent registration needs the submitted session_id");
    const childSessionId = `native-${randomUUID()}`;
    const origin = memoryReadMcp.endpoint.replace(/\/mcp$/, "");
    const response = await fetch(`${origin}/internal/hermes/session-bindings/subagent`, {
      method: "POST",
      headers: { Authorization: `Bearer ${memoryReadMcp.token}`, "Content-Type": "application/json" },
      body: JSON.stringify(signedSubagentRegistration(memoryReadMcp.token, rootSessionId, rootSessionId, childSessionId)),
    });
    subagentRegistrations.push({ childSessionId, rootSessionId, status: response.status });
    return childSessionId;
  };

  /**
   * profile 플러그인처럼 도구 인자에 서명한 `_fos_ctx` 를 붙여 `artifact_write` 를 부른다.
   *
   * <p>제출받은 run 의 session 이 곧 루트 session 이다. 하위 에이전트가 아니므로 session 과 루트가 같다.
   */
  const writeArtifactViaMcp = async (conversationId: string, sessionId: string | undefined): Promise<void> => {
    if (artifactWriteMcp === undefined) throw new Error("artifact_write MCP runtime is not configured");
    if (sessionId === undefined) throw new Error("artifact_write MCP needs the submitted session_id to sign _fos_ctx");
    const token = artifactWriteMcp.token;
    const request = async (body: unknown): Promise<unknown> => {
      const response = await fetch(artifactWriteMcp.endpoint, {
        method: "POST",
        headers: { Authorization: `Bearer ${artifactWriteMcp.token}`, "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!response.ok) throw new Error(`artifact_write MCP HTTP ${response.status}`);
      return response.json();
    };
    await request({ jsonrpc: "2.0", id: 1, method: "initialize" });
    const notification = await fetch(artifactWriteMcp.endpoint, {
      method: "POST",
      headers: { Authorization: `Bearer ${artifactWriteMcp.token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }),
    });
    if (notification.status !== 202) throw new Error(`artifact_write MCP notification ${notification.status}`);
    const listed = await request({ jsonrpc: "2.0", id: 2, method: "tools/list" }) as { result?: { tools?: { name?: string }[] } };
    if (!listed.result?.tools?.some((tool) => tool.name === "artifact_write")) throw new Error("artifact_write MCP tool was not discovered");
    for (const [path, content] of [["test/index.html", "<!doctype html><title>MCP 초안</title><h1>MCP 결과물</h1>"], ["test/style.css", "h1 { color: navy; }"]] as const) {
      const _fos_ctx = signedCallContext(token, "artifact_write", sessionId, sessionId, `call_${randomUUID()}`);
      const result = await request({ jsonrpc: "2.0", id: path, method: "tools/call", params: { name: "artifact_write", arguments: { conversation_id: conversationId, path, content, _fos_ctx } } }) as { result?: { content?: { text?: string }[]; isError?: boolean } };
      if (result.result?.isError === true || result.result?.content?.[0]?.text === undefined) throw new Error("artifact_write MCP call failed");
      const written = JSON.parse(result.result.content[0].text) as { path?: string; byteSize?: number };
      if (written.path !== path || typeof written.byteSize !== "number") throw new Error("artifact_write MCP response is invalid");
    }
  };

  const authorized = (request: IncomingMessage, profile: string): boolean => {
    const expected = keys[profile];
    return expected !== undefined && request.headers.authorization === `Bearer ${expected}`;
  };

  /** 대시보드 경로는 profile 별 key 가 아니라 기계용 토큰 하나로 열린다. */
  const dashboardAuthorized = (request: IncomingMessage): boolean =>
    request.headers.authorization === `Bearer ${FAKE_DASHBOARD_TOKEN}`;

  /** 붙잡은 성격 읽기 응답을 보내고 대기 표시를 끈다. 붙잡은 것이 없어도 대기 표시는 끈다. */
  const releaseHeldSoul = () => {
    holdNextSoul = false;
    const held = heldSoul;
    heldSoul = undefined;
    if (held !== undefined) send(held.response, 200, held.payload);
  };
  /** 붙잡은 실행을 풀되 실행 상태는 바꾸지 않는다. */
  const releaseHeldRun = (): string | undefined => {
    const runId = heldRunId;
    heldRunWaiter?.();
    heldRunId = undefined;
    heldRunReady = undefined;
    heldRunWaiter = undefined;
    return runId;
  };

  /** 기다리는 긴 작업 과정 스트림을 풀고 대기 표시를 지운다. 기다리는 것이 없으면 아무것도 하지 않는다. */
  const releaseLongActivity = () => {
    const gate = longActivityGate;
    longActivityGate = undefined;
    gate?.();
  };

  /**
   * 대시보드의 profile 관리 경로다.
   *
   * <p>Control Plane 이 부르는 셋만 흉내 낸다. 여기서 맡은 경로면 true 를 돌려주고, 아니면 false 를
   * 돌려줘 실행 경로로 넘긴다.
   */
  const handleDashboard = async (
    request: IncomingMessage,
    response: ServerResponse,
    path: string,
    queryProfile: string | null,
  ): Promise<boolean> => {
    const soulMatch = SOUL_PATH.exec(path);
    const modelDefaultsMatch = MODEL_DEFAULTS_PATH.exec(path);
    const sessionProviderMatch = SESSION_PROVIDER_PATH.exec(path);
    const profileMatch = PROFILE_PATH.exec(path);
    const isDashboardPath = path === PROFILES_PATH || path === ENV_PATH || path === TOOLSET_CATALOG_PATH
      || path === CONFIG_PATH || path === SKILLS_PATH || path === SKILL_TOGGLE_PATH || profileMatch !== null
      || path === CONNECTORS_PATH || path === CONNECTOR_CATALOG_PATH
      || path === CONNECTOR_VAULT_PATH || path === CONNECTOR_VAULT_IMPORT_PATH
      || CONNECTOR_CALL_PATH.test(path) || CONNECTOR_EXECUTE_PATH.test(path) || MCP_SERVER_TEST_PATH.test(path);
    if (!isDashboardPath) return false;

    if (!dashboardAuthorized(request)) {
      send(response, 401, { reason: "no_token" });
      return true;
    }

    if (request.method === "GET" && path === CONNECTOR_CATALOG_PATH) {
      send(response, 200, FAKE_CONNECTORS);
      return true;
    }

    const callMatch = CONNECTOR_CALL_PATH.exec(path);
    if (request.method === "POST" && callMatch !== null) {
      const connector = fakeConnector(callMatch[1]);
      if (connector === undefined) {
        send(response, 404, { error: "no such connector" });
        return true;
      }
      const body = JSON.parse((await readBody(request)) || "{}") as {
        tool?: string; values?: Record<string, string>; vault?: string;
      };
      // 칸 값은 본문의 `values` 나 보관 파일 가운데 정확히 하나에서 온다.
      if ((body.values === undefined) === (body.vault === undefined)) {
        send(response, 400, { error: "values or vault is required" });
        return true;
      }
      const stored = body.vault === undefined ? undefined : vaults.get(body.vault);
      if (body.vault !== undefined && stored?.connector !== callMatch[1]) {
        send(response, 400, { error: "no such vault for this connector" });
        return true;
      }
      const values = stored?.values ?? body.values;
      if (body.tool !== connector.verify.tool) {
        send(response, 200, { ok: false, error: "invalid_input" });
        return true;
      }
      connectorRequests.push(`call ${body.tool}`);
      send(response, 200, verifyAnswer(connector, values));
      return true;
    }

    // 대시보드의 실행 경로다. 승인 여부를 다시 보지 않고 받은 호출을 한 번 실행한 것으로 친다.
    const executeMatch = CONNECTOR_EXECUTE_PATH.exec(path);
    if (request.method === "POST" && executeMatch !== null) {
      const body = JSON.parse((await readBody(request)) || "{}") as {
        profile?: string; hermes_tool?: string; args?: unknown;
      };
      const connectorId = executeMatch[1]!;
      const installed = body.profile !== undefined
        && (installedConnectors.get(body.profile)?.has(connectorId) === true
          || boundConnectors.get(body.profile)?.has(connectorId) === true);
      if (fakeConnector(connectorId) === undefined || !installed) {
        send(response, 404, { error: "no such connector" });
        return true;
      }
      if (typeof body.hermes_tool !== "string" || typeof body.args !== "object" || body.args === null) {
        send(response, 400, { error: "invalid request" });
        return true;
      }
      connectorToolCalls.push({
        profile: body.profile!, hermesTool: body.hermes_tool, argsJson: JSON.stringify(body.args), via: "execute",
      });
      send(response, 200, { ok: true, result: { saved: true } });
      return true;
    }

    if (request.method === "GET" && path === CONNECTORS_PATH) {
      // 대시보드로 만든 profile 은 관리 표식이, 미리 심어 둔 profile 은 운영자가 둔 커넥터 표식이 있는 것으로 친다.
      if (queryProfile === null || (!profiles.has(queryProfile) && keys[queryProfile] === undefined)) {
        send(response, 404, { error: "no such profile" });
        return true;
      }
      const env = profiles.get(queryProfile) ?? hostEnv.get(queryProfile) ?? {};
      const installed = installedConnectors.get(queryProfile) ?? new Set<string>();
      const toolsets = apiServerToolsets.get(queryProfile) ?? [];
      const states = FAKE_CONNECTORS.map((connector) => {
        const bound = boundConnectors.get(queryProfile)?.has(connector.id) === true;
        const filled = connector.fields.every((field) => !field.required || env[field.env] !== undefined);
        // 옛 설치는 도구 목록이 설치가 쓰는 목록과 같을 때만 configured 다. 다른 내장 도구나 Control Plane MCP 가 남으면 아니다.
        // 바인딩 설치는 서버 이름이 목록에 있으면 된다. Control Plane MCP 와 다른 도구가 함께 있어도 된다.
        const configured = filled && (bound
          ? toolsets.includes(connector.mcp_server)
          : toolsets.join() === [connector.mcp_server, ...connector.toolsets].join());
        return {
          plugin: connector.id,
          enabled: bound || installed.has(connector.id),
          configured,
          mode: bound ? "bind" : "isolated",
        };
      });
      send(response, 200, {
        profile: queryProfile,
        policy_hook: policyHookInstalled.has(queryProfile) && !policyHookOff.has(queryProfile),
        connectors: states,
      });
      return true;
    }

    if (request.method === "PUT" && path === CONNECTORS_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as {
        profile?: string; plugin?: string; enabled?: unknown; bind?: { vault?: unknown };
      };
      if (body.profile === undefined || typeof body.plugin !== "string" || typeof body.enabled !== "boolean") {
        send(response, 400, { error: "invalid connector request" });
        return true;
      }
      const boundHere = boundConnectors.get(body.profile);
      if (body.bind !== undefined || boundHere?.has(body.plugin) === true) {
        bindingInstall(response, body.profile, body.plugin, body.enabled, body.bind);
        return true;
      }
      if (!profiles.has(body.profile)) {
        // 커넥터 표식만 있는 profile 은 옛 설치를 받지 않는다. 붙은 것이 없는 떼기는 끌 것이 없으므로 바뀐 것 없이 성공한다.
        if (keys[body.profile] !== undefined && !body.enabled) {
          send(response, 200, {
            profile: body.profile, plugin: body.plugin, enabled: false, changed: false, restart_required: false,
            plugin_updated: false,
          });
        } else {
          send(response, 400, { error: "invalid connector request" });
        }
        return true;
      }
      if (body.plugin !== DEMO_CONNECTOR.id) {
        // 모르는 plugin 은 켜지 못한다. 끄기는 끌 것이 없으므로 바뀐 것 없이 성공한다.
        if (body.enabled) send(response, 400, { error: "invalid connector request" });
        else {
          send(response, 200, {
            profile: body.profile, plugin: body.plugin, enabled: false, changed: false, restart_required: false,
            plugin_updated: false,
          });
        }
        return true;
      }
      connectorRequests.push(`install ${body.profile} ${body.enabled ? "on" : "off"}`);
      const installed = installedConnectors.get(body.profile) ?? new Set<string>();
      const changed = installed.has(body.plugin) !== body.enabled;
      if (body.enabled) {
        installed.add(body.plugin);
        // 설치는 그 profile 의 정책 hook 을 지금 판으로 맞춘다.
        policyHookInstalled.add(body.profile);
      } else {
        installed.delete(body.plugin);
      }
      installedConnectors.set(body.profile, installed);
      // 설치는 그 profile 의 API 도구 목록을 커넥터의 MCP 서버 이름과 선언한 toolset 으로 다시 쓰고,
      // 해제는 MCP 가 없는 목록으로 쓴다.
      apiServerToolsets.set(
        body.profile,
        body.enabled ? [DEMO_CONNECTOR.mcp_server, ...DEMO_CONNECTOR.toolsets] : ["no_mcp"],
      );
      send(response, 200, {
        profile: body.profile, plugin: body.plugin, enabled: body.enabled, changed, restart_required: false,
        plugin_updated: false,
      });
      return true;
    }

    // 보관 파일 경로다. 값과 이름은 응답과 요청 기록에 싣지 않는다.
    if (request.method === "PUT" && path === CONNECTOR_VAULT_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as { vault?: unknown; connector?: unknown; values?: unknown };
      const connector = typeof body.connector === "string" ? fakeConnector(body.connector) : undefined;
      if (typeof body.vault !== "string" || !VAULT_NAME.test(body.vault) || connector === undefined
          || !vaultValuesValid(connector, body.values)) {
        send(response, 400, { error: "invalid vault request" });
        return true;
      }
      if (vaults.has(body.vault) && vaults.get(body.vault)!.connector !== connector.id) {
        send(response, 409, { error: "the vault belongs to another connector" });
        return true;
      }
      vaults.set(body.vault, { connector: connector.id, values: { ...body.values } });
      connectorRequests.push("vault put");
      send(response, 200, { ok: true });
      return true;
    }

    if (request.method === "DELETE" && path === CONNECTOR_VAULT_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as { vault?: unknown };
      if (typeof body.vault !== "string" || !VAULT_NAME.test(body.vault)) {
        send(response, 400, { error: "invalid vault request" });
        return true;
      }
      connectorRequests.push("vault delete");
      send(response, 200, { changed: vaults.delete(body.vault) });
      return true;
    }

    if (request.method === "POST" && path === CONNECTOR_VAULT_IMPORT_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as {
        vault?: unknown; connector?: unknown; profile?: unknown;
      };
      if (typeof body.vault !== "string" || !VAULT_NAME.test(body.vault) || body.connector !== DEMO_CONNECTOR.id
          || typeof body.profile !== "string") {
        send(response, 400, { error: "invalid vault request" });
        return true;
      }
      // 옛 설치는 관리 표식이 있는 profile 에만 있다.
      const env = profiles.get(body.profile);
      if (env === undefined) {
        send(response, keys[body.profile] === undefined ? 404 : 401, { error: "no managed profile" });
        return true;
      }
      if (!installedConnectors.get(body.profile)?.has(DEMO_CONNECTOR.id)) {
        send(response, 404, { error: "the connector is not installed in this profile" });
        return true;
      }
      if (vaults.has(body.vault) && vaults.get(body.vault)!.connector !== DEMO_CONNECTOR.id) {
        send(response, 409, { error: "the vault belongs to another connector" });
        return true;
      }
      const values: Record<string, string> = {};
      for (const field of DEMO_CONNECTOR.fields) {
        const value = env[field.env];
        if (value !== undefined && value !== "") values[field.key] = value;
      }
      if (!vaultValuesValid(DEMO_CONNECTOR, values)) {
        send(response, 400, { error: "a required field is empty" });
        return true;
      }
      vaults.set(body.vault, { connector: DEMO_CONNECTOR.id, values });
      connectorRequests.push(`vault import ${body.profile}`);
      send(response, 200, { ok: true });
      return true;
    }

    const probeMatch = MCP_SERVER_TEST_PATH.exec(path);
    if (request.method === "POST" && probeMatch !== null) {
      // 그 profile 에 설치하거나 붙인 커넥터의 서버만 시험한다.
      const connector = FAKE_CONNECTORS.find((candidate) => candidate.mcp_server === probeMatch[1]);
      if (connector === undefined || queryProfile === null
          || (!installedConnectors.get(queryProfile)?.has(connector.id)
            && !boundConnectors.get(queryProfile)?.has(connector.id))) {
        send(response, 404, { error: "no such mcp server" });
        return true;
      }
      connectorRequests.push(`probe ${queryProfile}`);
      send(response, 200, { ok: true, tools: (SERVER_TOOLS[connector.mcp_server] ?? []).map((name) => ({ name })) });
      return true;
    }

    if (request.method === "GET" && path === TOOLSET_CATALOG_PATH) {
      send(response, 200, TOOLSET_CATALOG.map((toolset) => ({ ...toolset, enabled: false })));
      return true;
    }

    if (request.method === "GET" && path === SKILLS_PATH) {
      if (queryProfile === null) {
        send(response, 400, { error: "profile query is required" });
        return true;
      }
      if (!keys[queryProfile]) {
        send(response, 404, { error: "no such profile" });
        return true;
      }
      const disabled = disabledSkills.get(queryProfile) ?? new Set<string>();
      // 실제 응답의 모양이다. `enabled` 는 전역 `skills.disabled` 만 반영한다.
      send(response, 200, [
        ...BUILTIN_SKILLS.map((skill) => ({ ...skill, category: "builtin", provenance: "bundled" })),
        ...readPublishedSkills(externalDirs.get(queryProfile) ?? []).map((skill) => ({
          ...skill, category: "agent", provenance: "agent",
        })),
      ].map((skill) => ({ ...skill, enabled: !disabled.has(skill.name), usage: 0 })));
      return true;
    }

    if (request.method === "PUT" && path === SKILL_TOGGLE_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as {
        profile?: string;
        name?: string;
        enabled?: unknown;
      };
      if (body.profile === undefined || body.name === undefined || typeof body.enabled !== "boolean") {
        send(response, 400, { error: "profile, name and enabled are required" });
        return true;
      }
      if (!keys[body.profile]) {
        send(response, 404, { error: "no such profile" });
        return true;
      }
      const disabled = disabledSkills.get(body.profile) ?? new Set<string>();
      if (body.enabled) disabled.delete(body.name);
      else disabled.add(body.name);
      disabledSkills.set(body.profile, disabled);
      send(response, 200, { ok: true, name: body.name, enabled: body.enabled });
      return true;
    }

    if (request.method === "PUT" && path === CONFIG_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as {
        profile?: string;
        config?: {
          platform_toolsets?: { api_server?: unknown };
          skills?: { external_dirs?: unknown };
        };
      };
      const configKeys = Object.keys(body.config ?? {});
      // 도구와 스킬 게시만 받는다. 둘을 한 본문에 함께 둘 수 있고, 그 밖의 키는 거절한다.
      const exactKeys = Object.keys(body).length === 2 && Object.keys(body).every((key) => key === "profile" || key === "config")
        && configKeys.length >= 1
        && configKeys.every((key) => key === "platform_toolsets" || key === "skills");
      if (body.profile === undefined || queryProfile !== null && queryProfile !== body.profile
          || !keys[body.profile] || !exactKeys) {
        send(response, 400, { error: "invalid configuration" });
        return true;
      }
      const profile = body.profile;
      let nextToolsets: string[] | undefined;
      if (configKeys.includes("platform_toolsets")) {
        const toolsets = body.config?.platform_toolsets?.api_server;
        const bound = boundServers(profile);
        const validNames = new Set([...TOOLSET_CATALOG.map((entry) => entry.name), CONTROL_PLANE_MCP, ...bound]);
        if (Object.keys(body.config?.platform_toolsets ?? {}).length !== 1 || !Array.isArray(toolsets)
            || !toolsets.includes(CONTROL_PLANE_MCP)
            || !toolsets.every((name) => typeof name === "string" && validNames.has(name))) {
          send(response, 400, { error: "invalid toolset configuration" });
          return true;
        }
        // 처리기가 목록을 통째로 바꾸므로 붙은 커넥터의 서버 이름이 빠진 목록은 조용히 지우지 않고 거절한다.
        if (!bound.every((name) => toolsets.includes(name))) {
          send(response, 409, { error: "연결된 커넥터의 도구 이름이 빠졌다" });
          return true;
        }
        nextToolsets = toolsets as string[];
      }
      let nextDirs: string[] | undefined;
      if (configKeys.includes("skills")) {
        const dirs = body.config?.skills?.external_dirs;
        // 게시 거절 규칙은 docs/hermes/profiles.md 의 표를 따른다. 경로 형식, 다른 profile 의 prefix, 둘 이상,
        // 심볼릭 링크, 없는 디렉터리, skills 도구가 꺼진 채 게시가 모두 400 이다.
        if (Object.keys(body.config?.skills ?? {}).length !== 1 || !Array.isArray(dirs) || dirs.length > 1
            || !dirs.every((dir) => typeof dir === "string")) {
          send(response, 400, { error: "invalid skills configuration" });
          return true;
        }
        for (const dir of dirs as string[]) {
          const match = SKILL_VERSION_DIR.exec(dir);
          if (match === null || match[1] !== profile || (skillRoot !== undefined && !dir.startsWith(`${skillRoot}/`))) {
            send(response, 400, { error: "external_dirs path is not this profile's skill directory" });
            return true;
          }
          let stat;
          try {
            stat = lstatSync(dir);
          } catch {
            send(response, 400, { error: "external_dirs path does not exist" });
            return true;
          }
          if (stat.isSymbolicLink() || !stat.isDirectory()) {
            send(response, 400, { error: "external_dirs path is not a plain directory" });
            return true;
          }
        }
        const effectiveToolsets = nextToolsets ?? apiServerToolsets.get(profile) ?? DEFAULT_API_SERVER_TOOLSETS;
        if (dirs.length > 0 && !effectiveToolsets.includes("skills")) {
          send(response, 400, { error: "the skills toolset is off for this profile" });
          return true;
        }
        nextDirs = dirs as string[];
      }
      if (holdNextConfig) {
        holdNextConfig = false;
        heldConfigWaiter?.();
        await new Promise<void>((done) => { releaseConfig = done; });
        releaseConfig = undefined;
      }
      if (nextToolsets !== undefined) {
        connectorRequests.push(`toolsets ${profile}`);
        apiServerToolsets.set(profile, nextToolsets.filter((name) => name !== droppedToolset));
        droppedToolset = undefined;
      }
      if (nextDirs !== undefined) externalDirs.set(profile, nextDirs);
      send(response, 200, { ok: true });
      return true;
    }

    if (modelDefaultsMatch !== null && request.method === "GET") {
      send(response, 200, {
        provider: DEFAULT_RUNTIME.provider,
        model: DEFAULT_RUNTIME.model,
        reasoningEffort: "medium",
      });
      return true;
    }

    // `PROFILE_PATH` 의 `.+` 가 이 경로도 함께 먹으므로 그 분기보다 앞에서 처리한다.
    if (request.method === "GET" && sessionProviderMatch !== null) {
      const child = childUsages.get(decodeURIComponent(sessionProviderMatch[2]!));
      if (child === undefined || child.profile !== decodeURIComponent(sessionProviderMatch[1]!)) {
        send(response, 404, { detail: "없는 session 이다" });
        return true;
      }
      send(response, 200, { provider: child.provider ?? null, model: child.model ?? "example-fast" });
      return true;
    }

    if (soulMatch !== null) {
      const name = decodeURIComponent(soulMatch[1]!);
      if (request.method === "GET") {
        const content = souls.get(name);
        const payload = { content: content ?? "", exists: content !== undefined };
        if (holdNextSoul) {
          holdNextSoul = false;
          heldSoul = { response, payload };
          return true;
        }
        send(response, 200, payload);
        return true;
      }
      if (request.method === "PUT") {
        const body = JSON.parse((await readBody(request)) || "{}") as { content?: string };
        souls.set(name, body.content ?? "");
        // 실제 대시보드는 쓴 본문을 되돌려주지 않고 `{"ok": true}` 만 준다.
        send(response, 200, { ok: true });
        return true;
      }
    }

    if (request.method === "POST" && path === PROFILES_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as { name?: string };
      const name = body.name ?? "";
      if (name.length === 0) {
        send(response, 400, { error: "name is required" });
        return true;
      }
      if (profiles.has(name)) {
        send(response, 409, { error: "profile already exists" });
        return true;
      }
      profiles.set(name, {});
      apiServerToolsets.set(name, [...PLUGIN_TEMPLATE_TOOLSETS]);
      send(response, 200, { name });
      return true;
    }

    if (request.method === "PUT" && path === ENV_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as {
        profile?: string;
        key?: string;
        value?: string;
      };
      const env = body.profile === undefined ? undefined : profiles.get(body.profile);
      if (env === undefined || body.key === undefined || body.value === undefined) {
        send(response, 404, { error: "no such profile" });
        return true;
      }
      env[body.key] = body.value;
      if (connectorEnvNames.has(body.key)) {
        // 커넥터 칸은 응답이 재시작 필요 여부도 담는다. 값은 적지 않는다.
        connectorRequests.push(`env put ${body.profile} ${body.key}`);
        send(response, 200, { profile: body.profile, key: body.key, restart_required: false });
        return true;
      }
      // 이 칸으로 들어온 값이 그 profile 의 key 가 된다. 대역이 스스로 만들면 Control Plane 이 key
      // 파일에 쓴 값과 어긋나 그 profile 의 실행이 401 을 받는다.
      if (body.key === API_KEY_ENV_NAME) keys[body.profile!] = body.value;
      send(response, 200, { profile: body.profile, key: body.key });
      return true;
    }

    if (request.method === "DELETE" && path === ENV_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as { profile?: string; key?: string };
      const env = body.profile === undefined ? undefined : profiles.get(body.profile);
      if (env === undefined || body.key === undefined || !connectorEnvNames.has(body.key)) {
        send(response, 404, { error: "no such profile" });
        return true;
      }
      connectorRequests.push(`env delete ${body.profile} ${body.key}`);
      delete env[body.key];
      send(response, 200, { profile: body.profile, key: body.key, restart_required: false });
      return true;
    }

    if (request.method === "DELETE" && profileMatch !== null) {
      const name = decodeURIComponent(profileMatch[1]!);
      if (!profiles.delete(name)) {
        send(response, 404, { error: "no such profile" });
        return true;
      }
      delete keys[name];
      // 붙인 커넥터의 소유 기록과 `.env` 는 profile 디렉터리에 있어 함께 사라진다.
      boundConnectors.delete(name);
      hostEnv.delete(name);
      send(response, 200, { name });
      return true;
    }

    send(response, 404, { error: "not found" });
    return true;
  };

  const server: Server = createServer((request, response) => {
    void (async () => {
      const requestUrl = new URL(request.url ?? "/", "http://fake-hermes.test");
      const path = requestUrl.pathname;

      const blockMatch = TEST_BLOCK_PROVIDER_PATH.exec(path);
      if (request.method === "POST" && blockMatch) {
        blockedProviders.add(blockMatch[1]!);
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_CLEAR_BLOCKED_PATH) {
        blockedProviders.clear();
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_BUSY_PATH) {
        busy = true;
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_CLEAR_BUSY_PATH) {
        busy = false;
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_HOLD_NEXT_CONFIG_PATH) {
        holdNextConfig = true;
        heldConfigReady = new Promise<void>((done) => { heldConfigWaiter = done; });
        return send(response, 204, null);
      }
      if (request.method === "POST" && path === TEST_RELEASE_HELD_CONFIG_PATH) {
        releaseConfig?.();
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_HOLD_NEXT_RUN_PATH) {
        holdNextRun = true;
        heldRunReady = new Promise<void>((done) => {
          heldRunWaiter = done;
        });
        return send(response, 204, null);
      }

      if (request.method === "GET" && path === TEST_WAIT_HELD_RUN_PATH) {
        if (heldRunReady === undefined) return send(response, 409, { error: "no held run is pending" });
        await heldRunReady;
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_RELEASE_HELD_RUN_PATH) {
        holdNextRun = false;
        const releasedRunId = releaseHeldRun();
        if (releasedRunId === undefined) return send(response, 204, null);
        const run = runs.get(releasedRunId);
        if (run !== undefined) run.status = "completed";
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_RELEASE_LONG_ACTIVITY_PATH) {
        releaseLongActivity();
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_SCRIPT_PATH) {
        const script = JSON.parse(await readBody(request)) as DemoScript;
        scripts.set(script.input, script);
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_PROACTIVE_OUTPUT_PATH) {
        const { output } = JSON.parse(await readBody(request)) as { output: string };
        proactiveScript = { output, tools: [] };
        return send(response, 204, null);
      }

      if (request.method === "GET" && path === TEST_LAST_SUBMITTED_RUNTIME_PATH) {
        return send(response, 200, lastSubmittedRuntime);
      }

      if (request.method === "POST" && path === TEST_HOLD_NEXT_SOUL_PATH) {
        holdNextSoul = true;
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_RELEASE_HELD_SOUL_PATH) {
        // 대기 표시는 붙잡은 응답이 없어도 끈다. 클릭 전에 실패한 검사가 남긴 홀드 때문에 다음 검사가
        // 걸리지 않게 하기 위해서다.
        releaseHeldSoul();
        return send(response, 204, null);
      }

      if (await handleDashboard(request, response, path, requestUrl.searchParams.get("profile"))) return;

      if (request.method === "GET") {
        const enabledToolsetsMatch = ENABLED_TOOLSETS_PATH.exec(path);
        if (enabledToolsetsMatch) {
          const profile = enabledToolsetsMatch[1]!;
          if (!authorized(request, profile)) return send(response, 401, { error: "bad key for this profile" });
          const enabled = new Set(apiServerToolsets.get(profile) ?? DEFAULT_API_SERVER_TOOLSETS);
          // 실제 listener 는 목록을 `data` 로 감싼다(v0.21.3 `gateway/platforms/api_server.py` 의 `_handle_toolsets`).
          return send(response, 200, {
            object: "list",
            platform: "api_server",
            data: TOOLSET_CATALOG.map((toolset) => ({ ...toolset, enabled: enabled.has(toolset.name) })),
          });
        }
        const modelMatch = MODEL_OPTIONS_PATH.exec(path);
        if (modelMatch) {
          const profile = modelMatch[1];
          if (!authorized(request, profile)) {
            return send(response, 401, { error: "bad key for this profile" });
          }
          modelOptionsCalls += 1;
          // 실제 응답의 모양이다. 설정하지 않은 provider 도 빈 행으로 함께 온다.
          return send(response, 200, {
            ...DEFAULT_RUNTIME,
            providers: [
              {
                slug: DEFAULT_RUNTIME.provider,
                name: "OpenAI Codex",
                authenticated: true,
                models: [DEFAULT_RUNTIME.model, "example-model-mini", "example-fast", "example-balanced", "example-deep"],
                // 실제로 can_disable_reasoning 은 aggregator provider 의 모델에만 온다.
                // 이 대역은 reasoning 끄기(none)를 시험하려고 기본 모델에도 준다.
                // example-balanced 는 칸이 없는 모델(UNKNOWN)이다.
                capabilities: {
                  [DEFAULT_RUNTIME.model]: { reasoning: true, can_disable_reasoning: true },
                  "example-model-mini": { reasoning: false },
                  "example-fast": { reasoning: true },
                  "example-balanced": {},
                  "example-deep": { reasoning: true, can_disable_reasoning: false },
                },
              },
              { slug: "unconfigured", name: "Unconfigured", authenticated: false, models: [] },
            ],
          });
        }

        const sessionMatch = SESSION_PATH.exec(path);
        if (sessionMatch) {
          const [, profile, sessionId] = sessionMatch;
          if (!authorized(request, profile!)) {
            return send(response, 401, { error: "bad key for this profile" });
          }
          const child = childUsages.get(sessionId!);
          if (child && child.profile === profile) {
            child.reads += 1;
            const ended = !child.delayed || child.reads > 1;
            return send(response, 200, { object: "session", session: {
              id: sessionId, source: "subagent", parent_session_id: child.parent,
              model: child.model ?? "example-fast", started_at: 1000, ended_at: ended ? 1002.5 : null,
              end_reason: ended ? "agent_close" : null,
              input_tokens: 100, cache_read_tokens: 50, cache_write_tokens: 10, output_tokens: 20,
            } });
          }
          const session = sessions.get(sessionId!);
          if (!session) return send(response, 404, { error: "no such session" });
          // 실제 v0.21.5 는 세션 행을 session 안에 감싸고 provider 칸을 주지 않는다(저장소의 billing_provider).
          return send(response, 200, { object: "session", session: { id: sessionId, model: session.model } });
        }

        const capabilitiesMatch = CAPABILITIES_PATH.exec(path);
        if (capabilitiesMatch) {
          const profile = capabilitiesMatch[1];
          if (!authorized(request, profile)) {
            return send(response, 401, { error: "bad key for this profile" });
          }
          return send(response, 200, { model: "example-model", tools: [] });
        }

        const eventMatch = RUN_EVENTS_PATH.exec(path);
        if (eventMatch) {
          const [, profile, runId] = eventMatch;
          if (!authorized(request, profile)) {
            return send(response, 401, { error: "bad key for this profile" });
          }
          eventsOpened.add(runId!);
          for (const done of eventsWaiters.get(runId!) ?? []) done();
          eventsWaiters.delete(runId!);
          const run = runs.get(runId);
          if (!run) return send(response, 404, runNotFound(runId!));
          response.writeHead(200, {
            "Content-Type": "text/event-stream; charset=utf-8",
            "Cache-Control": "no-cache",
            Connection: "keep-alive",
          });
          response.write(": keepalive\n\n");
          // 지연 중인 실행은 끝나거나 중지될 때까지 스트림을 연 채로 둔다. 실제 Hermes 가 도는 동안 그렇게 한다.
          while (slowActive.has(runId!) && !response.destroyed) await wait(20);
          // 살펴보기 실행이다. 도구 사건과 마지막 답 글을 흘린다. Control Plane 은 답 조각을 화면으로 보내지 않아야 한다.
          if (run.proactive !== undefined) {
            if (run.proactive.gate !== undefined) {
              await Promise.race([run.proactive.gate, new Promise<void>((resolve) => response.on("close", resolve))]);
            }
            for (const tool of run.proactive.tools) {
              event(response, { event: "tool.started", tool, preview: tool });
              event(response, { event: "tool.completed", tool, duration: 0.1, error: false });
            }
            event(response, { event: "message.delta", delta: run.output });
            event(response, { event: "run.completed" });
            response.end();
            return;
          }
          if (run.input === "중지 조각 전 검사" && run.status !== "completed") {
            if (run.status === "cancelled") {
              response.end();
            } else {
              emptyUntilStopped.set(runId!, response);
              response.on("close", () => emptyUntilStopped.delete(runId!));
            }
            return;
          }
          const script = scripts.get(run.input);
          if (script !== undefined) {
            for (const scripted of script.events ?? []) {
              if (scripted.event === "subagent.start" && typeof scripted.child_session_id === "string") {
                childUsages.set(scripted.child_session_id, { profile: profile!, parent: run.session_id, reads: 0, delayed: false });
              }
              event(response, scripted);
            }
            if (script.pause === true) {
              await new Promise<void>((resolve) => {
                longActivityGate = resolve;
                response.on("close", resolve);
              });
            }
            event(response, { event: "run.completed" });
            response.end();
            return;
          }
          // 실제 Hermes v0.21.0 이 보내는 형태다.
          // 사건 이름은 `event`, 조각은 `delta`, 도구 이름은 `tool`, 설명은 `preview` 다.
          // 여기가 실제와 어긋나면 테스트는 통과하는데 운영에서 조각이 흐르지 않는다.
          const streamedOutput = specialOutputFor(run.input);
          event(response, {
            event: "message.delta",
            delta: run.input === LONG_ACTIVITY_PROBE ? "긴 작업 과정"
              : streamedOutput === null ? "화면에서만 " : streamedOutput.slice(0, 80),
          });
          // 도구 줄이 많은 작업 과정이다. 시작만 한 도구 줄을 남기고 두 번 기다려, 검사가 도는 중인 블록의
          // 높이와 스크롤을 본다. 이 입력에는 다른 도구 사건과 하위 에이전트 사건을 보내지 않는다.
          if (run.input === LONG_ACTIVITY_PROBE) {
            for (let step = 1; step <= 30; step += 1) {
              event(response, { event: "tool.started", tool: "terminal", preview: `단계 ${step}` });
              event(response, { event: "tool.completed", tool: "terminal", duration: 0.1, error: false });
            }
            event(response, { event: "tool.started", tool: "terminal", preview: "단계 31" });
            await new Promise<void>((resolve) => {
              longActivityGate = resolve;
              response.on("close", resolve);
            });
            event(response, { event: "tool.completed", tool: "terminal", duration: 0.1, error: false });
            for (let step = 32; step <= 41; step += 1) {
              event(response, { event: "tool.started", tool: "terminal", preview: `단계 ${step}` });
              event(response, { event: "tool.completed", tool: "terminal", duration: 0.1, error: false });
            }
            event(response, { event: "tool.started", tool: "terminal", preview: "단계 42" });
            await new Promise<void>((resolve) => {
              longActivityGate = resolve;
              response.on("close", resolve);
            });
            event(response, { event: "tool.completed", tool: "terminal", duration: 0.1, error: false });
            for (let step = 43; step <= 52; step += 1) {
              event(response, { event: "tool.started", tool: "terminal", preview: `단계 ${step}` });
              event(response, { event: "tool.completed", tool: "terminal", duration: 0.1, error: false });
            }
            event(response, { event: "run.completed" });
            response.end();
            return;
          }
          // 중지 뒤에도 Hermes 사건 스트림이 닫히지 않는 경우를 재현한다. Control Plane 이 유예 시간 뒤
          // 이 연결을 직접 닫아야 한다.
          if (run.input === "중지 스트림 유지 검사") return;
          // 취소 뒤 Hermes 가 최종 output 을 비워도, 이미 화면으로 보낸 첫 조각은 남겨야 한다.
          if (run.input === "중지 빈 답 검사") {
            response.end();
            return;
          }
          if (run.interruptEvents) {
            response.end();
            return;
          }
          if (streamedOutput === null) {
            event(response, { event: "message.delta", delta: `보이는 조각: ${run.input}` });
          } else {
            for (let offset = 80; offset < streamedOutput.length; offset += 80) {
              if (run.input === "긴 답 스트림 검사") await wait(25);
              event(response, { event: "message.delta", delta: streamedOutput.slice(offset, offset + 80) });
            }
          }
          const redactDetail = run.input === "도구 가리기 검사" || run.input === "스트림 정본 검사";
          event(response, { event: "tool.started", tool: "fake-tool",
            preview: redactDetail ? TOOL_DETAIL_SAMPLE : "started" });
          event(response, { event: "tool.completed", tool: "fake-tool", duration: 0.1,
            result: redactDetail ? JSON.parse(TOOL_DETAIL_SAMPLE) : undefined,
            error: run.input === "병렬 하위 에이전트 검사" });
          event(response, { event: "tool.started", tool: "fake-reader", preview: "started" });
          event(response, { event: "tool.completed", tool: "fake-reader", duration: 0.25, error: false });
          // 모델이 스킬을 읽은 사건이다. 실제 Hermes 는 `skill_view` 의 `preview` 에 스킬 이름을 싣는다.
          if (run.input === "스킬 읽기 검사") {
            event(response, { event: "tool.started", tool: "skill_view", preview: "shopping" });
            event(response, { event: "tool.completed", tool: "skill_view", duration: 0.05, error: false });
          }
          // 스킬 커맨드로 바꾼 입력이다. 실제 Hermes 의 모델도 이 입력을 받으면 그 이름으로 `skill_view` 를 부른다.
          const commandedSkill = SKILL_VIEW_CALL.exec(run.input)?.[1];
          if (commandedSkill !== undefined) {
            event(response, { event: "tool.started", tool: "skill_view", preview: commandedSkill });
            event(response, { event: "tool.completed", tool: "skill_view", duration: 0.05, error: false });
          }
          // 하위 에이전트 사건은 도구 사건과 어미가 다르다. `.started` 와 `.completed` 가 아니다.
          // Hermes v0.21.0 은 여기에 session 번호를 싣지 않고 `preview` 만 보낸다.
          if (run.input === "자식 늦은 완료 검사" || run.input === "자식 완료 사건 없음 검사" || run.input === "압축 뒤 자식 완료 검사"
            || run.input === SUBAGENT_PROVIDER_PROBE) {
            const childSessionId = `child-${run.run_id}`;
            const parentSessionId = run.input === "압축 뒤 자식 완료 검사"
              ? `compacted-${run.session_id}`
              : run.session_id;
            childUsages.set(childSessionId, { profile: profile!, parent: parentSessionId,
              reads: 0, delayed: run.input === "자식 늦은 완료 검사",
              ...(run.input === SUBAGENT_PROVIDER_PROBE ? { model: "example-model-large", provider: "anthropic" } : {}) });
            const child = { subagent_id: `sa-${run.run_id}`, goal: "부모 뒤에 끝나는 조사",
              model: "example-fast", child_session_id: childSessionId };
            event(response, { event: "subagent.start", ...child });
            event(response, { event: "run.completed" });
            // 부모 스트림을 먼저 닫는다. 늦은 자식 완료는 첫 session 조회 뒤에만 보이며,
            // 이미 닫힌 부모 스트림에는 완료 사건을 전달할 수 없다.
            response.end();
            return;
          }
          if (run.input === "병렬 하위 에이전트 검사") {
            const first = { goal: "첫째 조사", child_session_id: "child-first" };
            const second = { goal: "둘째 조사", child_session_id: "child-second" };
            event(response, { event: "subagent.start", ...first });
            event(response, { event: "subagent.start", ...second });
            event(response, { event: "subagent.complete", ...second, model: "model-second",
              status: "completed", duration_seconds: 2, input_tokens: 200, output_tokens: 20 });
            event(response, { event: "subagent.complete", ...first, model: "model-first",
              status: "completed", duration_seconds: 1, input_tokens: 100, output_tokens: 10 });
          } else if (run.input === "하위 에이전트 칸 검사") {
            const subagent = { subagent_id: "sa-1", goal: "숙소 후보를 조사한다", model: "z-ai/glm-5.2", child_session_id: "child-1" };
            event(response, { event: "subagent.start", preview: "하위 에이전트가 찾기 시작했다", ...subagent });
            event(response, { event: "subagent.complete", preview: "하위 에이전트가 찾기를 마쳤다", ...subagent,
              status: "completed", duration_seconds: 1.5, input_tokens: 12300, output_tokens: 410 });
          } else {
            event(response, { event: "subagent.start", preview: "하위 에이전트가 찾기 시작했다" });
            event(response, { event: "subagent.complete", preview: "하위 에이전트가 찾기를 마쳤다" });
          }
          event(response, { event: "run.completed" });
          response.end();
          return;
        }
      }

      if (request.method === "POST") {
        const stopMatch = RUN_STOP_PATH.exec(path);
        if (stopMatch) {
          const [, profile, runId] = stopMatch;
          if (!authorized(request, profile!)) {
            return send(response, 401, { error: "bad key for this profile" });
          }
          const run = runs.get(runId!);
          if (!run) return send(response, 404, runNotFound(runId!));
          run.status = "cancelled";
          if (run.input === "중지 빈 답 검사" || run.input === "중지 조각 전 검사") run.output = "";
          emptyUntilStopped.get(runId!)?.end();
          emptyUntilStopped.delete(runId!);
          stoppedRuns.push(runId!);
          if (heldRunId === runId) releaseHeldRun();
          slowActive.delete(runId!);
          return send(response, 200, { status: "stopping" });
        }
        const match = RUN_PATH.exec(path);
        if (!match) return send(response, 404, { error: "not found" });
        const profile = match[1];
        if (!authorized(request, profile)) {
          return send(response, 401, { error: "bad key for this profile" });
        }
        submitCount += 1;
        // 한도에 닿은 gateway 는 본문을 읽기 전에 거절한다. 실행을 만들지 않는다.
        if (busy) return send(response, 429, RATE_LIMITED);

        const raw = await readBody(request);
        const submitted = (raw.length > 0 ? JSON.parse(raw) : {}) as {
          input?: string;
          instructions?: string;
          session_id?: string;
          provider?: string;
          model?: string;
          model_options?: { reasoning?: { effort?: string } };
        };
        const { folder: artifactFolder, conversationId: artifactConversationId, rest: input } = splitArtifactPreamble(submitted.input ?? "");
        // 추천을 만드는 실행은 Control Plane 이 새 대화 화면을 열 때마다 끼어든다. 대화 실행을 관찰하려는 검사가
        // 그 실행에 흔들리지 않도록 「마지막 제출」 기록을 덮어쓰지 않고 `holdNextRun` 도 가져가지 않는다.
        const starterRun = input.startsWith(STARTER_MARK);
        if (!starterRun) {
          lastSubmittedInstructions = submitted.instructions;
          // 되돌려 받는 쪽은 원문을 본다. 결과물 폴더 단락이 붙었는지 검사가 이것으로 안다.
          lastSubmittedInput = submitted.input;
        }
        if (input === ARTIFACT_PROBE && artifactFolder !== undefined) writeArtifactDraft(artifactFolder);
        if (input === ARTIFACT_SAME_NAME_PROBE && artifactFolder !== undefined) writeSameNameArtifacts(artifactFolder);
        if (input === ARTIFACT_WRITE_PROBE && artifactConversationId !== undefined) await writeArtifactViaMcp(artifactConversationId, submitted.session_id);
        const registeredChild = input.startsWith(SUBAGENT_MEMORY_PROBE)
          ? await registerSubagent(submitted.session_id)
          : undefined;
        const memoryReadPrefix = `${MEMORY_READ_PROBE} `;
        const memoryReadOutput = input.startsWith(memoryReadPrefix)
          ? await readMemoryViaMcp(Number(input.slice(memoryReadPrefix.length)), submitted.session_id)
          : undefined;
        const followUpProposePrefix = `${FOLLOW_UP_PROPOSE_PROBE} `;
        const followUpProposeOutput = input.startsWith(followUpProposePrefix)
          ? await callControlPlaneToolViaMcp(
            "follow_up_propose",
            JSON.parse(input.slice(followUpProposePrefix.length)) as Record<string, unknown>,
            submitted.session_id,
          )
          : undefined;
        const connectorOutput = input.startsWith(CONNECTOR_TOOL_PROBE)
          ? await judgeConnectorCalls(
            profile!,
            profiles.get(profile!)?.MCP_FOS_ASSISTANT_API_KEY,
            submitted.session_id,
            input.split("\n").slice(1).map((line) => line.trim()).filter((line) => line.length > 0),
            `connector-call-${submitCount}`,
          )
          : undefined;
        if (!starterRun) {
          lastSubmittedRuntime = {
            provider: submitted.provider,
            model: submitted.model,
            reasoningEffort: submitted.model_options?.reasoning?.effort,
          };
        }
        const runId = `run_${shortId()}`;
        const sessionId = submitted.session_id ?? `sess_${shortId()}`;

        // 실제 Hermes 는 provider 만 받으면 config 의 모델 문자열을 그대로 써서 실패한다.
        if (submitted.provider !== undefined && submitted.model === undefined) {
          runs.set(runId, {
            run_id: runId,
            status: "failed",
            session_id: sessionId,
            model: submitted.model ?? profile!,
            provider: submitted.provider ?? null,
            error: "No LLM provider configured. Run `hermes model` to select a provider.",
            output: "",
            input,
            interruptEvents: false,
            usage: FAKE_USAGE,
          });
          return send(response, 200, { run_id: runId, status: "queued" });
        }

        // 그 provider 의 계정이 전부 막힌 상태다. 접수는 되고 나중에 failed 로 바뀐다.
        if (submitted.provider !== undefined && blockedProviders.has(submitted.provider)) {
          runs.set(runId, {
            run_id: runId,
            status: "failed",
            session_id: sessionId,
            model: submitted.model ?? profile!,
            provider: submitted.provider,
            error: PROVIDER_AUTH_FAILED,
            output: "",
            input,
            interruptEvents: false,
            usage: FAKE_USAGE,
          });
          return send(response, 200, { run_id: runId, status: "queued" });
        }
        const echoed = withoutAskGuide(withoutResponseGuide(submitted.instructions ?? ""));
        const instructionsEcho = echoed.length > 0 ? ` [instructions: ${echoed}]` : "";
        // 살펴보기 실행은 넣어 둔 각본 하나를 가져간다. 각본이 없으면 기본 답을 준다.
        const proactiveRun = !starterRun && input.includes(PROACTIVE_CHECK_CALL);
        const script = proactiveRun ? proactiveScript : undefined;
        if (proactiveRun) {
          proactiveScript = undefined;
          proactiveInputs.push({ profile: profile!, input: submitted.input ?? "" });
        }
        const heldByNext = holdNextRun && !starterRun;
        if (heldByNext) holdNextRun = false;
        const held = heldByNext || script?.hold === true;
        const slow = !held && !starterRun && slowRunMs !== undefined;
        runs.set(runId, {
          run_id: runId,
          status: held || slow ? "running" : "completed",
          session_id: sessionId,
                  // 실제 Hermes 와 같이 요청 본문의 값을 그대로 되돌려 준다. 실제로 돈 모델이 아니다.
          model: submitted.model ?? profile!,
          output: (proactiveRun ? script?.output ?? defaultProactiveOutput() : undefined)
            ?? memoryReadOutput
            ?? followUpProposeOutput
            ?? connectorOutput
            ?? (registeredChild === undefined ? undefined : `하위 에이전트 session: ${registeredChild}`)
            ?? scripts.get(input)?.output
            ?? specialOutputFor(input)
            ?? `[${who} on profile ${profile}]${instructionsEcho} ${input}`,
          input,
          provider: submitted.provider ?? null,
          interruptEvents: input === "스트림 중단 검사",
          usage: FAKE_USAGE,
          proactive: proactiveRun
            ? {
                tools: script === undefined ? DEFAULT_PROACTIVE_TOOLS : script.tools ?? [],
                gate: script?.waitBeforeEvents === true ? proactiveGate : undefined,
              }
            : undefined,
        });
        // 실제로 돈 모델은 세션 행과 v0.21.5 실행 조회의 runtime 에 남는다.
        // provider 와 모델을 빼고 온 실행은 profile 의 기본값으로 돈다.
        const served = {
          model: actualModelFor(input, submitted.model ?? DEFAULT_RUNTIME.model),
          provider:
            input === SESSION_MODEL_PROBE ? "nvidia" : submitted.provider ?? DEFAULT_RUNTIME.provider,
        };
        sessions.set(sessionId, served);
        const stored = runs.get(runId);
        if (stored) stored.runtime = { provider: served.provider, model: served.model, route_source: "global" };
        if (held) {
          heldRunId = runId;
          heldRunWaiter?.();
        }
        if (slow) {
          slowActive.set(runId, profile!);
          maxTotalConcurrency = Math.max(maxTotalConcurrency, slowActive.size);
          const sameProfile = [...slowActive.values()].filter((name) => name === profile).length;
          maxProfileConcurrency.set(profile!, Math.max(maxProfileConcurrency.get(profile!) ?? 0, sameProfile));
          setTimeout(() => finishSlowRun(runId), slowRunMs);
        }
        return send(response, 200, { run_id: runId, status: "queued" });
      }

      const match = RUN_STATUS_PATH.exec(path);
      if (!match) return send(response, 404, { error: "not found" });
      const [, profile, runId] = match;
      if (!authorized(request, profile)) {
        return send(response, 401, { error: "bad key for this profile" });
      }
      const run = runs.get(runId);
      if (!run) return send(response, 404, runNotFound(runId!));
      return send(response, 200, run);
    })();
  });

  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (address === null || typeof address === "string") {
        reject(new Error("fake Hermes 의 포트를 알 수 없다"));
        return;
      }
      resolve({
        baseUrl: `http://127.0.0.1:${address.port}`,
        lastSubmittedInstructions: () => lastSubmittedInstructions,
        lastSubmittedInput: () => lastSubmittedInput,
        modelOptionsCalls: () => modelOptionsCalls,
        lastSubmittedRuntime: () => lastSubmittedRuntime,
        blockProvider: (provider: string) => blockedProviders.add(provider),
        clearBlockedProviders: () => blockedProviders.clear(),
        busy: () => {
          busy = true;
        },
        clearBusy: () => {
          busy = false;
        },
        submitCount: () => submitCount,
        profiles: () => [...profiles.keys()],
        profileEnv: (name: string) => ({ ...(profiles.get(name) ?? hostEnv.get(name) ?? {}) }),
        soulOf: (name: string) => souls.get(name),
        skillDirsOf: (name: string) => [...(externalDirs.get(name) ?? [])],
        apiServerToolsetsOf: (name: string) => {
          const toolsets = apiServerToolsets.get(name);
          return toolsets === undefined ? undefined : [...toolsets];
        },
        connectorRequests: () => [...connectorRequests],
        setPolicyHook: (profile: string, active: boolean) => {
          if (active) policyHookOff.delete(profile);
          else policyHookOff.add(profile);
        },
        setConnectorPolicy: (endpoint: string) => {
          connectorPolicyEndpoint = endpoint;
        },
        connectorToolCalls: () => connectorToolCalls.map((entry) => ({ ...entry })),
        callConnectorTools: (profile: string, sessionId: string, lines: readonly string[], token: string) => {
          directConnectorCalls += 1;
          return judgeConnectorCalls(profile, token, sessionId, lines, `connector-direct-${directConnectorCalls}`);
        },
        boundConnectorsOf: (profile: string) => [...(boundConnectors.get(profile)?.keys() ?? [])],
        holdNextRun: () => {
          holdNextRun = true;
          heldRunReady = new Promise<void>((done) => {
            heldRunWaiter = done;
          });
        },
        slowRuns: (ms: number | undefined) => {
          slowRunMs = ms;
        },
        runConcurrency: () => ({
          maxTotal: maxTotalConcurrency,
          maxByProfile: Object.fromEntries(maxProfileConcurrency),
        }),
        resetRunConcurrency: () => {
          maxTotalConcurrency = 0;
          maxProfileConcurrency.clear();
        },
        waitForHeldRun: () => heldRunReady ?? Promise.reject(new Error("유지할 실행을 먼저 지정해야 한다")),
        releaseHeldRun: () => {
          holdNextRun = false;
          const releasedRunId = releaseHeldRun();
          if (releasedRunId === undefined) return;
          const run = runs.get(releasedRunId);
          if (run !== undefined) run.status = "completed";
        },
        forgetRun: (runId: string) => {
          runs.delete(runId);
        },
        waitForRunEvents: (runId: string) =>
          eventsOpened.has(runId)
            ? Promise.resolve()
            : new Promise<void>((done) => {
                eventsWaiters.set(runId, [...(eventsWaiters.get(runId) ?? []), done]);
              }),
        heldRun: () => {
          const run = heldRunId === undefined ? undefined : runs.get(heldRunId);
          return run === undefined ? undefined : { runId: run.run_id, sessionId: run.session_id };
        },
        releaseLongActivity,
        holdNextSoul: () => {
          holdNextSoul = true;
        },
        releaseHeldSoul,
        stoppedRuns: () => [...stoppedRuns],
        holdNextConfig: () => {
          holdNextConfig = true;
          heldConfigReady = new Promise<void>((done) => { heldConfigWaiter = done; });
        },
        waitForHeldConfig: () => heldConfigReady ?? Promise.reject(new Error("유지할 설정을 먼저 지정해야 한다")),
        releaseHeldConfig: () => releaseConfig?.(),
        dropNextAppliedToolset: (name) => { droppedToolset = name; },
        setArtifactWriteMcp: (endpoint: string, token: string) => {
          artifactWriteMcp = { endpoint, token };
        },
        setMemoryReadMcp: (endpoint: string, token: string) => {
          memoryReadMcp = { endpoint, token };
        },
        subagentRegistrations: () => [...subagentRegistrations],
        readMemoryAsSubagent: (childSessionId, memoryId) => {
          const registered = subagentRegistrations.find((entry) => entry.childSessionId === childSessionId);
          if (registered === undefined) return Promise.reject(new Error(`등록한 적 없는 자식 session 이다: ${childSessionId}`));
          return readMemoryViaMcp(memoryId, childSessionId, registered.rootSessionId);
        },
        readMemoryAsUnregisteredSubagent: (rootSessionId, memoryId) =>
          readMemoryViaMcp(memoryId, `native-${randomUUID()}`, rootSessionId),
        setProactiveScript: (script: ProactiveScript) => {
          proactiveScript = { ...script, tools: [...(script.tools ?? [])] };
          proactiveGate = script.waitBeforeEvents === true
            ? new Promise<void>((done) => {
                openProactiveGate = done;
              })
            : undefined;
          if (script.hold === true) {
            heldRunReady = new Promise<void>((done) => {
              heldRunWaiter = done;
            });
          }
        },
        releaseProactiveEvents: () => openProactiveGate?.(),
        proactiveInputs: () => proactiveInputs.map((entry) => ({ ...entry })),
        close: () =>
          new Promise<void>((done) => {
            holdNextRun = false;
            releaseHeldRun();
            releaseLongActivity();
            openProactiveGate?.();
            holdNextSoul = false;
            heldSoul = undefined;
            server.closeAllConnections();
            server.close(() => done());
          }),
      });
    });
  });
}
