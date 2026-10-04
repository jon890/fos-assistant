/**
 * 승인이 필요한 커넥터 호출이 승인 요청을 만들면 그 사용자의 알림이 생기고, 사용자 단위 SSE 로 알려지는지 전체 흐름으로 본다.
 *
 * <p>승인 요청은 커넥터 도구 정책 시나리오와 같은 방법으로 만든다. 앞의 시나리오가 해제로 끝나므로 여기서 다시 등록하고
 * 끝에서 해제한다. 앞 시나리오가 남긴 알림이 있을 수 있어 수를 세지 않고 이 시나리오가 만든 줄을 찾아 본다.
 */
import { call, expect, expectStatus, fail, step, type Context, type Scenario } from "../harness.ts";
import { CONNECTOR_TOOL_PROBE, DEMO_CONNECTOR, DEMO_TOKEN_OK } from "../fake-hermes.ts";
import { readEventStream } from "../../../web/src/lib/stream.ts";

type ConnectionView = { status: string; agentCode: string | null };
type Turn = { conversationId: string; assistantText: string };
type NotificationView = {
  id: string;
  kind: string;
  title: string;
  body: string;
  targetType: string | null;
  targetId: string | null;
  createdAt: string;
  readAt: string | null;
};
type NotificationPageView = { items: NotificationView[]; nextCursor: string | null; unreadCount: number };
type NotificationEvent = { type: string; notificationId?: string; unreadCount: number };

const CONNECTION = `/connections/${DEMO_CONNECTOR.id}`;
const PREFIX = `mcp__${DEMO_CONNECTOR.mcp_server}__`;
const SSE_TIMEOUT_MS = 10_000;

/** 연결용 에이전트에게 승인이 필요한 도구 호출을 시켜 승인 요청을 하나 만들고, 그 요청이 나온 대화를 돌려준다. */
async function requestApproval(context: Context, agentCode: string, text: string): Promise<string> {
  const hermesTool = `${PREFIX}write_note`;
  const turn = expectStatus(
    await call(context, "/chat/messages", {
      method: "POST",
      token: context.tokens.dad,
      body: { text: `${CONNECTOR_TOOL_PROBE}\n${hermesTool} ${JSON.stringify({ text })}`, agentCode },
    }),
    200,
    "승인이 필요한 도구 호출 대화",
  ).json<Turn>();
  const line = turn.assistantText.split("\n").find((candidate) => candidate.startsWith(`${hermesTool}: `));
  expect(line?.startsWith(`${hermesTool}: block `) === true, `write_note 가 승인 요청으로 막히지 않았다: ${turn.assistantText}`);
  return turn.conversationId;
}

async function notificationsOf(context: Context): Promise<NotificationPageView> {
  return expectStatus(
    await call(context, "/notifications?limit=100", { token: context.tokens.dad }),
    200,
    "알림 목록",
  ).json<NotificationPageView>();
}

/** 알림 SSE 를 열어 둔다. 받은 사건을 차례로 모으고, 닫으면 읽기를 멈춘다. */
async function openNotificationStream(
  context: Context,
): Promise<{ events: NotificationEvent[]; close: () => Promise<void> }> {
  const controller = new AbortController();
  const response = await fetch(`${context.api}/notifications/events`, {
    headers: { Authorization: `Bearer ${context.tokens.dad}` },
    signal: controller.signal,
  });
  expect(response.status === 200, `알림 SSE 를 열지 못했다: ${response.status}`);
  expect(
    (response.headers.get("content-type") ?? "").startsWith("text/event-stream"),
    `알림 SSE 의 Content-Type 이 다르다: ${response.headers.get("content-type")}`,
  );
  const events: NotificationEvent[] = [];
  const reading = readEventStream<NotificationEvent>(response, (event) => {
    events.push(event);
  }).catch(() => undefined);
  return {
    events,
    async close() {
      controller.abort();
      await reading;
    },
  };
}

export const notificationsScenario: Scenario = {
  name: "알림",

  async run(context) {
    context.hermes.setConnectorPolicy(`${context.api.replace(/\/api\/v1$/, "")}/internal/hermes/connector-policy`);
    let profile: string | undefined;
    let stream: { events: NotificationEvent[]; close: () => Promise<void> } | undefined;
    let failed = false;
    try {
      step("시험 커넥터를 다시 등록하고 연결을 확인해 READY 로 만든다");
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
      profile = installLine!.split(" ")[1]!;
      const ready = expectStatus(
        await call(context, `${CONNECTION}/check`, { method: "POST", token: context.tokens.dad }), 200, "연결 확인",
      ).json<ConnectionView>();
      expect(ready.status === "READY" && ready.agentCode !== null, `READY 가 아니다: ${JSON.stringify(ready)}`);
      const agentCode = ready.agentCode!;

      step("승인 요청을 만들면 그 사용자의 알림 목록에 그 대화를 가리키는 APPROVAL_REQUESTED 가 생긴다");
      const firstConversation = await requestApproval(context, agentCode, "알림으로 알릴 글");
      const listed = await notificationsOf(context);
      const requested = listed.items.find(
        (item) => item.kind === "APPROVAL_REQUESTED" && item.targetId === firstConversation,
      );
      expect(
        requested !== undefined,
        `그 대화의 APPROVAL_REQUESTED 알림이 없다: ${JSON.stringify(listed.items.slice(0, 5))}`,
      );
      expect(
        requested!.targetType === "CONVERSATION" && requested!.readAt === null
          && requested!.title === "승인을 기다리는 요청이 있어요" && requested!.body === "「메모 쓰기」",
        `알림 칸이 다르다: ${JSON.stringify(requested)}`,
      );
      expect(listed.unreadCount >= 1, `읽지 않은 수가 0 이다: ${JSON.stringify(listed)}`);

      step("다른 사용자는 그 알림을 읽음으로 표시하지 못한다");
      const foreign = expectStatus(
        await call(context, `/notifications/${requested!.id}/read`, { method: "POST", token: context.tokens.kid }),
        404,
        "다른 사용자의 읽음 표시",
      );
      expect(
        foreign.json<{ code: string }>().code === "NOTIFICATION_NOT_FOUND",
        `오류 코드가 다르다\n${foreign.body}`,
      );
      const untouched = (await notificationsOf(context)).items.find((item) => item.id === requested!.id);
      expect(untouched?.readAt === null, `남이 읽음으로 표시한 것이 반영됐다: ${JSON.stringify(untouched)}`);

      step("모두 읽음 뒤 읽지 않은 수가 0 이다");
      const readAll = expectStatus(
        await call(context, "/notifications/read-all", { method: "POST", token: context.tokens.dad }),
        200,
        "모두 읽음",
      ).json<{ unreadCount: number }>();
      expect(readAll.unreadCount === 0, `모두 읽음의 응답이 0 이 아니다: ${JSON.stringify(readAll)}`);
      const afterReadAll = (await notificationsOf(context)).items.find((item) => item.id === requested!.id);
      expect(afterReadAll?.readAt !== null, `모두 읽음 뒤에도 그 알림을 읽지 않았다: ${JSON.stringify(afterReadAll)}`);

      step("알림 SSE 를 열어 둔 채 승인 요청을 하나 더 만들면 그 요청의 created 사건을 받는다");
      stream = await openNotificationStream(context);
      const secondConversation = await requestApproval(context, agentCode, "SSE 로 알릴 글");
      // 만료 정리가 1초마다 돌아 앞선 요청의 APPROVAL_EXPIRED 사건이 먼저 올 수 있다. 받은 번호를 목록에서 찾아 대조한다.
      const deadline = Date.now() + SSE_TIMEOUT_MS;
      let matched: NotificationView | undefined;
      while (matched === undefined && Date.now() < deadline) {
        const createdIds = stream.events
          .filter((event) => event.type === "created" && event.notificationId !== undefined)
          .map((event) => event.notificationId!);
        if (createdIds.length > 0) {
          const items = (await notificationsOf(context)).items;
          matched = items.find(
            (item) => createdIds.includes(item.id)
              && item.kind === "APPROVAL_REQUESTED" && item.targetId === secondConversation,
          );
        }
        if (matched === undefined) await new Promise((resolve) => setTimeout(resolve, 100));
      }
      if (matched === undefined) {
        fail(`${SSE_TIMEOUT_MS / 1000}초 안에 새 승인 요청의 created 사건이 오지 않았다: ${JSON.stringify(stream.events)}`);
      }
      const createdEvent = stream.events.find((event) => event.notificationId === matched!.id)!;
      expect(createdEvent.unreadCount >= 1, `created 사건의 읽지 않은 수가 0 이다: ${JSON.stringify(createdEvent)}`);
    } catch (error) {
      failed = true;
      throw error;
    } finally {
      // 어디서 실패해도 SSE 와 대역과 연결을 되돌린다. 정리가 실패해도 원래 실패를 가리지 않는다.
      await stream?.close();
      if (profile !== undefined) context.hermes.setPolicyHook(profile, true);
      try {
        expectStatus(await call(context, CONNECTION, { method: "DELETE", token: context.tokens.dad }), 200, "해제");
      } catch (cleanupError) {
        if (!failed) throw cleanupError;
      }
    }
  },
};
