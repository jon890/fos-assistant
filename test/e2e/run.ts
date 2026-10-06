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
import { execFileSync, spawn, type ChildProcess } from "node:child_process";
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
import { skillsScenario } from "./scenarios/skills.ts";
import { memoryScenario } from "./scenarios/memory.ts";
import { memoryDocumentScenario } from "./scenarios/memory-document.ts";
import { memoryImportScenario } from "./scenarios/memory-import.ts";
import { chatScenario } from "./scenarios/chat.ts";
import { followUpScenario } from "./scenarios/follow-up.ts";
import { conversationHistoryScenario } from "./scenarios/conversation-history.ts";
import { conversationManageScenario } from "./scenarios/conversation-manage.ts";
import { usageCostScenario } from "./scenarios/usage-cost.ts";
import { streamingScenario } from "./scenarios/streaming.ts";
import { stopScenario } from "./scenarios/stop.ts";
import { E2E_STREAM_HEARTBEAT, streamHeartbeatScenario } from "./scenarios/stream-heartbeat.ts";
import { regenerateScenario } from "./scenarios/regenerate.ts";
import { orchestrationScenario, FLOW_BINDING } from "./scenarios/orchestration.ts";
import { modelSelectionScenario } from "./scenarios/model-selection.ts";
import { busyScenario } from "./scenarios/busy.ts";
import { attentionScenario } from "./scenarios/attention.ts";
import { chatAttachmentScenario } from "./scenarios/chat-attachment.ts";
import { artifactScenario } from "./scenarios/artifact.ts";
import { AGENT_TOOLS_PROFILE, agentToolsScenario } from "./scenarios/agent-tools.ts";
import { MCP_PRINCIPAL_PROFILE, mcpPrincipalScenario } from "./scenarios/mcp-principal.ts";
import { FOLLOW_UP_MCP_PROFILE, followUpMcpScenario } from "./scenarios/follow-up-mcp.ts";
import { agentLifecycleScenario } from "./scenarios/agent-lifecycle.ts";
import { NATIVE_DELEGATION_PROFILE, nativeDelegationScenario } from "./scenarios/native-delegation-mcp.ts";
import { DELEGATION_PROFILE, delegationScenario } from "./scenarios/delegation.ts";
import { connectorScenario } from "./scenarios/connector.ts";
import { connectorBindingScenario } from "./scenarios/connector-binding.ts";
import { connectorPolicyScenario } from "./scenarios/connector-policy.ts";
import { notificationsScenario } from "./scenarios/notifications.ts";
import { deliveryRetryScenario } from "./scenarios/delivery-retry.ts";
import { scheduledTaskScenario } from "./scenarios/scheduled-task.ts";
import { proactiveCheckScenario } from "./scenarios/proactive-check.ts";
import { CHAT_QUEUE_PROFILE, chatQueueRestartScenario, chatQueueScenario } from "./scenarios/chat-queue.ts";
import { restartReconcileScenario } from "./scenarios/restart-reconcile.ts";
import { userExecutionLimitScenario } from "./scenarios/user-execution-limit.ts";
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
  // 서비스 토큰은 주인이 허용 목록에 켜져 있어야 통하므로 aunt 가 허용 목록에 들어온 뒤에 둔다.
  // 문서를 지우고 끝나고 대화 turn 과 에이전트를 만들지 않아 뒤 시나리오의 Memory 주입과 사용량 세기에 걸리지 않는다.
  memoryDocumentScenario,
  // 대화 turn 을 돌리지 않고 들인 항목을 지우고 끝나므로 뒤 시나리오의 Memory 주입과 사용량 세기에 걸리지 않는다.
  memoryImportScenario,
  meScenario,
  bindingScenario,
  agentsScenario,
  agentAddressScenario,
  personaScenario,
  startersScenario,
  skillsScenario,
  memoryScenario,
  chatScenario,
  // turn 을 돌리지 않는다. 바로 뒤의 사용량 시나리오가 실행 수를 앞의 대화 turn 수와 같은지 본다.
  followUpScenario,
  usageCostScenario,
  conversationHistoryScenario,
  conversationManageScenario,
  streamingScenario,
  stopScenario,
  streamHeartbeatScenario,
  regenerateScenario,
  orchestrationScenario,
  chatAttachmentScenario,
  artifactScenario,
  mcpPrincipalScenario,
  followUpMcpScenario,
  nativeDelegationScenario,
  agentToolsScenario,
  // 아이의 개인 에이전트와 꺼진 에이전트가 목록에서 빠지는 것을 보므로 그 둘을 만드는 시나리오 뒤에 둔다.
  delegationScenario,
  agentLifecycleScenario,
  // 커넥터 시나리오들은 저마다 연결을 등록하고 붙일 에이전트를 만들며, 그 에이전트를 지우고 연결을 해제하는 것으로 끝난다.
  // 지운 에이전트가 목록에서 빠지지만 에이전트 수를 세는 시나리오 뒤에 둔다.
  connectorScenario,
  connectorBindingScenario,
  connectorPolicyScenario,
  notificationsScenario,
  // 승인 요청을 하나 더 만드므로 알림 수를 세는 시나리오 뒤에 둔다.
  deliveryRetryScenario,
  // 승인이 필요한 도구를 예약 turn 에서 부르므로 알림 시나리오와 같은 준비를 쓴다.
  scheduledTaskScenario,
  // 시험 커넥터를 다시 등록해 쓰고 해제로 끝나므로 커넥터 시나리오들 뒤에 둔다. 도구 호출 상한을 바꿔 Control Plane 을 다시 띄웠다가
  // 기본값으로 되돌려 다시 띄운다.
  proactiveCheckScenario,
  // 에이전트를 하나 만들고 끄므로 에이전트 수를 세는 시나리오 뒤에 둔다.
  chatQueueScenario,
  // 사용량 합계를 세는 시나리오 뒤에 둔다. 실패한 실행을 하나 더 남기기 때문이다.
  busyScenario,
  // 실패한 turn 을 하나 더 남기므로 사용량 합계를 세는 시나리오 뒤에 둔다.
  attentionScenario,
  // 막힌 provider 를 만들어 두고 끝나므로 turn 을 돌리는 시나리오 가운데 마지막에 둔다. 앞 시나리오가 그 막힘에 걸리지 않게 한다.
  modelSelectionScenario,
  // Control Plane 을 다시 띄우므로 맨 끝에 둔다. 막힌 provider 를 먼저 푼다.
  chatQueueRestartScenario,
  // 이것도 Control Plane 을 다시 띄운다. 앞 시나리오가 남긴 상태에 기대지 않는다.
  restartReconcileScenario,
  // 한도를 바꿔 Control Plane 을 다시 띄우므로 맨 끝에 둔다.
  userExecutionLimitScenario,
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

/**
 * 첨부 루트를 실행마다 만든다.
 *
 * <p>운영에서는 붙여 둔 디렉터리다. Control Plane 은 Hermes 에 도구 설정을 보내기 전에 이 아래에 주인의 디렉터리를 만들고,
 * 루트가 없으면 설정 쓰기를 409 로 멈춘다(ADR-089).
 */
async function makeAttachmentRoot(work: string): Promise<string> {
  const attachmentRoot = join(work, "attachments");
  await mkdir(attachmentRoot, { recursive: true });
  return attachmentRoot;
}

/**
 * 스킬 버전 디렉터리의 루트를 실행마다 만든다.
 *
 * <p>Control Plane 이 쓰는 경로와 Hermes 가 보는 경로가 같은 기계의 같은 디렉터리다. 대역이 게시된 경로에서 `SKILL.md` 를 읽는다.
 */
async function makeSkillRoot(work: string): Promise<string> {
  const skillRoot = join(work, "skills");
  await mkdir(skillRoot, { recursive: true });
  return skillRoot;
}

/** profile 이름을 파일 이름으로 쓰는 mode 600 key 디렉터리를 만든다. 운영과 같은 모양이다. */
async function writeProfileKeys(work: string): Promise<string> {
  const keyDir = join(work, "keys");
  await mkdir(keyDir, { recursive: true });
  for (const profileName of [DAD_BINDING.profileName, FLOW_BINDING.profileName, AGENT_TOOLS_PROFILE, MCP_PRINCIPAL_PROFILE, FOLLOW_UP_MCP_PROFILE, NATIVE_DELEGATION_PROFILE, DELEGATION_PROFILE, CHAT_QUEUE_PROFILE]) {
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
  artifactRoot: string,
  skillRoot: string,
  databaseFile: string,
): ChildProcess {
  // 다시 띄울 때 앞 실행의 로그를 지우지 않고 이어 쓴다.
  const log = createWriteStream(logPath, { flags: "a" });
  const app = spawn("./gradlew", ["--no-daemon", "--quiet", "smokeRun"], {
    cwd: join(ROOT, "backend"),
    env: {
      ...process.env,
      // 파일이라 프로세스를 다시 띄워도 같은 데이터를 읽는다. WRITE_DELAY=0 은 커밋을 바로 디스크에 내려
      // 강제 종료 직전의 커밋도 남게 한다. 운영 데이터베이스의 커밋과 같은 약속이다.
      DB_URL: `jdbc:h2:file:${databaseFile};MODE=MySQL;WRITE_DELAY=0`,
      DB_USERNAME: "sa",
      DB_PASSWORD: "",
      SERVER_PORT: String(APP_PORT),
      ASSISTANT_JWT_SECRET: JWT_SECRET,
      // 민감 Memory 문서를 만드는 시나리오가 쓴다. 운영 값이 아니라 글자 0123456789abcdef0123456789abcdef 의 base64 다.
      ASSISTANT_MEMORY_ENCRYPTION_ACTIVE_KEY_ID: "test-1",
      ASSISTANT_MEMORY_ENCRYPTION_KEYS: "test-1:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
      // 검사는 같은 사용자로 짧은 시간에 커넥터를 여러 번 부른다. 기본값 10회에 걸리지 않게 올린다.
      ASSISTANT_CONNECTOR_CALLS_PER_MINUTE: "1000",
      // 답이 없는 승인 요청이 만료되는 것을 기본값 24시간을 기다리지 않고 본다. 승인과 거절을 보는 단계는
      // 요청을 만든 직후에 답하므로 이 시간 안에 끝난다. 만료 정리도 1분이 아니라 1초마다 돌린다.
      ASSISTANT_CONNECTOR_POLICY_APPROVAL_TTL: "15s",
      ASSISTANT_CONNECTOR_POLICY_EXPIRE_CRON: "* * * * * *",
      // 예약 작업이 정한 시각에 발화하는 것을 기본값 30초를 기다리지 않고 본다.
      ASSISTANT_TASK_DISPATCH_CRON: "* * * * * *",
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
      // 띄운 대역이 실행마다 빈 포트를 받아 쓰므로 고정값으로 적을 수 없다. 실제 주소를 넘긴다.
      HERMES_SHARED_LISTENER_BASE_URL: dashboardBaseUrl,
      ASSISTANT_PRICING_CATALOG: PRICING_CATALOG,
      // 조용한 실행에서 주석 줄이 오는지 기본값 20초를 기다리지 않고 본다. 다른 시나리오의 스트림에도 섞여 흐른다.
      ASSISTANT_CHAT_STREAM_HEARTBEAT: E2E_STREAM_HEARTBEAT,
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

const STOP_TIMEOUT_MS = 30_000;

/** `root` 와 그 자손의 pid 를 모은다. gradle 이 JVM 을 별도 프로세스 그룹으로 띄울 수 있어 그룹만으로는 다 못 잡는다. */
function processTree(root: number): number[] {
  const table = execFileSync("ps", ["-axo", "pid=,ppid="], { encoding: "utf-8" });
  const children = new Map<number, number[]>();
  for (const line of table.split("\n")) {
    const [pid, ppid] = line.trim().split(/\s+/).map(Number);
    if (pid === undefined || ppid === undefined || Number.isNaN(pid) || Number.isNaN(ppid)) continue;
    children.set(ppid, [...(children.get(ppid) ?? []), pid]);
  }
  const found: number[] = [];
  const pending = [root];
  while (pending.length > 0) {
    const next = pending.pop()!;
    found.push(next);
    pending.push(...(children.get(next) ?? []));
  }
  return found;
}

function alive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

/** 프로세스 그룹과 그 자손 모두에 SIGKILL 을 보내고 전부 끝날 때까지 기다린다. */
async function killControlPlane(app: ChildProcess): Promise<void> {
  if (app.pid === undefined) return;
  const victims = processTree(app.pid);
  try {
    process.kill(-app.pid, "SIGKILL");
  } catch {
    // 이미 내려갔다
  }
  for (const pid of victims) {
    try {
      process.kill(pid, "SIGKILL");
    } catch {
      // 이미 내려갔다
    }
  }
  const deadline = Date.now() + STOP_TIMEOUT_MS;
  while (victims.some(alive)) {
    if (Date.now() > deadline) throw new ScenarioFailure("Control Plane 이 30초 안에 내려가지 않았다");
    await new Promise((done) => setTimeout(done, 100));
  }
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
    const skillRoot = await makeSkillRoot(work);
    console.log("== fake Hermes 기동");
    hermes = await startFakeHermes({
      [DAD_BINDING.profileName]: PROFILE_KEY,
      [FLOW_BINDING.profileName]: PROFILE_KEY,
      [AGENT_TOOLS_PROFILE]: PROFILE_KEY,
      [MCP_PRINCIPAL_PROFILE]: PROFILE_KEY,
      [FOLLOW_UP_MCP_PROFILE]: PROFILE_KEY,
      [NATIVE_DELEGATION_PROFILE]: PROFILE_KEY,
      [DELEGATION_PROFILE]: PROFILE_KEY,
      [CHAT_QUEUE_PROFILE]: PROFILE_KEY,
    }, undefined, {
      [DAD_BINDING.profileName]: ["fos-assistant"],
      [AGENT_TOOLS_PROFILE]: ["fos-assistant"],
      // 기본 toolset 에는 shell 과 file 이 있어 GROUP 에이전트로 등록되지 않는다. MCP 서버만 켠 profile 로 둔다.
      [MCP_PRINCIPAL_PROFILE]: ["fos-assistant"],
      [FOLLOW_UP_MCP_PROFILE]: ["fos-assistant"],
      [NATIVE_DELEGATION_PROFILE]: ["fos-assistant"],
      [DELEGATION_PROFILE]: ["fos-assistant"],
      [CHAT_QUEUE_PROFILE]: ["fos-assistant"],
    }, skillRoot);
    console.log(`   ${hermes.baseUrl}`);

    console.log("== Control Plane 기동");
    const keyDir = await writeProfileKeys(work);
    const artifactRoot = await makeArtifactRoot(work);
    const attachmentRoot = await makeAttachmentRoot(work);
    const hermesBaseUrl = hermes.baseUrl;
    const healthUrl = `http://127.0.0.1:${APP_PORT}/actuator/health`;
    const launch = (): ChildProcess =>
      startControlPlane(
        keyDir,
        hermesBaseUrl,
        logPath,
        attachmentRoot,
        artifactRoot,
        skillRoot,
        join(work, "smoke"),
      );
    app = launch();
    await waitForHealth(healthUrl, logPath);
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
      restartControlPlane: async () => {
        if (app !== undefined) await killControlPlane(app);
        app = launch();
        await waitForHealth(healthUrl, logPath);
      },
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
