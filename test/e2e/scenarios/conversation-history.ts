/** 대화 목록과 메시지 이력을 사용자의 소유권과 함께 검사한다. */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

type Turn = { conversationId: string };
type Conversation = { id: string; title: string };
type Message = { role: "USER" | "ASSISTANT"; content: string; senderName: string | null };

export const conversationHistoryScenario: Scenario = {
  name: "대화 이력",

  async run(context) {
    step("대화를 두 번 만들면 최근 대화부터 목록에 나온다");
    const older = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "첫 번째 새 대화", agentCode: "dad" },
      }),
      200,
      "첫 번째 새 대화",
    ).json<Turn>();
    const latest = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "두 번째 새 대화", agentCode: "dad" },
      }),
      200,
      "두 번째 새 대화",
    ).json<Turn>();

    const conversations = expectStatus(
      await call(context, "/chat/conversations", { token: context.tokens.dad }),
      200,
      "대화 목록 조회",
    ).json<{ items: Conversation[]; nextCursor: string | null }>().items;
    expect(
      conversations.findIndex((conversation) => conversation.id === latest.conversationId) <
        conversations.findIndex((conversation) => conversation.id === older.conversationId),
      "최근 대화가 앞에 오지 않았다",
    );

    step("목록은 cursor 로 한 줄씩 이어 읽어도 같은 순서로 나온다");
    const head = expectStatus(
      await call(context, "/chat/conversations?limit=1", { token: context.tokens.dad }),
      200,
      "목록 첫 쪽",
    ).json<{ items: Conversation[]; nextCursor: string | null }>();
    expect(head.items.length === 1, `첫 쪽이 한 줄이 아니다: ${head.items.length}`);
    expect(head.items[0]?.id === conversations[0]?.id, "첫 쪽의 줄이 목록의 맨 앞이 아니다");
    expect(head.nextCursor !== null, "다음 쪽이 있는데 nextCursor 가 없다");
    const next = expectStatus(
      await call(context, `/chat/conversations?limit=1&cursor=${encodeURIComponent(head.nextCursor!)}`, {
        token: context.tokens.dad,
      }),
      200,
      "목록 둘째 쪽",
    ).json<{ items: Conversation[]; nextCursor: string | null }>();
    expect(next.items[0]?.id === conversations[1]?.id, "둘째 쪽의 줄이 목록의 둘째와 다르다");

    step("메시지는 보낸 순서대로 오고 사용자 이름을 포함한다");
    const messages = expectStatus(
      await call(context, `/chat/conversations/${latest.conversationId}/messages`, {
        token: context.tokens.dad,
      }),
      200,
      "메시지 이력 조회",
    ).json<Message[]>();
    expect(messages.length === 2, `메시지 2건을 기대했는데 ${messages.length}건이다`);
    expect(
      messages[0]?.role === "USER" &&
        messages[0].content === "두 번째 새 대화" &&
        messages[0].senderName === "dad@example.com",
      `사용자 메시지의 순서나 이름이 다르다: ${JSON.stringify(messages[0])}`,
    );
    expect(
      messages[1]?.role === "ASSISTANT" && messages[1].senderName === null,
      `비서 메시지의 순서나 이름이 다르다: ${JSON.stringify(messages[1])}`,
    );

    step("다른 사용자는 메시지 이력을 읽지 못한다");
    const refused = expectStatus(
      await call(context, `/chat/conversations/${latest.conversationId}/messages`, {
        token: context.tokens.kid,
      }),
      404,
      "남의 메시지 이력 조회",
    );
    expect(
      refused.json<{ code: string }>().code === "CONVERSATION_NOT_FOUND",
      `기대한 오류 코드가 아니다: ${refused.body}`,
    );
  },
};
