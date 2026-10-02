/**
 * Control Plane 만 다시 떴을 때 Hermes 에서 계속 돈 실행의 답과 사용량이 남는지, Hermes 가 그 run 을
 * 잊었으면 실패로 정리되는지를 전체 흐름으로 본다.
 */
import { call, expect, expectStatus, fail, step, type Context, type Scenario } from "../harness.ts";
import { FAKE_USAGE } from "../fake-hermes.ts";
import { within } from "../delegation-support.ts";
import { awaitMessages, enqueue, holdTurn, messagesOf, pendingOf } from "./chat-queue.ts";

const TURN_TIMEOUT_MS = 15_000;

type Execution = {
  id: number;
  status: string;
  errorCode: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  latencyMs: number | null;
  estimatedCostMicros: number | null;
};
type RunningTurn = { running: boolean; executionId: number | null };

async function executionOf(context: Context, executionId: number): Promise<Execution | undefined> {
  const executions = expectStatus(
    await call(context, "/usage/executions?limit=50", { token: context.tokens.dad }),
    200,
    "사용량 조회",
  ).json<Execution[]>();
  return executions.find((execution) => execution.id === executionId);
}

/** 실행 줄이 조건을 만족할 때까지 다시 읽는다. */
async function awaitExecution(
  context: Context,
  executionId: number,
  predicate: (execution: Execution) => boolean,
  what: string,
): Promise<Execution> {
  const deadline = Date.now() + TURN_TIMEOUT_MS;
  let last: Execution | undefined;
  while (Date.now() < deadline) {
    last = await executionOf(context, executionId);
    if (last !== undefined && predicate(last)) return last;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  return fail(`${what}: ${TURN_TIMEOUT_MS / 1000}초 안에 기대한 상태가 되지 않았다. 마지막 실행 줄: ${JSON.stringify(last)}`);
}

async function runningOf(context: Context, conversationId: string): Promise<RunningTurn> {
  return expectStatus(
    await call(context, `/chat/conversations/${conversationId}/running`, { token: context.tokens.dad }),
    200,
    "도는 turn 조회",
  ).json<RunningTurn>();
}

function assistantCount(messages: { role: string }[]): number {
  return messages.filter((message) => message.role === "ASSISTANT").length;
}

export const restartReconcileScenario: Scenario = {
  name: "재시작 때 남은 실행 정리",

  async run(context) {
    // 앞 시나리오가 막아 둔 provider 가 남아 있으면 재시작 뒤 turn 이 거절된다.
    context.hermes.clearBlockedProviders();

    try {
      await hermesKeepsRunning(context);
      await hermesForgotRun(context);
    } finally {
      context.hermes.releaseHeldRun();
    }
  },
};

/**
 * 붙잡은 run 의 번호가 실행 줄에 적힐 때까지 기다린다.
 *
 * <p>제출을 받은 순간 내리면 run 번호가 적히기 전이라 그 줄은 대조하지 못하고 ORPHANED 로 남는다(ADR-061 이 남긴 한계).
 * 이 검사가 보려는 것은 번호가 적힌 줄의 대조이므로, Control Plane 이 사건 스트림을 연 뒤에 내린다.
 */
async function runNumberSaved(context: Context): Promise<void> {
  const run = context.hermes.heldRun();
  if (run === undefined) fail("붙잡은 turn 의 run 이 없다");
  await within(context.hermes.waitForRunEvents(run.runId), 5_000, "Control Plane 이 붙잡은 run 의 사건 스트림을 열지 않았다");
}

/** Control Plane 만 다시 뜨고 Hermes 의 run 은 계속 돈다. */
async function hermesKeepsRunning(context: Context): Promise<void> {
  step("도는 turn 이 있을 때 Control Plane 만 다시 띄운다");
  const held = await holdTurn(context, "재시작 대조 첫 글", "dad");
  const conversationId = held.started.conversationId!;
  const executionId = held.started.executionId!;
  await runNumberSaved(context);
  await context.restartControlPlane();

  step("다시 뜬 뒤에도 그 대화에 도는 turn 이 있고 보통 보내기는 거절된다");
  const running = await runningOf(context, conversationId);
  expect(
    running.running && running.executionId === executionId,
    `도는 turn 이 다시 붙지 않았다: ${JSON.stringify(running)}`,
  );
  const busy = expectStatus(
    await call(context, "/chat/messages", {
      method: "POST",
      token: context.tokens.dad,
      body: { text: "도는 중에 보통 보내기", agentCode: "dad", conversationId },
    }),
    409,
    "도는 turn 이 있는 대화에 보통 보내기",
  ).json<{ code: string }>();
  expect(busy.code === "CONVERSATION_BUSY", `보통 보내기의 오류가 다르다: ${busy.code}`);
  const queued = await enqueue(context, conversationId, "재시작 대조 대기 글");
  expect(queued.items.length === 1, `대기 메시지가 쌓이지 않았다: ${JSON.stringify(queued)}`);

  step("붙잡은 run 을 놓으면 답이 하나 남고 사용량이 기록된다");
  context.hermes.releaseHeldRun();
  await awaitMessages(
    context,
    conversationId,
    (list) => assistantCount(list) >= 1,
    TURN_TIMEOUT_MS,
    "다시 붙은 turn 의 답",
  );
  const finished = await awaitExecution(context, executionId, (each) => each.status === "SUCCEEDED", "다시 붙은 실행");
  expect(
    finished.inputTokens === FAKE_USAGE.input_tokens && finished.outputTokens === FAKE_USAGE.output_tokens,
    `토큰이 Hermes 가 돌려준 값과 다르다: ${finished.inputTokens} / ${finished.outputTokens}`,
  );
  expect(finished.estimatedCostMicros !== null, "환산 금액이 비어 있다");

  step("쌓아 둔 글이 USER 로 저장되고 그 답이 온다");
  const messages = await awaitMessages(
    context,
    conversationId,
    (list) => {
      const index = list.findIndex((message) => message.role === "USER" && message.content === "재시작 대조 대기 글");
      return index >= 0 && list.slice(index + 1).some((message) => message.role === "ASSISTANT");
    },
    TURN_TIMEOUT_MS,
    "쌓아 둔 글의 turn",
  );
  expect(
    assistantCount(messages) === 2,
    `답이 둘이어야 하는데 ${assistantCount(messages)} 개다: ${JSON.stringify(messages.map((m) => [m.role, m.content]))}`,
  );
  expect((await pendingOf(context, conversationId)).items.length === 0, "보낸 뒤에도 대기 줄이 남았다");

  step("한 번 더 다시 띄워도 답과 사용량이 그대로다");
  await context.restartControlPlane();
  const again = await messagesOf(context, conversationId);
  expect(
    again.length === messages.length && assistantCount(again) === 2,
    `다시 띄운 뒤 메시지 수가 달라졌다: ${messages.length} -> ${again.length}`,
  );
  const settled = await executionOf(context, executionId);
  expect(
    settled?.status === "SUCCEEDED" &&
      settled.inputTokens === finished.inputTokens &&
      settled.outputTokens === finished.outputTokens &&
      settled.latencyMs === finished.latencyMs,
    `다시 띄운 뒤 실행 줄이 달라졌다: ${JSON.stringify(finished)} -> ${JSON.stringify(settled)}`,
  );
}

/** Hermes 가 그 run 을 모르면 실패로 정리하고 대화를 풀어 준다. */
async function hermesForgotRun(context: Context): Promise<void> {
  step("Hermes 가 run 을 잊은 채 Control Plane 을 다시 띄운다");
  const held = await holdTurn(context, "잊힌 실행 첫 글", "dad");
  const conversationId = held.started.conversationId!;
  const executionId = held.started.executionId!;
  const run = context.hermes.heldRun();
  if (run === undefined) fail("붙잡은 turn 의 run 이 없다");
  await runNumberSaved(context);
  context.hermes.forgetRun(run.runId);
  await context.restartControlPlane();

  step("그 실행은 REMOTE_RUN_LOST 로 실패하고 대화는 풀린다");
  await awaitExecution(
    context,
    executionId,
    (each) => each.status === "FAILED" && each.errorCode === "REMOTE_RUN_LOST",
    "잊힌 실행",
  );
  const running = await runningOf(context, conversationId);
  expect(!running.running, `실패한 실행의 대화에 도는 turn 이 남았다: ${JSON.stringify(running)}`);
  const before = assistantCount(await messagesOf(context, conversationId));
  expectStatus(
    await call(context, "/chat/messages", {
      method: "POST",
      token: context.tokens.dad,
      body: { text: "잊힌 뒤 다시 보낸 글", agentCode: "dad", conversationId },
    }),
    200,
    "풀린 대화에 다시 보내기",
  );
  const messages = await messagesOf(context, conversationId);
  expect(assistantCount(messages) === before + 1, `다시 보낸 글의 답이 오지 않았다: ${JSON.stringify(messages.map((m) => [m.role, m.content]))}`);
}
