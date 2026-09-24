/** 대화 이름과 삭제 경계, 시작 사건을 실제 HTTP 경로로 확인한다. */
import { call, expect, expectStatus, step, type Response, type Scenario } from "../harness.ts";
import { readEventStream } from "../../../web/src/lib/stream.ts";

type Conversation = { id: number; title: string };
type Turn = { conversationId: number };
type ChatEvent = { type: string; conversationId?: number; executionId?: number };

async function events(response: Response): Promise<ChatEvent[]> {
  const received: ChatEvent[] = [];
  await readEventStream<ChatEvent>(
    new globalThis.Response(response.body, { headers: { "Content-Type": "text/event-stream" } }),
    (event) => received.push(event),
  );
  return received;
}

export const conversationManageScenario: Scenario = {
  name: "대화 관리",

  async run(context) {
    step("이름을 바꾸고 지운 대화는 목록과 메시지와 보내기에서 빠진다");
    const created = expectStatus(await call(context, "/chat/messages", {
      method: "POST", token: context.tokens.dad,
      body: { text: "관리할 대화", agentCode: "dad" },
    }), 200, "관리할 대화 생성").json<Turn>();
    const id = created.conversationId;

    const renamed = expectStatus(await call(context, `/chat/conversations/${id}`, {
      method: "PATCH", token: context.tokens.dad, body: { title: "  바뀐 제목  " },
    }), 200, "대화 이름 바꾸기").json<Conversation>();
    expect(renamed.title === "바뀐 제목", `제목의 공백을 떼지 않았다: ${renamed.title}`);
    expectStatus(await call(context, `/chat/conversations/${id}`, {
      method: "DELETE", token: context.tokens.kid,
    }), 404, "남의 대화 삭제");
    expectStatus(await call(context, `/chat/conversations/${id}`, {
      method: "DELETE", token: context.tokens.dad,
    }), 204, "대화 삭제");

    const listed = expectStatus(await call(context, "/chat/conversations", {
      token: context.tokens.dad,
    }), 200, "삭제 뒤 목록").json<Conversation[]>();
    expect(!listed.some((conversation) => conversation.id === id), "지운 대화가 목록에 남았다");
    expectStatus(await call(context, `/chat/conversations/${id}/messages`, {
      token: context.tokens.dad,
    }), 404, "지운 대화 메시지");
    expectStatus(await call(context, "/chat/messages", {
      method: "POST", token: context.tokens.dad,
      body: { conversationId: id, text: "다시 보내기", agentCode: "dad" },
    }), 404, "지운 대화로 보내기");

    step("스트림은 실행을 만든 직후 대화 번호를 먼저 보낸다");
    const received = await events(expectStatus(await call(context, "/chat/messages/stream", {
      method: "POST", token: context.tokens.dad,
      body: { text: "시작 사건 검사", agentCode: "dad" },
    }), 200, "시작 사건 스트림"));
    expect(received[0]?.type === "started", `첫 사건이 started 가 아니다: ${received[0]?.type}`);
    const done = received.at(-1);
    expect(done?.type === "done", `마지막 사건이 done 이 아니다: ${done?.type}`);
    expect(received[0]?.conversationId === done?.conversationId,
      "started 와 done 의 대화 번호가 다르다");
    expect(received[0]?.executionId === done?.executionId,
      "started 와 done 의 실행 번호가 다르다");
  },
};
