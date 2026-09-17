/** 스트림 조각은 화면에만 쓰고, 최종 상태의 답과 실행 기록을 저장하는지 본다. */
import { call, expect, expectStatus, step, type Response, type Scenario } from "../harness.ts";
import { readEventStream } from "../../../web/src/lib/stream.ts";

type ChatEvent = {
  type: "delta" | "tool" | "done" | "error";
  text?: string;
  conversationId?: number;
  messageId?: number;
  executionId?: number;
};

type Message = { id: number; role: "USER" | "ASSISTANT"; content: string; executionId: number | null };
type Execution = { id: number; conversationId: number; status: string };

async function events(response: Response): Promise<ChatEvent[]> {
  const received: ChatEvent[] = [];
  await readEventStream<ChatEvent>(
    new globalThis.Response(response.body, { headers: { "Content-Type": "text/event-stream" } }),
    (event) => received.push(event),
  );
  return received;
}

async function assertSaved(context: Parameters<Scenario["run"]>[0], done: ChatEvent, expected: string) {
  expect(done.conversationId !== undefined, "done 에 대화 번호가 없다");
  const messages = expectStatus(
    await call(context, `/chat/conversations/${done.conversationId}/messages`, {
      token: context.tokens.dad,
    }),
    200,
    "스트림 대화 이력 조회",
  ).json<Message[]>();
  const assistant = messages.find((message) => message.id === done.messageId);
  expect(assistant?.content === expected, `최종 상태의 답이 저장되지 않았다: ${assistant?.content}`);
  expect(assistant?.executionId === done.executionId, "저장된 메시지와 실행 기록이 연결되지 않았다");

  const executions = expectStatus(
    await call(context, "/usage/executions?limit=20", { token: context.tokens.dad }),
    200,
    "스트림 실행 기록 조회",
  ).json<Execution[]>();
  expect(
    executions.some((execution) =>
      execution.id === done.executionId &&
      execution.conversationId === done.conversationId &&
      execution.status === "SUCCEEDED"),
    "스트림 실행 기록이 남지 않았다",
  );
}

export const streamingScenario: Scenario = {
  name: "대화 스트리밍",

  async run(context) {
    step("keepalive 를 건너뛰고 delta 여러 개와 tool, done 을 받는다");
    const question = "스트림 정본 검사";
    const response = expectStatus(
      await call(context, "/chat/messages/stream", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: question, agentCode: "dad" },
      }),
      200,
      "스트림 대화",
    );
    const received = await events(response);
    expect(received.filter((item) => item.type === "delta").length === 2, "delta 두 개를 받지 못했다");
    expect(received.filter((item) => item.type === "tool").length === 2, "도구 사건을 받지 못했다");
    expect(received.every((item) => item.type !== (":" as ChatEvent["type"])), "keepalive 가 사건에 섞였다");
    const done = received.at(-1);
    expect(done?.type === "done", `마지막 사건이 done 이 아니다: ${JSON.stringify(done)}`);

    const streamed = received.filter((item) => item.type === "delta").map((item) => item.text).join("");
    const saved = `[fake hermes on profile dad] ${question}`;
    expect(streamed !== saved, "스트림 조각과 저장할 답이 달라야 검사가 성립한다");
    await assertSaved(context, done!, saved);

    step("Hermes 이벤트 스트림이 중간에 끝나도 최종 답과 실행 기록을 남긴다");
    const interrupted = await events(expectStatus(
      await call(context, "/chat/messages/stream", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "스트림 중단 검사", agentCode: "dad" },
      }),
      200,
      "중단된 스트림 대화",
    ));
    expect(interrupted.filter((item) => item.type === "delta").length === 1, "중단 전 delta 를 받지 못했다");
    const interruptedDone = interrupted.at(-1);
    expect(interruptedDone?.type === "done", "중단 뒤 최종 상태를 읽어 done 을 보내지 않았다");
    await assertSaved(
      context,
      interruptedDone!,
      "[fake hermes on profile dad] 스트림 중단 검사",
    );
  },
};
