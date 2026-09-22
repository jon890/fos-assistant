import { spawn, type ChildProcess } from "node:child_process";
import { createWriteStream } from "node:fs";
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { encode } from "../../web/node_modules/next-auth/jwt.js";
import { SignJWT } from "../../web/node_modules/jose/dist/webapi/index.js";
import playwright, { type BrowserContext } from "../../web/node_modules/@playwright/test/index.js";
import { FAKE_DASHBOARD_TOKEN, startFakeHermes, type FakeHermes } from "../e2e/fake-hermes.ts";
import {
  AUTH_SECRET,
  CONTROL_PLANE_BASE_URL,
  CONTROL_PLANE_PORT,
  JWT_SECRET,
  TEST_EMAIL,
  WEB_BASE_URL,
} from "./settings.ts";

const { expect, test: base } = playwright;

const ROOT = join(import.meta.dirname, "../..");
const SESSION_COOKIE = "authjs.session-token";
const HEALTH_TIMEOUT_MS = 90_000;
const HERMES_CONTROL_PATH = join(tmpdir(), `fos-assistant-browser-hermes-${CONTROL_PLANE_PORT}.url`);

/** 흐름이 붙은 에이전트의 코드다. 흐름 검사가 이 코드로 대화를 시작한다. */
export const FLOW_AGENT_CODE = "browserflow";

/** 모델 목록을 고치는 검사 전용이다. 다른 검사가 쓰는 에이전트를 건드리지 않는다. */
export const MODELS_AGENT_CODE = "browsermodels";

/** 막혀서 넘어가는 검사 전용이다. 여기서만 막힌 provider 를 만든다. */
export const SWITCH_AGENT_CODE = "browserswitch";

/** 성격을 읽고 쓰는 검사 전용이다. `TEST_EMAIL` 이 주인이라 admin 세션이 언제나 고칠 수 있다. */
export const PERSONA_AGENT_CODE = "browserpersona";

/**
 * 「고칠 수 없는 에이전트」 검사가 잠깐 가족에게 공개하는 에이전트다.
 *
 * <p>씨 뿌릴 때는 다른 에이전트와 같이 `PRIVATE` 이고 주인이 `TEST_EMAIL` 이다. 그 검사가
 * `setAgentVisibility` 로 `FAMILY` 로 바꿔 `MEMBER` 세션에서 읽기 전용으로 열고, 끝나면 되돌린다.
 * 여기서 바로 `FAMILY` 로 씨 뿌리면 관리 화면에 가족 공개 에이전트가 하나 늘어, 정확히 하나만
 * 있다고 가정하는 `identity.spec.ts` 가 어긋난다.
 */
export const PERSONA_FAMILY_AGENT_CODE = "browserpersonafamily";

/**
 * 본문이 비어 있는 채로 두는 검사 전용이다.
 *
 * <p>다른 성격 검사가 저장을 걸어 두면 이 에이전트까지 함께 비어 있지 않게 될 수 있어 따로 둔다. 이
 * 에이전트에는 끝까지 아무도 쓰지 않는다.
 */
export const PERSONA_EMPTY_AGENT_CODE = "browserpersonaempty";

/** profile 이름과 그 profile 의 key 다. 가짜 Hermes 와 key 디렉터리가 같은 표를 쓴다. */
const PROFILE_KEYS: Record<string, string> = {
  browser: "browser-profile-key",
  browserflow: "browser-flow-profile-key",
  browsermodels: "browser-models-profile-key",
  browserswitch: "browser-switch-profile-key",
  browserpersona: "browser-persona-profile-key",
  browserpersonafamily: "browser-persona-family-profile-key",
  browserpersonaempty: "browser-persona-empty-profile-key",
};

export type FakeHermesControl = {
  holdNextRun(): Promise<void>;
  waitForHeldRun(): Promise<void>;
  releaseHeldRun(): Promise<void>;
  /** 그 provider 의 계정이 전부 막힌 것처럼 답하게 한다. */
  blockProvider(provider: string): Promise<void>;
  clearBlockedProviders(): Promise<void>;
  /** 동시 실행 한도에 닿아 실행 제출을 429 로 거절하게 한다. */
  busy(): Promise<void>;
  clearBusy(): Promise<void>;
};

async function fakeHermesControl(): Promise<FakeHermesControl> {
  const baseUrl = await hermesBaseUrl();
  const call = async (path: string, method: "GET" | "POST") => {
    const response = await fetch(`${baseUrl}${path}`, { method });
    if (!response.ok) throw new Error(`가짜 Hermes 제어 요청이 실패했다: ${response.status}`);
  };
  return {
    holdNextRun: () => call("/__test/hold-next-run", "POST"),
    waitForHeldRun: () => call("/__test/wait-held-run", "GET"),
    releaseHeldRun: () => call("/__test/release-held-run", "POST"),
    blockProvider: (provider: string) => call(`/__test/block-provider/${provider}`, "POST"),
    clearBlockedProviders: () => call("/__test/clear-blocked-providers", "POST"),
    busy: () => call("/__test/busy", "POST"),
    clearBusy: () => call("/__test/clear-busy", "POST"),
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
  visibility: "PRIVATE" | "FAMILY",
  ownerEmail: string | null,
): Promise<void> {
  const token = await new SignJWT({ name: "브라우저 테스트" })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(TEST_EMAIL)
    .setIssuedAt()
    .setExpirationTime("2m")
    .sign(new TextEncoder().encode(JWT_SECRET));
  const response = await fetch(`${CONTROL_PLANE_BASE_URL}/api/v1/admin/agents/${code}`, {
    method: "PATCH",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ enabled: true, visibility, ownerEmail }),
  });
  if (!response.ok) {
    throw new Error(`에이전트 공개 범위를 바꾸지 못했다: ${code} ${response.status} ${await response.text()}`);
  }
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

async function waitForHealth(logPath: string): Promise<void> {
  const deadline = Date.now() + HEALTH_TIMEOUT_MS;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${CONTROL_PLANE_BASE_URL}/actuator/health`);
      if (response.ok) return;
    } catch {
      // 아직 듣지 않는다. 다시 두드린다.
    }
    await new Promise((done) => setTimeout(done, 1_000));
  }
  const log = await readFile(logPath, "utf-8").catch(() => "");
  throw new Error(`Control Plane 이 뜨지 않았다\n${log.split("\n").slice(-40).join("\n")}`);
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
  const token = await new SignJWT({ name: "브라우저 테스트" })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(TEST_EMAIL)
    .setIssuedAt()
    .setExpirationTime("2m")
    .sign(new TextEncoder().encode(JWT_SECRET));
  for (const agent of [
    { code: "browser", name: "브라우저 비서", profile: "browser", flow: null, visibility: "PRIVATE" as const },
    // 흐름 검사 전용이다. 하나만 두면 흐름이 붙지 않은 대화를 함께 검사할 수 없다.
    { code: FLOW_AGENT_CODE, name: "흐름 비서", profile: "browserflow", flow: "research-and-build", visibility: "PRIVATE" as const },
    // 모델 목록과 넘김을 고치는 검사 전용이다. 다른 검사가 쓰는 에이전트와 섞이지 않게 나눈다.
    { code: MODELS_AGENT_CODE, name: "모델 목록 비서", profile: "browsermodels", flow: null, visibility: "PRIVATE" as const },
    { code: SWITCH_AGENT_CODE, name: "넘김 비서", profile: "browserswitch", flow: null, visibility: "PRIVATE" as const },
    // 성격을 읽고 쓰는 검사 전용이다. 주인이 TEST_EMAIL 이라 admin 세션이 언제나 고칠 수 있다.
    { code: PERSONA_AGENT_CODE, name: "성격 비서", profile: "browserpersona", flow: null, visibility: "PRIVATE" as const },
    // 가족에게 공개할 에이전트다. 검사가 실행 중에만 FAMILY 로 바꿨다 되돌린다. 여기서 바로 FAMILY 로
    // 씨 뿌리면 관리 화면에 "가족 공개로 변경"/"나만으로 변경" 단추가 하나 더 생겨, 정확히 하나만
    // 있다고 가정하는 identity.spec.ts 가 어긋난다.
    { code: PERSONA_FAMILY_AGENT_CODE, name: "가족 성격 비서", profile: "browserpersonafamily", flow: null, visibility: "PRIVATE" as const },
    // 본문이 비어 있는 채로 두는 검사 전용이다.
    { code: PERSONA_EMPTY_AGENT_CODE, name: "빈 성격 비서", profile: "browserpersonaempty", flow: null, visibility: "PRIVATE" as const },
  ]) {
    const response = await fetch(`${CONTROL_PLANE_BASE_URL}/api/v1/admin/agents`, {
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
        provider: "openai-codex",
        costMode: "SUBSCRIPTION",
        credentialScope: "SHARED_HOUSEHOLD",
        visibility: agent.visibility,
        ownerEmail: agent.visibility === "PRIVATE" ? TEST_EMAIL : null,
        flow: agent.flow,
      }),
    });
    if (!response.ok) {
      throw new Error(
        `브라우저 테스트 에이전트를 등록하지 못했다: ${agent.code} ${response.status} ${await response.text()}`,
      );
    }
  }
}

function startControlPlane(
  keyDir: string,
  dashboardBaseUrl: string,
  logPath: string,
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
      HERMES_PROFILE_KEY_DIR: keyDir,
      HERMES_DASHBOARD_BASE_URL: dashboardBaseUrl,
      HERMES_DASHBOARD_TOKEN: FAKE_DASHBOARD_TOKEN,
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
      // 막힌 provider 를 오래 기억하면 다음 검사가 1순위를 건너뛴다. 검사에서만 짧게 둔다.
      ASSISTANT_MODEL_PROVIDER_COOLDOWN: "PT1S",
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
    hermes = await startFakeHermes(PROFILE_KEYS);
    await writeFile(HERMES_CONTROL_PATH, hermes.baseUrl);
    app = startControlPlane(await writeProfileKeys(work), hermes.baseUrl, logPath);
    await waitForHealth(logPath);
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

export const test = base.extend<{ hermes: FakeHermesControl }>({
  hermes: async ({}, use) => {
    await use(await fakeHermesControl());
  },
  page: async ({ context, page }, use) => {
    await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
    await use(page);
  },
});

export { expect };
