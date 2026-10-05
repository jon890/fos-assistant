/**
 * 승인한 커넥터 호출의 결과 전달이 실패한 뒤 다시 전달하면 저장된 결과만으로 답이 생기는지 전체 흐름으로 본다.
 *
 * <p>대역 Hermes 가 run 제출을 거절하는 동안 승인하면 결과 전달 묶음이 FAILED 로 남는다. 거절을 푼 뒤 다시 전달하면
 * 커넥터 서버에 닿는 호출 없이 부모 에이전트의 답이 이어진다. 앞의 시나리오처럼 연결을 등록하고 끝에서 해제한다.
 */
import { call, expect, expectStatus, fail, step, type Context, type Response, type Scenario } from "../harness.ts";
import { readEventStream } from "../../../web/src/lib/stream.ts";
import { CONNECTOR_TOOL_PROBE, DEMO_CONNECTOR, DEMO_TOKEN_OK, type ConnectorToolCall } from "../fake-hermes.ts";

type ConnectionView = { status: string; agentCode: string | null };
type Turn = { conversationId: string; assistantText: string };
type ActionView = { actionId: string; status: string };
type Message = {
  id: number;
  role: "USER" | "ASSISTANT" | "SYSTEM";
  content: string;
  delivery: { id: number; status: string } | null;
};
type ChatEvent = { type: string; code?: string };
type AttentionItem = { conversationId: string | null; why: { trigger: string; signals: string[] } };
type AttentionView = { cards: { key: string; items: AttentionItem[] }[] };

const CONNECTION = `/connections/${DEMO_CONNECTOR.id}`;
const PREFIX = `mcp__${DEMO_CONNECTOR.mcp_server}__`;
const RETRY_NOTICE = "맡긴 일의 결과를 다시 전해요";
const NOTE_TEXT = "다시 전달 시험 메모";

async function events(response: Response): Promise<ChatEvent[]> {
  const received: ChatEvent[] = [];
  await readEventStream<ChatEvent>(
    new globalThis.Response(response.body, { headers: { "Content-Type": "text/event-stream" } }),
    (event) => received.push(event),
  );
  return received;
}

async function messagesOf(context: Context, conversationId: string): Promise<Message[]> {
  return expectStatus(
    await call(context, `/chat/conversations/${conversationId}/messages`, { token: context.tokens.dad }),
    200,
    "대화 이력 조회",
  ).json<Message[]>();
}

/** 대화의 메시지 목록을 조건이 참이 될 때까지 다시 읽는다. 고정 시간 sleep 으로 기다리지 않는다. */
async function awaitMessages(
  context: Context,
  conversationId: string,
  predicate: (messages: Message[]) => boolean,
  what: string,
): Promise<Message[]> {
  const timeoutMs = 15_000;
  const deadline = Date.now() + timeoutMs;
  let last: Message[] = [];
  while (Date.now() < deadline) {
    last = await messagesOf(context, conversationId);
    if (predicate(last)) return last;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  fail(`${what}: ${timeoutMs / 1000}초 안에 기대한 이력이 되지 않았다. 마지막 목록: ${JSON.stringify(last.map((m) => [m.role, m.content, m.delivery]))}`);
}

/** 지금 화면의 실패 카드에서 그 대화의 항목을 찾는다. */
async function failureOf(context: Context, conversationId: string): Promise<AttentionItem | undefined> {
  const view = expectStatus(await call(context, "/attention", { token: context.tokens.dad }), 200, "지금 화면")
    .json<AttentionView>();
  return view.cards.find((card) => card.key === "failures")?.items.find((item) => item.conversationId === conversationId);
}

function requestNumber(answer: string): string | undefined {
  return /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/.exec(answer)?.[0];
}

function endsWithFailedDelivery(messages: Message[]): boolean {
  const last = messages.at(-1);
  return last?.role === "SYSTEM" && last.delivery?.status === "FAILED";
}

export const deliveryRetryScenario: Scenario = {
  name: "결과 다시 전달",

  async run(context) {
    context.hermes.setConnectorPolicy(`${context.api.replace(/\/api\/v1$/, "")}/internal/hermes/connector-policy`);
    let failed = false;
    try {
      step("연결을 등록하고 write_note 승인 줄을 만든다");
      const requestsAtRegister = context.hermes.connectorRequests().length;
      expectStatus(
        await call(context, CONNECTION, {
          method: "POST", token: context.tokens.dad, body: { values: { token: DEMO_TOKEN_OK, scope: "a" } },
        }),
        200,
        "등록",
      );
      const installLine = context.hermes.connectorRequests().slice(requestsAtRegister)
        .find((line) => /^install \S+ on$/.test(line));
      expect(installLine !== undefined, "등록 동안 설치 요청이 없었다");
      const profile = installLine!.split(" ")[1]!;
      const ready = expectStatus(
        await call(context, `${CONNECTION}/check`, { method: "POST", token: context.tokens.dad }), 200, "연결 확인",
      ).json<ConnectionView>();
      expect(ready.status === "READY" && ready.agentCode !== null, `READY 가 아니다: ${JSON.stringify(ready)}`);
      const mine = (): ConnectorToolCall[] =>
        context.hermes.connectorToolCalls().filter((entry) => entry.profile === profile);

      const turn = expectStatus(
        await call(context, "/chat/messages", {
          method: "POST",
          token: context.tokens.dad,
          body: {
            text: `${CONNECTOR_TOOL_PROBE}\n${PREFIX}write_note ${JSON.stringify({ text: NOTE_TEXT })}`,
            agentCode: ready.agentCode,
          },
        }),
        200,
        "write_note 호출 대화",
      ).json<Turn>();
      const line = turn.assistantText.split("\n").find((candidate) => candidate.startsWith(`${PREFIX}write_note: `));
      const actionId = line === undefined ? undefined : requestNumber(line);
      expect(line?.includes("block ") === true && actionId !== undefined, `write_note 가 승인 요청 번호와 함께 막히지 않았다: ${turn.assistantText}`);
      const conversationId = turn.conversationId;

      step("Hermes 가 거절하는 동안 승인하면 결과 전달 묶음이 FAILED 로 남는다");
      context.hermes.busy();
      const approved = expectStatus(
        await call(context, `/connector-actions/${actionId}/approve`, {
          method: "POST", token: context.tokens.dad, body: { grant: null },
        }),
        200,
        "승인",
      ).json<ActionView>();
      expect(approved.status === "SUCCEEDED", `승인한 줄이 SUCCEEDED 가 아니다: ${JSON.stringify(approved)}`);
      const failedHistory = await awaitMessages(context, conversationId, endsWithFailedDelivery, "전달 실패의 알림 줄");
      const deliveryId = failedHistory.at(-1)!.delivery!.id;
      const failure = await failureOf(context, conversationId);
      expect(
        failure !== undefined && failure.why.trigger === "DELIVERY_FAILED" && failure.why.signals.includes("DELIVERY_NOT_DONE"),
        `결과 전달 실패가 실패 카드에 DELIVERY_FAILED 로 보이지 않는다: ${JSON.stringify(failure)}`,
      );
      const noticeId = failedHistory.at(-1)!.id;
      const callsAfterApproval = mine().length;
      expect(
        mine().filter((entry) =>
          entry.via === "execute" && entry.hermesTool === `${PREFIX}write_note` && entry.argsJson.includes(NOTE_TEXT)).length === 1,
        `승인이 write_note 를 한 번 실행하지 않았다: ${JSON.stringify(mine())}`,
      );

      step("거절이 이어지는 동안 다시 전달하면 HERMES_BUSY 로 끝나고 묶음은 FAILED 다");
      const retryPath = `/chat/conversations/${conversationId}/deliveries/${deliveryId}/retry/stream`;
      const busyEvents = await events(expectStatus(
        await call(context, retryPath, { method: "POST", token: context.tokens.dad }), 200, "거절 중 다시 전달",
      ));
      const busyEnd = busyEvents.at(-1);
      expect(
        busyEnd?.type === "error" && busyEnd.code === "HERMES_BUSY",
        `마지막 사건이 HERMES_BUSY 의 error 가 아니다: ${JSON.stringify(busyEvents)}`,
      );
      await awaitMessages(context, conversationId, endsWithFailedDelivery, "다시 실패한 알림 줄");

      step("거절을 풀고 다시 전달하면 알림 줄과 답이 이어지고 묶음이 DELIVERED 가 된다");
      context.hermes.clearBusy();
      const retryEvents = await events(expectStatus(
        await call(context, retryPath, { method: "POST", token: context.tokens.dad }), 200, "다시 전달",
      ));
      const types = retryEvents.map((event) => event.type);
      expect(
        types[0] === "system" && types.includes("started") && types.indexOf("started") > 0 && types.at(-1) === "done",
        `사건이 system, started 를 거쳐 done 으로 끝나지 않았다: ${JSON.stringify(types)}`,
      );
      const delivered = await awaitMessages(
        context,
        conversationId,
        (messages) => messages.length >= 2
          && messages.at(-2)!.role === "SYSTEM" && messages.at(-2)!.delivery?.status === "DELIVERED"
          && messages.at(-1)!.role === "ASSISTANT",
        "다시 전달의 알림 줄과 답",
      );
      const retryNotice = delivered.at(-2)!;
      expect(retryNotice.content === RETRY_NOTICE, `다시 전달의 알림 줄 글이 다르다: ${retryNotice.content}`);
      expect(retryNotice.delivery!.id === deliveryId, `같은 묶음이 아니다: ${JSON.stringify(retryNotice.delivery)}`);
      const firstNotice = delivered.find((message) => message.id === noticeId);
      expect(firstNotice?.role === "SYSTEM" && firstNotice.delivery === null,
        `첫 알림 줄에 delivery 가 남았다: ${JSON.stringify(firstNotice)}`);
      const resolved = await failureOf(context, conversationId);
      expect(resolved === undefined, `다시 전달이 끝났는데 실패 카드에 남았다: ${JSON.stringify(resolved)}`);

      step("마지막 입력에 저장된 결과가 실리고 커넥터 호출은 늘지 않는다");
      const input = context.hermes.lastSubmittedInput() ?? "";
      expect(
        input.includes("승인한 동작의 결과가 도착했다.") && input.includes("<external-data>"),
        `다시 전달의 입력에 승인 결과가 실리지 않았다: ${input}`,
      );
      expect(
        mine().length === callsAfterApproval,
        `다시 전달이 커넥터 호출을 늘렸다: ${JSON.stringify(mine())}`,
      );

      step("전달이 끝난 묶음은 다시 전달할 수 없고 다른 사용자는 부르지 못한다");
      const again = await events(expectStatus(
        await call(context, retryPath, { method: "POST", token: context.tokens.dad }), 200, "끝난 묶음의 다시 전달",
      ));
      const againEnd = again.at(-1);
      expect(
        againEnd?.type === "error" && againEnd.code === "DELIVERY_NOT_RETRYABLE",
        `끝난 묶음의 마지막 사건이 DELIVERY_NOT_RETRYABLE 이 아니다: ${JSON.stringify(again)}`,
      );
      expectStatus(
        await call(context, retryPath, { method: "POST", token: context.tokens.kid }), 404, "다른 사용자의 다시 전달",
      );
    } catch (error) {
      failed = true;
      throw error;
    } finally {
      // 어디서 실패해도 대역과 연결을 되돌린다. 정리가 실패해도 원래 실패를 가리지 않는다.
      context.hermes.clearBusy();
      try {
        expectStatus(await call(context, CONNECTION, { method: "DELETE", token: context.tokens.dad }), 200, "해제");
      } catch (cleanupError) {
        if (!failed) throw cleanupError;
      }
    }
  },
};
