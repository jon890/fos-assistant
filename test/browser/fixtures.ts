import { spawn, type ChildProcess } from "node:child_process";
import { createWriteStream } from "node:fs";
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { encode } from "../../web/node_modules/next-auth/jwt.js";
import { SignJWT } from "../../web/node_modules/jose/dist/webapi/index.js";
import playwright, { type BrowserContext } from "../../web/node_modules/@playwright/test/index.js";
import { startFakeHermes, type FakeHermes } from "../e2e/fake-hermes.ts";
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

/** profile 이름과 그 profile 의 key 다. 가짜 Hermes 와 key 디렉터리가 같은 표를 쓴다. */
const PROFILE_KEYS: Record<string, string> = {
  browser: "browser-profile-key",
  browserflow: "browser-flow-profile-key",
};

export type FakeHermesControl = {
  holdNextRun(): Promise<void>;
  waitForHeldRun(): Promise<void>;
  releaseHeldRun(): Promise<void>;
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
  };
}

/** 가짜 Hermes 가 듣고 있는 주소다. 에이전트 주소를 고치는 검사가 이 값으로 새 주소를 만든다. */
export async function hermesBaseUrl(): Promise<string> {
  return (await readFile(HERMES_CONTROL_PATH, "utf-8")).trim();
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
    { code: "browser", name: "브라우저 비서", profile: "browser", flow: null },
    // 흐름 검사 전용이다. 하나만 두면 흐름이 붙지 않은 대화를 함께 검사할 수 없다.
    { code: FLOW_AGENT_CODE, name: "흐름 비서", profile: "browserflow", flow: "research-and-build" },
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
        visibility: "PRIVATE",
        ownerEmail: TEST_EMAIL,
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

function startControlPlane(keyDir: string, logPath: string): ChildProcess {
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
    hermes = await startFakeHermes(PROFILE_KEYS);
    await writeFile(HERMES_CONTROL_PATH, hermes.baseUrl);
    app = startControlPlane(await writeProfileKeys(work), logPath);
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
