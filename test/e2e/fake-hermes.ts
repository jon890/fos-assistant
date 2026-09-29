/**
 * 홈서버 없이 돌리기 위한 Hermes Runs API 대역이다.
 *
 * <p>Control Plane 이 실제로 부르는 것만 구현한다. profile 경로로 실행을 제출하고, 그 실행의 상태와
 * 토큰 수를 돌려준다. profile 마다 bearer key 도 검사한다. 라우팅을 잘못하면 조용히 성공하는 대신
 * 여기서 401 이 나게 하기 위해서다.
 */
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { randomUUID } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { signedCallContext, signedSubagentRegistration } from "./mcp-context.ts";

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
const ENV_PATH = "/api/env";
const TOOLSET_CATALOG_PATH = "/api/tools/toolsets";
const CONFIG_PATH = "/api/config";
const ENABLED_TOOLSETS_PATH = /^\/p\/([a-z0-9-]+)\/v1\/toolsets$/;
const TEST_BLOCK_PROVIDER_PATH = /^\/__test\/block-provider\/([a-z0-9-]+)$/;
const TEST_CLEAR_BLOCKED_PATH = "/__test/clear-blocked-providers";
const TEST_HOLD_NEXT_RUN_PATH = "/__test/hold-next-run";
const TEST_WAIT_HELD_RUN_PATH = "/__test/wait-held-run";
const TEST_RELEASE_HELD_RUN_PATH = "/__test/release-held-run";
/** 다음 `GET /api/profiles/{이름}/soul` 응답을 붙잡아 화면의 뼈대 검사가 서버를 실제로 늦출 수 있게 한다. */
const TEST_HOLD_NEXT_SOUL_PATH = "/__test/hold-next-soul";
const TEST_RELEASE_HELD_SOUL_PATH = "/__test/release-held-soul";
const TEST_BUSY_PATH = "/__test/busy";
const TEST_CLEAR_BUSY_PATH = "/__test/clear-busy";
const TEST_HOLD_NEXT_CONFIG_PATH = "/__test/hold-next-config";
const TEST_RELEASE_HELD_CONFIG_PATH = "/__test/release-held-config";
/** 마지막 실행 요청이 실어 온 provider, 모델, effort 를 돌려준다. 브라우저 검사는 대역을 다른 프로세스에서 띄워 이 길로 묻는다. */
const TEST_LAST_SUBMITTED_RUNTIME_PATH = "/__test/last-submitted-runtime";

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

/** Control Plane 이 모든 실행 입력 맨 앞에 붙이는 결과물 폴더 단락의 첫 줄이다. `ArtifactService` 와 같아야 한다. */
const ARTIFACT_HEADER = "[결과물 폴더]";

/**
 * 입력 맨 앞의 결과물 폴더 단락을 떼고, 단락의 둘째 줄인 폴더를 함께 돌려준다.
 *
 * <p>단락은 빈 줄 하나로 끝난다. 맨 앞에 단락이 없으면 아무것도 떼지 않는다. Chief 는 요청 본문 안에서 이 단락을
 * 받아 맨 앞이 아니므로 그대로 둔다. 떼지 않으면 입력을 글자 그대로 견주는 분기와 입력을 되돌려 주는 답이 모두
 * 어긋난다.
 */
function splitArtifactPreamble(input: string): { folder?: string; conversationId?: string; rest: string } {
  if (!input.startsWith(`${ARTIFACT_HEADER}\n`)) return { rest: input };
  const end = input.indexOf("\n\n");
  if (end < 0) return { rest: input };
  const lines = input.slice(0, end).split("\n");
  const identifier = lines.find((line) => line.startsWith("대화 식별자: "));
  return { folder: lines[1], conversationId: identifier?.slice("대화 식별자: ".length), rest: input.slice(end + 2) };
}

/** 이 글을 보내면 결과물 폴더에 HTML 과 그것이 부르는 사진을 쓰고 답한다. */
export const ARTIFACT_PROBE = "결과물 파일 검사";

/** 실제 MCP 호출로 HTML과 CSS를 저장하는지 보는 입력이다. */
export const ARTIFACT_WRITE_PROBE = "MCP 결과물 파일 검사";

/**
 * 이 글 뒤에 공백과 Memory 번호를 붙여 보내면 그 run 안에서 `memory_read` 를 부르고, 도구 결과의 text 를 답으로 돌려준다.
 */
export const MEMORY_READ_PROBE = "MCP Memory 읽기 검사";

/**
 * 이 글로 시작하는 입력을 받으면 그 run 안에서 하위 에이전트 session 을 등록하고, 답으로 자식 session 을 돌려준다.
 *
 * <p>실제 플러그인의 `subagent_start` hook 처럼 부모 run 이 끝나기 전에 등록을 마친다. 자식의 도구 호출은 시나리오가
 * 부모가 끝난 뒤 `readMemoryAsSubagent` 로 부른다.
 */
export const SUBAGENT_MEMORY_PROBE = "MCP 하위 에이전트 검사";

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

/** 묻는 카드를 그리는지 보는 검사가 보내는 글이다. 이 글에는 답 끝에 `<ask>` 를 둔 답을 준다. */
export const ASK_CARD_PROBE = "묻는 카드 검사";

function specialOutputFor(input: string): string | null {
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
  if (input === ARTIFACT_PROBE) {
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
  holdNextRun(): void;
  waitForHeldRun(): Promise<void>;
  releaseHeldRun(): void;
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
  /** 등록한 자식 session 이 부모의 뿌리로 서명해 `memory_read` 를 부르고 도구 결과의 text 를 돌려준다. */
  readMemoryAsSubagent(childSessionId: string, memoryId: number): Promise<string>;
  /** 등록하지 않은 자식 session 으로 그 뿌리에 서명해 `memory_read` 를 부른다. */
  readMemoryAsUnregisteredSubagent(rootSessionId: string, memoryId: number): Promise<string>;
};

/**
 * fake Hermes 를 띄우고 그 주소를 돌려준다.
 *
 * @param profileKeys profile 이름과 그 profile 의 API server key
 * @param label 이 대역을 다른 대역과 구분하는 이름. 실행의 답에 그대로 실린다. 주소를 옮기는 검사가
 *     답이 어느 대역에서 왔는지 보는 데 쓴다
 */
export function startFakeHermes(
  profileKeys: Record<string, string>,
  label?: string,
  initialApiServerToolsets: Record<string, string[]> = {},
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
  const runs = new Map<string, Run>();
  const sessions = new Map<string, Session>();
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
  const blockedProviders = new Set<string>();
  let busy = false;
  let submitCount = 0;
  let modelOptionsCalls = 0;
  let lastSubmittedRuntime: { provider?: string; model?: string; reasoningEffort?: string } = {};
  let holdNextRun = false;
  let heldRunId: string | undefined;
  let heldRunWaiter: (() => void) | undefined;
  let heldRunReady: Promise<void> | undefined;
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

  /**
   * profile 플러그인처럼 서명한 `_fos_ctx` 를 붙여 `memory_read` 를 부르고 도구 결과의 text 를 돌려준다.
   *
   * <p>제출받은 run 의 session 이 곧 뿌리 session 이다. run 마다 자기 session 으로 서명하므로, 나란히 도는 두 run 이
   * 서로의 session 을 쓰면 요청자가 뒤섞여 검사가 실패한다. 하위 에이전트는 뿌리와 자기 session 을 따로 준다.
   */
  const readMemoryViaMcp = async (
    memoryId: number,
    sessionId: string | undefined,
    rootSessionId: string | undefined = sessionId,
  ): Promise<string> => {
    if (memoryReadMcp === undefined) throw new Error("memory_read MCP runtime is not configured");
    if (sessionId === undefined || rootSessionId === undefined) {
      throw new Error("memory_read MCP needs the submitted session_id to sign _fos_ctx");
    }
    const _fos_ctx = signedCallContext(memoryReadMcp.token, "memory_read", rootSessionId, sessionId, `call_${randomUUID()}`);
    const response = await fetch(memoryReadMcp.endpoint, {
      method: "POST",
      headers: { Authorization: `Bearer ${memoryReadMcp.token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "memory_read", arguments: { id: memoryId, _fos_ctx } } }),
    });
    if (!response.ok) throw new Error(`memory_read MCP HTTP ${response.status}`);
    const body = await response.json() as { result?: { content?: { text?: string }[] } };
    const text = body.result?.content?.[0]?.text;
    if (text === undefined) throw new Error("memory_read MCP response has no text");
    return text;
  };

  /**
   * profile 플러그인의 `subagent_start` hook 처럼 자식 session 을 부모의 뿌리 아래 등록한다.
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
   * <p>제출받은 run 의 session 이 곧 뿌리 session 이다. 하위 에이전트가 아니므로 session 과 뿌리가 같다.
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
    const profileMatch = PROFILE_PATH.exec(path);
    const isDashboardPath = path === PROFILES_PATH || path === ENV_PATH || path === TOOLSET_CATALOG_PATH
      || path === CONFIG_PATH || profileMatch !== null;
    if (!isDashboardPath) return false;

    if (!dashboardAuthorized(request)) {
      send(response, 401, { reason: "no_token" });
      return true;
    }

    if (request.method === "GET" && path === TOOLSET_CATALOG_PATH) {
      send(response, 200, TOOLSET_CATALOG.map((toolset) => ({ ...toolset, enabled: false })));
      return true;
    }

    if (request.method === "PUT" && path === CONFIG_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as {
        profile?: string;
        config?: {
          platform_toolsets?: { api_server?: unknown };
        };
      };
      const toolsets = body.config?.platform_toolsets?.api_server;
      const validNames = new Set([...TOOLSET_CATALOG.map((entry) => entry.name), CONTROL_PLANE_MCP]);
      const exactKeys = Object.keys(body).length === 2 && Object.keys(body).every((key) => key === "profile" || key === "config")
        && Object.keys(body.config ?? {}).length === 1
        && Object.keys(body.config ?? {}).every((key) => key === "platform_toolsets")
        && Object.keys(body.config?.platform_toolsets ?? {}).length === 1;
      if (body.profile === undefined || queryProfile !== null && queryProfile !== body.profile
          || !keys[body.profile] || !Array.isArray(toolsets)
          || !toolsets.includes(CONTROL_PLANE_MCP) || !toolsets.every((name) => typeof name === "string" && validNames.has(name))
          || !exactKeys) {
        send(response, 400, { error: "invalid toolset configuration" });
        return true;
      }
      if (holdNextConfig) {
        holdNextConfig = false;
        heldConfigWaiter?.();
        await new Promise<void>((done) => { releaseConfig = done; });
        releaseConfig = undefined;
      }
      apiServerToolsets.set(body.profile, toolsets.filter((name) => name !== droppedToolset));
      droppedToolset = undefined;
      send(response, 200, { ok: true });
      return true;
    }

    // `PROFILE_PATH` 의 `.+` 가 이 경로도 함께 먹으므로 그 분기보다 앞에서 처리한다.
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
      // 이 칸으로 들어온 값이 그 profile 의 key 가 된다. 대역이 스스로 만들면 Control Plane 이 key
      // 파일에 쓴 값과 어긋나 그 profile 의 실행이 401 을 받는다.
      if (body.key === API_KEY_ENV_NAME) keys[body.profile!] = body.value;
      send(response, 200, { profile: body.profile, key: body.key });
      return true;
    }

    if (request.method === "DELETE" && profileMatch !== null) {
      const name = decodeURIComponent(profileMatch[1]!);
      if (!profiles.delete(name)) {
        send(response, 404, { error: "no such profile" });
        return true;
      }
      delete keys[name];
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
                models: [DEFAULT_RUNTIME.model, "example-model-mini"],
                capabilities: {
                  [DEFAULT_RUNTIME.model]: { reasoning: true },
                  "example-model-mini": { reasoning: false },
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
          const run = runs.get(runId);
          if (!run) return send(response, 404, { error: "no such run" });
          response.writeHead(200, {
            "Content-Type": "text/event-stream; charset=utf-8",
            "Cache-Control": "no-cache",
            Connection: "keep-alive",
          });
          response.write(": keepalive\n\n");
          if (run.input === "중지 조각 전 검사" && run.status !== "completed") {
            if (run.status === "cancelled") {
              response.end();
            } else {
              emptyUntilStopped.set(runId!, response);
              response.on("close", () => emptyUntilStopped.delete(runId!));
            }
            return;
          }
          // 실제 Hermes v0.21.0 이 보내는 형태다.
          // 사건 이름은 `event`, 조각은 `delta`, 도구 이름은 `tool`, 설명은 `preview` 다.
          // 여기가 실제와 어긋나면 테스트는 통과하는데 운영에서 조각이 흐르지 않는다.
          const streamedOutput = specialOutputFor(run.input);
          event(response, {
            event: "message.delta",
            delta: streamedOutput === null ? "화면에서만 " : streamedOutput.slice(0, 80),
          });
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
          event(response, { event: "tool.started", tool: "fake-tool", preview: "started" });
          event(response, { event: "tool.completed", tool: "fake-tool", duration: 0.1,
            error: run.input === "병렬 하위 에이전트 검사" });
          event(response, { event: "tool.started", tool: "fake-reader", preview: "started" });
          event(response, { event: "tool.completed", tool: "fake-reader", duration: 0.25, error: false });
          // 하위 에이전트 사건은 도구 사건과 어미가 다르다. `.started` 와 `.completed` 가 아니다.
          // Hermes v0.21.0 은 여기에 session 번호를 싣지 않고 `preview` 만 보낸다.
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
          if (!run) return send(response, 404, { error: "no such run" });
          run.status = "cancelled";
          if (run.input === "중지 빈 답 검사" || run.input === "중지 조각 전 검사") run.output = "";
          emptyUntilStopped.get(runId!)?.end();
          emptyUntilStopped.delete(runId!);
          stoppedRuns.push(runId!);
          if (heldRunId === runId) releaseHeldRun();
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
        lastSubmittedInstructions = submitted.instructions;
        // 되돌려 받는 쪽은 원문을 본다. 결과물 폴더 단락이 붙었는지 검사가 이것으로 안다.
        lastSubmittedInput = submitted.input;
        const { folder: artifactFolder, conversationId: artifactConversationId, rest: input } = splitArtifactPreamble(submitted.input ?? "");
        if (input === ARTIFACT_PROBE && artifactFolder !== undefined) writeArtifactDraft(artifactFolder);
        if (input === ARTIFACT_WRITE_PROBE && artifactConversationId !== undefined) await writeArtifactViaMcp(artifactConversationId, submitted.session_id);
        const registeredChild = input.startsWith(SUBAGENT_MEMORY_PROBE)
          ? await registerSubagent(submitted.session_id)
          : undefined;
        const memoryReadPrefix = `${MEMORY_READ_PROBE} `;
        const memoryReadOutput = input.startsWith(memoryReadPrefix)
          ? await readMemoryViaMcp(Number(input.slice(memoryReadPrefix.length)), submitted.session_id)
          : undefined;
        lastSubmittedRuntime = {
          provider: submitted.provider,
          model: submitted.model,
          reasoningEffort: submitted.model_options?.reasoning?.effort,
        };
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
        const echoed = withoutAskGuide(submitted.instructions ?? "");
        const instructionsEcho = echoed.length > 0 ? ` [instructions: ${echoed}]` : "";
        const held = holdNextRun;
        holdNextRun = false;
        runs.set(runId, {
          run_id: runId,
          status: held ? "running" : "completed",
          session_id: sessionId,
                  // 실제 Hermes 와 같이 요청 본문의 값을 그대로 되돌려 준다. 실제로 돈 모델이 아니다.
          model: submitted.model ?? profile!,
          output: memoryReadOutput
            ?? (registeredChild === undefined ? undefined : `하위 에이전트 session: ${registeredChild}`)
            ?? specialOutputFor(input)
            ?? `[${who} on profile ${profile}]${instructionsEcho} ${input}`,
          input,
          provider: submitted.provider ?? null,
          interruptEvents: input === "스트림 중단 검사",
          usage: FAKE_USAGE,
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
        return send(response, 200, { run_id: runId, status: "queued" });
      }

      const match = RUN_STATUS_PATH.exec(path);
      if (!match) return send(response, 404, { error: "not found" });
      const [, profile, runId] = match;
      if (!authorized(request, profile)) {
        return send(response, 401, { error: "bad key for this profile" });
      }
      const run = runs.get(runId);
      if (!run) return send(response, 404, { error: "no such run" });
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
        profileEnv: (name: string) => ({ ...(profiles.get(name) ?? {}) }),
        soulOf: (name: string) => souls.get(name),
        holdNextRun: () => {
          holdNextRun = true;
          heldRunReady = new Promise<void>((done) => {
            heldRunWaiter = done;
          });
        },
        waitForHeldRun: () => heldRunReady ?? Promise.reject(new Error("유지할 실행을 먼저 지정해야 한다")),
        releaseHeldRun: () => {
          holdNextRun = false;
          const releasedRunId = releaseHeldRun();
          if (releasedRunId === undefined) return;
          const run = runs.get(releasedRunId);
          if (run !== undefined) run.status = "completed";
        },
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
        close: () =>
          new Promise<void>((done) => {
            holdNextRun = false;
            releaseHeldRun();
            holdNextSoul = false;
            heldSoul = undefined;
            server.closeAllConnections();
            server.close(() => done());
          }),
      });
    });
  });
}
