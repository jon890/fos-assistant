/**
 * 응답 중에 보낸 글이 대기 줄에 쌓였다가 앞 turn 이 끝나면 합쳐 다음 turn 으로 가는 것을 전체 흐름으로 본다.
 *
 * <p>대기 메시지로 연 turn 은 사용자가 연 스트림이 없다. 그 turn 의 끝은 메시지 목록을 다시 읽어 기다린다.
 * 연결하기 전에 지나간 사건을 놓치지 않기 때문이다.
 */
import { call, expect, expectStatus, fail, step, type Context, type Scenario } from "../harness.ts";
import { awaitStatus, callTool, contextFor, openStream, parsed, tree, within, type ChatEvent, type Status } from "../delegation-support.ts";

export const CHAT_QUEUE_PROFILE = "chat-queue-group";

const QUEUE_AGENT_CODE = "queue-order";
const TURN_TIMEOUT_MS = 15_000;

type Message = { id: number; role: "USER" | "ASSISTANT" | "SYSTEM"; content: string };
type PendingItem = { id: number; text: string; createdAt: string };
type PendingQueue = { held: boolean; items: PendingItem[] };
type TurnStream = Awaited<ReturnType<typeof openStream>>;

export async function messagesOf(context: Context, conversationId: string): Promise<Message[]> {
  return expectStatus(
    await call(context, `/chat/conversations/${conversationId}/messages`, { token: context.tokens.dad }),
    200,
    "대기열 검사 대화 이력 조회",
  ).json<Message[]>();
}

/** 대화의 메시지 목록을 조건이 참이 될 때까지 다시 읽는다. */
export async function awaitMessages(
  context: Context,
  conversationId: string,
  predicate: (messages: Message[]) => boolean,
  timeoutMs: number,
  what: string,
): Promise<Message[]> {
  const deadline = Date.now() + timeoutMs;
  let last: Message[] = [];
  while (Date.now() < deadline) {
    last = await messagesOf(context, conversationId);
    if (predicate(last)) return last;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  fail(`${what}: ${timeoutMs / 1000}초 안에 기대한 메시지가 오지 않았다. 마지막 목록: ${JSON.stringify(last.map((m) => [m.role, m.content]))}`);
}

export async function pendingOf(context: Context, conversationId: string): Promise<PendingQueue> {
  return expectStatus(
    await call(context, `/chat/conversations/${conversationId}/pending`, { token: context.tokens.dad }),
    200,
    "대기 줄 조회",
  ).json<PendingQueue>();
}

async function awaitHeld(context: Context, conversationId: string): Promise<PendingQueue> {
  const deadline = Date.now() + 10_000;
  let last: PendingQueue | undefined;
  while (Date.now() < deadline) {
    last = await pendingOf(context, conversationId);
    if (last.held) return last;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  return fail(`대기 줄이 10초 안에 멈추지 않았다: ${JSON.stringify(last)}`);
}

export async function enqueue(context: Context, conversationId: string, text: string): Promise<PendingQueue> {
  return expectStatus(
    await call(context, `/chat/conversations/${conversationId}/pending`, {
      method: "POST",
      token: context.tokens.dad,
      body: { text },
    }),
    201,
    `대기 메시지 더하기(${text})`,
  ).json<PendingQueue>();
}

async function cancelPending(context: Context, conversationId: string, pendingId: number): Promise<void> {
  expectStatus(
    await call(context, `/chat/conversations/${conversationId}/pending/${pendingId}`, {
      method: "DELETE",
      token: context.tokens.dad,
    }),
    204,
    "대기 메시지 취소",
  );
}

/** 다음 run 을 붙잡고 turn 을 열어 `started` 사건과 run 이 제출된 것까지 기다린다. */
export async function holdTurn(
  context: Context,
  text: string,
  agentCode: string,
  conversationId?: string,
): Promise<{ turn: TurnStream; started: ChatEvent }> {
  context.hermes.holdNextRun();
  const turn = await openStream(context, text, agentCode, conversationId);
  await within(context.hermes.waitForHeldRun(), 5_000, "가짜 Hermes 가 turn 의 실행을 받지 않았다");
  const started = await within(turn.started, 5_000, "turn 의 started 사건을 받지 못했다");
  return { turn, started };
}

function lastRoles(messages: Message[], count: number): string {
  return messages.slice(-count).map((message) => message.role).join(",");
}

export const chatQueueScenario: Scenario = {
  name: "응답 중에 보낸 메시지 대기열",

  async run(context) {
    let conversationId: string | undefined;

    try {
      step("둘을 쌓으면 앞 turn 이 끝난 뒤 빈 줄 하나로 이어 한 turn 으로 간다");
      const first = await holdTurn(context, "대기열 첫 글", "dad");
      conversationId = first.started.conversationId!;
      await enqueue(context, conversationId, "첫째 대기 글");
      const queued = await enqueue(context, conversationId, "둘째 대기 글");
      expect(queued.items.length === 2, `대기 줄이 두 줄이 아니다: ${JSON.stringify(queued)}`);
      const listed = await pendingOf(context, conversationId);
      expect(listed.items.length === 2 && !listed.held, `조회한 대기 줄이 다르다: ${JSON.stringify(listed)}`);
      context.hermes.releaseHeldRun();
      await within(first.turn.completed, 5_000, "앞 turn 의 스트림이 끝나지 않았다");
      const merged = "첫째 대기 글\n\n둘째 대기 글";
      let messages = await awaitMessages(
        context,
        conversationId,
        (list) => list.length >= 4 && lastRoles(list, 4) === "USER,ASSISTANT,USER,ASSISTANT",
        TURN_TIMEOUT_MS,
        "합쳐 보낸 turn",
      );
      expect(messages.at(-2)?.content === merged, `합친 글이 다르다: ${JSON.stringify(messages.at(-2)?.content)}`);
      const submitted = context.hermes.lastSubmittedInput();
      expect(submitted?.endsWith(merged) === true, `Hermes 입력이 합친 글로 끝나지 않는다: ${JSON.stringify(submitted)}`);
      expect((await pendingOf(context, conversationId)).items.length === 0, "합쳐 보낸 뒤에도 대기 줄이 남았다");

      step("대기 중에 취소한 글은 보내지 않는다");
      const before = messages.length;
      const second = await holdTurn(context, "취소 검사 첫 글", "dad", conversationId);
      await enqueue(context, conversationId, "취소할 글");
      const kept = await enqueue(context, conversationId, "남길 글");
      const cancelId = kept.items[0]!.id;
      await cancelPending(context, conversationId, cancelId);
      const again = expectStatus(
        await call(context, `/chat/conversations/${conversationId}/pending/${cancelId}`, {
          method: "DELETE",
          token: context.tokens.dad,
        }),
        404,
        "이미 취소한 대기 메시지 취소",
      ).json<{ code: string }>();
      expect(again.code === "PENDING_MESSAGE_NOT_FOUND", `없는 대기 메시지 오류가 다르다: ${again.code}`);
      context.hermes.releaseHeldRun();
      await within(second.turn.completed, 5_000, "취소 검사 스트림이 끝나지 않았다");
      messages = await awaitMessages(
        context,
        conversationId,
        (list) => list.length >= before + 4 && lastRoles(list, 4) === "USER,ASSISTANT,USER,ASSISTANT",
        TURN_TIMEOUT_MS,
        "취소하고 남은 글의 turn",
      );
      expect(messages.at(-2)?.content === "남길 글", `남은 글이 다르다: ${JSON.stringify(messages.at(-2)?.content)}`);
      expect(!messages.some((message) => message.content.includes("취소할 글")), "취소한 글이 대화에 들어갔다");

      step("중지하면 대기 줄을 멈춰 두고 보내기를 눌러야 간다");
      const third = await holdTurn(context, "중지 검사 첫 글", "dad", conversationId);
      await enqueue(context, conversationId, "멈춘 뒤 보낼 글");
      expectStatus(
        await call(context, `/chat/executions/${third.started.executionId}/stop`, {
          method: "POST",
          token: context.tokens.dad,
        }),
        202,
        "대기열 검사 turn 중지",
      );
      await within(third.turn.completed, 10_000, "중지 뒤 스트림이 끝나지 않았다");
      const held = await awaitHeld(context, conversationId);
      expect(held.items.length === 1, `멈춘 대기 줄이 한 줄이 아니다: ${JSON.stringify(held)}`);
      await new Promise((resolve) => setTimeout(resolve, 1_000));
      messages = await messagesOf(context, conversationId);
      expect(
        !messages.some((message) => message.role === "USER" && message.content === "멈춘 뒤 보낼 글"),
        "멈춘 대기 줄의 글이 저절로 보내졌다",
      );
      expectStatus(
        await call(context, `/chat/conversations/${conversationId}/pending/send`, {
          method: "POST",
          token: context.tokens.dad,
        }),
        202,
        "멈춘 대기 줄 보내기",
      );
      await awaitMessages(
        context,
        conversationId,
        (list) => {
          const index = list.findIndex((message) => message.role === "USER" && message.content === "멈춘 뒤 보낼 글");
          return index >= 0 && list.slice(index + 1).some((message) => message.role === "ASSISTANT");
        },
        TURN_TIMEOUT_MS,
        "보내기 뒤 turn",
      );

      step("대기 줄은 다섯 줄까지이고 남의 대화에서는 보이지 않는다");
      const fourth = await holdTurn(context, "상한 검사 첫 글", "dad", conversationId);
      let full = await pendingOf(context, conversationId);
      for (let index = 1; index <= 5; index += 1) {
        full = await enqueue(context, conversationId, `대기 글 ${index}`);
      }
      expect(full.items.length === 5, `다섯 줄이 쌓이지 않았다: ${JSON.stringify(full)}`);
      const overflow = expectStatus(
        await call(context, `/chat/conversations/${conversationId}/pending`, {
          method: "POST",
          token: context.tokens.dad,
          body: { text: "여섯째 글" },
        }),
        409,
        "여섯째 대기 메시지",
      ).json<{ code: string }>();
      expect(overflow.code === "PENDING_QUEUE_FULL", `상한 오류가 다르다: ${overflow.code}`);
      const stolen = expectStatus(
        await call(context, `/chat/conversations/${conversationId}/pending`, { token: context.tokens.kid }),
        404,
        "남의 대화의 대기 줄 조회",
      ).json<{ code: string }>();
      expect(stolen.code === "CONVERSATION_NOT_FOUND", `남의 대화 오류가 다르다: ${stolen.code}`);
      for (const item of full.items) await cancelPending(context, conversationId, item.id);
      context.hermes.releaseHeldRun();
      await within(fourth.turn.completed, 5_000, "상한 검사 스트림이 끝나지 않았다");
      expect((await pendingOf(context, conversationId)).items.length === 0, "다 취소했는데 대기 줄이 남았다");
    } finally {
      context.hermes.releaseHeldRun();
    }

    await orderWithDelegation(context);
  },
};

/** 대기 메시지가 끝난 위임 결과보다 먼저 가는 것을 본다. */
async function orderWithDelegation(context: Context): Promise<void> {
  step("대기 메시지가 끝난 위임 결과보다 먼저 간다");
  expectStatus(
    await call(context, "/admin/agents", {
      method: "POST",
      token: context.tokens.dad,
      body: {
        code: QUEUE_AGENT_CODE,
        name: "Queue Order",
        hermesProfile: CHAT_QUEUE_PROFILE,
        apiBaseUrl: `${context.hermesBaseUrl}/p/${CHAT_QUEUE_PROFILE}`,
        costMode: "SUBSCRIPTION",
        credentialScope: "SHARED_HOUSEHOLD",
        visibility: "GROUP",
        ownerEmail: null,
      },
    }),
    200,
    "대기열 검사 에이전트 등록",
  );

  let tokenId: number | undefined;
  try {
    const issued = expectStatus(
      await call(context, "/admin/agent-tokens", {
        method: "POST",
        token: context.tokens.dad,
        body: { profileName: CHAT_QUEUE_PROFILE, label: "chat-queue-e2e" },
      }),
      200,
      "대기열 검사 MCP 토큰 발급",
    ).json<{ id: number; token: string }>();
    tokenId = issued.id;

    const root = await holdTurn(context, "순서 검사 첫 글", QUEUE_AGENT_CODE);
    const conversationId = root.started.conversationId!;
    const rootExecutionId = root.started.executionId!;
    const rootRun = context.hermes.heldRun();
    if (rootRun === undefined) fail("붙잡은 뿌리 turn 의 run 이 없다");

    // 자식은 붙잡지 않으므로 곧 끝난다.
    const delegated = parsed<Status>(
      await callTool(
        context,
        issued.token,
        "agent_delegate",
        { agent_code: QUEUE_AGENT_CODE, task: "순서 검사에 맡긴 일" },
        contextFor(issued.token, "agent_delegate", rootRun.sessionId),
      ),
      "agent_delegate",
    );
    const child = await awaitStatus(
      async () => {
        const grown = await tree(context, rootExecutionId);
        const node = grown.root.children.find((candidate) => candidate.executionId === delegated.execution_id);
        return { execution_id: delegated.execution_id, status: node?.status ?? "MISSING" };
      },
      (value) => value.status === "SUCCEEDED",
      "맡긴 자식",
    );
    expect(child.status === "SUCCEEDED", `자식이 끝나지 않았다: ${JSON.stringify(child)}`);

    await enqueue(context, conversationId, "순서 검사 대기 글");
    context.hermes.releaseHeldRun();
    await within(root.turn.completed, 5_000, "뿌리 turn 의 스트림이 끝나지 않았다");
    const messages = await awaitMessages(
      context,
      conversationId,
      (list) => list.length >= 4 && lastRoles(list, 4) === "USER,ASSISTANT,SYSTEM,ASSISTANT",
      TURN_TIMEOUT_MS,
      "대기 글과 위임 결과의 순서",
    );
    expect(
      messages.at(-4)?.content === "순서 검사 대기 글",
      `대기 글이 위임 결과보다 앞에 오지 않았다: ${JSON.stringify(messages.slice(-4).map((m) => [m.role, m.content]))}`,
    );
  } finally {
    context.hermes.releaseHeldRun();
    const cleanupErrors: string[] = [];
    if (tokenId !== undefined) {
      const response = await call(context, `/admin/agent-tokens/${tokenId}`, { method: "DELETE", token: context.tokens.dad });
      if (response.status !== 200) cleanupErrors.push(`MCP 토큰 폐기 ${response.status}`);
    }
    const disabled = await call(context, `/admin/agents/${QUEUE_AGENT_CODE}`, {
      method: "PATCH",
      token: context.tokens.dad,
      body: { enabled: false, visibility: "GROUP", ownerEmail: null },
    });
    if (disabled.status !== 200) cleanupErrors.push(`에이전트 끄기 ${disabled.status}`);
    // 시나리오가 이미 실패했다면 그 실패를 가리지 않도록 정리 실패는 로그로만 남긴다.
    for (const message of cleanupErrors) step(`정리 실패: ${message}`);
  }
}

export const chatQueueRestartScenario: Scenario = {
  name: "재시작 뒤 대기열",

  async run(context) {
    // 앞 시나리오가 막아 둔 provider 가 남아 있으면 재시작 뒤 turn 이 거절된다.
    context.hermes.clearBlockedProviders();

    try {
      step("중지로 멈춘 대기 줄을 가진 대화를 만든다");
      const stoppedTurn = await holdTurn(context, "멈출 대화 첫 글", "dad");
      const stoppedConversation = stoppedTurn.started.conversationId!;
      await enqueue(context, stoppedConversation, "멈춰 둘 글");
      expectStatus(
        await call(context, `/chat/executions/${stoppedTurn.started.executionId}/stop`, {
          method: "POST",
          token: context.tokens.dad,
        }),
        202,
        "멈출 대화 turn 중지",
      );
      await within(stoppedTurn.turn.completed, 10_000, "중지 뒤 스트림이 끝나지 않았다");
      await awaitHeld(context, stoppedConversation);
      const stoppedBefore = (await messagesOf(context, stoppedConversation)).length;

      step("turn 이 도는 중에 대기 글을 쌓아 둔 대화를 만든다");
      const runningTurn = await holdTurn(context, "보낼 대화 첫 글", "dad");
      const sendConversation = runningTurn.started.conversationId!;
      await enqueue(context, sendConversation, "재시작 뒤 보낼 글");

      step("Control Plane 을 강제로 내리고 다시 띄운다");
      await context.restartControlPlane();

      step("붙잡힌 turn 에 다시 붙어 있는 동안에는 쌓인 글이 대기 줄에 남는다");
      const waiting = await pendingOf(context, sendConversation);
      expect(waiting.items.length === 1, `다시 붙은 turn 이 끝나기 전에 대기 줄이 달라졌다: ${JSON.stringify(waiting)}`);

      step("붙잡은 turn 을 놓으면 그 답이 먼저 오고, 그 뒤에 쌓인 글이 USER 로 저장되고 답이 온다");
      context.hermes.releaseHeldRun();
      await awaitMessages(
        context,
        sendConversation,
        (list) => {
          const index = list.findIndex((message) => message.role === "USER" && message.content === "재시작 뒤 보낼 글");
          return (
            index >= 0 &&
            list.slice(0, index).some((message) => message.role === "ASSISTANT") &&
            list.slice(index + 1).some((message) => message.role === "ASSISTANT")
          );
        },
        TURN_TIMEOUT_MS,
        "재시작 뒤 보낸 turn",
      );
      expect((await pendingOf(context, sendConversation)).items.length === 0, "보낸 뒤에도 대기 줄이 남았다");

      step("멈춘 대화의 대기 줄은 그대로 멈춰 있다");
      const stillHeld = await pendingOf(context, stoppedConversation);
      expect(stillHeld.held && stillHeld.items.length === 1, `멈춘 대기 줄이 달라졌다: ${JSON.stringify(stillHeld)}`);
      const after = await messagesOf(context, stoppedConversation);
      expect(after.length === stoppedBefore, `멈춘 대화에 새 메시지가 생겼다: ${stoppedBefore} -> ${after.length}`);
    } finally {
      context.hermes.releaseHeldRun();
    }
  },
};
