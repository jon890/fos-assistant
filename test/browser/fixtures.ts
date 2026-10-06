import { spawn, type ChildProcess } from "node:child_process";
import { createWriteStream } from "node:fs";
import {
  chmod,
  mkdir,
  mkdtemp,
  readFile,
  rm,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { encode } from "../../web/node_modules/next-auth/jwt.js";
import { SignJWT } from "../../web/node_modules/jose/dist/webapi/index.js";
import playwright, {
  type BrowserContext,
} from "../../web/node_modules/@playwright/test/index.js";
import {
  DEMO_CONNECTOR,
  DEMO_TOKEN_OK,
  FAKE_DASHBOARD_TOKEN,
  startFakeHermes,
  type FakeHermes,
} from "../e2e/fake-hermes.ts";
import {
  AUTH_SECRET,
  CONTROL_PLANE_BASE_URL,
  CONTROL_PLANE_PORT,
  JWT_SECRET,
  RUN_ID,
  TEST_EMAIL,
  WEB_BASE_URL,
} from "./settings.ts";

const { expect, test: base } = playwright;

const ROOT = join(import.meta.dirname, "../..");
const SESSION_COOKIE = "authjs.session-token";
const HEALTH_TIMEOUT_MS = 90_000;
const HERMES_CONTROL_PATH = join(
  tmpdir(),
  `fos-assistant-browser-hermes-${RUN_ID}.url`,
);

/** 흐름이 붙은 에이전트의 코드다. 흐름 검사가 이 코드로 대화를 시작한다. */
export const FLOW_AGENT_CODE = "browserflow";

/** 막힌 모델을 고른 대화를 검사하는 전용이다. 여기서만 막힌 provider 를 만든다. */
export const SWITCH_AGENT_CODE = "browserswitch";

/** 성격을 읽고 쓰는 검사 전용이다. `TEST_EMAIL` 이 주인이라 admin 세션이 언제나 고칠 수 있다. */
export const PERSONA_AGENT_CODE = "browserpersona";

/**
 * 「고칠 수 없는 에이전트」 검사가 잠깐 그룹에 공개하는 에이전트다.
 *
 * <p>씨 뿌릴 때는 다른 에이전트와 같이 `PRIVATE` 이고 주인이 `TEST_EMAIL` 이다. 그 검사가
 * `setAgentVisibility` 로 `GROUP` 으로 바꿔 `MEMBER` 세션에서 읽기 전용으로 열고, 끝나면 되돌린다.
 * 여기서 바로 `GROUP` 으로 씨 루트면 관리 화면에 그룹 공개 에이전트가 하나 늘어, 정확히 하나만
 * 있다고 가정하는 `identity.spec.ts` 가 어긋난다.
 */
export const PERSONA_GROUP_AGENT_CODE = "browserpersonagroup";

/**
 * 본문이 비어 있는 채로 두는 검사 전용이다.
 *
 * <p>다른 성격 검사가 저장을 걸어 두면 이 에이전트까지 함께 비어 있지 않게 될 수 있어 따로 둔다. 이
 * 에이전트에는 끝까지 아무도 쓰지 않는다.
 */
export const PERSONA_EMPTY_AGENT_CODE = "browserpersonaempty";

/** 대화 공개 식별자(UUID)의 정규식 조각이다. 대화 번호는 주소와 API 에 나오지 않는다. */
export const CONVERSATION_ID =
  "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

/** 대화 화면의 주소다. 첫째 묶음이 공개 식별자다. */
export const CONVERSATION_URL = new RegExp(`/chat/(${CONVERSATION_ID})$`);

/** 대화 화면 주소에서 공개 식별자를 꺼낸다. 대화 화면이 아니면 실패한다. */
export function conversationIdOf(url: string): string {
  const id = new URL(url).pathname.match(CONVERSATION_URL)?.[1];
  if (!id) throw new Error(`대화 화면 주소가 아니다: ${url}`);
  return id;
}

/** profile 이름과 그 profile 의 key 다. 가짜 Hermes 와 key 디렉터리가 같은 표를 쓴다. */
const PROFILE_KEYS: Record<string, string> = {
  browser: "browser-profile-key",
  browserflow: "browser-flow-profile-key",
  browserswitch: "browser-switch-profile-key",
  browserpersona: "browser-persona-profile-key",
  browserpersonagroup: "browser-persona-group-profile-key",
  browserpersonaempty: "browser-persona-empty-profile-key",
};

export type FakeHermesControl = {
  holdNextRun(): Promise<void>;
  waitForHeldRun(): Promise<void>;
  releaseHeldRun(): Promise<void>;
  /** 긴 작업 과정 입력의 스트림이 기다리는 자리를 푼다. 실행 상태는 그대로다. 기다리는 것이 없으면 아무것도 하지 않는다. */
  releaseLongActivity(): Promise<void>;
  /** 다음 `GET /api/profiles/{이름}/soul` 응답을 붙잡는다. 성격 화면의 서버를 실제로 늦출 때 쓴다. */
  holdNextSoul(): Promise<void>;
  /** 붙잡은 성격 읽기 응답을 보낸다. 붙잡은 것이 없어도 대기 표시를 끄고 끝난다. */
  releaseHeldSoul(): Promise<void>;
  /** 그 provider 의 계정이 전부 막힌 것처럼 답하게 한다. */
  blockProvider(provider: string): Promise<void>;
  clearBlockedProviders(): Promise<void>;
  /** 동시 실행 한도에 닿아 실행 제출을 429 로 거절하게 한다. */
  busy(): Promise<void>;
  clearBusy(): Promise<void>;
  /** 준비 상태의 toolset 조회를 실제 Hermes 장애처럼 실패하게 한다. */
  setReadinessOutage(
    outage: "busy" | "unavailable" | "timeout" | undefined,
  ): Promise<void>;
  /** 다음 살펴보기 실행의 마지막 답 글을 정한다. 한 번 쓰면 그 뒤 살펴보기는 기본 답으로 돌아간다. */
  setProactiveOutput(output: string): Promise<void>;
  /** 마지막 실행 요청이 실어 온 provider, 모델, effort 다. 싣지 않은 칸은 빠진다. */
  lastSubmittedRuntime(): Promise<{
    provider?: string;
    model?: string;
    reasoningEffort?: string;
  }>;
};

async function fakeHermesControl(): Promise<FakeHermesControl> {
  const baseUrl = await hermesBaseUrl();
  const call = async (path: string, method: "GET" | "POST") => {
    const response = await fetch(`${baseUrl}${path}`, { method });
    if (!response.ok)
      throw new Error(`가짜 Hermes 제어 요청이 실패했다: ${response.status}`);
  };
  return {
    holdNextRun: () => call("/__test/hold-next-run", "POST"),
    waitForHeldRun: () => call("/__test/wait-held-run", "GET"),
    releaseHeldRun: () => call("/__test/release-held-run", "POST"),
    releaseLongActivity: () => call("/__test/release-long-activity", "POST"),
    holdNextSoul: () => call("/__test/hold-next-soul", "POST"),
    releaseHeldSoul: () => call("/__test/release-held-soul", "POST"),
    blockProvider: (provider: string) =>
      call(`/__test/block-provider/${provider}`, "POST"),
    clearBlockedProviders: () =>
      call("/__test/clear-blocked-providers", "POST"),
    busy: () => call("/__test/busy", "POST"),
    clearBusy: () => call("/__test/clear-busy", "POST"),
    setReadinessOutage: async (outage) => {
      const response = await fetch(`${baseUrl}/__test/readiness-outage`, {
        method: "POST",
        body: JSON.stringify(outage === undefined ? {} : { outage }),
      });
      if (!response.ok)
        throw new Error(`가짜 Hermes 제어 요청이 실패했다: ${response.status}`);
    },
    setProactiveOutput: async (output: string) => {
      const response = await fetch(`${baseUrl}/__test/proactive-output`, {
        method: "POST",
        body: JSON.stringify({ output }),
      });
      if (!response.ok)
        throw new Error(`가짜 Hermes 제어 요청이 실패했다: ${response.status}`);
    },
    lastSubmittedRuntime: async () => {
      const response = await fetch(`${baseUrl}/__test/last-submitted-runtime`);
      if (!response.ok)
        throw new Error(`가짜 Hermes 제어 요청이 실패했다: ${response.status}`);
      return (await response.json()) as {
        provider?: string;
        model?: string;
        reasoningEffort?: string;
      };
    },
  };
}

/** 가짜 Hermes 가 듣고 있는 주소다. 에이전트 주소를 고치는 검사가 이 값으로 새 주소를 만든다. */
export async function hermesBaseUrl(): Promise<string> {
  return (await readFile(HERMES_CONTROL_PATH, "utf-8")).trim();
}

/**
 * admin 세션과 무관하게 그 에이전트의 공개 범위를 직접 바꾼다.
 *
 * <p>브라우저의 세션 쿠키를 보지 않고 Control Plane 을 바로 부른다. 검사가 `setSession` 으로 세션을
 * `MEMBER` 로 바꾼 뒤에도 이 함수로 원래 범위를 되돌릴 수 있어야 하기 때문이다.
 */
export async function setAgentVisibility(
  code: string,
  visibility: "PRIVATE" | "GROUP",
  ownerEmail: string | null,
): Promise<void> {
  const token = await new SignJWT({ name: "브라우저 테스트" })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(TEST_EMAIL)
    .setIssuedAt()
    .setExpirationTime("2m")
    .sign(new TextEncoder().encode(JWT_SECRET));
  const response = await fetch(
    `${CONTROL_PLANE_BASE_URL}/api/v1/admin/agents/${code}`,
    {
      method: "PATCH",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ enabled: true, visibility, ownerEmail }),
    },
  );
  if (!response.ok) {
    throw new Error(
      `에이전트 공개 범위를 바꾸지 못했다: ${code} ${response.status} ${await response.text()}`,
    );
  }
}

/** 그 사용자의 메일로 서명한 Control Plane 토큰으로 경로 하나를 부른다. */
async function callAs(
  email: string,
  method: "POST" | "PUT" | "DELETE",
  path: string,
  body?: unknown,
): Promise<Response> {
  const token = await new SignJWT({ name: "브라우저 테스트" })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(email)
    .setIssuedAt()
    .setExpirationTime("2m")
    .sign(new TextEncoder().encode(JWT_SECRET));
  return fetch(`${CONTROL_PLANE_BASE_URL}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

function connectorCall(
  email: string,
  method: "POST" | "DELETE",
  path: string,
  body?: unknown,
): Promise<Response> {
  return callAs(
    email,
    method,
    `/api/v1/connections/${DEMO_CONNECTOR.id}${path}`,
    body,
  );
}

/**
 * 그 사용자로 시험 커넥터를 등록하고 확인해 `READY` 로 만든다. 연결의 상태를 돌려준다.
 *
 * <p>연결 등록은 에이전트를 만들지 않는다. 에이전트에 붙이는 것은 `bindDemoConnector` 가 한다.
 * 커넥터 호출에는 사용자별 동시 1개 제한이 있어 등록과 확인을 차례로 보낸다.
 */
export async function connectDemoConnector(email: string): Promise<string> {
  const registered = await connectorCall(email, "POST", "", {
    values: { token: DEMO_TOKEN_OK },
  });
  if (!registered.ok)
    throw new Error(
      `시험 커넥터를 등록하지 못했다: ${registered.status} ${await registered.text()}`,
    );
  const checked = await connectorCall(email, "POST", "/check");
  if (!checked.ok)
    throw new Error(
      `시험 커넥터를 확인하지 못했다: ${checked.status} ${await checked.text()}`,
    );
  const view = (await checked.json()) as { status: string };
  if (view.status !== "READY") {
    throw new Error(`시험 커넥터가 READY 가 아니다: ${view.status}`);
  }
  return view.status;
}

/** 그 사용자의 시험 커넥터 연결을 해제한다. 붙은 에이전트에서도 모두 뗀다. */
export async function disconnectDemoConnector(email: string): Promise<void> {
  const response = await connectorCall(email, "DELETE", "");
  if (!response.ok)
    throw new Error(
      `시험 커넥터를 해제하지 못했다: ${response.status} ${await response.text()}`,
    );
}

/** 그 사용자의 시험 커넥터 연결을 그 사용자의 에이전트에 붙인다. 붙인 줄의 상태를 돌려준다. */
export async function bindDemoConnector(
  email: string,
  agentCode: string,
): Promise<{ bound: boolean; status: string | null; restartRequired: boolean }> {
  const response = await callAs(
    email,
    "PUT",
    `/api/v1/agents/${agentCode}/connections/${DEMO_CONNECTOR.id}`,
  );
  if (!response.ok)
    throw new Error(
      `시험 커넥터를 붙이지 못했다: ${agentCode} ${response.status} ${await response.text()}`,
    );
  return (await response.json()) as {
    bound: boolean;
    status: string | null;
    restartRequired: boolean;
  };
}

/**
 * 그 사용자의 예전 방식 연결 에이전트를 만들고 에이전트 번호(화면 주소의 코드)를 돌려준다.
 *
 * <p>연결 등록은 더는 이런 에이전트를 만들지 않으므로 검사에서만 뜨는 경로로 만든다. 그 사용자의 시험 커넥터 연결도
 * `READY` 가 된다. 검사가 끝나면 에이전트를 지우고 연결을 해제한다.
 */
export async function createLegacyConnectorAgent(email: string): Promise<string> {
  const response = await callAs(
    email,
    "POST",
    "/api/v1/test-support/connector/legacy-agent",
    { email, connectorId: DEMO_CONNECTOR.id },
  );
  if (!response.ok)
    throw new Error(
      `예전 방식의 연결 에이전트를 만들지 못했다: ${response.status} ${await response.text()}`,
    );
  const { code } = (await response.json()) as { code?: unknown };
  if (typeof code !== "string")
    throw new Error("예전 방식의 연결 에이전트 번호가 없다");
  return code;
}

export async function setSession(
  context: BrowserContext,
  user: { email: string; name: string },
): Promise<void> {
  const token = await encode({
    salt: SESSION_COOKIE,
    secret: AUTH_SECRET,
    token: {
      sub: user.email,
      email: user.email,
      name: user.name,
    },
  });
  await context.clearCookies({ name: SESSION_COOKIE });
  await context.addCookies([
    {
      name: SESSION_COOKIE,
      value: token,
      url: WEB_BASE_URL,
      httpOnly: true,
      sameSite: "Lax",
    },
  ]);
}

async function readControlPlaneLog(logPath: string): Promise<string> {
  return readFile(logPath, "utf-8").catch(() => "");
}

function childExitState(app: ChildProcess): string | null {
  if (app.exitCode !== null) return `종료 코드 ${app.exitCode}`;
  if (app.signalCode !== null) return `${app.signalCode} 신호`;
  return null;
}

async function controlPlaneStartError(
  logPath: string,
  message: string,
): Promise<Error> {
  const log = await readControlPlaneLog(logPath);
  return new Error(`${message}\n${log.split("\n").slice(-40).join("\n")}`);
}

async function waitForHealth(
  logPath: string,
  app: ChildProcess,
): Promise<void> {
  const deadline = Date.now() + HEALTH_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const exitState = childExitState(app);
    if (exitState !== null) {
      throw await controlPlaneStartError(
        logPath,
        `Control Plane 이 시작 전에 끝났다: ${exitState}`,
      );
    }

    const log = await readControlPlaneLog(logPath);
    if (!log.includes("Started Assistant")) {
      await new Promise((done) => setTimeout(done, 1_000));
      continue;
    }

    try {
      const response = await fetch(`${CONTROL_PLANE_BASE_URL}/actuator/health`);
      if (response.ok && childExitState(app) === null) return;
    } catch {
      // 아직 듣지 않는다. 다시 두드린다.
    }
    await new Promise((done) => setTimeout(done, 1_000));
  }
  throw await controlPlaneStartError(logPath, "Control Plane 이 뜨지 않았다");
}

/**
 * 결과물 폴더의 루트를 실행마다 만든다.
 *
 * <p>Control Plane 이 보는 경로와 에이전트가 보는 경로가 같은 기계의 같은 디렉터리다. 대역이 입력에 적힌 폴더에 곧바로 쓴다.
 */
async function makeArtifactRoot(work: string): Promise<string> {
  const artifactRoot = join(work, "artifacts");
  await mkdir(artifactRoot, { recursive: true });
  return artifactRoot;
}

/** 첨부 루트다. 없으면 Control Plane 이 도구 설정 쓰기를 409 로 멈춘다(ADR-091). */
async function makeAttachmentRoot(work: string): Promise<string> {
  const attachmentRoot = join(work, "attachments");
  await mkdir(attachmentRoot, { recursive: true });
  return attachmentRoot;
}

/** 스킬 버전 디렉터리의 루트다. 결과물 폴더와 같이 두 루트에 같은 경로를 준다. */
async function makeSkillRoot(work: string): Promise<string> {
  const skillRoot = join(work, "skills");
  await mkdir(skillRoot, { recursive: true });
  return skillRoot;
}

async function writeProfileKeys(work: string): Promise<string> {
  const keyDir = join(work, "keys");
  await mkdir(keyDir, { recursive: true });
  for (const profile of Object.keys(PROFILE_KEYS)) {
    const keyFile = join(keyDir, profile);
    await writeFile(keyFile, PROFILE_KEYS[profile]!);
    await chmod(keyFile, 0o600);
  }
  return keyDir;
}

async function seedAgents(hermesBaseUrl: string): Promise<void> {
  // 첫 요청이 관리자를 만들고 표시 이름은 그때 굳는다. README 화면을 찍는 설정만 이 이름을 바꾼다.
  const token = await new SignJWT({ name: process.env.BROWSER_ADMIN_NAME ?? "브라우저 테스트" })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(TEST_EMAIL)
    .setIssuedAt()
    .setExpirationTime("2m")
    .sign(new TextEncoder().encode(JWT_SECRET));
  for (const agent of [
    {
      code: "browser",
      name: "브라우저 비서",
      profile: "browser",
      flow: null,
      visibility: "PRIVATE" as const,
    },
    // 흐름 검사 전용이다. 하나만 두면 흐름이 붙지 않은 대화를 함께 검사할 수 없다.
    {
      code: FLOW_AGENT_CODE,
      name: "흐름 비서",
      profile: "browserflow",
      flow: "research-and-build",
      visibility: "PRIVATE" as const,
    },
    // 막힘 검사 전용이다. 다른 검사가 쓰는 에이전트와 섞이지 않게 나눈다.
    {
      code: SWITCH_AGENT_CODE,
      name: "막힘 비서",
      profile: "browserswitch",
      flow: null,
      visibility: "PRIVATE" as const,
    },
    // 성격을 읽고 쓰는 검사 전용이다. 주인이 TEST_EMAIL 이라 admin 세션이 언제나 고칠 수 있다.
    {
      code: PERSONA_AGENT_CODE,
      name: "성격 비서",
      profile: "browserpersona",
      flow: null,
      visibility: "PRIVATE" as const,
    },
    // 그룹에 공개할 에이전트다. 검사가 실행 중에만 GROUP 으로 바꿨다 되돌린다. 여기서 바로 GROUP 으로
    // 씨 루트면 관리 화면에 "그룹 공개로 변경"/"나만으로 변경" 단추가 하나 더 생겨, 정확히 하나만
    // 있다고 가정하는 identity.spec.ts 가 어긋난다.
    {
      code: PERSONA_GROUP_AGENT_CODE,
      name: "그룹 성격 비서",
      profile: "browserpersonagroup",
      flow: null,
      visibility: "PRIVATE" as const,
    },
    // 본문이 비어 있는 채로 두는 검사 전용이다.
    {
      code: PERSONA_EMPTY_AGENT_CODE,
      name: "빈 성격 비서",
      profile: "browserpersonaempty",
      flow: null,
      visibility: "PRIVATE" as const,
    },
  ]) {
    const response = await fetch(
      `${CONTROL_PLANE_BASE_URL}/api/v1/admin/agents`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          code: agent.code,
          name: agent.name,
          hermesProfile: agent.profile,
          apiBaseUrl: `${hermesBaseUrl}/p/${agent.profile}`,
          costMode: "SUBSCRIPTION",
          credentialScope: "SHARED_HOUSEHOLD",
          visibility: agent.visibility,
          ownerEmail: agent.visibility === "PRIVATE" ? TEST_EMAIL : null,
          flow: agent.flow,
        }),
      },
    );
    if (!response.ok) {
      throw new Error(
        `브라우저 테스트 에이전트를 등록하지 못했다: ${agent.code} ${response.status} ${await response.text()}`,
      );
    }
  }
  await seedModelTiers(token);
}

/** 단계 정의는 DB 만 갖는다. 관리자 경로로 대역 catalog 의 중립 모델을 저장한다. */
async function seedModelTiers(token: string): Promise<void> {
  const response = await fetch(
    `${CONTROL_PLANE_BASE_URL}/api/v1/chat/model-tiers/group`,
    {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        tiers: [
          {
            tier: "FAST",
            provider: null,
            model: "example-fast",
            reasoningEffort: "low",
          },
          {
            tier: "BALANCED",
            provider: null,
            model: "example-balanced",
            reasoningEffort: "medium",
          },
          {
            tier: "DEEP",
            provider: null,
            model: "example-deep",
            reasoningEffort: "high",
          },
        ],
        defaultTier: null,
      }),
    },
  );
  if (!response.ok) {
    throw new Error(
      `브라우저 테스트 단계 정의를 저장하지 못했다: ${response.status} ${await response.text()}`,
    );
  }
}

/** 그룹 공개 검사가 쓰는 profile 에 셸·파일 도구가 남지 않도록 API 실행 도구를 초기화한다. */
async function seedBrowserToolsets(hermesBaseUrl: string): Promise<void> {
  for (const profile of Object.keys(PROFILE_KEYS)) {
    const response = await fetch(`${hermesBaseUrl}/api/config`, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${FAKE_DASHBOARD_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        profile,
        config: { platform_toolsets: { api_server: ["fos-assistant"] } },
      }),
    });
    if (!response.ok) {
      throw new Error(
        `브라우저 테스트 도구 설정을 준비하지 못했다: ${profile} ${response.status}`,
      );
    }
  }
}

function startControlPlane(
  keyDir: string,
  dashboardBaseUrl: string,
  logPath: string,
  attachmentRoot: string,
  artifactRoot: string,
  skillRoot: string,
): ChildProcess {
  const log = createWriteStream(logPath);
  const app = spawn("./gradlew", ["--no-daemon", "--quiet", "smokeRun"], {
    cwd: join(ROOT, "backend"),
    env: {
      ...process.env,
      DB_URL: "jdbc:h2:mem:browser;MODE=MySQL;DB_CLOSE_DELAY=-1",
      DB_USERNAME: "sa",
      DB_PASSWORD: "",
      SERVER_PORT: String(CONTROL_PLANE_PORT),
      ASSISTANT_JWT_SECRET: JWT_SECRET,
      // 민감 Memory 문서를 만드는 검사가 쓴다. 운영 값이 아니라 글자 0123456789abcdef0123456789abcdef 의 base64 다.
      ASSISTANT_MEMORY_ENCRYPTION_ACTIVE_KEY_ID: "test-1",
      ASSISTANT_MEMORY_ENCRYPTION_KEYS: "test-1:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
      // 검사는 같은 사용자로 짧은 시간에 커넥터를 여러 번 부른다. 기본값 10회에 걸리지 않게 올린다.
      ASSISTANT_CONNECTOR_CALLS_PER_MINUTE: "1000",
      HERMES_PROFILE_KEY_DIR: keyDir,
      // 기본값이 없어 주지 않으면 기동하지 못한다. 실행마다 만든 임시 디렉터리 아래에 둔다.
      ASSISTANT_ATTACHMENT_ROOT: attachmentRoot,
      // 에이전트 쪽에서 보는 경로다. 검사는 그 경로를 열지 않고 입력에 적힌 글자만 본다.
      ASSISTANT_ATTACHMENT_AGENT_ROOT: "/agent-side/attachments",
      // 결과물 폴더는 두 루트에 같은 경로를 준다. 대역이 같은 기계에서 입력에 적힌 폴더에 파일을 쓴다.
      ASSISTANT_ARTIFACT_ROOT: artifactRoot,
      ASSISTANT_ARTIFACT_AGENT_ROOT: artifactRoot,
      // 스킬 디렉터리도 두 루트에 같은 경로를 준다. 대역이 게시된 경로의 SKILL.md 를 같은 기계에서 읽는다.
      ASSISTANT_SKILL_ROOT: skillRoot,
      ASSISTANT_SKILL_AGENT_ROOT: skillRoot,
      HERMES_DASHBOARD_BASE_URL: dashboardBaseUrl,
      HERMES_DASHBOARD_TOKEN: FAKE_DASHBOARD_TOKEN,
      // 장애 시험의 11초 지연이 실제 읽기 timeout 을 넘도록 시험 서버의 한도를 명시한다.
      HERMES_READ_TIMEOUT: "10s",
      // 띄운 대역이 실행마다 빈 포트를 받아 쓰므로 고정값으로 적을 수 없다. 실제 주소를 넘긴다.
      HERMES_SHARED_LISTENER_BASE_URL: dashboardBaseUrl,
      ASSISTANT_PRICING_CATALOG: join(
        ROOT,
        "backend/src/test/resources/pricing/models-dev-sample.json",
      ),
      SPRING_FLYWAY_ENABLED: "true",
      SPRING_JPA_HIBERNATE_DDL_AUTO: "validate",
      SPRING_DATASOURCE_DRIVER_CLASS_NAME: "org.h2.Driver",
      ASSISTANT_TESTSUPPORT_ENABLED: "true",
    },
    detached: true,
    stdio: ["ignore", "pipe", "pipe"],
  });
  app.stdout?.pipe(log);
  app.stderr?.pipe(log);
  return app;
}

async function stopProcess(app: ChildProcess | undefined): Promise<void> {
  if (app?.pid === undefined) return;
  try {
    process.kill(-app.pid, "SIGTERM");
  } catch {
    // 이미 내려갔다.
  }
}

export default async function setupServices(): Promise<() => Promise<void>> {
  const work = await mkdtemp(join(tmpdir(), "fos-assistant-browser-"));
  const logPath = join(work, "control-plane.log");
  let hermes: FakeHermes | undefined;
  let app: ChildProcess | undefined;

  try {
    const skillRoot = await makeSkillRoot(work);
    hermes = await startFakeHermes(PROFILE_KEYS, undefined, {}, skillRoot);
    await writeFile(HERMES_CONTROL_PATH, hermes.baseUrl);
    await seedBrowserToolsets(hermes.baseUrl);
    const controlPlane = startControlPlane(
      await writeProfileKeys(work),
      hermes.baseUrl,
      logPath,
      await makeAttachmentRoot(work),
      await makeArtifactRoot(work),
      skillRoot,
    );
    app = controlPlane;
    await waitForHealth(logPath, controlPlane);
    await seedAgents(hermes.baseUrl);
  } catch (error) {
    await stopProcess(app);
    await hermes?.close();
    await rm(HERMES_CONTROL_PATH, { force: true });
    await rm(work, { recursive: true, force: true });
    throw error;
  }

  return async () => {
    await stopProcess(app);
    await hermes?.close();
    await rm(HERMES_CONTROL_PATH, { force: true });
    await rm(work, { recursive: true, force: true });
  };
}

/**
 * `loading.tsx` 가 있는 경로를 `goto` 나 `reload` 로 열면 본문이 `<body>` 끝의 숨은 `S:` 조각으로 먼저 흘러오고,
 * React 가 조금 뒤에 제자리로 옮긴다. 문서를 다 읽은 뒤에도 그 옮김이 남아 있어서,
 * 그 사이에는 같은 요소가 숨어 있거나 두 벌이라 strict 검사가 곧바로 실패한다.
 * 그래서 문서를 새로 읽은 뒤에는 사용자가 보는 상태, 곧 숨은 조각이 모두 옮겨진 뒤까지 기다린다.
 * 조각이 없는 경로는 곧바로 지나가고, 시간 안에 옮겨지지 않으면 그 검사를 실패시킨다.
 * 링크를 눌러 옮기는 것은 RSC 로 오므로 숨은 조각이 생기지 않는다.
 */
export function waitForStreamedContentAfterLoad(
  page: import("../../web/node_modules/@playwright/test/index.js").Page,
) {
  const streamedContentPlaced = () =>
    page.waitForFunction(
      () => document.querySelector('div[hidden][id^="S:"]') === null,
      undefined,
      {
        timeout: 10_000,
      },
    );
  const goto = page.goto.bind(page);
  page.goto = async (...args) => {
    const response = await goto(...args);
    await streamedContentPlaced();
    return response;
  };
  const reload = page.reload.bind(page);
  page.reload = async (...args) => {
    const response = await reload(...args);
    await streamedContentPlaced();
    return response;
  };
}

export const test = base.extend<{ hermes: FakeHermesControl }>({
  hermes: async ({}, use) => {
    await use(await fakeHermesControl());
  },
  page: async ({ context, page }, use) => {
    await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
    waitForStreamedContentAfterLoad(page);
    await use(page);
  },
});

export { expect };
