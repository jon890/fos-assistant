/** 마지막 답을 판으로 쌓아 다시 생성하는 경로를 검사한다. */
import { call, expect, expectStatus, step, type Response, type Scenario } from "../harness.ts";
import { readEventStream } from "../../../web/src/lib/stream.ts";

type ChatEvent = {
  type: string;
  conversationId?: number;
  messageId?: number;
};

type Message = {
  id: number;
  role: "USER" | "ASSISTANT";
  content: string;
  replacesMessageId: number | null;
};

async function events(response: Response): Promise<ChatEvent[]> {
  const received: ChatEvent[] = [];
  await readEventStream<ChatEvent>(
    new globalThis.Response(response.body, { headers: { "Content-Type": "text/event-stream" } }),
    (event) => received.push(event),
  );
  return received;
}

async function stream(
  context: Parameters<Scenario["run"]>[0],
  body: Record<string, unknown>,
  description: string,
): Promise<ChatEvent[]> {
  return events(expectStatus(await call(context, "/chat/messages/stream", {
    method: "POST", token: context.tokens.dad, body,
  }), 200, description));
}

export const regenerateScenario: Scenario = {
  name: "다시 생성",

  async run(context) {
    step("마지막 답을 다시 만들면 새 답이 이전 답을 가리킨다");
    const question = "다시 생성 원래 질문";
    const originalEvents = await stream(context, { text: question, agentCode: "dad" }, "원래 스트림 대화");
    const original = originalEvents.at(-1);
    expect(original?.type === "done" && original.conversationId !== undefined && original.messageId !== undefined,
      `원래 대화의 done 사건이 다르다: ${JSON.stringify(original)}`);

    const regeneratedEvents = await events(expectStatus(await call(
      context,
      `/chat/conversations/${original.conversationId}/regenerate/stream`,
      { method: "POST", token: context.tokens.dad },
    ), 200, "답 다시 생성"));
    const regenerated = regeneratedEvents.at(-1);
    expect(regenerated?.type === "done" && regenerated.messageId !== undefined,
      `다시 생성의 마지막 사건이 done 이 아니다: ${JSON.stringify(regenerated)}`);
    expect(
      context.hermes.lastSubmittedInstructions()?.endsWith(
        "사용자가 바로 앞 질문에 대한 답을 다시 받기를 원한다. 앞의 답을 되풀이하지 말고 새로 답한다.",
      ) === true,
      "다시 생성 안내 문구를 Hermes instructions 끝에 붙이지 않았다",
    );

    const regeneratedHistory = expectStatus(await call(
      context,
      `/chat/conversations/${original.conversationId}/messages`,
      { token: context.tokens.dad },
    ), 200, "다시 생성 뒤 메시지 조회").json<Message[]>();
    const originalAnswer = regeneratedHistory.find((message) => message.id === original.messageId);
    const regeneratedAnswer = regeneratedHistory.find((message) => message.id === regenerated.messageId);
    expect(originalAnswer?.role === "ASSISTANT", "원래 답을 찾지 못했다");
    expect(regeneratedAnswer?.replacesMessageId === original.messageId,
      `새 답이 원래 답을 가리키지 않는다: ${JSON.stringify(regeneratedAnswer)}`);
    expect(regeneratedHistory.filter((message) => message.role === "USER").length === 1,
      "다시 생성이 사용자 메시지를 새로 저장했다");

    step("다른 사용자는 다시 생성할 수 없다");
    const denied = expectStatus(await call(
      context,
      `/chat/conversations/${original.conversationId}/regenerate/stream`,
      { method: "POST", token: context.tokens.kid },
    ), 404, "남의 대화 다시 생성").json<{ code: string }>();
    expect(denied.code === "CONVERSATION_NOT_FOUND", `남의 대화 오류가 다르다: ${denied.code}`);
  },
};
