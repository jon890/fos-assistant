import { FAKE_CONNECTORS } from "./connectors.ts";
import { type ServerResponse } from "node:http";
import { FAKE_USAGE } from "./runtime-fixtures.ts";

/** 서버 하나가 소유하는 시험 상태다. 다른 서버와 공유하지 않는다. */
export function createFakeHermesState(
  profileKeys: Record<string, string>,
  label: string | undefined,
  initialApiServerToolsets: Record<string, string[]>,
) {
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
  /** profile 이름과 전역으로 끈 스킬 이름들이다. */
  const disabledSkills = new Map<string, Set<string>>();
  const blockedProviders = new Set<string>();
  /** 입력 글과 그 입력의 대본이다. */
  const scripts = new Map<string, DemoScript>();
  let busy = false;
  let readinessOutage: "busy" | "unavailable" | "timeout" | undefined;
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
  let sandboxUnavailable = false;
  let lastSandboxOwner: string | null = null;
  let heldConfigWaiter: (() => void) | undefined;
  let heldConfigReady: Promise<void> | undefined;
  let releaseConfig: (() => void) | undefined;
  let droppedToolset: string | undefined;
  let artifactWriteMcp: { endpoint: string; token: string } | undefined;
  let memoryReadMcp: { endpoint: string; token: string } | undefined;
  const memoryRememberCalls = new Map<string, Record<string, unknown>>();
  const outsideToolInputs = new Set<string>();
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
  return {
    who,
    keys: keys as Record<string, string>,
    runs,
    sessions,
    childUsages,
    profiles,
    apiServerToolsets,
    souls,
    externalDirs,
    installedConnectors,
    connectorRequests: connectorRequests as string[],
    policyHookInstalled,
    policyHookOff,
    connectorEnvNames,
    vaults,
    boundConnectors,
    hostEnv,
    disabledSkills,
    blockedProviders,
    scripts,
    busy,
    readinessOutage: readinessOutage as "busy" | "unavailable" | "timeout" | undefined,
    submitCount,
    modelOptionsCalls,
    lastSubmittedRuntime: lastSubmittedRuntime as { provider?: string; model?: string; reasoningEffort?: string },
    holdNextRun,
    slowRunMs: slowRunMs as number | undefined,
    slowActive,
    maxTotalConcurrency,
    maxProfileConcurrency,
    heldRunId: heldRunId as string | undefined,
    heldRunWaiter: heldRunWaiter as (() => void) | undefined,
    heldRunReady: heldRunReady as Promise<void> | undefined,
    eventsOpened,
    eventsWaiters,
    longActivityGate: longActivityGate as (() => void) | undefined,
    holdNextSoul,
    heldSoul: heldSoul as { response: ServerResponse; payload: unknown } | undefined,
    stoppedRuns: stoppedRuns as string[],
    emptyUntilStopped,
    lastSubmittedInstructions: lastSubmittedInstructions as string | undefined,
    lastSubmittedInput: lastSubmittedInput as string | undefined,
    holdNextConfig,
    sandboxUnavailable,
    lastSandboxOwner: lastSandboxOwner as string | null,
    heldConfigWaiter: heldConfigWaiter as (() => void) | undefined,
    heldConfigReady: heldConfigReady as Promise<void> | undefined,
    releaseConfig: releaseConfig as (() => void) | undefined,
    droppedToolset: droppedToolset as string | undefined,
    artifactWriteMcp: artifactWriteMcp as { endpoint: string; token: string } | undefined,
    memoryReadMcp: memoryReadMcp as { endpoint: string; token: string } | undefined,
    memoryRememberCalls,
    outsideToolInputs,
    subagentRegistrations: subagentRegistrations as { childSessionId: string; rootSessionId: string; status: number }[],
    connectorPolicyEndpoint: connectorPolicyEndpoint as string | undefined,
    connectorToolCalls: connectorToolCalls as ConnectorToolCall[],
    directConnectorCalls,
    proactiveScript: proactiveScript as ProactiveScript | undefined,
    proactiveGate: proactiveGate as Promise<void> | undefined,
    openProactiveGate: openProactiveGate as (() => void) | undefined,
    proactiveInputs: proactiveInputs as { profile: string; input: string }[],
  };
}
export type FakeHermesState = ReturnType<typeof createFakeHermesState>;

/** 대역이 흉내 내는 커넥터 칸이다. 두 시험 커넥터의 선언이 함께 맞는 모양이다. */
export type FakeConnectorField = {
  key: string;
  env: string;
  required: boolean;
  secret?: boolean;
  pattern?: string;
};

/** 대역이 흉내 내는 커넥터 선언이다. 칸, 확인 도구, 서버 이름, 도구 이름만 본다. */
export type FakeConnector = {
  id: string;
  fields: readonly FakeConnectorField[];
  verify: { tool: string };
  mcp_server: string;
  toolsets: readonly string[];
  tools: Record<string, unknown>;
};

export type Run = {
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
  /** 정책이 허용해 커넥터 서버에 닿은 호출이다. 사건 스트림이 호출마다 시작과 완료 사건을 실제 Hermes 의 모양으로 흘린다. */
  connectorCalls?: ConnectorCall[];
  /** 바깥 도구를 쓰는 run 이다. 사건 스트림이 `web_search` 도구 사건을 흘린 뒤 끝난다. */
  outsideTool?: boolean;
};

/** 허용된 커넥터 도구 호출 하나다. `hermesTool` 은 등록 이름이다. */
export type ConnectorCall = { hermesTool: string; argsJson: string };

/**
 * 세션 하나가 마지막으로 실제로 쓴 provider 와 모델이다.
 *
 * <p>실제 Hermes 에서 이 값만 넘김이 일어난 뒤의 모델을 담는다. `GET /v1/runs/{id}` 는 담지 않는다.
 */
export type Session = { model: string; provider: string | null };

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

export type ConnectorToolCall = { profile: string; hermesTool: string; argsJson: string; via: "hook" | "execute" };

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
  /** 준비 상태의 API 실행 toolset 조회를 429, 503, 또는 읽기 timeout 으로 실패시킨다. */
  setReadinessOutage(outage: "busy" | "unavailable" | "timeout" | undefined): void;
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
  /**
   * 이 입력을 받은 run 이 `memory_remember` 를 서명해 부르고 도구 결과의 text 를 답으로 돌려주게 한다.
   *
   * <p>사용자가 말한 입력 그대로를 질문으로 보내야 하므로 입력에 인자를 싣지 않고 따로 등록한다.
   */
  setMemoryRememberCall(input: string, args: Record<string, unknown>): void;
  /** 이 입력을 받은 run 의 사건 스트림이 `web_search` 도구 사건을 흘린 뒤 끝나게 한다. 바깥 글을 읽은 대화를 만든다. */
  setOutsideToolRun(input: string): void;
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
