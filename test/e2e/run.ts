/**
 * 홈서버 없이 전체 흐름을 검사한다.
 *
 * <p>fake Hermes 와 Control Plane 을 띄우고 시나리오를 차례로 돌린다. 시나리오는 앞의 것이 만든
 * 상태 위에서 이어지므로 순서가 있다. 로그인이 되어야 바인딩을 걸고, 바인딩이 있어야 대화가 돌고,
 * 대화가 돌아야 사용량이 남는다.
 *
 *     node test/e2e/run.ts
 *
 * <p>Node 의 TypeScript 실행을 쓰므로 빌드 단계가 없다. Node 22.18 이상이 필요하다.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { mkdtemp, rm, writeFile, chmod, mkdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { createWriteStream } from "node:fs";
import { readFile } from "node:fs/promises";

import { mintToken, ScenarioFailure, type Context, type Scenario } from "./harness.ts";
import { startFakeHermes, type FakeHermes } from "./fake-hermes.ts";
import { authScenario } from "./scenarios/auth.ts";
import { bindingScenario, DAD_BINDING } from "./scenarios/binding.ts";
import { agentsScenario } from "./scenarios/agents.ts";
import { chatScenario } from "./scenarios/chat.ts";
import { conversationHistoryScenario } from "./scenarios/conversation-history.ts";
import { usageCostScenario } from "./scenarios/usage-cost.ts";
import { workspaceScenario } from "./scenarios/workspace.ts";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const APP_PORT = 18080;
const JWT_SECRET = "smoke-secret-smoke-secret-smoke-secret";
const PROFILE_KEY = "dad-key";
const HEALTH_TIMEOUT_MS = 90_000;

/**
 * 표본 가격표다.
 *
 * <p>models.dev 카탈로그에서 필요한 모델만 뽑은 것이라 단가가 실제 값이다. 백엔드 테스트도 같은
 * 파일을 읽는다.
 */
const PRICING_CATALOG = join(
  ROOT,
  "backend",
  "src",
  "test",
  "resources",
  "pricing",
  "models-dev-sample.json",
);

const SCENARIOS: readonly Scenario[] = [
  authScenario,
  bindingScenario,
  agentsScenario,
  chatScenario,
  usageCostScenario,
  conversationHistoryScenario,
  workspaceScenario,
];

async function waitForHealth(url: string, logPath: string): Promise<void> {
  const deadline = Date.now() + HEALTH_TIMEOUT_MS;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch {
      // 아직 듣지 않는다. 다시 두드린다
    }
    await new Promise((done) => setTimeout(done, 1_000));
  }
  const log = await readFile(logPath, "utf-8").catch(() => "");
  throw new Error(`Control Plane 이 뜨지 않았다\n${log.split("\n").slice(-40).join("\n")}`);
}

/** profile 이름을 파일 이름으로 쓰는 mode 600 key 디렉터리를 만든다. 운영과 같은 모양이다. */
async function writeProfileKeys(work: string): Promise<string> {
  const keyDir = join(work, "keys");
  await mkdir(keyDir, { recursive: true });
  const keyFile = join(keyDir, DAD_BINDING.profileName);
  await writeFile(keyFile, PROFILE_KEY);
  await chmod(keyFile, 0o600);
  return keyDir;
}

function startControlPlane(keyDir: string, workspaceRoot: string, logPath: string): ChildProcess {
  const log = createWriteStream(logPath);
  const app = spawn("./gradlew", ["--no-daemon", "--quiet", "smokeRun"], {
    cwd: join(ROOT, "backend"),
    env: {
      ...process.env,
      DB_URL: "jdbc:h2:mem:smoke;MODE=MySQL;DB_CLOSE_DELAY=-1",
      DB_USERNAME: "sa",
      DB_PASSWORD: "",
      SERVER_PORT: String(APP_PORT),
      ASSISTANT_JWT_SECRET: JWT_SECRET,
      HERMES_PROFILE_KEY_DIR: keyDir,
      ASSISTANT_PRICING_CATALOG: PRICING_CATALOG,
      ASSISTANT_WORKSPACE_ROOT: workspaceRoot,
      SPRING_FLYWAY_ENABLED: "true",
      SPRING_JPA_HIBERNATE_DDL_AUTO: "validate",
      SPRING_DATASOURCE_DRIVER_CLASS_NAME: "org.h2.Driver",
    },
    // gradle 이 JVM 을 자식으로 띄운다. 프로세스 그룹으로 묶어야 끝낼 때 그 JVM 까지 함께 내려간다.
    detached: true,
    stdio: ["ignore", "pipe", "pipe"],
  });
  app.stdout?.pipe(log);
  app.stderr?.pipe(log);
  return app;
}

/** 실패한 자리에서 Control Plane 로그를 보여준다. */
async function printAppLog(logPath: string): Promise<void> {
  const log = await readFile(logPath, "utf-8").catch(() => "");
  if (log.length === 0) return;
  const interesting = log
    .split("\n")
    .filter((line) => /ERROR|Caused by|at com\.bifos/.test(line))
    .slice(-30);
  console.error("--- Control Plane 로그 ---");
  console.error(interesting.length > 0 ? interesting.join("\n") : log.split("\n").slice(-20).join("\n"));
}

async function main(): Promise<void> {
  const work = await mkdtemp(join(tmpdir(), "fos-assistant-e2e-"));
  const logPath = join(work, "app.log");
  let hermes: FakeHermes | undefined;
  let app: ChildProcess | undefined;

  try {
    console.log("== fake Hermes 기동");
    hermes = await startFakeHermes({ [DAD_BINDING.profileName]: PROFILE_KEY });
    console.log(`   ${hermes.baseUrl}`);

    console.log("== Control Plane 기동");
    const workspaceRoot = join(work, "workspaces");
    await mkdir(workspaceRoot, { recursive: true });
    app = startControlPlane(await writeProfileKeys(work), workspaceRoot, logPath);
    await waitForHealth(`http://127.0.0.1:${APP_PORT}/actuator/health`, logPath);
    console.log(`   http://127.0.0.1:${APP_PORT}`);

    const context: Context = {
      api: `http://127.0.0.1:${APP_PORT}/api/v1`,
      tokens: {
        dad: mintToken("dad@example.com", JWT_SECRET),
        kid: mintToken("kid@example.com", JWT_SECRET),
      },
      hermesBaseUrl: hermes.baseUrl,
      workspaceRoot,
    };

    for (const scenario of SCENARIOS) {
      console.log(`== ${scenario.name}`);
      await scenario.run(context);
    }

    console.log("\n모두 통과했다");
  } catch (error) {
    console.error(`\n실패: ${error instanceof ScenarioFailure ? error.message : String(error)}`);
    await printAppLog(logPath);
    process.exitCode = 1;
  } finally {
    if (app?.pid !== undefined) {
      try {
        process.kill(-app.pid, "SIGTERM");
      } catch {
        // 이미 내려갔다
      }
    }
    await hermes?.close();
    await rm(work, { recursive: true, force: true });
  }
}

await main();
