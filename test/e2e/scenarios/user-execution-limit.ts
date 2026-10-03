/**
 * 사용자 한 명의 동시 실행 한도를 지키는지 재고, 한도마다 처리량과 응답 시간을 출력한다.
 *
 * <p>가짜 Hermes 가 새 실행을 일정 시간 붙잡도록 하고, 한 사용자가 한도보다 2 개 많은 대화를 한꺼번에 보낸다.
 * 같은 순간 다른 사용자가 하나를 보내 한 사용자의 한도가 다른 사용자를 막지 않는지도 본다.
 * 지연은 정한 값이라 실제 모델이 걸리는 시간이 아니다. 글 본문은 합성 글이고 출력하지 않는다.
 *
 * <p>기본 한도 하나만 잰다. `E2E_MEASURE_USER_LIMITS=2,6` 처럼 주면 그 한도들을 더 잰다.
 */
import { call, expect, expectStatus, step, type Context, type Scenario } from "../harness.ts";

/** 설정의 기본값이다. 이 값은 환경 변수를 주지 않고 잰다. */
const DEFAULT_LIMIT = 4;
const LIMIT_ENV = "ASSISTANT_USER_EXECUTION_MAX_RUNNING";
/** 가짜 Hermes 가 새 실행을 붙잡는 시간이다. 문서의 측정 조건이 이 값을 적는다. */
const RUN_DELAY_MS = 1_500;
/** 한도보다 몇 개 많이 보내는가 */
const OVERFLOW = 2;

type AgentView = { code: string };
type AdminAgentView = { code: string; hermesProfile: string };
type Execution = { id: number; status: string };
type Failure = { code: string };
type Timed = { status: number; body: string; startedAt: number; elapsedMs: number };

function measuredLimits(): number[] {
  const extra = (process.env.E2E_MEASURE_USER_LIMITS ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter((value) => value.length > 0)
    .map(Number);
  for (const limit of extra) {
    expect(Number.isInteger(limit) && limit >= 2, `E2E_MEASURE_USER_LIMITS 의 값이 2 이상의 정수가 아니다: ${limit}`);
  }
  return [DEFAULT_LIMIT, ...extra.filter((limit) => limit !== DEFAULT_LIMIT)].filter(
    (limit, index, all) => all.indexOf(limit) === index,
  );
}

function percentile(sorted: number[], fraction: number): number {
  return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * fraction))]!;
}

async function send(context: Context, token: string, text: string, agentCode: string): Promise<Timed> {
  const startedAt = performance.now();
  const response = await call(context, "/chat/messages", {
    method: "POST",
    token,
    body: { text, agentCode },
  });
  return { status: response.status, body: response.body, startedAt, elapsedMs: performance.now() - startedAt };
}

async function conversationCount(context: Context, token: string): Promise<number> {
  const page = expectStatus(
    await call(context, "/chat/conversations?limit=100", { token }),
    200,
    "대화 목록",
  ).json<{ items: unknown[] }>();
  return page.items.length;
}

/** 그 사용자의 루트 실행이 모두 끝날 때까지 기다린다. */
async function waitUntilIdle(context: Context, token: string): Promise<void> {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const executions = expectStatus(
      await call(context, "/usage/executions?limit=50", { token }),
      200,
      "실행 기록",
    ).json<Execution[]>();
    if (!executions.some((execution) => execution.status === "RUNNING")) return;
    await new Promise((done) => setTimeout(done, 200));
  }
  expect(false, "30초 안에 실행 중인 실행이 끝나지 않았다");
}

async function measure(
  context: Context,
  limit: number,
  aunt: { agentCode: string; profile: string },
  kidAgentCode: string,
): Promise<void> {
  const sent = limit + OVERFLOW;
  step(`한도 ${limit}: 이모가 ${sent} 개를 한꺼번에 보낸다`);
  await waitUntilIdle(context, context.tokens.aunt);
  await waitUntilIdle(context, context.tokens.kid);
  const conversationsBefore = await conversationCount(context, context.tokens.aunt);
  context.hermes.resetRunConcurrency();

  const auntRequests = Array.from({ length: sent }, (_, index) =>
    send(context, context.tokens.aunt, `부하 측정 ${limit}-${index + 1}`, aunt.agentCode),
  );
  const kidRequest = send(context, context.tokens.kid, `부하 측정 ${limit}-kid`, kidAgentCode);
  const results = await Promise.all(auntRequests);
  const kid = await kidRequest;

  const accepted = results.filter((result) => result.status === 200);
  const rejected = results.filter((result) => result.status !== 200);
  expect(
    accepted.length === limit,
    `한도 ${limit} 에서 받아들여진 수가 다르다: ${accepted.length} (${results.map((result) => result.status).join(",")})`,
  );
  expect(
    rejected.length === OVERFLOW && rejected.every((result) => result.status === 409),
    `넘친 요청이 모두 409 로 거절되지 않았다: ${rejected.map((result) => result.status).join(",")}`,
  );
  for (const result of rejected) {
    const code = (JSON.parse(result.body) as Failure).code;
    expect(code === "USER_BUSY", `거절 본문의 code 가 USER_BUSY 가 아니다: ${code}`);
  }

  step(`한도 ${limit}: 거절된 요청은 대화를 만들지 않고 다른 사용자의 요청은 받아들여진다`);
  const conversationsAfter = await conversationCount(context, context.tokens.aunt);
  expect(
    conversationsAfter - conversationsBefore === accepted.length,
    `대화가 받아들여진 수만큼 늘지 않았다: ${conversationsAfter - conversationsBefore} 개 늘었다`,
  );
  expect(kid.status === 200, `다른 사용자의 요청이 거절됐다: ${kid.status}`);

  const concurrency = context.hermes.runConcurrency();
  const auntMax = concurrency.maxByProfile[aunt.profile] ?? 0;
  expect(auntMax <= limit, `한 사용자의 동시 실행이 한도를 넘었다: ${auntMax} > ${limit}`);
  expect(auntMax >= 1, "측정한 사용자의 실행을 가짜 Hermes 가 세지 못했다");

  step(`한도 ${limit}: 모두 끝난 뒤 하나를 더 보내면 받아들여진다`);
  await waitUntilIdle(context, context.tokens.aunt);
  const after = await send(context, context.tokens.aunt, `부하 측정 ${limit}-뒤`, aunt.agentCode);
  expect(after.status === 200, `자리가 돌아오지 않았다: ${after.status}`);

  const acceptedMs = accepted.map((result) => result.elapsedMs).sort((a, b) => a - b);
  const rejectedMaxMs = Math.max(...rejected.map((result) => result.elapsedMs));
  console.log(
    `   측정 한도=${limit} 보냄=${sent} 받아들여짐=${accepted.length} 거절=${rejected.length}`
      + ` 이모 동시 최댓값=${auntMax} 전체 동시 최댓값=${concurrency.maxTotal}`
      + ` 받아들여진 응답 ms 최소=${Math.round(acceptedMs[0]!)} 중앙값=${Math.round(percentile(acceptedMs, 0.5))}`
      + ` 최대=${Math.round(acceptedMs[acceptedMs.length - 1]!)} 거절 응답 ms 최대=${Math.round(rejectedMaxMs)}`,
  );
}

export const userExecutionLimitScenario: Scenario = {
  name: "사용자 동시 실행 한도 측정",

  async run(context) {
    const limits = measuredLimits();

    step("이모의 에이전트와 그 profile 을 찾고 아이가 쓸 에이전트를 만든다");
    const auntAgents = expectStatus(
      await call(context, "/agents", { token: context.tokens.aunt }),
      200,
      "이모의 에이전트 목록",
    ).json<AgentView[]>();
    expect(auntAgents.length >= 1, "이모의 에이전트가 없다");
    const auntAgent = auntAgents[0]!;
    const profile = expectStatus(
      await call(context, "/admin/agents", { token: context.tokens.dad }),
      200,
      "관리 목록",
    ).json<AdminAgentView[]>().find((agent) => agent.code === auntAgent.code)?.hermesProfile;
    expect(profile !== undefined, "이모의 에이전트 profile 을 찾지 못했다");
    const kidAgent = expectStatus(
      await call(context, "/agents", {
        method: "POST",
        token: context.tokens.kid,
        body: { name: "부하 측정 도우미" },
      }),
      201,
      "아이의 에이전트 만들기",
    ).json<AgentView>();

    context.hermes.slowRuns(RUN_DELAY_MS);
    try {
      for (const limit of limits) {
        if (limit === DEFAULT_LIMIT) {
          delete process.env[LIMIT_ENV];
        } else {
          process.env[LIMIT_ENV] = String(limit);
          await context.restartControlPlane();
        }
        await measure(context, limit, { agentCode: auntAgent.code, profile: profile! }, kidAgent.code);
      }
    } finally {
      context.hermes.slowRuns(undefined);
      const restoreDefault = process.env[LIMIT_ENV] !== undefined;
      delete process.env[LIMIT_ENV];
      if (restoreDefault) await context.restartControlPlane();
      await call(context, `/agents/${kidAgent.code}`, { method: "DELETE", token: context.tokens.kid });
    }
  },
};
