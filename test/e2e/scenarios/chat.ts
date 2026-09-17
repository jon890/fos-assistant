/** 대화 한 번이 Hermes 까지 갔다 오고, 남의 대화는 읽히지 않는 것을 본다. */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

/** 대화 두 번을 보낸다. 사용량 시나리오가 그 횟수로 합계를 검사한다. */
export const CHAT_TURNS = 2;

type Turn = { conversationId: number; executionId: number; assistantText: string };

export const chatScenario: Scenario = {
  name: "대화",

  async run(context) {
    step("보낸 문장이 Hermes 까지 간다");
    const question = "오늘 저녁 뭐 먹을까?";
    const first = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: question },
      }),
      200,
      "첫 대화",
    ).json<Turn>();
    expect(
      first.assistantText.includes(question),
      `보낸 문장이 Hermes 까지 가지 않았다: ${first.assistantText}`,
    );
    step("같은 대화를 이어서 보낸다");
    const next = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { conversationId: first.conversationId, text: "재료는 뭐가 필요해?" },
      }),
      200,
      "이어지는 대화",
    ).json<Turn>();
    expect(
      next.conversationId === first.conversationId,
      "이어 보낸 메시지가 다른 대화로 갔다",
    );

    step("다른 사용자는 그 대화를 읽지 못한다");
    expectStatus(
      await call(context, `/chat/conversations/${first.conversationId}/messages`, {
        token: context.tokens.kid,
      }),
      404,
      "남의 대화 조회",
    );
  },
};
