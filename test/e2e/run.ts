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

import {
  mintSignInToken,
  mintToken,
  ScenarioFailure,
  type Context,
  type Scenario,
} from "./harness.ts";
import { FAKE_DASHBOARD_TOKEN, startFakeHermes, type FakeHermes } from "./fake-hermes.ts";
import { authScenario } from "./scenarios/auth.ts";
import { signInScenario } from "./scenarios/signin.ts";
import { peopleScenario, NEW_PERSON } from "./scenarios/people.ts";
import { meScenario } from "./scenarios/me.ts";
import { bindingScenario, DAD_BINDING } from "./scenarios/binding.ts";
import { agentsScenario } from "./scenarios/agents.ts";
import { agentAddressScenario } from "./scenarios/agent-address.ts";
import { personaScenario } from "./scenarios/persona.ts";
import { startersScenario } from "./scenarios/starters.ts";
import { memoryScenario } from "./scenarios/memory.ts";
import { chatScenario } from "./scenarios/chat.ts";
import { conversationHistoryScenario } from "./scenarios/conversation-history.ts";
import { conversationManageScenario } from "./scenarios/conversation-manage.ts";
import { usageCostScenario } from "./scenarios/usage-cost.ts";
import { streamingScenario } from "./scenarios/streaming.ts";
import { orchestrationScenario, FLOW_BINDING } from "./scenarios/orchestration.ts";
import { modelSelectionScenario } from "./scenarios/model-selection.ts";
import { busyScenario } from "./scenarios/busy.ts";
import { chatAttachmentScenario } from "./scenarios/chat-attachment.ts";
import { pickPort } from "../support/pick-port.ts";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const APP_PORT = pickPort("E2E_APP_PORT", 18_080);
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
  signInScenario,
  peopleScenario,
  meScenario,
  bindingScenario,
  agentsScenario,
  agentAddressScenario,
  personaScenario,
  startersScenario,
  memoryScenario,
  chatScenario,
  usageCostScenario,
  conversationHistoryScenario,
  conversationManageScenario,
  streamingScenario,
  orchestrationScenario,
  chatAttachmentScenario,
  // 사용량 합계를 세는 시나리오 뒤에 둔다. 실패한 실행을 하나 더 남기기 때문이다.
  busyScenario,
  // 막힌 provider 를 만들어 두고 끝나므로 마지막에 둔다. 앞 시나리오가 그 막힘에 걸리지 않게 한다.
  modelSelectionScenario,
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
  for (const profileName of [DAD_BINDING.profileName, FLOW_BINDING.profileName]) {
    const keyFile = join(keyDir, profileName);
    await writeFile(keyFile, PROFILE_KEY);
    await chmod(keyFile, 0o600);
  }
  return keyDir;
}

function startControlPlane(
  keyDir: string,
  dashboardBaseUrl: string,
  logPath: string,
  attachmentRoot: string,
): ChildProcess {
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
      // 기본값이 없어 주지 않으면 기동하지 못한다. 실행마다 만든 임시 디렉터리 아래에 둔다.
      ASSISTANT_ATTACHMENT_ROOT: attachmentRoot,
      // 에이전트 쪽에서 보는 경로다. 검사는 그 경로를 열지 않고 입력에 적힌 글자만 본다.
      ASSISTANT_ATTACHMENT_AGENT_ROOT: "/agent-side/attachments",
      HERMES_DASHBOARD_BASE_URL: dashboardBaseUrl,
      HERMES_DASHBOARD_TOKEN: FAKE_DASHBOARD_TOKEN,
      // 띄운 대역이 실행마다 빈 포트를 받아 쓰므로 고정값으로 적을 수 없다. 실제 주소를 넘긴다.
      HERMES_SHARED_LISTENER_BASE_URL: dashboardBaseUrl,
      ASSISTANT_PRICING_CATALOG: PRICING_CATALOG,
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
    hermes = await startFakeHermes({
      [DAD_BINDING.profileName]: PROFILE_KEY,
      [FLOW_BINDING.profileName]: PROFILE_KEY,
    });
    console.log(`   ${hermes.baseUrl}`);

    console.log("== Control Plane 기동");
    app = startControlPlane(
      await writeProfileKeys(work),
      hermes.baseUrl,
      logPath,
      join(work, "attachments"),
    );
    await waitForHealth(`http://127.0.0.1:${APP_PORT}/actuator/health`, logPath);
    console.log(`   http://127.0.0.1:${APP_PORT}`);

    const context: Context = {
      api: `http://127.0.0.1:${APP_PORT}/api/v1`,
      tokens: {
        dad: mintToken("dad@example.com", JWT_SECRET),
        kid: mintToken("kid@example.com", JWT_SECRET),
        aunt: mintToken(NEW_PERSON.email, JWT_SECRET),
        signin: mintSignInToken(JWT_SECRET),
      },
      hermesBaseUrl: hermes.baseUrl,
      hermesProfileKey: PROFILE_KEY,
      hermes,
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
