/**
 * 답이 도는 중 입력창에서 보낸 글을 도는 실행에 끼워 넣는 흐름(steer)을 본다(ADR-20261116 / run-steer).
 *
 * <p>E2E 는 설정을 켜고 돈다. 가짜 Hermes 는 steer 검사 입력으로 연 실행만 받으므로 다른 대기열 검사는 그대로다.
 * 대기 글로 연 turn 은 사용자가 연 스트림이 없어 메시지 목록을 다시 읽어 기다린다.
 */
import { call, expect, expectStatus, fail, step, type Context } from "../harness.ts";
import { within } from "../delegation-support.ts";
import { STEER_PROBE, STEER_UNREAD_PROBE } from "../fake-hermes.ts";
import { awaitMessages, holdTurn, pendingOf } from "./chat-queue.ts";

const TURN_TIMEOUT_MS = 15_000;

type SteeredMessage = {
  id: number;
  role: "USER" | "ASSISTANT" | "SYSTEM";
  content: string;
  steer?: { state: string; child: string | null } | null;
};
type PendingQueue = { held: boolean; items: { id: number; text: string; messageId?: number | null; steering?: boolean }[] };

async function steeredMessages(context: Context, conversationId: string): Promise<SteeredMessage[]> {
  return expectStatus(
    await call(context, `/chat/conversations/${conversationId}/messages`, { token: context.tokens.dad }),
    200,
    "steer 검사 대화 이력 조회",
  ).json<SteeredMessage[]>();
}

/** 화면의 입력창 경로와 같이 `steer` 표시를 싣거나 빼고 대기 경로로 보낸다. */
async function send(context: Context, conversationId: string, text: string, steer: boolean): Promise<PendingQueue> {
  return expectStatus(
    await call(context, `/chat/conversations/${conversationId}/pending`, {
      method: "POST",
      token: context.tokens.dad,
      body: { text, steer },
    }),
    201,
    `대기 경로로 보내기(${text}, steer=${steer})`,
  ).json<PendingQueue>();
}

function bubbleOf(messages: SteeredMessage[], text: string): SteeredMessage {
  const bubble = messages.find((message) => message.role === "USER" && message.content === text && message.steer);
  if (bubble === undefined) fail(`steer 말풍선이 없다(${text}): ${JSON.stringify(messages.map((m) => [m.role, m.content, m.steer?.state]))}`);
  return bubble;
}

export async function steerFlow(context: Context): Promise<void> {
  try {
    step("입력창 글은 도는 실행에 끼워 넣고 그 turn 의 답 바로 위에 「전달됨」 말풍선으로 남는다");
    const first = await holdTurn(context, STEER_PROBE, "dad");
    const conversationId = first.started.conversationId!;
    const steered = await send(context, conversationId, "2만원 이하로", true);
    expect(steered.items.length === 0, `받은 글이 대기 줄에 남았다: ${JSON.stringify(steered)}`);
    const steerInputs = context.hermes.steerInputs();
    expect(steerInputs.at(-1)?.input === "2만원 이하로", `steer 요청 본문이 그 글이 아니다: ${JSON.stringify(steerInputs)}`);
    const during = await steeredMessages(context, conversationId);
    expect(bubbleOf(during, "2만원 이하로").steer?.state === "DELIVERED", "도는 동안 말풍선이 「전달됨」 이 아니다");

    step("말풍선이 있으면 표시 없는 글(카드 답)은 끼우지 않고 대기 줄에 남아 다음 turn 으로 간다");
    const queued = await send(context, conversationId, "카드 답", false);
    expect(queued.items.length === 1 && queued.items[0]!.steering !== true, `표시 없는 글이 대기 줄에 없다: ${JSON.stringify(queued)}`);
    expect(context.hermes.steerInputs().length === steerInputs.length, "표시 없는 글에 steer 를 불렀다");
    context.hermes.releaseHeldRun();
    await within(first.turn.completed, 10_000, "steer 검사 turn 의 스트림이 끝나지 않았다");
    const messages = await awaitMessages(
      context,
      conversationId,
      (list) => list.at(-2)?.content === "카드 답" && list.at(-1)?.role === "ASSISTANT",
      TURN_TIMEOUT_MS,
      "대기 글 turn",
    );
    const ordered = (await steeredMessages(context, conversationId)).slice(-5);
    expect(
      ordered.map((message) => `${message.role}:${message.steer ? "steer" : ""}`).join(",")
        === "USER:,USER:steer,ASSISTANT:,USER:,ASSISTANT:",
      `질문, 말풍선, 답의 순서가 다르다: ${JSON.stringify(ordered.map((m) => [m.role, m.content]))}`,
    );
    expect(ordered[1]!.steer?.state === "DELIVERED", "넣은 글로 끝난 turn 의 말풍선이 「전달됨」 이 아니다");

    step("모델에 넣지 못하고 돌아온 글은 「다음 차례로 넘어감」 이 되고 새 질문 없이 바로 다음 turn 이 된다");
    const before = messages.length;
    const second = await holdTurn(context, STEER_UNREAD_PROBE, "dad", conversationId);
    await send(context, conversationId, "반말로", true);
    context.hermes.releaseHeldRun();
    await within(second.turn.completed, 10_000, "돌아온 글 검사 turn 의 스트림이 끝나지 않았다");
    await awaitMessages(
      context,
      conversationId,
      (list) => list.length >= before + 4 && list.at(-1)?.role === "ASSISTANT" && list.at(-2)?.role === "ASSISTANT",
      TURN_TIMEOUT_MS,
      "돌아온 글로 연 turn",
    );
    const carried = (await steeredMessages(context, conversationId)).slice(before);
    expect(
      carried.map((message) => message.role).join(",") === "USER,USER,ASSISTANT,ASSISTANT",
      `돌아온 글 turn 의 줄이 다르다: ${JSON.stringify(carried.map((m) => [m.role, m.content]))}`,
    );
    expect(bubbleOf(carried, "반말로").steer?.state === "CARRIED_OVER", "돌아온 글이 「다음 차례로 넘어감」 이 아니다");
    expect(carried.filter((message) => message.content === "반말로").length === 1, "돌아온 글의 사용자 말풍선이 둘이다");
    expect(context.hermes.lastSubmittedInput()?.endsWith("반말로") === true, "다음 turn 의 Hermes 입력이 돌아온 글이 아니다");

    // 넣기 전에 중지해 Hermes 가 그 글을 `pending_steer` 로 돌려주는 경우다. 넣은 뒤 중지해 돌려준 글이 없는 경우(「전달됐는지 확인 못 함」)는
    // 가짜 Hermes 가 글을 넣는 때와 중지가 겨뤄 흔들리므로 `SteerSettleTest` 가 본다.
    step("넣기 전에 중지하면 돌려준 글의 말풍선은 「전달 안 됨」 이 되고 같은 글이 멈춘 대기 줄로 돌아온다");
    const third = await holdTurn(context, STEER_UNREAD_PROBE, "dad", conversationId);
    await send(context, conversationId, "그거 말고 위 것", true);
    expectStatus(
      await call(context, `/chat/executions/${third.started.executionId}/stop`, { method: "POST", token: context.tokens.dad }),
      202,
      "steer 검사 turn 중지",
    );
    await within(third.turn.completed, 10_000, "중지 뒤 스트림이 끝나지 않았다");
    const deadline = Date.now() + 10_000;
    let held = await pendingOf(context, conversationId) as PendingQueue;
    while (!held.held && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 100));
      held = await pendingOf(context, conversationId) as PendingQueue;
    }
    const stopped = bubbleOf(await steeredMessages(context, conversationId), "그거 말고 위 것");
    expect(stopped.steer?.state === "NOT_DELIVERED", `중지 뒤 말풍선 상태가 다르다: ${stopped.steer?.state}`);
    expect(
      held.held && held.items.length === 1 && held.items[0]!.messageId === stopped.id,
      `멈춘 대기 줄에 말풍선 번호가 붙은 글이 없다: ${JSON.stringify(held)}`,
    );
    expectStatus(
      await call(context, `/chat/conversations/${conversationId}/pending/${held.items[0]!.id}`, {
        method: "DELETE",
        token: context.tokens.dad,
      }),
      204,
      "되돌린 글 취소",
    );
    const cancelled = bubbleOf(await steeredMessages(context, conversationId), "그거 말고 위 것");
    expect(cancelled.steer?.state === "NOT_DELIVERED", "취소한 뒤 말풍선이 「전달 안 됨」 으로 남지 않았다");
  } finally {
    context.hermes.releaseHeldRun();
  }
}
