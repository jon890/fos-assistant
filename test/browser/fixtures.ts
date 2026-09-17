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
  const keyFile = join(keyDir, "browser");
  await writeFile(keyFile, "browser-profile-key");
  await chmod(keyFile, 0o600);
  return keyDir;
}

async function seedAgent(hermesBaseUrl: string): Promise<void> {
  const token = await new SignJWT({ name: "브라우저 테스트" })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(TEST_EMAIL)
    .setIssuedAt()
    .setExpirationTime("2m")
    .sign(new TextEncoder().encode(JWT_SECRET));
  const response = await fetch(`${CONTROL_PLANE_BASE_URL}/api/v1/admin/agents`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      code: "browser",
      name: "브라우저 비서",
      hermesProfile: "browser",
      apiBaseUrl: `${hermesBaseUrl}/p/browser`,
      provider: "openai-codex",
      costMode: "SUBSCRIPTION",
      credentialScope: "SHARED_HOUSEHOLD",
      visibility: "PRIVATE",
      ownerEmail: TEST_EMAIL,
    }),
  });
  if (!response.ok) {
    throw new Error(`브라우저 테스트 에이전트를 등록하지 못했다: ${response.status} ${await response.text()}`);
  }
}

function startControlPlane(keyDir: string, workspaceRoot: string, logPath: string): ChildProcess {
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
      ASSISTANT_WORKSPACE_ROOT: workspaceRoot,
      SPRING_FLYWAY_ENABLED: "true",
      SPRING_JPA_HIBERNATE_DDL_AUTO: "validate",
      SPRING_DATASOURCE_DRIVER_CLASS_NAME: "org.h2.Driver",
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
    hermes = await startFakeHermes({ browser: "browser-profile-key" });
    const workspaceRoot = join(work, "workspaces");
    await mkdir(workspaceRoot, { recursive: true });
    app = startControlPlane(await writeProfileKeys(work), workspaceRoot, logPath);
    await waitForHealth(logPath);
    await seedAgent(hermes.baseUrl);
  } catch (error) {
    await stopProcess(app);
    await hermes?.close();
    await rm(work, { recursive: true, force: true });
    throw error;
  }

  return async () => {
    await stopProcess(app);
    await hermes?.close();
    await rm(work, { recursive: true, force: true });
  };
}

export const test = base.extend({
  page: async ({ context, page }, use) => {
    await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
    await use(page);
  },
});

export { expect };
